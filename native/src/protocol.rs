use prost::Message;
use std::io;
use std::mem;
use std::os::fd::RawFd;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixStream;

use crate::proto::{Request, Response};

pub async fn read_frame(stream: &mut UnixStream) -> io::Result<Option<Request>> {
    let mut size_bytes = [0u8; 4];
    if stream.read_exact(&mut size_bytes).await.is_err() {
        return Ok(None);
    }
    let size = u32::from_be_bytes(size_bytes) as usize;
    let mut payload = vec![0u8; size];
    stream.read_exact(&mut payload).await?;
    Request::decode(payload.as_slice())
        .map(Some)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

pub async fn write_frame(stream: &mut UnixStream, response: Response) -> io::Result<()> {
    let payload = response.encode_to_vec();
    stream
        .write_all(&(payload.len() as u32).to_be_bytes())
        .await?;
    stream.write_all(&payload).await
}

/// Write a response with an attached file descriptor via SCM_RIGHTS.
/// Uses raw fd operations (not tokio) to attach ancillary data.
pub fn write_response_with_fd(write_fd: RawFd, response: &Response, fd_to_send: RawFd) -> io::Result<()> {
    let payload = response.encode_to_vec();
    let header = (payload.len() as u32).to_be_bytes();

    let iov = [
        libc::iovec {
            iov_base: header.as_ptr() as *mut libc::c_void,
            iov_len: header.len(),
        },
        libc::iovec {
            iov_base: payload.as_ptr() as *mut libc::c_void,
            iov_len: payload.len(),
        },
    ];

    let cmsg_space: libc::c_uint = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) };
    let mut cmsg_buf: Vec<u8> = vec![0u8; cmsg_space as usize];

    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = iov.as_ptr() as *mut libc::iovec;
    msg.msg_iovlen = iov.len() as _;
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
        *data_ptr = fd_to_send;
    }

    let sent = unsafe { libc::sendmsg(write_fd, &msg, libc::MSG_NOSIGNAL) };
    if sent < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}
