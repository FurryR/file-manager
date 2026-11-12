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
│       ├── Vfs.kt                # Scheme-strict router + address→node walk (incl. `!` views)
│       ├── ViewOpener.kt         # ViewOpener SPI + priority registry + enter
│       ├── NodeOps.kt            # copy/move/destination over nodes
│       ├── Folder.kt / File       # Abstract node facade returned by Vfs
│       ├── FileProvider.kt       # Backend SPI + feature/capability types
│       ├── Location.kt           # LocationEntry + edit/delete actions
│       ├── ResolvedProvider.kt / Directory*.kt
│       ├── model/                # FileEntry, ProviderStat, CopyProgress
│       └── source/               # FileSource interface + stream/cache sources
├── opener/              # :opener — container views + open-with table (Kotlin/JVM, no Android)
│   └── src/main/java/io/furryr/file/opener/
│       ├── OpenRouter.kt       # generic ordered open-with table (C, R)
│       ├── ArchiveOpener.kt    # zip-compatible file → ArchiveFolder
│       ├── ArchiveFolder.kt    # ArchiveFolder/ArchiveFile/ArchiveSource
│       ├── Archives.kt         # process-wide mount cache keyed by Path
│       └── ZipArchiveSource.kt # in-place central-directory index + entry reads
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
│       ├── LocationSources.kt    # app-supplied read-only location contracts
│       ├── remote/               # AList/SSH backends
│       └── model/                # Persisted location records
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
  `FileSource` interface. It also owns the node/view model (`VNode`, `VFile`,
  `VFolder`, `Path` (address + view levels), `ContentInfo`, `Navigator`,
  `ViewOpener`/`ViewOpeners`). It
  must not import Android, `:uds`, `:daemon`, or any concrete provider.
- `:opener` is the **file-to-view route** (Kotlin/JVM): the generic
  `OpenRouter<C, R>` open-with table, concrete openers and the
  views they return (`ArchiveFolder`, `ArchiveFile`, `ArchiveSource`). It
  depends only on `:core`; the staging directory for stream-only archives is
  passed in as a `File`, never a `Context`. The router knows outcome types only
  through its caller's `R`, so `:opener` never imports app renderers or UI.
  Renderers know nothing about openers: the app resolves a file through the
  table (`app/.../OpenTable.kt`) and applies the returned outcome.
- `:daemon` is the **daemon protocol layer over `:uds.Transport`**: IPC,
  protobuf, `DaemonManager`, `FileService`, `ExecService`, hub callbacks. It
  depends on `:core` for neutral models and the `FileSource` contract.
- `:provider` implements **protocol domains** over `:core`: Local (`:daemon`),
  FTP/SFTP, SAF/Document, remote AList/SSH and HTTP(S). One provider instance
  owns all locations of its protocol (internal subinstances), so Router keys are
  strict scheme names with no duplicates. `HttpProvider` registers both `http`
  and `https` and is the only place that knows a URL is a network address: it
  mints a `RemoteFile` node and everything above it — openers, renderers, copy,
  share, forward provider — treats that node like any other file. No layer
  inspects a scheme to decide how to open, copy or share a resource. It also
  fetches the progress/`WebTransfers` state the app's foreground notification
  observes (the app binds the service starter, `:provider` owns the facts). **No provider owns persistence**: the app
  opens the SQLite/preference/encrypted stores and passes the current records
  through `SafLocationSource` / `FtpServerSource` / `SftpServerSource` /
  `RemoteLocationSource` / `DocumentTreeSource` at `init()` time.
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
  Containers use only `PULSE_SERVER=unix:/dev/pulse`. The module never reads
  preferences: `app/.../settings/PulseSettings.kt` persists `pulse_enabled`,
  `pulse_mic_enabled` and `pulse_buffer_ms`, then calls `PulseManager.configure`.
- `:app` is the UI shell and platform wiring: Compose, terminal/session,
  `ForwardFileProvider`, provider registration and the Android renderer layer
  under `app/.../render/`. It must not implement routing.

## NODES / PATHS

- **One address type.** `core/.../Path.kt` is the only address: the provider
  part (`scheme`/`host`/`segments`) plus **view levels**, everything after a
  `!`. A provider never sees a view level — `toFullUri`, `toInnerPath`,
  `segmentList`, `parent`, `child` and `name` are all view-blind.
  - `/storage/a.zip!/docs/page.html` — one view level (archive entry).
  - `/storage/a.zip!/inner.zip!/x.txt` — two levels; nesting is a chain, never a
    recursive `Nested`/`Proxy` type and never a `zip:` scheme.
  - `https://host/a.zip!/x.txt` — the *bytes* come from HTTP, the view does not.
  - The `!` is mandatory and reserved: boundaries are written, never guessed by
    probing the filesystem the way `root!/inner` locators used to.
- **Object model, not provider strings.** `core/.../VNode.kt` defines
  `VNode`/`VFile`/`VFolder`; `VNode.path: Path` is the node's identity (there is
  no `ViewRef`/`NodeRef` any more). `VFolder.listNodes()`, `child()` and
  `VNode.parent` are the navigation currency; only `Vfs.node(path)` turns
  an address into a node, entering each `!` level through the opener registry.
  `FileProvider.node(path, innerPath)` is what
  mints a node, so a provider whose files are not storage paths (a live
  `RemoteFile`) returns its own `VFile` implementation and no caller needs to
  know which kind it got.
- **Archives.** `opener/.../ArchiveFolder.kt` owns `ArchiveFolder`/
  `ArchiveFile`/`ArchiveSource`; core only knows `VFile`/`VFolder` and the
  `ViewOpeners` SPI. An archive node's identity is `backing.path.enterView(segments)`,
  so nested archives extend the chain one level at a time.
  `opener/.../ZipArchiveSource.kt` reads the central directory in place
  when the outer `FileSource` is seekable (local/daemon fd or handle, SFTP/SSH,
  AList cache) and inflates entries on demand, so opening a large archive never
  copies it into the cache. Stream-only backends (SAF, FTP, content URIs) are
  staged once. Entries are read-only; `ArchiveFolder.capabilities()` is what
  greys out create/terminal/share/modify actions. An APK is a zip but is not
  offered as an archive: `ContentTypes` maps it to the package MIME kind so it
  opens with the installer. Opening an entry externally signs its address text
  into a `ForwardFileProvider` URI; the provider resolves it through
  `Vfs.node`, so a zip member never needs a synthetic VFS path.
- **Content facts.** `core/.../ContentInfo.kt` defines `ContentInfo`,
  `ContentKind` and `ContentTypes` (extension map + magic sniffing).
  `ProviderStat` carries `size`. `VFile.describe()` is the only place renderer
  selection should read MIME/kind/seekable from.
- **Navigation/history.** `core/.../Navigator.kt` owns current node,
  back/forward and breadcrumb ancestry; `Vfs.node` is the only address walker,
  and every operation above it takes nodes.
  A node address is its `Path` text (`/a.zip!/docs`), persisted as-is; `Address.kt` is
  the breadcrumb projection.
- **One history.** The tab owns it: `FileTab.history` is the one linear stop
  list and `historyIndex` is the cursor, so a page, a folder, Home and an edited
  file are stops of the same kind and a jump of any distance is the same
  operation as one step. Each `HistoryStop` carries a stable `id`
  (`kind` = `LOCATION`/`FILE`/`PAGE` plus the last title); renderer sessions are
  keyed by that id, so Back reuses a warm directory and a live page does not keep
  a second list. A WebView's own back/forward list is only a cache of pages it
  already holds (`WebRendererSession.retrace`, used by `moveToStop` so a history
  step costs no request). `RenderHost.onPageVisited` appends or moves the one
  list; the history panel only projects it (`historyRows`).
- **Renderers/Openers.** `app/.../render/Renderer.kt` defines
  `RenderTarget` (a node plus its facts), `RendererFactory`/`RendererSession`,
  `RenderHost` and `Renderers`. Directory, Web, Home and the tab-owned Sora
  text editor all have renderer sessions. `core/.../ViewOpener.kt` defines the format-agnostic
  `ViewOpener`/`ViewOpeners` route. `FolderRendererSession` is the directory
  renderer; `FolderContent` is its list view. `ArchiveOpener` turns a zip
  file into an `ArchiveFolder`; it draws nothing.
- **HTML.** `HtmlDocument`/`DocumentView`/`WebRenderer.kt` project HTML files
  and live URLs as one `DocumentView`; `WebContent` is the WebView. A projected
  page reports no `remoteBase`, so the renderer serves it (and its siblings)
  through `Vfs` on the synthetic `ASSET_HOST`; a live page reports its address,
  so the WebView loads that origin itself and resolves the page's references
  there. The renderer never sees a scheme — it asks the document.
  A long press on a link or an image sets `WebRendererSession.hitTarget`: the
  target *is* a node (`RemoteFile` is a `VFile`), plus the anchor/`alt` text read
  from the DOM through `elementFromPoint` and the response facts from its own
  probe. `WebHitDialog` replaces Chromium's own menu with five actions: copy the
  file, copy the link text, copy the URL, share the file, share the URL. Both
  file actions take the node, never a string: `RenderHost.copyFile` enters the
  tab's normal copy flow (`CopyMoveSource(node)` → `VFolder.destination` →
  `NodeOps.copyTo(source, target)`, so the destination node streams the link in on
  demand), and `RenderHost.shareFile` hands the receiver an on-demand
  `content://` stream through the forward provider.
  Relative assets resolve through the node graph (`file.parent.child(...)`),
  so HTML inside a zip works. WebView never learns about `zip:`/`ssh:`; a
  future `_vfs` gateway + `WebAccessPolicy` is the way to expose other
  providers, not provider aliases.
- **UI wiring.** `FolderContent` accepts `folderOverride` and node callbacks;
  `FileManagerApp` keeps `FileTab.folder` plus flat folder back/forward
  stacks, enters `ArchiveFolder` on archive open, and displays
  `WebContent` for HTML/URL renderers. A `WebRendererSession` is kept per tab
  (`FileTab.id`), so a tab switch neither pays Chromium start-up again nor
  reloads the page; `webUrl` is the page that tab is showing and `webTitle` its
  title, which the path bar and tab label read. Losing the session (an editor
  takes the tab over, another location opens) loses nothing but the cache: the
  pages visited are already stops of the tab. It is still not a full per-tab
  `NavTarget`.
- **Agent.** Archive tools resolve through `Vfs.node` like any other caller;
  there is no injected node service. `archive_list`/`archive_read`/`archive_copy`
  are read-only and never teach the daemon or hub about archives.
- **Do not** register a second provider for the same scheme to add terminal,
  browser or editor features. `Vfs` scheme routing stays strict; new features
  add services/renderers, not providers.

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| UI screens | `app/.../FileManagerApp.kt` | Main composable, tabs, state |
| File listing | `app/.../render/FolderContent.kt` | LazyColumn, pull-to-refresh; error policy from `FolderRendererSession` |
| Settings | `app/.../ui/screens/SettingsActivity.kt` | Root/shell toggles, su path → `DaemonManager` |
| File operations | `core/.../Vfs.kt` | Scheme-strict routing + node facade (`provider.node` mints the node); UI only calls this |
| Node/view model | `core/.../VNode.kt` | `VNode`/`VFile`/`VFolder`; identity is `Path`, views are `!` levels |
| Address → node | `core/.../Vfs.kt` | `Vfs.node(Path)`: provider part, then one `ViewOpeners.enter` per `!` level |
| Node operations | `core/.../VNode.kt`, `core/.../NodeOps.kt` | `rename`/`delete`/`create`/`mkdir`/`destination` on nodes; `NodeOps.copyTo(source, target)` takes two nodes — no operation formats a path |
| Open-with table | `opener/.../OpenRouter.kt` + `app/.../OpenTable.kt` | `OpenRouter<C, R>`/`OpenRule<C, R>` in `:opener`; `OpenTable` registers view/renderer/external order (no per-scheme route) |
| Archive view | `opener/.../ArchiveFolder.kt` + `opener/.../ZipArchiveSource.kt` | Opener-owned view; core knows only `VFile`/`VFolder` |
| Renderers | `app/.../render/Renderer.kt` | `RenderTarget`, `Renderers`, `RenderHost` (Directory + Web + Editor) |
| Openers route | `core/.../ViewOpener.kt` | Format-agnostic SPI + priority registry; archive implementation lives in `:opener` |
| HTML/WebView | `app/.../render/WebRenderer.kt` | `DocumentView`, `HtmlDocument`, `WebContent` |
| Agent archive tools | `agent/.../AgentTools.kt` | `archive_list`/`archive_read`/`archive_copy` via `Vfs.node` |
| Provider locations | `app/.../store/LocationsRepository.kt` | Location entries + actions for SAF/FTP/SFTP/local/remote |
| Location stores | `app/.../store/LocationStores.kt` | App-owned SQLite/preferences/encrypted stores passed to providers |
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
| Child reaping | `native/src/reaper.rs` | Central SIGCHLD reaper + `PR_SET_CHILD_SUBREAPER` (parentage only); exit-code channels, no process inventory |
| Container cgroup | `native/src/cgroup.rs` | Group ownership opened before the chroot; one write ends the container (v2 kill / freeze, v1 freezer); `sweep` kills groups whose owner died |
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
./gradlew :opener:compileKotlin                  # Compile the opener SPI + archive view
bash native/build-android-aarch64.sh             # Cross-compile Rust daemon
bash native/build-proot.sh                       # Cross-compile proot binary
bash native/build-pulse.sh                       # Cross-compile embedded PulseAudio host (libpulsehost.so)
bash native/build-xkbcommon.sh aarch64           # Cross-compile libxkbcommon (required by the Wayland compositor)
bash scripts/fetch-xkbdata.sh                    # xkeyboard-config → app/src/main/assets/xkb (required before packaging)
adb install -r app/build/outputs/apk/debug/*.apk # Install
```

## NOTES

- Kotlin incremental compilation caches go stale after source files are moved or
  deleted; the Compose compiler then dies with
  `kotlin.NotImplementedError: An operation is not implemented: Unknown file`
  (`IrUtilsKt.getFile` → `InferenceFunctionDeclaration.toScheme`). This is a
  cache fault, not a code fault: rebuild with `./gradlew clean <task>` and the
  same sources compile.
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
  The compositor advertises `zwp_linux_dmabuf_v1` (v3) with the formats its
  own EGL display can import (`ImportDma::dmabuf_formats`), imports each
  buffer into the GLES texture cache at `wl_buffer` creation, and refuses the
  import when EGL rejects it. When EGL reports no importable formats the
  global is not created at all, so clients keep using `wl_shm`.
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
- **The daemon is the container's init stand-in; a cgroup bounds it**
  ([`native/src/reaper.rs`], [`native/src/cgroup.rs`]) —
  one central SIGCHLD sweep calls `waitpid(-1)` so no zombie outlives its
  creator, and tracked children get their exit code over a watch channel
  (`reaper::track` returns it immediately after spawn, before any await); no
  other code calls `wait*`, so a status can never be stolen twice. It keeps
  **no process inventory of its own**: killing belongs to the handle that owns the process
  — a PTY owns its session, an exec owns its process group.
  `PR_SET_CHILD_SUBREAPER` makes the daemon the container's **init stand-in**: a
  descendant whose parent dies is reparented to it instead of to host init, so
  the daemon reaps it and `getppid()` reports the daemon. That is the *parentage*
  half of init and costs one `prctl`.
  It is **not** the enumeration half, and no `prctl` can be: these kernels build
  no `children` file and `wait*` only reports exits, so a live adopted orphan
  stays invisible to its subreaper. Listing and killing container processes is
  `cgroup.procs`' job (needs no parentage, no `/proc` walk). A PID namespace
  would deliver both halves at once, but this kernel has no `PID_NS`
  (`/proc/self/ns/pid` is absent, `unshare -p` returns EINVAL), so init is
  assembled from a subreaper plus a cgroup.
  Every spawn arms `PR_SET_PDEATHSIG(SIGKILL)` in the child
  (`reaper::arm_parent_death_signal`, cleared by `fork`, so direct children
  only), which covers the case no bookkeeping can: the daemon being SIGKILLed
  instead of reaching its shutdown path.
  The **container-wide** guarantee comes from the cgroup, because that is the one
  kernel object inherited across `fork` that outlives every process in the tree.
  A chroot daemon owns `ct/<name>` and every spawned child joins it before
  `exec` (`cgroup::attach_self`), so anything the container ever starts — a
  `setsid()`-detached session, a double-forked daemon, a binary this daemon
  never sees — is a member. Shutdown then ends the container with one write
  (`cgroup.kill` on kernel 5.14+, else freeze + `cgroup.procs` loop, else the
  v1 freezer), which no cooperative behaviour can defeat. The handles are
  opened *before* the chroot and used through `openat`, because cgroupfs is gone
  from view afterwards.
  A daemon that is SIGKILLed cannot shoot, so the next launch's `cgroup::sweep`
  ends any group whose recorded owner (pid + starttime) is gone — one directory
  listing plus one `/proc/<pid>/stat` per container, never a process-table walk.
  A spawned child also calls `CLONE_NEWCGROUP` (`cgroup::isolate_view`) right
  after joining: `ct/<name>` becomes the root of its cgroupfs view, so an init
  system inside the container can still create and manage sub-groups (systemd
  does this on startup) while it can no longer see, let alone write, an
  ancestor's `cgroup.procs`. Without that, `systemctl`/`cgclassify` can
  accidentally classify the container's own processes out of the group that is
  supposed to end them.
  This is **accident prevention, not a boundary**: a container root shares uid 0
  and the host's PID namespace with the daemon, so `/proc/<daemon>/ns/*` and
  `/proc/<pid>/mem` are open to it. Do **not** "harden" this by masking
  `/sys/fs/cgroup` — that breaks the init systems that need it and buys nothing
  against a root that is already our peer. A real boundary needs a PID namespace,
  which these kernels do not have.
  proot containers cannot create real cgroups, so they use PRoot's `--cgroup`
  emulation: the same daemon code owns a synthetic v2 hierarchy backed by
  ppid/session data, which also ends the container on shutdown even though the
  processes are not root. Resource limits are unavailable in that hierarchy.
- **Init is optional and user-chosen** — `container::spawn_init()` starts
  `/init` once the container daemon is ready, only when it is an executable
  file. It is an ordinary child (the daemon stays the subreaper and cgroup
  owner), so it runs as non-PID 1; a rootfs without `/init` keeps working as a
  plain shell container. On shutdown init gets a catchable SIGTERM and a bounded
  20 s grace before the cgroup's SIGKILL. There is no daemon-side service manager
  or service UI.
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
- [ ] `core/.../VNode.kt` + `core/.../FileProvider.kt`: verify `writable`/`liveAddress`/`liveReferer`/`peek`/`withReferer` on local, container, SAF, document, FTP, SFTP, SSH, AList, HTTP and archive nodes.
- [ ] `core/.../ResolvedProvider.kt` + `provider/.../LocalProvider.kt`: verify bare paths and `file:///` addresses still reach the local daemon and every other provider keeps its inner-path spelling.
- [ ] `daemon/.../process/ExecService.kt` + `hub/.../HubServer.kt`: verify hub PTY and pipe EXEC still pass descriptors, wait with the 15 min bound, resize, signal and close through run0/native clients.
- [ ] `agent/.../AgentFileAccess.kt`: verify offset reads/writes, EOF, directory listing and glob through `FileService` on host and container targets, including cross-container copy.
- [ ] `agent/.../SystemPrompt.kt` + `agent/.../AgentTools.kt`: verify approval reasons and target roots with `container://` addresses and `container-` hub ids.
- [ ] `container/.../ContainerUris.kt` + `container/.../ContainerPaths.kt` + `container/.../ContainerProvider.kt`: verify here reveal, terminal drop, container root navigation and provider roots.
- [ ] `provider/.../ProviderSchemes.kt`: verify add-location, drawer and web-location flows for `ftp`, `sftp`, `ssh`, `alist`, `http` and `https`.
- [ ] `agent/build.gradle.kts` + `hub/build.gradle.kts`: verify the `:container` dependency does not change runtime initialization or APK packaging.
- [ ] `core/.../Vfs.kt`: verify `Vfs.node(Path)` walks nested `!` levels (zip in zip, URL→zip, archive in container) and still resolves view-free paths through one provider lookup.
- [ ] `core/.../Vfs.kt` + `app/.../Providers.kt`: verify an address with a view level fails clearly when no `ViewOpener` is registered, and the `ArchiveOpener` registration still reaches `Vfs` at startup.
- [ ] `core/.../Navigator.kt`: verify history restore (`restore(path)`) lands on the same node without a service argument.
- [ ] `app/.../forward/ForwardFileProvider.kt`: verify a zip-member `content://` still streams, and a live URL keeps its referer.
- [ ] `agent/.../AgentTools.kt`: verify `archive_list`/`archive_read`/`archive_copy` resolve through `Vfs.node` on host and container targets.
- [ ] `core/.../ViewOpener.kt` + `opener/.../ArchiveOpener.kt`: verify zip open/list after the `VFile`→`VFolder` signature change.
- [ ] `core/.../NodeOps.kt`: verify copy/move across providers, same-provider rename, folder copy and link streaming still behave like the old `Vfs` code.
- [ ] `app/.../FileManagerApp.kt`: verify `navigateTo(view=)` keeps plain provider directories writable/shareable (no `Folder` as a view hint) and typed addresses, home rows and history still open.
- [ ] `core/.../Vfs.kt`: verify invalid address text returns `Result.failure` instead of throwing from `Vfs.node`/`folder`/`file`.
- [ ] `core/.../Vfs.kt`: verify `folder`/`file`/`stat` now suspend and view-aware; agent read/write/list and the drawer root stats still work.
- [ ] `app/.../forward/ForwardFileProvider.kt`: verify share/open/query after unifying `Target` (zip member, live URL referer, `getType` fallback).
- [ ] `opener/.../OpenRouter.kt` + `app/.../OpenTable.kt`: verify rule order still yields archive folder → renderer → external, and a broken archive still reports failure instead of opening externally.
- [ ] `app/.../FileManagerApp.kt`: verify `folderFor` copy/paste destination on plain and archive tabs.
- [ ] `provider/.../SAFProvider.kt` + `core/.../ProviderUnavailableException.kt`: verify a stopped DocumentsProvider app produces the typed unavailable failure from `list`/`stat`/`open` instead of "not a folder", an empty folder, or "not found".
- [ ] `provider/.../SAFProvider.kt`: verify a SAF tree root still opens as a directory while the target app is not running, and that a genuinely deleted document still reports not-found rather than unavailable.
- [ ] `provider/.../SAFProvider.kt`: verify null-cursor classification against a provider that returns empty cursors for missing documents and against a revoked tree grant.
- [ ] `core/.../VNode.kt` + `core/.../Folder.kt`: verify `VFolder.listWithNodes()` returns the same entries and nodes as `list()` + `listNodes()` on every provider and archive folder.
- [ ] `opener/.../ArchiveFolder.kt`: verify `list()` and `listWithNodes()` still report nested archive entries and directory flags identically.
- [ ] `app/.../render/FolderContent.kt`: verify a large folder lists in one listing round trip, never flashes "empty folder" during the first load, and scrolls without dropped frames.
- [ ] `app/.../render/FolderContent.kt`: verify refresh, filter, sort and back-navigation scroll restore still work after moving sort/map work to `Dispatchers.IO`.
- [ ] `app/.../ui/util/Formatters.kt`: verify modified time and size text still render correctly with the cached `DateFormat` after a locale change.
- [ ] `app/.../FileManagerApp.kt`: verify selection survives refresh with selected rows and starts empty after refresh with none selected.
- [ ] `core/.../Plugin.kt`: verify `Plugins` ordering, id replacement, `installAll` failure isolation, and that a plugin-registered provider/opener/content type behaves like a built-in.
- [ ] `core/.../FileProvider.kt`: verify the `ProviderFeature` value class keeps toolbar create actions and `entries` parity on local, container, SAF, FTP, SFTP, SSH, AList and HTTP providers.
- [ ] `core/.../ContentInfo.kt`: verify the new extension registrations (vue/svelte/wmv/3gp/wma/jsonc/gradle/...) and a runtime `ContentTypes.register` both reach icons, text detection and `describe()`.
- [ ] `provider/.../ProviderSchemes.kt`: verify `registerRemote`/`registerWeb`/`unregister` change add-location parsing and drawer grouping while built-in schemes stay unchanged.

## TODO - Code quality review

- [ ] `agent/build.gradle.kts` + `hub/build.gradle.kts`: remove the `:container` dependency by moving `ContainerUris`/`ContainerUriResolver` to `:core` or `:daemon`; verify `:agent`/`:hub` no longer pull `:pulse`/`:wayland` at runtime.
- [ ] `agent/.../SystemPrompt.kt` + `agent/.../AgentTools.kt` + `hub/.../HubServer.kt`: replace the three target-catalog implementations with one `:daemon` catalog; fix the existing `startsWith(CONTAINER_PREFIX)` vs `isContainerHubId()` drift.
- [ ] `app/.../ui/components/FileDrawer.kt` + `app/.../ui/components/drawer/DialogAddLocation.kt` + `app/.../ui/components/drawer/RemoteLocationTools.kt`: replace the hardcoded ftp/sftp/ssh/alist/http dispatch and protocol-specific fields with a registered remote-protocol descriptor; verify a plugin scheme no longer falls through to local path.
- [ ] `app/.../FileManagerApp.kt`: replace the nine `is Folder` checks with a `VFolder` capability (`isView`/`addressable`); verify plugin/custom folders navigate, bookmark and go Up like built-ins.
- [ ] `core/.../Path.kt` + `opener/.../ArchiveFolder.kt`: represent a view root explicitly (or escape `!`); verify `a.zip!` and `a.zip` are different addresses, `child("a!b")` round-trips, and `parent()` of an entry does not collapse to the backing file.
- [ ] `core/.../Path.kt`: lowercase scheme on parse and compare case-insensitively; verify `HTTP://host/path` reaches `HttpProvider`.
- [ ] `core/.../Vfs.kt` + `core/.../Folder.kt` + `core/.../NodeOps.kt` + `opener/.../Archives.kt`: replace `runCatching` around suspend code with a helper that rethrows `CancellationException`; verify cancellation aborts node walks, listings and archive opens.
- [ ] `app/.../OpenTable.kt`: stop swallowing `RendererFactory.create` failures as "unsupported"; verify a failing renderer reports `AppOpenOutcome.Failure` instead of opening externally.
- [ ] `core/.../FileProvider.kt` + `provider/.../web/HttpProvider.kt` + `app/.../Providers.kt`: replace the single `scheme` with a `schemes` set; verify `http`/`https` register and unregister as one provider.
- [ ] `core/.../FileProvider.kt`: remove or use the unused `authority`; make `ProviderFeature` reflect real operations and stop `LocalProvider`/`ContainerProvider` advertising `PTY`/`SYMLINK` they do not implement.
- [ ] `core/.../ContentInfo.kt` + `opener/.../ArchiveOpener.kt`: unify the MIME table, `archiveMimes`/`codeMimes` and the opener extension whitelist; verify a plugin-registered archive/code MIME gets the right `ContentKind` and opener.
- [ ] `core/.../Plugin.kt`: expose renderers, location kinds, open rules and `ContentKind` through `PluginHost`, or stop claiming plugin parity with built-ins; verify a plugin can add each contribution without editing app code.
- [ ] `app/.../store/LocationsRepository.kt`: make the kind list ordered/overridable with collision checks; stop constructing new `RemoteProviders` per `records()` call and stop hand-building `saf://`/`ftp://`/`sftp://` roots.
- [ ] `opener/.../Archives.kt`: add bounded eviction and invalidation (mtime/size) to the process-wide archive cache; verify replacing a zip does not return a stale index and fds are released.
- [ ] `app/.../TerminalSheet.kt` + `app/.../agent/SessionManager.kt` + `app/.../ui/components/SessionManagerDialog.kt` + `app/.../util/ProcessHelper.kt`: replace `ssh://` string checks and the duplicated `SessionType.Ssh` fields with a terminal-profile service; verify SSH terminals, cwd and drops without scheme inspection in the UI.
- [ ] `agent/.../AgentFileAccess.kt` + `agent/.../SystemPrompt.kt`: replace `contains("://")` and the `"file"`/`container` scheme special cases with `Path` parsing; verify a local file named `a://b` is not misrouted.
- [ ] `daemon/.../DaemonManager.kt` + `container/.../ContainerPaths.kt`: expose `host`/`app` target ids as constants or a sealed type; verify no module compares raw `"host"`/`"app"` strings.
- [ ] `container/.../ContainerProvider.kt`: stop parsing provider inner paths with `Path.parse`, stop hand-building `container://` roots, reuse `ContainerManager`'s containers directory, and make `stat("/")` succeed before the first container exists.
- [ ] `app/.../model/LocationAddress.kt`: remove the second URI parser/builder; parse and format through `Path`; verify userinfo/IPv6/port spellings match core.
- [ ] `provider/.../SAFProvider.kt`: normalize indentation/comments, replace manual `saf://` strings, use the location title for the root entry name, and verify `resolveDoc` uses the correct DocumentFile API.
- [ ] `app/.../ui/util/FileOpen.kt`: make `shareEntries` resolve nodes instead of `FileEntry.path`; verify archive entries share and open without a pseudo `/docs/x` address.
- [ ] `app/.../render/HtmlDocument.kt`: resolve absolute `/...` references from the origin root and percent-decode segments; verify `/logo.png` in `/docs/page.html` loads the provider root asset.
- [ ] `app/.../render/FolderListing.kt`: replace the first-entry `realPath` heuristic with a folder/provider fact; verify an empty internal-storage folder still reports internal.
- [ ] `app/.../FileManagerApp.kt`: delete the dead `folderLabel` fallback that hand-builds addresses and checks `is Folder`.
- [ ] `app/.../FileManagerApp.kt` + `app/.../ui/components/FileDrawer.kt` + `app/.../TerminalSheet.kt` + `hub/.../HubServer.kt`: split the god objects (3261/1549/708/822 lines) into navigation, copy, dialog and terminal controllers; verify no behavior change.
- [ ] `app/.../render/Renderer.kt`: split the 20+ method `RenderHost` into navigation/file-action/home hosts; verify web, editor, folder and Home renderers keep their used callbacks.
- [ ] `proto/hub.proto`: split `Target.name` (exec id vs location root URI) into separate fields or a oneof; update hub/agent clients and verify `ListTargets` consumers.
- [ ] `agent/.../AgentTools.kt` + `agent/.../SystemPrompt.kt`: replace the five scattered tool-name switches with a registered `Tool` descriptor; verify definitions, execution, approval and prompt text stay in sync.
- [ ] `core/.../Folder.kt` + `opener/.../ArchiveFolder.kt`: deduplicate `describe()` and prefer `peek`/metadata; verify opening a remote file in the external viewer no longer downloads the whole body first.
- [ ] `app/.../render/FolderListing.kt` + `opener/.../ArchiveOpener.kt` + `container/.../ContainerProvider.kt` + `app/.../WebPagePolicy.kt`: add `Locale.ROOT` to every `lowercase()` used for sorting, extension and MIME comparison; verify Turkish locale.
- [ ] `core/.../Vfs.kt`: synchronize provider registration/unregistration and clear `default` when its provider is unregistered; verify plugin install/uninstall from a background thread.
- [ ] `core/.../Path.kt` + `app/.../FileManagerApp.kt`: make `parent()` and `navigate("..")` agree at a host root (`container://alpine`, `saf://hex`); verify Up reaches the scheme root.
- [ ] `core/.../NodeOps.kt`: stop treating every `Vfs.node` failure as "destination missing"; verify permission/transport errors surface instead of creating or overwriting a file.
- [ ] `agent/.../SystemPrompt.kt`: stop using the hub `ReadFileRequest` proto for a local `/etc/issue` read; use the agent file API.
