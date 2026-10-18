# Native executables

This Cargo workspace manages the daemon and all terminal utilities together:

- `file_daemon`: privileged file operations and process execution.
- `run0`: run a command through the hub, optionally as another user/container.
- `here`: reveal a file or directory in the app's current tab.
- `agent`: a small, single-file Rust TUI for an OpenAI-compatible Chat Completions API.
- `hub-client`: shared Protobuf framing, abstract sockets and SCM_RIGHTS transport.

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
into `nativeLibraryDir`. The utilities keep their original names (`run0`, `here`,
`agent`) under `app/src/main/executables/lib/arm64-v8a/`; the existing Java-resource
packaging preserves those names in the APK's `lib/arm64-v8a/` directory. All
workspace build artifacts and the shared dependency lockfile live under `native/`.

The build requires Rust with the `aarch64-linux-android` target, `protoc`, and an
Android NDK. The script also supplies the NDK C compiler and archiver to the TLS
dependency used by `agent`.

## here

```sh
here report.txt
here ../directory
here -- -filename
```

The command requires one path. It opens the target with `O_PATH | O_NOFOLLOW`
and passes the descriptor to the hub. Relative paths are interpreted in the
calling shell; symbolic links, including dangling links, select the link itself.
Descriptor resolution retains the underlying path across proot/chroot boundaries.
The app then collapses the terminal, opens the selected entry's parent in the
current file-manager tab, and selects that entry. A directory is selected in its
parent rather than opened.

## agent

Set the API base URL (for example `https://api.openai.com/v1`), API key, and model
in the app's AI settings, then run `agent` from an app terminal. The command reads
configuration from `HUB_SOCKET`; it does not save credentials or conversations.
An empty API key is supported for local providers. The base URL may also be a
complete `/chat/completions` endpoint. HTTP redirects are disabled.

The interface uses the terminal's alternative screen and restores its original
mode on exit, cancellation, ordinary errors and handled termination signals.
Input and network requests share a `current_thread` Tokio runtime. `AsyncFd`
handles input without a background input thread. DNS uses the system resolver
through Tokio's blocking pool so Android's active network and VPN DNS work without
`/etc/resolv.conf` or a hard-coded public DNS service. Chinese input,
grapheme-aware backspace, pasted multiline messages, and Unicode display widths
are supported. Responses appear when the request completes.

- Enter sends a message. While waiting, a new message can be prepared.
- Ctrl+C cancels an outstanding request; otherwise it exits. Ctrl+Q/Ctrl+D exit.
- Backspace deletes the last grapheme; Ctrl+U clears input.
- PageUp/PageDown or Ctrl+P/Ctrl+N scroll conversation history.
- `/clear` clears the conversation; `/help` shows controls; `/exit` exits.

Conversation context includes successful exchanges. Failed/cancelled prompts are
restored for editing when the input field is empty. API errors show a status or
generic explanation rather than provider response bodies, URLs or credentials.
The UI performs no tool execution and writes no conversation files.
