//! systemctl-compatible command subset for file-manager supervised containers.
use hub_client::filehub::{self, hub_request::Op, hub_response, HubRequest, ServiceControlRequest};
use std::{env, io, mem, os::fd::AsRawFd};

fn main() {
    match run() {
        Ok(code) => std::process::exit(code),
        Err(error) => {
            eprintln!("systemctl: {error}");
            std::process::exit(1);
        }
    }
}
fn run() -> io::Result<i32> {
    let mut args = env::args().skip(1);
    let mut machine = None;
    let mut now = false;
    let mut quiet = false;
    let mut positional = Vec::new();
    while let Some(argument) = args.next() {
        match argument.as_str() {
            "-h" | "--help" => {
                println!("Usage: systemctl [--machine NAME] [--now] COMMAND [SERVICE...]\n\nCommands: list-units, list-unit-files, status, start, stop, restart, enable,\n          disable, is-active, is-enabled, daemon-reload, reset-failed, cat, logs\n\nFile-manager service subset: simple/exec/oneshot; After/Requires/Wants;\nRestart=no/on-failure/always. This is not the systemd PID 1 manager.\nUnit files: /etc/file-manager/services/*.service\nLogs: /var/log/file-manager/services/*.log");
                return Ok(0);
            }
            "-M" | "--machine" => machine = Some(args.next().ok_or_else(|| io::Error::other("--machine needs a container name"))?),
            "--now" => now = true,
            "--quiet" | "-q" => quiet = true,
            "--no-pager" | "--plain" | "--system" | "--all" => {},
            "--user" => return Err(io::Error::other("per-user systemd managers are unsupported; the session bus is dbus-session.service")),
            value if value.starts_with("--machine=") => machine = Some(value[10..].into()),
            value if value.starts_with('-') => return Err(io::Error::other(format!("unsupported option {value}"))),
            _ => positional.push(argument),
        }
    }
    let action = positional
        .first()
        .cloned()
        .unwrap_or_else(|| "list-units".into());
    let units = positional.into_iter().skip(1).collect::<Vec<_>>();
    let (socket, root) = hub_client::connect_from_env()?;
    let fd = socket.as_raw_fd();
    let machine = machine
        .or_else(|| (!root.container_name.is_empty()).then_some(root.container_name))
        .ok_or_else(|| io::Error::other("run inside a container or use --machine NAME"))?;
    // Service stop/oneshot timeouts are controlled by their unit files.
    let timeout = libc::timeval {
        tv_sec: 0,
        tv_usec: 0,
    };
    if unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            (&timeout as *const libc::timeval).cast(),
            mem::size_of_val(&timeout) as _,
        )
    } != 0
    {
        return Err(io::Error::last_os_error());
    }
    let selected = hub_client::round_trip(
        fd,
        HubRequest {
            id: 1,
            op: Some(Op::SelectTarget(filehub::SelectTargetRequest {
                target: machine,
            })),
        },
    )?;
    if !selected.ok {
        return Err(io::Error::other(selected.error));
    }
    let mut response = request(fd, 2, &action, &units)?;
    if now && matches!(action.as_str(), "enable" | "disable") {
        response = request(
            fd,
            3,
            if action == "enable" { "start" } else { "stop" },
            &units,
        )?;
    }
    if !quiet {
        if !response.text.is_empty() {
            print!("{}", response.text);
        }
        if !matches!(action.as_str(), "cat" | "logs" | "daemon-reload") {
            for service in &response.services {
                if action == "is-active" {
                    println!("{}", service.state);
                } else if action == "is-enabled" {
                    println!(
                        "{}",
                        if service.enabled {
                            "enabled"
                        } else {
                            "disabled"
                        }
                    );
                } else {
                    println!(
                        "{}\t{}\t{}\tpid={}\t{}",
                        service.name,
                        service.state,
                        if service.enabled {
                            "enabled"
                        } else {
                            "disabled"
                        },
                        service.pid,
                        service.description
                    );
                    if !service.detail.is_empty() {
                        println!("  {}", service.detail);
                    }
                }
            }
        }
    }
    Ok(response.exit_code)
}
fn request(
    fd: i32,
    id: u32,
    action: &str,
    units: &[String],
) -> io::Result<filehub::ServiceControlResponse> {
    let response = hub_client::round_trip(
        fd,
        HubRequest {
            id,
            op: Some(Op::ServiceControl(ServiceControlRequest {
                action: action.into(),
                units: units.to_vec(),
                content: String::new(),
            })),
        },
    )?;
    if !response.ok {
        return Err(io::Error::other(response.error));
    }
    match response.result {
        Some(hub_response::Result::ServiceControl(result)) => Ok(result),
        _ => Err(io::Error::other("hub returned no service response")),
    }
}
