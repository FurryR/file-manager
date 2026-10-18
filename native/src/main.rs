use std::collections::HashMap;
use std::env;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::net::UnixStream as StdUnixStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{mpsc, watch, Mutex, OwnedSemaphorePermit, Semaphore};
use tokio::task::JoinSet;

mod proto {
    include!(concat!(env!("OUT_DIR"), "/filedaemon.rs"));
}
mod auth;
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
const AUTH_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
const FIRST_AUTH_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);
const PREAUTH_FRAME_LIMIT: usize = 4096;
const FRAME_LIMIT: usize = 64 * 1024 * 1024;
const MAX_PENDING_AUTH: usize = 32;

#[derive(Default)]
struct Clients {
    active: std::sync::Mutex<usize>,
    has_authenticated: AtomicBool,
}

struct AuthenticatedClient {
    clients: Arc<Clients>,
    shutdown: watch::Sender<bool>,
}

impl AuthenticatedClient {
    fn new(clients: Arc<Clients>, shutdown: watch::Sender<bool>) -> io::Result<Self> {
        let mut active = clients.active.lock().unwrap();
        if *shutdown.borrow() {
            return Err(io::Error::new(io::ErrorKind::ConnectionAborted, "daemon is shutting down"));
        }
        *active += 1;
        clients.has_authenticated.store(true, Ordering::Relaxed);
        drop(active);
        Ok(Self { clients, shutdown })
    }
}

impl Drop for AuthenticatedClient {
    fn drop(&mut self) {
        let mut active = self.clients.active.lock().unwrap();
        *active -= 1;
        if *active == 0 {
            self.shutdown.send_replace(true);
        }
    }
}

/// PTYs and handles are shared across clients, but not across daemon launches.
struct Shared {
    pty_manager: Arc<Mutex<PtyManager>>,
    handles: Arc<Mutex<HandleTable>>,
    source_read_broker: copy::SourceReadBroker,
    clients: Arc<Clients>,
    /// This launch's Ed25519 public key, used to authenticate every connection.
    public_key: Vec<u8>,
    /// Derived abstract socket name, also bound into the auth challenge.
    socket_name: String,
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> io::Result<()> {
    let (public_key, id) = parse_identity_args()?;
    let socket_name = auth::derive_socket_name(&public_key, &id);
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

    let shared = Arc::new(Shared {
        pty_manager: Arc::new(Mutex::new(PtyManager::new())),
        handles: Arc::new(Mutex::new(HandleTable::new())),
        source_read_broker: copy::SourceReadBroker::default(),
        clients: Arc::new(Clients::default()),
        public_key,
        socket_name,
    });

    serve(listener, shared).await
}

async fn serve(listener: UnixListener, shared: Arc<Shared>) -> io::Result<()> {
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let mut main_shutdown = shutdown_rx.clone();
    let mut connections: JoinSet<()> = JoinSet::new();
    let pending_auth = Arc::new(Semaphore::new(MAX_PENDING_AUTH));
    let first_auth_deadline = tokio::time::Instant::now() + FIRST_AUTH_TIMEOUT;
    let mut signal_check = tokio::time::interval(std::time::Duration::from_millis(100));

    loop {
        if *main_shutdown.borrow() {
            break;
        }
        tokio::select! {
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, _)) => {
                        let Ok(permit) = pending_auth.clone().try_acquire_owned() else {
                            continue;
                        };
                        let shared = shared.clone();
                        let conn_shutdown = shutdown_rx.clone();
                        let conn_shutdown_tx = shutdown_tx.clone();
                        connections.spawn(async move {
                            if let Err(error) =
                                handle_connection(stream, shared, conn_shutdown, conn_shutdown_tx, permit).await
                            {
                                eprintln!("[file-daemon] connection ended: {error}");
                            }
                        });
                    }
                    Err(error) => eprintln!("[file-daemon] accept error: {error}"),
                }
            }
            _ = main_shutdown.changed() => break,
            _ = connections.join_next(), if !connections.is_empty() => {},
            _ = tokio::time::sleep_until(first_auth_deadline), if !shared.clients.has_authenticated.load(Ordering::Relaxed) => {
                if !shared.clients.has_authenticated.load(Ordering::Relaxed) {
                    eprintln!("[file-daemon] first authentication timed out");
                    break;
                }
            }
            _ = signal_check.tick() => {
                if TERMINATE_REQUESTED.load(Ordering::Relaxed) {
                    break;
                }
            }
        }
    }

    // Release the endpoint before draining clients; shutdown cannot accept new work.
    drop(listener);
    // Tell every connection to stop, then give them a moment to flush (e.g. the
    // `exit` ack) before tearing the process tree down.
    let _ = shutdown_tx.send(true);
    let drain = async { while connections.join_next().await.is_some() {} };
    let _ = tokio::time::timeout(std::time::Duration::from_millis(1000), drain).await;
    connections.abort_all();
    while connections.join_next().await.is_some() {}

    shared.pty_manager.lock().await.close_all();
    *shared.handles.lock().await = HandleTable::new();
    shared.source_read_broker.lock().await.clear();
    Ok(())
}

/// Other authenticated clients survive a disconnect; the last one triggers shutdown.
async fn handle_connection(
    stream: UnixStream,
    shared: Arc<Shared>,
    mut shutdown_rx: watch::Receiver<bool>,
    shutdown_tx: watch::Sender<bool>,
    auth_permit: OwnedSemaphorePermit,
) -> io::Result<()> {
    if *shutdown_rx.borrow() {
        return Ok(());
    }
    let auth_deadline = tokio::time::Instant::now() + AUTH_TIMEOUT;
    let mut auth_permit = Some(auth_permit);
    let mut authenticated_client = None;
    let mut requests = JoinSet::new();
    // Duplicate fd for independent read/write halves.
    let raw_fd = stream.as_raw_fd();
    let write_fd = unsafe { libc::fcntl(raw_fd, libc::F_DUPFD_CLOEXEC, 0) };
    if write_fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let write_std = unsafe { StdUnixStream::from_raw_fd(write_fd) };
    write_std.set_nonblocking(true)?;
    let mut write_stream = UnixStream::from_std(write_std)?;
    let mut stream = stream;

    let (response_tx, mut response_rx) = mpsc::unbounded_channel::<OutboundResponse>();
    let mut frame_reader = FrameReader::new();

    // Cancellable in-flight requests are scoped to this connection.
    let cancel_flags: Arc<Mutex<HashMap<u64, Arc<AtomicBool>>>> =
        Arc::new(Mutex::new(HashMap::new()));

    // Issue the challenge before serving anything: the client must prove it
    // holds this launch's private key by signing `auth_message`.
    let nonce = auth::random_nonce()?;
    let auth_message = auth::auth_message(&shared.socket_name, &nonce);
    let hello = proto::Response {
        id: 0,
        ok: true,
        server_hello: Some(proto::ServerHello { nonce: nonce.to_vec() }),
        ..Default::default()
    };
    tokio::time::timeout_at(auth_deadline, protocol::write_frame(&mut write_stream, hello)).await??;
    let mut authed = false;

    let result = loop {
        let shutting_down = *shutdown_rx.borrow();
        if shutting_down {
            // Flush anything already queued (e.g. the exit ack), then stop.
            while let Ok(r) = response_rx.try_recv() {
                if let Some(fd) = r.attached_fd {
                    let _ = protocol::write_response_with_fd(write_fd, &r.response, fd);
                    unsafe { libc::close(fd); }
                } else {
                    let _ = protocol::write_frame(&mut write_stream, r.response).await;
                }
            }
            break Ok(());
        }
        tokio::select! {
            _ = shutdown_rx.changed() => {}
            request_result = frame_reader.read_frame(&mut stream, if authed { FRAME_LIMIT } else { PREAUTH_FRAME_LIMIT }) => {
                match request_result {
                    Ok(Some(incoming)) => {
                        if *shutdown_rx.borrow() {
                            continue;
                        }
                        if !authed {
                            drop(incoming.attached_fd);
                            match incoming.request.command {
                                Some(proto::request::Command::Auth(request)) => {
                                    if auth::verify(&shared.public_key, &auth_message, &request.signature) {
                                        authenticated_client = Some(AuthenticatedClient::new(
                                            shared.clients.clone(), shutdown_tx.clone(),
                                        )?);
                                        authed = true;
                                        drop(auth_permit.take());
                                        let reply = proto::Response {
                                            id: incoming.request.id,
                                            ok: true,
                                            ..Default::default()
                                        };
                                        let _ = response_tx.send(OutboundResponse::plain(reply));
                                    } else {
                                        let reply = proto::Response {
                                            id: incoming.request.id,
                                            ok: false,
                                            error: "authentication failed".to_string(),
                                            ..Default::default()
                                        };
                                        tokio::time::timeout_at(
                                            auth_deadline,
                                            protocol::write_frame(&mut write_stream, reply),
                                        ).await??;
                                        break Err(io::Error::new(
                                            io::ErrorKind::PermissionDenied,
                                            "authentication failed",
                                        ));
                                    }
                                }
                                _ => {
                                    let reply = proto::Response {
                                        id: incoming.request.id,
                                        ok: false,
                                        error: "authentication required".to_string(),
                                        ..Default::default()
                                    };
                                    tokio::time::timeout_at(
                                        auth_deadline,
                                        protocol::write_frame(&mut write_stream, reply),
                                    ).await??;
                                }
                            }
                            continue;
                        }
                        let tx = response_tx.clone();
                        let shared = shared.clone();
                        let shutdown = shutdown_tx.clone();
                        let cancel_flags = cancel_flags.clone();
                        let is_exit = matches!(&incoming.request.command, Some(proto::request::Command::Exit(_)));
                        let handler = async move {
                            handlers::handle_streaming(
                                incoming.request, incoming.attached_fd,
                                &shared.pty_manager, &shared.handles,
                                cancel_flags, &shared.source_read_broker, tx,
                                &shutdown,
                            ).await;
                        };
                        // Exit must survive a client closing immediately after sending it.
                        if is_exit {
                            handler.await;
                        } else {
                            requests.spawn(handler);
                        }
                    }
                    Ok(None) => break Ok(()),
                    Err(error) => break Err(error),
                }
            }
            _ = requests.join_next(), if !requests.is_empty() => {}
            response = response_rx.recv() => {
                match response {
                    Some(r) => {
                        if let Some(fd) = r.attached_fd {
                            let result = protocol::write_response_with_fd(write_fd, &r.response, fd);
                            unsafe { libc::close(fd); }
                            if let Err(error) = result {
                                break Err(error);
                            }
                        } else if let Err(error) =
                            protocol::write_frame(&mut write_stream, r.response).await
                        {
                            break Err(error);
                        }
                    }
                    None => break Ok(()),
                }
            }
            _ = tokio::time::sleep_until(auth_deadline), if !authed => {
                break Err(io::Error::new(io::ErrorKind::TimedOut, "authentication timed out"));
            }
        }
    };

    requests.abort_all();
    while requests.join_next().await.is_some() {}
    response_rx.close();
    while let Ok(response) = response_rx.try_recv() {
        if let Some(fd) = response.attached_fd {
            unsafe { libc::close(fd); }
        }
    }
    // Connection gone: cancel whatever is still in flight on it.
    {
        let mut flags = cancel_flags.lock().await;
        for flag in flags.values() {
            flag.store(true, Ordering::Relaxed);
        }
        flags.clear();
    }
    drop(authenticated_client);
    result
}

fn parse_identity_args() -> io::Result<(Vec<u8>, String)> {
    let key = parse_value_arg("--key").ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "missing --key argument")
    })?;
    let public_key = auth::parse_hex(&key).ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "--key must be a hex string")
    })?;
    let id = parse_value_arg("--id").ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "missing --id argument")
    })?;
    Ok((public_key, id))
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
