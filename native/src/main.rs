use std::collections::HashMap;
use std::env;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::net::UnixStream as StdUnixStream;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use tokio::sync::{mpsc, Mutex};

mod proto {
    include!(concat!(env!("OUT_DIR"), "/filedaemon.rs"));
}
mod copy;
mod handlers;
mod handles;
mod list;
mod protocol;
mod pty;
mod socket;

use handles::HandleTable;
use proto::Response;
use pty::PtyManager;

#[tokio::main(flavor = "current_thread")]
async fn main() -> io::Result<()> {
    let socket_name = parse_socket_arg()?;
    let listener = socket::create_abstract_server(&socket_name)?;
    unsafe {
        libc::signal(libc::SIGCHLD, libc::SIG_IGN);
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
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
    let (response_tx, mut response_rx) = mpsc::unbounded_channel::<Response>();

    loop {
        tokio::select! {
            request_result = protocol::read_frame(&mut stream) => {
                match request_result {
                    Ok(Some(request)) => {
                        let tx = response_tx.clone();
                        let cancel_flags = cancel_flags.clone();
                        let pty_manager = pty_manager.clone();
                        let handles = handles.clone();
                        let _socket_fd = stream.as_raw_fd();
                        let write_fd = write_fd;
                        tokio::spawn(async move {
                            handlers::handle_streaming(
                                request, &pty_manager, &handles, &cancel_flags, _socket_fd, write_fd, tx,
                            ).await;
                        });
                    }
                    Ok(None) | Err(_) => break,
                }
            }
            response = response_rx.recv() => {
                match response {
                    Some(r) => protocol::write_frame(&mut write_stream, r).await?,
                    None => break,
                }
            }
        }
    }
    Ok(())
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
