//! Shared hub wire protocol for the native terminal utilities.
use prost::Message;
use std::collections::{HashMap, VecDeque};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::{env, io, mem};

use tokio::io::Interest;
use tokio::net::UnixStream;

pub mod filehub {
    include!(concat!(env!("OUT_DIR"), "/filehub.rs"));
}
pub mod fileagent {
    include!(concat!(env!("OUT_DIR"), "/fileagent.rs"));
}
use filehub::{HubRequest, HubResponse};

/// Responses that arrived while another id was awaited. Keeping them is what
/// makes correlation *by id* instead of *by arrival order*: a response for a
/// request that was abandoned (timeout, cancelled tool call) can no longer shift
/// the stream and make every later request fail with a stale id.
const MAX_STRAY: usize = 512;

#[derive(Default)]
struct Stray {
    /// Frames keyed by request id; a stream (chat, session titles) keeps its own
    /// queue and therefore its own order.
    frames: HashMap<u32, VecDeque<(HubResponse, Vec<OwnedFd>)>>,
    /// Ids in arrival order, used to evict the oldest when nobody claims them.
    order: VecDeque<u32>,
    /// Total parked frames, so one abandoned stream cannot grow without bound.
    total: usize,
}

const MAX_STRAY_FRAMES: usize = 8 * MAX_STRAY;

/// A live hub connection. Requests are correlated by their `id`; the transport
/// is a non-blocking tokio socket, so callers await instead of blocking a
/// thread, and there is no socket-level receive/send deadline.
pub struct Connection {
    stream: UnixStream,
    stray: Mutex<Stray>,
    /// Set once the stream can no longer be trusted; later calls fail fast
    /// instead of silently reading somebody else's response.
    poisoned: AtomicBool,
}

impl Connection {
    /// Connect and perform the Hello handshake using `$HUB_SOCKET`.
    pub async fn connect_from_env() -> io::Result<(Self, filehub::CallerRoot)> {
        let value = env::var("HUB_SOCKET").map_err(|_| {
            io::Error::other(
                "$HUB_SOCKET is not set; invoke this command from an app-spawned terminal",
            )
        })?;
        let (name, key) = value
            .split_once('@')
            .ok_or_else(|| io::Error::other("$HUB_SOCKET is missing its daemon key"))?;
        if name.is_empty() || key.is_empty() {
            return Err(io::Error::other("$HUB_SOCKET is malformed"));
        }
        let connection = Self {
            stream: connect_abstract(name)?,
            stray: Mutex::new(Stray::default()),
            poisoned: AtomicBool::new(false),
        };
        let response = connection
            .round_trip(HubRequest {
                id: 0,
                op: Some(filehub::hub_request::Op::Hello(filehub::HelloRequest {
                    terminal_id: env::var("HUB_TERMINAL").unwrap_or_default(),
                    key: key.to_string(),
                })),
            })
            .await?;
        if !response.ok {
            return Err(io::Error::other(response.error));
        }
        match response.result {
            Some(filehub::hub_response::Result::CallerRoot(root)) => Ok((connection, root)),
            _ => Err(io::Error::other("hub returned no caller root")),
        }
    }

    pub fn as_raw_fd(&self) -> RawFd {
        self.stream.as_raw_fd()
    }

    pub async fn round_trip(&self, request: HubRequest) -> io::Result<HubResponse> {
        let (response, _attached) = self.round_trip_with_fds(request, &[]).await?;
        Ok(response)
    }

    /// Send a request without waiting for its response. Used by streaming
    /// requests such as Chat, whose responses are read with
    /// [Self::receive_response].
    pub async fn send_request(&self, request: HubRequest) -> io::Result<()> {
        self.send_frame(&request.encode_to_vec(), &[]).await
    }

    pub async fn round_trip_with_fd(
        &self,
        request: HubRequest,
        attached_fd: Option<RawFd>,
    ) -> io::Result<(HubResponse, Option<OwnedFd>)> {
        let attached: Vec<RawFd> = attached_fd.into_iter().collect();
        let (response, fds) = self.round_trip_with_fds(request, &attached).await?;
        let mut fds = fds.into_iter();
        Ok((response, fds.next()))
    }

    pub async fn round_trip_with_fds(
        &self,
        request: HubRequest,
        attached_fds: &[RawFd],
    ) -> io::Result<(HubResponse, Vec<OwnedFd>)> {
        self.send_frame(&request.encode_to_vec(), attached_fds)
            .await?;
        self.receive_response(request.id).await
    }

    /// Await the response for `id`, keeping responses for other ids aside.
    ///
    /// A caller that gives up on a request (deadline, cancellation) leaves one
    /// unmatched response in flight; it is parked under its own id instead of
    /// being mistaken for the next request's answer.
    pub async fn receive_response(&self, id: u32) -> io::Result<(HubResponse, Vec<OwnedFd>)> {
        if self.poisoned.load(Ordering::Relaxed) {
            return Err(io::Error::other("hub connection is no longer usable"));
        }
        loop {
            if let Some(response) = self.take_stray(id) {
                return Ok(response);
            }
            let (payload, attached) = self.recv_frame().await?;
            let response = HubResponse::decode(payload.as_slice()).map_err(|error| {
                self.poison("malformed hub response");
                io::Error::other(error.to_string())
            })?;
            if response.id == id {
                return Ok((response, attached));
            }
            // An abandoned chat keeps streaming for a while; reading through
            // its parked events is expected, so only a stash that cannot keep
            // up may end this loop early.
            if !self.stash(response, attached) {
                self.poison("too many unmatched hub responses");
                return Err(io::Error::other(
                    "hub response stream is out of sync and was dropped",
                ));
            }
        }
    }

    fn take_stray(&self, id: u32) -> Option<(HubResponse, Vec<OwnedFd>)> {
        let mut stray = self.stray.lock().unwrap();
        let entry = stray.frames.get_mut(&id)?;
        let response = entry.pop_front()?;
        let drained = entry.is_empty();
        stray.total -= 1;
        if drained {
            stray.frames.remove(&id);
            stray.order.retain(|queued| *queued != id);
        }
        Some(response)
    }

    /// Park a response nobody is waiting for. Returns `false` only when the
    /// parked set is so large that the correlation state cannot be trusted.
    fn stash(&self, response: HubResponse, fds: Vec<OwnedFd>) -> bool {
        let mut stray = self.stray.lock().unwrap();
        if !stray.frames.contains_key(&response.id) {
            stray.order.push_back(response.id);
        }
        stray
            .frames
            .entry(response.id)
            .or_default()
            .push_back((response, fds));
        stray.total += 1;
        while stray.order.len() > MAX_STRAY || stray.total > MAX_STRAY_FRAMES {
            let Some(evicted) = stray.order.pop_front() else { break };
            if let Some(queue) = stray.frames.remove(&evicted) {
                stray.total -= queue.len();
            }
        }
        stray.order.len() <= MAX_STRAY
    }

    fn poison(&self, why: &str) {
        if !self.poisoned.swap(true, Ordering::Relaxed) {
            eprintln!("hub connection dropped: {why}");
        }
    }

    pub async fn send_frame(&self, payload: &[u8], attached_fds: &[RawFd]) -> io::Result<()> {
        let mut frame = Vec::with_capacity(4 + payload.len());
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(payload);
        let mut sent = 0;
        let mut attached_fds = attached_fds;
        let fd = self.stream.as_raw_fd();
        while sent < frame.len() {
            // SCM_RIGHTS must accompany only the first successful partial write.
            let attached = attached_fds;
            let n = self
                .stream
                .async_io(Interest::WRITABLE, || {
                    send_some(fd, &frame[sent..], attached)
                })
                .await?;
            if n == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "send returned zero bytes",
                ));
            }
            sent += n;
            attached_fds = &[];
        }
        Ok(())
    }

    async fn recv_frame(&self) -> io::Result<(Vec<u8>, Vec<OwnedFd>)> {
        let mut attached = Vec::new();
        let header = self.recv_exact(4, &mut attached).await?;
        let size = u32::from_be_bytes([header[0], header[1], header[2], header[3]]) as usize;
        if size > 64 * 1024 * 1024 {
            return Err(io::Error::other("hub frame too large"));
        }
        let payload = self.recv_exact(size, &mut attached).await?;
        Ok((payload, attached))
    }

    async fn recv_exact(&self, len: usize, attached: &mut Vec<OwnedFd>) -> io::Result<Vec<u8>> {
        let mut buf = vec![0u8; len];
        let mut got = 0;
        let fd = self.stream.as_raw_fd();
        while got < len {
            let n = self
                .stream
                .async_io(Interest::READABLE, || {
                    recv_some(fd, &mut buf[got..], attached)
                })
                .await?;
            if n == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "hub closed connection",
                ));
            }
            got += n;
        }
        Ok(buf)
    }
}

fn send_some(fd: RawFd, data: &[u8], attached_fds: &[RawFd]) -> io::Result<usize> {
    let result = if attached_fds.is_empty() {
        send_plain(fd, data)
    } else {
        send_with_fds(fd, data, attached_fds)
    };
    match result {
        Err(error) if error.kind() == io::ErrorKind::Interrupted => {
            send_some(fd, data, attached_fds)
        }
        other => other,
    }
}

fn send_plain(fd: RawFd, data: &[u8]) -> io::Result<usize> {
    let n = unsafe { libc::send(fd, data.as_ptr().cast(), data.len(), libc::MSG_NOSIGNAL) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

fn send_with_fds(fd: RawFd, data: &[u8], rights_fds: &[RawFd]) -> io::Result<usize> {
    let data_len = mem::size_of_val(rights_fds) as libc::c_uint;
    let size = unsafe { libc::CMSG_SPACE(data_len) } as usize;
    let mut control = vec![0usize; size.div_ceil(mem::size_of::<usize>())];
    let mut iov = libc::iovec {
        iov_base: data.as_ptr() as *mut libc::c_void,
        iov_len: data.len(),
    };
    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = size as _;
    unsafe {
        let cmsg = libc::CMSG_FIRSTHDR(&msg);
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        (*cmsg).cmsg_len = libc::CMSG_LEN(data_len) as _;
        let data = libc::CMSG_DATA(cmsg) as *mut RawFd;
        for (index, rights_fd) in rights_fds.iter().enumerate() {
            *data.add(index) = *rights_fd;
        }
    }
    let n = unsafe { libc::sendmsg(fd, &msg, libc::MSG_NOSIGNAL) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

fn recv_some(fd: RawFd, buf: &mut [u8], attached: &mut Vec<OwnedFd>) -> io::Result<usize> {
    let mut iov = libc::iovec {
        iov_base: buf.as_mut_ptr() as *mut libc::c_void,
        iov_len: buf.len(),
    };
    // Enough room for every descriptor a response can carry (stdout+stderr is
    // the largest current frame); MSG_CTRUNC still rejects anything bigger.
    const MAX_FDS: usize = 8;
    let control_len =
        unsafe { libc::CMSG_SPACE((MAX_FDS * mem::size_of::<RawFd>()) as u32) as usize };
    let mut control = vec![0usize; control_len.div_ceil(mem::size_of::<usize>())];
    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = control_len as _;

    let n = unsafe { libc::recvmsg(fd, &mut msg, libc::MSG_CMSG_CLOEXEC) };
    if n < 0 {
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::Interrupted {
            return recv_some(fd, buf, attached);
        }
        return Err(error);
    }

    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    while !cmsg.is_null() {
        let header = unsafe { &*cmsg };
        if header.cmsg_level == libc::SOL_SOCKET && header.cmsg_type == libc::SCM_RIGHTS {
            let data_len =
                (header.cmsg_len as usize).saturating_sub(unsafe { libc::CMSG_LEN(0) as usize });
            let count = data_len / mem::size_of::<RawFd>();
            let data = unsafe { libc::CMSG_DATA(cmsg) as *const RawFd };
            for i in 0..count {
                let received = unsafe { *data.add(i) };
                if received < 0 {
                    continue;
                }
                attached.push(unsafe { OwnedFd::from_raw_fd(received) });
            }
        }
        cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
    }
    if msg.msg_flags & libc::MSG_CTRUNC != 0 {
        return Err(io::Error::other("truncated hub descriptors"));
    }
    Ok(n as usize)
}

// ── Socket ───────────────────────────────────────────────────────────

fn connect_abstract(name: &str) -> io::Result<UnixStream> {
    let bytes = name.as_bytes();
    if bytes.len() > 107 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "hub socket name too long",
        ));
    }
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };
    let mut addr: libc::sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (index, byte) in bytes.iter().enumerate() {
        addr.sun_path[index + 1] = *byte as libc::c_char;
    }
    let length = (mem::size_of::<libc::sa_family_t>() + 1 + bytes.len()) as libc::socklen_t;
    let result = unsafe {
        libc::connect(
            owned.as_raw_fd(),
            &addr as *const _ as *const libc::sockaddr,
            length,
        )
    };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    let stream = std::os::unix::net::UnixStream::from(owned);
    stream.set_nonblocking(true)?;
    UnixStream::from_std(stream)
}
