use std::io;
use std::mem;
use std::os::fd::{FromRawFd, RawFd};
use std::os::unix::net::UnixListener as StdUnixListener;
use tokio::net::UnixListener;

pub fn create_abstract_server(name: &str) -> io::Result<UnixListener> {
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }

    if let Err(e) = bind_abstract_socket(fd, name) {
        unsafe { libc::close(fd) };
        return Err(e);
    }

    let result = unsafe { libc::listen(fd, 1) };
    if result != 0 {
        unsafe { libc::close(fd) };
        return Err(io::Error::last_os_error());
    }

    let std_listener = unsafe { StdUnixListener::from_raw_fd(fd) };
    std_listener.set_nonblocking(true)?;
    UnixListener::from_std(std_listener)
}

fn bind_abstract_socket(fd: RawFd, name: &str) -> io::Result<()> {
    let bytes = name.as_bytes();
    let max_name_len = 107usize;
    if bytes.len() > max_name_len {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "socket name is too long",
        ));
    }

    let mut address: libc::sockaddr_un = unsafe { mem::zeroed() };
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (index, byte) in bytes.iter().enumerate() {
        address.sun_path[index + 1] = *byte as libc::c_char;
    }
    let length = (mem::size_of::<libc::sa_family_t>() + 1 + bytes.len()) as libc::socklen_t;
    let result = unsafe {
        libc::bind(
            fd,
            &address as *const libc::sockaddr_un as *const libc::sockaddr,
            length,
        )
    };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}
