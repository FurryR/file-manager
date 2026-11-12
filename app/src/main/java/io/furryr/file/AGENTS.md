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
| `render/` | `FolderRenderer.kt`, `FolderContent.kt`, `RendererErrorView.kt`, `SortMode.kt`, Web/Home renderers |
| `settings/` | `PulseSettings.kt` — app-owned PulseAudio preference store |
| `store/` | Location stores + `LocationStores` + `LocationsRepository` (all persistence) |
| `ui/components/` | `BottomToolbar.kt`, `TopBarContent.kt`, `FileDrawer.kt`, `Dialogs.kt`, `ServiceUnavailableState.kt` |
| `ui/screens/` | `SettingsActivity.kt`, `LicensesScreen.kt` |
| `ui/theme/` | `FileManagerTheme.kt` (Material3 dark/light), `Color.kt` (palettes + AMOLED canvas), `PaletteStore.kt` |
| `ui/util/` | `Formatters.kt`, `FileIcons.kt`, `FileOpen.kt`, `OperationNotification.kt` |
| `editor/` | Tab-owned Sora text editor, text codec, TextMate grammars and container LSP |

> The VFS abstraction lives in `:core`; protocol providers (Local/FTP/SFTP/
> SAF/remote) live in `:provider`; containers and the single `ContainerProvider`
> live in `:container`; daemon IPC lives in `:daemon`; the hub lives in `:hub`.
> `Providers.kt` only wires platform services. All location persistence
> (SQLite, SharedPreferences, encrypted files) lives in `store/` and is passed
> to providers through the `LocationSources.kt` interfaces.

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
- **Add a location store**: implement a `LocationSources.kt` interface in `store/`, wire it through `LocationStores.init` and the matching provider `init()`

## CONVENTIONS

- **State hoisting**: all state in `FileManagerApp.kt`, passed down as params/callbacks.
- **`:core` is `internal`-guarded** — UI touches only `DaemonManager`, `ExecService`,
  `FileService`, sources/models; never `DaemonConnection`/proto/sockets.
- **`Dispatchers.IO`** for daemon calls in coroutines.
- **Protobuf lite** — `com.google.protobuf:protobuf-javalite` (generated in `:daemon`, exposed through its public facades).
- **No `enum` for screens** — uses `private enum class` in Activity files.
- **SessionManager singleton** — `object`, not DI. Manual wiring via `configure()`.
- **Native binaries as jniLibs** — `libfiledaemon.so`, `libproot.so` and `libpulsehost.so` in `jniLibs/arm64-v8a/` here.

## ANTI-PATTERNS

- Do NOT add DI frameworks — explicit wiring is deliberate for this small scope.
- Silent catch (`catch {}`) — only allowed for Shizuku availability checks.

## TODO - Bug checks

- [ ] `ui/components/FileDrawer.kt`: verify remote/SSH location tap closes the drawer immediately and the file list spinner (delayed) covers slow loads.
- [ ] `ui/components/FileDrawer.kt`: verify SSH host-key approval and connect-error dialogs still appear after the drawer closes, and that trusting a key navigates into the location.
- [ ] `util/ProcessHelper.kt` + `provider/.../SshTerminal.kt`: verify an SSH terminal survives sheet size changes and that dragging a file icon onto it inserts the quoted path instead of closing the session.
- [ ] `ui/screens/WaylandActivity.kt`: verify the viewer attaches its surface to the Smithay compositor, renders a container app, and forwards touch/keyboard input.
- [ ] `ui/screens/WaylandActivity.kt`: verify leaving and reopening the viewer keeps the Wayland socket and connected clients alive.
- [ ] `AndroidManifest.xml`: verify the merged manifest builds after dropping the wldroid `overrideLibrary` entry and that minSdk 26 is still accepted.
- [ ] `ui/screens/LicensesScreen.kt`: verify the license list renders with the Smithay/libxkbcommon entries and no removed wldroid entries.
- [ ] `editor/EditorRenderer.kt`: verify SAF, document, FTP, SFTP, SSH, AList and HTTP files open read-only and local/container files still save.
- [ ] `render/WebRenderer.kt` + `render/HtmlDocument.kt` + `FileManagerApp.kt`: verify live-page detection without the extension fallback, referer on link hits/shares, and `confirmPage` hand-back for non-pages.
- [ ] `forward/ForwardFileProvider.kt`: verify a shared live URL keeps its referer and archive entries still re-resolve through `Vfs.node`.
- [ ] `model/LocationAddress.kt` + `store/WebLocationStore.kt` + `ui/components/FileDrawer.kt` + `ui/components/drawer/DialogAddLocation.kt`: verify add-location for `ftp`, `sftp`, `ssh`, `alist`, `http` and `https`.
- [ ] `render/NodeAddress.kt` (renamed from `LocationAddress`): verify typed addresses, breadcrumbs and the editor parent address still format/parse.
- [ ] `HereNavigation.kt` + `FileManagerApp.kt` + `agent/SessionManager.kt` + `util/ProcessHelper.kt` + `TerminalSheet.kt`: verify here reveal, container root navigation and terminal container markers after the `ContainerUris` move.
- [ ] `app/src/main/java/io/furryr/file/render/FolderRenderer.kt` + `render/FolderContent.kt` + `render/RendererErrorView.kt` + `ui/components/ServiceUnavailableState.kt`: verify an unavailable SAF location shows the centered crying state, the launch button opens the target app, and returning after launch retries the listing.
- [ ] `ui/components/ServiceUnavailableState.kt`: verify authority→package resolution and the no-launcher fallback to application details on system providers and apps without a launcher activity.
- [ ] `ui/components/TopBarContent.kt`: verify the address suggestion popup still appears only while typing and hides when the anchor or rows are missing.
- [ ] `FileManagerApp.kt` + `ui/components/TopBarContent.kt`: verify local directory tabs show the used/total space summary again, while Home, Web and editor tabs keep their own caption and hide it.
- [ ] `render/SortMode.kt` + `ui/components/Dialogs.kt` + `FileManagerApp.kt`: verify the sort dialog still reads and applies the same modes after the package move.
- [ ] `res/drawable/ic_android_crying.xml` + `res/values*/strings.xml`: verify the icon renders at 72dp and all four locales resolve the new strings.
- [ ] `app/src/main/java/io/furryr/file/AGENTS.md`: verify the updated structure table matches the final render/ui layout.
- [ ] `render/DirectoryRenderer.kt`: verify refresh, filter and sort menu actions still fire from the folder renderer overflow menu.
- [ ] `Providers.kt`: verify directory tabs still restore correctly after tab switches and tab closes now that directory sessions are created only by the shell.
- [ ] `render/Renderer.kt` + `FileManagerApp.kt`: verify generic session metadata (`address`, `webAddress`, `title`, `isLive`, `isOverlay`, `parent`, `terminalPath`, `suppressBookmark`) across web, editor, folder and Home tabs, including history stops, bookmarks, Up and terminal cwd.
- [ ] `OpenTable.kt` + `FileManagerApp.kt`: verify `AppOpenOutcome.Renderer` opens pages, editors and falls back to the platform viewer without concrete renderer checks.
- [ ] `render/WebRenderer.kt`: verify `adopt` keeps a live WebView across live-page navigation, `retrace` still restores cached history, and confirmation hooks still hide the cover for optimistic non-page hand-back.
- [ ] `editor/EditorRenderer.kt`: verify dirty leave prompts, `terminalPath`, bookmark suppression and Back overlay behaviour after the metadata overrides.
- [ ] `ui/components/ServiceUnavailableState.kt` + `res/values*/strings.xml`: verify the crying icon, app-label title, hint and launch button for a stopped SAF provider app in all four locales.
- [ ] `FileManagerApp.kt`: verify open tabs and the selected tab survive a language change (activity recreate), and process death still starts from Home.
- [ ] `render/WebRenderer.kt` + `FileManagerApp.kt` + `ui/components/HistorySheet.kt`: verify a stored HTML page (local, SSH, FTP, archive) gets a file icon in history, Back returns to its folder with the list visible, and Up leaves the page; also verify a live https page keeps its blank reload slot and still has no Up.
- [ ] `render/FolderListing.kt` + `render/FolderContent.kt` + `render/FolderRenderer.kt` + `FileManagerApp.kt`: verify tab switches do not re-list a loaded folder, direct refresh still re-lists and animates, and a refresh keeps the current path, sort and highlight selection.
- [ ] `FileManagerApp.kt` + `render/DirectoryRenderer.kt`: verify the fallback directory menu and here-navigation highlight still fire after removing the shell refresh/animation tokens.
- [ ] `FileManagerApp.kt`: verify the overflow menu is renderer-only in a transitional stop with no live renderer, and that a folder tab still shows refresh/filter/sort.
- [ ] `render/FolderListing.kt` + `render/FolderRenderer.kt` + `render/FolderContent.kt`: verify sort mode/reverse live per session, switching tabs keeps each tab's sort, and confirm in the renderer sort dialog re-lists without a shell round trip.
- [ ] `render/SortDialog.kt`: verify the moved dialog reads/applies the same modes and renders in all four locales.
- [ ] `render/HomeRendererSession.kt` + `render/FolderRenderer.kt` + `FileManagerApp.kt`: verify the shared filter field reads/writes the active session, Back closes it, and Home and folder tabs keep independent filter text.
- [ ] `render/Renderer.kt` + `FileManagerApp.kt`: verify the title space summary still comes from the active folder session and no shell-owned directory info remains; confirm removing the unused InfoDialog block loses no action.
- [ ] `render/Renderer.kt` + `render/FilterTitle.kt`: verify Home and Folder provide a title slot only while filtering, return to the shell's default path title after close, keep path suggestions from the shell, and leave web/editor titles untouched.
- [ ] `render/HomeRendererSession.kt` + `render/FolderRenderer.kt`: verify filter text and open state stay per session across tab switches, and the menu toggle still opens the title field.
- [ ] `ui/util/FileIcons.kt`: verify icons and tints are unchanged for image/video/audio/pdf/archive/code/text files and an APK still gets the generic file icon.
- [ ] `editor/EditorLanguages.kt`: verify text detection (including special names), the language picker, plugin `registerLanguage`, and JSON/YAML/HTML grammars after the `ContentTypes` switch.
- [ ] `Providers.kt`: verify a failing plugin is logged and skipped while built-in providers, openers and renderers still install.
- [ ] `store/LocationsRepository.kt`: verify built-in drawer entries are unchanged and a registered location source appears, while a throwing source is skipped.
- [ ] `store/LocationKind.kt` + `store/LocationsRepository.kt`: verify one kind table yields local/SAF/FTP/SFTP/remote entries in the same order and rename/delete actions still update the drawer.
- [ ] `store/JsonLocationStore.kt` + `store/LocalLocationStore.kt` + `store/WebLocationStore.kt`: verify add/rename/delete/reorder and legacy preference rows missing `sortOrder`.
- [ ] `store/ServerDatabase.kt` + `store/FtpDatabase.kt` + `store/SftpDatabase.kt`: verify insert, rename, host update, reorder and default ports on both existing and fresh databases.

