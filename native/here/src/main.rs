//! Reveal a path in the app, preserving the caller's filesystem identity.
use std::env;
use std::fs::OpenOptions;
use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::PathBuf;

use hub_client::filehub::{hub_request::Op, HereRequest, HubRequest};

const USAGE: &str = "Usage: here [--] FILE_OR_DIRECTORY\n\nReveal and select a path in the file manager's current tab.";

fn main() {
    if let Err(error) = run() {
        eprintln!("here: {error}");
        std::process::exit(2);
    }
}

fn run() -> io::Result<()> {
    let mut args = env::args_os().skip(1);
    let first = args.next().ok_or_else(|| io::Error::other(USAGE))?;
    if first == "--help" || first == "-h" {
        println!("{USAGE}");
        return Ok(());
    }
    let path = if first == "--" {
        args.next().ok_or_else(|| io::Error::other(USAGE))?
    } else {
        first
    };
    if args.next().is_some() {
        return Err(io::Error::other(USAGE));
    }
    let path = PathBuf::from(path);
    let path = if path.is_absolute() {
        path
    } else {
        env::current_dir()?.join(path)
    };
    // Do not canonicalize: the selected entry may itself be a (broken) symlink.
    // SCM_RIGHTS lets the hub resolve this descriptor outside proot/chroot.
    let target = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_PATH | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(path)?;
    let socket = hub_client::connect_from_env()?;
    let (response, _) = hub_client::round_trip_with_attached_fd(
        socket.as_raw_fd(),
        HubRequest {
            id: 1,
            op: Some(Op::Here(HereRequest {})),
        },
        Some(target.as_raw_fd()),
    )?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    Ok(())
}
