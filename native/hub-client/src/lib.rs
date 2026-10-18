//! Shared hub wire protocol for the native terminal utilities.
use prost::Message;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::OpenOptionsExt;
use std::{env, io, mem};

pub mod filehub {
    include!(concat!(env!("OUT_DIR"), "/filehub.rs"));
}
use filehub::{HubRequest, HubResponse};

pub fn connect_from_env() -> io::Result<(OwnedFd, filehub::CallerRoot)> {
    let name = env::var("HUB_SOCKET").map_err(|_| {
        io::Error::other("$HUB_SOCKET is not set; invoke this command from an app-spawned terminal")
    })?;
    let socket = connect_abstract(&name)?;
    let timeout = libc::timeval {
        tv_sec: 10,
        tv_usec: 0,
    };
    for option in [libc::SO_RCVTIMEO, libc::SO_SNDTIMEO] {
        if unsafe {
            libc::setsockopt(
                socket.as_raw_fd(),
                libc::SOL_SOCKET,
                option,
                (&timeout as *const libc::timeval).cast(),
                mem::size_of_val(&timeout) as _,
            )
        } != 0
        {
            return Err(io::Error::last_os_error());
        }
    }
    let root = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC)
        .open("/")?;
    let (response, _) = round_trip_with_attached_fd(
        socket.as_raw_fd(),
        HubRequest {
            id: 0,
            op: Some(filehub::hub_request::Op::Hello(filehub::HelloRequest {
                terminal_id: env::var("HUB_TERMINAL").unwrap_or_default(),
            })),
        },
        Some(root.as_raw_fd()),
    )?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    match response.result {
        Some(filehub::hub_response::Result::CallerRoot(root)) => Ok((socket, root)),
        _ => Err(io::Error::other("hub returned no caller root")),
    }
}
pub fn round_trip(fd: RawFd, request: HubRequest) -> io::Result<HubResponse> {
    let (response, _attached) = round_trip_with_fd(fd, request)?;
    Ok(response)
}

pub fn round_trip_with_fd(
    fd: RawFd,
    request: HubRequest,
) -> io::Result<(HubResponse, Option<OwnedFd>)> {
    round_trip_with_attached_fd(fd, request, None)
}

pub fn round_trip_with_attached_fd(
    fd: RawFd,
    request: HubRequest,
    attached_fd: Option<RawFd>,
) -> io::Result<(HubResponse, Option<OwnedFd>)> {
    send_frame_with_fd(fd, &request.encode_to_vec(), attached_fd)?;
    receive_response(fd, request.id)
}

pub fn receive_response(fd: RawFd, id: u32) -> io::Result<(HubResponse, Option<OwnedFd>)> {
    let (payload, attached) = recv_frame(fd)?;
    let response =
        HubResponse::decode(payload.as_slice()).map_err(|e| io::Error::other(e.to_string()))?;
    if response.id != id {
        return Err(io::Error::other("hub response id mismatch"));
    }
    Ok((response, attached))
}

pub fn send_frame(fd: RawFd, payload: &[u8]) -> io::Result<()> {
    send_frame_with_fd(fd, payload, None)
}

pub fn send_frame_with_fd(fd: RawFd, payload: &[u8], attached_fd: Option<RawFd>) -> io::Result<()> {
    let mut frame = Vec::with_capacity(4 + payload.len());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    frame.extend_from_slice(payload);
    let mut sent = 0;
    let mut attached_fd = attached_fd;
    while sent < frame.len() {
        let n = if let Some(rights_fd) = attached_fd {
            let size = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as _) } as usize;
            let mut control = vec![0usize; size.div_ceil(mem::size_of::<usize>())];
            let mut iov = libc::iovec {
                iov_base: frame[sent..].as_mut_ptr().cast(),
                iov_len: frame.len() - sent,
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
                (*cmsg).cmsg_len = libc::CMSG_LEN(mem::size_of::<RawFd>() as _) as _;
                *(libc::CMSG_DATA(cmsg) as *mut RawFd) = rights_fd;
                libc::sendmsg(fd, &msg, libc::MSG_NOSIGNAL)
            }
        } else {
            unsafe {
                libc::send(
                    fd,
                    frame[sent..].as_ptr().cast(),
                    frame.len() - sent,
                    libc::MSG_NOSIGNAL,
                )
            }
        };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if n == 0 {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "send returned zero bytes",
            ));
        }
        sent += n as usize;
        // SCM_RIGHTS must accompany only the first successful partial write.
        attached_fd = None;
    }
    Ok(())
}

fn recv_frame(fd: RawFd) -> io::Result<(Vec<u8>, Option<OwnedFd>)> {
    let mut attached = None;
    let header = recv_exact(fd, 4, &mut attached)?;
    let size = u32::from_be_bytes([header[0], header[1], header[2], header[3]]) as usize;
    if size > 64 * 1024 * 1024 {
        return Err(io::Error::other("hub frame too large"));
    }
    let payload = recv_exact(fd, size, &mut attached)?;
    Ok((payload, attached))
}

fn recv_exact(fd: RawFd, len: usize, attached: &mut Option<OwnedFd>) -> io::Result<Vec<u8>> {
    let mut buf = vec![0u8; len];
    let mut got = 0;
    while got < len {
        let n = match recv_some(fd, &mut buf[got..], attached) {
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            result => result?,
        };
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

fn recv_some(fd: RawFd, buf: &mut [u8], attached: &mut Option<OwnedFd>) -> io::Result<usize> {
    let mut iov = libc::iovec {
        iov_base: buf.as_mut_ptr() as *mut libc::c_void,
        iov_len: buf.len(),
    };
    let control_len = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) as usize };
    let mut control = vec![0usize; control_len.div_ceil(mem::size_of::<usize>())];
    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = control_len as _;

    let n = unsafe { libc::recvmsg(fd, &mut msg, libc::MSG_CMSG_CLOEXEC) };
    if n < 0 {
        return Err(io::Error::last_os_error());
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
                if attached.is_none() {
                    *attached = Some(unsafe { OwnedFd::from_raw_fd(received) });
                } else {
                    unsafe { libc::close(received) };
                }
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

pub fn connect_abstract(name: &str) -> io::Result<OwnedFd> {
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };
    let bytes = name.as_bytes();
    if bytes.len() > 107 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "hub socket name too long",
        ));
    }
    let mut addr: libc::sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (index, byte) in bytes.iter().enumerate() {
        addr.sun_path[index + 1] = *byte as libc::c_char;
    }
    let length = (mem::size_of::<libc::sa_family_t>() + 1 + bytes.len()) as libc::socklen_t;
    let result = unsafe { libc::connect(fd, &addr as *const _ as *const libc::sockaddr, length) };
    if result != 0 {
        let error = io::Error::last_os_error();
        return Err(error);
    }
    Ok(owned)
}
