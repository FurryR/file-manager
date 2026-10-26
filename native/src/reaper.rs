//! Subreaper support: adopt orphaned descendants, reap the zombies nobody
//! owns, and tear the whole tree down when the daemon exits.
use std::collections::HashSet;
use std::io;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

fn managed() -> &'static Mutex<HashSet<i32>> {
    static PIDS: OnceLock<Mutex<HashSet<i32>>> = OnceLock::new();
    PIDS.get_or_init(|| Mutex::new(HashSet::new()))
}

/// Register a child whose exit status belongs to a Tokio waiter or a PTY
/// poller; the sweep must never reap it out from under its owner.
pub fn track(pid: u32) {
    managed().lock().unwrap().insert(pid as i32);
}

/// Forget a child whose owner consumed the exit status (or will never wait),
/// so the sweep may reap the zombie.
pub fn release(pid: u32) {
    managed().lock().unwrap().remove(&(pid as i32));
}

/// Drop every registration. Shutdown only: the async owners are gone or
/// frozen for good, so no status can be stolen from them any more.
pub fn release_all() {
    managed().lock().unwrap().clear();
}

/// A cancellation guard for an owned process group. Construct before spawning
/// its async waiter so even an unpolled task tears down descendants on drop.
pub struct ProcessGroup(pub u32);
impl ProcessGroup {
    pub fn finish(&mut self) {
        if self.0 != 0 {
            unsafe {
                libc::kill(-(self.0 as i32), libc::SIGKILL);
            }
            self.0 = 0;
        }
    }
}
impl Drop for ProcessGroup {
    fn drop(&mut self) {
        self.finish();
    }
}

pub fn enable() -> io::Result<()> {
    if unsafe { libc::prctl(libc::PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Direct children of `pid`; zombies included — they are reaped, not killed.
fn children_of(pid: i32) -> Vec<i32> {
    let Ok(children) = std::fs::read_to_string(format!("/proc/{pid}/task/{pid}/children")) else {
        return Vec::new();
    };
    children
        .split_whitespace()
        .filter_map(|p| p.parse().ok())
        .collect()
}

pub fn reap_orphans() {
    let pid = std::process::id() as i32;
    let children = children_of(pid);
    let mut tracked = managed().lock().unwrap();
    // Cancelled Tokio commands are killed/reaped by Tokio itself. Keep their
    // registration until /proc confirms they have gone; never waitpid(-1).
    tracked.retain(|pid| std::path::Path::new(&format!("/proc/{pid}")).exists());
    for child in children {
        if !tracked.contains(&child) {
            let mut status = 0;
            unsafe {
                libc::waitpid(child, &mut status, libc::WNOHANG);
            }
        }
    }
}

/// Every descendant of `root`, walking /proc children lists transitively.
/// Orphans reparent to us (subreaper), so this stays a complete inventory of
/// the tree even for processes that escaped their process group.
fn collect_descendants(root: i32) -> Vec<i32> {
    let mut queue = children_of(root);
    let mut found = Vec::new();
    while let Some(pid) = queue.pop() {
        if !found.contains(&pid) {
            found.push(pid);
            queue.extend(children_of(pid));
        }
    }
    found
}

/// Init-like teardown: terminate every live descendant before the daemon
/// exits. SIGTERM first for the grace period — children whose parents die
/// reparent to us and join the sweep — then SIGKILL the remainder, reaping
/// zombies until the tree is quiet. Blocks; call only from shutdown, after
/// every async owner has stopped.
pub fn kill_tree(term_grace: Duration) {
    let root = std::process::id() as i32;
    // Polite phase: TERM each process once — repeats would interrupt the very
    // cleanup the grace period is meant to allow. Orphans whose parents die
    // reparent to us and are picked up by the next sweep.
    let mut signaled = HashSet::new();
    let deadline = Instant::now() + term_grace;
    while Instant::now() < deadline {
        reap_orphans();
        let alive = collect_descendants(root);
        if alive.is_empty() {
            return;
        }
        for child in alive {
            if signaled.insert(child) {
                unsafe { libc::kill(child, libc::SIGTERM); }
            }
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    // Forceful phase: SIGKILL cannot be blocked or caught, so re-sending to
    // whatever is still alive each sweep is exactly what converges.
    let deadline = Instant::now() + Duration::from_millis(500);
    while Instant::now() < deadline {
        reap_orphans();
        let alive = collect_descendants(root);
        if alive.is_empty() {
            return;
        }
        for child in alive {
            unsafe { libc::kill(child, libc::SIGKILL); }
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}
