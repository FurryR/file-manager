//! Subreaper support: adopt orphaned descendants, reap the zombies nobody
//! owns, and tear down tracked process groups when the daemon exits.
//!
//! Rootless Android cannot rely on listing `/proc`, so the reaper never scans
//! it. The kernel already reports every child state change through `SIGCHLD`;
//! one central `waitpid(-1)` then reaps tracked children and adopted orphans
//! alike. Owners receive their exit code over a watch channel instead of
//! racing the reaper with their own `waitpid`.
use std::collections::HashMap;
use std::io;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::sync::watch;

/// One tracked child. The process group id is the child pid because every
/// spawn path creates a fresh process group (`process_group(0)` or `setsid`).
struct ChildEntry {
    exit: watch::Sender<Option<i32>>,
    group: i32,
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
    children().lock().unwrap().insert(
        pid as i32,
        ChildEntry {
            exit,
            group: pid as i32,
        },
    );
    receiver
}

/// Forget a child without waiting for it. The caller must have signalled its
/// process group; the next `SIGCHLD` sweep reaps the zombie as an orphan.
pub fn release(pid: u32) {
    children().lock().unwrap().remove(&(pid as i32));
}

/// Drop every registration. Shutdown only: no owner is left to receive an
/// exit code, and `kill_tree` has already signalled every tracked group.
pub fn release_all() {
    children().lock().unwrap().clear();
}

pub fn enable() -> io::Result<()> {
    if unsafe { libc::prctl(libc::PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
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
            // The leader is gone. Descendants that stayed in its process group
            // must not outlive it; this replaces walking parent links in /proc.
            unsafe {
                libc::kill(-entry.group, libc::SIGKILL);
            }
            entry.exit.send_replace(Some(code));
        }
    }
}

/// Terminate every tracked process group before the daemon exits.
///
/// `kill_tree` intentionally relies on the recorded groups rather than walking
/// `/proc`: a group id stays valid while any member is alive, so the graceful
/// and forceful phases can signal the same group even after its leader was
/// reaped. Descendants that called `setsid()` and left the group are outside
/// this inventory; the daemon cannot discover them without a process-table
/// scan.
pub fn kill_tree(term_grace: Duration) {
    let groups = children()
        .lock()
        .unwrap()
        .values()
        .map(|entry| entry.group)
        .collect::<Vec<_>>();

    // Polite phase: TERM each group once. Repeats would interrupt the very
    // cleanup the grace period is meant to allow.
    for group in &groups {
        unsafe {
            libc::kill(-*group, libc::SIGTERM);
        }
    }
    let deadline = Instant::now() + term_grace;
    while Instant::now() < deadline && !children().lock().unwrap().is_empty() {
        reap_orphans();
        std::thread::sleep(Duration::from_millis(50));
    }

    // Forceful phase: SIGKILL cannot be blocked or caught. Re-read the live
    // groups every round so a reaped leader's id is never signalled after the
    // kernel could have recycled it.
    let deadline = Instant::now() + Duration::from_millis(500);
    while Instant::now() < deadline && !children().lock().unwrap().is_empty() {
        let live = children()
            .lock()
            .unwrap()
            .values()
            .map(|entry| entry.group)
            .collect::<Vec<_>>();
        for group in live {
            unsafe {
                libc::kill(-group, libc::SIGKILL);
            }
        }
        reap_orphans();
        std::thread::sleep(Duration::from_millis(20));
    }
    reap_orphans();
    release_all();
}
