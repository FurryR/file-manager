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
├── wayland/              # :wayland — wldroid compositor wiring + container env
│   └── src/main/java/io/furryr/file/wayland/
│       └── WaylandManager.kt    # runtime dirs, GPU mode, virgl session, env/binds
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
- `:wayland` wires the embedded wldroid/wlroots compositor into containers:
  the socket-directory bind + `WAYLAND_DISPLAY`/`XDG_RUNTIME_DIR` env, GPU-mode
  detection (KGSL → turnip+zink passthrough; otherwise virgl vtest), the virgl
  server session, xkb extraction, and handing the app's SELinux context to the
  daemon (`WAYLAND_SHM_CONTEXT`) so it can gate container shared memory. The
  compositor AARs come from the prebuilt wldroid maven repo
  (`scripts/fetch-wldroid.sh` →
  `third_party/wldroid-maven`, registered first in `settings.gradle.kts`
  because public mirrors serve bogus metadata for unknown coordinates).
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
| Wayland viewer | `app/.../ui/screens/WaylandActivity.kt` | Full-screen wldroid/wlroots viewer; in-process, `CompositorSurface`, predictive back |
| Wayland wiring | `wayland/.../WaylandManager.kt` | socket dir + env for containers, GPU mode, virgl session, xkb extract |
| wldroid fetch | `scripts/fetch-wldroid.sh` | prebuilt wldroid maven repo (compositor+virgl+shims AARs) → `third_party/wldroid-maven` |
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
| Agent loop | `agent/.../AgentSession.kt` | Prompt loop, tool dispatch, approval, history persistence |
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
| Process subreaper | `native/src/reaper.rs` | Central SIGCHLD reaper; kill tracked process groups on exit |
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
bash scripts/fetch-wldroid.sh                     # Fetch prebuilt wldroid maven repo into third_party/ (required before gradle sync)
bash native/build-pulse.sh                       # Cross-compile embedded PulseAudio host (libpulsehost.so)
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
  (`libfiledaemon.so`, `libproot.so` and
  `libpulsehost.so`; the wldroid AARs contribute `libwldroid-compositor.so`,
  `libvirgl-test-server.so` and the shim assets) because the Android installer only
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
- **The Wayland viewer is the wldroid wlroots compositor in-process**
  (`:wayland` + the `wldroid-ui` `CompositorSurface` inside
  `WaylandActivity`; toolbox entry in the drawer tools group). It only runs
  while the viewer activity is alive — it needs the Surface — and wlroots
  creates `wayland-0` inside `cacheDir/wayland/wayland-runtime/`, whose
  directory is bound into containers at `/dev/wayland` (same
  bind-the-directory trick as PulseAudio; guests get
  `XDG_RUNTIME_DIR=/dev/wayland`, `WAYLAND_DISPLAY=wayland-0`). All
  containers share the socket; "one desktop at a time" is a usage
  constraint, extra clients just open more windows on the nested desktop.
  GPU modes: KGSL devices keep the existing turnip+zink passthrough
  (nothing added to the container env); otherwise `WaylandManager` starts
  the vtest `virgl_test_server` and containers get `VTEST_SOCK` plus
  MESA overrides, with the guest-side shims bound at `/dev/shims`
  (virgl-mode guests still need their setup — mesa + shim install — inside
  the container). The wldroid AARs declare minSdk 29 but use nothing newer
  than API 26; the app manifest overrides them down to 26, and
  `wldroid-ui`'s transitive `wldroid-proot` is excluded (own container
  stack).
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
- **The daemon is a child subreaper** ([`native/src/reaper.rs`]) — orphaned
  descendants reparent to it instead of escaping to host init. One central
  SIGCHLD sweep calls `waitpid(-1)`, reaps adopted orphans, and publishes each
  tracked child's exit code over a watch channel; no other code waits on a
  child, so a status can never be stolen twice. `reaper::track` returns that
  channel immediately after spawn, before any await. Rootless Android cannot
  traverse `/proc`, so the reaper never scans the process table: every spawn
  creates its own process group, and teardown signals the tracked groups
  (SIGTERM, then SIGKILL after a 400ms grace) until the registrations drain.
  Descendants that `setsid()` out of their group are the one case this
  inventory cannot reach.
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
