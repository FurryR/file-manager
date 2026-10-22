use std::collections::HashMap;
use std::os::fd::OwnedFd;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use tokio::sync::{mpsc, watch, Mutex};

use crate::copy;
use crate::exec::ExecManager;
use crate::handles::HandleTable;
use crate::proto::request::Command;
use crate::proto::{
    CreateDirRequest, CreateFileRequest, DeleteRequest, ExecRequest, ListRequest, RenameRequest,
    Request, Response, StatRequest,
};
use crate::protocol::OutboundResponse;
use crate::pty::PtyManager;

mod copy_ops;
mod exec;
mod file_ops;
mod handle_ops;
mod pty_ops;
mod util;

use exec::{run_exec, run_exec_stream};
use util::{close_unexpected_fd, ok_response};

pub async fn handle_streaming(
    request: Request,
    attached_fd: Option<OwnedFd>,
    pty_manager: &Arc<Mutex<PtyManager>>,
    execs: &Arc<Mutex<ExecManager>>,
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
            let _ = tx.send(OutboundResponse::plain(file_ops::list(req_id, path).await));
        }
        Some(Command::CreateFile(CreateFileRequest { path })) => {
            let _ = tx.send(OutboundResponse::plain(
                file_ops::create_file(req_id, path).await,
            ));
        }
        Some(Command::Stat(StatRequest { path })) => {
            let _ = tx.send(OutboundResponse::plain(file_ops::stat(req_id, path)));
        }
        Some(Command::Rename(RenameRequest { path, new_name })) => {
            let _ = tx.send(OutboundResponse::plain(
                file_ops::rename(req_id, path, new_name).await,
            ));
        }
        Some(Command::Delete(DeleteRequest { path })) => {
            let _ = tx.send(OutboundResponse::plain(
                file_ops::delete(req_id, path).await,
            ));
        }
        Some(Command::CreateDir(CreateDirRequest { path })) => {
            let _ = tx.send(OutboundResponse::plain(
                file_ops::create_dir(req_id, path).await,
            ));
        }
        Some(Command::Copy(req)) => {
            close_unexpected_fd(attached_fd);
            copy_ops::copy(req_id, req, &cancel_flags, &tx).await;
        }
        Some(Command::CopyFromSource(req)) => {
            copy_ops::copy_from_source(
                req_id,
                req,
                attached_fd,
                &cancel_flags,
                source_read_broker,
                &tx,
            )
            .await;
        }
        Some(Command::ReadSourceResponse(response)) => {
            let resp = copy_ops::read_source_response(req_id, response, source_read_broker).await;
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::Cancel(req)) => {
            close_unexpected_fd(attached_fd);
            let resp = copy_ops::cancel(req_id, req, &cancel_flags).await;
            let _ = tx.send(OutboundResponse::plain(resp));
        }
        Some(Command::SpawnPty(req)) => {
            pty_ops::spawn(req_id, req, attached_fd, pty_manager, &tx).await;
        }
        Some(Command::ResizePty(req)) => {
            let _ = tx.send(OutboundResponse::plain(
                pty_ops::resize(req_id, req, pty_manager).await,
            ));
        }
        Some(Command::ClosePty(req)) => {
            let _ = tx.send(OutboundResponse::plain(
                pty_ops::close(req_id, req, pty_manager).await,
            ));
        }
        Some(Command::WaitPty(req)) => {
            pty_ops::wait(req_id, req, pty_manager, &tx);
        }
        Some(Command::Exec(ExecRequest {
            command,
            args,
            cwd,
            env,
            pipes,
        })) => {
            close_unexpected_fd(attached_fd);
            if pipes {
                let outbound = run_exec_stream(req_id, command, args, cwd, env, execs).await;
                let _ = tx.send(outbound);
            } else {
                let resp = run_exec(req_id, command, args, cwd, env).await;
                let _ = tx.send(OutboundResponse::plain(resp));
            }
        }
        Some(Command::WaitExec(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(OutboundResponse::plain(
                exec::wait(req_id, req, execs).await,
            ));
        }
        Some(Command::CloseExec(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(OutboundResponse::plain(
                exec::close(req_id, req, execs).await,
            ));
        }

        Some(Command::KillPty(req)) => {
            let _ = tx.send(OutboundResponse::plain(
                pty_ops::kill(req_id, req, pty_manager).await,
            ));
        }
        Some(Command::SignalPty(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(OutboundResponse::plain(
                pty_ops::signal(req_id, req, pty_manager).await,
            ));
        }
        Some(Command::OpenHandle(req)) => {
            let _ = tx.send(handle_ops::open(req_id, req, handles).await);
        }
        Some(Command::ReadHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(handle_ops::read(req_id, req, handles).await);
        }
        Some(Command::WriteHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(handle_ops::write(req_id, req, handles).await);
        }
        Some(Command::CloseHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(handle_ops::close(req_id, req, handles).await);
        }
        Some(Command::GetHandleSize(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(handle_ops::size(req_id, req, handles).await);
        }
        Some(Command::SeekHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(handle_ops::seek(req_id, req, handles).await);
        }
        Some(Command::DupHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(handle_ops::dup(req_id, req, handles).await);
        }
        Some(Command::TruncateHandle(req)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(handle_ops::truncate(req_id, req, handles).await);
        }
        None => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(OutboundResponse::plain(Response {
                id: req_id,
                ok: false,
                error: "missing command".to_string(),
                ..Response::default()
            }));
        }
        // Handled in the connection loop before any dispatch; reaching here
        // means an authenticated client sent it after the handshake.
        Some(Command::Auth(_)) => {
            close_unexpected_fd(attached_fd);
            let _ = tx.send(OutboundResponse::plain(Response {
                id: req_id,
                ok: false,
                error: "already authenticated".to_string(),
                ..Response::default()
            }));
        }
    }
}
