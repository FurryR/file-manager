use prost::Message;
use std::io;
use std::mem;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use tokio::io::{AsyncWriteExt, Interest};
use tokio::net::UnixStream;

use crate::proto::{Request, Response};

pub struct OutboundResponse {
    pub response: Response,
    pub attached_fd: Option<RawFd>,
}

impl OutboundResponse {
    pub fn plain(response: Response) -> Self {
        Self { response, attached_fd: None }
    }

    pub fn with_fd(response: Response, fd: RawFd) -> Self {
        Self { response, attached_fd: Some(fd) }
    }
}

pub struct IncomingRequest {
    pub request: Request,
    pub attached_fd: Option<OwnedFd>,
}

/// Reads framed requests while retaining SCM_RIGHTS descriptors attached to
/// the beginning of their frame. Plain AsyncRead cannot observe ancillary data.
pub struct FrameReader {
    header: [u8; 4],
    header_len: usize,
    attached_fd: Option<OwnedFd>,
    payload: Vec<u8>,
    payload_len: usize,
}

impl FrameReader {
    pub fn new() -> Self {
        Self { header: [0; 4], header_len: 0, attached_fd: None, payload: Vec::new(), payload_len: 0 }
    }

    pub async fn read_frame(&mut self, stream: &mut UnixStream, max_size: usize) -> io::Result<Option<IncomingRequest>> {
        let result = self.read_frame_inner(stream, max_size).await;
        if result.is_err() || matches!(result, Ok(None)) {
            self.attached_fd = None;
        }
        result
    }

    async fn read_frame_inner(&mut self, stream: &mut UnixStream, max_size: usize) -> io::Result<Option<IncomingRequest>> {
        loop {
            let reading_header = self.header_len < self.header.len();
            if !reading_header {
                let size = u32::from_be_bytes(self.header) as usize;
                if size > max_size {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "request frame too large"));
                }
                self.payload.resize(size, 0);
                if self.payload_len == size {
                    break;
                }
            }
            stream.readable().await?;
            let control_size = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) as usize };
            // cmsghdr requires alignment; SCM_RIGHTS padding may fit extra fds.
            let mut control = vec![0usize; (control_size + mem::size_of::<usize>() - 1) / mem::size_of::<usize>()];
            let buffer = if reading_header {
                &mut self.header[self.header_len..]
            } else {
                &mut self.payload[self.payload_len..]
            };
            let mut iov = libc::iovec {
                iov_base: buffer.as_mut_ptr() as *mut libc::c_void,
                iov_len: buffer.len(),
            };
            let mut msg: libc::msghdr = unsafe { mem::zeroed() };
            msg.msg_iov = &mut iov;
            msg.msg_iovlen = 1;
            msg.msg_control = control.as_mut_ptr() as *mut libc::c_void;
            msg.msg_controllen = control_size as _;

            let received = match stream.try_io(Interest::READABLE, || {
                let received = unsafe { libc::recvmsg(stream.as_raw_fd(), &mut msg, libc::MSG_DONTWAIT | libc::MSG_CMSG_CLOEXEC) };
                if received < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(received)
                }
            }) {
                Ok(received) => received,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            };
            if received == 0 {
                if reading_header && self.header_len == 0 {
                    return Ok(None);
                }
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "truncated request frame"));
            }

            if reading_header {
                self.header_len += received as usize;
            } else {
                self.payload_len += received as usize;
            }
            let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
            while !cmsg.is_null() {
                let header = unsafe { &*cmsg };
                if header.cmsg_level == libc::SOL_SOCKET && header.cmsg_type == libc::SCM_RIGHTS {
                    let data_len = (header.cmsg_len as usize)
                        .saturating_sub(unsafe { libc::CMSG_LEN(0) as usize });
                    let count = data_len / mem::size_of::<RawFd>();
                    let data_ptr = unsafe { libc::CMSG_DATA(cmsg) as *const RawFd };
                    for index in 0..count {
                        let fd = unsafe { OwnedFd::from_raw_fd(*data_ptr.add(index)) };
                        if self.attached_fd.is_none() {
                            self.attached_fd = Some(fd);
                        }
                    }
                }
                cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
            }
            if msg.msg_flags & libc::MSG_CTRUNC != 0 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated request ancillary data"));
            }
        }

        let request = Request::decode(self.payload.as_slice())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        self.header_len = 0;
        self.payload_len = 0;
        self.payload = Vec::new();
        Ok(Some(IncomingRequest { request, attached_fd: self.attached_fd.take() }))
    }
}

pub async fn write_frame(stream: &mut UnixStream, response: Response) -> io::Result<()> {
    let payload = response.encode_to_vec();
    stream
        .write_all(&(payload.len() as u32).to_be_bytes())
        .await?;
    stream.write_all(&payload).await
}

/// Write a length-prefixed response with an attached file descriptor via
/// SCM_RIGHTS. The fd rides the frame's first bytes; the peer reads the whole
/// frame with an ancillary buffer, so the descriptor is delivered either way.
pub fn write_response_with_fd(write_fd: RawFd, response: &Response, fd_to_send: RawFd) -> io::Result<()> {
    let payload = response.encode_to_vec();
    let mut frame = Vec::with_capacity(4 + payload.len());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    frame.extend_from_slice(&payload);
    send_all_with_fd(write_fd, &frame, Some(fd_to_send))
}

/// Send `data` in full via `sendmsg`, attaching `fd` to the first chunk only.
/// Handles partial writes and `EAGAIN` (the socket is non-blocking: tokio owns
/// it and `dup` shares O_NONBLOCK).
fn send_all_with_fd(write_fd: RawFd, data: &[u8], fd: Option<RawFd>) -> io::Result<()> {
    let cmsg_space: libc::c_uint = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) };
    let mut cmsg_buf: Vec<u8> = vec![0u8; cmsg_space as usize];

    let mut sent = 0usize;
    let mut fd_to_attach = fd;
    while sent < data.len() {
        let mut iov = libc::iovec {
            iov_base: data[sent..].as_ptr() as *mut libc::c_void,
            iov_len: data.len() - sent,
        };
        let mut msg: libc::msghdr = unsafe { mem::zeroed() };
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        if let Some(fd) = fd_to_attach {
            msg.msg_control = cmsg_buf.as_mut_ptr() as *mut libc::c_void;
            msg.msg_controllen = cmsg_space as _;
            let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
            if cmsg.is_null() {
                return Err(io::Error::new(io::ErrorKind::Other, "CMSG_FIRSTHDR returned null"));
            }
            unsafe {
                (*cmsg).cmsg_level = libc::SOL_SOCKET;
                (*cmsg).cmsg_type = libc::SCM_RIGHTS;
                (*cmsg).cmsg_len = libc::CMSG_LEN(mem::size_of::<RawFd>() as u32) as _;
                let data_ptr = libc::CMSG_DATA(cmsg) as *mut RawFd;
                *data_ptr = fd;
            }
        }

        let n = unsafe { libc::sendmsg(write_fd, &msg, libc::MSG_NOSIGNAL | libc::MSG_DONTWAIT) };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::WouldBlock {
                let mut poll_fd = libc::pollfd {
                    fd: write_fd,
                    events: libc::POLLOUT,
                    revents: 0,
                };
                let ready = unsafe { libc::poll(&mut poll_fd, 1, 30_000) };
                if ready < 0 {
                    return Err(io::Error::last_os_error());
                }
                if ready == 0 {
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "fd response write timed out"));
                }
                continue;
            }
            return Err(error);
        }
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "sendmsg wrote zero bytes"));
        }
        sent += n as usize;
        // The fd must be attached to exactly one chunk.
        fd_to_attach = None;
    }
    Ok(())
}
