//! run0 — elevate privileges interactively through the file-manager hub.
//!
//! It reads the hub's abstract socket name from `$HUB_SOCKET`, connects, asks
//! the hub to run a command under a chosen user (optionally inside a container),
//! and bridges the returned PTY master fd to the local terminal.

use std::collections::HashMap;
use std::env;
use std::io;
use std::mem;
use std::os::fd::{AsRawFd, RawFd};
use std::process::exit;
use std::sync::atomic::{AtomicI32, Ordering};

use prost::Message;

use filehub::hub_request::Op as HubOp;
use filehub::{ExecRequest, HubRequest, WaitRequest};
use hub_client::{
    connect_from_env, filehub, receive_response, round_trip_with_attached_fd, send_frame,
};

const TIOCGWINSZ: libc::c_ulong = 0x5413;
const TIOCSWINSZ: libc::c_ulong = 0x5414;

static WINCH_WRITE: AtomicI32 = AtomicI32::new(-1);
static mut ORIG_TERMIOS: Option<libc::termios> = None;

const USAGE: &str = "\
run0 [OPTIONS...] [COMMAND [ARGUMENTS...]]

Elevate privileges interactively.

Options:
  -h --help                       Show this help
  -V --version                    Show package version
     --machine=CONTAINER          Operate on local container
  -u --user=USER                  Run as system user
  -D --chdir=PATH                 Set working directory
     --via-shell                  Invoke command via target user's login shell
  -i                              Shortcut for --via-shell --chdir='~'
     --setenv=NAME[=VALUE]        Set environment variable
";

fn main() {
    let args = parse_args();
    match run(args) {
        Ok(code) => exit(code),
        Err(error) => {
            eprintln!("run0: {error}");
            exit(2);
        }
    }
}

// ── Argument parsing ─────────────────────────────────────────────────

#[derive(Default)]
struct Args {
    user: Option<String>,
    chdir: Option<String>,
    machine: Option<String>,
    setenv: Vec<(String, String)>,
    nice: Option<i32>,
    via_shell: bool,
    command: Vec<String>,
}

fn parse_args() -> Args {
    let mut args = Args::default();
    let mut it = env::args().skip(1).peekable();
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "-h" | "--help" => {
                print!("{USAGE}");
                exit(0);
            }
            "-V" | "--version" => {
                println!("run0 {}", env!("CARGO_PKG_VERSION"));
                exit(0);
            }
            "-u" | "--user" => args.user = Some(take_value(&mut it, &arg)),
            "--machine" => args.machine = Some(take_value(&mut it, &arg)),
            "-D" | "--chdir" => args.chdir = Some(take_value(&mut it, &arg)),
            "--setenv" => args.setenv.push(parse_setenv(&take_value(&mut it, &arg))),
            "--nice" => args.nice = Some(parse_nice(&take_value(&mut it, &arg))),
            "--via-shell" => args.via_shell = true,
            "-i" => {
                args.via_shell = true;
                args.chdir.get_or_insert_with(|| "~".to_string());
            }
            // Accepted but ignored.
            "--no-ask-password" | "--pty" | "--pty-late" | "--pipe" | "--slice-inherit"
            | "--lightweight" | "--empower" => {}
            other => {
                if let Some(rest) = other.strip_prefix("--user=") {
                    args.user = Some(rest.to_string());
                } else if let Some(rest) = other.strip_prefix("--machine=") {
                    args.machine = Some(rest.to_string());
                } else if let Some(rest) = other.strip_prefix("--chdir=") {
                    args.chdir = Some(rest.to_string());
                } else if let Some(rest) = other.strip_prefix("--setenv=") {
                    args.setenv.push(parse_setenv(rest));
                } else if let Some(rest) = other.strip_prefix("--nice=") {
                    args.nice = Some(parse_nice(rest));
                } else if other.starts_with('-') {
                    eprintln!("run0: unsupported option '{other}'");
                    exit(2);
                } else {
                    args.command.push(other.to_string());
                    args.command.extend(it);
                    break;
                }
            }
        }
    }
    args
}

fn take_value(it: &mut impl Iterator<Item = String>, flag: &str) -> String {
    it.next().unwrap_or_else(|| {
        eprintln!("run0: missing value for '{flag}'");
        exit(2);
    })
}

fn parse_setenv(spec: &str) -> (String, String) {
    match spec.split_once('=') {
        Some((name, value)) => (name.to_string(), value.to_string()),
        None => (spec.to_string(), env::var(spec).unwrap_or_default()),
    }
}

fn parse_nice(value: &str) -> i32 {
    value.parse::<i32>().unwrap_or_else(|_| {
        eprintln!("run0: invalid --nice value '{value}'");
        exit(2);
    })
}

// ── The run ──────────────────────────────────────────────────────────

fn command_spec(args: &Args) -> (String, Vec<String>) {
    let mut shell_args = Vec::new();
    if args.via_shell {
        shell_args.push("-l".to_string());
    }
    if !args.command.is_empty() {
        let script = args
            .command
            .iter()
            .map(|word| format!("'{}'", word.replace('\'', "'\\''")))
            .collect::<Vec<_>>()
            .join(" ");
        shell_args.extend(["-c".to_string(), script]);
    }
    (String::new(), shell_args)
}

fn working_directory(chdir: Option<&str>) -> io::Result<Option<String>> {
    match chdir {
        // Preserve the caller's directory; the daemon's own cwd belongs to the app process.
        None => Ok(env::current_dir()
            .ok()
            .map(|path| path.to_string_lossy().into_owned())),
        Some(path) if path == "~" || path.starts_with("~/") => Ok(Some(path.to_string())),
        Some(path) => {
            let path = std::path::Path::new(path);
            if path.is_absolute() {
                Ok(Some(path.to_string_lossy().into_owned()))
            } else {
                Ok(Some(
                    env::current_dir()?
                        .join(path)
                        .to_string_lossy()
                        .into_owned(),
                ))
            }
        }
    }
}

fn container_id(machine: Option<&str>) -> io::Result<String> {
    match machine {
        None => Ok("host".to_string()),
        Some("") => Err(io::Error::other("empty --machine value")),
        Some(value) => Ok(format!("container-{value}")),
    }
}

fn run(args: Args) -> io::Result<i32> {
    let (rows, cols) = tty_size(libc::STDIN_FILENO);
    let term = env::var("TERM").unwrap_or_else(|_| "xterm-256color".to_string());

    // Let the target shell resolve commands and report its own execution errors.
    let (command, command_args) = command_spec(&args);
    let cwd = working_directory(args.chdir.as_deref())?;
    let container_id = container_id(args.machine.as_deref())?;

    let mut env_map = HashMap::new();
    for (name, value) in args.setenv {
        env_map.insert(name, value);
    }

    let (control, _root) = connect_from_env()?;
    let fd = control.as_raw_fd();
    ignore_sigpipe();

    let exec = ExecRequest {
        command,
        args: command_args,
        env: env_map,
        cwd: cwd.unwrap_or_default(),
        user: args.user,
        term,
        rows,
        cols,
        nice: args.nice,
        container_id,
    };
    let request = HubRequest {
        id: 2,
        op: Some(HubOp::Exec(exec)),
    };
    let (response, master) = round_trip_with_attached_fd(fd, request, None)?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    let master = master
        .ok_or_else(|| io::Error::other("hub returned no PTY fd (SCM_RIGHTS was dropped?)"))?;
    let pty_id = match response.result {
        Some(filehub::hub_response::Result::Exec(e)) => e.pty_id,
        _ => return Err(io::Error::other("hub returned no Exec result")),
    };

    bridge(fd, master.as_raw_fd(), pty_id)
}

/// Put the terminal into raw mode and pump bytes and resizes while waiting for
/// the child, restoring the terminal before returning its exit code.
fn bridge(control_fd: RawFd, master: RawFd, pty_id: u64) -> io::Result<i32> {
    if unsafe { libc::isatty(libc::STDIN_FILENO) } == 1 {
        let original = make_raw(libc::STDIN_FILENO)?;
        unsafe { ORIG_TERMIOS = Some(original) };
    }
    install_winch();
    install_restore_handlers();
    let winch_read = WINCH_READ.load(Ordering::Relaxed);
    let result = pump(
        control_fd,
        master,
        pty_id,
        libc::STDIN_FILENO,
        libc::STDOUT_FILENO,
        winch_read,
    );
    restore_termios();
    unsafe { ORIG_TERMIOS = None };
    for slot in [&WINCH_WRITE, &WINCH_READ] {
        let fd = slot.swap(-1, Ordering::Relaxed);
        if fd >= 0 {
            unsafe { libc::close(fd) };
        }
    }
    result
}

fn pump(
    control_fd: RawFd,
    master: RawFd,
    pty_id: u64,
    input: RawFd,
    output: RawFd,
    winch: RawFd,
) -> io::Result<i32> {
    let flags = unsafe { libc::fcntl(master, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(master, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }
    // Wait for the child concurrently with terminal I/O. Resize the shared
    // master locally so no extra control responses enter this receive loop.
    send_frame(
        control_fd,
        &HubRequest {
            id: 3,
            op: Some(HubOp::Wait(WaitRequest { pty_id })),
        }
        .encode_to_vec(),
    )?;
    let mut buf = [0u8; 4096];
    let mut pending = Vec::new();
    let mut input_open = true;
    let mut master_open = true;
    let mut code = None;
    let mut final_output = 64 * 1024;
    loop {
        let mut pollfds = [
            libc::pollfd {
                fd: if master_open { master } else { -1 },
                events: libc::POLLIN | if pending.is_empty() { 0 } else { libc::POLLOUT },
                revents: 0,
            },
            libc::pollfd {
                fd: winch,
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: if code.is_none() { control_fd } else { -1 },
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: if input_open && master_open && pending.is_empty() && code.is_none() {
                    input
                } else {
                    -1
                },
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        let ready = unsafe {
            libc::poll(
                pollfds.as_mut_ptr(),
                pollfds.len() as _,
                if code.is_some() { 0 } else { -1 },
            )
        };
        if ready < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if ready == 0 && code.is_some() {
            break;
        }
        if pollfds.iter().any(|p| p.revents & libc::POLLNVAL != 0) {
            return Err(io::Error::other("invalid bridge descriptor"));
        }
        if pollfds[1].revents & libc::POLLIN != 0 {
            drain(winch);
            resize(master);
        }
        if pollfds[2].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
            let response = receive_response(control_fd, 3)?.0;
            if !response.ok {
                return Err(io::Error::other(response.error));
            }
            code = Some(match response.result {
                Some(filehub::hub_response::Result::Wait(w)) => w.exit_code,
                _ => return Err(io::Error::other("hub returned no Wait result")),
            });
            pending.clear();
        }
        if pollfds[3].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0
            && code.is_none()
        {
            let n = unsafe { libc::read(input, buf.as_mut_ptr().cast(), buf.len()) };
            if n > 0 {
                pending.extend_from_slice(&buf[..n as usize]);
            } else if n == 0 {
                input_open = false;
                // A PTY cannot be half-closed. Supply canonical EOF for piped input.
                // The first EOF can flush a partial line; the second ends input.
                pending.extend_from_slice(&[4, 4]);
            } else {
                let error = io::Error::last_os_error();
                if error.kind() != io::ErrorKind::Interrupted {
                    return Err(error);
                }
            }
        }
        if pollfds[0].revents & libc::POLLOUT != 0 && !pending.is_empty() {
            let n = unsafe { libc::write(master, pending.as_ptr().cast(), pending.len()) };
            if n > 0 {
                pending.drain(..n as usize);
            } else if n < 0 {
                let error = io::Error::last_os_error();
                if !matches!(
                    error.kind(),
                    io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock
                ) && error.raw_os_error() != Some(libc::EIO)
                {
                    return Err(error);
                }
            }
        }

        if master_open
            && (code.is_some()
                || pollfds[0].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0)
        {
            let limit = if code.is_some() {
                buf.len().min(final_output)
            } else {
                buf.len()
            };
            let n = unsafe { libc::read(master, buf.as_mut_ptr() as *mut libc::c_void, limit) };
            if n > 0 {
                write_all(output, &buf[..n as usize])?;
                if code.is_some() {
                    final_output -= n as usize;
                    if final_output == 0 {
                        break;
                    }
                }
            } else {
                let error = io::Error::last_os_error();
                if n == 0 || error.raw_os_error() == Some(libc::EIO) {
                    master_open = false;
                    pending.clear();
                } else if !matches!(
                    error.kind(),
                    io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock
                ) {
                    return Err(error);
                } else if code.is_some() && error.kind() == io::ErrorKind::WouldBlock {
                    break;
                }
            }
        }
        // Wait reaps and releases the daemon handle. Descendants may retain the
        // slave, so drain bounded queued output without waiting for their EOF.
        if code.is_some() && !master_open {
            break;
        }
    }
    let code = code.unwrap();
    // The daemon represents signal termination as a negative signal number.
    Ok(if code < 0 {
        code.saturating_neg().saturating_add(128)
    } else {
        code
    })
}

fn resize(master: RawFd) {
    let (rows, cols) = tty_size(libc::STDIN_FILENO);
    let winsize = libc::winsize {
        ws_row: rows as u16,
        ws_col: cols as u16,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    // Set locally on the master; the kernel signals the child's process group.
    unsafe { libc::ioctl(master, TIOCSWINSZ as _, &winsize) };
}

// ── Hub protocol I/O ─────────────────────────────────────────────────

fn write_all(fd: RawFd, data: &[u8]) -> io::Result<()> {
    let mut sent = 0;
    while sent < data.len() {
        let n = unsafe {
            libc::write(
                fd,
                data[sent..].as_ptr() as *const libc::c_void,
                data.len() - sent,
            )
        };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if n == 0 {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "write returned zero bytes",
            ));
        }
        sent += n as usize;
    }
    Ok(())
}

// ── Terminal ─────────────────────────────────────────────────────────

fn tty_size(fd: RawFd) -> (u32, u32) {
    let mut winsize: libc::winsize = unsafe { mem::zeroed() };
    if unsafe { libc::ioctl(fd, TIOCGWINSZ as _, &mut winsize) } == 0
        && winsize.ws_row > 0
        && winsize.ws_col > 0
    {
        (winsize.ws_row as u32, winsize.ws_col as u32)
    } else {
        (24, 80)
    }
}

fn make_raw(fd: RawFd) -> io::Result<libc::termios> {
    let mut term: libc::termios = unsafe { mem::zeroed() };
    if unsafe { libc::tcgetattr(fd, &mut term) } != 0 {
        return Err(io::Error::last_os_error());
    }
    let original = term;
    term.c_iflag &= !(libc::IGNBRK
        | libc::BRKINT
        | libc::PARMRK
        | libc::ISTRIP
        | libc::INLCR
        | libc::IGNCR
        | libc::ICRNL
        | libc::IXON);
    term.c_oflag &= !libc::OPOST;
    term.c_lflag &= !(libc::ECHO | libc::ECHONL | libc::ICANON | libc::ISIG | libc::IEXTEN);
    term.c_cflag &= !(libc::CSIZE | libc::PARENB);
    term.c_cflag |= libc::CS8;
    term.c_cc[libc::VMIN] = 1;
    term.c_cc[libc::VTIME] = 0;
    if unsafe { libc::tcsetattr(fd, libc::TCSANOW, &term) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(original)
}

fn restore_termios() {
    // Copy the saved termios out by value; avoids a reference to the static mut.
    if let Some(term) = unsafe { ORIG_TERMIOS } {
        unsafe { libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &term) };
    }
}

static WINCH_READ: AtomicI32 = AtomicI32::new(-1);

extern "C" fn on_winch(_: libc::c_int) {
    let fd = WINCH_WRITE.load(Ordering::Relaxed);
    if fd >= 0 {
        let byte = [1u8];
        unsafe { libc::write(fd, byte.as_ptr() as *const libc::c_void, 1) };
    }
}

extern "C" fn on_terminate(signal: libc::c_int) {
    restore_termios();
    unsafe { libc::_exit(128 + signal) };
}

fn install_winch() {
    let mut pipe_fds = [0i32; 2];
    if unsafe { libc::pipe2(pipe_fds.as_mut_ptr(), libc::O_NONBLOCK | libc::O_CLOEXEC) } != 0 {
        return;
    }
    WINCH_READ.store(pipe_fds[0], Ordering::Relaxed);
    WINCH_WRITE.store(pipe_fds[1], Ordering::Relaxed);
    unsafe { libc::signal(libc::SIGWINCH, on_winch as *const () as libc::sighandler_t) };
}

fn install_restore_handlers() {
    unsafe {
        libc::signal(
            libc::SIGTERM,
            on_terminate as *const () as libc::sighandler_t,
        );
        libc::signal(
            libc::SIGINT,
            on_terminate as *const () as libc::sighandler_t,
        );
        libc::signal(
            libc::SIGHUP,
            on_terminate as *const () as libc::sighandler_t,
        );
    }
}

fn ignore_sigpipe() {
    unsafe { libc::signal(libc::SIGPIPE, libc::SIG_IGN) };
}

fn drain(fd: RawFd) {
    let mut buf = [0u8; 64];
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
        if n <= 0 {
            break;
        }
    }
}
