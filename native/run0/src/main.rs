//! run0 — elevate privileges interactively through the file-manager hub.
//!
//! It reads the hub's abstract socket name from `$HUB_SOCKET`, connects, asks
//! the hub to run a command under a chosen user (optionally inside a container),
//! and bridges the returned PTY master fd to the local terminal.

use std::collections::HashMap;
use std::env;
use std::io;
use std::mem;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::process::exit;

use prost::Message;
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;
use tokio::signal::unix::{signal, SignalKind};

use filehub::hub_request::Op as HubOp;
use filehub::{ExecRequest, HubRequest, WaitRequest};
use hub_client::{filehub, Connection};

const TIOCGWINSZ: libc::c_ulong = 0x5413;
const TIOCSWINSZ: libc::c_ulong = 0x5414;

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

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let args = parse_args();
    match run(args).await {
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

async fn run(args: Args) -> io::Result<i32> {
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

    let (control, _root) = Connection::connect_from_env().await?;
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
        pipes: false,
    };
    let request = HubRequest {
        id: 2,
        op: Some(HubOp::Exec(exec)),
    };
    let (response, master) = control.round_trip_with_fd(request, None).await?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    let master = master
        .ok_or_else(|| io::Error::other("hub returned no PTY fd (SCM_RIGHTS was dropped?)"))?;
    let pty_id = match response.result {
        Some(filehub::hub_response::Result::Exec(e)) => e.pty_id,
        _ => return Err(io::Error::other("hub returned no Exec result")),
    };

    bridge(control, master, pty_id).await
}

/// Put the terminal into raw mode and pump bytes and resizes while waiting for
/// the child, restoring the terminal before returning its exit code.
async fn bridge(control: Connection, master: OwnedFd, pty_id: u64) -> io::Result<i32> {
    if unsafe { libc::isatty(libc::STDIN_FILENO) } == 1 {
        let original = make_raw(libc::STDIN_FILENO)?;
        unsafe { ORIG_TERMIOS = Some(original) };
    }
    install_restore_handlers();
    let result = pump(&control, master, pty_id).await;
    restore_termios();
    unsafe { ORIG_TERMIOS = None };
    result
}

async fn pump(control: &Connection, master: OwnedFd, pty_id: u64) -> io::Result<i32> {
    set_nonblocking(master.as_raw_fd())?;
    let master = AsyncFd::new(master)?;
    let input = AsyncFd::new(duplicate_nonblocking(libc::STDIN_FILENO)?)?;
    let output = AsyncFd::new(duplicate_nonblocking(libc::STDOUT_FILENO)?)?;

    // Wait for the child concurrently with terminal I/O. Resize the shared
    // master locally so no extra control responses enter the wait response.
    control
        .send_frame(
            &HubRequest {
                id: 3,
                op: Some(HubOp::Wait(WaitRequest { pty_id })),
            }
            .encode_to_vec(),
            &[],
        )
        .await?;
    let mut winch = signal(SignalKind::window_change())?;
    let mut wait = Box::pin(async {
        let (response, _) = control.receive_response(3).await?;
        if !response.ok {
            return Err(io::Error::other(response.error));
        }
        match response.result {
            Some(filehub::hub_response::Result::Wait(w)) => Ok(w.exit_code),
            _ => Err(io::Error::other("hub returned no Wait result")),
        }
    });

    let mut master_buf = [0u8; 4096];
    let mut input_buf = [0u8; 4096];
    let mut pending = Vec::new();
    let mut input_open = true;
    let mut master_open = true;
    let mut code = None;

    while code.is_none() {
        tokio::select! {
            result = &mut wait => {
                code = Some(result?);
                pending.clear();
            }
            _ = winch.recv() => resize(master.as_raw_fd()),
            result = read_fd(&master, &mut master_buf), if master_open => {
                match result {
                    Ok(0) => {
                        master_open = false;
                        pending.clear();
                    }
                    Ok(n) => write_all_fd(&output, &master_buf[..n]).await?,
                    // A PTY master reports EIO once the slave side is gone.
                    Err(error) if error.raw_os_error() == Some(libc::EIO) => {
                        master_open = false;
                        pending.clear();
                    }
                    Err(error) => return Err(error),
                }
            }
            result = write_pending(&master, &mut pending), if master_open && !pending.is_empty() => {
                result?;
            }
            result = read_fd(&input, &mut input_buf), if input_open && master_open && pending.is_empty() => {
                match result? {
                    0 => {
                        input_open = false;
                        // A PTY cannot be half-closed. Supply canonical EOF for piped input.
                        // The first EOF can flush a partial line; the second ends input.
                        pending.extend_from_slice(&[4, 4]);
                    }
                    n => pending.extend_from_slice(&input_buf[..n]),
                }
            }
        }
    }

    // The daemon reaps the child in the Wait handler. Descendants may retain
    // the slave, so drain bounded queued output without waiting for their EOF.
    let mut final_output: usize = 64 * 1024;
    while master_open && final_output > 0 {
        match try_read(&master, &mut master_buf) {
            Ok(0) => master_open = false,
            Ok(n) => {
                write_all_fd(&output, &master_buf[..n]).await?;
                final_output = final_output.saturating_sub(n);
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
            Err(error) if error.raw_os_error() == Some(libc::EIO) => master_open = false,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
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

fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn duplicate_nonblocking(fd: RawFd) -> io::Result<OwnedFd> {
    let duplicated = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 3) };
    if duplicated < 0 {
        return Err(io::Error::last_os_error());
    }
    let owned = unsafe { OwnedFd::from_raw_fd(duplicated) };
    set_nonblocking(owned.as_raw_fd())?;
    Ok(owned)
}

async fn read_fd(fd: &AsyncFd<OwnedFd>, buf: &mut [u8]) -> io::Result<usize> {
    fd.async_io(Interest::READABLE, |inner| unsafe {
        read_raw(inner.as_raw_fd(), buf)
    })
    .await
}

fn try_read(fd: &AsyncFd<OwnedFd>, buf: &mut [u8]) -> io::Result<usize> {
    fd.try_io(Interest::READABLE, |inner| unsafe {
        read_raw(inner.as_raw_fd(), buf)
    })
}

async fn write_all_fd(fd: &AsyncFd<OwnedFd>, data: &[u8]) -> io::Result<()> {
    let mut data = data;
    while !data.is_empty() {
        let written = fd
            .async_io(Interest::WRITABLE, |inner| unsafe {
                write_raw(inner.as_raw_fd(), data)
            })
            .await?;
        if written == 0 {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "write returned zero bytes",
            ));
        }
        data = &data[written..];
    }
    Ok(())
}

async fn write_pending(fd: &AsyncFd<OwnedFd>, pending: &mut Vec<u8>) -> io::Result<()> {
    while !pending.is_empty() {
        match fd
            .async_io(Interest::WRITABLE, |inner| unsafe {
                write_raw(inner.as_raw_fd(), pending)
            })
            .await
        {
            Ok(0) => {
                return Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "write returned zero bytes",
                ))
            }
            Ok(written) => {
                pending.drain(..written);
            }
            Err(error) if error.raw_os_error() == Some(libc::EIO) => return Ok(()),
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

unsafe fn read_raw(fd: RawFd, buf: &mut [u8]) -> io::Result<usize> {
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        return Ok(n as usize);
    }
}

unsafe fn write_raw(fd: RawFd, data: &[u8]) -> io::Result<usize> {
    loop {
        let n = unsafe { libc::write(fd, data.as_ptr().cast(), data.len()) };
        if n < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        return Ok(n as usize);
    }
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

extern "C" fn on_terminate(signal: libc::c_int) {
    restore_termios();
    unsafe { libc::_exit(128 + signal) };
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
