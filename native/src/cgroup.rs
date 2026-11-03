//! Container cgroup ownership: one write ends the whole container.
//!
//! Nothing else in this daemon can guarantee that. There is no PID namespace, so
//! an orphaned session reparents to init(1); `/proc` is far too slow to walk on a
//! timer and `children` is not exposed, so nothing can enumerate what escaped;
//! `PR_SET_PDEATHSIG` is cleared by `fork` and only covers direct children; the
//! pty hang-up reaches the foreground process group and nobody who ignores
//! SIGHUP. The cgroup is the one kernel object that outlives every process in
//! the tree and is inherited by `fork`, so a single `cgroup.procs` write covers
//! everything the container will ever spawn, and a single `cgroup.kill` write
//! kills it all — no cooperation from the processes involved.
//!
//! Container daemons chroot themselves, which removes cgroupfs from view for the
//! rest of their life. Every path here is therefore opened *before* the chroot
//! and used afterwards through `openat`/`unlinkat` relative to the saved fds.
//!
//! proot containers are out of scope: they are not root and their processes
//! cannot escape the app in the first place.
//!
//! Kernel coverage (the daemon supports minSdk 26):
//! - Android 13+ / kernel 5.14+: `cgroup.kill`, one write, O(1).
//! - Android 12 / kernel 5.10: `cgroup.freeze` + `cgroup.procs` kill loop.
//! - Android 8-11 / kernel 4.9-5.4: cgroup v1 `freezer.state` + `procs` loop.

use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::ffi::OsStrExt;
use std::sync::OnceLock;

/// Per-container bookkeeping on the host filesystem (readable after the chroot
/// through the kept directory fd).
const RUNTIME_ROOT: &str = "/data/local/tmp/file-manager/ct";
/// cgroup v2 hierarchy owned by this app/root setup.
const V2_ROOT: &str = "/sys/fs/cgroup/ct";
/// cgroup v1 freezer (Android 8-11, and devices without a usable v2 mount).
const V1_ROOT: &str = "/dev/freezer/ct";
/// `starttime` is field 22 of `/proc/<pid>/stat` (pid and comm being 1 and 2).
const STAT_STARTTIME: usize = 22;
/// Bound on the freeze-and-kill loop; a stuck member must not hang shutdown.
const KILL_ROUNDS: usize = 200;

/// How this kernel lets us end a group.
#[derive(Clone, Copy)]
enum Flavor {
    /// v2 with `cgroup.kill` (kernel 5.14+): a single write kills every member.
    Kill,
    /// v2 with `cgroup.freeze` only: freeze, then SIGKILL the member list.
    Freeze,
    /// v1 freezer: `freezer.state=FROZEN`, then SIGKILL the member list.
    V1Freezer,
}

/// A container's cgroup, opened before the chroot so it stays usable after it.
pub struct Group {
    /// O_PATH directory fd of the group itself.
    dir: OwnedFd,
    /// O_PATH directory fd of the hierarchy root, for `unlinkat(AT_REMOVEDIR)`.
    root: OwnedFd,
    /// O_PATH directory fd of [`RUNTIME_ROOT`], for the owner file.
    runtime: OwnedFd,
    name: String,
    flavor: Flavor,
}

static GROUP: OnceLock<Group> = OnceLock::new();

fn cstr(bytes: &[u8]) -> io::Result<CString> {
    CString::new(bytes).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path contains NUL"))
}

fn open_dir(path: &[u8]) -> io::Result<OwnedFd> {
    let path = cstr(path)?;
    // O_PATH stays valid after chroot and cannot be used to read data by mistake.
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

fn open_at(dir: &OwnedFd, name: &str, flags: libc::c_int) -> io::Result<OwnedFd> {
    let name = cstr(name.as_bytes())?;
    let fd = unsafe { libc::openat(dir.as_raw_fd(), name.as_ptr(), flags | libc::O_CLOEXEC) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

/// `openat` with `O_CREAT`: only the owner file needs it, cgroup pseudo-files
/// must exist beforehand.
fn create_at(dir: &OwnedFd, name: &str) -> io::Result<OwnedFd> {
    let name = cstr(name.as_bytes())?;
    let fd = unsafe {
        libc::openat(
            dir.as_raw_fd(),
            name.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_TRUNC | libc::O_CLOEXEC,
            0o600,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

fn write_fd(fd: &OwnedFd, data: &[u8]) -> io::Result<()> {
    let mut written = 0;
    while written < data.len() {
        let n = unsafe {
            libc::write(fd.as_raw_fd(), data[written..].as_ptr().cast(), data.len() - written)
        };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        written += n as usize;
    }
    Ok(())
}

fn write_at(dir: &OwnedFd, name: &str, data: &[u8]) -> io::Result<()> {
    write_fd(&open_at(dir, name, libc::O_WRONLY)?, data)
}

/// Read a pseudo-file in full (member lists are a few KiB at most).
fn read_at(dir: &OwnedFd, name: &str) -> io::Result<String> {
    let fd = open_at(dir, name, libc::O_RDONLY)?;
    let mut out = Vec::new();
    let mut buffer = [0u8; 4096];
    loop {
        let n = unsafe { libc::read(fd.as_raw_fd(), buffer.as_mut_ptr().cast(), buffer.len()) };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if n == 0 {
            break;
        }
        out.extend_from_slice(&buffer[..n as usize]);
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

/// Members of the group: thread-group ids, one per line.
fn members(dir: &OwnedFd) -> Vec<libc::pid_t> {
    read_at(dir, "cgroup.procs")
        .unwrap_or_default()
        .split_whitespace()
        .filter_map(|pid| pid.parse().ok())
        .collect()
}

/// Field `index` (1-based over the whole line) of a `/proc/<pid>/stat` line.
fn stat_field(stat: &str, index: usize) -> Option<u64> {
    let close = stat.rfind(')')?; // `comm` may contain spaces and parentheses
    stat[close + 1..].split_whitespace().nth(index.saturating_sub(3))?.parse().ok()
}

impl Group {
    fn probe(dir: OwnedFd, root: OwnedFd, runtime: OwnedFd, name: String) -> io::Result<Group> {
        let flavor = if open_at(&dir, "cgroup.kill", libc::O_WRONLY).is_ok() {
            Flavor::Kill
        } else if open_at(&dir, "cgroup.freeze", libc::O_WRONLY).is_ok() {
            Flavor::Freeze
        } else if open_at(&dir, "freezer.state", libc::O_WRONLY).is_ok() {
            Flavor::V1Freezer
        } else {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "cgroup has neither kill, freeze nor a v1 freezer",
            ));
        };
        Ok(Group { dir, root, runtime, name, flavor })
    }

    /// mkdir + probe. Must run before the chroot.
    fn create(name: &str) -> io::Result<Group> {
        let runtime_path = std::path::Path::new(RUNTIME_ROOT);
        std::fs::create_dir_all(runtime_path)?;
        let runtime = open_dir(runtime_path.as_os_str().as_bytes())?;

        // v2 first: `cgroup.kill` is one write while the freeze path needs a
        // member list, and the v1 freezer only exists on Android <= 11.
        for root_path in [V2_ROOT, V1_ROOT] {
            let root_path = std::path::Path::new(root_path);
            if std::fs::create_dir_all(root_path).is_err() {
                continue;
            }
            let path = root_path.join(name);
            if std::fs::create_dir(&path).is_err() && !path.is_dir() {
                continue;
            }
            let (Ok(root), Ok(dir)) = (
                open_dir(root_path.as_os_str().as_bytes()),
                open_dir(path.as_os_str().as_bytes()),
            ) else {
                continue;
            };
            let Ok(runtime_clone) = runtime.try_clone() else { continue };
            if let Ok(group) = Group::probe(dir, root, runtime_clone, name.to_owned()) {
                return Ok(group);
            }
        }
        Err(io::Error::new(io::ErrorKind::Unsupported, "no usable cgroup hierarchy"))
    }

    /// Record `<pid> <starttime>` so a later launch can tell a live owner from a
    /// recycled pid. Best effort: a missing file only costs `sweep` precision.
    fn record_owner(&self, pid: libc::pid_t) {
        let starttime = std::fs::read_to_string(format!("/proc/{pid}/stat"))
            .ok()
            .and_then(|stat| stat_field(&stat, STAT_STARTTIME));
        let line = match starttime {
            Some(starttime) => format!("{pid} {starttime}\n"),
            None => format!("{pid}\n"),
        };
        if let Ok(file) = create_at(&self.runtime, &self.name) {
            let _ = write_fd(&file, line.as_bytes());
        }
    }

    /// Put one process in the group. Descendants inherit the membership, so one
    /// call per spawn covers the whole subtree.
    ///
    /// Runs in a forked child before `exec`, so it formats the pid on the stack
    /// and never allocates.
    fn attach(&self, pid: libc::pid_t) -> io::Result<()> {
        let mut digits = [0u8; 16];
        let mut value = pid.max(0) as u64;
        let mut len = 0;
        loop {
            digits[len] = b'0' + (value % 10) as u8;
            len += 1;
            value /= 10;
            if value == 0 {
                break;
            }
        }
        let mut buffer = [0u8; 17];
        for index in 0..len {
            buffer[index] = digits[len - 1 - index];
        }
        buffer[len] = b'\n';
        write_at(&self.dir, "cgroup.procs", &buffer[..=len])
    }

    /// Freeze (when the kernel cannot kill outright) and SIGKILL every member
    /// until the group is empty.
    fn kill(&self) {
        match self.flavor {
            Flavor::Kill => {
                // O(1): the kernel walks the group for us. Nothing below can
                // improve on that, so stop here when the write succeeded.
                if write_at(&self.dir, "cgroup.kill", b"1").is_ok() {
                    return;
                }
            }
            Flavor::Freeze => {
                // Freeze first: a running member forks faster than we can kill,
                // while a frozen group cannot grow.
                let _ = write_at(&self.dir, "cgroup.freeze", b"1");
            }
            Flavor::V1Freezer => {
                let _ = write_at(&self.dir, "freezer.state", b"FROZEN");
            }
        }
        for _ in 0..KILL_ROUNDS {
            let members = members(&self.dir);
            if members.is_empty() {
                return;
            }
            for pid in members {
                unsafe {
                    libc::kill(pid, libc::SIGKILL);
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
    }

    /// rmdir the group and drop the owner file. Best effort: a leftover empty
    /// group is harmless and `sweep` retries it on the next launch.
    fn remove(&self) {
        let Ok(name) = cstr(self.name.as_bytes()) else { return };
        unsafe {
            libc::unlinkat(self.root.as_raw_fd(), name.as_ptr(), libc::AT_REMOVEDIR);
            libc::unlinkat(self.runtime.as_raw_fd(), name.as_ptr(), 0);
        }
    }
}

/// True when the recorded owner is still the process that wrote the file.
fn owner_alive(pid: libc::pid_t, starttime: Option<u64>) -> bool {
    if pid <= 0 {
        return false;
    }
    let stat = match std::fs::read_to_string(format!("/proc/{pid}/stat")) {
        Ok(stat) => stat,
        // Gone, or unreadable: only a dead pid is a definite answer.
        Err(_) => return unsafe { libc::kill(pid, 0) } == 0,
    };
    match starttime {
        Some(expected) => stat_field(&stat, STAT_STARTTIME) == Some(expected),
        None => true,
    }
}

/// Kill every group whose owner is gone.
///
/// This is the only path that can clean up after a daemon that was SIGKILLed, and
/// it costs one directory listing plus one `/proc/<pid>/stat` per container —
/// never a process-table walk. Runs on the host filesystem: call before the
/// chroot.
pub fn sweep() {
    if !std::path::Path::new(RUNTIME_ROOT).is_dir() {
        return;
    }
    let mut names = Vec::new();
    for hierarchy in [V2_ROOT, V1_ROOT] {
        let Ok(entries) = std::fs::read_dir(hierarchy) else { continue };
        for entry in entries.flatten() {
            if let Some(name) = entry.file_name().to_str() {
                names.push(name.to_owned());
            }
        }
    }
    names.sort();
    names.dedup();
    for name in names {
        let owner = std::fs::read_to_string(format!("{RUNTIME_ROOT}/{name}")).unwrap_or_default();
        let mut fields = owner.split_whitespace();
        let pid = fields.next().and_then(|pid| pid.parse().ok()).unwrap_or(0);
        let starttime = fields.next().and_then(|value| value.parse().ok());
        if owner_alive(pid, starttime) {
            continue;
        }
        // The daemon that owned this group is gone, so nobody will ever kill it.
        for hierarchy in [V2_ROOT, V1_ROOT] {
            let root_path = std::path::Path::new(hierarchy);
            let path = root_path.join(&name);
            let (Ok(root), Ok(dir)) = (
                open_dir(hierarchy.as_bytes()),
                open_dir(path.as_os_str().as_bytes()),
            ) else {
                continue;
            };
            let Ok(runtime) = open_dir(RUNTIME_ROOT.as_bytes()) else { continue };
            if let Ok(group) = Group::probe(dir, root, runtime, name.clone()) {
                eprintln!("[file-daemon] cgroup {name}: owner {pid} is gone, killing the container");
                group.kill();
                group.remove();
            }
        }
        let _ = std::fs::remove_file(format!("{RUNTIME_ROOT}/{name}"));
    }
}

/// Create this container's group, sweep leftovers and record the owner.
///
/// Call before the chroot. Failure is not fatal: the daemon keeps the weaker
/// guarantees (`PDEATHSIG`, pty hang-up) instead of refusing to start.
pub fn install(name: &str) -> io::Result<()> {
    sweep();
    take_over(name);
    let group = Group::create(name)?;
    group.record_owner(unsafe { libc::getpid() });
    let _ = GROUP.set(group);
    Ok(())
}

/// Refuse to share a group with a previous daemon for the same container.
///
/// A fresh daemon is started with a fresh key (hence a fresh socket), so two
/// daemons for one container are possible in a narrow window: the old one is
/// still shutting down while the new one starts. Two owners would take turns
/// killing each other's processes, so the newcomer waits briefly and then
/// reclaims the group outright — one container has exactly one owner.
fn take_over(name: &str) {
    let Some(group) = existing(name) else { return };
    let owner = std::fs::read_to_string(format!("{RUNTIME_ROOT}/{name}")).unwrap_or_default();
    let mut fields = owner.split_whitespace();
    let pid = fields.next().and_then(|pid| pid.parse().ok()).unwrap_or(0);
    let starttime = fields.next().and_then(|value| value.parse().ok());
    if !owner_alive(pid, starttime) {
        return;
    }
    for _ in 0..20 {
        std::thread::sleep(std::time::Duration::from_millis(50));
        if !owner_alive(pid, starttime) {
            return;
        }
    }
    eprintln!("[file-daemon] cgroup {name}: reclaiming it from pid {pid}");
    group.kill();
    group.remove();
}

/// Open an existing group by name, if this kernel has one.
fn existing(name: &str) -> Option<Group> {
    let runtime = open_dir(RUNTIME_ROOT.as_bytes()).ok()?;
    for hierarchy in [V2_ROOT, V1_ROOT] {
        let root_path = std::path::Path::new(hierarchy).join(name);
        if !root_path.is_dir() {
            continue;
        }
        let (Ok(root), Ok(dir)) = (
            open_dir(hierarchy.as_bytes()),
            open_dir(root_path.as_os_str().as_bytes()),
        ) else {
            continue;
        };
        if let Ok(group) = Group::probe(dir, root, runtime.try_clone().ok()?, name.to_owned()) {
            return Some(group);
        }
    }
    None
}

/// Put the calling process into the container group. Called in the forked child
/// right after the parent-death signal is armed, before `exec`, so the whole
/// subtree — including anything that starts its own session later — is inside.
pub fn attach_self() {
    let Some(group) = GROUP.get() else { return };
    let _ = group.attach(unsafe { libc::getpid() });
}


/// Pin the container's cgroupfs view to its own subtree.
///
/// Called in the spawned child right after [`attach_self`], before `exec`:
/// `CLONE_NEWCGROUP` makes `ct/<name>` the root of the child's cgroup view, so
/// an init system inside the container can still create and manage sub-groups
/// (systemd does exactly that on startup) while it can no longer see — let alone
/// write — an ancestor's `cgroup.procs`. Without it, `systemctl`/`cgclassify`
/// can accidentally classify the container's own processes out of the group that
/// is supposed to end them.
///
/// This is accident prevention, not a security boundary: a container root shares
/// uid 0 and the host's PID namespace with this daemon, so `/proc/<daemon>/ns/*`
/// and `/proc/<pid>/mem` are open to it. Masking cgroupfs would look stronger but
/// would break the init systems that legitimately need it, so the view is
/// narrowed instead.
///
/// Best effort: without `CAP_SYS_ADMIN` (proot, rootless sessions) the container
/// simply keeps the shared view, and `cgroup` covers it with a member table that
/// nobody inside can move out of.
pub fn isolate_view() {
    if GROUP.get().is_none() {
        return;
    }
    unsafe {
        libc::unshare(libc::CLONE_NEWCGROUP);
    }
}

/// Kill and drop the group. Called on the one path every shutdown reaches (last
/// client disconnect, SIGTERM, SIGHUP, `exit` request).
pub fn kill_and_remove() {
    let Some(group) = GROUP.get() else { return };
    group.kill();
    group.remove();
}
