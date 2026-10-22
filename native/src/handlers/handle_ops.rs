use std::sync::Arc;
use tokio::sync::Mutex;

use crate::handles::HandleTable;
use crate::proto::{
    CloseHandleRequest, DupHandleRequest, GetHandleSizeRequest, GetHandleSizeResponse, HandleMode,
    OpenHandleRequest, ReadHandleRequest, ReadHandleResponse, Response, SeekHandleRequest,
    TruncateHandleRequest, WriteHandleRequest,
};
use crate::protocol::OutboundResponse;

use super::util::{error_response, ok_response};

pub(super) async fn open(
    req_id: u64,
    req: OpenHandleRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let OpenHandleRequest {
        path,
        mode,
        want_fd,
    } = req;
    let mode = HandleMode::try_from(mode).unwrap_or(HandleMode::Read);
    let mut table = handles.lock().await;
    match table.open(&path, mode).await {
        Ok(handle) => {
            let response = Response {
                id: req_id,
                ok: true,
                open_handle: Some(handle.clone()),
                ..Response::default()
            };
            if mode == HandleMode::Read && want_fd {
                // Only local/same-domain callers ask for this. A kernel fd
                // handed to a process in another SELinux domain is dropped by
                // `file_receive`, so callers stream instead.
                match table.duplicate_fd(handle.handle_id) {
                    Ok(fd) => OutboundResponse::with_fd(response, fd),
                    Err(error) => {
                        let _ = table.close(handle.handle_id).await;
                        OutboundResponse::plain(error_response(req_id, error))
                    }
                }
            } else {
                OutboundResponse::plain(response)
            }
        }
        Err(error) => OutboundResponse::plain(error_response(req_id, error)),
    }
}

pub(super) async fn read(
    req_id: u64,
    req: ReadHandleRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let mut table = handles.lock().await;
    let resp = match table.read(req.handle_id, req.offset, req.length).await {
        Ok((data, eof)) => Response {
            id: req_id,
            ok: true,
            read_handle: Some(ReadHandleResponse { data, eof }),
            ..Response::default()
        },
        Err(error) => error_response(req_id, error),
    };
    OutboundResponse::plain(resp)
}

pub(super) async fn write(
    req_id: u64,
    req: WriteHandleRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let mut table = handles.lock().await;
    let resp = match table.write(req.handle_id, req.offset, &req.data).await {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    };
    OutboundResponse::plain(resp)
}

pub(super) async fn close(
    req_id: u64,
    req: CloseHandleRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let mut table = handles.lock().await;
    let resp = match table.close(req.handle_id).await {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    };
    OutboundResponse::plain(resp)
}

pub(super) async fn size(
    req_id: u64,
    req: GetHandleSizeRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let table = handles.lock().await;
    let resp = match table.get_size(req.handle_id) {
        Some(size) => Response {
            id: req_id,
            ok: true,
            get_handle_size: Some(GetHandleSizeResponse { size }),
            ..Response::default()
        },
        None => Response {
            id: req_id,
            ok: false,
            error: format!("handle {} not found", req.handle_id),
            ..Response::default()
        },
    };
    OutboundResponse::plain(resp)
}

pub(super) async fn seek(
    req_id: u64,
    req: SeekHandleRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let mut table = handles.lock().await;
    let resp = match table.seek(req.handle_id, req.position).await {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    };
    OutboundResponse::plain(resp)
}

pub(super) async fn dup(
    req_id: u64,
    req: DupHandleRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let mut table = handles.lock().await;
    let resp = match table.dup(req.handle_id).await {
        Ok(r) => Response {
            id: req_id,
            ok: true,
            dup_handle: Some(r),
            ..Response::default()
        },
        Err(error) => error_response(req_id, error),
    };
    OutboundResponse::plain(resp)
}

pub(super) async fn truncate(
    req_id: u64,
    req: TruncateHandleRequest,
    handles: &Arc<Mutex<HandleTable>>,
) -> OutboundResponse {
    let mut table = handles.lock().await;
    let resp = match table.truncate(req.handle_id, req.new_size).await {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    };
    OutboundResponse::plain(resp)
}
