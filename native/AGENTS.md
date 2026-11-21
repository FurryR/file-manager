# AGENTS.md — native/

**Rust workspace** — file daemon and terminal utilities, managed and built together.

## STRUCTURE

```
native/
├── src/main.rs      # All daemon logic (~580 lines)
├── src/cgroup.rs    # Daemon cgroup ownership: one write ends the whole process tree
├── Cargo.toml       # Workspace root + file_daemon (Tokio + libc + prost)
├── run0/src/main.rs # Privileged command client + local tty bridge
├── here/src/main.rs # Reveal a path via one O_PATH descriptor sent to the hub
├── agent/src/        # Modular ratatui + crossterm terminal AI chat UI
├── hub-client/      # Shared hub protocol codegen, socket I/O and SCM_RIGHTS
├── adblock/         # JNI ad-blocking engine over adblock-rust (libadblock.so)
├── wayland/         # Smithay Wayland compositor (libwayland_compositor.so)
│   ├── src/lib.rs   # run loop, socket bind, Android JNI entry points
│   ├── src/state.rs # protocol state: xdg-shell, shm, dmabuf, seat, output, fractional scale
│   ├── src/egl.rs   # EGL context + per-ANativeWindow EGLSurface (android only)
│   ├── src/render.rs# damage-tracked frame drawing
│   ├── src/scale.rs # density-derived output scale (fractional + integer fallback)
│   └── src/keymap.rs# Android KeyEvent → evdev code table
├── build.rs         # Prost protobuf codegen + libsepol static link
├── build-libsepol.sh         # Cross-compile pinned libsepol → native/sepolicy/prefix
├── build-android-aarch64.sh  # Build workspace; stage daemon + original-name utilities
└── build-proot.sh            # Cross-compile proot for Android ARM64 → app/jniLibs/libproot.so
```

## WHERE TO LOOK

- **Container policy backend**: `native/sepolicy/shim.c` + `src/sepolicy.rs`
  — `file_container_ensure()` always reads and parses `/sys/fs/selinux/policy`,
  then makes the shared domain a copy of the daemon's own domain: attribute and
  role membership, every allow rule minus a blocklist, and the **constraint
  type sets** the base appears in. Rules are added only when absent —
  `avtab_search`/`ebitmap_get_bit` gate each one, and extended permissions are
  merged per driver instead of appended — so the image is written back only
  when something actually changed, at the same policy version.
  Three things are easy to get wrong:
  - **Attribute targets are real.** A root-solution policy grants through
    attributes (`allow ksu domain:process *`) and the kernel expands both sides
    of a check through the type attribute maps
    (`context_struct_compute_av`). Filtering rules by "target == the domain"
    leaves the new domain without `fork`/`signal` and without `fd use` for every
    inode the daemon hands it. Only the dangerous process perms are stripped
    (`transition`, `setexec`, `setcurrent`, …); `process`, `fd` and file rules
    are copied unchanged, so a container shell interacts with the app (all
    `untrusted_app*` variants) exactly like the root shell does.
  - **Constraints hold pre-expanded type IDs.** `t1 == mlstrustedsubject` is
    compiled into that attribute's members at build time and the kernel tests
    `ebitmap_get_bit(&names, type - 1)`, so a type created at runtime is never
    in it — even after joining the attribute. `copy_constraint_memberships`
    adds the new type wherever the base domain appears. Without it every
    constrained class denies the container: the rootfs carries the app's MCS
    categories while the container context is plain `s0`, so even `chdir("/")`
    fails.
  - **`/bin/login` is exec'd twice.** The first exec leaves the daemon's
    domain, which checks `process transition` plus `file entrypoint`; every
    later one (`login` starting the shell) is a same-domain exec and the kernel
    checks `file execute_no_trans` instead. Both are granted for the rootfs
    label.
  - **A received descriptor is two checks.** The container gets the app's
    `type_transition <domain> tmpfs:file appdomain_tmpfs`, so a pool it creates
    is labeled exactly like an app pool (a runtime domain otherwise falls back to
    plain `tmpfs`, which no app may touch); the app context
    (`APP_SELINUX_CONTEXT`, the wayland host's own) is then granted only
    `fd { use }` on `file_container` — the label saved when the container created
    the file. Without both the kernel silently drops the fd and the client only
    sees `wl_shm#4.create_pool` fail. A policy without `appdomain_tmpfs` falls
    back to granting the app `tmpfs:file` access.
  `file_container_context()` builds the `/proc/self/attr/exec` value: the
  daemon's user/role and the **app/rootfs MLS level** (the app's first), so a
  pool a container creates carries the categories the receiving app has. Version
  gates: kernel maximum from `/sys/fs/selinux/policyvers`, image version must
  match and be at least `POLICYDB_VERSION_XPERMS_IOCTL` (30), otherwise the
  result is `Disabled` and containers must not switch domains.
- **File operations**: `handlers::handle_streaming()` dispatches by command
- **Connection lifecycle**: `main()` accept loop + `handle_connection()` — one task per client
- **Connection auth**: `auth::derive_socket_name` / `auth::verify`; every client must sign the `--key`/`--id`-derived socket's nonce before any request
- **Abstract socket**: `create_abstract_server()` + `bind_abstract_socket()` (name derived from `--key`/`--id`)
- **Process-tree lifetime**: the daemon is the subreaper (orphans reparent to it) while `cgroup.procs` is the only enumeration this kernel allows. Every daemon (host and container) automatically owns `ct/<id>`; `cgroup::install` / `attach_self` / `kill_and_remove` own it, opened before the chroot and used through `openat`, and `cgroup::sweep` ends groups whose owner died. App force-stop closes the last daemon connection, so shutdown kills every shell and command. Only container daemons call `cgroup::isolate_view` (`unshare(CLONE_NEWCGROUP)`) so an init system manages only its own subtree; the host daemon keeps the normal cgroupfs view. The proot backend uses PRoot `--cgroup` (a synthetic cgroup2 hierarchy backed by ppid/session data); resource limits are unavailable there. `container::spawn_init()` starts an executable `/init` once as an ordinary child; the daemon remains subreaper and cgroup owner, so init is not PID 1 and a missing `/init` is not an error. Shutdown sends it a catchable SIGTERM and waits up to 20 s before `cgroup::kill_and_remove()` hard-kills the group
- **Spawned identity is a client concern**: the native daemon executes the command it receives and children inherit the daemon's credentials (root for a root daemon, shell for Shizuku, app UID for the direct daemon, container identity for container daemons). A caller that needs another identity sets `CommandSpec.user` explicitly. File operations use the same daemon credentials.
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
- **Ad blocking engine**: `adblock/src/lib.rs` — process-wide `RwLock<Option<Engine>>` over `adblock-rust`; Kotlin sends rule text/cache bytes and network-request facts. Build without the crate's `single-thread` feature (Chromium calls from several threads) and parse `RuleTypes::NetworkOnly` (the WebView does not inject cosmetic rules).
- **Agent UI/network**: ratatui + crossterm UI in `agent/src/{ui,terminal,app}.rs`; chat/models/history/online MCP go through the hub (`agent/src/hub.rs`), local stdio MCP in `agent/src/mcp.rs`. A finished title stream means the hub is gone — local abstract sockets do not drop on their own and the hub never evicts a client, so the agent must not re-subscribe or probe

## CONVENTIONS

- **libsepol is pinned and statically linked** — Android ships no libsepol and
  its policydb structs are not a stable ABI. `bash build-libsepol.sh` builds the
  checksummed archive plus `sepolicy/shim.c` into
  `native/sepolicy/prefix/<target>/lib/libfile_container.a`, which `build.rs`
  links as `-l static=file_container` for Android targets. Never `dlopen` a
  device library and never add a Rust binding crate: policy mutation goes
  through the C boundary in `native/sepolicy`, with `src/sepolicy.rs` declaring
  only that ABI.
- **Single-threaded async** — a manually built `current_thread` runtime with `enable_all()` (`main` is a synchronous preflight, then `runtime.block_on(serve(..))`); concurrency is tasks, not threads
- **The runtime starts after the container root is entered** — `unshare(CLONE_NEWNS)` and `chroot` only change the calling thread's filesystem context; a thread created earlier keeps the host root and host mount namespace, and `tokio::fs` runs on blocking-pool threads. Keep every preflight (reaper, cgroup, SELinux, chroot) synchronous in `main()` and build the Tokio runtime only afterwards, so every pool thread inherits the container root. Never call `spawn_blocking` before `enter_chroot`, and bind the socket only after the preflight: a container listing served from a pre-chroot thread would return the host `/`.
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
bash build-libsepol.sh           # → native/sepolicy/prefix (static archive linked into the daemon)
bash build-android-aarch64.sh    # → jniLibs libfiledaemon.so + lib{run0,here,agent,adblock}.so
bash build-proot.sh              # → app/src/main/jniLibs/arm64-v8a/libproot.so
bash build-xkbcommon.sh aarch64  # → wayland/third_party/libxkbcommon/builddir-aarch64/libxkbcommon.a
```

The Smithay compositor crate also produces
`jniLibs/arm64-v8a/libwayland_compositor.so`; it needs the xkbcommon archive
above first (Smithay's `xkbcommon` dependency links the C library, which the
NDK does not ship).

Android Rust releases are size-optimized: `rust-toolchain.toml` pins
`nightly-2026-11-20` with `rust-src`, `native/Cargo.toml` selects
`opt-level = "z"` / fat LTO / one CGU / `panic = "immediate-abort"`, and
`build-android-aarch64.sh` passes `-Z build-std=std,panic_abort
-Z build-std-features=optimize_for_size`. Host cargo commands are not forced
through `build-std`. `.cargo/config.toml` adds `--icf=all`,
`--gc-sections`, `--strip-all` and 16 KiB max-page-size for Android links.
Stable Cargo cannot parse the `cargo-features = ["panic-immediate-abort"]`
line; use the pinned nightly.

C-side: `build-proot.sh` and `build-pulse.sh` use `-Oz` plus fat LTO across
their pure-C objects. `build-xkbcommon.sh`, `build-libsepol.sh` and the EGL shim
use `-Oz` with function/data sections only: the NDK clang (LLVM 21) cannot
consume Rust's LLVM 23 bitcode, so cross-language LTO is deliberately not
enabled. The final Rust link still runs `--gc-sections`/`--icf=all`, dropping
unused C sections. Never add `-flto` to those archives without matching the
toolchain LLVMs and adding `-C linker-plugin-lto`.

Gradle builds native before packaging: the app `preBuild` task runs
`scripts/build-native.sh`, which calls every script above in order. Its inputs
are the native sources/scripts/proto and its outputs are the staged `jniLibs`,
utility libs, xkb assets and libsepol/xkbcommon archives, so Gradle skips it
when nothing native changed. Use `-PskipNativeBuild=true` only when reusing
already staged binaries. A quick check is `strings
app/src/main/jniLibs/arm64-v8a/libfiledaemon.so | grep 'container domain'`.

Network downloads (libsepol, talloc, xkbcommon, xkeyboard-config, PulseAudio,
libsndfile, libtool) are cached in the gitignored `cache/file-manager` at the
repository root; delete that directory to force a refetch. Extracted libsepol
and PulseAudio build trees live under `cache/native`, and the proot source is
cloned once into `cache/native/proot-<version>` (copied to `/tmp` per build so
patches never dirty the cache).

`native/build-proot.sh` and `native/patches/proot/**` are part of this
project and licensed GPL-3.0-or-later (root `LICENSE`). PRoot is a
standalone executable, not linked into the app; upstream PRoot is
GPL-2.0-or-later, and the generated `libproot.so` statically links talloc
(LGPL-3.0-or-later).

## TODO - Bug checks

- [ ] `src/sepolicy.rs` + `sepolicy/shim.c`: verify a chroot container started from a second root solution (or with a second rootfs label) gets its transition/entry point even though `file_container` already exists, that `/bin/login` (including a busybox symlink) runs instead of returning `Permission denied`, that a container shell can `fork`/`signal` and that `run0`/the terminal can receive a pty fd from the daemon, that a Wayland client's `wl_shm` pool reaches the app (no `wl_shm#4.create_pool` failure), that a relaunch with nothing new logs `container domain present` and does not load the policy, and that a changed root label/base logs `container domain injected` once.
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
- [ ] `native/build-proot.sh`: run a clean cross build to confirm the patch from `native/patches/proot/` applies to the cached `v5.1.107.79` clone and that the resulting `libproot.so` still provides the `--cgroup` emulation.
- [ ] `native/src/list.rs`: verify `stat_path` reports `modified_at_ms` for files/directories and 0 for a missing path, and that the daemon proto field round-trips.
- [ ] `daemon/process/ExecService.kt` + `pty.rs` + `handlers/exec.rs`: verify host PTY, agent and run0 children inherit the daemon's identity (root shell can enter `/storage/emulated/0`), preserving cwd/env/args/stdin.
- [ ] `daemon/process/ExecService.kt` + `DaemonManager.kt`: verify the direct app-UID daemon sends plain commands, container daemons keep container root, and file operations still use the daemon's own credentials.
- [ ] `src/cgroup.rs` + `src/main.rs`: verify the host daemon owns `ct/host` without `--group`, every PTY/exec child joins it, the last client disconnect kills the tree, and a SIGKILLed daemon's group is swept by the next launch.
- [ ] `src/cgroup.rs`: verify chroot/proot containers still own a group without the removed `--group` argument and that `isolate_view` is a no-op in the host daemon.
- [ ] `native/build-proot.sh`: verify a clean build stages talloc's LICENSE as `app/src/main/assets/native-licenses/COPYING-talloc` and the rebuilt `libproot.so` still provides the `--cgroup` emulation.

