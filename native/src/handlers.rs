use std::collections::HashMap;
use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::fs::File;
use tokio::sync::{mpsc, watch, Mutex};

use crate::copy;
use crate::handles::HandleTable;
use crate::list;
use crate::proto::request::Command;
use crate::proto::{
    CancelRequest, CloseHandleRequest, ClosePtyRequest, CopyFromSourceRequest, CopyRequest, CreateDirRequest, CreateFileRequest,
    DeleteRequest, DupHandleRequest, ExecRequest, ExecResponse, GetHandleSizeRequest, KillPtyRequest, ListRequest,
    OpenHandleRequest, RenameRequest, Request, ResizePtyRequest, Response,
    SeekHandleRequest, StatRequest, TruncateHandleRequest, WaitPtyRequest,
    PtyEvent,
};
use crate::protocol::OutboundResponse;
use crate::pty::PtyManager;

pub async fn handle_streaming(
    request: Request,
    attached_fd: Option<RawFd>,
    pty_manager: &Arc<Mutex<PtyManager>>,
    handles: &Arc<Mutex<HandleTable>>,
    cancel_flags: Arc<Mutex<HashMap<u64, Arc<AtomicBool>>>>,
    source_read_broker: &copy::SourceReadBroker,
    tx: mpsc::UnboundedSender<OutboundResponse>,
    shutdown: &watch::Sender<bool>,
) {
    let req_id = request.id;
    let cmd = request.command;

    match cmd {
        Some(Command::Exit(_)) => {
            close_unexpected_fd(attached_fd);
            // Ack first (the connection loop flushes queued responses on the
            // shutdown signal), then tear the whole daemon down. Any connection
            // may ask for this.
            let _ = tx.send(OutboundResponse::plain(ok_response(req_id)));
            let _ = shutdown.send(true);
        }
        Some(Command::List(ListRequest { path })) => {
            let resp = match list::list_files(&path).await {
                Ok(entries) => Response { id: req_id, ok: true, entries, ..Response::default() },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::CreateFile(CreateFileRequest { path })) => {
            let resp = match File::create(list::maybe_android_data_path(&path)).await {
                Ok(_) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::Stat(StatRequest { path })) => {
            let resp = match list::stat_path(&path) {
                Ok(stat) => Response { id: req_id, ok: true, stat: Some(stat), ..Response::default() },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::Rename(RenameRequest { path, new_name })) => {
            let target_path = std::path::Path::new(&list::maybe_android_data_path(&path))
                .parent().unwrap_or(std::path::Path::new("/")).join(&new_name);
            let resp = match tokio::fs::rename(list::maybe_android_data_path(&path), &target_path).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::Delete(DeleteRequest { path })) => {
            let resp = match copy::delete_recursive(&list::maybe_android_data_path(&path)).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::CreateDir(CreateDirRequest { path })) => {
            let resp = match tokio::fs::create_dir(list::maybe_android_data_path(&path)).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::Copy(CopyRequest { from, to })) => {
            close_unexpected_fd(attached_fd);
            let cancel_flag = Arc::new(AtomicBool::new(false));
            {
                let mut flags = cancel_flags.lock().await;
                flags.insert(req_id, cancel_flag.clone());
            }

            let tx_clone = tx.clone();
            let from_c = list::maybe_android_data_path(&from);
            let to_c = list::maybe_android_data_path(&to);
            let cancel_flags_clone = cancel_flags.clone();
            tokio::spawn(async move {
                let result = copy::copy(&from_c, &to_c, req_id, &tx_clone, &cancel_flag).await;

                let mut flags = cancel_flags_clone.lock().await;
                flags.remove(&req_id);

                    let _ = tx_clone.send(OutboundResponse::plain(Response {
                        id: req_id, ok: result.is_ok(),
                    stream_progress: Some(crate::proto::StreamProgress {
                        finished: true,
                        total_bytes: 0,
                        copied_bytes: 0,
                        current_name: result.as_ref().err().map(|e| e.to_string()).unwrap_or_default(),
                        }),
                        ..Response::default()
                    }));
            });
        }
        Some(Command::CopyFromSource(CopyFromSourceRequest { to, source_size, virtual_source })) => {
            let source_fd = if virtual_source {
                close_unexpected_fd(attached_fd);
                None
            } else {
                attached_fd
            };
            let cancel_flag = Arc::new(AtomicBool::new(false));
            {
                let mut flags = cancel_flags.lock().await;
                flags.insert(req_id, cancel_flag.clone());
            }
            let tx_clone = tx.clone();
            let cancel_flags_clone = cancel_flags.clone();
            let broker = source_read_broker.clone();
            tokio::spawn(async move {
                let result = if virtual_source {
                    copy::copy_from_virtual(&to, req_id, source_size, &tx_clone, &cancel_flag, &broker).await
                } else if let Some(fd) = source_fd {
                    copy::copy_from_fd(fd, &to, req_id, source_size, &tx_clone, &cancel_flag).await
                } else {
                    Err(io::Error::new(io::ErrorKind::InvalidInput, "missing source fd"))
                };
                let copied_bytes = result.as_ref().copied().unwrap_or_default();
                let mut flags = cancel_flags_clone.lock().await;
                flags.remove(&req_id);
                let _ = tx_clone.send(OutboundResponse::plain(Response {
                    id: req_id,
                    ok: result.is_ok(),
                    stream_progress: Some(crate::proto::StreamProgress {
                        finished: true,
                        total_bytes: source_size,
                        copied_bytes,
                        current_name: result.as_ref().err().map(|e| e.to_string()).unwrap_or_default(),
                    }),
                    ..Response::default()
                }));
            });
        }
        Some(Command::ReadSourceResponse(response)) => {
            let response_id = response.request_id;
            let response = match source_read_broker.lock().await.remove(&response_id) {
                Some(sender) => {
                    let _ = sender.send(response);
                    ok_response(req_id)
                }
                None => error_response(req_id, io::Error::new(io::ErrorKind::NotFound, "source read request not found")),
            };
            let _ = tx.send(OutboundResponse::plain(response));
        }
        Some(Command::Cancel(CancelRequest { target_id })) => {
            close_unexpected_fd(attached_fd);
            let flags = cancel_flags.lock().await;
            let resp = match flags.get(&target_id) {
                Some(flag) => {
                    flag.store(true, Ordering::Relaxed);
                    ok_response(req_id)
                }
                None => error_response(
                    req_id,
                    io::Error::new(
                        io::ErrorKind::NotFound,
                        format!("request {target_id} is not cancellable"),
                    ),
                ),
            };
            drop(flags);
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::SpawnPty(req)) => {
            let mut mgr = pty_manager.lock().await;
            match mgr.spawn_pty(req) {
                Ok((pty_id, master_fd, child_pid)) => {
                    let resp = Response {
                        id: req_id, ok: true, pty_id, child_pid,
                        ..Response::default()
                    };
                    let client_fd = unsafe { libc::dup(master_fd) };
                    if client_fd < 0 {
                        let error = io::Error::last_os_error();
                        let _ = mgr.close_pty(pty_id);
                        let _ = tx.send(OutboundResponse::plain(error_response(req_id, error)));
                    } else if tx.send(OutboundResponse::with_fd(resp, client_fd)).is_err() {
                        unsafe { libc::close(client_fd); }
                    }
                }
                Err(error) => {
                    let _ = tx.send(OutboundResponse::plain(error_response(req_id, error)));
                }
            }
        }
        Some(Command::ResizePty(ResizePtyRequest { pty_id, rows, cols })) => {
            let mut mgr = pty_manager.lock().await;
            let resp = match mgr.resize_pty(pty_id, rows, cols) {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
                    let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::ClosePty(ClosePtyRequest { pty_id })) => {
            let mut mgr = pty_manager.lock().await;
            let resp = match mgr.close_pty(pty_id) {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::WaitPty(WaitPtyRequest { pty_id })) => {
            let pm = pty_manager.clone();
            let tx = tx.clone();
            tokio::spawn(async move {
                let child_pid = {
                    let mgr = pm.lock().await;
                    mgr.get_child_pid(pty_id)
                };
                match child_pid {
                    Ok(pid) => {
                        let result = tokio::task::spawn_blocking(move || {
                            let mut status: i32 = 0;
                            unsafe {
                                libc::waitpid(pid as i32, &mut status as *mut i32, 0);
                            }
                            status
                        }).await;

                        let exit_code = match result {
                            Ok(status) => {
                                if libc::WIFEXITED(status) {
                                    libc::WEXITSTATUS(status) as i32
                                } else if libc::WIFSIGNALED(status) {
                                    -(libc::WTERMSIG(status) as i32)
                                } else {
                                    -1
                                }
                            }
                            Err(_) => -1,
                        };

                        // Clean up after child has been reaped
                        let mut mgr = pm.lock().await;
                        let _ = mgr.remove_pty(pty_id);

                        let _ = tx.send(OutboundResponse::plain(Response {
                            id: req_id, ok: true,
                            exit_code,
                            pty_event: Some(PtyEvent {
                                pty_id,
                                event: Some(crate::proto::pty_event::Event::ExitCode(exit_code as u32)),
                            }),
                            ..Response::default()
                        }));
                    }
                    Err(_) => {
                        // PTY already cleaned up by closePty — return 0
                        let _ = tx.send(OutboundResponse::plain(Response {
                            id: req_id, ok: true, exit_code: 0,
                            ..Response::default()
                        }));
                    }
                }
            });
        }
        Some(Command::Exec(ExecRequest { command, args, cwd, env })) => {
            close_unexpected_fd(attached_fd);
            let resp = run_exec(req_id, command, args, cwd, env).await;
            let _ = tx.send(OutboundResponse::plain(resp));
        }

        Some(Command::KillPty(KillPtyRequest { pty_id })) => {
            let mut mgr = pty_manager.lock().await;
            let resp = match mgr.close_pty(pty_id) {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::OpenHandle(OpenHandleRequest { path, mode, want_fd })) => {
            let mode = crate::proto::HandleMode::try_from(mode).unwrap_or(crate::proto::HandleMode::Read);
            let mut table = handles.lock().await;
            let outbound = match table.open(&path, mode).await {
                Ok(handle) => {
                    let response = Response {
                        id: req_id, ok: true, open_handle: Some(handle.clone()), ..Response::default()
                    };
                    if mode == crate::proto::HandleMode::Read && want_fd {
                        // Only local/same-domain callers ask for this. A kernel
                        // fd handed to a process in another SELinux domain is
                        // dropped by `file_receive`, so callers stream instead.
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
            };
            let _ = tx.send(outbound);
        }
        Some(Command::ReadHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let mut table = handles.lock().await;
            let resp = match table.read(req.handle_id, req.offset, req.length).await {
                Ok((data, eof)) => Response {
                    id: req_id, ok: true,
                    read_handle: Some(crate::proto::ReadHandleResponse { data, eof }),
                    ..Response::default()
                },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::WriteHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let mut table = handles.lock().await;
            let resp = match table.write(req.handle_id, req.offset, &req.data).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::CloseHandle(CloseHandleRequest { handle_id })) => {
            close_unexpected_fd(attached_fd);
            let mut table = handles.lock().await;
            let resp = match table.close(handle_id).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::GetHandleSize(GetHandleSizeRequest { handle_id })) => {
            close_unexpected_fd(attached_fd);
            let table = handles.lock().await;
            let resp = match table.get_size(handle_id) {
                Some(size) => Response {
                    id: req_id, ok: true,
                    get_handle_size: Some(crate::proto::GetHandleSizeResponse { size }),
                    ..Response::default()
                },
                None => Response { id: req_id, ok: false, error: format!("handle {} not found", handle_id), ..Response::default() },
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::SeekHandle(SeekHandleRequest { handle_id, position })) => {
            close_unexpected_fd(attached_fd);
            let mut table = handles.lock().await;
            let resp = match table.seek(handle_id, position).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::DupHandle(DupHandleRequest { handle_id })) => {
            close_unexpected_fd(attached_fd);
            let mut table = handles.lock().await;
            let resp = match table.dup(handle_id).await {
                Ok(r) => Response { id: req_id, ok: true, dup_handle: Some(r), ..Response::default() },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::TruncateHandle(TruncateHandleRequest { handle_id, new_size })) => {
            close_unexpected_fd(attached_fd);
            let mut table = handles.lock().await;
            let resp = match table.truncate(handle_id, new_size).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        None => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(OutboundResponse::plain(Response { id: req_id, ok: false, error: "missing command".to_string(), ..Response::default() }));
        }
    }
}

async fn run_exec(
    req_id: u64,
    command: String,
    args: Vec<String>,
    cwd: String,
    env: HashMap<String, String>,
) -> Response {
    let outcome = tokio::task::spawn_blocking(move || {
        let mut cmd = std::process::Command::new(&command);
        cmd.args(&args);
        if !cwd.is_empty() {
            cmd.current_dir(&cwd);
        }
        for (key, value) in &env {
            cmd.env(key, value);
        }
        cmd.output()
    })
    .await;

    match outcome {
        Ok(Ok(output)) => Response {
            id: req_id,
            ok: true,
            exec: Some(ExecResponse {
                exit_code: output.status.code().unwrap_or(-1),
                stdout: output.stdout,
                stderr: output.stderr,
            }),
            ..Response::default()
        },
        Ok(Err(error)) => error_response(req_id, error),
        Err(error) => error_response(
            req_id,
            io::Error::new(io::ErrorKind::Other, error.to_string()),
        ),
    }
}

fn close_unexpected_fd(fd: Option<RawFd>) {
    if let Some(fd) = fd {
        unsafe { libc::close(fd); }
    }
}

fn ok_response(id: u64) -> Response {
    Response { id, ok: true, ..Response::default() }
}

fn error_response(id: u64, error: io::Error) -> Response {
    Response { id, ok: false, error: error.to_string(), ..Response::default() }
}

#[cfg(test)]
mod tests {
    use super::handle_streaming;
    use crate::handles::HandleTable;
    use crate::proto::request::Command;
    use crate::proto::{ExitRequest, Request};
    use crate::pty::PtyManager;
    use std::collections::HashMap;
    use std::sync::atomic::AtomicBool;
    use std::sync::Arc;
    use tokio::sync::{mpsc, watch, Mutex};

    #[tokio::test]
    async fn exit_request_acks_and_signals_shutdown() {
        let pty = Arc::new(Mutex::new(PtyManager::new()));
        let handles = Arc::new(Mutex::new(HandleTable::new()));
        let cancel = Arc::new(Mutex::new(HashMap::<u64, Arc<AtomicBool>>::new()));
        let broker = crate::copy::SourceReadBroker::default();
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let (resp_tx, mut resp_rx) = mpsc::unbounded_channel();

        let request = Request {
            id: 7,
            command: Some(Command::Exit(ExitRequest {})),
        };
        handle_streaming(
            request, None, &pty, &handles, cancel, &broker, resp_tx, &shutdown_tx,
        )
        .await;

        assert!(*shutdown_rx.borrow(), "exit must signal daemon shutdown");
        let outbound = resp_rx.recv().await.expect("exit must be acked");
        assert!(outbound.response.ok);
        assert_eq!(outbound.response.id, 7);
    }
}
