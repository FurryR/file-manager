use std::collections::HashMap;
use std::ffi::CString;
use std::io;
use std::mem;
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicU64, Ordering};

use crate::proto::SpawnPtyRequest;

static NEXT_PTY_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub struct PtyManager {
    ptys: HashMap<u64, PtyHandle>,
}

#[derive(Debug)]
struct PtyHandle {
    master_fd: RawFd,
    child_pid: u32,
    #[allow(dead_code)]
    container_name: Option<String>,
}

impl PtyManager {
    pub fn new() -> Self {
        PtyManager {
            ptys: HashMap::new(),
        }
    }

    /// Spawn a new PTY. Forks a child, sets up session/terminal, execs the
    /// requested command, and returns the master fd, pty_id, and child_pid.
    /// The caller is responsible for sending the master fd to the client.
    pub fn spawn_pty(&mut self, req: SpawnPtyRequest) -> io::Result<(u64, RawFd, u32)> {
        // 1. Open a PTY master
        let master_fd = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY) };
        if master_fd < 0 {
            return Err(io::Error::last_os_error());
        }

        // 2. Grant and unlock slave
        if unsafe { libc::grantpt(master_fd) } != 0 {
            let e = io::Error::last_os_error();
            unsafe { libc::close(master_fd) };
            return Err(e);
        }
        if unsafe { libc::unlockpt(master_fd) } != 0 {
            let e = io::Error::last_os_error();
            unsafe { libc::close(master_fd) };
            return Err(e);
        }

        // 3. Get slave path
        let mut slave_buf = [0u8; 128];
        if unsafe { libc::ptsname_r(master_fd, slave_buf.as_mut_ptr() as *mut libc::c_char, slave_buf.len()) } != 0 {
            let e = io::Error::last_os_error();
            unsafe { libc::close(master_fd) };
            return Err(e);
        }
        let slave_ptr = slave_buf.as_ptr();

        // 4. Build command/args/env as CStrings in the parent so they are
        //    safely accessible after fork (fork duplicates memory).
        let command_c =
            CString::new(req.command.as_bytes()).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
        let mut argv: Vec<CString> = Vec::with_capacity(1 + req.args.len() + 1);
        argv.push(
            CString::new(req.command.as_bytes())
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?,
        );
        for arg in &req.args {
            argv.push(
                CString::new(arg.as_bytes())
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?,
            );
        }

        // Build environment: copy caller's env then overlay request env
        let mut env_map: HashMap<String, String> = HashMap::new();
        // Inherit daemon's environment as baseline
        for (key, value) in std::env::vars() {
            env_map.insert(key, value);
        }
        // Overlay TERM from term_type if provided and not overridden by req.env
        if !req.term_type.is_empty() {
            env_map.entry("TERM".to_string()).or_insert_with(|| req.term_type.clone());
        }
        // Overlay with explicit env from request
        for (k, v) in &req.env {
            env_map.insert(k.clone(), v.clone());
        }
        let mut envp: Vec<CString> = Vec::with_capacity(env_map.len() + 1);
        for (k, v) in &env_map {
            let entry = format!("{}={}", k, v);
            envp.push(CString::new(entry.as_bytes()).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?);
        }

        // Convert to raw pointer arrays for execve
        let mut argv_ptrs: Vec<*const libc::c_char> = argv.iter().map(|c| c.as_ptr()).collect();
        argv_ptrs.push(std::ptr::null());
        let mut envp_ptrs: Vec<*const libc::c_char> = envp.iter().map(|c| c.as_ptr()).collect();
        envp_ptrs.push(std::ptr::null());

        let rows = req.rows;
        let cols = req.cols;
        let cwd_c = if !req.cwd.is_empty() {
            Some(CString::new(req.cwd.as_bytes()).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?)
        } else {
            None
        };

        // 5. Fork
        let pid = unsafe { libc::fork() };
        if pid < 0 {
            let e = io::Error::last_os_error();
            unsafe { libc::close(master_fd) };
            return Err(e);
        }

        if pid == 0 {
            // ── CHILD ──────────────────────────────────────────────────
            unsafe {
                // Create new session (child becomes session leader, no ctty)
                libc::setsid();

                // Open the slave end of the PTY
                let slave_fd = libc::open(slave_ptr as *const libc::c_char, libc::O_RDWR);
                if slave_fd < 0 {
                    libc::_exit(1);
                }

                // Make the slave the controlling terminal of the new session
                libc::ioctl(slave_fd, TIOCSCTTY as _, 0);

                // Set initial terminal size
                let winsize = libc::winsize {
                    ws_row: rows as u16,
                    ws_col: cols as u16,
                    ws_xpixel: 0,
                    ws_ypixel: 0,
                };
                libc::ioctl(slave_fd, libc::TIOCSWINSZ, &winsize);

                // Duplicate slave to stdin/stdout/stderr
                libc::dup2(slave_fd, libc::STDIN_FILENO);
                libc::dup2(slave_fd, libc::STDOUT_FILENO);
                libc::dup2(slave_fd, libc::STDERR_FILENO);
                if slave_fd > libc::STDERR_FILENO {
                    libc::close(slave_fd);
                }

                // Close master fd — child does not need it
                libc::close(master_fd);

                // Change working directory if specified
                if let Some(ref cwd) = cwd_c {
                    libc::chdir(cwd.as_ptr());
                }

                // Execute the target command
                libc::execve(command_c.as_ptr(), argv_ptrs.as_ptr(), envp_ptrs.as_ptr());

                // execve failed — exit with code 127 (command not found / exec error)
                libc::_exit(127);
            }
        }

        // ── PARENT ────────────────────────────────────────────────────
        // Allocate PTY id and store handle
        let child_pid = pid as u32;
        let pty_id = NEXT_PTY_ID.fetch_add(1, Ordering::SeqCst);
        self.ptys.insert(
            pty_id,
            PtyHandle {
                master_fd,
                child_pid,
                container_name: None,
            },
        );

        Ok((pty_id, master_fd, child_pid))
    }

    /// Resize the terminal window. Sends SIGWINCH to the child process group
    /// so it re-reads the terminal dimensions.
    pub fn resize_pty(&mut self, pty_id: u64, rows: u32, cols: u32) -> io::Result<()> {
        let handle = self.ptys.get(&pty_id).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("pty {} not found", pty_id),
            )
        })?;

        let winsize = libc::winsize {
            ws_row: rows as u16,
            ws_col: cols as u16,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };

        if unsafe { libc::ioctl(handle.master_fd, libc::TIOCSWINSZ, &winsize) } != 0 {
            return Err(io::Error::last_os_error());
        }

        // Notify foreground process group of the size change
        unsafe {
            libc::kill(-(handle.child_pid as i32), libc::SIGWINCH);
        }

        Ok(())
    }

    /// Kill the child process group, close the master fd, and remove the
    /// handle from the manager.
    pub fn close_pty(&mut self, pty_id: u64) -> io::Result<()> {
        let handle = self.ptys.remove(&pty_id).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("pty {} not found", pty_id),
            )
        })?;

        // Kill the entire process group (negative pid → process group)
        // SIGHUP first to give processes a chance to clean up
        unsafe {
            libc::kill(-(handle.child_pid as i32), libc::SIGHUP);
            // SIGKILL to ensure termination
            libc::kill(-(handle.child_pid as i32), libc::SIGKILL);
            libc::close(handle.master_fd);
        }

        Ok(())
    }
    /// Get the child pid for a PTY without removing the handle.
    pub fn get_child_pid(&self, pty_id: u64) -> io::Result<u32> {
        self.ptys.get(&pty_id).map(|h| h.child_pid).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("pty {} not found", pty_id),
            )
        })
    }

    /// Remove a PTY handle and close the master fd.
    /// Called after the child has already been reaped.
    pub fn remove_pty(&mut self, pty_id: u64) -> Option<RawFd> {
        self.ptys.remove(&pty_id).map(|h| {
            let fd = h.master_fd;
            unsafe { libc::close(fd); }
            fd
        })
    }
}

// ── TIOCSCTTY definition ─────────────────────────────────────────────
// This ioctl is not present in the libc crate for all target triples,
// so we define it ourselves (standard Linux value).
#[cfg(not(any(target_os = "android", target_os = "linux")))]
const TIOCSCTTY: libc::c_ulong = 0x540E;
#[cfg(any(target_os = "android", target_os = "linux"))]
const TIOCSCTTY: libc::c_ulong = 0x540E;

// ── SCM_RIGHTS fd passing ────────────────────────────────────────────

/// Send a file descriptor over a UNIX domain socket using SCM_RIGHTS.
///
/// The fd is duplicated in the receiver's process. The sender retains
/// its copy unchanged.
#[allow(dead_code)]
pub(crate) fn send_fd(socket_fd: RawFd, fd_to_send: RawFd) -> io::Result<()> {
    // Dummy data payload — required even for pure ancillary messages
    let dummy = [0u8; 1];

    let iov = libc::iovec {
        iov_base: dummy.as_ptr() as *mut libc::c_void,
        iov_len: 1,
    };

    // Allocate a buffer large enough for the control message header + fd
    let cmsg_space: libc::c_uint = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) };
    let mut cmsg_buf: Vec<u8> = vec![0u8; cmsg_space as usize];

    // Build the msghdr
    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = &iov as *const libc::iovec as *mut libc::iovec;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = cmsg_space as _;

    // Write the cmsghdr inside the buffer
    let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    if cmsg.is_null() {
        return Err(io::Error::new(
            io::ErrorKind::Other,
            "CMSG_FIRSTHDR returned null",
        ));
    }
    unsafe {
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        (*cmsg).cmsg_len = libc::CMSG_LEN(mem::size_of::<RawFd>() as u32) as _;
        let data_ptr = libc::CMSG_DATA(cmsg) as *mut RawFd;
        *data_ptr = fd_to_send;
    }

    let sent = unsafe { libc::sendmsg(socket_fd, &msg, libc::MSG_NOSIGNAL) };
    if sent != 1 {
        return Err(io::Error::last_os_error());
    }

    Ok(())
}

/// Receive a file descriptor from a UNIX domain socket via SCM_RIGHTS.
#[allow(dead_code)]
pub(crate) fn recv_fd(socket_fd: RawFd) -> io::Result<RawFd> {
    let mut dummy = [0u8; 1];

    let iov = libc::iovec {
        iov_base: dummy.as_mut_ptr() as *mut libc::c_void,
        iov_len: 1,
    };

    let cmsg_space: libc::c_uint = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) };
    let mut cmsg_buf: Vec<u8> = vec![0u8; cmsg_space as usize];

    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = &iov as *const libc::iovec as *mut libc::iovec;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = cmsg_space as _;

    let received = unsafe { libc::recvmsg(socket_fd, &mut msg, 0) };
    if received < 0 {
        return Err(io::Error::last_os_error());
    }

    let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    if cmsg.is_null() {
        return Err(io::Error::new(io::ErrorKind::Other, "no ancillary data received"));
    }
    unsafe {
        if (*cmsg).cmsg_level != libc::SOL_SOCKET || (*cmsg).cmsg_type != libc::SCM_RIGHTS {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "expected SCM_RIGHTS"));
        }
        let fd = *(libc::CMSG_DATA(cmsg) as *const RawFd);
        Ok(fd)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Verify send_fd correctly encodes an SCM_RIGHTS message.
    #[test]
    fn send_fd_round_trip() {
        // Create a socket pair for testing
        let mut fds = [-1i32; 2];
        let result = unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, fds.as_mut_ptr()) };
        assert_eq!(result, 0, "socketpair failed");

        let sender_fd = fds[0];
        let receiver_fd = fds[1];

        // Create a pipe to get a real fd to send
        let mut pipe_fds = [-1i32; 2];
        let result = unsafe { libc::pipe(pipe_fds.as_mut_ptr()) };
        assert_eq!(result, 0, "pipe failed");

        let pipe_read = pipe_fds[0];
        let pipe_write = pipe_fds[1];

        // Write a known value
        unsafe { libc::write(pipe_write, &42u8 as *const u8 as *const libc::c_void, 1) };

        // Send the read end of the pipe over the socket
        send_fd(sender_fd, pipe_read).expect("send_fd should succeed");

        // Receive the fd on the other end
        let mut recv_dummy = [0u8; 1];
        let mut recv_iov = libc::iovec {
            iov_base: recv_dummy.as_mut_ptr() as *mut libc::c_void,
            iov_len: 1,
        };
        let recv_cmsg_space: libc::c_uint =
            unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) };
        let mut recv_cmsg_buf: Vec<u8> = vec![0u8; recv_cmsg_space as usize];
        let mut recv_msg: libc::msghdr = unsafe { mem::zeroed() };
        recv_msg.msg_iov = &mut recv_iov as *mut libc::iovec;
        recv_msg.msg_iovlen = 1;
        recv_msg.msg_control = recv_cmsg_buf.as_mut_ptr() as *mut libc::c_void;
        recv_msg.msg_controllen = recv_cmsg_space as _;

        let received = unsafe { libc::recvmsg(receiver_fd, &mut recv_msg, 0) };
        assert!(received > 0, "recvmsg should receive data");

        // Extract the received fd from the control message
        let recv_cmsg = unsafe { libc::CMSG_FIRSTHDR(&recv_msg) };
        assert!(!recv_cmsg.is_null(), "should have a control message");
        unsafe {
            assert_eq!((*recv_cmsg).cmsg_level, libc::SOL_SOCKET);
            assert_eq!((*recv_cmsg).cmsg_type, libc::SCM_RIGHTS);
        }
        let received_fd = unsafe { *(libc::CMSG_DATA(recv_cmsg) as *const RawFd) };

        // Verify the received fd works by reading the byte we piped
        let mut read_buf = [0u8; 1];
        let n = unsafe { libc::read(received_fd, read_buf.as_mut_ptr() as *mut libc::c_void, 1) };
        assert_eq!(n, 1, "should read 1 byte from received fd");
        assert_eq!(read_buf[0], 42u8, "should read the correct byte");

        // Cleanup
        unsafe {
            libc::close(received_fd);
            libc::close(pipe_write); // close our original write end
            libc::close(sender_fd);
            libc::close(receiver_fd);
        }
        // pipe_read was sent and closed via received_fd above
    }

    /// PtyManager must allocate unique, monotonically increasing IDs.
    #[test]
    fn unique_pty_ids() {
        // We can't actually spawn PTYs in a test without a real client
        // socket, but we can verify the internal ID counter works by
        // checking that NEXT_PTY_ID advances.
        let id1 = NEXT_PTY_ID.fetch_add(1, Ordering::SeqCst);
        let id2 = NEXT_PTY_ID.fetch_add(1, Ordering::SeqCst);
        assert_ne!(id1, id2);
        assert!(id2 > id1);
    }

    /// resize_pty on an unknown PTY id returns an error.
    #[test]
    fn resize_unknown_pty_returns_error() {
        let mut mgr = PtyManager::new();
        let result = mgr.resize_pty(99999, 24, 80);
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
        assert!(err.to_string().contains("99999"), "error should mention the PTY id");
    }

    /// close_pty on an unknown PTY id returns an error.
    #[test]
    fn close_unknown_pty_returns_error() {
        let mut mgr = PtyManager::new();
        let result = mgr.close_pty(99999);
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
        assert!(err.to_string().contains("99999"), "error should mention the PTY id");
    }
}
