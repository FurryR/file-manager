use std::io;
use std::collections::HashMap;
use std::os::fd::{FromRawFd, RawFd};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use tokio::fs;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, oneshot, Mutex};

use crate::proto::{ReadSourceResponse, Response, StreamProgress};
use crate::protocol::OutboundResponse;

pub type SourceReadBroker = Arc<Mutex<HashMap<u64, (u64, oneshot::Sender<ReadSourceResponse>)>>>;

static NEXT_SOURCE_READ_ID: AtomicU64 = AtomicU64::new(1);

/// Streaming copy with progress events. Sends [StreamProgress] for each
/// chunk via [tx]. Cancelled by setting [cancel_flag] to true.
pub async fn copy(
    from: &str,
    to: &str,
    req_id: u64,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
    cancel_flag: &Arc<AtomicBool>,
) -> io::Result<()> {
    if cancel_flag.load(Ordering::Relaxed) {
        return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
    }

    let src = Path::new(from);
    let dst = Path::new(to);

    if !src.is_dir() {
        copy_file(src, dst, req_id, tx, cancel_flag, from).await?;
        return Ok(());
    }

    fs::create_dir_all(dst).await?;
    let mut entries = fs::read_dir(src).await?;
    while let Some(entry) = entries.next_entry().await? {
        if cancel_flag.load(Ordering::Relaxed) {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
        }

        let entry_path = entry.path();
        let child_to = dst.join(entry.file_name());
        let entry_str = entry_path.to_string_lossy().into_owned();
        let child_str = child_to.to_string_lossy().into_owned();
        let entry_name = entry_path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();

        if entry_path.is_dir() {
            Box::pin(copy(&entry_str, &child_str, req_id, tx, cancel_flag)).await?;
        } else {
            copy_file(&entry_path, &child_to, req_id, tx, cancel_flag, &entry_name).await?;
        }
    }
    Ok(())
}

pub async fn copy_from_fd(
    source_fd: RawFd,
    to: &str,
    req_id: u64,
    source_size: u64,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
    cancel_flag: &Arc<AtomicBool>,
) -> io::Result<u64> {
    let source = unsafe { std::fs::File::from_raw_fd(source_fd) };
    let source = tokio::fs::File::from_std(source);
    copy_reader(source, to, req_id, source_size, tx, cancel_flag).await
}

pub async fn copy_from_virtual(
    to: &str,
    req_id: u64,
    source_size: u64,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
    cancel_flag: &Arc<AtomicBool>,
    broker: &SourceReadBroker,
) -> io::Result<u64> {
    let dst_path = Path::new(to);
    if let Some(parent) = dst_path.parent() {
        fs::create_dir_all(parent).await?;
    }
    let mut dst = fs::File::create(dst_path).await?;
    let mut offset = 0u64;
    let mut copied = 0u64;

    loop {
        let read_id = NEXT_SOURCE_READ_ID.fetch_add(1, Ordering::Relaxed);
        let (response_tx, response_rx) = oneshot::channel();
        {
            let mut pending = broker.lock().await;
            if cancel_flag.load(Ordering::Relaxed) {
                drop(pending);
                dst.flush().await?;
                return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
            }
            pending.insert(read_id, (req_id, response_tx));
        }
        let request = Response {
            id: req_id,
            ok: true,
            read_source_request: Some(crate::proto::ReadSourceRequest {
                request_id: read_id,
                offset,
                length: 256 * 1024,
            }),
            ..Response::default()
        };
        if tx.send(OutboundResponse::plain(request)).is_err() {
            broker.lock().await.remove(&read_id);
            return Err(io::Error::new(io::ErrorKind::BrokenPipe, "response channel closed"));
        }
        let response = response_rx.await;
        if cancel_flag.load(Ordering::Relaxed) {
            dst.flush().await?;
            return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
        }
        let response = response.map_err(|_| {
            io::Error::new(io::ErrorKind::BrokenPipe, "source read response missing")
        })?;
        if !response.error.is_empty() {
            return Err(io::Error::new(io::ErrorKind::Other, response.error));
        }
        let data = response.data;
        if data.is_empty() {
            break;
        }
        dst.write_all(&data).await?;
        offset += data.len() as u64;
        copied += data.len() as u64;
        let _ = tx.send(progress(req_id, source_size, copied, to));
        if response.eof {
            break;
        }
    }
    dst.flush().await?;
    Ok(copied)
}

async fn copy_reader<R: tokio::io::AsyncRead + Unpin>(
    mut source: R,
    to: &str,
    req_id: u64,
    total: u64,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
    cancel_flag: &Arc<AtomicBool>,
) -> io::Result<u64> {
    let dst_path = Path::new(to);
    if let Some(parent) = dst_path.parent() {
        fs::create_dir_all(parent).await?;
    }
    let mut dst = fs::File::create(dst_path).await?;
    let mut buf = vec![0u8; 256 * 1024];
    let mut copied = 0u64;
    loop {
        if cancel_flag.load(Ordering::Relaxed) {
            // Tokio may still be completing the preceding buffered file write.
            dst.flush().await?;
            return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
        }
        let n = source.read(&mut buf).await?;
        if n == 0 { break; }
        dst.write_all(&buf[..n]).await?;
        copied += n as u64;
        let _ = tx.send(progress(req_id, total, copied, to));
    }
    dst.flush().await?;
    Ok(copied)
}

fn progress(req_id: u64, total: u64, copied: u64, name: &str) -> OutboundResponse {
    OutboundResponse::plain(Response {
        id: req_id,
        ok: true,
        stream_progress: Some(StreamProgress {
            finished: false,
            total_bytes: total,
            copied_bytes: copied,
            current_name: Path::new(name).file_name()
                .map(|value| value.to_string_lossy().into_owned())
                .unwrap_or_else(|| name.to_string()),
        }),
        ..Response::default()
    })
}

async fn copy_file(
    src: &Path,
    dst: &Path,
    req_id: u64,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
    cancel_flag: &Arc<AtomicBool>,
    display_name: &str,
) -> io::Result<()> {
    let mut src_file = fs::File::open(src).await?;
    if let Some(parent) = dst.parent() {
        fs::create_dir_all(parent).await?;
    }
    let mut dst_file = fs::File::create(dst).await?;
    let total = src_file.metadata().await?.len();
    let mut buf = vec![0u8; 256 * 1024];
    let mut copied: u64 = 0;

    loop {
        if cancel_flag.load(Ordering::Relaxed) {
            dst_file.flush().await?;
            return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
        }

        let n = src_file.read(&mut buf).await?;
        if n == 0 { break; }
        dst_file.write_all(&buf[..n]).await?;
        copied += n as u64;

        let _ = tx.send(OutboundResponse::plain(Response {
            id: req_id, ok: true,
            stream_progress: Some(StreamProgress {
                finished: false,
                total_bytes: total,
                copied_bytes: copied,
                current_name: display_name.to_string(),
            }),
            ..Response::default()
        }));
    }

    dst_file.flush().await?;
    Ok(())
}

pub async fn delete_recursive(path: &str) -> io::Result<()> {
    let p = Path::new(path);
    if p.is_dir() {
        fs::remove_dir_all(p).await
    } else {
        fs::remove_file(p).await
    }
}
