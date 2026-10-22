use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use tokio::sync::Mutex;

use crate::exec::ExecManager;
use crate::proto::{CloseExecRequest, WaitExecRequest};
use crate::proto::{ExecResponse, ExecStreamResponse, Response};
use crate::protocol::OutboundResponse;

use super::util::error_response;

pub(super) async fn run_exec_stream(
    req_id: u64,
    command: String,
    args: Vec<String>,
    cwd: String,
    env: HashMap<String, String>,
    execs: &Arc<Mutex<ExecManager>>,
) -> OutboundResponse {
    let mut cmd = tokio::process::Command::new(&command);
    cmd.args(&args)
        .envs(&env)
        .kill_on_drop(true)
        .process_group(0)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    if !cwd.is_empty() {
        cmd.current_dir(&cwd);
    }
    let mut child = match cmd.spawn() {
        Ok(child) => child,
        Err(error) => return OutboundResponse::plain(error_response(req_id, error)),
    };
    let stdout = match child.stdout.take() {
        Some(stdout) => stdout,
        None => {
            let _ = child.kill().await;
            return OutboundResponse::plain(error_response(
                req_id,
                io::Error::other("child stdout was not piped"),
            ));
        }
    };
    let stderr = match child.stderr.take() {
        Some(stderr) => stderr,
        None => {
            let _ = child.kill().await;
            return OutboundResponse::plain(error_response(
                req_id,
                io::Error::other("child stderr was not piped"),
            ));
        }
    };
    let stdout = match stdout.into_owned_fd() {
        Ok(fd) => fd,
        Err(error) => {
            let _ = child.kill().await;
            return OutboundResponse::plain(error_response(req_id, error));
        }
    };
    let stderr = match stderr.into_owned_fd() {
        Ok(fd) => fd,
        Err(error) => {
            let _ = child.kill().await;
            return OutboundResponse::plain(error_response(req_id, error));
        }
    };
    let (exec_id, child_pid) = execs.lock().await.insert(child);
    OutboundResponse::with_fds(
        Response {
            id: req_id,
            ok: true,
            exec_stream: Some(ExecStreamResponse { exec_id, child_pid }),
            ..Response::default()
        },
        vec![stdout, stderr],
    )
}

pub(super) async fn run_exec(
    req_id: u64,
    command: String,
    args: Vec<String>,
    cwd: String,
    env: HashMap<String, String>,
) -> Response {
    struct ProcessGroup(u32);
    impl Drop for ProcessGroup {
        fn drop(&mut self) {
            unsafe {
                libc::kill(-(self.0 as i32), libc::SIGKILL);
            }
        }
    }

    let mut cmd = tokio::process::Command::new(&command);
    cmd.args(&args)
        .envs(&env)
        .kill_on_drop(true)
        .process_group(0)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    if !cwd.is_empty() {
        cmd.current_dir(&cwd);
    }
    let child = match cmd.spawn() {
        Ok(child) => child,
        Err(error) => return error_response(req_id, error),
    };
    // Cancellation must kill the command's group, not leave blocking work holding the runtime open.
    let _group = ProcessGroup(child.id().expect("new child has a pid"));
    let outcome = child.wait_with_output().await;

    match outcome {
        Ok(output) => Response {
            id: req_id,
            ok: true,
            exec: Some(ExecResponse {
                exit_code: output.status.code().unwrap_or(-1),
                stdout: output.stdout,
                stderr: output.stderr,
            }),
            ..Response::default()
        },
        Err(error) => error_response(req_id, error),
    }
}

pub(super) async fn wait(
    req_id: u64,
    req: WaitExecRequest,
    execs: &Arc<Mutex<ExecManager>>,
) -> Response {
    let process = execs.lock().await.remove(req.exec_id);
    match process {
        Some(mut process) => match process.child.wait().await {
            Ok(status) => Response {
                id: req_id,
                ok: true,
                exit_code: status.code().unwrap_or(-1),
                ..Response::default()
            },
            Err(error) => error_response(req_id, error),
        },
        None => error_response(
            req_id,
            io::Error::new(io::ErrorKind::NotFound, "exec not found"),
        ),
    }
}

pub(super) async fn close(
    req_id: u64,
    req: CloseExecRequest,
    execs: &Arc<Mutex<ExecManager>>,
) -> Response {
    let removed = execs.lock().await.remove(req.exec_id).is_some();
    if removed {
        Response {
            id: req_id,
            ok: true,
            ..Response::default()
        }
    } else {
        error_response(
            req_id,
            io::Error::new(io::ErrorKind::NotFound, "exec not found"),
        )
    }
}
