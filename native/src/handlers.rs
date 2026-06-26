use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use tokio::fs::File;
use tokio::sync::{mpsc, Mutex};

use crate::copy;
use crate::handles::HandleTable;
use crate::list;
use crate::proto::request::Command;
use crate::proto::{
    CloseHandleRequest, ClosePtyRequest, CopyRequest, CreateDirRequest, CreateFileRequest,
    DeleteRequest, DupHandleRequest, GetHandleSizeRequest, KillPtyRequest, ListRequest,
    OpenHandleRequest, RenameRequest, Request, ResizePtyRequest, Response,
    SeekHandleRequest, StatRequest, TruncateHandleRequest, WaitPtyRequest,
    PtyEvent,
};
use crate::protocol::write_response_with_fd;
use crate::pty::PtyManager;

pub async fn handle_streaming(
    request: Request,
    pty_manager: &Arc<Mutex<PtyManager>>,
    handles: &Arc<Mutex<HandleTable>>,
    cancel_flags: &Arc<Mutex<HashMap<u64, Arc<AtomicBool>>>>,
    _socket_fd: std::os::fd::RawFd,
    write_fd: std::os::fd::RawFd,
    tx: mpsc::UnboundedSender<Response>,
) {
    let req_id = request.id;
    let cmd = request.command;

    match cmd {
        Some(Command::List(ListRequest { path })) => {
            let resp = match list::list_files(&path).await {
                Ok(entries) => Response { id: req_id, ok: true, entries, ..Response::default() },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::CreateFile(CreateFileRequest { path })) => {
            let resp = match File::create(list::maybe_android_data_path(&path)).await {
                Ok(_) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::Stat(StatRequest { path })) => {
            let resp = match list::stat_path(&path) {
                Ok(stat) => Response { id: req_id, ok: true, stat: Some(stat), ..Response::default() },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::Rename(RenameRequest { path, new_name })) => {
            let target_path = std::path::Path::new(&list::maybe_android_data_path(&path))
                .parent().unwrap_or(std::path::Path::new("/")).join(&new_name);
            let resp = match tokio::fs::rename(list::maybe_android_data_path(&path), &target_path).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::Delete(DeleteRequest { path })) => {
            let resp = match copy::delete_recursive(&list::maybe_android_data_path(&path)).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::CreateDir(CreateDirRequest { path })) => {
            let resp = match tokio::fs::create_dir(list::maybe_android_data_path(&path)).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::Copy(CopyRequest { from, to })) => {
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

                let _ = tx_clone.send(Response {
                    id: req_id, ok: result.is_ok(),
                    stream_progress: Some(crate::proto::StreamProgress {
                        finished: true,
                        total_bytes: 0,
                        copied_bytes: 0,
                        current_name: result.as_ref().err().map(|e| e.to_string()).unwrap_or_default(),
                    }),
                    ..Response::default()
                });
            });
        }
        Some(Command::SpawnPty(req)) => {
            let mut mgr = pty_manager.lock().await;
            match mgr.spawn_pty(req) {
                Ok((pty_id, master_fd, child_pid)) => {
                    let resp = Response {
                        id: req_id, ok: true, pty_id, child_pid,
                        ..Response::default()
                    };
                    if let Err(e) = write_response_with_fd(write_fd, &resp, master_fd) {
                        let _ = tx.send(error_response(req_id, e));
                        unsafe { libc::close(master_fd); }
                    }
                }
                Err(error) => {
                    let _ = tx.send(error_response(req_id, error));
                }
            }
        }
        Some(Command::ResizePty(ResizePtyRequest { pty_id, rows, cols })) => {
            let mut mgr = pty_manager.lock().await;
            let resp = match mgr.resize_pty(pty_id, rows, cols) {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::ClosePty(ClosePtyRequest { pty_id })) => {
            let mut mgr = pty_manager.lock().await;
            let resp = match mgr.close_pty(pty_id) {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
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

                        let _ = tx.send(Response {
                            id: req_id, ok: true,
                            exit_code,
                            pty_event: Some(PtyEvent {
                                pty_id,
                                event: Some(crate::proto::pty_event::Event::ExitCode(exit_code as u32)),
                            }),
                            ..Response::default()
                        });
                    }
                    Err(_) => {
                        // PTY already cleaned up by closePty — return 0
                        let _ = tx.send(Response {
                            id: req_id, ok: true, exit_code: 0,
                            ..Response::default()
                        });
                    }
                }
            });
        }
        Some(Command::KillPty(KillPtyRequest { pty_id })) => {
            let mut mgr = pty_manager.lock().await;
            let resp = match mgr.close_pty(pty_id) {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::OpenHandle(OpenHandleRequest { path, mode })) => {
            let mode = crate::proto::HandleMode::try_from(mode).unwrap_or(crate::proto::HandleMode::Read);
            let mut table = handles.lock().await;
            let resp = match table.open(&path, mode).await {
                Ok(r) => Response { id: req_id, ok: true, open_handle: Some(r), ..Response::default() },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::ReadHandle(req)) => {
            let mut table = handles.lock().await;
            let resp = match table.read(req.handle_id, req.offset, req.length).await {
                Ok((data, eof)) => Response {
                    id: req_id, ok: true,
                    read_handle: Some(crate::proto::ReadHandleResponse { data, eof }),
                    ..Response::default()
                },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::WriteHandle(req)) => {
            let mut table = handles.lock().await;
            let resp = match table.write(req.handle_id, req.offset, &req.data).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::CloseHandle(CloseHandleRequest { handle_id })) => {
            let mut table = handles.lock().await;
            let resp = match table.close(handle_id).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::GetHandleSize(GetHandleSizeRequest { handle_id })) => {
            let table = handles.lock().await;
            let resp = match table.get_size(handle_id) {
                Some(size) => Response {
                    id: req_id, ok: true,
                    get_handle_size: Some(crate::proto::GetHandleSizeResponse { size }),
                    ..Response::default()
                },
                None => Response { id: req_id, ok: false, error: format!("handle {} not found", handle_id), ..Response::default() },
            };
            let _ = tx.send(resp);
        }
        Some(Command::SeekHandle(SeekHandleRequest { handle_id, position })) => {
            let mut table = handles.lock().await;
            let resp = match table.seek(handle_id, position).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::DupHandle(DupHandleRequest { handle_id })) => {
            let mut table = handles.lock().await;
            let resp = match table.dup(handle_id).await {
                Ok(r) => Response { id: req_id, ok: true, dup_handle: Some(r), ..Response::default() },
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        Some(Command::TruncateHandle(TruncateHandleRequest { handle_id, new_size })) => {
            let mut table = handles.lock().await;
            let resp = match table.truncate(handle_id, new_size).await {
                Ok(()) => ok_response(req_id),
                Err(error) => error_response(req_id, error),
            };
            let _ = tx.send(resp);
        }
        None => {
            let _ = tx.send(Response { id: req_id, ok: false, error: "missing command".to_string(), ..Response::default() });
        }
    }
}

fn ok_response(id: u64) -> Response {
    Response { id, ok: true, ..Response::default() }
}

fn error_response(id: u64, error: io::Error) -> Response {
    Response { id, ok: false, error: error.to_string(), ..Response::default() }
}
