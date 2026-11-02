//! Subreaper support: adopt orphaned descendants and reap the zombies nobody
//! owns.
//!
//! This module owns exactly two jobs. Every child state change arrives as
//! `SIGCHLD`, and one central `waitpid(-1)` reaps tracked children and adopted
//! orphans alike, so no zombie outlives the process that created it. Owners get
//! their exit code over a watch channel instead of racing the reaper with their
//! own `waitpid`.
//!
//! It deliberately keeps no process inventory. Killing is the business of the
//! handle that owns the process — a PTY owns its session, an exec owns its
//! process group — and the lifetime boundary is the container/app itself: when
//! file-manager (or the container) goes away, nothing is left to kill. A global
//! "who is alive" table would be a second source of truth that only drifts from
//! the handles, and rootless Android cannot enumerate `/proc` to rebuild it.
//! `arm_parent_death_signal` covers the abrupt case that no bookkeeping can.
use std::collections::HashMap;
use std::io;
use std::sync::{Mutex, OnceLock};
use tokio::sync::watch;

/// One tracked child: the exit code its owner is waiting for.
struct ChildEntry {
    exit: watch::Sender<Option<i32>>,
}

fn children() -> &'static Mutex<HashMap<i32, ChildEntry>> {
    static CHILDREN: OnceLock<Mutex<HashMap<i32, ChildEntry>>> = OnceLock::new();
    CHILDREN.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Register a freshly spawned child and return its exit subscription.
///
/// Must be called immediately after spawn, before any await: registration has
/// to win the race against the `SIGCHLD` sweep that reaps the process.
pub fn track(pid: u32) -> watch::Receiver<Option<i32>> {
    let (exit, receiver) = watch::channel(None);
    children()
        .lock()
        .unwrap()
        .insert(pid as i32, ChildEntry { exit });
    receiver
}

/// Forget a child without waiting for it. The next `SIGCHLD` sweep reaps the
/// zombie as an adopted orphan.
pub fn release(pid: u32) {
    children().lock().unwrap().remove(&(pid as i32));
}

pub fn enable() -> io::Result<()> {
    if unsafe { libc::prctl(libc::PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Arm the kernel's parent-death signal in a freshly forked child.
///
/// Nothing here reacts to the daemon dying: an Android force-stop, the
/// low-memory killer or a plain `kill -9` runs no cleanup at all, and everything
/// the daemon spawned would be orphaned onto host init and keep running until
/// the next reboot. `PR_SET_PDEATHSIG` closes that hole — the kernel kills the
/// child the instant the daemon dies, however it dies, which is also what makes
/// "file-manager exits, the container goes with it" hold at process level.
///
/// The attribute is per-process and cleared by `fork`, so every spawn path arms
/// it in the child (after `fork`, or in `Command::pre_exec`). The `getppid` check
/// afterwards closes the window where the daemon died between `fork` and
/// `prctl` and the signal was already missed.
///
/// # Safety
/// Only async-signal-safe calls; invoke in the forked child before `exec`.
pub unsafe fn arm_parent_death_signal(daemon_pid: libc::pid_t) -> bool {
    if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL, 0, 0, 0) != 0 {
        return false;
    }
    libc::getppid() == daemon_pid
}

fn exit_code(status: i32) -> i32 {
    if libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else if libc::WIFSIGNALED(status) {
        -libc::WTERMSIG(status)
    } else {
        -1
    }
}

/// Reap every child that has changed state. Tracked pids publish their exit
/// code; adopted orphans are discarded.
///
/// This is the only `wait*` call on children in the daemon. Keeping it in one
/// place prevents two owners from stealing the same exit status, and it works
/// without enumerating `/proc`.
pub fn reap_orphans() {
    loop {
        let mut status = 0;
        let pid = unsafe { libc::waitpid(-1, &mut status, libc::WNOHANG) };
        if pid <= 0 {
            if pid < 0 && io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            break;
        }
        let code = exit_code(status);
        let entry = children().lock().unwrap().remove(&pid);
        if let Some(entry) = entry {
            entry.exit.send_replace(Some(code));
        }
    }
}
