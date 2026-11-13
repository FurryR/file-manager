# AGENTS.md — native/

**Rust workspace** — file daemon and terminal utilities, managed and built together.

## STRUCTURE

```
native/
├── src/main.rs      # All daemon logic (~580 lines)
├── src/cgroup.rs    # Container cgroup ownership: one write ends the whole container
├── Cargo.toml       # Workspace root + file_daemon (Tokio + libc + prost)
├── run0/src/main.rs # Privileged command client + local tty bridge
├── here/src/main.rs # Reveal a path via one O_PATH descriptor sent to the hub
├── agent/src/        # Modular ratatui + crossterm terminal AI chat UI
├── hub-client/      # Shared hub protocol codegen, socket I/O and SCM_RIGHTS
├── wayland/         # Smithay Wayland compositor (libwayland_compositor.so)
│   ├── src/lib.rs   # run loop, socket bind, Android JNI entry points
│   ├── src/state.rs # protocol state: xdg-shell, shm, dmabuf, seat, output, fractional scale
│   ├── src/egl.rs   # EGL context + per-ANativeWindow EGLSurface (android only)
│   ├── src/render.rs# damage-tracked frame drawing
│   ├── src/scale.rs # density-derived output scale (fractional + integer fallback)
│   └── src/keymap.rs# Android KeyEvent → evdev code table
├── build.rs         # Prost protobuf codegen
├── build-android-aarch64.sh  # Build workspace; stage daemon + original-name utilities
└── build-proot.sh            # Cross-compile proot for Android ARM64 → app/jniLibs/libproot.so
```

## WHERE TO LOOK

- **File operations**: `handlers::handle_streaming()` dispatches by command
- **Connection lifecycle**: `main()` accept loop + `handle_connection()` — one task per client
- **Connection auth**: `auth::derive_socket_name` / `auth::verify`; every client must sign the `--key`/`--id`-derived socket's nonce before any request
- **Abstract socket**: `create_abstract_server()` + `bind_abstract_socket()` (name derived from `--key`/`--id`)
- **Container lifetime**: the daemon is the container's subreaper (parentage: orphans reparent to it) while `cgroup.procs` is the only enumeration this kernel allows; spawned children join the group and then `unshare(CLONE_NEWCGROUP)` (`cgroup::isolate_view`) so the container manages only its own subtree; `cgroup::install` / `attach_self` / `kill_and_remove` own the group, opened before the chroot and used through `openat`, and `cgroup::sweep` ends groups whose owner died.  The proot backend uses PRoot `--cgroup` (a synthetic cgroup2 hierarchy backed by ppid/session data); resource limits are unavailable there.  `container::spawn_init()` starts an executable `/init` once as an ordinary child; the daemon remains subreaper and cgroup owner, so init is not PID 1 and a missing `/init` is not an error.  Shutdown sends it a catchable SIGTERM and waits up to 20 s before `cgroup::kill_and_remove()` hard-kills the group
- **UID check** for data path: `maybe_android_data_path()` — skips `/Android/data` UID trick when root
- **Progress tracking**: `TaskState` in `HashMap`, tasks spawn via `tokio::spawn`
- **Shared hub connection**: the agent's `Hub` methods take `&self` (ids are
  handed out atomically, `hub-client` serializes frames), so Esc and mid-run
  steering reach the hub *while* a run streams. Never reintroduce a mutex held
  across `agent_run` — that deadlocks every control request behind the running
  turn. Cancel and steering are **write-only** (no reply wait): Esc drops the
  local run state in the same tick and the hub kills the exec trees on its own
  side, so the stop button never has a timeout to burn.
- **`/yolo` is terminal-local**: it only sets `App::yolo`, and the UI answers
  each approval request with `allow` as it arrives (`app/events.rs`,
  `dispatch_event` in `main.rs`). Never send it to the hub: a control round-trip
  on the shared connection stalls the draw loop until the hub answers, which is
  the freeze this removed.
- **Terminal utilities**: `run0/`, `here/`, `agent/`; common wire transport in `hub-client/`
- **Agent UI/network**: ratatui + crossterm UI in `agent/src/{ui,terminal,app}.rs`; chat/models/history/online MCP go through the hub (`agent/src/hub.rs`), local stdio MCP in `agent/src/mcp.rs`. A finished title stream means the hub is gone — local abstract sockets do not drop on their own and the hub never evicts a client, so the agent must not re-subscribe or probe

## CONVENTIONS

- **Single-threaded async** — `tokio::main(flavor = "current_thread")`; concurrency is tasks, not threads
- **One reaper owns every child** — `reaper::track` returns a `watch::Receiver`
  for the exit code; only the SIGCHLD sweep calls `waitpid(-1)`. Do not add a
  second `waitpid` owner, and do not enumerate `/proc` (rootless Android cannot
  traverse it).
- **No process inventory** — the daemon reaps, it does not track what is alive.
  Every spawn creates its own process group so its *owner* can signal it (PTY
  close ends the session, `ExecProcess::drop` kills the group); shutdown just
  closes PTYs and drops execs. Do not reintroduce a global registry, a
  `/proc` scan, or a shutdown tree walk: they duplicate the handle tables and
  drift from them. Arm `reaper::arm_parent_death_signal` in the child
  (`pty.rs` after `fork`, `handlers/exec.rs` in `pre_exec`) — parent death is the
  one case a live daemon cannot clean up after.
- **Input is an async fd, not crossterm's reader** — `agent/src/terminal.rs` opens
  `/dev/tty` non-blocking and waits on it with `tokio::io::unix::AsyncFd`, then
  parses bytes with `termwiz::input::InputParser` and converts to crossterm
  events. Never route input back through `crossterm::event::{poll,read}` or
  `EventStream`: on a hung-up pty that source parks in a `read(2)` loop that only
  leaves on `WouldBlock`, so EOF (`0`) and `EIO` are ignored and it spins at
  ~1.6M iterations/s — driven from `select!` it wedges the executor, and
  `EventStream` would put that spin on the runtime thread. With `AsyncFd` a `0`
  or `EIO` read is simply "input is over". Open a *fresh* `/dev/tty`
  description: `O_NONBLOCK` is per open file description, so a `dup` of fd 0
  would also make stdout non-blocking and turn a full pty buffer into `EAGAIN`
  on draw. A lone Escape stays a key because `maybe_more` is false.
- **Escaped chroot sessions die by self-detection, not by cleanup** — there is no
  PID namespace, so a session whose owner died reparents to init(1) and sits in
  the same table as init's own services: `ppid == 1` proves nothing by itself.
  It cannot be enumerated cheaply either — walking `/proc` is far too slow to run
  on a timer and `children` is not exposed — so do not add a janitor, a registry
  or a shutdown tree walk. Every long-lived process inside a chroot container has
  to end itself: hang-up detection in its readers, plus
  `reaper::arm_parent_death_signal` on direct children (it is cleared by `fork`).
  proot containers use the PRoot `--cgroup` synthetic hierarchy for service
  management and container lifetime; their processes still cannot escape the
  app, and resource limits remain unavailable.
- **Multi-client** — the daemon serves many concurrent connections. Each has its own
  read loop, write half and response channel; PTYs/handles/broker are shared via `Shared`.
  Disconnecting one client does not affect other authenticated clients.
- **Connection-owned lifetime**: exit after 10 seconds without a first successful
  authentication, or when the last authenticated client disconnects. Pending or
  failed authentication does not keep the daemon alive. An `ExitRequest` from
  any authenticated client (or SIGTERM/SIGHUP/SIGINT) also shuts down the daemon.
  Shutdown closes the listener, cancels requests, closes PTYs/handles and exits.
- **Protobuf codegen** via `prost-build` in `build.rs` — output at compile time
- **Error → `Response.ok=false` + `error` string** — no exceptions across IPC
- **Framed protocol** — 4-byte big-endian length prefix + protobuf payload
- **Abstract namespace** socket — `sun_path[0] = '\0'`, name derived from Kotlin's `--key <pub> --id <id>`

## ANTI-PATTERNS

- `unsafe { libc::... }` is required for abstract socket setup — keep isolated in `create_abstract_server()` / `bind_abstract_socket()`
- Do NOT add threads to the daemon — `current_thread` runtime is intentional.
  The `agent` utility also uses a `current_thread` runtime; network access goes
  through the hub, so it never needs its own DNS/TLS stack.

## BUILD

```bash
bash build-android-aarch64.sh    # → jniLibs/.../libfiledaemon.so + executables/lib/.../{run0,here,agent}
bash build-proot.sh              # → app/src/main/jniLibs/arm64-v8a/libproot.so
bash build-xkbcommon.sh aarch64  # → wayland/third_party/libxkbcommon/builddir-aarch64/libxkbcommon.a
```

The Smithay compositor crate also produces
`jniLibs/arm64-v8a/libwayland_compositor.so`; it needs the xkbcommon archive
above first (Smithay's `xkbcommon` dependency links the C library, which the
NDK does not ship).

## TODO - Bug checks

- [ ] `wayland/src/state.rs`: verify a plain `weston-simple-shm` client maps and fills the output on device, and that `xdg-shell` configure sizes match the logical output size.
- [ ] `wayland/src/state.rs`: verify `zwp_linux_dmabuf_v1` is advertised with non-zero formats and a dmabuf client (e.g. `weston-simple-egl`, GTK4) renders through imported buffers; a device whose EGL lacks `EGL_EXT_image_dma_buf_import` must log the fallback and keep `wl_shm` working.
- [ ] `wayland/src/scale.rs` + `state.rs`: verify `wl_output.scale`/`preferred_buffer_scale` and `wp_fractional_scale_v1` report `densityDpi / 160` and update live on `nativeSetScale`.
- [ ] `wayland/src/egl.rs` + `render.rs`: verify surface re-attach after `SurfaceView` recreate, rotation resize, and that no stale `EGLSurface` is reused.
- [ ] `wayland/src/input.rs`: verify touch down/move/up, multi-touch slots, mouse buttons/scroll and hardware keyboard typing all reach clients.
- [ ] `wayland/src/keymap.rs`: verify the Android→evdev table against a real hardware keyboard and the xkb layout extracted at `XKB_CONFIG_ROOT`.
- [ ] `native/build-xkbcommon.sh`: verify the x86_64 (`--abi x86_64`) cross build for emulators and that `build.rs` picks the right builddir.
- [ ] `wayland/.../NativeCompositor.kt`: verify the JNI signatures match the native exports and the library loads from `nativeLibraryDir`.
- [ ] `wayland/src/text_input.rs`: verify non-text-input clients (foot) receive CJK/emoji through the regenerated `F13`..`F22` Unicode keymap, including mixed ASCII+CJK commits and multi-char commits longer than ten characters.
- [ ] `wayland/src/text_input.rs`: verify `zwp_text_input_v3` end to end on device — keyboard auto-shows on focus, `commitText`/`setComposingText`/`finishComposingText`/`deleteSurroundingText` reach the client, and preedit renders in the IME's language (Chinese/emoji).
- [ ] `wayland/.../NativeCompositor.kt`: verify the reverse `onTextInputEnabled` callback survives an app restart (class/method ID are cached in `nativeStart`) and never runs a view call off the main thread.
- [ ] `wayland/src/text_input.rs`: surrounding text is not mirrored back to the IME (`set_surrounding_text` is stored but unused), so suggestions/autocorrect have no context; verify and decide whether to feed it through reverse JNI.
- [ ] `wayland/src/render.rs`: verify frames are not re-rendered when nothing changed and that the 60 Hz tick does not spin the CPU while the viewer is closed.
- [ ] `wayland/src/lib.rs`: verify a client launched from a container connects to `/dev/wayland/wayland-0` (foot: `failed to connect to wayland`), including after an app restart leaves an old socket file behind.
- [ ] `agent/src/terminal.rs`: verify an `agent` running in a **chroot** container (proot sessions cannot escape, so they are out of scope) exits within one poll interval after the pty master closes (kill the daemon or the app while the UI is open), and that no `agent-input` thread keeps accumulating CPU time afterwards.
- [ ] `native/build-proot.sh`: run a clean cross build to confirm the patch from `native/patches/proot/` applies to the pinned `v5.1.107.79` clone and that the resulting `libproot.so` still provides the `--cgroup` emulation.
- [ ] `native/src/list.rs`: verify `stat_path` reports `modified_at_ms` for files/directories and 0 for a missing path, and that the daemon proto field round-trips.

