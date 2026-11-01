#!/usr/bin/env bash
# Cross-compile libxkbcommon for Android and install it where
# native/wayland/build.rs looks for it.
#
# Smithay's `xkbcommon` dependency links the C library; the NDK does not ship
# it. Pinned to 1.11.0, the version the previous compositor stack used, so
# keymap behaviour stays identical.
#
# Output: native/wayland/third_party/libxkbcommon/builddir-<arch>/libxkbcommon.a
# (gitignored). The keymap *data* (xkeyboard-config) is not built here:
# scripts/fetch-xkbdata.sh installs it to app/src/main/assets/xkb, XkbData.kt
# extracts it, and the compositor gets that path as XKB_CONFIG_ROOT.
set -euo pipefail

VERSION="${XKBCOMMON_VERSION:-1.11.0}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/native/wayland/third_party/libxkbcommon"
SRC_DIR="$WORK_DIR/libxkbcommon-$VERSION"

ABIS=("${@:-}")
if [[ -z "${ABIS[0]:-}" ]]; then
    ABIS=(aarch64)
fi

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
    NDK_ROOT="$(ls -d "$SDK_ROOT"/ndk/* 2>/dev/null | sort -V | tail -n 1)"
fi
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
    echo "Android NDK not found. Install it with: sdkmanager 'ndk;27.2.12479018'" >&2
    exit 1
fi
HOST_TAG="linux-x86_64"
case "$(uname -s)" in
    Darwin*) HOST_TAG="darwin-x86_64" ;;
esac
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"

mkdir -p "$WORK_DIR"
if [[ ! -d "$SRC_DIR" ]]; then
    echo "==> Fetching libxkbcommon $VERSION"
    curl -fsSL --retry 2 \
        "https://github.com/xkbcommon/libxkbcommon/archive/refs/tags/xkbcommon-$VERSION.tar.gz" \
        | tar xz -C "$WORK_DIR"
    # GitHub names the archive root after repo + tag (`libxkbcommon-xkbcommon-1.11.0`).
    extracted="$(ls -d "$WORK_DIR"/libxkbcommon-* 2>/dev/null | head -n 1)"
    if [[ -n "$extracted" && "$extracted" != "$SRC_DIR" ]]; then
        mv "$extracted" "$SRC_DIR"
    fi
fi
if [[ ! -d "$SRC_DIR" ]]; then
    echo "libxkbcommon source missing after extraction: $SRC_DIR" >&2
    exit 1
fi

for abi in "${ABIS[@]}"; do
    case "$abi" in
        aarch64)
            triple="aarch64-linux-android"
            builddir="builddir-aarch64"
            ;;
        x86_64)
            triple="x86_64-linux-android"
            builddir="builddir-x86_64"
            ;;
        *)
            echo "unsupported ABI: $abi" >&2
            exit 1
            ;;
    esac

    echo "==> Building libxkbcommon for $triple"

    # libxkbcommon's wl/mesa backends are not wanted inside the app process;
    # only the core keymap compiler and state machine are.
    cat > "$WORK_DIR/cross-$abi.ini" <<EOF
[binaries]
c = '$TOOLCHAIN/bin/${triple}26-clang'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = '$abi'
cpu = '$abi'
endian = 'little'
EOF

    rm -rf "$WORK_DIR/$builddir"
    meson setup "$WORK_DIR/$builddir" "$SRC_DIR" \
        --cross-file "$WORK_DIR/cross-$abi.ini" \
        --default-library=static \
        --buildtype=release \
        -Denable-x11=false \
        -Denable-wayland=false \
        -Denable-docs=false \
        -Denable-tools=false \
        -Denable-xkbregistry=false \
        -Denable-bash-completion=false
    ninja -C "$WORK_DIR/$builddir"
done

echo "==> Done: $WORK_DIR"
