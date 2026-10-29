//! Container shm gate: keep `wl_shm` pools acceptable for the compositor.
//!
//! Wayland clients allocate their pool with `memfd_create()`, and a memfd inode
//! carries the *creating* process's SELinux label. Container clients that arrive
//! through `su` live in a different domain than the compositor (which runs in
//! the app process), so the fd handoff over the Wayland socket is refused by the
//! receiver's `file:{ read write }` check: the kernel drops the fd and the client
//! only sees `invalid arguments for wl_shm#4.create_pool`, never an mmap error.
//!
//! This gate intercepts `memfd_create` at the syscall boundary (seccomp user
//! notification) and answers it with a pool created in the *compositor's* domain
//! by a forked factory process. Nothing is preloaded into the container, so
//! statically linked clients are covered too.
//!
//! Ordering matters and is easy to get wrong:
//!  - the factory is forked **before** the filter is installed, so its own
//!    `memfd_create` is a plain syscall instead of a notification loop back into
//!    the process that is serving it;
//!  - the filter is installed **before** the first child is spawned, so every
//!    container process inherits it (filters survive `exec`);
//!  - the gate is a no-op when the daemon already shares the compositor's
//!    domain (the standard proot backend), which is the common case.
//!
//! Fail-safe: a notification that cannot be serviced is answered with `ENOSYS`,
//! so a client with its own fallback degrades instead of blocking. Note that
//! *closing the listener does not wake a blocked caller* (measured on this
//! kernel), so the supervisor must never die: the thread is started before the
//! filter is armed, the notification loop only retries on transient errors, and
//! the socket writes use `MSG_NOSIGNAL` so a dead factory cannot raise `SIGPIPE`.

use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};

/// Environment variable carrying the compositor's own SELinux context.
pub const CONTEXT_ENV: &str = "WAYLAND_SHM_CONTEXT";

const BPF_LD_W_ABS: u16 = 0x20;
const BPF_JMP_JEQ_K: u16 = 0x15;
const BPF_RET_K: u16 = 0x06;

const SECCOMP_RET_ALLOW: u32 = 0x7fff_0000;
const SECCOMP_RET_USER_NOTIF: u32 = 0x7fc0_0000;
const SECCOMP_SET_MODE_FILTER: i64 = 1;
const SECCOMP_FILTER_FLAG_NEW_LISTENER: i64 = 1 << 3;

const SECCOMP_NOTIF_FLAG_CONTINUE: u32 = 1;

const MFD_CLOEXEC: u32 = 0x0001;
const MFD_ALLOW_SEALING: u32 = 0x0002;

#[cfg(target_arch = "aarch64")]
mod sys {
    pub const IOCTL: i64 = 29;
    pub const SECCOMP: i64 = 277;
    pub const MEMFD_CREATE: i64 = 279;
    pub const AUDIT_ARCH: u32 = 0xc000_00b7;
}
#[cfg(target_arch = "x86_64")]
mod sys {
    pub const IOCTL: i64 = 16;
    pub const SECCOMP: i64 = 317;
    pub const MEMFD_CREATE: i64 = 319;
    pub const AUDIT_ARCH: u32 = 0xc000_003e;
}

#[repr(C)]
struct SockFilter {
    code: u16,
    jt: u8,
    jf: u8,
    k: u32,
}

#[repr(C)]
struct SockFprog {
    len: u16,
    filter: *const SockFilter,
}

#[repr(C)]
struct SeccompData {
    nr: i32,
    arch: u32,
    instruction_pointer: u64,
    args: [u64; 6],
}

#[repr(C)]
struct SeccompNotif {
    id: u64,
    pid: u32,
    flags: u32,
    data: SeccompData,
}

#[repr(C)]
struct SeccompNotifResp {
    id: u64,
    val: i64,
    error: i32,
    flags: u32,
}

#[repr(C)]
struct SeccompNotifAddfd {
    id: u64,
    flags: u32,
    srcfd: u32,
    newfd: u32,
    newfd_flags: u32,
}

/// The kernel's `struct cmsghdr`; declared here so the ioctl/cmsg plumbing does
/// not depend on platform-specific libc helpers.
#[repr(C)]
struct CmsgHdr {
    len: usize,
    level: i32,
    ty: i32,
}

const fn cmsg_align(len: usize) -> usize {
    let align = std::mem::size_of::<usize>();
    (len + align - 1) & !(align - 1)
}

const fn cmsg_space(payload: usize) -> usize {
    cmsg_align(std::mem::size_of::<CmsgHdr>()) + cmsg_align(payload)
}

const fn cmsg_len(payload: usize) -> usize {
    cmsg_align(std::mem::size_of::<CmsgHdr>()) + payload
}

// _IOC() for the generic (asm-generic) ioctl layout used by aarch64/x86_64.
const fn ioc(direction: u32, kind: u32, nr: u32, size: u32) -> u64 {
    const DIR_SHIFT: u32 = 30;
    const SIZE_SHIFT: u32 = 16;
    const TYPE_SHIFT: u32 = 8;
    const READ: u32 = 2;
    const WRITE: u32 = 1;
    let _ = (READ, WRITE);
    ((direction as u64) << DIR_SHIFT)
        | ((size as u64) << SIZE_SHIFT)
        | ((kind as u64) << TYPE_SHIFT)
        | nr as u64
}

const fn iowr(nr: u32, size: u32) -> u64 {
    ioc(1 | 2, b'!' as u32, nr, size)
}

const fn iow(nr: u32, size: u32) -> u64 {
    ioc(1, b'!' as u32, nr, size)
}

fn notif_recv() -> u64 {
    iowr(0, std::mem::size_of::<SeccompNotif>() as u32)
}
fn notif_send() -> u64 {
    iowr(1, std::mem::size_of::<SeccompNotifResp>() as u32)
}
fn notif_addfd() -> u64 {
    iow(3, std::mem::size_of::<SeccompNotifAddfd>() as u32)
}

fn ioctl(fd: RawFd, request: u64, arg: *mut libc::c_void) -> libc::c_int {
    unsafe { libc::syscall(sys::IOCTL, fd, request as u64, arg) as libc::c_int }
}

/// Arm the gate. Returns whether interception is now active.
///
/// A `false` return is not an error: either the daemon already shares the
/// compositor's domain (nothing to do) or the mechanism is unavailable (kernel
/// without seccomp user notification, policy refusing `setcurrent`, ...), in
/// which case clients behave exactly as they did before.
pub fn install() -> bool {
    let target = match std::env::var(CONTEXT_ENV) {
        Ok(value) => value.trim().trim_end_matches('\0').to_string(),
        Err(_) => return false,
    };
    if target.is_empty() {
        return false;
    }
    let own = match own_context() {
        Some(value) => value,
        None => return false,
    };
    if own == target {
        // Same domain as the compositor: every fd we would inject is already
        // accepted, and so is every fd the clients create themselves.
        return false;
    }

    // Everything the child needs must be prepared before `fork`: a forked child
    // of a multi-threaded process may only make async-signal-safe calls.
    let context = match CString::new(target) {
        Ok(value) => value,
        Err(_) => return false,
    };
    let name = match CString::new("wayland-shm") {
        Ok(value) => value,
        Err(_) => return false,
    };
    let pair = match socketpair() {
        Some(value) => value,
        None => {
            eprintln!("[shm-gate] cannot create the factory socket");
            return false;
        }
    };
    let (parent_end, child_end) = (pair.0, pair.1.as_raw_fd());
    let child_end_owned = pair.1;

    let pid = unsafe { libc::fork() };
    if pid == 0 {
        // Factory: switch into the compositor's domain and mint pools on demand.
        unsafe {
            libc::close(parent_end.as_raw_fd());
            factory(child_end, context.as_ptr(), name.as_ptr())
        }
    }
    drop(child_end_owned);
    if pid < 0 {
        eprintln!(
            "[shm-gate] cannot fork the pool factory: {}",
            io::Error::last_os_error()
        );
        return false;
    }

    // Start the supervisor *before* arming the filter: once a notification is
    // pending, nothing but an answer can release the caller.
    let (handoff, waiting) = std::sync::mpsc::channel::<OwnedFd>();
    let factory_end = parent_end;
    let spawned = std::thread::Builder::new()
        .name("shm-gate".to_string())
        .spawn(move || match waiting.recv() {
            Ok(listener) => supervisor(listener, factory_end),
            Err(_) => {}
        });
    if spawned.is_err() {
        eprintln!("[shm-gate] cannot start the supervisor; gate stays off");
        return false;
    }

    let listener = match install_filter() {
        Some(value) => value,
        None => return false,
    };
    if handoff.send(listener).is_err() {
        // Nothing can answer notifications now, and closing the listener would
        // not release the callers either: report it loudly.
        eprintln!("[shm-gate] supervisor vanished after the filter was armed");
        return false;
    }
    true
}

fn own_context() -> Option<String> {
    let text = std::fs::read("/proc/self/attr/current").ok()?;
    let value = String::from_utf8_lossy(&text).trim().trim_end_matches('\0').to_string();
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}

fn socketpair() -> Option<(OwnedFd, OwnedFd)> {
    let mut fds = [0 as libc::c_int; 2];
    let rc = unsafe {
        libc::socketpair(
            libc::AF_UNIX,
            libc::SOCK_STREAM | libc::SOCK_CLOEXEC,
            0,
            fds.as_mut_ptr(),
        )
    };
    if rc != 0 {
        return None;
    }
    unsafe { Some((OwnedFd::from_raw_fd(fds[0]), OwnedFd::from_raw_fd(fds[1]))) }
}

fn install_filter() -> Option<OwnedFd> {
    let program = [
        SockFilter { code: BPF_LD_W_ABS, jt: 0, jf: 0, k: 0 },
        SockFilter { code: BPF_JMP_JEQ_K, jt: 0, jf: 3, k: sys::MEMFD_CREATE as u32 },
        SockFilter { code: BPF_LD_W_ABS, jt: 0, jf: 0, k: 4 },
        SockFilter { code: BPF_JMP_JEQ_K, jt: 0, jf: 1, k: sys::AUDIT_ARCH },
        SockFilter { code: BPF_RET_K, jt: 0, jf: 0, k: SECCOMP_RET_USER_NOTIF },
        SockFilter { code: BPF_RET_K, jt: 0, jf: 0, k: SECCOMP_RET_ALLOW },
    ];
    let fprog = SockFprog {
        len: program.len() as u16,
        filter: program.as_ptr(),
    };
    let rc = unsafe {
        libc::syscall(
            sys::SECCOMP,
            SECCOMP_SET_MODE_FILTER,
            SECCOMP_FILTER_FLAG_NEW_LISTENER,
            &fprog as *const SockFprog,
        )
    };
    if rc < 0 {
        eprintln!(
            "[shm-gate] seccomp filter not installed: {}",
            io::Error::last_os_error()
        );
        return None;
    }
    unsafe { Some(OwnedFd::from_raw_fd(rc as RawFd)) }
}

/// Serve notifications until the listener disappears.
fn supervisor(listener: OwnedFd, factory: OwnedFd) {
    let listener_fd = listener.as_raw_fd();
    let factory_fd = factory.as_raw_fd();
    let mut served: u64 = 0;
    loop {
        let mut notification: SeccompNotif = unsafe { std::mem::zeroed() };
        let rc = ioctl(listener_fd, notif_recv(), &mut notification as *mut _ as *mut libc::c_void);
        if rc < 0 {
            let error = io::Error::last_os_error();
            match error.raw_os_error() {
                // Transient: keep serving. Anything else means the listener is
                // gone, and callers are already stuck, so only a loud log helps.
                Some(libc::EINTR) | Some(libc::EAGAIN) => continue,
                _ => {
                    eprintln!("[shm-gate] notification loop stopped: {error}");
                    return;
                }
            }
        }
        if notification.data.nr as i64 != sys::MEMFD_CREATE {
            // The filter only matches memfd_create; anything else is a kernel
            // surprise, so let it run untouched.
            respond(listener_fd, notification.id, -1, 0, SECCOMP_NOTIF_FLAG_CONTINUE);
            continue;
        }

        let offered = request_pool(factory_fd);
        let remote = match offered {
            Some(fd) => {
                let addfd = SeccompNotifAddfd {
                    id: notification.id,
                    flags: 0,
                    srcfd: fd as u32,
                    newfd: 0,
                    newfd_flags: libc::O_CLOEXEC as u32,
                };
                let rc = ioctl(
                    listener_fd,
                    notif_addfd(),
                    &addfd as *const SeccompNotifAddfd as *mut libc::c_void,
                );
                unsafe { libc::close(fd) };
                if rc < 0 {
                    eprintln!(
                        "[shm-gate] ADDFD failed: {}",
                        io::Error::last_os_error()
                    );
                    -1
                } else {
                    rc as i64
                }
            }
            None => -1,
        };

        if remote >= 0 {
            served += 1;
            if served % 64 == 0 {
                eprintln!("[shm-gate] {served} pools injected");
            }
            respond(listener_fd, notification.id, remote, 0, 0);
        } else {
            // Fail closed but never block: a client with its own fallback can
            // still allocate in $XDG_RUNTIME_DIR.
            respond(listener_fd, notification.id, -1, -(libc::ENOSYS), 0);
        }
    }
}

fn respond(listener: RawFd, id: u64, val: i64, error: i32, flags: u32) {
    let mut response = SeccompNotifResp {
        id,
        val,
        error,
        flags,
    };
    let rc = ioctl(
        listener,
        notif_send(),
        &mut response as *mut SeccompNotifResp as *mut libc::c_void,
    );
    if rc < 0 {
        // The woken task is gone (cancelled syscall); nothing left to answer.
        let error = io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::ENOENT) {
            eprintln!("[shm-gate] response rejected: {error}");
        }
    }
}

/// Ask the factory for one pool fd. `None` means the factory is gone.
fn request_pool(factory: RawFd) -> Option<RawFd> {
    let byte = [b'P'];
    let sent = unsafe {
        libc::send(factory, byte.as_ptr() as *const libc::c_void, 1, libc::MSG_NOSIGNAL)
    };
    if sent != 1 {
        return None;
    }
    unsafe { receive_fd(factory) }
}

unsafe fn receive_fd(socket: RawFd) -> Option<RawFd> {
    let mut payload = [0u8; 1];
    let mut iov = libc::iovec {
        iov_base: payload.as_mut_ptr() as *mut libc::c_void,
        iov_len: payload.len(),
    };
    let mut control = [0u64; 8];
    let mut message: libc::msghdr = std::mem::zeroed();
    message.msg_iov = &mut iov;
    message.msg_iovlen = 1;
    message.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    message.msg_controllen = std::mem::size_of_val(&control) as _;
    let received = libc::recvmsg(socket, &mut message, libc::MSG_CMSG_CLOEXEC);
    if received <= 0 {
        return None;
    }
    let header = control.as_ptr() as *const CmsgHdr;
    if (*header).len < cmsg_len(std::mem::size_of::<RawFd>()) || (*header).ty != libc::SCM_RIGHTS {
        return None;
    }
    let data = (header as *const u8).add(cmsg_align(std::mem::size_of::<CmsgHdr>()));
    Some(std::ptr::read_unaligned(data as *const RawFd))
}

/// Factory process: never returns.
///
/// Runs after `fork` in a multi-threaded parent, so it sticks to syscalls: it
/// switches into the compositor's domain, then answers each request with a
/// freshly created pool fd. The fd's inode therefore carries the compositor's
/// label, which is exactly what the receiver's permission check wants.
unsafe fn factory(socket: RawFd, context: *const libc::c_char, name: *const libc::c_char) -> ! {
    // Die with the daemon even if the socket somehow stays open.
    libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL, 0, 0, 0);

    let attr = b"/proc/self/attr/current\0";
    let wanted = libc::strlen(context);
    let mut switched = false;
    let fd = libc::open(attr.as_ptr() as *const libc::c_char, libc::O_WRONLY);
    if fd >= 0 {
        switched = libc::write(fd, context as *const libc::c_void, wanted) == wanted as isize;
        libc::close(fd);
    }
    if !switched {
        // Without the compositor's domain every pool we mint is rejected by the
        // compositor exactly as before — but now *after* a successful
        // memfd_create, which also hides the client's own fallback. Fail every
        // request with ENOSYS instead, the documented pre-gate degradation.
        let why = b"[shm-gate] factory cannot switch SELinux domain; gate disabled\n";
        libc::write(2, why.as_ptr() as *const libc::c_void, why.len());
        libc::close(socket);
        libc::_exit(1);
    }

    let mut request = [0u8; 1];
    loop {
        let read = libc::read(socket, request.as_mut_ptr() as *mut libc::c_void, 1);
        if read != 1 {
            // Daemon gone.
            libc::_exit(0);
        }
        let pool = libc::syscall(
            sys::MEMFD_CREATE,
            name,
            (MFD_CLOEXEC | MFD_ALLOW_SEALING) as u64,
        ) as libc::c_int;
        if pool >= 0 {
            if !send_fd(socket, pool) {
                libc::close(pool);
            }
            libc::close(pool);
        } else {
            // Signal failure with a payload-less answer.
            let marker = [b'E'];
            libc::send(
                socket,
                marker.as_ptr() as *const libc::c_void,
                1,
                libc::MSG_NOSIGNAL,
            );
        }
    }
}

unsafe fn send_fd(socket: RawFd, fd: RawFd) -> bool {
    let mut payload = [b'P'];
    let mut iov = libc::iovec {
        iov_base: payload.as_mut_ptr() as *mut libc::c_void,
        iov_len: payload.len(),
    };
    let mut control = [0u64; 8];
    let mut message: libc::msghdr = std::mem::zeroed();
    message.msg_iov = &mut iov;
    message.msg_iovlen = 1;
    message.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    message.msg_controllen = cmsg_space(std::mem::size_of::<RawFd>()) as _;
    let header = control.as_mut_ptr() as *mut CmsgHdr;
    (*header).len = cmsg_len(std::mem::size_of::<RawFd>());
    (*header).level = libc::SOL_SOCKET;
    (*header).ty = libc::SCM_RIGHTS;
    let data = (header as *mut u8).add(cmsg_align(std::mem::size_of::<CmsgHdr>()));
    std::ptr::write_unaligned(data as *mut RawFd, fd);
    libc::sendmsg(socket, &message, libc::MSG_NOSIGNAL) > 0
}
