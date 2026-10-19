# Container services and process pipes

The container daemon supervises services independently of terminal sessions and
the activity UI. The Android container foreground service retains the authenticated
daemon connection and displays a separate notification. Closing a terminal does
not stop services. Stopping a container, deleting/renaming it, or choosing **Stop
all containers** stops its processes. Android force-stop still stops the app;
this does not claim to bypass Android lifecycle restrictions.

## Service command

The packaged `systemctl` executable retains that name in the APK `lib/arm64-v8a/`
directory. The app puts its utility directory first on the container PATH. It
discovers the caller's container through the existing Hub hello/root descriptor
handshake and sends service operations over the random `HUB_SOCKET`. An Android
host shell can select a container with `systemctl --machine NAME ...`.

```sh
systemctl list-units
systemctl enable --now example.service
systemctl status example.service
systemctl restart example.service
systemctl logs example.service
systemctl disable --now example.service
```

Supported commands: `list-units`, `list-unit-files`, `status`, `start`, `stop`,
`restart`, `enable`, `disable`, `is-active`, `is-enabled`, `daemon-reload`,
`reset-failed`, `cat`, and `logs`. `--now` applies to enable/disable; `--quiet`
suppresses output. `is-active` returns 3 for an inactive unit; `is-enabled`
returns 1 for a disabled unit. The app provides an editor and equivalent actions
under **Container services**. Unit edits are validated before being saved.

Custom units are saved at `/etc/file-manager/services/NAME.service`; the manager
also reads `/etc/systemd/system`, `/usr/lib/systemd/system`, and
`/lib/systemd/system`. Enable/disable overrides are durable files under
`/etc/file-manager/enabled/`. Enabled units start every time that container daemon
starts. Merely modifying a running unit does not restart its process; `restart`
reads the new definition. `daemon-reload` explicitly acknowledges this behavior.

```ini
[Unit]
Description=Example worker
After=dbus.service
Requires=dbus.service

[Service]
Type=simple
ExecStart=/usr/bin/my-worker --foreground
WorkingDirectory=/root
Environment="MODE=development" "GREETING=hello world"
Restart=on-failure
RestartSec=3
TimeoutStopSec=10

[Install]
WantedBy=multi-user.target
```

This is a **service-unit subset, not systemd or PID 1**:

- `Type=simple`, `exec`, and `oneshot`; one absolute `ExecStart`. Commands are
  split into arguments with quoting/backslash handling and executed directly.
  Shell operators, variable expansion, specifiers, and multiple ExecStart lines
  are not interpreted; use an explicit `/bin/sh -c '...'` when needed.
- `WorkingDirectory`, `Environment`, and a `User` present in `/etc/passwd`.
- `Requires` starts mandatory services; failing prerequisites prevent dependant
  startup. Stopping a required service also stops its running dependants.
  `Wants` attempts optional prerequisites. `After` orders units that are part of
  the same start/autostart transaction. Cycles are rejected. `.target`
  dependencies are logical boundaries only, not independently managed targets.
- `Restart=no`, `on-failure`, and `always`, plus `RestartSec`. More than five exits
  in ten seconds marks the unit failed; an explicit start retries it. Oneshot
  services require `Restart=no` and support `RemainAfterExit`.
- `TimeoutStartSec` applies to oneshot completion. `TimeoutStopSec` bounds the
  TERM grace period before the service process group receives KILL. Durations
  accept seconds or `ms`/`s` suffixes. Complete container shutdown uses a shorter
  bounded grace period so the app's daemon teardown cannot abandon children.
- Unknown directives and unsupported types (`forking`, `notify`, `dbus`, etc.)
  are rejected rather than silently pretending to implement their semantics.
  There is no cgroup isolation, socket activation, systemd D-Bus API, journald,
  per-user manager, resource controller, or readiness notification protocol.

The main service process and descendants in its process group are stopped
together. Programs should stay in the foreground; daemonizing into a new session
is unsupported. stdout and stderr append to
`/var/log/file-manager/services/NAME.service.log`. `logs` displays the last 64 KiB.
A log larger than 8 MiB is rotated to `.log.1` at the next start. A continuously
running service can grow its log until that next start.

## Actual D-Bus processes

When `/usr/bin/dbus-daemon` or `/bin/dbus-daemon` exists, `dbus.service` and
`dbus-session.service` are enabled by default. They launch real foreground
`dbus-daemon --system` and `--session` processes, respectively, using the
container's installed D-Bus configuration. Missing executables/configuration
produce service errors and logs; the manager does not emulate D-Bus or install
packages automatically. The UI can disable either service or save a custom
definition overriding it.

The daemon prepares `/etc/machine-id` if absent and exports these defaults to
terminal commands, services, and pipe processes:

```text
DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket
DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/0/bus
XDG_RUNTIME_DIR=/run/user/0
```

Explicit command environment overrides take precedence. The session bus is the
container's shared root session bus; it is not a full per-login user bus manager.

## Subreaper and pipes

Container daemons call `PR_SET_CHILD_SUBREAPER`. A sweep reads their direct child
list and invokes `waitpid(PID, WNOHANG)` **only for untracked adopted children**.
PTY watchers and Tokio own the exit status of each registered child. PTY waits
are asynchronous per-PID polling rather than an unbounded blocking worker.

`ExecService.spawnPipe(CommandSpec)` supplies a `PipeProcessChannel` with separate
`input` (stdout), `error` (stderr), and `output` (stdin) streams. It does not create
a PTY, echo stdin, or translate newlines. Bounded protobuf read/write requests
allow stdout, stderr, and stdin to proceed concurrently on the same transport.
Closing stdin sends EOF; closing the channel cancels pending reads/writes and
terminates the process group (TERM, then KILL after 700 ms). Connection loss
closes all its pipe processes. Handles cannot be reattached or replayed after
daemon loss. This interface is used for language servers and owned desktop
sessions whose byte streams must remain unchanged.
