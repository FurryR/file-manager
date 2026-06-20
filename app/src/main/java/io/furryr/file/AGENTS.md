# AGENTS.md — app/.../io/furryr/file/

**59 files** — Kotlin + Compose UI in a flat package with subpackages for agent/daemon/provider.

## STRUCTURE

| Directory | Contents |
|-----------|----------|
| `.` (root) | `MainActivity.kt`, `FileManagerApp.kt`, `Constants.kt`, `FileRepository.kt` |
| `agent/` | Session, container, PTY, terminal, AI, wizard, distro, proot — all agent/container logic |
| `daemon/` | `FileDaemonClient.kt`, `DaemonLauncher.kt`, `DaemonConnection.kt`, `DaemonProtocol.kt`, `RootUtils.kt`, `FileErrors.kt` |
| `model/` | `Models.kt`, `ErrorState.kt` |
| `provider/` | SAF, container, daemon, document file providers |
| `copy/` | `FileSource.kt`, `CopyProgress.kt` |
| `ui/components/` | `BottomToolbar.kt`, `TopBarContent.kt`, `FileDrawer.kt`, `Dialogs.kt` |
| `ui/screens/` | `FileListScreen.kt`, `SettingsActivity.kt`, `AgentSheet.kt`, `LicensesScreen.kt` |
| `ui/theme/` | `FileManagerTheme.kt` (Material3 dark/light) |
| `ui/util/` | `Formatters.kt`, `FileIcons.kt`, `FileOpen.kt`, `OperationNotification.kt` |

## WHERE TO LOOK

- **Add new file operation**: define proto message → `FileDaemonClient.kt` method → `FileRepository.kt` helper → call from `FileManagerApp.kt`
- **Add UI toggle**: `SettingsActivity.kt` (prefs-based, reset daemon on change)
- **Change IPC protocol**: `proto/file_daemon.proto` (both sides need rebuild)
- **Selection logic**: `FileManagerApp.kt` lines 200-218 (`toggleSelection`, `rangeSelectTo`)
- **Terminal sessions**: `agent/SessionManager.kt` — session lifecycle, container session cleanup
- **Container management**: `agent/ContainerManager.kt` — process tracking, kill
- **Agent/AI panel**: `AgentSheet.kt` — bottom sheet with drawer overlay
- **Native daemon entry**: `daemon/DaemonLauncher.kt` — starts `libfiledaemon.so` from `nativeLibraryDir`

## CONVENTIONS

- **State hoisting**: all state in `FileManagerApp.kt`, passed down as params/callbacks.
- **`@Synchronized` on `FileDaemonClient`** — all daemon methods are atomic.
- **`Dispatchers.IO`** for daemon calls in coroutines.
- **Protobuf lite** — `com.google.protobuf:protobuf-javalite`.
- **No `enum` for screens** — uses `private enum class` in Activity files.
- **SessionManager singleton** — `object`, not DI. Manual wiring via `configure()`.
- **Native binaries as jniLibs** — `libfiledaemon.so` and `libproot.so` in `jniLibs/arm64-v8a/`.

## ANTI-PATTERNS

- Do NOT add DI frameworks — explicit wiring is deliberate for this small scope.
- Silent catch (`catch {}`) — only allowed for Shizuku availability checks.
