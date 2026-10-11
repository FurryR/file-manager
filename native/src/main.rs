use std::collections::HashMap;
use std::env;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::net::UnixStream as StdUnixStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::{mpsc, Mutex};

mod proto {
    include!(concat!(env!("OUT_DIR"), "/filedaemon.rs"));
}
mod container;
mod copy;
mod handlers;
mod handles;
mod list;
mod protocol;
mod pty;
mod socket;

use handles::HandleTable;
use pty::PtyManager;
use protocol::{FrameReader, OutboundResponse};

static TERMINATE_REQUESTED: AtomicBool = AtomicBool::new(false);

#[tokio::main(flavor = "current_thread")]
async fn main() -> io::Result<()> {
    let socket_name = parse_socket_arg()?;
    let chroot_rootfs = parse_value_arg("--chroot");
    let proot_mode = has_flag("--proot");

    eprintln!(
        "[file-daemon] v{} pid={}",
        env!("CARGO_PKG_VERSION"),
        std::process::id()
    );

    // Container daemons own the process tree: become a session/group leader so
    // the whole container can be signalled as a unit. Ignore failure (e.g. when
    // already a group leader).
    if chroot_rootfs.is_some() || proot_mode {
        unsafe { libc::setsid(); }
        list::set_container_mode(true);
    }

    // chroot backend: isolate the mount tree and enter the rootfs here. The
    // proot backend is already inside the container (launched via proot), so it
    // does no setup.
    if let Some(rootfs) = chroot_rootfs {
        let binds = parse_bind_args();
        container::enter_chroot(&rootfs, &binds)?;
    }

    let listener = socket::create_abstract_server(&socket_name)?;
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        let shutdown_handler = request_shutdown as *const () as libc::sighandler_t;
        libc::signal(libc::SIGTERM, shutdown_handler);
        libc::signal(libc::SIGHUP, shutdown_handler);
        libc::signal(libc::SIGINT, shutdown_handler);
    };

    let (mut stream, _) = listener.accept().await?;

    // Duplicate fd for independent read/write halves
    let raw_fd = stream.as_raw_fd();
    let write_fd = unsafe { libc::dup(raw_fd) };
    if write_fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let write_std = unsafe { StdUnixStream::from_raw_fd(write_fd) };
    write_std.set_nonblocking(true)?;
    let mut write_stream = tokio::net::UnixStream::from_std(write_std)?;

    let cancel_flags: Arc<Mutex<HashMap<u64, Arc<AtomicBool>>>> = Arc::new(Mutex::new(HashMap::new()));
    let pty_manager: Arc<Mutex<PtyManager>> = Arc::new(Mutex::new(PtyManager::new()));
    let handles: Arc<Mutex<HandleTable>> = Arc::new(Mutex::new(HandleTable::new()));

    // Channel for handlers to send responses back to the write loop
    let (response_tx, mut response_rx) = mpsc::unbounded_channel::<OutboundResponse>();
    let source_read_broker = copy::SourceReadBroker::default();
    let mut frame_reader = FrameReader::new();

    let loop_result = loop {
        tokio::select! {
            request_result = frame_reader.read_frame(&mut stream) => {
                match request_result {
                    Ok(Some(incoming)) => {
                        let tx = response_tx.clone();
                        let cancel_flags = cancel_flags.clone();
                        let pty_manager = pty_manager.clone();
                        let handles = handles.clone();
                        let source_read_broker = source_read_broker.clone();
                        tokio::spawn(async move {
                            handlers::handle_streaming(
                                incoming.request, incoming.attached_fd, &pty_manager, &handles,
                                &cancel_flags, &source_read_broker, tx,
                            ).await;
                        });
                    }
                    Ok(None) => break Ok(()),
                    Err(error) => break Err(error),
                }
            }
            response = response_rx.recv() => {
                match response {
                    Some(r) => {
                        if let Some(fd) = r.attached_fd {
                            let result = protocol::write_response_with_fd(write_fd, &r.response, fd);
                            unsafe { libc::close(fd); }
                            if let Err(error) = result {
                                break Err(error);
                            }
                        } else {
                            if let Err(error) = protocol::write_frame(&mut write_stream, r.response).await {
                                break Err(error);
                            }
                        }
                    }
                    None => break Ok(()),
                }
            }
            _ = tokio::time::sleep(std::time::Duration::from_millis(100)) => {
                if TERMINATE_REQUESTED.load(Ordering::Relaxed) {
                    break Ok(());
                }
            }
        }
    };
    pty_manager.lock().await.close_all();
    loop_result
}

fn parse_socket_arg() -> io::Result<String> {
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--socket" {
            if let Some(path) = args.next() {
                return Ok(path);
            }
        }
    }
    Err(io::Error::new(
        io::ErrorKind::InvalidInput,
        "missing --socket argument",
    ))
}

/// Return the value following `name` (e.g. `--chroot <rootfs>`).
fn parse_value_arg(name: &str) -> Option<String> {
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == name {
            return args.next();
        }
    }
    None
}

/// Whether a valueless flag (e.g. `--proot`) is present.
fn has_flag(name: &str) -> bool {
    env::args().skip(1).any(|arg| arg == name)
}

/// Collect repeated `--bind host:container` arguments.
fn parse_bind_args() -> Vec<(String, String)> {
    let mut binds = Vec::new();
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--bind" {
            if let Some(spec) = args.next() {
                if let Some((source, target)) = spec.split_once(':') {
                    binds.push((source.to_string(), target.to_string()));
                }
            }
        }
    }
    binds
}

extern "C" fn request_shutdown(_: libc::c_int) {
    TERMINATE_REQUESTED.store(true, Ordering::Relaxed);
}

#[cfg(test)]
mod tests {
    use super::proto;

    #[test]
    fn spawn_pty_request_round_trip() {
        let request = proto::SpawnPtyRequest {
            command: "/bin/sh".to_string(),
            args: vec!["-c".to_string(), "echo hello".to_string()],
            env: std::collections::HashMap::from([
                ("TERM".to_string(), "xterm-256color".to_string()),
                ("HOME".to_string(), "/root".to_string()),
            ]),
            term_type: "xterm-256color".to_string(),
            rows: 24,
            cols: 80,
            cwd: "/tmp".to_string(),
            use_root: true,
        };

        use prost::Message;
        let mut buf = Vec::new();
        request.encode(&mut buf).expect("encode should succeed");

        let decoded = proto::SpawnPtyRequest::decode(buf.as_slice())
            .expect("decode should succeed");

        assert_eq!(decoded.command, "/bin/sh");
        assert_eq!(decoded.args, vec!["-c", "echo hello"]);
        assert_eq!(decoded.term_type, "xterm-256color");
        assert_eq!(decoded.rows, 24);
        assert_eq!(decoded.cols, 80);
        assert_eq!(decoded.cwd, "/tmp");
        assert!(decoded.use_root);
        assert_eq!(decoded.env.get("TERM").map(|s| s.as_str()), Some("xterm-256color"));
        assert_eq!(decoded.env.get("HOME").map(|s| s.as_str()), Some("/root"));
    }
}
