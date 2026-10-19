#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_DIR="$ROOT_DIR/native"
case "$(uname -s)" in
  Linux*) HOST_TAG="linux-x86_64" ;;
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *) echo "Unsupported NDK host: $(uname -s)" >&2; exit 1 ;;
esac
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  if [[ "$HOST_TAG" == "windows-x86_64" && -n "${LOCALAPPDATA:-}" ]]; then
    SDK_ROOT="$LOCALAPPDATA/Android/Sdk"
  elif [[ -d "$HOME/.local/share/android" ]]; then
    SDK_ROOT="$HOME/.local/share/android"
  else
    SDK_ROOT="$HOME/Android/Sdk"
  fi
fi
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  SDK_ROOT="$(cygpath -u "$SDK_ROOT")"
  if [[ -n "$NDK_ROOT" ]]; then NDK_ROOT="$(cygpath -u "$NDK_ROOT")"; fi
fi

if [[ -z "$NDK_ROOT" ]]; then
  if [[ -d "$SDK_ROOT/ndk" ]]; then
    NDK_ROOT="$(ls -d "$SDK_ROOT"/ndk/* | sort -V | tail -n 1)"
  elif [[ -d "$SDK_ROOT/ndk-bundle" ]]; then
    NDK_ROOT="$SDK_ROOT/ndk-bundle"
  fi
fi

if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "Android NDK not found. Install it with: sdkmanager 'ndk;27.2.12479018'" >&2
  exit 1
fi

LINKER="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang"
ARCHIVER="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-ar"
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  LINKER="$LINKER.cmd"
  ARCHIVER="$ARCHIVER.exe"
fi
if [[ ( "$HOST_TAG" == "windows-x86_64" && ! -f "$LINKER" ) ||
      ( "$HOST_TAG" != "windows-x86_64" && ! -x "$LINKER" ) ]]; then
  echo "Android ARM64 linker not found: $LINKER" >&2
  exit 1
fi
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  # Cargo and cc-rs are Windows executables even when launched from Git Bash.
  LINKER="$(cygpath -m "$LINKER")"
  ARCHIVER="$(cygpath -m "$ARCHIVER")"
fi

rustup target add aarch64-linux-android
# NOTE: keep the daemon dynamically linked. proot crashes (SIGSEGV) on static
# binaries; the dynamic daemon runs under proot as long as /apex (bionic) and
# /system are bound into the guest.
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER" \
CC_aarch64_linux_android="$LINKER" \
AR_aarch64_linux_android="$ARCHIVER" \
  cargo build --manifest-path "$NATIVE_DIR/Cargo.toml" --workspace --locked --release --target aarch64-linux-android

JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
cp "$NATIVE_DIR/target/aarch64-linux-android/release/file_daemon" \
  "$JNI_DIR/libfiledaemon.so"

# Java resources bypass JNI filename filtering while preserving original names.
UTILITY_DIR="$ROOT_DIR/app/src/main/executables/lib/arm64-v8a"
mkdir -p "$UTILITY_DIR"
for utility in run0 here agent systemctl virgl_bridge; do
  cp "$NATIVE_DIR/target/aarch64-linux-android/release/$utility" "$UTILITY_DIR/$utility"
done

# The native GPU bridge needs a real Android EGL/GLES host renderer in the APK.
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  PYTHON_BIN="${PYTHON_BIN:-python}"
else
  PYTHON_BIN="${PYTHON_BIN:-python3}"
fi
"$PYTHON_BIN" "$NATIVE_DIR/build-virgl.py" --ndk "$NDK_ROOT" --work "$NATIVE_DIR/target/virgl-build"
