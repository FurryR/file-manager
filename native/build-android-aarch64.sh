#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_DIR="$ROOT_DIR/native"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  if [[ -d "$HOME/.local/share/android" ]]; then
    SDK_ROOT="$HOME/.local/share/android"
  else
    SDK_ROOT="$HOME/Android/Sdk"
  fi
fi
NDK_ROOT="${ANDROID_NDK_HOME:-}"

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

HOST_TAG="linux-x86_64"
LINKER="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang"
if [[ ! -x "$LINKER" ]]; then
  echo "Android ARM64 linker not found: $LINKER" >&2
  exit 1
fi

rustup target add aarch64-linux-android
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER" \
  cargo build --manifest-path "$NATIVE_DIR/Cargo.toml" --release --target aarch64-linux-android

JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
cp "$NATIVE_DIR/target/aarch64-linux-android/release/file_daemon" \
  "$JNI_DIR/libfiledaemon.so"
