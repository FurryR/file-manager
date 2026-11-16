#!/usr/bin/env bash
set -euo pipefail

# libsepol, statically linked into libfiledaemon.so.
#
# The daemon extends the running SELinux policy at container startup: read
# /sys/fs/selinux/policy, add the container domain and rules, then write the
# image back through /sys/fs/selinux/load. Android ships no libsepol, and its
# policy structs are not a stable ABI, so the version is pinned and checksummed
# here and the archive is linked into the one daemon artifact. Rust only sees
# the C boundary it owns (native/src/sepolicy.rs).
#
# Usage: bash native/build-libsepol.sh [aarch64|x86_64]
#
# Downloads go through gh-proxy.com by default (GFW). Override the mirror with
# GH_PROXY=<url>, or set GH_PROXY= to fetch from github.com directly.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBSEPOL_VERSION="3.11"
LIBSEPOL_SHA256="6ac2a91c05243895e69d5ecd182b78e3f90f09f0fed33933f0e026f4ed44cde6"
GH_PROXY="${GH_PROXY-https://gh-proxy.com}"

ABI="${1:-aarch64}"
case "$ABI" in
    aarch64) TRIPLE="aarch64-linux-android" ;;
    x86_64) TRIPLE="x86_64-linux-android" ;;
    *) echo "unsupported ABI: $ABI (use aarch64 or x86_64)" >&2; exit 1 ;;
esac
API=26

case "$(uname -s)" in
    Linux*) HOST_TAG="linux-x86_64" ;;
    Darwin*) HOST_TAG="darwin-x86_64" ;;
    *) echo "unsupported NDK host: $(uname -s)" >&2; exit 1 ;;
esac
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" ]]; then
    for candidate in "$HOME/.local/share/android" "$HOME/Android/Sdk"; do
        if [[ -d "$candidate" ]]; then SDK_ROOT="$candidate"; break; fi
    done
fi
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_ROOT" && -d "$SDK_ROOT/ndk" ]]; then
    NDK_ROOT="$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
    echo "Android NDK not found. Install it with: sdkmanager 'ndk;30.0.16248370'" >&2
    exit 1
fi
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
NM="$TOOLCHAIN/bin/llvm-nm"
[[ -x "$CC" ]] || { echo "Android API-$API compiler not found: $CC" >&2; exit 1; }

WORK_DIR="${LIBSEPOL_BUILD_DIR:-$ROOT_DIR/cache/native/libsepol-${LIBSEPOL_VERSION}-${ABI}}"
CACHE_DIR="$ROOT_DIR/cache/file-manager"
SRC="$WORK_DIR/selinux-$LIBSEPOL_VERSION"
PREFIX="$ROOT_DIR/native/sepolicy/prefix/$TRIPLE"
TARBALL="$CACHE_DIR/selinux-$LIBSEPOL_VERSION.tar.gz"

mkdir -p "$WORK_DIR" "$CACHE_DIR"
if [[ ! -f "$TARBALL" ]]; then
    URL="https://codeload.github.com/SELinuxProject/selinux/tar.gz/refs/tags/$LIBSEPOL_VERSION"
    if [[ -n "$GH_PROXY" ]]; then URL="$GH_PROXY/$URL"; fi
    curl -fL --retry 3 --connect-timeout 20 --max-time 600 "$URL" -o "$TARBALL.part"
    mv "$TARBALL.part" "$TARBALL"
fi
echo "$LIBSEPOL_SHA256  $TARBALL" | sha256sum --check
if [[ ! -d "$SRC" ]]; then tar -xf "$TARBALL" -C "$WORK_DIR"; fi

# The static archive is linked into a shared object, so every object is PIC
# (libsepol's %.o rule already adds -fPIC). CIL is the policy source language;
# the daemon edits the binary policydb directly, so its parser and the flex
# build dependency stay out. Set LIBSEPOL_DISABLE_CIL=n to keep CIL anyway.
CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden"
DISABLE_CIL="${LIBSEPOL_DISABLE_CIL:-y}"
STAMP="$WORK_DIR/build.stamp"
STAMP_KEY="$LIBSEPOL_VERSION|$TRIPLE|$CFLAGS|$DISABLE_CIL"
if [[ ! -f "$STAMP" || "$(<"$STAMP")" != "$STAMP_KEY" ]]; then
    make -C "$SRC/libsepol/src" clean >/dev/null 2>&1 || true
fi
make -C "$SRC/libsepol/src" -j"$(nproc)" libsepol.a \
    CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    CFLAGS="$CFLAGS" DISABLE_CIL="$DISABLE_CIL"
printf '%s\n' "$STAMP_KEY" > "$STAMP"

mkdir -p "$PREFIX/lib" "$PREFIX/include"
cp "$SRC/libsepol/src/libsepol.a" "$PREFIX/lib/libsepol.a"
rm -rf "$PREFIX/include/sepol"
cp -R "$SRC/libsepol/include/sepol" "$PREFIX/include/sepol"

# The daemon links exactly one archive: the container policy shim followed by
# every libsepol member it may call. Merging them keeps archive order out of
# the Rust link line.
"$CC" -std=c11 -O2 -fPIC -fvisibility=hidden -ffunction-sections \
    -fdata-sections -Wall -Wextra -Wno-unused-parameter \
    -I"$PREFIX/include" -c "$ROOT_DIR/native/sepolicy/shim.c" \
    -o "$WORK_DIR/shim.o"
rm -f "$PREFIX/lib/libfile_container.a"
"$AR" -M <<EOF
create $PREFIX/lib/libfile_container.a
addmod $WORK_DIR/shim.o
addlib $PREFIX/lib/libsepol.a
save
end
EOF

# The daemon links -lstatic=file_container; catch a silently empty or misbuilt
# archive at pipeline time instead of at the first policy load.
SYMBOLS="$("$NM" -g --defined-only "$PREFIX/lib/libsepol.a")"
missing=0
for symbol in \
    sepol_policy_file_create sepol_policy_file_free sepol_policy_file_set_mem \
    sepol_policydb_create sepol_policydb_free sepol_policydb_read \
    sepol_policydb_to_image sepol_policy_kern_vers_max sepol_policy_kern_vers_min; do
    if ! grep -qE " [Tt] $symbol$" <<<"$SYMBOLS"; then
        echo "missing symbol after build: $symbol" >&2
        missing=1
    fi
done

SHIM_SYMBOLS="$("$NM" -g --defined-only "$PREFIX/lib/libfile_container.a")"
for symbol in \
    file_container_ensure file_container_context file_container_string_free; do
    if ! grep -qE " [Tt] $symbol$" <<<"$SHIM_SYMBOLS"; then
        echo "missing symbol in the shim archive: $symbol" >&2
        missing=1
    fi
done
[[ "$missing" -eq 0 ]] || exit 1

# libsepol is LGPL-2.1. The archive is linked statically, so ship the notice
# next to the other native licenses.
LICENSE_DIR="$ROOT_DIR/app/src/main/assets/native-licenses"
mkdir -p "$LICENSE_DIR"
cp "$SRC/libsepol/LICENSE" "$LICENSE_DIR/LICENSE-libsepol"

echo "libsepol $LIBSEPOL_VERSION ($TRIPLE): $PREFIX/lib/libsepol.a"
echo "container policy shim ($TRIPLE): $PREFIX/lib/libfile_container.a"
du -h "$PREFIX/lib/libfile_container.a" | cut -f1
