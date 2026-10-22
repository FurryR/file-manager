use std::collections::HashMap;

use tokio::process::Child;

/// A pipe-mode child kept alive between its Exec response and WaitExec.
/// Dropping it (explicit close, wait completion, or daemon shutdown) kills any
/// remaining members of its process group.
pub struct ExecProcess {
    pub child: Child,
    pid: u32,
}

impl ExecProcess {
    fn new(child: Child) -> Self {
        let pid = child.id().unwrap_or(0);
        Self { child, pid }
    }
}

impl Drop for ExecProcess {
    fn drop(&mut self) {
        if self.pid != 0 {
            unsafe {
                libc::kill(-(self.pid as i32), libc::SIGKILL);
            }
        }
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
    pub fn insert(&mut self, child: Child) -> (u64, u32) {
        let process = ExecProcess::new(child);
        let pid = process.pid;
        self.next_id = self.next_id.wrapping_add(1).max(1);
        let id = self.next_id;
        self.processes.insert(id, process);
        (id, pid)
    }

    pub fn remove(&mut self, id: u64) -> Option<ExecProcess> {
        self.processes.remove(&id)
    }

    pub fn clear(&mut self) {
        self.processes.clear();
    }
}
