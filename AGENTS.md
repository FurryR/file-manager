# PROJECT KNOWLEDGE BASE

**Generated:** 2026-06-15
**Stack:** Kotlin + Jetpack Compose (app) · Kotlin core (transport/exec) · Rust + Tokio (native daemon) · Protobuf (IPC)

## STRUCTURE

```
file-manager/
├── core/                # :core — communication layer, UI-independent
│   └── src/main/java/io/furryr/file/core/
│       ├── DaemonManager.kt      # Public facade: lifecycle, launcher SPI, services
│       ├── DaemonRegistry.kt     # run0 attach seam (endpoint enumeration)
│       ├── ipc/                  # INTERNAL: OsSocket, DaemonConnection, framing, proto
│       ├── process/              # CommandSpec + ProcessChannel + ExecService (unified PTY/exec)
│       ├── file/                 # FileService (daemon-backed file ops)
│       ├── source/               # FileSource (fd/handle/stream)
│       └── model/                # FileEntry, ProviderStat, CopyProgress, ExecResult
├── app/                 # Android app (Kotlin/Compose)
│   └── src/main/
│       ├── java/io/furryr/file/  # UI + providers + agent + app facades
│       └── jniLibs/arm64-v8a/    # Prebuilt native binaries (file-daemon, proot)
├── native/              # Rust daemon binary (standalone, no NDK)
│   ├── src/main.rs      # Single-file implementation
│   ├── Cargo.toml
│   ├── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
│   └── build-proot.sh            # Cross-compile proot from source
├── proto/               # Protobuf IPC protocol (generated into :core)
│   └── file_daemon.proto
└── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
```

## LAYERING (do not break)

- `:core` is the **communication layer**. It must not import app packages
  (`agent`/`provider`/`ui`/`model`/…) and knows **nothing about proot/chroot**.
- IPC internals (`core.ipc`), protobuf, and `DaemonConnection` are `internal` —
  the UI can only reach `DaemonManager`, `ExecService`, `FileService`, sources,
  models, and the `DaemonLauncher`/`DaemonRegistry` SPIs.
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
| Daemon facade | `core/.../DaemonManager.kt` | Lifecycle, targets, launcher SPI |
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
./gradlew :core:compileDebugKotlin               # Compile communication layer only
bash native/build-android-aarch64.sh             # Cross-compile Rust daemon
bash native/build-proot.sh                       # Cross-compile proot binary
adb install -r app/build/outputs/apk/debug/*.apk # Install
```

## NOTES

- Target SDK 28 avoids storage restrictions needed by root features.
- Native binaries (file-daemon, proot) are packaged as native libraries in
  `app/src/main/jniLibs/arm64-v8a/` and automatically extracted by Android
  to `nativeLibraryDir` at install time.
- Build scripts under `native/` copy the compiled binaries into that directory.
- Socket uses **abstract namespace** (UUID-based, no filesystem path) — works across UIDs.
- Daemon priority: `su` (root) > `Shizuku` (shell) > direct (app UID).
- **The daemon is multi-client and persistent** — it serves many concurrent
  connections and keeps running after a client disconnects (survives Android
  tombstoning). Shutdown is explicit via an `ExitRequest` (or a signal);
  `DaemonConnection` retries the same socket automatically after a drop.
- Client entry points live in `:core` (`DaemonManager.fileService/execService`).
- `DaemonRegistry`/`DaemonEndpoint` are the seam for a future run0-style
  "attach to a running daemon" mechanism; no such implementation ships yet.
