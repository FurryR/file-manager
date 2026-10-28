use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::io;
use std::mem;
use std::os::fd::{AsRawFd, FromRawFd, IntoRawFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::sync::atomic::{AtomicU64, Ordering};

use crate::proto::SpawnPtyRequest;

static NEXT_PTY_ID: AtomicU64 = AtomicU64::new(1);

fn reopen_cwd(fd: RawFd) -> io::Result<OwnedFd> {
    let mut original = unsafe { mem::zeroed::<libc::stat>() };
    if unsafe { libc::fstat(fd, &mut original) } != 0 {
        return Err(io::Error::last_os_error());
    }
    if original.st_mode & libc::S_IFMT != libc::S_IFDIR {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "cwd fd is not a directory"));
    }
    let path = std::fs::read_link(format!("/proc/self/fd/{fd}"))?;
    if !path.is_absolute() || path.as_os_str().as_bytes().ends_with(b" (deleted)") {
        return Err(io::Error::new(io::ErrorKind::NotFound, "cwd fd pathname is deleted or unreachable"));
    }
    let candidate = CString::new(path.as_os_str().as_bytes())
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
    // Resolve in the daemon's own filesystem. Never fchdir the received fd:
    // it may refer to a private mount or a directory outside the target root.
    let reopened = unsafe { libc::open(candidate.as_ptr(), libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC) };
    if reopened < 0 {
        let error = io::Error::last_os_error();
        return Err(io::Error::new(error.kind(),
            format!("cannot reopen cwd {} in target: {error}", path.display())));
    }
    Ok(unsafe { OwnedFd::from_raw_fd(reopened) })
}

// Called only after the child has dropped credentials. Use a parent-allocated
// buffer so cwd validation does not allocate after fork.
unsafe fn enter_fd_cwd(fd: RawFd, buffer: &mut [libc::c_char]) -> bool {
    if libc::fchdir(fd) != 0 {
        return false;
    }
    if libc::getcwd(buffer.as_mut_ptr(), buffer.len()).is_null() {
        return false;
    }
    if buffer[0] != b'/' as libc::c_char {
        // Linux can report "(unreachable)/..." for a cwd outside the root.
        #[cfg(not(target_os = "android"))]
        { *libc::__errno_location() = libc::ENOENT; }
        #[cfg(target_os = "android")]
        { *libc::__errno() = libc::ENOENT; }
        return false;
    }
    true
}

/// A resolved target user for a spawned command.
struct RunAs {
    uid: libc::uid_t,
    gid: libc::gid_t,
    name: CString,
    home: Option<String>,
    shell: Option<String>,
}

fn cstr_to_string(ptr: *const libc::c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(ptr) }.to_str().ok().map(str::to_owned)
}

fn parse_passwd(contents: &str, user: &str) -> Option<RunAs> {
    let requested_uid = user.parse::<libc::uid_t>().ok();
    contents.lines().find_map(|line| {
        let fields: Vec<_> = line.split(':').collect();
        if fields.len() != 7 || fields[0].is_empty() || fields[0].starts_with('#') || line.contains('\0') {
            return None;
        }
        let uid = fields[2].parse::<libc::uid_t>().ok()?;
        let gid = fields[3].parse::<libc::gid_t>().ok()?;
        if requested_uid.map_or(fields[0] != user, |requested| uid != requested) {
            return None;
        }
        Some(RunAs {
            uid,
            gid,
            name: CString::new(fields[0]).ok()?,
            home: Some(fields[5].to_owned()),
            shell: Some(fields[6].to_owned()),
        })
    })
}

/// Prefer the target filesystem's passwd file: Bionic synthesizes Android
/// entries even inside containers. Copy libc's shared passwd buffer on fallback.
fn resolve_user(user: &str) -> io::Result<RunAs> {
    if user.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "empty user"));
    }

    match std::fs::read_to_string("/etc/passwd") {
        Ok(contents) => {
            if let Some(target) = parse_passwd(&contents, user) {
                return Ok(target);
            }
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(error),
    }

    if let Ok(uid) = user.parse::<libc::uid_t>() {
        let mut gid = uid;
        let mut name = CString::new(user).expect("numeric user is NUL-free");
        let mut home = None;
        let mut shell = None;
        let passwd = unsafe { libc::getpwuid(uid) };
        if !passwd.is_null() {
            unsafe {
                gid = (*passwd).pw_gid;
                if let Some(entry) = cstr_to_string((*passwd).pw_name) {
                    name = CString::new(entry).expect("pw_name is NUL-free");
                }
                home = cstr_to_string((*passwd).pw_dir);
                shell = cstr_to_string((*passwd).pw_shell);
            }
        }
        return Ok(RunAs { uid, gid, name, home, shell });
    }

    let requested = CString::new(user).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
    let passwd = unsafe { libc::getpwnam(requested.as_ptr()) };
    if passwd.is_null() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            format!("unknown user '{user}'"),
        ));
    }
    unsafe {
        let uid = (*passwd).pw_uid;
        let gid = (*passwd).pw_gid;
        let name = cstr_to_string((*passwd).pw_name).unwrap_or_else(|| user.to_owned());
        let home = cstr_to_string((*passwd).pw_dir);
        Ok(RunAs {
            uid,
            gid,
            name: CString::new(name).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?,
            home,
            shell: cstr_to_string((*passwd).pw_shell),
        })
    }
}

fn resolve_shell(passwd_shell: Option<&str>) -> io::Result<String> {
    use std::os::unix::fs::PermissionsExt;

    // The daemon already lives in the target filesystem, including containers.
    passwd_shell.into_iter().chain(["/bin/sh", "/system/bin/sh"])
        .find(|shell| {
            std::path::Path::new(shell).is_absolute()
                && std::fs::metadata(shell).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
        })
        .map(str::to_owned)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "target user has no executable shell"))
}

fn build_environment(req: &SpawnPtyRequest, target: Option<&RunAs>, command: &str) -> HashMap<String, String> {
    let mut env: HashMap<String, String> = if req.command.is_empty() {
        let path = if command.starts_with("/system/") {
            "/system/bin:/system/xbin"
        } else {
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        };
        let mut env = HashMap::from([
            ("SHELL".into(), command.into()),
            ("PATH".into(), std::env::var("PATH").unwrap_or_else(|_| path.into())),
        ]);
        if std::path::Path::new("/tmp").is_dir() {
            env.insert("TMPDIR".into(), "/tmp".into());
        }
        env
    } else {
        std::env::vars().collect()
    };
    if let Some(target) = target {
        if let Some(home) = &target.home {
            env.insert("HOME".into(), home.clone());
        }
        let name = target.name.to_string_lossy().into_owned();
        env.insert("USER".into(), name.clone());
        env.insert("LOGNAME".into(), name);
    }
    if !req.term_type.is_empty() {
        env.insert("TERM".into(), req.term_type.clone());
    }
    env.extend(req.env.clone());
    env
}

#[derive(Debug)]
pub struct PtyManager {
    ptys: HashMap<u64, PtyHandle>,
}

#[derive(Debug)]
struct PtyHandle {
    master_fd: RawFd,
    child_pid: u32,
    /// Exit code published by the daemon-wide reaper.
    exit: tokio::sync::watch::Receiver<Option<i32>>,
    #[allow(dead_code)]
    container_name: Option<String>,
}

impl PtyManager {
    pub fn new() -> Self {
        PtyManager {
            ptys: HashMap::new(),
        }
    }

    pub(crate) fn spawn_pty_with_cwd_fd(
        &mut self, req: SpawnPtyRequest, attached_fd: Option<OwnedFd>,
    ) -> io::Result<(u64, RawFd, u32)> {
        let cwd_fd = match (req.cwd_fd, attached_fd) {
            (true, Some(fd)) => Some(reopen_cwd(fd.as_raw_fd())?),
            (true, None) => return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing cwd fd")),
            (false, Some(_)) => return Err(io::Error::new(io::ErrorKind::InvalidInput, "unexpected cwd fd")),
            (false, None) => None,
        };
        // The original descriptor has been dropped, including on validation
        // errors. Only the reopened CLOEXEC descriptor is inherited by fork.
        let mut cwd_buffer = [0 as libc::c_char; libc::PATH_MAX as usize];
        // 1. Open a PTY master
        let master_fd = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC) };
        if master_fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let master = unsafe { OwnedFd::from_raw_fd(master_fd) };
        let master_fd = master.as_raw_fd();

        // 2. Grant and unlock slave
        if unsafe { libc::grantpt(master_fd) } != 0 {
            return Err(io::Error::last_os_error());
        }
        if unsafe { libc::unlockpt(master_fd) } != 0 {
            return Err(io::Error::last_os_error());
        }

        // 3. Get slave path
        let mut slave_buf = [0u8; 128];
        if unsafe { libc::ptsname_r(master_fd, slave_buf.as_mut_ptr() as *mut libc::c_char, slave_buf.len()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let slave_ptr = slave_buf.as_ptr();

        // 4. Build command/args/env as CStrings in the parent so they are
        //    safely accessible after fork (fork duplicates memory).
        // Resolve the requested run-as user up front, so an unknown user is a
        // clean request error rather than a child that dies after fork. A
        // non-root daemon can only "switch" to its own uid.
        let run_as = match req.user.as_deref() {
            Some(user) if !user.is_empty() => Some(resolve_user(user)?),
            _ => None,
        };
        if let Some(ref target) = run_as {
            let euid = unsafe { libc::geteuid() };
            if euid != 0 && target.uid != euid {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    format!("daemon (uid {euid}) cannot run as uid {}", target.uid),
                ));
            }
        }

        let own_user = if req.command.is_empty() && run_as.is_none() {
            Some(resolve_user(&unsafe { libc::geteuid() }.to_string())?)
        } else {
            None
        };
        let target = run_as.as_ref().or(own_user.as_ref());
        let command = if req.command.is_empty() {
            resolve_shell(target.unwrap().shell.as_deref())?
        } else {
            req.command.clone()
        };
        let argv0 = if req.command.is_empty() {
            command.rsplit('/').next().unwrap()
        } else {
            &command
        };
        let argv: Vec<CString> = std::iter::once(argv0).chain(req.args.iter().map(String::as_str))
            .map(|arg| CString::new(arg).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e)))
            .collect::<io::Result<_>>()?;

        // A negative nice level requires CAP_SYS_NICE (root).
        let nice = req.nice;
        if let Some(value) = nice {
            let euid = unsafe { libc::geteuid() };
            if value < 0 && euid != 0 {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    format!("daemon (uid {euid}) cannot set nice {value} (negative requires root)"),
                ));
            }
        }

        let env_map = build_environment(&req, target, &command);
        // Prepare PATH candidates before fork, but try them in the child's cwd
        // and with its credentials. Empty PATH entries mean the current directory.
        let commands: Vec<CString> = if command.contains('/') {
            vec![command.clone()]
        } else {
            #[cfg(target_os = "android")]
            let default_path = "/system/bin:/system/xbin";
            #[cfg(not(target_os = "android"))]
            let default_path = "/bin:/usr/bin";
            env_map.get("PATH").map(String::as_str).unwrap_or(default_path)
                .split(':')
                .map(|dir| if dir.is_empty() { command.clone() } else { format!("{dir}/{command}") })
                .collect()
        }.into_iter()
            .map(|command| CString::new(command).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e)))
            .collect::<io::Result<_>>()?;

        let mut envp: Vec<CString> = Vec::with_capacity(env_map.len() + 1);
        for (k, v) in &env_map {
            let entry = format!("{}={}", k, v);
            envp.push(CString::new(entry.as_bytes()).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?);
        }

        // Convert to raw pointer arrays for execve
        let mut argv_ptrs: Vec<*const libc::c_char> = argv.iter().map(|c| c.as_ptr()).collect();
        argv_ptrs.push(std::ptr::null());
        let mut envp_ptrs: Vec<*const libc::c_char> = envp.iter().map(|c| c.as_ptr()).collect();
        envp_ptrs.push(std::ptr::null());

        let rows = req.rows;
        let cols = req.cols;
        let cwd = if req.cwd_fd {
            String::new()
        } else if req.cwd == "~" {
            let home = match target {
                Some(target) => target.home.clone(),
                None => resolve_user(&unsafe { libc::geteuid() }.to_string())?.home
                    .or_else(|| env_map.get("HOME").cloned()),
            };
            home.filter(|home| !home.is_empty()).ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotFound, "target user has no home directory")
            })?
        } else {
            req.cwd.clone()
        };
        let cwd_c = if !cwd.is_empty() {
            Some(CString::new(cwd).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?)
        } else {
            None
        };

        // Configure the slave's terminal settings through the master before fork.
        let mut termios = unsafe { mem::zeroed() };
        if unsafe { libc::tcgetattr(master_fd, &mut termios) } != 0 {
            return Err(io::Error::last_os_error());
        }
        termios.c_iflag = (termios.c_iflag & !(libc::IXON | libc::IXOFF)) | libc::IUTF8;
        if unsafe { libc::tcsetattr(master_fd, libc::TCSANOW, &termios) } != 0 {
            return Err(io::Error::last_os_error());
        }

        // 5. Fork
        let pid = unsafe { libc::fork() };
        if pid < 0 {
            return Err(io::Error::last_os_error());
        }

        if pid == 0 {
            // ── CHILD ──────────────────────────────────────────────────
            unsafe {
                // Neither blocked daemon signals nor Rust's ignored SIGPIPE belong in commands.
                let mut mask = mem::zeroed();
                if libc::sigemptyset(&mut mask) != 0
                    || libc::sigprocmask(libc::SIG_SETMASK, &mask, std::ptr::null_mut()) != 0
                    || libc::signal(libc::SIGPIPE, libc::SIG_DFL) == libc::SIG_ERR
                {
                    libc::_exit(126);
                }

                // Create new session (child becomes session leader, no ctty)
                libc::setsid();

                // Open the slave end of the PTY
                let slave_fd = libc::open(slave_ptr as *const libc::c_char, libc::O_RDWR);
                if slave_fd < 0 {
                    libc::_exit(1);
                }

                // Make the slave the controlling terminal of the new session
                libc::ioctl(slave_fd, TIOCSCTTY as _, 0);

                // Set initial terminal size
                let winsize = libc::winsize {
                    ws_row: rows as u16,
                    ws_col: cols as u16,
                    ws_xpixel: 0,
                    ws_ypixel: 0,
                };
                libc::ioctl(slave_fd, libc::TIOCSWINSZ, &winsize);

                // Duplicate slave to stdin/stdout/stderr
                libc::dup2(slave_fd, libc::STDIN_FILENO);
                libc::dup2(slave_fd, libc::STDOUT_FILENO);
                libc::dup2(slave_fd, libc::STDERR_FILENO);
                if slave_fd > libc::STDERR_FILENO {
                    libc::close(slave_fd);
                }

                // Close master fd — child does not need it
                libc::close(master_fd);

                // Apply the requested nice level before dropping privileges --
                // lowering niceness needs CAP_SYS_NICE, which root still has.
                if let Some(value) = nice {
                    libc::setpriority(libc::PRIO_PROCESS, 0, value);
                }

                // Drop to the requested user, if any. Refuse to run with the
                // wrong credentials if the switch fails.
                if let Some(ref target) = run_as {
                    libc::initgroups(target.name.as_ptr(), target.gid);
                    if libc::setgid(target.gid) != 0 {
                        libc::_exit(126);
                    }
                    if libc::setuid(target.uid) != 0 {
                        libc::_exit(126);
                    }
                }

                if let Some(ref fd) = cwd_fd {
                    if !enter_fd_cwd(fd.as_raw_fd(), &mut cwd_buffer) {
                        libc::perror(b"cwd fd\0".as_ptr().cast());
                        libc::_exit(126);
                    }
                    libc::close(fd.as_raw_fd());
                } else if let Some(ref cwd) = cwd_c {
                    if libc::chdir(cwd.as_ptr()) != 0 {
                        libc::perror(b"chdir\0".as_ptr().cast());
                        libc::_exit(126);
                    }
                }

                let mut denied = false;
                for command in &commands {
                    libc::execve(command.as_ptr(), argv_ptrs.as_ptr(), envp_ptrs.as_ptr());
                    match io::Error::last_os_error().raw_os_error() {
                        Some(libc::ENOENT) | Some(libc::ENOTDIR) => {}
                        Some(libc::EACCES) => denied = true,
                        _ => libc::_exit(126),
                    }
                }

                // Distinguish a missing command from one that cannot be executed.
                libc::_exit(if denied { 126 } else { 127 });
            }
        }

        // ── PARENT ────────────────────────────────────────────────────
        // Register with the daemon-wide reaper before any other task can run.
        // WaitPty subscribes to the watch channel instead of racing a waitpid.
        let child_pid = pid as u32;
        let exit = crate::reaper::track(child_pid);
        // Allocate PTY id and store handle
        let pty_id = NEXT_PTY_ID.fetch_add(1, Ordering::SeqCst);
        self.ptys.insert(
            pty_id,
            PtyHandle {
                master_fd: master.into_raw_fd(),
                child_pid,
                exit,
                container_name: None,
            },
        );

        Ok((pty_id, master_fd, child_pid))
    }

    /// Resize the terminal window. Sends SIGWINCH to the child process group
    /// so it re-reads the terminal dimensions.
    pub fn resize_pty(&mut self, pty_id: u64, rows: u32, cols: u32) -> io::Result<()> {
        let handle = self.ptys.get(&pty_id).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("pty {} not found", pty_id),
            )
        })?;

        let winsize = libc::winsize {
            ws_row: rows as u16,
            ws_col: cols as u16,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };

        if unsafe { libc::ioctl(handle.master_fd, libc::TIOCSWINSZ, &winsize) } != 0 {
            return Err(io::Error::last_os_error());
        }

        // Notify foreground process group of the size change
        unsafe {
            libc::kill(-(handle.child_pid as i32), libc::SIGWINCH);
        }

        Ok(())
    }

    /// Kill the child process group, close the master fd, and remove the
    /// handle from the manager.
    pub fn close_pty(&mut self, pty_id: u64) -> io::Result<()> {
        let handle = self.ptys.remove(&pty_id).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("pty {} not found", pty_id),
            )
        })?;

        // Kill the entire process group (negative pid → process group)
        // SIGHUP first to give processes a chance to clean up. Once the child
        // is reaped its pid may be recycled, so never signal a dead group.
        unsafe {
            if handle.exit.borrow().is_none() {
                libc::kill(-(handle.child_pid as i32), libc::SIGHUP);
                // SIGKILL to ensure termination
                libc::kill(-(handle.child_pid as i32), libc::SIGKILL);
                // The parent can close a fresh PTY before the child has called setsid().
                libc::kill(handle.child_pid as i32, libc::SIGKILL);
            }
            libc::close(handle.master_fd);
        }

        Ok(())
    }

    /// Deliver an arbitrary signal to a PTY's process group. Used by the run0
    /// attach path via the hub, which needs more than SIGHUP/SIGKILL.
    pub fn signal_pty(&mut self, pty_id: u64, signal: i32) -> io::Result<()> {
        let handle = self.ptys.get(&pty_id).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("pty {} not found", pty_id),
            )
        })?;
        // A reaped child's pid may have been recycled; never signal a dead group.
        if handle.exit.borrow().is_some() {
            return Ok(());
        }
        // Negative pid targets the whole process group.
        unsafe {
            libc::kill(-(handle.child_pid as i32), signal);
        }
        Ok(())
    }

    pub fn close_all(&mut self) {
        let pty_ids = self.ptys.keys().copied().collect::<Vec<_>>();
        for pty_id in pty_ids {
            let _ = self.close_pty(pty_id);
        }
    }
    /// Subscribe to a PTY child's exit code; None while it is still running.
    pub fn exit_receiver(
        &self,
        pty_id: u64,
    ) -> Option<tokio::sync::watch::Receiver<Option<i32>>> {
        self.ptys.get(&pty_id).map(|h| h.exit.clone())
    }

    /// Remove a PTY handle and close the master fd.
    /// Called after the child has already been reaped.
    pub fn remove_pty(&mut self, pty_id: u64) -> Option<RawFd> {
        self.ptys.remove(&pty_id).map(|h| {
            let fd = h.master_fd;
            unsafe { libc::close(fd); }
            fd
        })
    }
}

// ── TIOCSCTTY definition ─────────────────────────────────────────────
// This ioctl is not present in the libc crate for all target triples,
// so we define it ourselves (standard Linux value).
#[cfg(not(any(target_os = "android", target_os = "linux")))]
const TIOCSCTTY: libc::c_ulong = 0x540E;
#[cfg(any(target_os = "android", target_os = "linux"))]
const TIOCSCTTY: libc::c_ulong = 0x540E;
