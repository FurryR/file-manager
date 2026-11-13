# AGENTS.md — app/.../io/furryr/file/

**UI shell** — Compose rendering + tab/selection state only. File routing and
operations live in `:core` (`io.furryr.file.core.Vfs`); this module wires
platform services and calls the library.

## STRUCTURE

| Directory | Contents |
|-----------|----------|
| `.` (root) | `MainActivity.kt`, `FileManagerApp.kt`, `Constants.kt`, `Providers.kt` |
| `agent/` | Agent settings (MCP/model/provider), no terminal code |
| `terminal/` | Terminal subsystem: `Terminal` tab owner, sheet, PTY `SessionManager`, Termux view/input, tab implementations, DevTools |
| `forward/` | `ForwardFileProvider` + per-process share-token signing |
| `model/` | `Models.kt`, `LocationAddress.kt`, `ErrorState.kt` |
| `render/` | `FolderRenderer.kt`, `FolderContent.kt`, `RendererErrorView.kt`, `SortMode.kt`, Web/Home renderers |
| `shell/` | Shell types, process-scoped tab store, clipboard controller, platform permission helpers |
| `ui/shell/` | Controlled tab strip, top/bottom bars, content host, drawer host and overlay dialogs |
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
- **Terminal sessions**: `terminal/SessionManager.kt` — session lifecycle, container session cleanup
- **Container management**: `:container/ContainerManager.kt` — create/list/exec/bind mounts
- **Container provider**: `:container/ContainerProvider.kt` — one provider for all containers
- **Container daemon launch**: `:container/ContainerDaemonLauncher.kt` — proot/chroot argv
- **Terminal panel**: `terminal/TerminalSheet.kt` — bottom sheet over `terminal/Terminal.kt`; `RuntimeForegroundService.kt` keeps sessions and container daemons alive
- **Exec/PTY**: `:daemon/process/ExecService.kt` — `CommandSpec` → `ProcessChannel` (one path for terminal + command exec)
- **Native daemon entry**: `:daemon/DaemonManager.kt` — starts `libfiledaemon.so` from `nativeLibraryDir`
- **Add a location store**: implement a `LocationSources.kt` interface in `store/`, wire it through `LocationStores.init` and the matching provider `init()`

## CONVENTIONS

- **State hoisting**: all state in `:app`, passed down as params/callbacks.
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

- [ ] `render/FolderSessionRenderer.kt` + `render/HomeSessionRenderer.kt` + `render/FolderRenderer.kt` + `render/HomeRendererSession.kt`: verify re-entering a filtered folder/Home closes the filter before commit and no stale filter or row entrance animation survives a tab switch.
- [ ] `ui/components/FileDrawer.kt`: verify the drawer title row, theme/menu buttons and divider stay fixed while only the group list below scrolls, including collapsed groups and sort mode.
- [ ] `OpenTable.kt`: verify an extensionless text file opens in the editor (no facts supplied by the row) while an extensionless binary still goes to the platform viewer.
- [ ] `FileManagerApp.kt`: verify directory rows, link hits, typed addresses, home rows and history restore still open after `openNode`/`openResolvedNodeFn` were merged into `openVFile`.
- [ ] `terminal/TerminalInputState.kt` + `terminal/TerminalExtraKeys.kt`: verify Ctrl+Enter sends LF, Alt+Enter sends ESC+CR, Ctrl+Alt+Enter sends ESC+LF, and modifier+arrow/letter keys still carry their meta state.
- [ ] `terminal/TerminalExtraKeys.kt` + `terminal/TerminalInputState.kt`: verify the up-swipe summons the IME on every terminal tab after switching, not only the first, and an inactive tab no longer keeps a reference to the shared input wrapper.
- [ ] `terminal/TermuxTerminalTab.kt` + `res/values*/strings.xml`: verify an SSH entry under the same connection inserts its remote path (no double-slash rejection), a path from another SSH connection shows the localized rejection, and no terminal drop/creation message falls back to English.
- [ ] `FileManagerApp.kt` + `ui/screens/HomeScreen.kt`: verify the top strip stays visible while a copy/move payload is frozen, and the merged recent list keeps HomeStore visit order.
- [ ] `FileManagerApp.kt`: verify tapping a Home file/web entry shows the opening overlay after 300 ms, Cancel aborts the resolve, and a folder row still shows the directory spinner.
- [ ] `FileManagerApp.kt`: verify the path-suggestion scrim covers tabs, content and the bottom toolbar but leaves the path field and popup interactive, including AMOLED dark mode and after rotation.
- [ ] `res/values*/strings.xml`: verify `home_recent` renders in all four locales.
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
- [ ] `HereNavigation.kt` + `FileManagerApp.kt` + `terminal/SessionManager.kt` + `util/ProcessHelper.kt` + `terminal/TerminalSheet.kt`: verify here reveal, container root navigation and terminal container markers after the `ContainerUris` move.
- [ ] `app/src/main/java/io/furryr/file/render/FolderRenderer.kt` + `render/FolderContent.kt` + `render/RendererErrorView.kt` + `ui/components/ServiceUnavailableState.kt`: verify an unavailable SAF location shows the centered unavailable state, the launch button opens the target app, and returning after launch retries the listing.
- [ ] `ui/components/ServiceUnavailableState.kt`: verify authority→package resolution and the no-launcher fallback to application details on system providers and apps without a launcher activity.
- [ ] `ui/components/TopBarContent.kt`: verify the address suggestion popup still appears only while typing and hides when the anchor or rows are missing.
- [ ] `FileManagerApp.kt` + `ui/components/TopBarContent.kt`: verify local directory tabs show the used/total space summary again, while Home, Web and editor tabs keep their own caption and hide it.
- [ ] `render/SortMode.kt` + `ui/components/Dialogs.kt` + `FileManagerApp.kt`: verify the sort dialog still reads and applies the same modes after the package move.
- [ ] `ui/components/ServiceUnavailableState.kt` + `res/values*/strings.xml`: verify the Material Android icon renders at 72dp with the surface-variant tint and all four locales resolve the new strings.
- [ ] `app/src/main/java/io/furryr/file/AGENTS.md`: verify the updated structure table matches the final render/ui layout.
- [ ] `render/DirectoryRenderer.kt`: verify refresh, filter and sort menu actions still fire from the folder renderer overflow menu.
- [ ] `Providers.kt`: verify directory tabs still restore correctly after tab switches and tab closes now that directory sessions are created only by the shell.
- [ ] `render/Renderer.kt` + `FileManagerApp.kt`: verify generic page metadata (`address`, `webAddress`, `title`, `isLive`, `isOverlay`, `parent`, `terminalPath`, `suppressBookmark`) across web, editor, folder and Home tabs, including pages, bookmarks, Up and terminal cwd.
- [ ] `OpenTable.kt` + `FileManagerApp.kt`: verify `AppOpenOutcome.Renderer` opens pages, editors and falls back to the platform viewer without concrete renderer checks.
- [ ] `render/WebRenderer.kt`: verify `adopt` keeps a live WebView across live-page navigation, `retrace` still restores cached history, and confirmation hooks still hide the cover for optimistic non-page hand-back.
- [ ] `editor/EditorRenderer.kt`: verify dirty leave prompts, `terminalPath`, bookmark suppression and Back overlay behaviour after the metadata overrides.
- [ ] `ui/components/ServiceUnavailableState.kt` + `res/values*/strings.xml`: verify the crying icon, app-label title, hint and launch button for a stopped SAF provider app in all four locales.
- [ ] `FileManagerApp.kt`: verify open tabs and the selected tab survive a language change (activity recreate), and process death still starts from Home.
- [ ] `render/WebRenderer.kt` + `FileManagerApp.kt` + `ui/components/PageSheet.kt`: verify a stored HTML page (local, SSH, FTP, archive) gets a file icon in the page panel, Back returns to its folder with the list visible, and Up leaves the page; also verify a live https page keeps its blank reload slot and still has no Up.
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
- [ ] `render/FolderRenderer.kt` + `FileManagerApp.kt`: verify the folder overflow menu shows the bookmark row, adds/removes the current directory, and Home lists it.
- [ ] `FileManagerApp.kt`: verify `VFolder.addressable` replaces every old `is Folder` branch for local, container, archive, SAF, FTP, SFTP, SSH, AList and HTTP tabs.
- [ ] `OpenTable.kt`: verify a failing renderer reports `AppOpenOutcome.Failure` and is not silently handed to the platform viewer.
- [ ] `render/HtmlDocument.kt`: verify an absolute `/logo.png` in `/docs/page.html` resolves from the page origin root, including inside a zip and with percent-encoded names.
- [ ] `render/FolderListing.kt`: verify the internal-storage flag comes from the folder path and empty internal folders still report internal.
- [ ] `model/LocationAddress.kt`: verify add-location parse/format for userinfo, IPv6 and ports still matches core `Path`.
- [ ] `ui/components/drawer/RemoteProtocols.kt` + `DialogAddLocation.kt` + `FileDrawer.kt`: verify FTP/SFTP/SSH/AList/http(s) add flows and that an unknown plugin scheme reports invalid instead of becoming a local path.
- [ ] `terminal/SessionManager.kt` + `terminal/TerminalSheet.kt` + `terminal/Terminal.kt` + `ui/components/SessionManagerDialog.kt` + `util/ProcessHelper.kt`: verify SSH sessions, labels, cwd drops and icons after the profile indirection.
- [ ] `ui/util/FileOpen.kt` + `opener/.../ArchiveFolder.kt`: verify multi-select share resolves archive entries and live nodes through `Vfs`.
- [ ] `render/Renderer.kt`: verify NavigationHost, FileActionHost and HomeHost together expose every callback used by folder, web, editor and Home renderers.
- [ ] `store/LocationsRepository.kt`: verify plugin location-kind collision checks and that remote provider lookup no longer rebuilds `RemoteProviders`.
- [ ] `terminal/TerminalExtraKeys.kt` + `terminal/TerminalSheet.kt` + `terminal/Terminal.kt`: verify extra-key modifiers/enter/swipe and tab labels after moving the composables out of the sheet.
- [ ] `render/RendererMenuItemRow.kt` + `FileManagerApp.kt`: verify renderer submenus, checkbox rows and menu clicks after the row composable move.
- [ ] `ui/components/drawer/DrawerDialogs.kt` + `FileDrawer.kt`: verify every drawer rename/delete/add dialog, SSH host-key approval and add-location flow after the `DrawerDialogState` move.
- [ ] `hub/.../HubAgentOps.kt` + `hub/.../HubExecOps.kt` + `HubServer.kt`: verify agent attach/prompt/cancel/approval/MCP, exec/PTY wait/resize/signal/close, hello and target listing over a live hub connection.
- [ ] `terminal/TerminalSheet.kt`: verify terminal tab create/switch/close, active-id fallback after closing a middle tab, sheet drag heights, drawer swipe and tab toasts.
- [ ] `terminal/Terminal.kt`: verify tabs and the active id survive activity recreate, a finished session prunes only its own tab, and DevTools is dropped when its page closes.
- [ ] `terminal/TerminalTabUi.kt`: verify drawer rows/labels and the empty state after the tab split.
- [ ] `terminal/TermuxTerminalTab.kt`: verify `terminalSpecFor` labels for local, container and SSH addresses and the retry path after a failed spawn.
- [ ] `terminal/TerminalTab.kt` + `terminal/TermuxTerminalTab.kt`: verify per-tab creation/retry, error isolation between tabs, title updates, session finish, dropped paths, IME adjustResize restore, and that a tab survives sheet hide and activity recreate.
- [ ] `render/Renderer.kt`: verify `createTerminalTab()` for folder/editor stops and null for web/Home.
- [ ] `FileManagerApp.kt`: verify opening the terminal uses the active renderer spec, web/Home tabs create no terminal tab, and the sheet no longer changes the window soft-input mode when hidden.
- [ ] `terminal/SessionManager.kt`: verify `sessionIdOf` maps restored Termux sessions after an activity recreate.
- [ ] `res/values*/strings.xml`: verify `terminal_retry` resolves in all four locales.
- [ ] `terminal/WebDevToolsTab.kt` + `render/WebRenderer.kt`: verify the web tab terminal action opens a DevTools tab, console logs and JS results render, clear/reload work, a closed page shows the read-only state, no PTY is created, and the shell prunes the tab when the page closes.
- [ ] `MainActivity.kt`: verify `chrome://inspect` over adb lists the WebView and the target disappears with the process.
- [ ] `terminal/TerminalSheet.kt` + `terminal/Terminal.kt`: verify the terminal tab list is independent of file tabs, survives file-tab switches, and is not rebuilt from `SessionManager`.
- [ ] `res/values*/strings.xml`: verify the six `devtools_*` strings resolve in all four locales.
- [ ] `FileManagerApp.kt` + `render/RendererBase.kt`: verify pending renderers stay out of pages/history, the 300ms loading overlay and its Cancel button, and `commit` only after `enter` finishes.
- [ ] `render/RendererBase.kt`: verify each renderer's `enter` and the Composable content lambda committed by `host.commit`.
- [ ] `render/WebRenderer.kt` + `FileManagerApp.kt`: verify pending WebView loading after commit, optimistic hand-back, and that `adopt` is not used; restore WebView reuse later.
- [ ] `render/FolderRenderer.kt` + `FileManagerApp.kt`: verify folder/error commit and cancellation from the shell overlay.
- [ ] `render/ExternalRenderer.kt`: verify external hand-off shows the chooser without replacing the current page, and Cancel/close clears only the pending renderer.
- [ ] `render/*SessionRenderer.kt` + `FileManagerApp.kt`: verify `enter` timing/cancellation drives the 300ms transition and that an immediate `commit` does not blank the outgoing page.
- [ ] `render/Renderer.kt` + `render/RendererBase.kt`: verify the `RendererContext` rename, nullable `terminalFactory` on Home/folder/editor/web, and that the toolbar terminal action matches the actual factory.
- [ ] `FileManagerApp.kt` + `editor/EditorRenderer.kt`: verify closing a background dirty editor tab prompts, Save/Discard/Cancel all run, a conflict calls `focus()` and shows the editor dialog, and a dirty editor page in history is guarded too.
- [ ] `FileManagerApp.kt`: verify `focus()` selects the tab, moves to the page, cancels its leave prompt, and that closing by id survives tab reorder while a prompt is open.
- [ ] `terminal/TerminalSheet.kt`: verify existing terminal tabs stay reachable when `newTerminal` is null and the create button is absent.
- [ ] `terminal/TerminalViewComposable.kt` + `terminal/TermuxTerminalTab.kt`: verify each tab owns one `TerminalInputState` and one AndroidView, `bind`/`unbind` run on every attach/release (including switching back to a tab after creating another), the extra keys and IME work on the newly active tab, and no view or modifier state leaks between tabs.
- [ ] `terminal/TerminalInputState.kt` + `terminal/TerminalViewComposable.kt`: verify `requestIme()` reaches the bound wrapper on every tab (tap and extra-key swipe), the input connection follows `bindState`, and typing/modifiers still target the active session after switching.
- [ ] `FileManagerApp.kt` + `ui/components/BottomToolbar.kt`: verify Up availability from `currentCanGoParent()` and terminal availability from `terminalFactory` on folder, editor, web, Home and no-renderer tabs.
- [ ] `render/Renderer.kt` + `render/RendererBase.kt` + `render/*SessionRenderer.kt` + `FileManagerApp.kt`: verify `enter` runs on every tab activation, a re-show does not append history or reload a committed folder, and `leave` still clears folder menus/selection on tab switch.
- [ ] `FileManagerApp.kt`: verify page-sheet moves, `focus()`, pending commits and live WebView navigation still show the correct renderer without the removed `onActivate` calls.
- [ ] `render/FolderSessionRenderer.kt` + `render/FolderRenderer.kt`: verify the copy/move selection lock survives tab switches and returning to a tab no longer double-locks or flickers selection.
- [ ] `terminal/Terminal.kt` + `terminal/TerminalTab.kt` + `terminal/TermuxTerminalTab.kt` + `terminal/WebDevToolsTab.kt`: verify tab open/switch/close enter-leave ordering, switching away during connect does not kill the PTY, retry after failure reconnects, and DevTools still commits its body.
- [ ] `terminal/TerminalSheet.kt`: verify the committed content appears on open/switch, the spinner/error states show before a session is ready, and hiding/showing the sheet re-binds the active tab.
- [ ] `render/Renderer.kt` + `render/FolderRenderer.kt` + `render/WebRenderer.kt` + `editor/EditorRenderer.kt` + `FileManagerApp.kt`: verify the folder and web overflow Refresh rows still fire after deleting the generic `refresh()`/`canRefresh` path, and Home/Editor still show no Refresh row.
- [ ] `terminal/TerminalInputState.kt` + `terminal/TerminalViewComposable.kt`: verify `leave()` clears the surface/focus/IME request, `bindState` restarts the input mode, and no modifier or input connection leaks across terminal tabs.
- [ ] `terminal/TerminalViewComposable.kt` + `terminal/TerminalSheet.kt`: verify the movable terminal surface keeps the IME open across PTY tabs, rebinds the new session/state, keeps typing on the active tab, and still releases focus/input when switching to DevTools, hiding the sheet or closing the last tab.
- [ ] `terminal/TerminalViewComposable.kt` + `terminal/TerminalInputState.kt`: verify every terminal tab accepts IME text after switching (not only the first), the connecting spinner no longer disposes the shared wrapper, focus/IME survive a tab switch, and extra keys still target the active session.
- [ ] `FileManagerApp.kt` + `render/HomeSessionRenderer.kt` + `render/HomeRendererSession.kt` + `render/RendererSessions.kt`: verify the initial Home tab shows the localized title/path immediately (no `home://` flash), the tab label is localized after a language change, and new Home tabs/here navigation still render.
- [ ] `terminal/TerminalTab.kt` + `terminal/Terminal.kt` + `terminal/TerminalSheet.kt` + `terminal/TermuxTerminalTab.kt` + `render/Renderer.kt` + `render/WebRenderer.kt`: verify the extra-key menu opens the sheet drawer on every tab (including after activity recreate), and `TerminalFactory` callers still create local/DevTools tabs.
- [ ] `terminal/TerminalInputState.kt`: verify Ctrl+IME Enter sends LF both as an Enter key event and as committed newline text, Alt+Enter sends ESC+CR, and plain IME Enter still sends CR.
- [ ] `ui/shell/FileTabStrip.kt` + `FileManagerApp.kt`: verify tab select/close/drag-reorder, the add button, long-press session manager, and the narrow-screen `ScrollableTabRow` fallback still behave after the tab strip extraction.
- [ ] `ui/shell/ShellOverlays.kt` + `FileManagerApp.kt`: verify page sheet rows hide Home, terminal sheet creation still follows `terminalFactory`, session manager open/close/reorder, container error and entry info dialogs still show and dismiss.
- [ ] `shell/ShellTypes.kt` + `shell/PlatformPermissions.kt`: verify tabs survive activity recreate after the `FileTabs` move and the storage permission launch still runs on first composition.
- [ ] `ui/shell/FileDrawerHost.kt` + `FileManagerApp.kt`: verify every drawer flow (home/root/container, container create/rename/delete/kill, local/SAF/FTP/SFTP/remote/web CRUD and reorder, settings, add SAF) still navigates, closes the drawer and refreshes.
- [ ] `ui/shell/FileOperationDialogs.kt` + `FileManagerApp.kt`: verify delete, single rename and regex batch rename still toast, clear selection, refresh and close the dialog on success and failure.
- [ ] `ui/shell/ShellTopBar.kt` + `ui/shell/ShellBottomBar.kt` + `ui/shell/ShellContentHost.kt` + `ui/shell/ShellOverlays.kt`: verify progress strips, renderer overflow/submenus, settings/exit guard, pending renderer cover and Cancel, page/terminal sheets, operation progress, leave prompt and the path-edit scrim.
- [ ] `shell/ClipboardController.kt` + `FileManagerApp.kt`: verify copy/move progress, speed sampling, cancel, success toast and clipboard clear still run through the controller, and a second paste cancels the first without clearing the new job.

