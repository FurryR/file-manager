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
├── core/                # :core — pure VFS abstraction (Kotlin/JVM, no Android)
│   └── src/main/java/io/furryr/file/core/
│       ├── Path.kt               # Protocol-aware path parsing
│       ├── Vfs.kt                # Scheme-strict router + filesystem operations
│       ├── Folder.kt / File       # Abstract node facade returned by Vfs
│       ├── FileProvider.kt       # Backend SPI + feature/capability types
│       ├── Location.kt           # LocationEntry + edit/delete actions
│       ├── ResolvedProvider.kt / Directory*.kt
│       ├── model/                # FileEntry, ProviderStat, CopyProgress
│       └── source/               # FileSource interface + stream/cache sources
├── daemon/              # :daemon — daemon IPC over :uds.Transport
│   └── src/main/java/io/furryr/file/daemon/
│       ├── DaemonManager.kt      # Lifecycle, targets, launcher SPI, services
│       ├── DaemonEndpoint.kt
│       ├── ipc/                  # DaemonConnection (proto codec + id correlation)
│       ├── process/              # CommandSpec + ProcessChannel + ExecService
│       ├── file/                 # FileService (daemon-backed file ops)
│       ├── source/               # Handle/Fd file sources
│       └── model/                # ExecResult
├── provider/            # :provider — protocol domains (no UI/app deps)
│   └── src/main/java/io/furryr/file/provider/
│       ├── LocalProvider.kt      # Local filesystem over :daemon
│       ├── FtpProvider / SftpProvider / SAFProvider / DocumentFileProvider
│       ├── remote/               # AList/SSH backends + encrypted location store
│       ├── model/                # Persisted location records
│       └── LocationsRepository.kt
├── container/           # :container — container management + one ContainerProvider
│   └── src/main/java/io/furryr/file/container/
│       ├── ContainerManager.kt / ContainerModels.kt / ContainerPaths.kt
│       ├── ProotRunner / ProotInstaller / DistroFetcher
│       ├── ContainerDaemonLauncher.kt
│       └── ContainerProvider.kt  # one FileProvider for every container
├── hub/                 # :hub — UDS hub server over daemons
│   └── src/main/java/io/furryr/file/hub/
│       ├── Hub.kt               # lifecycle + environment contract
│       └── HubServer.kt         # hub protocol, target/exec/file translation
├── native/              # Rust workspace: daemon + hub terminal utilities
│   ├── src/main.rs      # Accept loop + per-connection state
│   ├── src/handlers.rs  # Command dispatch (incl. CancelRequest)
│   ├── Cargo.toml
│   ├── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
│   ├── build-proot.sh            # Cross-compile proot from source
│   ├── run0/src/main.rs  # arg parsing + hub protocol + local tty bridge
│   ├── here/src/main.rs  # reveal a path in the current file-manager tab
│   ├── agent/src/        # modular ratatui + crossterm terminal AI chat UI
│   └── hub-client/       # shared hub wire protocol and descriptor transport
├── proto/               # Protobuf IPC (generated into :daemon)
│   ├── file_daemon.proto  # daemon protocol
│   └── hub.proto          # run0 hub attach handshake
└── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
```

## LAYERING (do not break)

- `:uds` is the **proto-agnostic transport**: opaque frames + optional fds.
- `:core` is the **pure VFS abstraction** (Kotlin/JVM): `Path`, `Vfs`,
  `Folder`/`File`, `FileProvider`, location entries, models and the
  `FileSource` interface. It must not import Android, `:uds`, `:daemon`, or any
  concrete provider.
- `:daemon` is the **daemon protocol layer over `:uds.Transport`**: IPC,
  protobuf, `DaemonManager`, `FileService`, `ExecService`, hub callbacks. It
  depends on `:core` for neutral models and the `FileSource` contract.
- `:provider` implements **protocol domains** over `:core`: Local (`:daemon`),
  FTP/SFTP, SAF/Document and remote AList/SSH. One provider instance owns all
  locations of its protocol (internal subinstances), so Router keys are strict
  scheme names with no duplicates.
- `:container` owns container lifecycle and exposes **one** `ContainerProvider`
  for all containers. It depends on `:core`, `:daemon` and `:virgl`.
- `:hub` owns `Hub`/`HubServer` and depends on `:daemon` + `:uds`.
- `:virgl` is the resident renderer host over `:uds`.
- `:app` is the UI shell and platform wiring: Compose, terminal/session,
  `ForwardFileProvider`, provider registration. It must not implement routing.

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| UI screens | `app/.../FileManagerApp.kt` | Main composable, tabs, state |
| File listing | `app/.../ui/screens/FileListScreen.kt` | LazyColumn, pull-to-refresh |
| Settings | `app/.../ui/screens/SettingsActivity.kt` | Root/shell toggles, su path → `DaemonManager` |
| File operations | `core/.../Vfs.kt` | Scheme-strict routing + Folder/File facade; UI only calls this |
| Provider locations | `provider/.../LocationsRepository.kt` | Location entries + actions for SAF/FTP/SFTP/local/remote |
| Provider implementations | `provider/.../` | Protocol domains with internal location subinstances |
| Provider wiring | `app/.../Providers.kt` | Daemon config, container runtime seam, registrations |
| VirGL host | `virgl/.../VirglService.kt` | Resident `:virgl` process; socket + renderer lifecycle |
| Daemon facade | `daemon/.../DaemonManager.kt` | Lifecycle, targets, launcher SPI |
| UDS transport | `uds/.../UdsTransport.kt` | Frames + fd passing; `UdsClient`/`UdsServer` |
| run0 hub server | `hub/.../HubServer.kt` | Exec/PTY, file tools, streaming AI chat, session history, MCP HTTP proxy |
| run0 CLI | `native/run0/src/main.rs` | Rust client: hub protocol + local tty bridge |
| here CLI | `native/here/src/main.rs` | Open target descriptor + request file-manager navigation |
| agent CLI | `native/agent/src/ui.rs` | Modular ratatui + crossterm terminal AI chat UI |
| Hub client transport | `native/hub-client/src/lib.rs` | Shared framing, abstract sockets and SCM_RIGHTS |
| Hub protocol | `proto/hub.proto` | run0/agent protocol; paths for file tools; chat/session/MCP streams |
| run0 bootstrap | `hub/.../Hub.kt` | Starts the hub, env contract |
| Unified exec/PTY | `daemon/.../process/ExecService.kt` | `CommandSpec` → `ProcessChannel` |
| AI gateway | `hub/.../AiService.kt` | OpenAI-compatible streaming, normalized Chat events, model catalog |
| Agent hub client | `agent/.../hub.rs` | Chat stream, history/sessions, catalog, MCP HTTP proxy |
| Agent MCP | `agent/.../mcp.rs` | Streamable HTTP through the hub; stdio locally |
| Agent sessions | `app/.../agent/AgentSettings.kt` | Session files, titles, active session per terminal |
| MCP proxy | `hub/.../McpProxy.kt` | HTTP proxy for online MCP; hub never speaks MCP |
| Daemon file I/O | `daemon/.../file/FileService.kt` | list/stat/open/copy per target |
| Container launch | `container/.../ContainerDaemonLauncher.kt` | proot/chroot argv + VirGL environment |
| Terminal IME | `app/.../agent/TerminalViewComposable.kt` | focus hand-off + `showSoftInput`; see NOTES |
| Native daemon | `native/src/main.rs` | File ops under root/shell |
| IPC protocol | `proto/file_daemon.proto` | Request/Response messages |

## CONVENTIONS

- **No DI framework** — `DaemonManager`, `Vfs`, `LocationsRepository`
  are singleton `object`s. Manual wiring in `app/.../Providers.kt`.
- **No ViewModel** — State lives in `FileManagerApp.kt` as `remember`/`mutableStateOf`. Single-activity.
- **All daemon I/O is blocking** — Kotlin wraps in `Dispatchers.IO`; Rust uses `current_thread` tokio.
- **Proto lives in `:daemon`** — the `app`/`:core` modules must not enable the protobuf plugin.
- **Container-visible names stay generic** — never expose `file-manager` in
  container paths, sockets, services, or environment values. VirGL is always
  enabled, runs in the `:virgl` process, and is exposed at `/dev/virgl`. The
  listener service is resident: the UI binds it early, it binds the vtest socket
  immediately (cheap), and accepts clients in Kotlin. The renderer library is
  started on demand when a container runs, on a background thread; queued client
  descriptors are handed to the native host once it is ready. That single thread
  serves every connection through one shared virglrenderer instance (vtest
  multi-client mode) and only initializes the renderer when a client actually
  sends commands. Connections do not consume a thread each. Renderer state is
  process-global. Pass `VTEST_SOCKET_NAME=/dev/virgl` directly in the process
  environment; do not write VirGL settings into `/etc/profile.d`.

## ANTI-PATTERNS (THIS PROJECT)

- `as any` / `@ts-ignore` / `@ts-expect-error` — type safety violations
- `unsafe { libc::... }` — allowed (Rust side for abstract UNIX sockets)
- Silent catch (`catch {}`) — only allowed for Shizuku availability checks
- UI importing `io.furryr.file.daemon.ipc`, `io.furryr.file.proto`, or raw
  `DaemonConnection` — always go through the `:daemon` public facades.

## TESTING POLICY

- Do not write, add, restore, or run unit tests for any Kotlin or Rust code in this repository.
- Verify behavior through manual testing only, including UI interaction and command-line checks.
- Do not add test-only dependencies, test source sets, or Rust `#[cfg(test)]` modules.
- Compilation and build checks are allowed; they are not substitutes for manual behavioral testing.

## COMMANDS

```bash
./gradlew assembleDebug                          # Build APK
./gradlew :uds:compileDebugKotlin                # Compile the UDS transport layer only
./gradlew :daemon:compileDebugKotlin             # Compile daemon communication layer only
./gradlew :core:compileKotlin                    # Compile the pure VFS abstraction
bash native/build-android-aarch64.sh             # Cross-compile Rust daemon
bash native/build-proot.sh                       # Cross-compile proot binary
adb install -r app/build/outputs/apk/debug/*.apk # Install
```

## NOTES

- Target SDK 28 avoids storage restrictions needed by root features.
- `DaemonManager.startHub()` runs the communication hub (bootstrap in
  [`hub/Hub.kt`]): an unauthenticated UDS server with a fresh random
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
  identity ([`daemon/DaemonIdentity.kt`]), owned by its connection, and receives
  `--key <pub> --id <id>`. Keys never touch disk; the fresh key gives every launch
  a different socket. The manager never reattaches to an old daemon.
  Every connection must pass a nonce/signature handshake
  ([`daemon/DaemonAuth.kt`] ↔ `native/src/auth.rs`) — the derived name is public,
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
- Client entry points live in `:daemon` (`DaemonManager.fileService/execService`).
- **Terminal IME tap race (upstream-worthy)** — Termux's
  `TerminalView.onSingleTapUp()` calls `requestFocus()` on the terminal view, then
  the client moves focus back to `TerminalInputView` and shows the IME.
  `InputMethodManager.focusIn()` only posts an asynchronous focus check, while
  `showSoftInput()` checks `mServedView` synchronously, so showing in the same
  touch event races the focus hand-off and the request is dropped. It is most
  visible after returning from a freeform/small-window app, where window focus is
  handed back late. Termux works around this with 300–500ms `postDelayed` retries
  (issues #2111/#2112/#5014) and still fails sometimes. `TerminalViewComposable.kt`
  instead posts `showSoftInput` so it runs after the focus check, keeps the request
  pending while `hasWindowFocus()` is false, and retries from
  `onWindowFocusChanged(true)`; pending requests are cancelled on focus
  loss/detach/release. Keep this ordering when touching terminal input — do not
  replace it with fixed delays. In normal mode a touch tap summons the IME while
  still being forwarded to the program (the extra-keys row has no keyboard
  toggle). When the terminal has mouse tracking active, a tap is a program click
  and the keyboard is summoned by swiping up starting from the extra-keys row;
  the same swipe also works in normal mode, so dragging and long-press selection
  stay untouched. `TerminalView` is kept unfocusable except during text selection
  so its tap handler cannot steal focus from `TerminalInputView` and attach the
  IME to its `TYPE_NULL` editor. `InputDevice.SOURCE_MOUSE` never summons the IME.
