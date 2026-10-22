use std::io;
use std::os::fd::IntoRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};

use crate::copy;
use crate::list;
use crate::proto::{
    CancelRequest, CopyFromSourceRequest, CopyRequest, ReadSourceResponse, Response, StreamProgress,
};
use crate::protocol::OutboundResponse;

use super::util::{error_response, ok_response};

pub(super) async fn copy(
    req_id: u64,
    req: CopyRequest,
    cancel_flags: &Arc<Mutex<std::collections::HashMap<u64, Arc<AtomicBool>>>>,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
) {
    let cancel_flag = Arc::new(AtomicBool::new(false));
    cancel_flags
        .lock()
        .await
        .insert(req_id, cancel_flag.clone());

    let tx_clone = tx.clone();
    let from = list::maybe_android_data_path(&req.from);
    let to = list::maybe_android_data_path(&req.to);
    let cancel_flags_clone = cancel_flags.clone();
    tokio::spawn(async move {
        let result = copy::copy(&from, &to, req_id, &tx_clone, &cancel_flag).await;

        cancel_flags_clone.lock().await.remove(&req_id);

        let _ = tx_clone.send(OutboundResponse::plain(Response {
            id: req_id,
            ok: result.is_ok(),
            stream_progress: Some(StreamProgress {
                finished: true,
                total_bytes: 0,
                copied_bytes: 0,
                current_name: result
                    .as_ref()
                    .err()
                    .map(|e| e.to_string())
                    .unwrap_or_default(),
            }),
            ..Response::default()
        }));
    });
}

pub(super) async fn copy_from_source(
    req_id: u64,
    req: CopyFromSourceRequest,
    attached_fd: Option<std::os::fd::OwnedFd>,
    cancel_flags: &Arc<Mutex<std::collections::HashMap<u64, Arc<AtomicBool>>>>,
    source_read_broker: &copy::SourceReadBroker,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
) {
    let source_fd = if req.virtual_source {
        None
    } else {
        attached_fd
    };
    let cancel_flag = Arc::new(AtomicBool::new(false));
    cancel_flags
        .lock()
        .await
        .insert(req_id, cancel_flag.clone());
    let tx_clone = tx.clone();
    let cancel_flags_clone = cancel_flags.clone();
    let broker = source_read_broker.clone();
    let to = req.to;
    let source_size = req.source_size;
    let virtual_source = req.virtual_source;
    tokio::spawn(async move {
        let result = if virtual_source {
            copy::copy_from_virtual(&to, req_id, source_size, &tx_clone, &cancel_flag, &broker)
                .await
        } else if let Some(fd) = source_fd {
            copy::copy_from_fd(
                fd.into_raw_fd(),
                &to,
                req_id,
                source_size,
                &tx_clone,
                &cancel_flag,
            )
            .await
        } else {
            Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "missing source fd",
            ))
        };
        let copied_bytes = result.as_ref().copied().unwrap_or_default();
        cancel_flags_clone.lock().await.remove(&req_id);
        let _ = tx_clone.send(OutboundResponse::plain(Response {
            id: req_id,
            ok: result.is_ok(),
            stream_progress: Some(StreamProgress {
                finished: true,
                total_bytes: source_size,
                copied_bytes,
                current_name: result
                    .as_ref()
                    .err()
                    .map(|e| e.to_string())
                    .unwrap_or_default(),
            }),
            ..Response::default()
        }));
    });
}

pub(super) async fn read_source_response(
    req_id: u64,
    response: ReadSourceResponse,
    source_read_broker: &copy::SourceReadBroker,
) -> Response {
    let response_id = response.request_id;
    match source_read_broker.lock().await.remove(&response_id) {
        Some(sender) => {
            let _ = sender.send(response);
            ok_response(req_id)
        }
        None => error_response(
            req_id,
            io::Error::new(io::ErrorKind::NotFound, "source read request not found"),
        ),
    }
}

pub(super) async fn cancel(
    req_id: u64,
    req: CancelRequest,
    cancel_flags: &Arc<Mutex<std::collections::HashMap<u64, Arc<AtomicBool>>>>,
) -> Response {
    let flags = cancel_flags.lock().await;
    match flags.get(&req.target_id) {
        Some(flag) => {
            flag.store(true, Ordering::Relaxed);
            ok_response(req_id)
        }
        None => error_response(
            req_id,
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("request {} is not cancellable", req.target_id),
            ),
        ),
    }
}
