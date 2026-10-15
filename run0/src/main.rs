//! run0 — elevate privileges interactively through the file-manager hub.
//!
//! It reads the hub's abstract socket name from `$RUN0_SOCKET`, connects, asks
//! the hub to run a command under a chosen user (optionally inside a container),
//! and bridges the returned PTY master fd to the local terminal.

use std::collections::HashMap;
use std::env;
use std::io;
use std::mem;
use std::os::fd::RawFd;
use std::process::exit;
use std::sync::atomic::{AtomicI32, Ordering};
use std::thread;

use prost::Message;

mod filehub {
    include!(concat!(env!("OUT_DIR"), "/filehub.rs"));
}
use filehub::hub_request::Op as HubOp;
use filehub::{ExecRequest, HubRequest, HubResponse, WaitRequest};

const TIOCGWINSZ: libc::c_ulong = 0x5413;
const TIOCSWINSZ: libc::c_ulong = 0x5414;

static WINCH_WRITE: AtomicI32 = AtomicI32::new(-1);
static mut ORIG_TERMIOS: Option<libc::termios> = None;

const USAGE: &str = "\
run0 [OPTIONS...] COMMAND [ARGUMENTS...]

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
    if let Err(error) = run(args) {
        eprintln!("run0: {error}");
        exit(2);
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

fn run(args: Args) -> io::Result<()> {
    let socket_name = env::var("RUN0_SOCKET").map_err(|_| {
        io::Error::new(
            io::ErrorKind::NotFound,
            "$RUN0_SOCKET is not set; invoke run0 from an app-spawned terminal",
        )
    })?;

    let (rows, cols) = tty_size(libc::STDIN_FILENO);
    let term = env::var("TERM").unwrap_or_else(|_| "xterm-256color".to_string());

    // Command: no COMMAND means a login shell; --via-shell wraps an explicit
    // command in the login shell; otherwise exec it directly.
    let (command, command_args) = if args.command.is_empty() {
        ("/system/bin/sh".to_string(), vec!["-l".to_string()])
    } else if args.via_shell {
        (
            "/system/bin/sh".to_string(),
            vec!["-l".to_string(), "-c".to_string(), args.command.join(" ")],
        )
    } else {
        (args.command[0].clone(), args.command[1..].to_vec())
    };

    let mut env_map = HashMap::new();
    for (name, value) in args.setenv {
        env_map.insert(name, value);
    }

    let fd = connect_abstract(&socket_name)?;
    ignore_sigpipe();

    if let Some(machine) = args.machine.as_deref() {
        let request = HubRequest {
            id: 1,
            op: Some(HubOp::SelectTarget(filehub::SelectTargetRequest {
                target: machine.to_string(),
            })),
        };
        let response = round_trip(fd, request)?;
        if !response.ok {
            return Err(io::Error::other(format!(
                "cannot select '{}': {}",
                machine,
                response.error
            )));
        }
    }

    let exec = ExecRequest {
        command,
        args: command_args,
        env: env_map,
        cwd: args.chdir.unwrap_or_default(),
        user: args.user,
        term,
        rows,
        cols,
        nice: args.nice,
    };
    let request = HubRequest {
        id: 2,
        op: Some(HubOp::Exec(exec)),
    };
    let (response, master) = round_trip_with_fd(fd, request)?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    let master = master.ok_or_else(|| {
        io::Error::other("hub returned no PTY fd (SCM_RIGHTS was dropped?)")
    })?;
    let pty_id = match response.result {
        Some(filehub::hub_response::Result::Exec(e)) => e.pty_id,
        _ => 0,
    };

    let code = bridge(fd, master, pty_id)?;
    exit(code);
}

/// Put the terminal into raw mode, pump bytes both ways and forward resizes,
/// then wait for the child and return its exit code.
fn bridge(control_fd: RawFd, master: RawFd, pty_id: u64) -> io::Result<i32> {
    let original = make_raw(libc::STDIN_FILENO)?;
    unsafe { ORIG_TERMIOS = Some(original) };
    install_winch();
    install_restore_handlers();

    // stdin -> daemon PTY (own thread, so a full input buffer can never stall
    // the output reader).
    thread::spawn(move || {
        let mut buf = [0u8; 4096];
        loop {
            let n = unsafe {
                libc::read(libc::STDIN_FILENO, buf.as_mut_ptr() as *mut libc::c_void, buf.len())
            };
            if n <= 0 {
                break;
            }
            if write_all(master, &buf[..n as usize]).is_err() {
                break;
            }
        }
    });

    let winch_read = WINCH_READ.load(Ordering::Relaxed);
    let mut buf = [0u8; 4096];
    loop {
        let mut pollfds = [
            libc::pollfd { fd: master, events: libc::POLLIN, revents: 0 },
            libc::pollfd { fd: winch_read, events: libc::POLLIN, revents: 0 },
        ];
        let ready = unsafe { libc::poll(pollfds.as_mut_ptr(), 2, -1) };
        if ready < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            break;
        }

        if pollfds[1].revents & libc::POLLIN != 0 {
            drain(winch_read);
            resize(control_fd, master, pty_id);
        }

        if pollfds[0].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
            let n = unsafe { libc::read(master, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
            if n > 0 {
                let _ = write_all(libc::STDOUT_FILENO, &buf[..n as usize]);
            } else {
                // 0 or EIO: the slave side is gone, the child has finished.
                break;
            }
        }
    }

    restore_termios();
    wait_child(control_fd, pty_id)
}

fn wait_child(control_fd: RawFd, pty_id: u64) -> io::Result<i32> {
    let request = HubRequest {
        id: 3,
        op: Some(HubOp::Wait(WaitRequest { pty_id })),
    };
    let response = round_trip(control_fd, request)?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    Ok(match response.result {
        Some(filehub::hub_response::Result::Wait(w)) => w.exit_code,
        _ => 0,
    })
}

fn resize(control_fd: RawFd, master: RawFd, pty_id: u64) {
    let (rows, cols) = tty_size(libc::STDIN_FILENO);
    let winsize = libc::winsize {
        ws_row: rows as u16,
        ws_col: cols as u16,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    // Set locally on the master; the kernel signals the child's process group.
    unsafe { libc::ioctl(master, TIOCSWINSZ as _, &winsize) };
    // Also tell the hub so the daemon's view stays in sync.
    let request = HubRequest {
        id: 0,
        op: Some(HubOp::Resize(filehub::ResizeRequest { pty_id, rows, cols })),
    };
    let _ = send_frame(control_fd, &request.encode_to_vec());
    let _ = recv_frame(control_fd);
}

// ── Hub protocol I/O ─────────────────────────────────────────────────

fn round_trip(fd: RawFd, request: HubRequest) -> io::Result<HubResponse> {
    let (response, attached) = round_trip_with_fd(fd, request)?;
    if let Some(extra) = attached {
        unsafe { libc::close(extra) };
    }
    Ok(response)
}

fn round_trip_with_fd(fd: RawFd, request: HubRequest) -> io::Result<(HubResponse, Option<RawFd>)> {
    send_frame(fd, &request.encode_to_vec())?;
    let (payload, attached) = recv_frame(fd)?;
    let response =
        HubResponse::decode(payload.as_slice()).map_err(|e| io::Error::other(e.to_string()))?;
    Ok((response, attached))
}

fn send_frame(fd: RawFd, payload: &[u8]) -> io::Result<()> {
    let mut frame = Vec::with_capacity(4 + payload.len());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    frame.extend_from_slice(payload);
    write_all(fd, &frame)
}

fn write_all(fd: RawFd, data: &[u8]) -> io::Result<()> {
    let mut sent = 0;
    while sent < data.len() {
        let n = unsafe {
            libc::send(
                fd,
                data[sent..].as_ptr() as *const libc::c_void,
                data.len() - sent,
                libc::MSG_NOSIGNAL,
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
            return Err(io::Error::new(io::ErrorKind::WriteZero, "send wrote zero bytes"));
        }
        sent += n as usize;
    }
    Ok(())
}

fn recv_frame(fd: RawFd) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let mut attached = None;
    let header = recv_exact(fd, 4, &mut attached)?;
    let size = u32::from_be_bytes([header[0], header[1], header[2], header[3]]) as usize;
    let payload = recv_exact(fd, size, &mut attached)?;
    Ok((payload, attached))
}

fn recv_exact(fd: RawFd, len: usize, attached: &mut Option<RawFd>) -> io::Result<Vec<u8>> {
    let mut buf = vec![0u8; len];
    let mut got = 0;
    while got < len {
        let n = recv_some(fd, &mut buf[got..], attached)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "hub closed connection"));
        }
        got += n;
    }
    Ok(buf)
}

fn recv_some(fd: RawFd, buf: &mut [u8], attached: &mut Option<RawFd>) -> io::Result<usize> {
    let mut iov = libc::iovec {
        iov_base: buf.as_mut_ptr() as *mut libc::c_void,
        iov_len: buf.len(),
    };
    let control_len = unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) as usize };
    let mut control = vec![0u8; control_len];
    let mut msg: libc::msghdr = unsafe { mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = control_len as _;

    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }

    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    while !cmsg.is_null() {
        let header = unsafe { &*cmsg };
        if header.cmsg_level == libc::SOL_SOCKET && header.cmsg_type == libc::SCM_RIGHTS {
            let data_len =
                (header.cmsg_len as usize).saturating_sub(unsafe { libc::CMSG_LEN(0) as usize });
            let count = data_len / mem::size_of::<RawFd>();
            let data = unsafe { libc::CMSG_DATA(cmsg) as *const RawFd };
            for i in 0..count {
                let received = unsafe { *data.add(i) };
                if received < 0 {
                    continue;
                }
                if attached.is_none() {
                    *attached = Some(received);
                } else {
                    unsafe { libc::close(received) };
                }
            }
        }
        cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
    }
    Ok(n as usize)
}

// ── Socket ───────────────────────────────────────────────────────────

fn connect_abstract(name: &str) -> io::Result<RawFd> {
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let bytes = name.as_bytes();
    if bytes.len() > 107 {
        unsafe { libc::close(fd) };
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "hub socket name too long"));
    }
    let mut addr: libc::sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (index, byte) in bytes.iter().enumerate() {
        addr.sun_path[index + 1] = *byte as libc::c_char;
    }
    let length = (mem::size_of::<libc::sa_family_t>() + 1 + bytes.len()) as libc::socklen_t;
    let result = unsafe {
        libc::connect(fd, &addr as *const _ as *const libc::sockaddr, length)
    };
    if result != 0 {
        let error = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(error);
    }
    Ok(fd)
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
    if unsafe { libc::pipe(pipe_fds.as_mut_ptr()) } != 0 {
        return;
    }
    for fd in pipe_fds {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
    }
    WINCH_READ.store(pipe_fds[0], Ordering::Relaxed);
    WINCH_WRITE.store(pipe_fds[1], Ordering::Relaxed);
    unsafe { libc::signal(libc::SIGWINCH, on_winch as *const () as libc::sighandler_t) };
}

fn install_restore_handlers() {
    unsafe {
        libc::signal(libc::SIGTERM, on_terminate as *const () as libc::sighandler_t);
        libc::signal(libc::SIGINT, on_terminate as *const () as libc::sighandler_t);
        libc::signal(libc::SIGHUP, on_terminate as *const () as libc::sighandler_t);
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
