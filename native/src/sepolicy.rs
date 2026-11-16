//! SELinux policy backend.
//!
//! libsepol 3.11 and the container policy shim (`native/sepolicy/shim.c`) are
//! cross-compiled once by `native/build-libsepol.sh` and linked statically into
//! libfiledaemon.so (see `build.rs`); the daemon never loads a device library.
//!
//! `install` reads `/sys/fs/selinux/policy`, ensures the `file_container`
//! domain and the rules this daemon, rootfs and app need, and writes the
//! same-version image back only when that changed a rule. The cached exec
//! context is then applied in every forked child before `execve`; if injection
//! was disabled or failed, no context is cached and children stay unconfined.

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::path::Path;
use std::sync::OnceLock;

const STATUS_APPLIED: c_int = 0;
const STATUS_PRESENT: c_int = 1;
const STATUS_DISABLED: c_int = 2;
const STATUS_ERROR: c_int = -1;

static EXEC_CONTEXT: OnceLock<CString> = OnceLock::new();

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Apply {
    /// Rules were missing and the edited policy image was loaded.
    Applied,
    /// The policy already grants everything this caller and rootfs need.
    Present,
    /// SELinux is off, or the policy version cannot support the injection.
    Disabled,
}

extern "C" {
    fn file_container_ensure(
        root_context: *const c_char,
        app_context: *const c_char,
        err: *mut *mut c_char,
    ) -> c_int;
    fn file_container_context(buf: *mut c_char, len: usize) -> c_int;
    fn file_container_string_free(text: *mut c_char);
}

/// Read the full `security.selinux` context of `path`.
fn selinux_context(path: &Path) -> Option<String> {
    use std::os::unix::ffi::OsStrExt;

    let path = CString::new(path.as_os_str().as_bytes()).ok()?;
    let mut buf = [0u8; 256];
    let n = unsafe {
        libc::getxattr(
            path.as_ptr(),
            b"security.selinux\0".as_ptr().cast(),
            buf.as_mut_ptr().cast(),
            buf.len(),
        )
    };
    if n <= 0 {
        return None;
    }
    let context = std::str::from_utf8(&buf[..n as usize]).ok()?;
    Some(context.trim_end_matches('\0').to_string())
}

/// Environment variable carrying the app's own SELinux context to the daemon.
///
/// The wayland host puts this process's context in every container environment.
/// It is the receiver the injected rules are written for: a container-created
/// descriptor is refused by the app unless the policy grants it.
pub const APP_CONTEXT_ENV: &str = "APP_SELINUX_CONTEXT";

/// The app context from [`APP_CONTEXT_ENV`], when the launcher supplied one.
fn app_context() -> Option<String> {
    let value = std::env::var(APP_CONTEXT_ENV).ok()?;
    let value = value.trim().trim_end_matches('\0');
    if value.is_empty() {
        None
    } else {
        Some(value.to_string())
    }
}

/// Ensure `file_container` carries the rules this caller needs.
///
/// `root_context` is the container root's full SELinux context: its type is the
/// entry point label and its level is what the container context adopts.
/// `app_context` is the receiver's context; its type is granted the descriptor
/// access a container-created inode needs. Called on every chroot container
/// start, so the result must depend only on the current caller and rootfs,
/// never on whether an earlier launch already created the type.
pub fn ensure(
    root_context: Option<&str>,
    app_context: Option<&str>,
) -> Result<Apply, String> {
    let root = root_context
        .map(|value| {
            CString::new(value).map_err(|_| "root context contains a NUL byte".to_string())
        })
        .transpose()?;
    let app = app_context
        .map(|value| {
            CString::new(value).map_err(|_| "app context contains a NUL byte".to_string())
        })
        .transpose()?;
    let mut err: *mut c_char = std::ptr::null_mut();
    let rc = unsafe {
        file_container_ensure(
            root.as_ref().map_or(std::ptr::null(), |value| value.as_ptr()),
            app.as_ref().map_or(std::ptr::null(), |value| value.as_ptr()),
            &mut err,
        )
    };
    let message = if err.is_null() {
        None
    } else {
        let text = unsafe { CStr::from_ptr(err) }
            .to_string_lossy()
            .into_owned();
        unsafe { file_container_string_free(err) };
        Some(text)
    };
    match rc {
        STATUS_APPLIED => Ok(Apply::Applied),
        STATUS_PRESENT => Ok(Apply::Present),
        STATUS_DISABLED => {
            if let Some(reason) = message.as_deref() {
                eprintln!("[file-daemon] container domain disabled: {reason}");
            }
            Ok(Apply::Disabled)
        }
        STATUS_ERROR => Err(message.unwrap_or_else(|| "policy injection failed".to_string())),
        other => Err(format!("unexpected policy status {other}")),
    }
}

/// Inject the domain for `rootfs` and cache the exec context on success.
pub fn install(rootfs: &Path) -> Result<Apply, String> {
    let root = selinux_context(rootfs);
    let app = app_context();
    let status = ensure(root.as_deref(), app.as_deref())?;
    if matches!(status, Apply::Applied | Apply::Present) {
        if let Some(context) = context() {
            let _ = EXEC_CONTEXT.set(context);
        }
    }
    Ok(status)
}

/// Exec context for `/proc/self/attr/exec`, derived from the daemon's own
/// context with the type replaced by `file_container`.
fn context() -> Option<CString> {
    let mut buf = [0 as c_char; 256];
    let rc = unsafe { file_container_context(buf.as_mut_ptr(), buf.len()) };
    if rc != 0 {
        return None;
    }
    Some(unsafe { CStr::from_ptr(buf.as_ptr()) }.to_owned())
}

/// Enter the container domain for the next `execve`.
///
/// Runs in a forked child, so only async-signal-safe calls are used here:
/// `open`, `write`, `close`, `prctl` and `seccomp` allocate nothing. Returns
/// true when there is no container domain (children stay in the daemon's
/// domain).
pub unsafe fn apply_exec_context() -> bool {
    let Some(context) = EXEC_CONTEXT.get() else {
        return true;
    };
    let bytes = context.as_bytes();
    let fd = libc::open(
        b"/proc/self/attr/exec\0".as_ptr().cast(),
        libc::O_WRONLY | libc::O_CLOEXEC,
    );
    if fd < 0 {
        return false;
    }
    let written = libc::write(fd, bytes.as_ptr().cast(), bytes.len());
    let ok = written == bytes.len() as isize;
    libc::close(fd);
    if !ok {
        return false;
    }
    // No UTS namespace exists, so sethostname/setdomainname would rename the
    // host. CAP_SYS_ADMIN is kept for mounts and also authorizes this filter.
    block_hostname_syscalls()
}

const BPF_LD_W_ABS: u16 = 0x20;
const BPF_JMP_JEQ_K: u16 = 0x15;
const BPF_RET_K: u16 = 0x06;
const SECCOMP_RET_ERRNO: u32 = 0x0005_0000;
const SECCOMP_RET_ALLOW: u32 = 0x7fff_0000;
const SECCOMP_SET_MODE_FILTER: libc::c_long = 1;
const EPERM: u32 = 1;

unsafe fn block_hostname_syscalls() -> bool {
    let mut filter = [
        libc::sock_filter {
            code: BPF_LD_W_ABS,
            jt: 0,
            jf: 0,
            k: 0, /* seccomp_data.nr */
        },
        libc::sock_filter {
            code: BPF_JMP_JEQ_K,
            jt: 0,
            jf: 1,
            k: libc::SYS_sethostname as u32,
        },
        libc::sock_filter {
            code: BPF_RET_K,
            jt: 0,
            jf: 0,
            k: SECCOMP_RET_ERRNO | EPERM,
        },
        libc::sock_filter {
            code: BPF_JMP_JEQ_K,
            jt: 0,
            jf: 1,
            k: libc::SYS_setdomainname as u32,
        },
        libc::sock_filter {
            code: BPF_RET_K,
            jt: 0,
            jf: 0,
            k: SECCOMP_RET_ERRNO | EPERM,
        },
        libc::sock_filter {
            code: BPF_RET_K,
            jt: 0,
            jf: 0,
            k: SECCOMP_RET_ALLOW,
        },
    ];
    let mut program = libc::sock_fprog {
        len: filter.len() as libc::c_ushort,
        filter: filter.as_mut_ptr(),
    };
    libc::syscall(
        libc::SYS_seccomp,
        SECCOMP_SET_MODE_FILTER,
        0,
        &mut program as *mut libc::sock_fprog,
    ) == 0
}
