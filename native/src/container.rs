//! chroot container environment.
//!
//! A container daemon is started once per container with `--chroot <rootfs>`.
//! It creates a private mount namespace, prepares the standard mounts (and any
//! `--bind` extras), then `chroot()`s into the rootfs. From that point the
//! daemon process itself lives *inside* the container, so it can directly
//! spawn the container shell and own the whole process tree.
//!
//! For the proot backend the daemon is launched *through* proot by Kotlin, so
//! it is already inside the container and does no setup here (`--proot`).

use std::ffi::CString;
use std::io;
use std::path::{Path, PathBuf};

/// Directory binds. chroot runs as real root, so the whole `/dev` can be bound
/// (per-device mounts are only needed for the unprivileged proot backend).
const DIRS: &[(&str, &str)] = &[
    ("/dev", "/dev"),
    ("/sys", "/sys"),
    ("/system", "/system"),
    ("/apex", "/apex"),
    ("/linkerconfig", "/linkerconfig"),
    ("/vendor", "/vendor"),
    ("/storage/emulated/0", "/sdcard"),
    // The daemon/proot binaries and the app's files live under /data; mounting
    // it keeps the container able to reach them.
    ("/data", "/data"),
];

/// Create a private mount namespace, apply all mounts and chroot into `rootfs`.
pub fn enter_chroot(rootfs: &str, binds: &[(String, String)]) -> io::Result<()> {
    let rootfs_path = PathBuf::from(rootfs);
    if !rootfs_path.is_dir() {
        return Err(io::Error::new(io::ErrorKind::NotFound, "chroot rootfs is missing"));
    }

    unsafe {
        if libc::unshare(libc::CLONE_NEWNS) != 0 {
            return Err(io::Error::last_os_error());
        }
        let root = cstring("/")?;
        if libc::mount(
            std::ptr::null(),
            root.as_ptr(),
            std::ptr::null(),
            (libc::MS_REC | libc::MS_PRIVATE) as libc::c_ulong,
            std::ptr::null(),
        ) != 0
        {
            return Err(io::Error::last_os_error());
        }
    }

    for (source, target) in DIRS {
        if Path::new(source).exists() {
            bind_mount(source, &rootfs_path, target, true)?;
        }
    }

    proc_mount(&rootfs_path, "/proc")?;
    tmpfs_mount(&rootfs_path, "/tmp", "1777")?;

    for (source, target) in binds {
        if source.is_empty() || target.is_empty() || !Path::new(source).exists() {
            continue;
        }
        let is_dir = Path::new(source).is_dir();
        bind_mount(source, &rootfs_path, target, is_dir)?;
    }

    unsafe {
        let root = cstring(rootfs)?;
        let slash = cstring("/")?;
        if libc::chroot(root.as_ptr()) != 0 {
            return Err(io::Error::last_os_error());
        }
        libc::chdir(slash.as_ptr());
    }
    Ok(())
}

// ── mount helpers ────────────────────────────────────────────────────

fn ensure_target(rootfs: &Path, guest: &str, is_dir: bool) -> io::Result<PathBuf> {
    let host = rootfs.join(guest.trim_start_matches('/'));
    if is_dir {
        std::fs::create_dir_all(&host)?;
    } else {
        if let Some(parent) = host.parent() {
            std::fs::create_dir_all(parent)?;
        }
        if !host.exists() {
            std::fs::File::create(&host)?;
        }
    }
    Ok(host)
}

fn bind_mount(source: &str, rootfs: &Path, guest: &str, is_dir: bool) -> io::Result<()> {
    let target = ensure_target(rootfs, guest, is_dir)?;
    let source = cstring(source)?;
    let target = path_cstring(&target)?;
    let result = unsafe {
        libc::mount(
            source.as_ptr(),
            target.as_ptr(),
            std::ptr::null(),
            (libc::MS_BIND | libc::MS_REC) as libc::c_ulong,
            std::ptr::null(),
        )
    };
    if result == 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
}

fn proc_mount(rootfs: &Path, guest: &str) -> io::Result<()> {
    fs_mount(rootfs, guest, "proc", "proc", (libc::MS_NOSUID | libc::MS_NODEV | libc::MS_NOEXEC) as libc::c_ulong, "")
}

fn tmpfs_mount(rootfs: &Path, guest: &str, mode: &str) -> io::Result<()> {
    let data = format!("mode={}", mode);
    fs_mount(rootfs, guest, "tmpfs", "tmpfs", (libc::MS_NOSUID | libc::MS_NODEV) as libc::c_ulong, &data)
}

fn fs_mount(
    rootfs: &Path,
    guest: &str,
    source: &str,
    fstype: &str,
    flags: libc::c_ulong,
    data: &str,
) -> io::Result<()> {
    let target = ensure_target(rootfs, guest, true)?;
    let source = cstring(source)?;
    let target = path_cstring(&target)?;
    let fstype = cstring(fstype)?;
    let data = if data.is_empty() { None } else { Some(cstring(data)?) };
    let result = unsafe {
        libc::mount(
            source.as_ptr(),
            target.as_ptr(),
            fstype.as_ptr(),
            flags,
            data.as_ref().map_or(std::ptr::null(), |c| c.as_ptr() as *const libc::c_void),
        )
    };
    if result == 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
}

fn cstring(value: &str) -> io::Result<CString> {
    CString::new(value).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "nul byte in path"))
}

fn path_cstring(path: &Path) -> io::Result<CString> {
    CString::new(path.as_os_str().as_encoded_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "nul byte in path"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dirs_include_data_and_dev() {
        assert!(DIRS.iter().any(|(_, t)| *t == "/data"));
        assert!(DIRS.iter().any(|(_, t)| *t == "/dev"));
    }

    #[test]
    fn missing_rootfs_is_rejected() {
        let result = enter_chroot("/definitely/not/here", &[]);
        assert!(result.is_err());
    }
}
