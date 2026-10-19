//! Container-owned service supervision. This is a deliberately bounded service
//! unit implementation, not systemd, PID 1, socket activation, or a cgroup manager.
use crate::{
    proto::{ServiceControlRequest, ServiceControlResponse, ServiceStatus},
    reaper,
};
use std::{
    collections::{BTreeSet, HashMap, HashSet},
    fs::{self, OpenOptions},
    io::{self, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    process::Stdio,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio::{
    process::{Child, Command},
    sync::{watch, Mutex},
};

const UNIT_DIR: &str = "/etc/file-manager/services";
const ENABLE_DIR: &str = "/etc/file-manager/enabled";
const LOG_DIR: &str = "/var/log/file-manager/services";
const SEARCH: &[&str] = &[
    UNIT_DIR,
    "/etc/systemd/system",
    "/usr/lib/systemd/system",
    "/lib/systemd/system",
];
static CONTAINER: AtomicBool = AtomicBool::new(false);

pub fn bus_environment() -> HashMap<String, String> {
    if !CONTAINER.load(Ordering::Relaxed) {
        return HashMap::new();
    }
    HashMap::from([
        (
            "DBUS_SYSTEM_BUS_ADDRESS".into(),
            "unix:path=/run/dbus/system_bus_socket".into(),
        ),
        (
            "DBUS_SESSION_BUS_ADDRESS".into(),
            "unix:path=/run/user/0/bus".into(),
        ),
        ("XDG_RUNTIME_DIR".into(), "/run/user/0".into()),
    ])
}

pub fn command_environment(user: Option<&str>) -> HashMap<String, String> {
    let mut environment = bus_environment();
    if !CONTAINER.load(Ordering::Relaxed) {
        return environment;
    }
    let requested = user
        .map(str::to_owned)
        .unwrap_or_else(|| unsafe { libc::geteuid() }.to_string());
    if let Ok(passwd) = fs::read_to_string("/etc/passwd") {
        for line in passwd.lines() {
            let fields = line.split(':').collect::<Vec<_>>();
            if fields.len() >= 7 && (fields[0] == requested || fields[2] == requested) {
                environment.insert("USER".into(), fields[0].into());
                environment.insert("LOGNAME".into(), fields[0].into());
                environment.insert(
                    "HOME".into(),
                    if fields[5].is_empty() { "/" } else { fields[5] }.into(),
                );
                environment.insert(
                    "SHELL".into(),
                    if fields[6].is_empty() {
                        "/bin/sh"
                    } else {
                        fields[6]
                    }
                    .into(),
                );
                break;
            }
        }
    }
    environment
}

#[derive(Clone)]
struct Unit {
    name: String,
    description: String,
    command: Vec<String>,
    env: HashMap<String, String>,
    cwd: String,
    user: Option<String>,
    requires: Vec<String>,
    wants: Vec<String>,
    after: Vec<String>,
    kind: String,
    restart: String,
    delay: Duration,
    timeout_start: Duration,
    timeout_stop: Duration,
    remain: bool,
}
#[derive(Clone)]
struct Running {
    stop: watch::Sender<bool>,
    exit: watch::Receiver<Option<i32>>,
    generation: u64,
    unit: Unit,
}
#[derive(Default)]
struct State {
    running: HashMap<String, Running>,
    statuses: HashMap<String, ServiceStatus>,
    generation: u64,
}
pub struct ServiceManager {
    container: bool,
    state: Mutex<State>,
    actions: Mutex<()>,
}

impl ServiceManager {
    pub fn new(container: bool) -> Arc<Self> {
        CONTAINER.store(container, Ordering::Relaxed);
        Arc::new(Self {
            container,
            state: Mutex::new(State::default()),
            actions: Mutex::new(()),
        })
    }
    pub async fn boot(self: &Arc<Self>) {
        if !self.container {
            return;
        }
        if let Err(error) = prepare_runtime() {
            eprintln!("[services] runtime setup: {error}");
        }
        let names = discover()
            .into_iter()
            .filter(|name| enabled(name))
            .collect::<Vec<_>>();
        let _action = self.actions.lock().await;
        // Include all enabled units in one transaction so After orders peers
        // even when neither is a Requires dependency of the other.
        if let Err(error) = self.start_units(&names).await {
            eprintln!("[services] autostart transaction: {error}");
            for name in names {
                if let Err(error) = self.start_units(&[name.clone()]).await {
                    self.record_error(&name, &error.to_string()).await;
                    eprintln!("[services] autostart {name}: {error}");
                }
            }
        }
    }
    pub async fn shutdown(&self) {
        let _action = self.actions.lock().await;
        let running = self
            .state
            .lock()
            .await
            .running
            .values()
            .cloned()
            .collect::<Vec<_>>();
        for service in &running {
            service.stop.send_replace(true);
        }
        let wait = async {
            for service in &running {
                let mut exit = service.exit.clone();
                while exit.borrow().is_none() {
                    if exit.changed().await.is_err() {
                        break;
                    }
                }
            }
        };
        if tokio::time::timeout(Duration::from_millis(700), wait)
            .await
            .is_err()
        {
            for status in self.state.lock().await.statuses.values() {
                if status.pid > 0 {
                    unsafe {
                        libc::kill(-(status.pid as i32), libc::SIGKILL);
                    }
                }
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }
    pub async fn control(
        self: &Arc<Self>,
        request: ServiceControlRequest,
    ) -> io::Result<ServiceControlResponse> {
        if !self.container {
            return Err(io::Error::other(
                "service supervision is only available inside a container",
            ));
        }
        let _action = self.actions.lock().await;
        let names = request
            .units
            .iter()
            .map(|n| unit_name(n))
            .collect::<io::Result<Vec<_>>>()?;
        if names.iter().any(|name| name.ends_with(".target")) {
            return Err(io::Error::other(
                "only .service units can be controlled; target dependencies are logical boundaries",
            ));
        }
        let mut reply = ServiceControlResponse::default();
        match request.action.as_str() {
            "list-units" | "list-unit-files" | "status" | "is-active" | "is-enabled" => {},
            "start" | "restart" => {
                require_names(&names)?;
                if request.action == "restart" { for name in &names { self.stop_one(name).await?; } }
                self.start_units(&names).await?;
            }
            "stop" => {
                require_names(&names)?;
                for name in &names {
                    if !self.state.lock().await.running.contains_key(name) { load_unit(name)?; }
                }
                let mut stop_names = names.iter().cloned().collect::<BTreeSet<_>>();
                loop {
                    let previous = stop_names.len();
                    for (name, running) in &self.state.lock().await.running {
                        if running.unit.requires.iter().any(|dep| stop_names.contains(dep)) { stop_names.insert(name.clone()); }
                    }
                    if previous == stop_names.len() { break; }
                }
                for name in stop_names.iter().filter(|n| !names.contains(n)) { self.stop_one(name).await?; }
                for name in &names { self.stop_one(name).await?; }
            }
            "enable" | "disable" => {
                require_names(&names)?;
                fs::create_dir_all(ENABLE_DIR)?;
                for name in &names {
                    load_unit(name)?;
                    fs::write(Path::new(ENABLE_DIR).join(name), if request.action == "enable" { "enabled\n" } else { "disabled\n" })?;
                }
            }
            "daemon-reload" => { reply.text = "Unit files will be read on the next start; running processes are unchanged.\n".into(); }
            "reset-failed" => {
                self.state.lock().await.statuses.retain(|name, status| {
                    status.state != "failed" || (!names.is_empty() && !names.contains(name))
                });
            }
            "cat" => {
                require_names(&names)?;
                for name in &names { reply.text.push_str(&unit_text(name)?); reply.text.push('\n'); }
            }
            "write-unit" => {
                if names.len() != 1 { return Err(io::Error::other("write-unit requires one service name")); }
                parse_unit(&names[0], &request.content)?;
                fs::create_dir_all(UNIT_DIR)?;
                let temporary = Path::new(UNIT_DIR).join(format!(".{}.new", names[0]));
                fs::write(&temporary, request.content)?;
                fs::rename(temporary, Path::new(UNIT_DIR).join(&names[0]))?;
            }
            "logs" => {
                require_names(&names)?;
                for name in &names {
                    reply.text.push_str(&format!("== {name} ==\n"));
                    match fs::File::open(log_path(name)) {
                        Ok(mut file) => {
                            let length = file.metadata()?.len();
                            file.seek(SeekFrom::Start(length.saturating_sub(64 * 1024)))?;
                            let mut bytes = Vec::new(); file.take(64 * 1024).read_to_end(&mut bytes)?;
                            reply.text.push_str(&String::from_utf8_lossy(&bytes));
                        }
                        Err(error) if error.kind() == io::ErrorKind::NotFound => reply.text.push_str("No log output yet.\n"),
                        Err(error) => return Err(error),
                    }
                }
            }
            _ => return Err(io::Error::other("unsupported operation; use list-units/status/start/stop/restart/enable/disable/daemon-reload/cat/logs/write-unit")),
        }
        let selected = if names.is_empty() {
            discover().into_iter().collect::<Vec<_>>()
        } else {
            names
        };
        for name in selected {
            reply.services.push(self.status(&name).await);
        }
        if request.action == "is-active" && reply.services.iter().any(|s| s.state != "active") {
            reply.exit_code = 3;
        }
        if request.action == "is-enabled" && reply.services.iter().any(|s| !s.enabled) {
            reply.exit_code = 1;
        }
        if request.action == "status" && reply.services.iter().any(|s| s.state != "active") {
            reply.exit_code = 3;
        }
        Ok(reply)
    }
    async fn status(&self, name: &str) -> ServiceStatus {
        let existing = self.state.lock().await.statuses.get(name).cloned();
        let mut status = existing.unwrap_or_else(|| ServiceStatus {
            name: name.into(),
            state: "inactive".into(),
            ..Default::default()
        });
        status.enabled = enabled(name);
        match load_unit(name) {
            Ok(unit) => status.description = unit.description,
            Err(error) => {
                status.state = "load-error".into();
                status.detail = error.to_string();
            }
        }
        status
    }
    async fn record_error(&self, name: &str, error: &str) {
        self.state.lock().await.statuses.insert(
            name.into(),
            ServiceStatus {
                name: name.into(),
                state: "failed".into(),
                detail: error.into(),
                enabled: enabled(name),
                ..Default::default()
            },
        );
    }
    async fn start_units(self: &Arc<Self>, names: &[String]) -> io::Result<()> {
        let mut units = HashMap::new();
        for name in names {
            collect_units(name, &mut units, &mut HashSet::new())?;
        }
        let mut order = Vec::new();
        let mut visiting = HashSet::new();
        let mut visited = HashSet::new();
        for name in units.keys() {
            order_unit(name, &units, &mut visiting, &mut visited, &mut order)?;
        }
        for name in order {
            let unit = units.remove(&name).unwrap();
            for dependency in &unit.requires {
                if dependency.ends_with(".target") {
                    continue;
                }
                let dependency_status = self.status(dependency).await;
                if dependency_status.state != "active"
                    && dependency_status.detail != "oneshot completed"
                {
                    return Err(io::Error::other(format!(
                        "{name}: required unit {dependency} is not active"
                    )));
                }
            }
            self.start_one(unit).await?;
        }
        Ok(())
    }
    async fn start_one(self: &Arc<Self>, unit: Unit) -> io::Result<()> {
        {
            let state = self.state.lock().await;
            if state.running.contains_key(&unit.name)
                || state
                    .statuses
                    .get(&unit.name)
                    .is_some_and(|s| s.state == "active")
            {
                return Ok(());
            }
        }
        let child = match spawn_service(&unit) {
            Ok(child) => child,
            Err(error) => {
                self.record_error(&unit.name, &error.to_string()).await;
                return Err(error);
            }
        };
        let pid = child.id().unwrap();
        let group = reaper::ProcessGroup(pid);
        let (stop, stopped) = watch::channel(false);
        let (exit_tx, mut exit) = watch::channel(None);
        let generation = {
            let mut state = self.state.lock().await;
            state.generation += 1;
            let generation = state.generation;
            state.statuses.insert(
                unit.name.clone(),
                ServiceStatus {
                    name: unit.name.clone(),
                    state: if unit.kind == "oneshot" {
                        "activating"
                    } else {
                        "active"
                    }
                    .into(),
                    enabled: enabled(&unit.name),
                    pid,
                    description: unit.description.clone(),
                    ..Default::default()
                },
            );
            state.running.insert(
                unit.name.clone(),
                Running {
                    stop,
                    exit: exit.clone(),
                    generation,
                    unit: unit.clone(),
                },
            );
            generation
        };
        let manager = self.clone();
        let monitor_unit = unit.clone();
        tokio::spawn(async move {
            manager
                .monitor(monitor_unit, child, group, stopped, exit_tx, generation)
                .await;
        });
        if unit.kind == "oneshot" {
            let wait = async {
                loop {
                    if let Some(code) = *exit.borrow() {
                        return Ok::<i32, io::Error>(code);
                    }
                    exit.changed()
                        .await
                        .map_err(|_| io::Error::other("service waiter closed"))?;
                }
            };
            match tokio::time::timeout(unit.timeout_start, wait).await {
                Ok(Ok(0)) => {}
                Ok(Ok(code)) => {
                    return Err(io::Error::other(format!(
                        "{} exited with {code}",
                        unit.name
                    )))
                }
                Ok(Err(error)) => return Err(error),
                Err(_) => {
                    self.stop_one(&unit.name).await?;
                    return Err(io::Error::other("oneshot start timed out"));
                }
            }
        }
        Ok(())
    }
    async fn monitor(
        self: Arc<Self>,
        unit: Unit,
        mut child: Child,
        mut group: reaper::ProcessGroup,
        mut stopped: watch::Receiver<bool>,
        exit: watch::Sender<Option<i32>>,
        generation: u64,
    ) {
        let mut bursts = Vec::<tokio::time::Instant>::new();
        let final_code = loop {
            let pid = child.id().unwrap();
            let status = if *stopped.borrow() {
                terminate_child(&mut child, pid, unit.timeout_stop).await
            } else {
                tokio::select! { status = child.wait() => status, _ = stopped.changed() => terminate_child(&mut child, pid, unit.timeout_stop).await }
            };
            reaper::release(pid);
            // A simple service may leave descendants even after its main process exits.
            group.finish();
            use std::os::unix::process::ExitStatusExt;
            let code = status
                .map(|s| s.code().unwrap_or_else(|| -s.signal().unwrap_or(1)))
                .unwrap_or(-1);
            let restart = !*stopped.borrow()
                && (unit.restart == "always" || (unit.restart == "on-failure" && code != 0));
            {
                let mut state = self.state.lock().await;
                if let Some(status) = state.statuses.get_mut(&unit.name) {
                    status.pid = 0;
                    status.exit_code = code;
                    status.state = if restart {
                        "activating"
                    } else if *stopped.borrow() {
                        "inactive"
                    } else if code != 0 {
                        "failed"
                    } else if unit.remain {
                        "active"
                    } else {
                        "inactive"
                    }
                    .into();
                    status.detail = if code == 0 && unit.kind == "oneshot" {
                        "oneshot completed"
                    } else if restart {
                        "waiting to restart"
                    } else {
                        ""
                    }
                    .into();
                }
            }
            if !restart {
                break code;
            }
            let now = tokio::time::Instant::now();
            bursts.retain(|when| now.duration_since(*when) < Duration::from_secs(10));
            bursts.push(now);
            if bursts.len() > 5 {
                self.record_error(&unit.name, "restart rate limit: more than five exits in ten seconds; start manually to retry").await;
                break code;
            }
            tokio::select! { _ = tokio::time::sleep(unit.delay) => {}, _ = stopped.changed() => break code }
            if *stopped.borrow() {
                break code;
            }
            match spawn_service(&unit) {
                Ok(next) => {
                    let pid = next.id().unwrap();
                    group = reaper::ProcessGroup(pid);
                    child = next;
                    if let Some(status) = self.state.lock().await.statuses.get_mut(&unit.name) {
                        status.pid = pid;
                        status.state = "active".into();
                        status.detail.clear();
                    }
                }
                Err(error) => {
                    self.record_error(&unit.name, &error.to_string()).await;
                    break -1;
                }
            }
        };
        {
            let mut state = self.state.lock().await;
            if state
                .running
                .get(&unit.name)
                .is_some_and(|r| r.generation == generation)
            {
                state.running.remove(&unit.name);
            }
            // Required services disappearing stop dependants instead of silently
            // leaving them active against a failed prerequisite.
            if !unit.remain && unit.kind != "oneshot" {
                for running in state.running.values() {
                    if running.unit.requires.contains(&unit.name) {
                        running.stop.send_replace(true);
                    }
                }
            }
        }
        exit.send_replace(Some(final_code));
    }
    async fn stop_one(&self, name: &str) -> io::Result<()> {
        let running = self.state.lock().await.running.get(name).cloned();
        if let Some(running) = running {
            running.stop.send_replace(true);
            let mut exit = running.exit;
            loop {
                if exit.borrow().is_some() {
                    break;
                }
                if exit.changed().await.is_err() {
                    break;
                }
            }
        }
        if let Some(status) = self.state.lock().await.statuses.get_mut(name) {
            status.state = "inactive".into();
            status.pid = 0;
            status.detail.clear();
        }
        Ok(())
    }
}

async fn terminate_child(
    child: &mut Child,
    pid: u32,
    timeout: Duration,
) -> io::Result<std::process::ExitStatus> {
    unsafe {
        libc::kill(-(pid as i32), libc::SIGTERM);
    }
    match tokio::time::timeout(timeout, child.wait()).await {
        Ok(status) => status,
        Err(_) => {
            unsafe {
                libc::kill(-(pid as i32), libc::SIGKILL);
            }
            let _ = child.start_kill();
            child.wait().await
        }
    }
}
fn spawn_service(unit: &Unit) -> io::Result<Child> {
    fs::create_dir_all(LOG_DIR)?;
    let path = log_path(&unit.name);
    if fs::metadata(&path).is_ok_and(|m| m.len() > 8 * 1024 * 1024) {
        fs::rename(&path, path.with_extension("log.1"))?;
    }
    let mut log = OpenOptions::new().create(true).append(true).open(path)?;
    writeln!(log, "\n[file-manager] starting {}", unit.name)?;
    let mut command = Command::new(&unit.command[0]);
    command
        .args(&unit.command[1..])
        .envs(command_environment(unit.user.as_deref()))
        .envs(&unit.env)
        .current_dir(&unit.cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::from(log.try_clone()?))
        .stderr(Stdio::from(log))
        .kill_on_drop(true)
        .process_group(0);
    if let Some(user) = &unit.user {
        let (uid, gid) = service_user(user)?;
        command.uid(uid).gid(gid);
    }
    let child = command.spawn()?;
    reaper::track(child.id().unwrap());
    Ok(child)
}
fn service_user(user: &str) -> io::Result<(u32, u32)> {
    let passwd = fs::read_to_string("/etc/passwd")?;
    for line in passwd.lines() {
        let fields = line.split(':').collect::<Vec<_>>();
        if fields.len() >= 4 && (fields[0] == user || fields[2] == user) {
            return Ok((
                fields[2]
                    .parse()
                    .map_err(|_| io::Error::other("invalid passwd uid"))?,
                fields[3]
                    .parse()
                    .map_err(|_| io::Error::other("invalid passwd gid"))?,
            ));
        }
    }
    Err(io::Error::other(format!("unknown service user {user}")))
}
fn prepare_runtime() -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    for directory in [UNIT_DIR, ENABLE_DIR, LOG_DIR, "/run/dbus", "/run/user/0"] {
        fs::create_dir_all(directory)?;
    }
    fs::set_permissions("/run/user/0", fs::Permissions::from_mode(0o700))?;
    if !Path::new("/etc/machine-id").exists() || fs::metadata("/etc/machine-id")?.len() == 0 {
        let mut bytes = [0u8; 16];
        getrandom::fill(&mut bytes).map_err(|e| io::Error::other(e.to_string()))?;
        fs::write(
            "/etc/machine-id",
            format!(
                "{}\n",
                bytes.iter().map(|b| format!("{b:02x}")).collect::<String>()
            ),
        )?;
    }
    Ok(())
}
fn log_path(name: &str) -> PathBuf {
    Path::new(LOG_DIR).join(format!("{name}.log"))
}
fn require_names(names: &[String]) -> io::Result<()> {
    if names.is_empty() {
        Err(io::Error::other("at least one service name is required"))
    } else {
        Ok(())
    }
}
fn unit_name(name: &str) -> io::Result<String> {
    if name.is_empty()
        || name.len() > 128
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_.@".contains(&b))
        || name.starts_with('.')
    {
        return Err(io::Error::other("invalid service name"));
    }
    if name.ends_with(".target") {
        return Ok(name.into());
    }
    Ok(if name.ends_with(".service") {
        name.into()
    } else {
        format!("{name}.service")
    })
}
fn enabled(name: &str) -> bool {
    match fs::read_to_string(Path::new(ENABLE_DIR).join(name)) {
        Ok(value) => value.trim() == "enabled",
        Err(_) => {
            matches!(name, "dbus.service" | "dbus-session.service") && dbus_binary().is_some()
        }
    }
}
fn discover() -> BTreeSet<String> {
    let mut names = BTreeSet::from(["dbus.service".into(), "dbus-session.service".into()]);
    for directory in SEARCH.iter().copied().chain([ENABLE_DIR]) {
        if let Ok(entries) = fs::read_dir(directory) {
            for entry in entries.flatten() {
                if let Some(name) = entry.file_name().to_str() {
                    if name.ends_with(".service") && unit_name(name).is_ok() {
                        names.insert(name.into());
                    }
                }
            }
        }
    }
    names
}
fn dbus_binary() -> Option<&'static str> {
    ["/usr/bin/dbus-daemon", "/bin/dbus-daemon"]
        .into_iter()
        .find(|p| Path::new(p).is_file())
}
fn unit_text(name: &str) -> io::Result<String> {
    let custom = Path::new(UNIT_DIR).join(name);
    if custom.is_file() {
        return fs::read_to_string(custom);
    }
    if matches!(name, "dbus.service" | "dbus-session.service") {
        let binary = dbus_binary()
            .ok_or_else(|| io::Error::other("dbus-daemon is not installed in this container"))?;
        let (mode, address) = if name == "dbus.service" {
            ("--system", "/run/dbus/system_bus_socket")
        } else {
            ("--session", "/run/user/0/bus")
        };
        return Ok(format!("[Unit]\nDescription=Container D-Bus {mode}\n[Service]\nType=simple\nExecStart={binary} {mode} --nofork --nopidfile --address=unix:path={address}\nRestart=on-failure\nRestartSec=2\n"));
    }
    for directory in SEARCH {
        let path = Path::new(directory).join(name);
        if path.is_file() {
            return fs::read_to_string(path);
        }
    }
    Err(io::Error::new(
        io::ErrorKind::NotFound,
        format!("unit {name} was not found"),
    ))
}
fn load_unit(name: &str) -> io::Result<Unit> {
    parse_unit(name, &unit_text(name)?)
}
fn parse_unit(name: &str, content: &str) -> io::Result<Unit> {
    if content.len() > 128 * 1024 {
        return Err(io::Error::other("unit file exceeds 128 KiB"));
    }
    let mut unit = Unit {
        name: name.into(),
        description: name.into(),
        command: Vec::new(),
        env: HashMap::new(),
        cwd: "/".into(),
        user: None,
        requires: Vec::new(),
        wants: Vec::new(),
        after: Vec::new(),
        kind: "simple".into(),
        restart: "no".into(),
        delay: Duration::from_secs(1),
        timeout_start: Duration::from_secs(90),
        timeout_stop: Duration::from_secs(10),
        remain: false,
    };
    let mut section = "";
    for raw in content.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        if line.starts_with('[') && line.ends_with(']') {
            section = &line[1..line.len() - 1];
            continue;
        }
        let (key, value) = line
            .split_once('=')
            .ok_or_else(|| io::Error::other("unit entries must be key=value"))?;
        let value = value.trim();
        match (section, key.trim()) {
            ("Unit", "Description") => unit.description = value.into(),
            ("Unit", "After") => unit.after = dependencies(value)?,
            ("Unit", "Requires") => unit.requires = dependencies(value)?,
            ("Unit", "Wants") => unit.wants = dependencies(value)?,
            ("Unit", "Documentation") | ("Install", "WantedBy") | ("Install", "RequiredBy") => {},
            ("Service", "Type") => unit.kind = value.into(),
            ("Service", "ExecStart") => { if !unit.command.is_empty() { return Err(io::Error::other("multiple ExecStart entries are unsupported")); } unit.command = words(value)?; },
            ("Service", "WorkingDirectory") => unit.cwd = value.into(),
            ("Service", "User") => unit.user = Some(value.into()),
            ("Service", "Environment") => { for entry in words(value)? { let (key, val) = entry.split_once('=').ok_or_else(|| io::Error::other("Environment requires NAME=value"))?; unit.env.insert(key.into(), val.into()); } },
            ("Service", "Restart") => unit.restart = value.into(),
            ("Service", "RestartSec") => unit.delay = seconds(value)?.max(Duration::from_millis(100)),
            ("Service", "TimeoutStartSec") => unit.timeout_start = seconds(value)?,
            ("Service", "TimeoutStopSec") => unit.timeout_stop = seconds(value)?,
            ("Service", "RemainAfterExit") => unit.remain = matches!(value, "yes" | "true" | "1"),
            _ => return Err(io::Error::other(format!("unsupported unit directive [{section}] {key}; this manager implements a service subset"))),
        }
    }
    if !matches!(unit.kind.as_str(), "simple" | "exec" | "oneshot") {
        return Err(io::Error::other(
            "supported Type values are simple, exec and oneshot",
        ));
    }
    if !matches!(unit.restart.as_str(), "no" | "always" | "on-failure") {
        return Err(io::Error::other(
            "supported Restart values are no, always and on-failure",
        ));
    }
    if unit.kind == "oneshot" && unit.restart != "no" {
        return Err(io::Error::other("oneshot services require Restart=no"));
    }
    if unit.command.is_empty() || !unit.command[0].starts_with('/') {
        return Err(io::Error::other(
            "ExecStart must name an absolute executable",
        ));
    }
    if !unit.cwd.starts_with('/') {
        return Err(io::Error::other("WorkingDirectory must be absolute"));
    }
    Ok(unit)
}
fn seconds(value: &str) -> io::Result<Duration> {
    let (value, scale) = if let Some(v) = value.strip_suffix("ms") {
        (v, 0.001)
    } else {
        (value.strip_suffix('s').unwrap_or(value), 1.0)
    };
    let seconds = value
        .parse::<f64>()
        .map_err(|_| io::Error::other("duration must be seconds or milliseconds"))?
        * scale;
    if !seconds.is_finite() || !(0.0..=86400.0).contains(&seconds) {
        return Err(io::Error::other("duration is out of range"));
    }
    Ok(Duration::from_secs_f64(seconds))
}
fn dependencies(value: &str) -> io::Result<Vec<String>> {
    value.split_whitespace().map(unit_name).collect()
}
fn words(value: &str) -> io::Result<Vec<String>> {
    let mut result = Vec::new();
    let mut word = String::new();
    let mut quote = None;
    let mut escaped = false;
    let mut started = false;
    for c in value.chars() {
        if escaped {
            word.push(c);
            escaped = false;
            started = true;
            continue;
        }
        if c == '\\' && quote != Some('\'') {
            escaped = true;
            continue;
        }
        if let Some(q) = quote {
            if c == q {
                quote = None;
            } else {
                word.push(c);
            }
            started = true;
        } else if c == '\'' || c == '"' {
            quote = Some(c);
            started = true;
        } else if c.is_whitespace() {
            if started {
                result.push(std::mem::take(&mut word));
                started = false;
            }
        } else {
            word.push(c);
            started = true;
        }
    }
    if escaped || quote.is_some() {
        return Err(io::Error::other("unterminated quoting in unit command"));
    }
    if started {
        result.push(word);
    }
    Ok(result)
}
fn collect_units(
    name: &str,
    units: &mut HashMap<String, Unit>,
    visiting: &mut HashSet<String>,
) -> io::Result<()> {
    if name.ends_with(".target") || units.contains_key(name) {
        return Ok(());
    }
    if !visiting.insert(name.into()) {
        return Err(io::Error::other("Requires dependency cycle"));
    }
    let result = (|| {
        let unit = load_unit(name)?;
        for dependency in &unit.requires {
            collect_units(dependency, units, visiting)?;
        }
        for dependency in &unit.wants {
            if let Err(error) = collect_units(dependency, units, visiting) {
                eprintln!("[services] optional {dependency}: {error}");
            }
        }
        units.insert(name.into(), unit);
        Ok(())
    })();
    visiting.remove(name);
    result
}
fn order_unit(
    name: &str,
    units: &HashMap<String, Unit>,
    visiting: &mut HashSet<String>,
    visited: &mut HashSet<String>,
    order: &mut Vec<String>,
) -> io::Result<()> {
    if visited.contains(name) {
        return Ok(());
    }
    if !visiting.insert(name.into()) {
        return Err(io::Error::other("After/Requires ordering cycle"));
    }
    let unit = &units[name];
    for dependency in unit.requires.iter().chain(&unit.after) {
        if units.contains_key(dependency) {
            order_unit(dependency, units, visiting, visited, order)?;
        }
    }
    visiting.remove(name);
    visited.insert(name.into());
    order.push(name.into());
    Ok(())
}
