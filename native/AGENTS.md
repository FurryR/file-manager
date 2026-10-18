# AGENTS.md — native/

**Rust workspace** — file daemon and terminal utilities, managed and built together.

## STRUCTURE

```
native/
├── src/main.rs      # All daemon logic (~580 lines)
├── Cargo.toml       # Workspace root + file_daemon (Tokio + libc + prost)
├── run0/src/main.rs # Privileged command client + local tty bridge
├── here/src/main.rs # Reveal a path via one O_PATH descriptor sent to the hub
├── agent/src/main.rs # Single-file async terminal AI chat UI
├── hub-client/      # Shared hub protocol codegen, socket I/O and SCM_RIGHTS
├── build.rs         # Prost protobuf codegen
├── build-android-aarch64.sh  # Build workspace; stage daemon + original-name utilities
└── build-proot.sh            # Cross-compile proot for Android ARM64 → app/jniLibs/libproot.so
```

## WHERE TO LOOK

- **File operations**: `handlers::handle_streaming()` dispatches by command
- **Connection lifecycle**: `main()` accept loop + `handle_connection()` — one task per client
- **Connection auth**: `auth::derive_socket_name` / `auth::verify`; every client must sign the `--key`/`--id`-derived socket's nonce before any request
- **Abstract socket**: `create_abstract_server()` + `bind_abstract_socket()` (name derived from `--key`/`--id`)
- **UID check** for data path: `maybe_android_data_path()` — skips `/Android/data` UID trick when root
- **Progress tracking**: `TaskState` in `HashMap`, tasks spawn via `tokio::spawn`
- **Terminal utilities**: `run0/`, `here/`, `agent/`; common wire transport in `hub-client/`
- **Agent input/network**: single-file `agent/src/main.rs`, `AsyncFd` + system DNS

## CONVENTIONS

- **Single-threaded async** — `tokio::main(flavor = "current_thread")`; concurrency is tasks, not threads
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
  The `agent` utility also uses a `current_thread` task runtime, but permits
  Reqwest's system DNS resolver to use Tokio's blocking pool. This preserves
  Android network/VPN DNS without `/etc/resolv.conf` or public DNS fallbacks.

## BUILD

```bash
bash build-android-aarch64.sh    # → jniLibs/.../libfiledaemon.so + executables/lib/.../{run0,here,agent}
bash build-proot.sh              # → app/src/main/jniLibs/arm64-v8a/libproot.so
```
