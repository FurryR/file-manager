# AGENTS.md — native/

**Single Rust binary** — file daemon for privileged file operations.

## STRUCTURE

```
native/
├── src/main.rs      # All daemon logic (~580 lines)
├── Cargo.toml       # Tokio + libc + prost
├── build.rs         # Prost protobuf codegen
├── build-android-aarch64.sh  # Cross-compile Rust daemon for Android ARM64 → app/jniLibs/libfiledaemon.so
└── build-proot.sh            # Cross-compile proot for Android ARM64 → app/jniLibs/libproot.so
```

## WHERE TO LOOK

- **File operations**: `handlers::handle_streaming()` dispatches by command
- **Connection lifecycle**: `main()` accept loop + `handle_connection()` — one task per client
- **Abstract socket**: `create_abstract_server()` + `bind_abstract_socket()`
- **UID check** for data path: `maybe_android_data_path()` — skips `/Android/data` UID trick when root
- **Progress tracking**: `TaskState` in `HashMap`, tasks spawn via `tokio::spawn`

## CONVENTIONS

- **Single-threaded async** — `tokio::main(flavor = "current_thread")`; concurrency is tasks, not threads
- **Multi-client** — the daemon serves many concurrent connections. Each has its own
  read loop, write half and response channel; PTYs/handles/broker are shared via `Shared`.
  A client disconnecting only ends *that* connection — the daemon keeps running.
- **Shutdown is explicit** — an `ExitRequest` from *any* connection (or SIGTERM/SIGHUP/SIGINT)
  stops the listener, flushes acks, closes all PTYs and exits. Disconnecting never does.
- **Protobuf codegen** via `prost-build` in `build.rs` — output at compile time
- **Error → `Response.ok=false` + `error` string** — no exceptions across IPC
- **Framed protocol** — 4-byte big-endian length prefix + protobuf payload
- **Abstract namespace** socket — `sun_path[0] = '\0'`, name passed from Kotlin as `--socket`

## ANTI-PATTERNS

- `unsafe { libc::... }` is required for abstract socket setup — keep isolated in `create_abstract_server()` / `bind_abstract_socket()`
- Do NOT add threads — `current_thread` runtime is intentional

## BUILD

```bash
bash build-android-aarch64.sh    # → app/src/main/jniLibs/arm64-v8a/libfiledaemon.so
bash build-proot.sh              # → app/src/main/jniLibs/arm64-v8a/libproot.so
```
