use std::collections::HashMap;
use std::io;
use std::os::fd::{FromRawFd, IntoRawFd, OwnedFd};
use std::os::unix::process::CommandExt;
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{mpsc, watch, Mutex};
use tokio::task::JoinHandle;

use crate::exec::ExecManager;
use crate::proto::{CloseExecRequest, WaitExecRequest};
use crate::proto::{ExecResponse, ExecStreamResponse, Response};
use crate::protocol::OutboundResponse;

use super::util::error_response;

/// Deadline for a command that reports its exit status in one response. The
/// pipes are plain, so a background descendant can hold them open forever;
/// without a bound the request would never answer at all.
const EXEC_TIMEOUT: Duration = Duration::from_secs(600);
/// Grace period for draining output after the child itself has exited.
const OUTPUT_DRAIN: Duration = Duration::from_secs(2);

/// Owns the reaper registration of one freshly spawned child. Every error path
/// in this module returns through `Drop`, so a pid can never stay registered
/// forever — the leak that used to pin zombies and expose their pids to reuse.
struct ReapGuard {
    pid: u32,
    exit: watch::Receiver<Option<i32>>,
}

impl ReapGuard {
    fn new(pid: u32, exit: watch::Receiver<Option<i32>>) -> Self {
        Self { pid, exit }
    }

    /// The exit status now belongs to `ExecManager` or the handler itself.
    fn disarm(mut self) {
        self.pid = 0;
    }
}

impl Drop for ReapGuard {
    fn drop(&mut self) {
        if self.pid == 0 {
            return;
        }
        // A failed spawn path must not leave a live process behind. Once the
        // reaper has published an exit code the pid is free for reuse, so only
        // signal the group while the child can still be running.
        if self.exit.borrow().is_none() {
            unsafe {
                libc::kill(-(self.pid as i32), libc::SIGKILL);
            }
        }
        crate::reaper::release(self.pid);
    }
}

/// Take over both output pipes of `child` so they can be drained separately
/// from the exit status of the child.
fn take_pipes(child: &mut Child) -> io::Result<(OwnedFd, OwnedFd)> {
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| io::Error::other("child stdout was not piped"))?;
    let stderr = child
        .stderr
        .take()
        .ok_or_else(|| io::Error::other("child stderr was not piped"))?;
    Ok((
        unsafe { OwnedFd::from_raw_fd(stdout.into_raw_fd()) },
        unsafe { OwnedFd::from_raw_fd(stderr.into_raw_fd()) },
    ))
}
/// Read one pipe to EOF without blocking a runtime thread.
///
/// `AsyncFd` is used instead of `tokio::fs::File` so that abandoning the reader
/// genuinely stops it: blocking file I/O runs on the blocking pool and cannot be
/// cancelled, so an aborted read would pin a thread forever on a pipe that a
/// descendant never closes.
async fn read_all(fd: OwnedFd) -> io::Result<Vec<u8>> {
    use std::os::fd::AsRawFd;
    use tokio::io::unix::AsyncFd;
    use tokio::io::Interest;

    let raw = fd.as_raw_fd();
    let flags = unsafe { libc::fcntl(raw, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(raw, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }
    let fd = AsyncFd::new(fd)?;
    let mut collected = Vec::new();
    let mut buf = [0u8; 8192];
    loop {
        let n = fd
            .async_io(Interest::READABLE, |inner| {
                read_raw(inner.as_raw_fd(), &mut buf)
            })
            .await?;
        if n == 0 {
            break;
        }
        collected.extend_from_slice(&buf[..n]);
    }
    Ok(collected)
}

fn read_raw(fd: std::os::fd::RawFd, buf: &mut [u8]) -> io::Result<usize> {
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        return Ok(n as usize);
    }
}
/// Aborts a reader task when dropped. Handler tasks are aborted on connection
/// loss, and a read future orphaned by an abort would otherwise linger until
/// the pipe finally closes.
struct ReaderGuard(Option<JoinHandle<io::Result<Vec<u8>>>>);

impl ReaderGuard {
    fn new(task: JoinHandle<io::Result<Vec<u8>>>) -> Self {
        Self(Some(task))
    }

    /// Hand the task to [drain_output], which owns it from here on.
    fn take(mut self) -> JoinHandle<io::Result<Vec<u8>>> {
        self.0.take().expect("reader taken exactly once")
    }
}

impl Drop for ReaderGuard {
    fn drop(&mut self) {
        if let Some(task) = self.0.take() {
            task.abort();
        }
    }
}

/// A descendant that inherited stdout/stderr keeps the write end open after the
/// child exited, so the reader may never reach EOF. Waiting for it would hang
/// the request; abandoning it only loses output nobody can produce.
async fn drain_output(task: JoinHandle<io::Result<Vec<u8>>>) -> Vec<u8> {
    match tokio::time::timeout(OUTPUT_DRAIN, task).await {
        Ok(Ok(Ok(bytes))) => bytes,
        // A failed or abandoned drain must not fail the command: the exit
        // status is the authoritative result.
        _ => Vec::new(),
    }
}

/// Wait for the reaper to publish the exit code. `None` means the deadline
/// elapsed; the caller must then terminate the process group itself.
async fn wait_exit(exit: &mut watch::Receiver<Option<i32>>, limit: Duration) -> Option<i32> {
    tokio::time::timeout(limit, async {
        loop {
            let ready = { *exit.borrow() };
            if let Some(code) = ready {
                return code;
            }
            if exit.changed().await.is_err() {
                return -1;
            }
        }
    })
    .await
    .ok()
}

pub(super) async fn run_exec_stream(
    req_id: u64,
    command: String,
    args: Vec<String>,
    cwd: String,
    env: HashMap<String, String>,
    execs: &Arc<Mutex<ExecManager>>,
) -> OutboundResponse {
    let mut cmd = Command::new(&command);
    cmd.args(&args)
        .envs(&env)
        .process_group(0)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if !cwd.is_empty() {
        cmd.current_dir(&cwd);
    }
    let mut child = match cmd.spawn() {
        Ok(child) => child,
        Err(error) => return OutboundResponse::plain(error_response(req_id, error)),
    };
    // The pid must be registered with the reaper before anything else runs.
    // The guard kills the group if any step below fails; `disarm` hands the
    // duty to the ExecManager entry once the child is registered there.
    let pid = child.id();
    let exit = crate::reaper::track(pid);
    let guard = ReapGuard::new(pid, exit.clone());
    let (stdout, stderr) = match take_pipes(&mut child) {
        Ok(pipes) => pipes,
        Err(error) => {
            return OutboundResponse::plain(error_response(req_id, error));
        }
    };
    // The reaper owns waiting; the std Child only held the spawn result.
    drop(child);
    let exec_id = execs.lock().await.insert(pid, exit);
    guard.disarm();
    OutboundResponse::with_fds(
        Response {
            id: req_id,
            ok: true,
            exec_stream: Some(ExecStreamResponse {
                exec_id,
                child_pid: pid,
            }),
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
    let mut cmd = Command::new(&command);
    cmd.args(&args)
        .envs(&env)
        .process_group(0)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if !cwd.is_empty() {
        cmd.current_dir(&cwd);
    }
    let mut child = match cmd.spawn() {
        Ok(child) => child,
        Err(error) => return error_response(req_id, error),
    };
    // Cancellation must kill the command's group, not leave blocking work
    // holding the runtime open. The guard terminates the group on every error
    // path, including an aborted handler.
    let pid = child.id();
    let mut exit = crate::reaper::track(pid);
    let guard = ReapGuard::new(pid, exit.clone());
    let (stdout, stderr) = match take_pipes(&mut child) {
        Ok(pipes) => pipes,
        Err(error) => {
            return error_response(req_id, error);
        }
    };
    drop(child);

    // Drain the pipes while waiting, but treat the published exit status as the
    // completion signal: a lingering descendant may keep the write ends open.
    let out_reader = ReaderGuard::new(tokio::spawn(read_all(stdout)));
    let err_reader = ReaderGuard::new(tokio::spawn(read_all(stderr)));
    let code = match wait_exit(&mut exit, EXEC_TIMEOUT).await {
        Some(code) => code,
        None => {
            // Dropping the guard kills the process group.
            return error_response(
                req_id,
                io::Error::new(io::ErrorKind::TimedOut, "exec timed out"),
            );
        }
    };

    let stdout = drain_output(out_reader.take()).await;
    let stderr = drain_output(err_reader.take()).await;

    guard.disarm();
    Response {
        id: req_id,
        ok: true,
        exec: Some(ExecResponse {
            exit_code: code,
            stdout,
            stderr,
        }),
        ..Response::default()
    }
}

pub(super) fn wait(
    req_id: u64,
    req: WaitExecRequest,
    execs: &Arc<Mutex<ExecManager>>,
    tx: &mpsc::UnboundedSender<OutboundResponse>,
) {
    let exec_id = req.exec_id;
    let execs = Arc::clone(execs);
    let tx = tx.clone();
    tokio::spawn(async move {
        // The daemon-wide reaper owns waitpid; subscribe to its published exit
        // code. A missing handle means the exec was closed already — report
        // that as a normal completion, matching the PTY contract.
        let mut code = 0;
        // Bind the receiver before the `if let`: a guard that stays a scrutinee
        // temporary lives through the whole body, holding the manager lock
        // across the await below and wedging every later exec request.
        let receiver = execs.lock().await.subscribe(exec_id);
        if let Some(mut receiver) = receiver {
            loop {
                if let Some(value) = *receiver.borrow() {
                    code = value;
                    break;
                }
                if receiver.changed().await.is_err() {
                    code = -1;
                    break;
                }
            }
        }
        // Drop the handle once the status is known; a close racing this wait
        // already removed it and killed the process group.
        execs.lock().await.remove(exec_id);

        let _ = tx.send(OutboundResponse::plain(Response {
            id: req_id,
            ok: true,
            exit_code: code,
            ..Response::default()
        }));
    });
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
