use std::io;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::fs;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

use crate::proto::{Response, StreamProgress};

/// Streaming copy with progress events. Sends [StreamProgress] for each
/// chunk via [tx]. Cancelled by setting [cancel_flag] to true.
pub async fn copy(
    from: &str,
    to: &str,
    req_id: u64,
    tx: &mpsc::UnboundedSender<Response>,
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

async fn copy_file(
    src: &Path,
    dst: &Path,
    req_id: u64,
    tx: &mpsc::UnboundedSender<Response>,
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
            return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
        }

        let n = src_file.read(&mut buf).await?;
        if n == 0 { break; }
        dst_file.write_all(&buf[..n]).await?;
        copied += n as u64;

        let _ = tx.send(Response {
            id: req_id, ok: true,
            stream_progress: Some(StreamProgress {
                finished: false,
                total_bytes: total,
                copied_bytes: copied,
                current_name: display_name.to_string(),
            }),
            ..Response::default()
        });
    }

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
