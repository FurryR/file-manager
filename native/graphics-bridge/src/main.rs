//! Transparent vtest bridge. Preserve SCM_RIGHTS; a byte-only proxy breaks shared buffers.
use hub_client::filehub::{
    hub_request::Op, hub_response, HubRequest, HubResponse, OpenGraphicsRequest,
};
use prost::Message;
use std::io;
use std::mem;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::io::unix::AsyncFd;
use tokio::net::UnixListener;
use tokio::signal::unix::{signal, SignalKind};

type Socket = Arc<AsyncFd<OwnedFd>>;
struct Packet {
    bytes: Vec<u8>,
    fds: Vec<OwnedFd>,
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("virgl_bridge: {error}");
        std::process::exit(1);
    }
}
async fn run() -> io::Result<()> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() == 1 && args[0] == "--help" {
        println!("Usage: virgl_bridge [--socket PATH]\nExpose host VirGL through HUB_SOCKET; set VTEST_SOCKET_NAME to PATH in the container.");
        return Ok(());
    }
    let path = if args.is_empty() {
        PathBuf::from("/run/file-manager/virgl.sock")
    } else if args.len() == 2 && args[0] == "--socket" {
        PathBuf::from(&args[1])
    } else {
        return Err(io::Error::other("Usage: virgl_bridge [--socket PATH]"));
    };
    if !path.is_absolute() {
        return Err(io::Error::other("socket path must be absolute"));
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    if let Ok(meta) = std::fs::symlink_metadata(&path) {
        if !meta.file_type().is_socket() {
            return Err(io::Error::other(
                "socket path is occupied by a non-socket entry",
            ));
        }
        match tokio::time::timeout(
            std::time::Duration::from_secs(2),
            tokio::net::UnixStream::connect(&path),
        )
        .await
        {
            Ok(Ok(_)) | Err(_) => {
                return Err(io::Error::other(
                    "another bridge is listening at this socket",
                ))
            }
            Ok(Err(error)) if error.kind() == io::ErrorKind::ConnectionRefused => {
                std::fs::remove_file(&path)?
            }
            Ok(Err(error)) => return Err(error),
        }
    }
    let listener = UnixListener::bind(&path)?;
    let _cleanup = Cleanup(path.clone());
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o666))?;
    let mut terminate = signal(SignalKind::terminate())?;
    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut children = tokio::task::JoinSet::new();
    loop {
        tokio::select! {
            _ = terminate.recv() => break,
            _ = interrupt.recv() => break,
            result = children.join_next(), if !children.is_empty() => {
                if let Some(Err(error)) = result { eprintln!("virgl_bridge: client task failed: {error}"); }
            }
            accepted = listener.accept() => {
                let (stream, _) = accepted?;
                let client = async_socket(stream.into_std()?.into())?;
                children.spawn(async move {
                    if let Err(error) = bridge(client).await { eprintln!("virgl_bridge: {error}"); }
                });
            }
        }
    }
    children.abort_all();
    while children.join_next().await.is_some() {}
    Ok(())
}
struct Cleanup(PathBuf);
impl Drop for Cleanup {
    fn drop(&mut self) {
        if let Err(error) = std::fs::remove_file(&self.0) {
            if error.kind() != io::ErrorKind::NotFound {
                eprintln!("virgl_bridge: socket cleanup failed: {error}");
            }
        }
    }
}
fn async_socket(fd: OwnedFd) -> io::Result<Socket> {
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFL) };
    if flags < 0
        || unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(Arc::new(AsyncFd::new(fd)?))
}
async fn bridge(client: Socket) -> io::Result<()> {
    let host = tokio::time::timeout(std::time::Duration::from_secs(15), connect_renderer())
        .await
        .map_err(|_| io::Error::other("hub graphics handshake timed out"))??;
    tokio::try_join!(pump(client.clone(), host.clone()), pump(host, client))?;
    Ok(())
}
async fn connect_renderer() -> io::Result<Socket> {
    let name =
        std::env::var("HUB_SOCKET").map_err(|_| io::Error::other("HUB_SOCKET is not set"))?;
    let hub = connect_hub(&name).await?;
    let payload = HubRequest {
        id: 1,
        op: Some(Op::OpenGraphics(OpenGraphicsRequest {})),
    }
    .encode_to_vec();
    let mut bytes = (payload.len() as u32).to_be_bytes().to_vec();
    bytes.extend(payload);
    send(&hub, Packet { bytes, fds: vec![] }).await?;
    let mut attached = Vec::new();
    let header = exact(&hub, 4, &mut attached).await?;
    let length = u32::from_be_bytes(header.try_into().unwrap()) as usize;
    if length > 64 * 1024 {
        return Err(io::Error::other("hub response is too large"));
    }
    let payload = exact(&hub, length, &mut attached).await?;
    let response = HubResponse::decode(payload.as_slice()).map_err(io::Error::other)?;
    if response.id != 1 || !response.ok {
        return Err(io::Error::other(if response.error.is_empty() {
            "invalid hub response".to_owned()
        } else {
            response.error
        }));
    }
    if !matches!(
        response.result,
        Some(hub_response::Result::GraphicsConnection(_))
    ) || attached.len() != 1
    {
        return Err(io::Error::other(
            "hub did not return exactly one renderer socket",
        ));
    }
    async_socket(attached.remove(0))
}
async fn exact(socket: &Socket, length: usize, attached: &mut Vec<OwnedFd>) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::with_capacity(length);
    while bytes.len() < length {
        let packet = receive(socket, length - bytes.len()).await?;
        if packet.bytes.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "hub closed the connection",
            ));
        }
        bytes.extend(packet.bytes);
        attached.extend(packet.fds);
        if attached.len() > 1 {
            return Err(io::Error::other("too many hub descriptors"));
        }
    }
    Ok(bytes)
}
async fn pump(from: Socket, to: Socket) -> io::Result<()> {
    loop {
        let packet = receive(&from, 64 * 1024).await?;
        if packet.bytes.is_empty() {
            unsafe { libc::shutdown(to.as_raw_fd(), libc::SHUT_WR) };
            return Ok(());
        }
        send(&to, packet).await?;
    }
}
async fn receive(socket: &Socket, capacity: usize) -> io::Result<Packet> {
    loop {
        let mut readiness = socket.readable().await?;
        let result = readiness.try_io(|fd| {
            // UNIX stream reads can combine preceding plain bytes with a later
            // SCM_RIGHTS message (unix(7), ancillary-data barrier). Find that
            // boundary before consuming bytes, otherwise forwarding rights at
            // byte zero moves them onto an earlier vtest protocol header.
            let preview = receive_now(fd.as_raw_fd(), capacity, libc::MSG_PEEK)?;
            let available = preview.bytes.len();
            if available == 0 {
                return Ok(preview);
            }
            let has_rights = !preview.fds.is_empty();
            drop(preview); // Every MSG_PEEK duplicates received descriptors.
            let mut length = available;
            if has_rights {
                let (mut low, mut high) = (1, available);
                while low < high {
                    let middle = low + (high - low) / 2;
                    let peek = receive_now(fd.as_raw_fd(), middle, libc::MSG_PEEK)?;
                    if peek.fds.is_empty() {
                        low = middle + 1;
                    } else {
                        high = middle;
                    }
                }
                if low > 1 {
                    length = low - 1;
                }
            }
            receive_now(fd.as_raw_fd(), length, 0)
        });
        match result {
            Ok(Err(error)) if error.kind() == io::ErrorKind::Interrupted => continue,
            Ok(result) => return result,
            Err(_) => continue,
        }
    }
}
fn receive_now(fd: RawFd, capacity: usize, flags: libc::c_int) -> io::Result<Packet> {
    let mut bytes = vec![0u8; capacity];
    let control_size = unsafe { libc::CMSG_SPACE((64 * mem::size_of::<RawFd>()) as _) } as usize;
    let mut control = vec![0usize; control_size.div_ceil(mem::size_of::<usize>())];
    let mut vector = libc::iovec {
        iov_base: bytes.as_mut_ptr().cast(),
        iov_len: bytes.len(),
    };
    let mut message: libc::msghdr = unsafe { mem::zeroed() };
    message.msg_iov = &mut vector;
    message.msg_iovlen = 1;
    message.msg_control = control.as_mut_ptr().cast();
    message.msg_controllen = control_size as _;
    let count = unsafe {
        libc::recvmsg(
            fd,
            &mut message,
            libc::MSG_CMSG_CLOEXEC | libc::MSG_DONTWAIT | flags,
        )
    };
    if count < 0 {
        return Err(io::Error::last_os_error());
    }
    let mut fds = Vec::new();
    let mut header = unsafe { libc::CMSG_FIRSTHDR(&message) };
    while !header.is_null() {
        unsafe {
            if (*header).cmsg_level == libc::SOL_SOCKET && (*header).cmsg_type == libc::SCM_RIGHTS {
                let size = ((*header).cmsg_len as usize).saturating_sub(libc::CMSG_LEN(0) as usize);
                let data = libc::CMSG_DATA(header).cast::<RawFd>();
                for index in 0..size / mem::size_of::<RawFd>() {
                    fds.push(OwnedFd::from_raw_fd(*data.add(index)));
                }
            }
            header = libc::CMSG_NXTHDR(&message, header);
        }
    }
    if message.msg_flags & (libc::MSG_CTRUNC | libc::MSG_TRUNC) != 0 {
        return Err(io::Error::other("truncated graphics packet/descriptors"));
    }
    bytes.truncate(count as usize);
    Ok(Packet { bytes, fds })
}

async fn connect_hub(name: &str) -> io::Result<Socket> {
    if name.is_empty() || name.len() > 107 {
        return Err(io::Error::other("invalid hub socket name"));
    }
    loop {
        let raw = unsafe {
            libc::socket(
                libc::AF_UNIX,
                libc::SOCK_STREAM | libc::SOCK_NONBLOCK | libc::SOCK_CLOEXEC,
                0,
            )
        };
        if raw < 0 {
            return Err(io::Error::last_os_error());
        }
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        let mut address: libc::sockaddr_un = unsafe { mem::zeroed() };
        address.sun_family = libc::AF_UNIX as _;
        for (index, byte) in name.bytes().enumerate() {
            address.sun_path[index + 1] = byte as _;
        }
        let length = (mem::size_of::<libc::sa_family_t>() + 1 + name.len()) as libc::socklen_t;
        if unsafe { libc::connect(raw, (&address as *const libc::sockaddr_un).cast(), length) } == 0
        {
            return async_socket(fd);
        }
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::WouldBlock {
            drop(fd);
            tokio::time::sleep(std::time::Duration::from_millis(25)).await;
            continue;
        }
        return Err(error);
    }
}
async fn send(socket: &Socket, packet: Packet) -> io::Result<()> {
    let mut offset = 0;
    while offset < packet.bytes.len() {
        let mut readiness = socket.writable().await?;
        let result = readiness.try_io(|fd| {
            let mut vector = libc::iovec {
                iov_base: packet.bytes[offset..].as_ptr() as *mut _,
                iov_len: packet.bytes.len() - offset,
            };
            let size =
                unsafe { libc::CMSG_SPACE((packet.fds.len() * mem::size_of::<RawFd>()) as _) }
                    as usize;
            let mut control = vec![0usize; size.div_ceil(mem::size_of::<usize>())];
            let mut message: libc::msghdr = unsafe { mem::zeroed() };
            message.msg_iov = &mut vector;
            message.msg_iovlen = 1;
            if offset == 0 && !packet.fds.is_empty() {
                message.msg_control = control.as_mut_ptr().cast();
                message.msg_controllen = size as _;
                unsafe {
                    let header = libc::CMSG_FIRSTHDR(&message);
                    (*header).cmsg_level = libc::SOL_SOCKET;
                    (*header).cmsg_type = libc::SCM_RIGHTS;
                    (*header).cmsg_len =
                        libc::CMSG_LEN((packet.fds.len() * mem::size_of::<RawFd>()) as _) as _;
                    let data = libc::CMSG_DATA(header).cast::<RawFd>();
                    for (index, passed) in packet.fds.iter().enumerate() {
                        *data.add(index) = passed.as_raw_fd();
                    }
                }
            }
            let count = unsafe {
                libc::sendmsg(
                    fd.as_raw_fd(),
                    &message,
                    libc::MSG_NOSIGNAL | libc::MSG_DONTWAIT,
                )
            };
            if count < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(count as usize)
            }
        });
        match result {
            Ok(Ok(0)) => {
                return Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "graphics socket closed",
                ))
            }
            Ok(Ok(count)) => offset += count,
            Ok(Err(error)) if error.kind() == io::ErrorKind::Interrupted => continue,
            Ok(Err(error)) => return Err(error),
            Err(_) => continue,
        }
    }
    Ok(())
}
