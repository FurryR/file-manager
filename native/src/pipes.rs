//! Connection-owned, byte-transparent stdio channels for LSP and other protocols.
use crate::{proto, reaper};
use std::{
    collections::HashMap,
    io,
    process::Stdio,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    process::{ChildStderr, ChildStdin, ChildStdout},
    sync::{watch, Mutex},
};

#[derive(Default)]
pub struct PipeManager {
    processes: Mutex<HashMap<u64, Arc<Process>>>,
}
struct Process {
    pid: u32,
    input: Mutex<Option<ChildStdin>>,
    output: Mutex<Option<ChildStdout>>,
    error: Mutex<Option<ChildStderr>>,
    exit: watch::Receiver<Option<i32>>,
    close: watch::Sender<bool>,
}
impl PipeManager {
    pub async fn spawn(
        &self,
        request: proto::SpawnPipeRequest,
    ) -> io::Result<proto::SpawnPipeResponse> {
        if request.command.is_empty() {
            return Err(io::Error::other("pipe command is required"));
        }
        let mut command = tokio::process::Command::new(&request.command);
        command
            .args(request.args)
            .envs(crate::services::command_environment(None))
            .envs(request.env)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .process_group(0);
        if !request.cwd.is_empty() {
            command.current_dir(request.cwd);
        }
        let mut child = command.spawn()?;
        let pid = child.id().unwrap();
        reaper::track(pid);
        let mut group = reaper::ProcessGroup(pid);
        let (exit_tx, exit) = watch::channel(None);
        let (close, mut closed) = watch::channel(false);
        let process = Arc::new(Process {
            pid,
            input: Mutex::new(child.stdin.take()),
            output: Mutex::new(child.stdout.take()),
            error: Mutex::new(child.stderr.take()),
            exit,
            close,
        });
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        self.processes.lock().await.insert(id, process);
        tokio::spawn(async move {
            let status = tokio::select! {
                result = child.wait() => result,
                _ = closed.changed() => {
                    unsafe { libc::kill(-(pid as i32), libc::SIGTERM); }
                    match tokio::time::timeout(std::time::Duration::from_millis(700), child.wait()).await {
                        Ok(status) => status,
                        Err(_) => {
                            unsafe { libc::kill(-(pid as i32), libc::SIGKILL); }
                            let _ = child.start_kill(); child.wait().await
                        }
                    }
                }
            };
            group.finish();
            reaper::release(pid);
            use std::os::unix::process::ExitStatusExt;
            let code = status
                .map(|s| s.code().unwrap_or_else(|| -s.signal().unwrap_or(1)))
                .unwrap_or(-1);
            exit_tx.send_replace(Some(code));
        });
        Ok(proto::SpawnPipeResponse {
            process_id: id,
            pid,
        })
    }
    async fn get(&self, id: u64) -> io::Result<Arc<Process>> {
        self.processes
            .lock()
            .await
            .get(&id)
            .cloned()
            .ok_or_else(|| io::Error::other("pipe process is closed"))
    }
    pub async fn read(
        &self,
        request: proto::ReadPipeRequest,
    ) -> io::Result<proto::ReadPipeResponse> {
        let process = self.get(request.process_id).await?;
        let mut closed = process.close.subscribe();
        if *closed.borrow() {
            return Ok(proto::ReadPipeResponse {
                data: Vec::new(),
                eof: true,
            });
        }
        let mut data = vec![0; (request.length as usize).clamp(1, 64 * 1024)];
        let read = async {
            if request.stderr {
                match process.error.lock().await.as_mut() {
                    Some(reader) => reader.read(&mut data).await,
                    None => Ok(0),
                }
            } else {
                match process.output.lock().await.as_mut() {
                    Some(reader) => reader.read(&mut data).await,
                    None => Ok(0),
                }
            }
        };
        let length = tokio::select! { result = read => result?, _ = closed.changed() => 0 };
        data.truncate(length);
        Ok(proto::ReadPipeResponse {
            data,
            eof: length == 0,
        })
    }
    pub async fn write(&self, request: proto::WritePipeRequest) -> io::Result<()> {
        if request.data.len() > 64 * 1024 {
            return Err(io::Error::other("pipe write exceeds 64 KiB"));
        }
        let process = self.get(request.process_id).await?;
        let mut closed = process.close.subscribe();
        if *closed.borrow() {
            return Err(io::Error::other("pipe process is closed"));
        }
        let write = async {
            let mut input = process.input.lock().await;
            if !request.data.is_empty() {
                input
                    .as_mut()
                    .ok_or_else(|| io::Error::other("stdin is closed"))?
                    .write_all(&request.data)
                    .await?;
            }
            if request.close_stdin {
                input.take();
            }
            Ok(())
        };
        tokio::select! { result = write => result, _ = closed.changed() => Err(io::Error::other("pipe process is closed")) }
    }
    pub async fn wait(&self, id: u64) -> io::Result<i32> {
        let mut exit = self.get(id).await?.exit.clone();
        loop {
            if let Some(code) = *exit.borrow() {
                return Ok(code);
            }
            exit.changed()
                .await
                .map_err(|_| io::Error::other("pipe waiter closed"))?;
        }
    }
    pub async fn signal(&self, id: u64, signal: i32) -> io::Result<()> {
        let process = self.get(id).await?;
        if process.exit.borrow().is_some() {
            return Ok(());
        }
        if !(1..=64).contains(&signal) {
            return Err(io::Error::other("invalid signal"));
        }
        if unsafe { libc::kill(-(process.pid as i32), signal) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
    pub async fn close(&self, id: u64) {
        if let Some(process) = self.processes.lock().await.remove(&id) {
            process.close.send_replace(true);
        }
    }
    pub async fn close_all(&self) {
        for (_, process) in self.processes.lock().await.drain() {
            process.close.send_replace(true);
        }
    }
}
