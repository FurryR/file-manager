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
├── agent/               # :agent — Kotlin agent loop, prompt, tools, provider
│   └── src/main/java/io/furryr/file/agent/
│       ├── AgentSession.kt     # prompt loop, tool dispatch, cancel, history
│       ├── AgentTools.kt       # built-in read/write/copy/glob/exec/list_targets
│       ├── SystemPrompt.kt     # prompt + approval policy
│       ├── AiService.kt        # OpenAI-compatible transport + model catalog
│       ├── AgentEnvironment.kt # app callbacks (provider, sessions, MCP config)
│       └── AgentMcp.kt         # remote MCP catalog/proxy facade
├── hub/                 # :hub — UDS hub server over daemons
│   └── src/main/java/io/furryr/file/hub/
│       ├── Hub.kt               # lifecycle + environment contract
│       └── HubServer.kt         # hub protocol, target/exec/file translation
├── pulse/                # :pulse — embedded PulseAudio host
│   └── src/main/java/io/furryr/file/pulse/
│       ├── PulseManager.kt      # on/off prefs, socket paths, container env
│       ├── PulseService.kt      # :pulse process: resident listener + core lifecycle
│       ├── PulseNative.kt       # JNI bridge to libpulsehost
│       ├── AudioOut.kt          # the single AudioTrack (48k stereo float)
│       └── MicInput.kt          # AudioRecord loop + mic-FGS promotion
├── wayland/              # :wayland — compositor lifecycle, input/viewer, container env
│   └── src/main/java/io/furryr/file/wayland/
│       ├── WaylandManager.kt    # runtime dirs, density scale, env/binds
│       ├── WaylandService.kt    # resident host: native start/stop/attach
│       ├── WaylandView.kt       # SurfaceView + input forwarding
│       ├── NativeCompositor.kt  # JNI bridge to libwayland_compositor.so
│       └── XkbData.kt           # installs assets/xkb for libxkbcommon
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
  for all containers. It depends on `:core`, `:daemon`, `:pulse` and `:wayland`.
- `:wayland` hosts the nested compositor and wires it into containers: the
  Smithay implementation lives in the Rust crate `native/wayland`
  (`libwayland_compositor.so`), while this module owns its lifecycle, the
  viewer surface, input forwarding and the socket-directory bind +
  `WAYLAND_DISPLAY`/`XDG_RUNTIME_DIR` env. The output scale comes from
  `densityDpi / 160` (`WaylandManager.displayScale`) and is advertised as
  `wl_output` scale plus `wp_fractional_scale_v1`. It also hands the app's
  SELinux context to the daemon (`WAYLAND_SHM_CONTEXT`) so the daemon can gate
  container shared memory. Keymap data (xkeyboard-config) is installed to
  `assets/xkb` by `scripts/fetch-xkbdata.sh` and extracted by `XkbData`.
- `:agent` is the **single agent brain**: system prompt, provider transport,
  tool definitions, tool-call parsing, loop, approval policy and session
  history. It executes built-in tools directly through `:daemon`; the terminal
  shell only renders events and answers approval requests.
- `:hub` owns `Hub`/`HubServer` and depends on `:agent` + `:daemon` + `:uds`.
  It is transport-only: socket auth, exec/PTY, file primitives for legacy
  clients, and agent event forwarding.
- `:pulse` is the resident embedded-PulseAudio host over `:uds`.
  `native/pulse/pulse_host.c` embeds the native protocol, mixer, sink and source
  in `libpulsehost.so`; D-Bus and dynamic modules are disabled. Audio policy
  (AudioTrack, RECORD_AUDIO, microphone foreground service) stays in Kotlin.
  Containers use only `PULSE_SERVER=unix:/dev/pulse`.
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
| Daemon facade | `daemon/.../DaemonManager.kt` | Lifecycle, targets, launcher SPI |
| UDS transport | `uds/.../UdsTransport.kt` | Frames + fd passing; `UdsClient`/`UdsServer` |
| run0 hub server | `hub/.../HubServer.kt` | Exec/PTY, file tools, streaming AI chat, session history, MCP HTTP proxy |
| PulseAudio host | `pulse/.../PulseService.kt` | Resident `:pulse` process; one socket, AudioTrack sink, AudioRecord source |
| PulseAudio build | `native/build-pulse.sh` | PA 17.0, libsndfile and libltdl statically linked into one libpulsehost.so |
| Wayland viewer | `app/.../ui/screens/WaylandActivity.kt` | Full-screen viewer; `WaylandView` surface + input, predictive back |
| Wayland wiring | `wayland/.../WaylandManager.kt` | socket dir + env for containers, density scale, xkb data |
| Smithay compositor | `native/wayland/src/lib.rs` | run loop, socket, JNI; `state.rs` protocol state, `egl.rs` Android output |
| xkb data fetch | `scripts/fetch-xkbdata.sh` | xkeyboard-config → `app/src/main/assets/xkb` (gitignored) |
| Container shm gate | `native/src/shmgate.rs` | seccomp user-notif on `memfd_create` + fd injected from the compositor's domain; no-op when domains match |
| run0 CLI | `native/run0/src/main.rs` | Rust client: hub protocol + local tty bridge |
| here CLI | `native/here/src/main.rs` | Open target descriptor + request file-manager navigation |
| agent CLI | `native/agent/src/ui.rs` | Modular ratatui + crossterm terminal AI chat UI |
| Hub client transport | `native/hub-client/src/lib.rs` | Shared framing, abstract sockets and SCM_RIGHTS |
| Hub protocol | `proto/hub.proto` | run0 transport: PTY/exec, auth, history, catalog, MCP proxy |
| Agent protocol | `proto/agent.proto` | Attach/prompt/compact, streamed events, approval and MCP bridge |
| run0 bootstrap | `hub/.../Hub.kt` | Starts the hub, env contract |
| Unified exec/PTY | `daemon/.../process/ExecService.kt` | `CommandSpec` → `ProcessChannel` |
| AI gateway | `agent/.../AiService.kt` | OpenAI-compatible streaming, normalized Chat events, model catalog |
| Agent loop | `agent/.../AgentSession.kt` | Prompt loop, tool dispatch, approval, mid-run steering, history persistence |
| Agent tools | `agent/.../AgentTools.kt` | Built-in tools executed directly through `:daemon` |
| Agent prompt/policy | `agent/.../SystemPrompt.kt` | System prompt + dangerous-path approval rules |
| Rust agent shell | `native/agent/src/main.rs` | TUI client: prompt input, rendering, approval, local MCP host |
| Agent MCP | `native/agent/.../mcp.rs` | Local stdio host + hub HTTP proxy for remote servers |
| Agent sessions | `app/.../agent/AgentSettings.kt` | Session files, titles, active session per terminal |
| MCP proxy | `agent/.../McpProxy.kt` | HTTP proxy for online MCP; hub never speaks MCP |
| Daemon file I/O | `daemon/.../file/FileService.kt` | list/stat/open/copy per target |
| Container launch | `container/.../ContainerDaemonLauncher.kt` | proot/chroot argv + PulseAudio/Wayland environment; KGSL GPU probe in `ProotRunner` |
| Terminal IME | `app/.../agent/TerminalViewComposable.kt` | focus hand-off + `showSoftInput`; see NOTES |
| Native daemon | `native/src/main.rs` | File ops under root/shell |
| Process subreaper | `native/src/reaper.rs` | Central SIGCHLD reaper; exit-code channels only, no process inventory |
| IPC protocol | `proto/file_daemon.proto` | Request/Response messages |

## CONVENTIONS

- **No DI framework** — `DaemonManager`, `Vfs`, `LocationsRepository`
  are singleton `object`s. Manual wiring in `app/.../Providers.kt`.
- **No ViewModel** — State lives in `FileManagerApp.kt` as `remember`/`mutableStateOf`. Single-activity.
- **All daemon I/O is blocking** — Kotlin wraps in `Dispatchers.IO`; Rust uses `current_thread` tokio.
- **Proto lives in `:daemon`** — the `app`/`:core` modules must not enable the protobuf plugin.
- **Container-visible names stay generic** — never expose `file-manager` in
  container paths, sockets, services, or environment values. The audio host
  is on by default (`pulse_enabled` in `settings`). When off, `PulseManager`
  provides no audio mount or environment variable. Bind `cacheDir/pulse` at
  `/dev/audio`, and create `/dev/pulse -> /dev/audio/pulse` in both backends.
  This exposes one socket, without a `.sock` suffix, while directory binds
  survive listener recreation. File binds would pin a dead inode in chroot.
  The UI binds the listener early; the engine starts on container launch.
  Settings restart only the engine, keeping the listener alive. Pass
  `PULSE_SERVER` in the process environment, not `/etc/profile.d`.
- **A memfd's label follows its creator, not its path** — `wl_shm` pools are
  fds the client creates, so a memfd inode carries the client's SELinux domain.
  A proot container shares the app's domain and is fine; a root container (chroot
  backend via `su`, `u:r:ksu:s0`) does not, and the compositor's
  `file:{ read write }` check then drops the fd — the client only sees
  `invalid arguments for wl_shm#4.create_pool`, never an mmap error. `shmgate`
  answers `memfd_create` from the syscall boundary (seccomp user notification)
  with a pool created in the compositor's domain by a forked factory, so nothing
  is preloaded into the container and statically linked clients work too. The
  gate stays off when the daemon already shares the compositor's domain, and it
  replies `ENOSYS` whenever it cannot service a request. PulseAudio shared memory
  has the same constraint and is not covered yet.
- **proot GPU pass-through is a KGSL probe** — `ProotRunner.gpuMounts()` binds
  `/dev/kgsl-3d0` (plus `/dev/dma_heap`/`/dev/ion` when present) only when the
  KGSL command device exists, so proot containers can run turnip + zink
  against the real Adreno GPU. The chroot backend needs no equivalent: its
  private `/dev` tmpfs already replicates these nodes from the host.

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
bash native/build-pulse.sh                       # Cross-compile embedded PulseAudio host (libpulsehost.so)
bash native/build-xkbcommon.sh aarch64           # Cross-compile libxkbcommon (required by the Wayland compositor)
bash scripts/fetch-xkbdata.sh                    # xkeyboard-config → app/src/main/assets/xkb (required before packaging)
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
  workspace member (`native/run0/`), alongside `native/here/` and
  `native/agent/`. All three
  are staged under `app/src/main/executables/` and packaged through
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
  (`libfiledaemon.so`, `libproot.so`, `libpulsehost.so`,
  `libwayland_compositor.so` and its `libeglshim.so` soname bridge) because the Android installer only
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
- **Container runtime files are shared, not per-container**: one
  `cacheDir/container/resolv.conf` and `hub.sh` serve every container,
  written **in place** (truncate + write, never delete + recreate) so live
  binds see updates — proot's binds are path translation (every open
  re-reads the host file) and a chroot `mount --bind` pins the inode but
  shows in-place writes. `ContainerManager.startResolverWatch` (registered
  from `MainActivity.onResume`) re-resolves the active network's DNS on link
  changes — including VPN switches — and every container launch refreshes it
  too; an empty DNS report keeps the last known servers. `/tmp` and `/run`
  stay per-container under `cacheDir/container/<id>/tmp/`.
- **The Wayland viewer runs the Smithay compositor from `native/wayland`**
  (`libwayland_compositor.so`, started by the resident `WaylandService`;
  toolbox entry in the drawer tools group). The compositor creates
  `wayland-0` inside `cacheDir/wayland/wayland-runtime/`, whose directory is
  bound into containers at `/dev/wayland` (same bind-the-directory trick as
  PulseAudio; guests get `XDG_RUNTIME_DIR=/dev/wayland`,
  `WAYLAND_DISPLAY=wayland-0`). All containers share the socket; "one
  desktop at a time" is a usage constraint, extra clients just open more
  windows on the nested desktop. GPU access is the guest's own stack:
  containers run turnip+zink against the KGSL device nodes and hand dma-bufs
  to the compositor, so no virgl/vtest/shim variables or binds exist.
  The output scale is `densityDpi / 160` (`WaylandManager.displayScale`):
  it sets the `wl_output`/`preferred_buffer_scale` integer fallback and the
  `wp_fractional_scale_v1` value, and refreshes as one unit, so clients
  never disagree with the panel. Input arrives from `WaylandView` as raw
  Android `MotionEvent`/`KeyEvent` payloads; the native keymap turns Android
  key codes into evdev before they reach the seat. Keymap data
  (`assets/xkb`, installed by `scripts/fetch-xkbdata.sh`) is extracted once
  by `XkbData` into `filesDir/xkb` and passed as `XKB_CONFIG_ROOT`.
  The Android IME drives `zwp_text_input_v3` directly
  (`native/wayland/src/text_input.rs` — Smithay's own module expects a Wayland
  input-method client, which does not exist here): `enter`/`leave` follow the
  keyboard focus, `commit`/`preedit`/`delete` are JNI commands from
  `NativeCompositor`, and the compositor calls back `onTextInputEnabled` so the
  viewer summons the keyboard when a client enables a text field. That callback
  must use a class reference and method ID cached during `nativeStart`: a native
  thread later attached to the VM has no application class loader, so
  `FindClass` there fails with `ClassNotFoundException` and kills the process.
  The Kotlin side posts the callback to the main thread (views may only be
  touched there). Clients without `zwp_text_input_v3` (foot and most terminals)
  still get CJK: the IME composes normally, and a commit the terminal cannot
  express as keycodes is typed through a regenerated keymap that maps
  `F13`..`F22` to Unicode keysyms (`native/wayland/src/text_input.rs`,
  `State::send_text_as_keys`). The keymap string comes from the keyboard's own
  `xkb_keymap_get_as_string`, one `replace key <FKnn> { [ Uxxxx ] };` per
  character is spliced into its symbols section, and the keycodes are then
  pressed. Write Unicode keysyms as `U4F60`, never `U+4F60` — XKB's lexer reads
  `U+` as an operator and rejects the whole keymap, which strands the client on
  the previous keymap with no error on its side.
  Smithay's EGL loader `dlopen`s the desktop soname `libEGL.so.1`, which does
  not exist on Android and panics the compositor thread. `native/wayland`'s
  build script links `native/egl_shim.c` into `libeglshim.so` with
  `SONAME libEGL.so.1` and a `DT_NEEDED` on `libEGL.so`; `NativeCompositor`
  preloads it before the compositor library, so the lookup resolves to the
  already-loaded shim and `dlsym` reaches the real EGL symbols. Do not remove
  either the soname flag or the preload order. The compositor also unlinks a
  stale `wayland-0` before binding — a crashed previous process leaves the
  socket file behind, and clients then get `ECONNREFUSED`
  (`failed to connect to wayland`) instead of a clear bind error.
  Three Smithay integration points are easy to get wrong and each shows up as
  "connected but nothing draws": `Display::dispatch_clients` must run every
  loop iteration (the socket is polled by calloop, but client requests are only
  read there), `Window::on_commit` must be called *after*
  `on_commit_buffer_handler` in the compositor's `commit` (otherwise the cached
  window geometry stays 0×0), and the Android EGL window surface needs
  `Transform::Flipped180` at render time, not on the output's own transform —
  flipping the output would also mirror pointer coordinates.
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
- **The daemon is a child subreaper, and nothing more** ([`native/src/reaper.rs`]) —
  orphaned descendants reparent to it instead of escaping to host init, and one
  central SIGCHLD sweep calls `waitpid(-1)` so no zombie outlives its creator.
  Tracked children get their exit code over a watch channel (`reaper::track`
  returns it immediately after spawn, before any await); no other code calls
  `wait*`, so a status can never be stolen twice.
  It keeps **no process inventory**, and that is deliberate: killing belongs to
  the handle that owns the process — a PTY owns its session, an exec owns its
  process group — and the lifetime boundary is the container/app itself. When
  file-manager or the container goes away, nothing is left to kill, so a global
  "who is alive" table would only be a second source of truth that drifts from
  the handles (and rootless Android cannot rebuild it from `/proc`). Daemon
  shutdown therefore just closes PTYs and drops execs.
  Every spawn arms `PR_SET_PDEATHSIG(SIGKILL)` in the child
  (`reaper::arm_parent_death_signal`), which covers the one case no bookkeeping
  can: the daemon being SIGKILLed (force-stop, LMK, `kill -9`) instead of
  reaching its shutdown path. That is also what makes "file-manager exits, the
  container goes with it" hold down to the last process.
  A descendant that deliberately `setsid()`s into its own session (nohup-style
  detaching) outlives the group kill. That is accepted: it is not an escape from
  the subreaper (parentage never changes, it is still reaped), and its lifetime
  is already bounded by the container/app.
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
- **Audio host: one feature, one .so.** `native/build-pulse.sh` builds PA 17.0,
  libsndfile 1.2.2 and libltdl 2.4.7 as static archives for API 26, then links
  `libpulsehost.so` with undefined-symbol rejection. No D-Bus, GLib, dynamic
  modules, config files or standalone audio daemon are needed.
  `pulse_host.c` runs a PA main loop plus one audio I/O thread. Kotlin owns
  the filesystem listener; native code duplicates its fd and never unlinks it.
  `android_output` is 48 kHz stereo float32; `android_input` is 48 kHz mono
  s16le. Render complete 10 ms blocks and advance an accumulated sample clock;
  resetting the deadline to each wake-up accumulates scheduler delay and
  periodically starves AudioTrack. Prime AudioTrack before playback.
  `pulse_mic_enabled` defaults off; capture requires a RUNNING source,
  RECORD_AUDIO permission and microphone foreground-service promotion.
  `pulse_buffer_ms` defaults to 200 and clamps to 10–1000. Settings travel over
  Messenger because SharedPreferences caches are process-local. Restart the
  engine in place for changes; preserve the listener and its socket inode.

## TODO - Bug checks

- [ ] `provider/.../SftpProvider.kt`: verify SFTP preview/read across forward and backward seeks and EOF against a real server.
- [ ] `provider/.../remote/SshExecBackend.kt`: verify `ssh://` seeks, sequential copy progress and cancellation against a real server with `tail -c +N` support.
