# AGENTS.md — app/.../io/furryr/file/

**61 files** — Kotlin + Compose UI in a flat package with subpackages for agent/daemon/provider.

## STRUCTURE

| Directory | Contents |
|-----------|----------|
| `.` (root) | `MainActivity.kt`, `FileManagerApp.kt`, `Constants.kt`, `FileRepository.kt`, `LocationsRepository.kt` |
| `agent/` | Session, container, PTY bridge, terminal, wizard, distro, proot — all agent/container logic |
| `model/` | `Models.kt`, `Locations.kt`, `ErrorState.kt` |
| `provider/` | SAF, container, daemon, document, forward, FTP, SFTP file providers + location DBs |
| `ui/components/` | `BottomToolbar.kt`, `TopBarContent.kt`, `FileDrawer.kt`, `Dialogs.kt` |
| `ui/screens/` | `FileListScreen.kt`, `SettingsActivity.kt`, `LicensesScreen.kt` |
| `ui/theme/` | `FileManagerTheme.kt` (Material3 dark/light) |
| `ui/util/` | `Formatters.kt`, `FileIcons.kt`, `FileOpen.kt`, `OperationNotification.kt` |

> The proto-agnostic UDS transport lives in the `:uds` module
> (`io.furryr.file.uds.*`); the daemon protocol, sources and the unified exec
> API live in `:core` (`io.furryr.file.core.*`). The old `daemon/` and `copy/`
> packages were removed.

## WHERE TO LOOK

- **Add new file operation**: define proto message → `:core/FileService.kt` → `FileRepository.kt` helper → call from `FileManagerApp.kt`
- **Add UI toggle**: `SettingsActivity.kt` (prefs-based, `DaemonManager.reset()` on change)
- **Change IPC protocol**: `proto/file_daemon.proto` (both sides need rebuild)
- **Selection logic**: `FileManagerApp.kt` lines 200-218 (`toggleSelection`, `rangeSelectTo`)
- **Terminal sessions**: `agent/SessionManager.kt` — session lifecycle, container session cleanup
- **Container management**: `agent/ContainerManager.kt` — process tracking, kill
- **Container daemon launch**: `agent/ContainerDaemonLauncher.kt` — proot/chroot argv (app-owned SPI)
- **Terminal panel**: `TerminalSheet.kt` — bottom sheet with terminals; `RuntimeForegroundService.kt` keeps sessions and container daemons alive
- **Exec/PTY**: `:core/process/ExecService.kt` — `CommandSpec` → `ProcessChannel` (one path for terminal + command exec)
- **Native daemon entry**: `:core/DaemonManager.kt` — starts `libfiledaemon.so` from `nativeLibraryDir`

## CONVENTIONS

- **State hoisting**: all state in `FileManagerApp.kt`, passed down as params/callbacks.
- **`:core` is `internal`-guarded** — UI touches only `DaemonManager`, `ExecService`,
  `FileService`, sources/models; never `DaemonConnection`/proto/sockets.
- **`Dispatchers.IO`** for daemon calls in coroutines.
- **Protobuf lite** — `com.google.protobuf:protobuf-javalite` (generated in `:core`).
- **No `enum` for screens** — uses `private enum class` in Activity files.
- **SessionManager singleton** — `object`, not DI. Manual wiring via `configure()`.
- **Native binaries as jniLibs** — `libfiledaemon.so` and `libproot.so` in `jniLibs/arm64-v8a/`.

## ANTI-PATTERNS

- Do NOT add DI frameworks — explicit wiring is deliberate for this small scope.
- Silent catch (`catch {}`) — only allowed for Shizuku availability checks.
