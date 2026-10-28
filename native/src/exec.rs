use std::collections::HashMap;

use tokio::sync::watch;

/// A pipe-mode child kept alive between its Exec response and WaitExec.
///
/// The daemon-wide reaper owns `waitpid`; this entry only subscribes to the
/// exit code. Dropping it while the child is still running kills the whole
/// process group, so closing an exec takes effect immediately.
pub struct ExecProcess {
    pid: u32,
    /// Exit code published by the reaper; `None` while still running.
    exit: watch::Receiver<Option<i32>>,
}

impl ExecProcess {
    /// Subscribe to the exit code without taking ownership of the process.
    pub fn subscribe(&self) -> watch::Receiver<Option<i32>> {
        self.exit.clone()
    }
}

impl Drop for ExecProcess {
    fn drop(&mut self) {
        // The reaper kills the group when the leader exits; do it here only
        // while the child can still be running. Pid reuse after that point
        // could otherwise signal an unrelated process.
        if self.exit.borrow().is_none() {
            unsafe {
                libc::kill(-(self.pid as i32), libc::SIGKILL);
            }
        }
        crate::reaper::release(self.pid);
    }
}

/// Daemon-wide pipe-mode execs. Handles are global so a later WaitExec can be
/// served by any authenticated client, matching PTYs and file handles.
#[derive(Default)]
pub struct ExecManager {
    next_id: u64,
    processes: HashMap<u64, ExecProcess>,
}

impl ExecManager {
    /// Register a spawned child. The caller must have registered the pid with
    /// `reaper::track` before any await and must have created a process group.
    pub fn insert(&mut self, pid: u32, exit: watch::Receiver<Option<i32>>) -> u64 {
        let process = ExecProcess { pid, exit };
        self.next_id = self.next_id.wrapping_add(1).max(1);
        let id = self.next_id;
        self.processes.insert(id, process);
        id
    }

    /// Look up an exec's exit subscription without removing it.
    pub fn subscribe(&self, id: u64) -> Option<watch::Receiver<Option<i32>>> {
        self.processes.get(&id).map(ExecProcess::subscribe)
    }

    pub fn remove(&mut self, id: u64) -> Option<ExecProcess> {
        self.processes.remove(&id)
    }

    pub fn clear(&mut self) {
        self.processes.clear();
    }
}
