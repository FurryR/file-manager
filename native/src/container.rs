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
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::time::Duration;
use tokio::sync::watch;

/// Optional init program at the container root.
const INIT: &str = "/init";
/// Grace period between SIGTERM to init and the cgroup's hard SIGKILL.
const INIT_STOP_GRACE: Duration = Duration::from_secs(20);

/// Shared between the off-runtime spawn task and the shutdown path.
///
/// The spawn runs on a blocking thread while the daemon keeps accepting, so a
/// shutdown can race the fork/exec. `closed` makes that race harmless: a late
/// spawn sees it and kills the child instead of adopting it.
pub struct InitSlot {
    state: Mutex<InitState>,
}

#[derive(Default)]
struct InitState {
    closed: bool,
    handle: Option<InitHandle>,
}

/// The running container init, tracked so shutdown can ask it to stop.
pub struct InitHandle {
    pid: libc::pid_t,
    exit: watch::Receiver<Option<i32>>,
}

impl InitSlot {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(InitState::default()),
        }
    }

    fn closed(&self) -> bool {
        self.state.lock().unwrap().closed
    }

    fn adopt(&self, handle: InitHandle) {
        let mut state = self.state.lock().unwrap();
        if state.closed {
            // Shutdown already passed: never let a late spawn survive it. A
            // child that already exited and was reaped may have its pid reused,
            // so only signal it while the reaper still holds an exit slot.
            if handle.exit.borrow().is_none() {
                unsafe {
                    libc::kill(handle.pid, libc::SIGKILL);
                }
            }
            crate::reaper::release(handle.pid as u32);
            return;
        }
        state.handle = Some(handle);
    }

    /// Refuse new inits and hand the running one to the shutdown path.
    pub fn close(&self) -> Option<InitHandle> {
        let mut state = self.state.lock().unwrap();
        state.closed = true;
        state.handle.take()
    }
}

impl InitHandle {
    /// Ask init to stop, then wait a bounded time for it to exit.
    ///
    /// SIGTERM is the catchable signal every init system handles; the cgroup
    /// SIGKILL that follows is only the backstop for a wedged init.
    pub async fn stop(self) {
        if self.exit.borrow().is_some() {
            return;
        }
        eprintln!("[file-daemon] stopping /init (pid {})", self.pid);
        unsafe {
            libc::kill(self.pid, libc::SIGTERM);
        }
        let deadline = tokio::time::Instant::now() + INIT_STOP_GRACE;
        loop {
            if self.exit.borrow().is_some() {
                return;
            }
            if tokio::time::Instant::now() >= deadline {
                eprintln!("[file-daemon] /init did not stop within {INIT_STOP_GRACE:?}");
                return;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
            crate::reaper::reap_orphans();
        }
    }
}

/// Directory binds.  `/dev` gets a container-private tmpfs and is populated
/// separately, so it never reaches the host device tree.
const DIRS: &[(&str, &str)] = &[
    ("/sys", "/sys"),
    ("/system", "/system"),
    ("/apex", "/apex"),
    ("/linkerconfig", "/linkerconfig"),
    ("/vendor", "/vendor"),
    ("/storage", "/storage"),
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

    mount_dev(&rootfs_path)?;

    proc_mount(&rootfs_path, "/proc")?;
    tmpfs_mount(&rootfs_path, "/tmp", "1777")?;
    tmpfs_mount(&rootfs_path, "/run", "0755")?;

    for (source, target) in binds {
        if source.is_empty() || target.is_empty() || !Path::new(source).exists() {
            continue;
        }
        let is_dir = Path::new(source).is_dir();
        bind_mount(source, &rootfs_path, target, is_dir)?;
        if target == "/dev/audio" {
            let alias = rootfs_path.join("dev/pulse");
            if let Ok(meta) = std::fs::symlink_metadata(&alias) {
                if meta.is_dir() {
                    std::fs::remove_dir(&alias)?;
                } else {
                    std::fs::remove_file(&alias)?;
                }
            }
            std::os::unix::fs::symlink("/dev/audio/pulse", alias)?;
        }
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

/// Start the container's own init system when its rootfs ships `/init`.
///
/// The daemon stays the subreaper and cgroup owner, so init is an ordinary
/// child: the user picks the init system by installing it in the rootfs, and it
/// runs as a non-PID 1 process. Missing, non-executable or failing `/init` is
/// not an error — the container keeps working as a plain shell rootfs.
pub fn spawn_init(slot: &InitSlot) {
    if slot.closed() || !executable(INIT).unwrap_or(false) {
        return;
    }
    let daemon_pid = unsafe { libc::getpid() };
    let mut command = Command::new(INIT);
    command
        .current_dir("/")
        .process_group(0)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    // SAFETY: runs in the forked child; `prctl`, `getppid`, `openat`, `write`
    // and `unshare` are async-signal-safe and allocate nothing.
    unsafe {
        command.pre_exec(move || {
            if crate::reaper::arm_parent_death_signal(daemon_pid) {
                // Join the container group and narrow cgroupfs to its subtree,
                // exactly like every exec/pty child, so init can manage its own
                // services without escaping the group that ends the container.
                crate::cgroup::attach_self();
                crate::cgroup::isolate_view();
                Ok(())
            } else {
                Err(io::Error::from_raw_os_error(libc::ESRCH))
            }
        });
    }
    // This runs off the runtime thread, so the reaper gate must span fork and
    // track: otherwise an init that exits immediately is reaped before its pid
    // is registered, and that pid could be reused.
    let gate = crate::reaper::spawn_guard();
    let child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            drop(gate);
            // A SIGCHLD for another child may have arrived while the gate was
            // held and been skipped by the runtime; collect it now.
            crate::reaper::reap_orphans();
            eprintln!("[file-daemon] /init failed to start: {error}");
            return;
        }
    };
    let pid = child.id() as libc::pid_t;
    let exit = crate::reaper::track(child.id());
    drop(gate);
    // A SIGCHLD for this init (or any other child) may have arrived while the
    // gate was held; collect it now that the pid is registered.
    crate::reaper::reap_orphans();
    // The reaper owns waiting; dropping `Child` neither waits nor kills.
    drop(child);
    eprintln!("[file-daemon] started /init (pid {pid})");
    slot.adopt(InitHandle { pid, exit });
}

fn executable(path: &str) -> Option<bool> {
    let metadata = std::fs::metadata(path).ok()?;
    Some(metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
}

// ── mount helpers ────────────────────────────────────────────────────

/// Replace `/dev` with a container-private tmpfs, then populate it from the
/// host device tree.  Mount points are created before any bind mount and
/// nothing is ever written to the host `/dev`.
fn mount_dev(rootfs: &Path) -> io::Result<()> {
    // Snapshot the host entries before /dev is shadowed in this namespace.
    // `/dev/ptmx` is handled separately below: it must not become a mount
    // point of its own.
    let entries: Vec<_> = std::fs::read_dir("/dev")?
        .flatten()
        .filter(|entry| {
            let name = entry.file_name();
            name != "ptmx"
        })
        .collect();

    fs_mount(rootfs, "/dev", "tmpfs", "tmpfs",
             libc::MS_NOSUID as libc::c_ulong, "mode=0755")?;

    // The kernel resolves the legacy ptmx device by searching the devpts
    // mount below the mount containing `/dev/ptmx`. A file bind mount of
    // host `/dev/ptmx` would make `/dev/ptmx` its own mount root, hiding the
    // sibling `/dev/pts` mount and making open("/dev/ptmx") fail with
    // ENOENT. Use the standard devpts symlink instead.
    let ptmx = rootfs.join("dev").join("ptmx");
    let _ = std::fs::remove_file(&ptmx);
    let _ = std::fs::remove_dir(&ptmx);
    std::os::unix::fs::symlink("pts/ptmx", &ptmx)?;

    // Create every mount point first: files for devices, directories for
    // folders, and real symlinks for entries such as /dev/fd.
    for entry in &entries {
        let name = entry.file_name();
        let guest = format!("/dev/{}", name.to_string_lossy());
        let result = match entry.file_type() {
            Ok(file_type) if file_type.is_symlink() => {
                let link = rootfs.join("dev").join(&name);
                let _ = std::fs::remove_file(&link);
                std::fs::read_link(entry.path())
                    .and_then(|destination| std::os::unix::fs::symlink(destination, &link))
            }
            _ => ensure_target(rootfs, &guest, entry.path().is_dir()).map(|_| ()),
        };
        if let Err(error) = result {
            eprintln!("container: cannot create mount point {guest}: {error}");
        }
    }

    // Then bind the host entries onto the prepared mount points.
    for entry in &entries {
        if entry.file_type().is_ok_and(|file_type| file_type.is_symlink()) {
            continue;
        }
        let source = entry.path();
        let guest = format!("/dev/{}", entry.file_name().to_string_lossy());
        if let Err(error) = bind_mount(
            &source.to_string_lossy(), rootfs, &guest, source.is_dir(),
        ) {
            eprintln!("container: cannot bind {}: {error}", source.display());
        }
    }
    Ok(())
}

fn ensure_target(rootfs: &Path, guest: &str, is_dir: bool) -> io::Result<PathBuf> {
    let host = rootfs.join(guest.trim_start_matches('/'));
    if is_dir {
        // Older launches may have left a regular file at a directory bind point.
        if host.is_file() {
            std::fs::remove_file(&host)?;
        }
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
