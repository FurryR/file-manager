# Native File Daemon

Rust subprocess daemon for file operations and command execution. The app starts
it with `--key <public-key> --id <target-id>`. Each launch has an independent
in-memory Ed25519 key pair; the daemon listens on an abstract UNIX socket derived
from the public key and target id. Connections authenticate with a nonce/signature
handshake and exchange framed Protobuf messages, optionally carrying descriptors.

The daemon supports multiple authenticated clients. It exits when the last one
disconnects, or if none has authenticated within 10 seconds of listening.
Unauthenticated connections do not extend its lifetime. Explicit exit requests
and termination signals stop it regardless of the client count. Shutdown cancels
requests and closes PTYs and handles. There is no automatic reconnection or reuse
of a previous launch; later app operations may start a fresh daemon on demand.

Build and stage the Android ARM64 executables:

```bash
bash native/build-android-aarch64.sh
```

The script stages the daemon as
`app/src/main/jniLibs/arm64-v8a/libfiledaemon.so` for APK packaging and extraction
into `nativeLibraryDir`. It also builds and stages the separate `run0` hub client.
