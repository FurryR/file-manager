//! Reveal a path in the app, preserving the caller's filesystem identity.
use std::env;
use std::io;
use std::path::PathBuf;

use hub_client::filehub::{hub_request::Op, HereRequest, HubRequest};

const USAGE: &str = "Usage: here [--] FILE_OR_DIRECTORY\n\nReveal and select a path in the file manager's current tab.";

#[tokio::main(flavor = "current_thread")]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("here: {error}");
        std::process::exit(2);
    }
}

async fn run() -> io::Result<()> {
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
    // Do not canonicalize: the selected entry may itself be a (broken) symlink.
    let path = PathBuf::from(path);
    let path = if path.is_absolute() {
        path
    } else {
        env::current_dir()?.join(path)
    };
    let path = path.to_string_lossy().into_owned();
    let (connection, _root) = hub_client::Connection::connect_from_env().await?;
    let response = connection
        .round_trip(HubRequest {
            id: 1,
            op: Some(Op::Here(HereRequest { path })),
        })
        .await?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    Ok(())
}
