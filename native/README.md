# Native File Daemon

Rust subprocess daemon for file operations. Android creates a filesystem UNIX socket under app `filesDir`, starts this daemon with `--socket <path>`, then sends line-based commands. The daemon is a tokio UNIX socket client and exits gracefully when the server socket closes.

Build the Android ARM64 executable into the Gradle asset source directory:

```bash
native/build-android-aarch64.sh
```

The Android app packages `native/target/android/assets/file-daemon-aarch64`, copies it to `filesDir`, marks it executable, and starts it as a child process.
