#!/usr/bin/env bash
set -euo pipefail

# Builds the Rust workspace for every requested Android ABI and stages the
# resulting libraries under app/src/main/jniLibs/<abi>/.
#
# Usage: bash native/build-android.sh [arm64-v8a|armeabi-v7a|x86|x86_64 ...]
#
# Debug builds pass one ABI; the release build and F-Droid pass all four.
# The C dependencies (libsepol, libxkbcommon) must already be built for the
# same ABIs; scripts/build-native.sh does that first.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_DIR="$ROOT_DIR/native"
# rustup resolves the active toolchain from the repository's
# rust-toolchain.toml, so run from the root even when invoked elsewhere.
cd "$ROOT_DIR"
# rustup installs its shims in $CARGO_HOME/bin. F-Droid's build shell is not
# a login shell, so make the toolchain rustup installed in `prebuild` visible
# to this script without requiring a system-wide cargo.
export PATH="${CARGO_HOME:-$HOME/.cargo}/bin:$PATH"
command -v cargo >/dev/null || {
  echo "cargo not found; install Rust (rustup) before building the native artifacts" >&2
  exit 1
}
command -v rustup >/dev/null || {
  echo "rustup not found; install a Rust toolchain before building the native artifacts" >&2
  exit 1
}

ALL_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
if (( $# == 0 )); then
  ABIS=("${ALL_ABIS[@]}")
else
  ABIS=("$@")
fi

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
  echo "Android NDK not found. Install it with: sdkmanager 'ndk;30.0.16248370'" >&2
  exit 1
fi

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
# Rebuild std for size. Kept on the command line instead of .cargo/config.toml
# so host cargo commands are not forced through build-std. Requires nightly +
# rust-src (see rust-toolchain.toml).
CARGO_STD_FLAGS=(-Z build-std=std,panic_abort -Z build-std-features=optimize_for_size)

for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a)
      target="aarch64-linux-android"
      cc_triple="aarch64-linux-android"
      ;;
    armeabi-v7a)
      target="armv7-linux-androideabi"
      cc_triple="armv7a-linux-androideabi"
      ;;
    x86)
      target="i686-linux-android"
      cc_triple="i686-linux-android"
      ;;
    x86_64)
      target="x86_64-linux-android"
      cc_triple="x86_64-linux-android"
      ;;
    *)
      echo "unsupported ABI: $abi (use arm64-v8a, armeabi-v7a, x86 or x86_64)" >&2
      exit 1
      ;;
  esac

  LINKER="$TOOLCHAIN/bin/${cc_triple}26-clang"
  CXX="$TOOLCHAIN/bin/${cc_triple}26-clang++"
  ARCHIVER="$TOOLCHAIN/bin/llvm-ar"
  if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
    LINKER="$LINKER.cmd"
    CXX="$CXX.cmd"
    ARCHIVER="$ARCHIVER.exe"
  fi
  if [[ ( "$HOST_TAG" == "windows-x86_64" && ! -f "$LINKER" ) ||
        ( "$HOST_TAG" != "windows-x86_64" && ! -x "$LINKER" ) ]]; then
    echo "Android linker not found for $abi: $LINKER" >&2
    exit 1
  fi
  if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
    # Cargo and cc-rs are Windows executables even when launched from Git Bash.
    LINKER="$(cygpath -m "$LINKER")"
    CXX="$(cygpath -m "$CXX")"
    ARCHIVER="$(cygpath -m "$ARCHIVER")"
  fi

  JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/$abi"
  # The compositor's build.rs stages libeglshim.so into jniLibs only when the
  # directory already exists, so create it before cargo runs.
  mkdir -p "$JNI_DIR"

  # Install only the target this invocation actually builds. This keeps a
  # debug build (arm64 by default) from downloading every release target;
  # F-Droid's `prebuild` installs all four because its release builds all.
  if [[ ! -d "$(rustc --print sysroot)/lib/rustlib/$target" ]]; then
    echo "==> Installing missing Rust target $target for $abi"
    rustup target add "$target"
  fi

  target_env="${target//-/_}"
  target_upper="${target_env^^}"
  echo "==> Building Rust workspace for $abi ($target)"
  env \
    "CARGO_TARGET_${target_upper}_LINKER=$LINKER" \
    "CC_${target_env}=$LINKER" \
    "CXX_${target_env}=$CXX" \
    "AR_${target_env}=$ARCHIVER" \
    cargo build --manifest-path "$NATIVE_DIR/Cargo.toml" --workspace --locked --release \
    --target "$target" "${CARGO_STD_FLAGS[@]}"

  # NOTE: keep the daemon dynamically linked. proot crashes (SIGSEGV) on static
  # binaries; the dynamic daemon runs under proot as long as /apex (bionic) and
  # /system are bound into the guest.
  cp "$NATIVE_DIR/target/$target/release/file_daemon" "$JNI_DIR/libfiledaemon.so"
  # Smithay-based Wayland compositor. Requires libxkbcommon first:
  #   bash native/build-xkbcommon.sh <abi>
  cp "$NATIVE_DIR/target/$target/release/libwayland_compositor.so" \
    "$JNI_DIR/libwayland_compositor.so"
  # JNI ad-blocking engine used by the browser WebView.
  cp "$NATIVE_DIR/target/$target/release/libadblock.so" "$JNI_DIR/libadblock.so"

  # Android only extracts lib*.so from lib/<abi>/, so each utility ships under a
  # lib name. DaemonManager.binDir creates the original-name symlinks at startup.
  for utility in run0 here agent; do
    cp "$NATIVE_DIR/target/$target/release/$utility" "$JNI_DIR/lib$utility.so"
  done
done
