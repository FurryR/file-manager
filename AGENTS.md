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
│       ├── UdsTransport.kt # reader coroutine + write lock + optional reconnect
│       └── UdsClient.kt / UdsServer.kt / Frame.kt / UdsEndpoint.kt / ReconnectPolicy.kt
├── core/                # :core — daemon protocol layer over :uds.Transport
│   └── src/main/java/io/furryr/file/core/
│       ├── DaemonManager.kt      # Public facade: lifecycle, launcher SPI, hub, services
│       ├── DaemonRegistry.kt     # endpoint enumeration seam
│       ├── ipc/                  # INTERNAL: DaemonConnection (proto codec + id correlation)
│       ├── hub/                  # INTERNAL: HubServer (attach handshake + transparent relay)
│       ├── process/              # CommandSpec + ProcessChannel + ExecService (unified PTY/exec)
│       ├── file/                 # FileService (daemon-backed file ops)
│       ├── source/               # FileSource (fd/handle/stream)
│       └── model/                # FileEntry, ProviderStat, CopyProgress, CopyHandle, ExecResult
├── app/                 # Android app (Kotlin/Compose)
│   └── src/main/
│       ├── java/io/furryr/file/  # UI + providers + agent + app facades
│       └── jniLibs/arm64-v8a/    # Prebuilt native binaries (libfiledaemon.so, libproot.so)
├── native/              # Rust daemon binary (standalone, no NDK)
│   ├── src/main.rs      # Accept loop + per-connection state
│   ├── src/handlers.rs  # Command dispatch (incl. CancelRequest)
│   ├── Cargo.toml
│   ├── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
│   └── build-proot.sh            # Cross-compile proot from source
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
| run0 hub | `core/.../hub/HubServer.kt` | Attach handshake + transparent relay |
| Hub protocol | `proto/hub.proto` | Control-plane handshake |
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
- Native executables are packaged as **`lib*.so` under `jniLibs/`** because the
  Android installer only auto-extracts `lib/<abi>/lib*.so` to `nativeLibraryDir`
  (`debuggable` apps are the sole exception). Renaming them to `file-daemon` /
  `proot` would drop them from the APK (AGP strips non-`.so` from jniLibs) and
  break extraction. To ship original names you must extract them yourself at
  runtime (e.g. from `assets/`).
- Build scripts under `native/` copy the compiled binaries into `jniLibs/`.
- Socket uses **abstract namespace** (UUID-based, no filesystem path) — works across UIDs.
- Daemon priority: `su` (root) > `Shizuku` (shell) > direct (app UID).
- **The daemon is multi-client** — it serves many concurrent connections, but
  per-connection state (cancellable in-flight requests) is *not* shared, and
  long-lived resources (handles, PTYs) are daemon-global. Shutdown is explicit
  via an `ExitRequest` (or a signal); `DaemonConnection` re-attaches via
  `:uds.ReconnectPolicy` after a transient drop.
- Client entry points live in `:core` (`DaemonManager.fileService/execService`).
- `DaemonManager.startHub()` runs the run0-style communication hub: a stable
  UDS server that enumerates live endpoints (`liveEndpoints()`) and, on attach,
  relays frames (fds included) to the chosen daemon without parsing its proto.
  Peer-credential auth is not implemented yet — the hub is same-device trusted.
