# PROJECT KNOWLEDGE BASE

**Generated:** 2026-06-15
**Stack:** Kotlin + Jetpack Compose (app) · Kotlin core (transport/exec) · Rust + Tokio (native daemon) · Protobuf (IPC)

## STRUCTURE

```
file-manager/
├── uds/                 # :uds — proto-agnostic UDS transport (client + server, fd passing)
│   └── src/main/java/io/furryr/file/uds/
│       ├── UdsSocket.kt    # framed 4-byte-length I/O, SCM_RIGHTS via recvmsg/sendmsg
│       ├── UdsNative.kt    # native bridge: bind/listen/accept + fd helpers
│       ├── Transport.kt    # full-duplex event transport (Frame + TransportEvent)
│       ├── UdsTransport.kt # reader coroutine + write lock; disconnects are terminal
│       └── UdsClient.kt / UdsServer.kt / Frame.kt / UdsEndpoint.kt
├── core/                # :core — daemon protocol layer over :uds.Transport
│   └── src/main/java/io/furryr/file/core/
│       ├── DaemonManager.kt      # Public facade: lifecycle, launcher SPI, hub, services
│       ├── DaemonRegistry.kt     # endpoint enumeration seam
│       ├── ipc/                  # INTERNAL: DaemonConnection (proto codec + id correlation)
│       ├── hub/                  # INTERNAL: HubServer + Hub (shared run0/agent server + lifecycle)
│       ├── process/              # CommandSpec + ProcessChannel + ExecService (unified PTY/exec)
│       ├── file/                 # FileService (daemon-backed file ops)
│       ├── source/               # FileSource (fd/handle/stream)
│       └── model/                # FileEntry, ProviderStat, CopyProgress, CopyHandle, ExecResult
├── app/                 # Android app (Kotlin/Compose)
│   └── src/main/
│       ├── java/io/furryr/file/  # UI + providers + agent + app facades
│       └── jniLibs/arm64-v8a/    # Prebuilt native binaries (libfiledaemon.so, libproot.so)
├── native/              # Rust workspace: daemon + hub terminal utilities
│   ├── src/main.rs      # Accept loop + per-connection state
│   ├── src/handlers.rs  # Command dispatch (incl. CancelRequest)
│   ├── Cargo.toml
│   ├── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
│   ├── build-proot.sh            # Cross-compile proot from source
│   ├── run0/src/main.rs  # arg parsing + hub protocol + local tty bridge
│   ├── here/src/main.rs  # reveal a path in the current file-manager tab
│   ├── agent/src/main.rs # single-file terminal AI chat UI
│   └── hub-client/       # shared hub wire protocol and descriptor transport
├── proto/               # Protobuf IPC (generated into :core)
│   ├── file_daemon.proto  # daemon protocol
│   └── hub.proto          # run0 hub attach handshake
└── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
```

## LAYERING (do not break)

- `:uds` is the **proto-agnostic transport**: it only knows *frames* (opaque
  bytes + optional fds) and connection lifecycle, never `file_daemon.proto`.
  This is what lets the hub and the daemon protocol stay independent.
- `:core` is the **daemon protocol layer over `:uds.Transport`**. It must not
  import app packages (`agent`/`provider`/`ui`/`model`/…) and knows **nothing
  about proot/chroot**.
- IPC internals (`core.ipc`), protobuf, `DaemonConnection` and `core.hub` are
  `internal`/public-facade-guarded — the UI can only reach `DaemonManager`,
  `ExecService`, `FileService`, sources, models, and the
  `DaemonLauncher`/`DaemonRegistry` SPIs.
- Named daemons (containers) are launched by an app-registered `DaemonLauncher`
  (`agent/ContainerDaemonLauncher.kt`); the UI side owns that decision.

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| UI screens | `app/.../FileManagerApp.kt` | Main composable, tabs, state |
| File listing | `app/.../ui/screens/FileListScreen.kt` | LazyColumn, pull-to-refresh |
| Settings | `app/.../ui/screens/SettingsActivity.kt` | Root/shell toggles, su path → `DaemonManager` |
| File operations | `app/.../FileRepository.kt` | Provider registry facade → `:core` |
| Provider locations | `app/.../LocationsRepository.kt` | SAF/FTP/SFTP config facade |
| Daemon facade | `core/.../DaemonManager.kt` | Lifecycle, targets, launcher SPI, hub |
| UDS transport | `uds/.../UdsTransport.kt` | Frames + fd passing; `UdsClient`/`UdsServer` |
| run0 hub server | `core/.../hub/HubServer.kt` | Interprets the hub protocol, translates to daemons |
| run0 CLI | `native/run0/src/main.rs` | Rust client: hub protocol + local tty bridge |
| here CLI | `native/here/src/main.rs` | Open target descriptor + request file-manager navigation |
| agent CLI | `native/agent/src/main.rs` | Single-file terminal AI chat UI |
| Hub client transport | `native/hub-client/src/lib.rs` | Shared framing, abstract sockets and SCM_RIGHTS |
| Hub protocol | `proto/hub.proto` | run0/agent protocol (list/select/exec/resize/signal/wait/close) |
| run0 bootstrap | `core/.../hub/Hub.kt` | Installs the binary, starts the hub, env contract |
| Unified exec/PTY | `core/.../process/ExecService.kt` | `CommandSpec` → `ProcessChannel` |
| Daemon file I/O | `core/.../file/FileService.kt` | list/stat/open/copy per target |
| Container launch | `app/.../agent/ContainerDaemonLauncher.kt` | proot/chroot argv (app-owned) |
| Native daemon | `native/src/main.rs` | File ops under root/shell |
| IPC protocol | `proto/file_daemon.proto` | Request/Response messages |

## CONVENTIONS

- **No DI framework** — `DaemonManager`, `FileRepository`, `LocationsRepository`
  are singleton `object`s. Manual wiring in `provider/Providers.kt`.
- **No ViewModel** — State lives in `FileManagerApp.kt` as `remember`/`mutableStateOf`. Single-activity.
- **All daemon I/O is blocking** — Kotlin wraps in `Dispatchers.IO`; Rust uses `current_thread` tokio.
- **Proto lives in `:core`** — the `app` module must not enable the protobuf plugin.

## ANTI-PATTERNS (THIS PROJECT)

- `as any` / `@ts-ignore` / `@ts-expect-error` — type safety violations
- `unsafe { libc::... }` — allowed (Rust side for abstract UNIX sockets)
- Silent catch (`catch {}`) — only allowed for Shizuku availability checks
- UI importing `io.furryr.file.core.ipc`, `io.furryr.file.proto`, or raw
  `DaemonConnection` — always go through the `:core` public facades.

## TESTING POLICY

- Do not write, add, restore, or run unit tests for any Kotlin or Rust code in this repository.
- Verify behavior through manual testing only, including UI interaction and command-line checks.
- Do not add test-only dependencies, test source sets, or Rust `#[cfg(test)]` modules.
- Compilation and build checks are allowed; they are not substitutes for manual behavioral testing.

## COMMANDS

```bash
./gradlew assembleDebug                          # Build APK
./gradlew :uds:compileDebugKotlin                # Compile the UDS transport layer only
./gradlew :core:compileDebugKotlin               # Compile communication layer only
bash native/build-android-aarch64.sh             # Cross-compile Rust daemon
bash native/build-proot.sh                       # Cross-compile proot binary
adb install -r app/build/outputs/apk/debug/*.apk # Install
```

## NOTES

- Target SDK 28 avoids storage restrictions needed by root features.
- `DaemonManager.startHub()` runs the communication hub (bootstrap in
  [`core/hub/Hub.kt`]): an unauthenticated UDS server with a fresh random
  abstract socket name on every start that speaks
  its **own** protocol ([`proto/hub.proto`]) and translates each request onto
  the selected daemon — it never exposes the daemon's file protocol. run0 (and,
  later, agents) attach to it; the hub holds the app identity, so it
  authenticates to the daemon on the client's behalf. The `run0` Rust CLI is a
  workspace member (`native/run0/`), alongside `native/here/` and `native/agent/`.
  All three are staged under `app/src/main/executables/` and packaged through
  Java resources as `lib/<abi>/{run0,here,agent}` before APK signing. This
  bypasses AGP's JNI filename filter. The current debuggable build types allow
  Android to extract this original name into `nativeLibraryDir`; non-debuggable
  builds do not extract arbitrary executable names. App-spawned shells get
  `HUB_SOCKET` and `nativeLibraryDir` on `PATH`, so the command is `run0`.
  Kotlin runtime code knows only the generic hub contract and utility directory.
  Daemon launchers set PATH to the native utility directory plus the target's
  standard directories. Target shells retain that PATH while initializing a
  clean user environment. Clients send only
  explicit environment overrides. Container terminals initialize that clean
  environment before `login -p -f root`, preserving the normal login/MOTD
  experience; hub command execution launches the passwd shell directly.
- Native executables are packaged as **`lib*.so` under `jniLibs/`**
  (`libfiledaemon.so`, `libproot.so`) because the Android installer only
  auto-extracts `lib/<abi>/lib*.so` to `nativeLibraryDir` (debuggable apps also
  allow other safe filenames). AGP's jniLibs
  pipeline also drops any non-`.so` entry, so the `lib*.so` names are what let
  the daemon/proot binaries ship and be extracted without a custom staging step.
- Build scripts under `native/` copy the compiled binaries into `jniLibs/`.
- Daemon sockets use the **abstract namespace** with names derived from the
  public key and target id. Each daemon launch creates a fresh in-memory Ed25519
  identity ([`core/DaemonIdentity.kt`]), owned by its connection, and receives
  `--key <pub> --id <id>`. Keys never touch disk; the fresh key gives every launch
  a different socket. The manager never reattaches to an old daemon.
  Every connection must pass a nonce/signature handshake
  ([`core/DaemonAuth.kt`] ↔ `native/src/auth.rs`) — the derived name is public,
  so it is not a credential. The run0 hub keeps its random name. Ids are
  `privileged` (one elevated target: `su` when root is available, else Shizuku),
  `app`, and `container-<name>` for named daemons (the prefix avoids colliding
  with the reserved ids).
- Daemon priority: `su` (root) > `Shizuku` (shell) > direct (app UID). The
  elevated daemon is a single `privileged` target regardless of mechanism.
- **The daemon is multi-client** — it serves many concurrent connections, but
  per-connection state (cancellable in-flight requests) is *not* shared, and
  long-lived resources (handles, PTYs) are daemon-global. The daemon exits after
  10 seconds without its first successful authentication, or immediately when
  its last authenticated connection disconnects. Unauthenticated clients cannot
  keep it alive. `ExitRequest` and signals still shut down the whole daemon.
  Ordinary connection close only disconnects that client; manager reset and
  container shutdown explicitly stop the daemon. There is no transport reconnect
  or request replay. After connection loss, a later operation starts a fresh
  daemon on demand; old PTYs and handles are not restored.
- Client entry points live in `:core` (`DaemonManager.fileService/execService`).
