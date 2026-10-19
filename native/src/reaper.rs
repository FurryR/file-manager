//! Reap adopted orphans without consuming exit statuses owned by Tokio or a PTY.
use std::collections::HashSet;
use std::io;
use std::sync::{Mutex, OnceLock};

fn managed() -> &'static Mutex<HashSet<i32>> {
    static PIDS: OnceLock<Mutex<HashSet<i32>>> = OnceLock::new();
    PIDS.get_or_init(|| Mutex::new(HashSet::new()))
}
pub fn track(pid: u32) {
    managed().lock().unwrap().insert(pid as i32);
}
pub fn release(pid: u32) {
    managed().lock().unwrap().remove(&(pid as i32));
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
pub fn reap_orphans() {
    let pid = std::process::id();
    let Ok(children) = std::fs::read_to_string(format!("/proc/{pid}/task/{pid}/children")) else {
        return;
    };
    let mut tracked = managed().lock().unwrap();
    // Cancelled Tokio commands are killed/reaped by Tokio itself. Keep their
    // registration until /proc confirms they have gone; never waitpid(-1).
    tracked.retain(|pid| std::path::Path::new(&format!("/proc/{pid}")).exists());
    for child in children
        .split_whitespace()
        .filter_map(|p| p.parse::<i32>().ok())
    {
        if !tracked.contains(&child) {
            let mut status = 0;
            unsafe {
                libc::waitpid(child, &mut status, libc::WNOHANG);
            }
        }
    }
}
