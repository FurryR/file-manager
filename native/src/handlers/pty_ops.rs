use std::io;
use std::os::fd::OwnedFd;
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};

use crate::proto::{
    ClosePtyRequest, KillPtyRequest, PtyEvent, ResizePtyRequest, Response, SignalPtyRequest,
    SpawnPtyRequest, WaitPtyRequest,
};
use crate::protocol::OutboundResponse;
use crate::pty::PtyManager;

use super::util::{error_response, ok_response};

pub(super) async fn spawn(
    req_id: u64,
    req: SpawnPtyRequest,
    attached_fd: Option<OwnedFd>,
    pty_manager: &Arc<Mutex<PtyManager>>,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
) {
    let mut mgr = pty_manager.lock().await;
    match mgr.spawn_pty_with_cwd_fd(req, attached_fd) {
        Ok((pty_id, master_fd, child_pid)) => {
            let resp = Response {
                id: req_id,
                ok: true,
                pty_id,
                child_pid,
                ..Response::default()
            };
            let client_fd = unsafe { libc::fcntl(master_fd, libc::F_DUPFD_CLOEXEC, 0) };
            if client_fd < 0 {
                let error = io::Error::last_os_error();
                let _ = mgr.close_pty(pty_id);
                let _ = tx.send(OutboundResponse::plain(error_response(req_id, error)));
            } else {
                let _ = tx.send(OutboundResponse::with_fd(resp, client_fd));
            }
        }
        Err(error) => {
            let _ = tx.send(OutboundResponse::plain(error_response(req_id, error)));
        }
    }
}

pub(super) async fn resize(
    req_id: u64,
    req: ResizePtyRequest,
    pty_manager: &Arc<Mutex<PtyManager>>,
) -> Response {
    let mut mgr = pty_manager.lock().await;
    match mgr.resize_pty(req.pty_id, req.rows, req.cols) {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) async fn close(
    req_id: u64,
    req: ClosePtyRequest,
    pty_manager: &Arc<Mutex<PtyManager>>,
) -> Response {
    let mut mgr = pty_manager.lock().await;
    match mgr.close_pty(req.pty_id) {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) fn wait(
    req_id: u64,
    req: WaitPtyRequest,
    pty_manager: &Arc<Mutex<PtyManager>>,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
) {
    let pty_id = req.pty_id;
    let pm = pty_manager.clone();
    let tx = tx.clone();
    tokio::spawn(async move {
        let child_pid = {
            let mgr = pm.lock().await;
            mgr.get_child_pid(pty_id)
        };
        let pid = match child_pid {
            Ok(pid) => pid,
            Err(_) => {
                // PTY already cleaned up by closePty — return 0.
                let _ = tx.send(OutboundResponse::plain(Response {
                    id: req_id,
                    ok: true,
                    exit_code: 0,
                    ..Response::default()
                }));
                return;
            }
        };
        let result = tokio::task::spawn_blocking(move || {
            let mut status: i32 = 0;
            loop {
                let waited = unsafe { libc::waitpid(pid as i32, &mut status, 0) };
                if waited == pid as i32 {
                    return Ok(status);
                }
                let error = io::Error::last_os_error();
                if error.kind() != io::ErrorKind::Interrupted {
                    return Err(error);
                }
            }
        })
        .await;

        let exit_code = match result {
            Ok(Ok(status)) => {
                if libc::WIFEXITED(status) {
                    libc::WEXITSTATUS(status) as i32
                } else if libc::WIFSIGNALED(status) {
                    -(libc::WTERMSIG(status) as i32)
                } else {
                    -1
                }
            }
            Ok(Err(error)) => {
                let _ = tx.send(OutboundResponse::plain(error_response(req_id, error)));
                return;
            }
            Err(error) => {
                let _ = tx.send(OutboundResponse::plain(error_response(
                    req_id,
                    io::Error::other(error.to_string()),
                )));
                return;
            }
        };

        let mut mgr = pm.lock().await;
        let _ = mgr.remove_pty(pty_id);

        let _ = tx.send(OutboundResponse::plain(Response {
            id: req_id,
            ok: true,
            exit_code,
            pty_event: Some(PtyEvent {
                pty_id,
                event: Some(crate::proto::pty_event::Event::ExitCode(exit_code as u32)),
            }),
            ..Response::default()
        }));
    });
}

pub(super) async fn kill(
    req_id: u64,
    req: KillPtyRequest,
    pty_manager: &Arc<Mutex<PtyManager>>,
) -> Response {
    let mut mgr = pty_manager.lock().await;
    match mgr.close_pty(req.pty_id) {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}

pub(super) async fn signal(
    req_id: u64,
    req: SignalPtyRequest,
    pty_manager: &Arc<Mutex<PtyManager>>,
) -> Response {
    let mut mgr = pty_manager.lock().await;
    match mgr.signal_pty(req.pty_id, req.signal) {
        Ok(()) => ok_response(req_id),
        Err(error) => error_response(req_id, error),
    }
}
