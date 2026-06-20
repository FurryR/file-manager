# PROJECT KNOWLEDGE BASE

**Generated:** 2026-06-15
**Stack:** Kotlin + Jetpack Compose (app) · Rust + Tokio (native daemon) · Protobuf (IPC)

## STRUCTURE

```
file-manager/
├── app/                 # Android app (Kotlin/Compose)
│   └── src/main/
│       ├── java/io/furryr/file/  # 22 files — all app logic
│       └── jniLibs/arm64-v8a/    # Prebuilt native binaries (file-daemon, proot)
├── native/              # Rust daemon binary (standalone, no NDK)
│   ├── src/main.rs      # Single-file implementation
│   ├── Cargo.toml
│   ├── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
│   └── build-proot.sh            # Cross-compile proot from source
├── proto/               # Protobuf IPC protocol
│   └── file_daemon.proto
└── build-android-aarch64.sh  # Cross-compile Rust → Android ARM64
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| UI screens | `FileManagerApp.kt` | Main composable, tabs, state |
| File listing | `FileListScreen.kt` | LazyColumn, pull-to-refresh |
| Settings | `SettingsActivity.kt` | Root/shell toggles, su path |
| File operations | `FileRepository.kt` | Thin wrappers → daemon |
| IPC daemon client | `FileDaemonClient.kt` | Socket connection, su/Shizuku |
| Native daemon | `native/src/main.rs` | File ops under root/shell |
| IPC protocol | `proto/file_daemon.proto` | Request/Response messages |

## CONVENTIONS

- **No DI framework** — `FileDaemonClient` is a singleton `object`. Manual wiring via `configure()`.
- **No ViewModel** — State lives in `FileManagerApp.kt` as `remember`/`mutableStateOf`. Single-activity.
- **All daemon I/O is blocking** — Kotlin wraps in `Dispatchers.IO`; Rust uses `current_thread` tokio.
- **Package `io.furryr.file`** — flat package, no subdirectories.

## ANTI-PATTERNS (THIS PROJECT)

- `as any` / `@ts-ignore` / `@ts-expect-error` — type safety violations
- `unsafe { libc::... }` — allowed (Rust side for abstract UNIX sockets)
- Silent catch (`catch {}`) — only allowed for Shizuku availability checks

## COMMANDS

```bash
./gradlew assembleDebug                          # Build APK
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
