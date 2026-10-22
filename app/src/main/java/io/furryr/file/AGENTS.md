# AGENTS.md — app/.../io/furryr/file/

**UI shell** — Compose rendering + tab/selection state only. File routing and
operations live in `:core` (`io.furryr.file.core.Vfs`); this module wires
platform services and calls the library.

## STRUCTURE

| Directory | Contents |
|-----------|----------|
| `.` (root) | `MainActivity.kt`, `FileManagerApp.kt`, `Constants.kt`, `Providers.kt` |
| `agent/` | Session/PTY bridge, terminal, container wizard, settings, path utils |
| `forward/` | `ForwardFileProvider` + per-process share-token signing |
| `model/` | `Models.kt`, `LocationAddress.kt`, `ErrorState.kt` |
| `ui/components/` | `BottomToolbar.kt`, `TopBarContent.kt`, `FileDrawer.kt`, `Dialogs.kt` |
| `ui/screens/` | `FileListScreen.kt`, `SettingsActivity.kt`, `LicensesScreen.kt` |
| `ui/theme/` | `FileManagerTheme.kt` (Material3 dark/light) |
| `ui/util/` | `Formatters.kt`, `FileIcons.kt`, `FileOpen.kt`, `OperationNotification.kt` |

> The VFS abstraction lives in `:core`; protocol providers (Local/FTP/SFTP/
> SAF/remote) live in `:provider`; containers and the single `ContainerProvider`
> live in `:container`; daemon IPC lives in `:daemon`; the hub lives in `:hub`;
> VirGL lives in `:virgl`. `Providers.kt` only wires platform services.

## WHERE TO LOOK

- **Add new file operation**: define proto message → `:daemon/FileService.kt` → `:core/Vfs.kt` helper → call from `FileManagerApp.kt`
- **Add UI toggle**: `SettingsActivity.kt` (prefs-based, `DaemonManager.reset()` on change)
- **Change IPC protocol**: `proto/file_daemon.proto` in `:daemon` (both sides need rebuild)
- **Selection logic**: `FileManagerApp.kt` lines 200-218 (`toggleSelection`, `rangeSelectTo`)
- **Terminal sessions**: `agent/SessionManager.kt` — session lifecycle, container session cleanup
- **Container management**: `:container/ContainerManager.kt` — create/list/exec/bind mounts
- **Container provider**: `:container/ContainerProvider.kt` — one provider for all containers
- **Container daemon launch**: `:container/ContainerDaemonLauncher.kt` — proot/chroot argv
- **Terminal panel**: `TerminalSheet.kt` — bottom sheet with terminals; `RuntimeForegroundService.kt` keeps sessions and container daemons alive
- **Exec/PTY**: `:daemon/process/ExecService.kt` — `CommandSpec` → `ProcessChannel` (one path for terminal + command exec)
- **Native daemon entry**: `:daemon/DaemonManager.kt` — starts `libfiledaemon.so` from `nativeLibraryDir`

## CONVENTIONS

- **State hoisting**: all state in `FileManagerApp.kt`, passed down as params/callbacks.
- **`:core` is `internal`-guarded** — UI touches only `DaemonManager`, `ExecService`,
  `FileService`, sources/models; never `DaemonConnection`/proto/sockets.
- **`Dispatchers.IO`** for daemon calls in coroutines.
- **Protobuf lite** — `com.google.protobuf:protobuf-javalite` (generated in `:core`).
- **No `enum` for screens** — uses `private enum class` in Activity files.
- **SessionManager singleton** — `object`, not DI. Manual wiring via `configure()`.
- **Native binaries as jniLibs** — `libfiledaemon.so` and `libproot.so` in `jniLibs/arm64-v8a/` here; `libvirglhost.so` ships with `:virgl`.

## ANTI-PATTERNS

- Do NOT add DI frameworks — explicit wiring is deliberate for this small scope.
- Silent catch (`catch {}`) — only allowed for Shizuku availability checks.
