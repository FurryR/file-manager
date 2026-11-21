#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the modified PRoot binary used by the container daemon for every
# requested Android ABI.
#
# Usage: bash native/build-proot.sh [arm64-v8a|armeabi-v7a|x86|x86_64 ...]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_DIR="$ROOT_DIR/native"
ALL_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
if (( $# == 0 )); then
  ABIS=("${ALL_ABIS[@]}")
else
  ABIS=("$@")
fi
WORK_ROOT="$(mktemp -d /tmp/proot-build-XXXXXX)"
trap 'rm -rf "$WORK_ROOT"' EXIT

# --- NDK detection ---
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
  echo "Android NDK not found. Install it with: sdkmanager 'ndk;30.0.16248370'" >&2
  exit 1
fi
HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"

# C-side size flags. PRoot and talloc are pure C, so fat LTO stays entirely
# inside this build; there is no cross-language bitcode to match.
SIZE_CFLAGS=(-Oz -ffunction-sections -fdata-sections)
FAT_LTO=(-flto)

# --- Prepare sources once ---
echo "==> Preparing sources"

PROOT_VERSION="5.1.107.79"
PROOT_COMMIT="898b4a8494e62e225d23c32713e76201ce7c1561"
PROOT_URL="${PROOT_URL-https://github.com/termux/proot.git}"
PROOT_CACHE="$ROOT_DIR/cache/native/proot-$PROOT_VERSION"
PROOT_SRC="$WORK_ROOT/proot"
TALLOC_SRC="$WORK_ROOT/talloc"

if [[ ! -f "$PROOT_CACHE/src/loader/loader.c" ]]; then
    echo "==> Cloning proot v$PROOT_VERSION"
    rm -rf "$PROOT_CACHE"
    mkdir -p "$(dirname "$PROOT_CACHE")"
    git clone --depth 1 --branch "v$PROOT_VERSION" "$PROOT_URL" "$PROOT_CACHE"
fi
if [[ -d "$PROOT_CACHE/.git" ]]; then
    actual_commit="$(git -C "$PROOT_CACHE" rev-parse HEAD)"
    if [[ "$actual_commit" != "$PROOT_COMMIT" ]]; then
        echo "proot v$PROOT_VERSION resolved to $actual_commit, expected $PROOT_COMMIT" >&2
        exit 1
    fi
fi
cp -a "$PROOT_CACHE/." "$PROOT_SRC/"
rm -rf "$PROOT_SRC/.git"

PATCH_DIR="$NATIVE_DIR/patches/proot"
shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
shopt -u nullglob
if (( ${#patches[@]} == 0 )); then
  echo "No proot patches found in $PATCH_DIR" >&2
  exit 1
fi
for patch in "${patches[@]}"; do
  echo "==> Applying $(basename "$patch")"
  if ! patch --batch -p1 -d "$PROOT_SRC" -i "$patch"; then
    echo "Failed to apply $patch" >&2
    exit 1
  fi
done

TALLOC_VER="2.4.3"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"
TALLOC_URL="https://download.samba.org/pub/talloc/talloc-${TALLOC_VER}.tar.gz"
TALLOC_CACHE="$ROOT_DIR/cache/file-manager/talloc-${TALLOC_VER}.tar.gz"
mkdir -p "$(dirname "$TALLOC_CACHE")"
if [[ -f "$TALLOC_CACHE" ]] &&
   ! echo "$TALLOC_SHA256  $TALLOC_CACHE" | sha256sum --check --status; then
  echo "cached talloc checksum mismatch; downloading it again" >&2
  rm -f "$TALLOC_CACHE"
fi
if [[ ! -f "$TALLOC_CACHE" ]]; then
  echo "==> Fetching talloc $TALLOC_VER"
  curl -fL --retry 3 --connect-timeout 20 --max-time 600 \
    "$TALLOC_URL" -o "$TALLOC_CACHE.part"
  mv "$TALLOC_CACHE.part" "$TALLOC_CACHE"
  echo "$TALLOC_SHA256  $TALLOC_CACHE" | sha256sum --check
fi
tar xzf "$TALLOC_CACHE" -C "$WORK_ROOT"
mv "$WORK_ROOT/talloc-${TALLOC_VER}" "$TALLOC_SRC"

# --- Build each ABI ---
for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a)
      CC_TRIPLE="aarch64-linux-android"
      OBJCOPY_FORMAT="elf64-littleaarch64"
      OBJCOPY_ARCH="aarch64"
      PAGE_FLAGS=(-Wl,-z,max-page-size=16384)
      ;;
    armeabi-v7a)
      CC_TRIPLE="armv7a-linux-androideabi"
      OBJCOPY_FORMAT="elf32-littlearm"
      OBJCOPY_ARCH="arm"
      PAGE_FLAGS=()
      ;;
    x86)
      CC_TRIPLE="i686-linux-android"
      OBJCOPY_FORMAT="elf32-i386"
      OBJCOPY_ARCH="i386"
      PAGE_FLAGS=()
      ;;
    x86_64)
      CC_TRIPLE="x86_64-linux-android"
      OBJCOPY_FORMAT="elf64-x86-64"
      OBJCOPY_ARCH="i386:x86-64"
      PAGE_FLAGS=(-Wl,-z,max-page-size=16384)
      ;;
    *)
      echo "unsupported ABI: $abi" >&2
      exit 1
      ;;
  esac

  CC="$TOOLCHAIN/${CC_TRIPLE}26-clang"
  AR="$TOOLCHAIN/llvm-ar"
  STRIP="$TOOLCHAIN/llvm-strip"
  OBJCOPY="$TOOLCHAIN/llvm-objcopy"
  READELF="$TOOLCHAIN/llvm-readelf"
  [[ -x "$CC" ]] || { echo "Android linker not found: $CC" >&2; exit 1; }

  BUILD_DIR="$WORK_ROOT/build-$abi"
  OBJ_DIR="$BUILD_DIR/obj"
  JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/$abi"
  mkdir -p "$BUILD_DIR" "$OBJ_DIR" "$JNI_DIR"
  echo "==> Building proot for $abi ($CC_TRIPLE)"

  (
    # --- Create minimal build.h ---
    cat > "$BUILD_DIR/build.h" << EOF
#ifndef BUILD_H
#define BUILD_H
#define VERSION "$PROOT_VERSION"
#define HAVE_PROCESS_VM
#define HAVE_SECCOMP_FILTER
#endif /* BUILD_H */
EOF

    # --- Create minimal replace.h for talloc ---
    cat > "$TALLOC_SRC/replace.h" << 'EOF'
#ifndef _REPLACE_H_
#define _REPLACE_H_
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>

#define _PUBLIC_ __attribute__((visibility("default")))
#define _PRIVATE_ __attribute__((visibility("hidden")))

#define TALLOC_VERSION_MAJOR 2
#define TALLOC_VERSION_MINOR 4
#define TALLOC_VERSION_RELEASE 3
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3

#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1

/* The NDK <stdarg.h> always provides va_copy. Without this talloc.c falls
 * back to "#define va_copy(dest, src) (dest) = (src)", which fails to
 * compile where va_list is an array type (e.g. x86_64). */
#define HAVE_VA_COPY 1

#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif

#ifndef MAX
#define MAX(a,b) ((a) > (b) ? (a) : (b))
#endif

#include <stdbool.h>
#endif /* _REPLACE_H_ */
EOF

    # --- Compile talloc ---
    echo "==> Compiling talloc ($abi)"
    CFLAGS_TALLOC=(-I"$TALLOC_SRC" -fPIC "${SIZE_CFLAGS[@]}" "${FAT_LTO[@]}")
    "$CC" "${CFLAGS_TALLOC[@]}" -c "$TALLOC_SRC/talloc.c" -o "$BUILD_DIR/talloc.o"
    "$AR" rcs "$BUILD_DIR/libtalloc.a" "$BUILD_DIR/talloc.o"

    # 1. Build the loader executable (without loader-info).
    echo "==> Building loader executable ($abi)"
    LOADER_SRC_DIR="$PROOT_SRC/src/loader"
    LOADER_BIN="$BUILD_DIR/loader_exe"
    "$CC" -nostdlib -static -ffreestanding -Oz -ffunction-sections -fdata-sections \
        -I"$PROOT_SRC/src" -I"$BUILD_DIR" \
        "$LOADER_SRC_DIR/loader.c" \
        "$LOADER_SRC_DIR/assembly.S" \
        -o "$LOADER_BIN" -Wl,--gc-sections -Wl,--icf=all \
        -Wl,-z,noexecstack "${PAGE_FLAGS[@]}"

    # 2. Generate loader-info.c using awk.
    echo "==> Generating loader-info.c with awk ($abi)"
    cat > "$BUILD_DIR/loader-info.awk" << 'EOF'
# Note: This file is included only for targets which have pokedata workaround
/\ypokedata_workaround\y/{pokedata_workaround=strtonum("0x" $2)}
/\y_start\y/{start=strtonum("0x" $2)}
END {
	print "#include <unistd.h>"
	print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround-start) ";"
}
EOF
    "$READELF" -s "$LOADER_BIN" | awk -f "$BUILD_DIR/loader-info.awk" > "$BUILD_DIR/loader-info.c"
    cat "$BUILD_DIR/loader-info.c"

    # 3. Convert loader executable to object for embedding.
    echo "==> Embedding loader into object file ($abi)"
    cd "$BUILD_DIR"
    cp loader_exe loader.exe
    "$OBJCOPY" -I binary -O "$OBJCOPY_FORMAT" -B "$OBJCOPY_ARCH" \
        loader.exe loader_exe.o
    LOADER_OBJ="$BUILD_DIR/loader_exe.o"

    # 4. Compile loader-info.c for the main program.
    echo "==> Compiling loader-info for main program ($abi)"
    "$CC" "${SIZE_CFLAGS[@]}" -I"$BUILD_DIR" -c "$BUILD_DIR/loader-info.c" -o "$BUILD_DIR/loader-info.o"

    # 5. Compile main proot sources (excluding loader/ and .check_*).
    echo "==> Compiling proot ($abi)"
    CFLAGS=(-D_GNU_SOURCE -I"$PROOT_SRC/src" -I"$TALLOC_SRC" -I"$BUILD_DIR" -fPIC "${SIZE_CFLAGS[@]}" "${FAT_LTO[@]}" -Wall -Wno-macro-redefined)
    FAILED=0
    SUCCESS=0
    for src in $(find "$PROOT_SRC/src" -name "*.c" ! -name ".check_*" | sort); do
        rel="${src#$PROOT_SRC/src/}"
        if [[ "$rel" == loader/* ]]; then
            continue
        fi
        obj="$OBJ_DIR/${rel%.c}.o"
        mkdir -p "$(dirname "$obj")"

        EXTRA_FLAGS=()
        if [[ "$rel" == "extension/ashmem_memfd/ashmem_memfd.c" ]]; then
            EXTRA_FLAGS=(-include string.h)
        fi

        if "$CC" "${CFLAGS[@]}" "${EXTRA_FLAGS[@]}" -c "$src" -o "$obj" 2>/dev/null; then
            SUCCESS=$((SUCCESS + 1))
        else
            FAILED=$((FAILED + 1))
            echo "  FAIL: $rel" >&2
            "$CC" "${CFLAGS[@]}" "${EXTRA_FLAGS[@]}" -c "$src" -o "$obj" 2>&1 | head -2 >&2
        fi
    done
    echo "  Compiled: $SUCCESS, Failed: $FAILED"
    if [[ "$FAILED" -gt 0 ]]; then
        echo "Compilation errors encountered for $abi" >&2
        exit 1
    fi
    find "$OBJ_DIR" -name ".check_*.o" -delete 2>/dev/null || true

    # 6. Link everything together.
    echo "==> Linking ($abi)"
    OBJ_FILES=()
    while IFS= read -r -d '' obj; do
        OBJ_FILES+=("$obj")
    done < <(find "$OBJ_DIR" -name "*.o" -print0 | sort -z)
    OBJ_FILES+=("$BUILD_DIR/loader-info.o")
    OBJ_FILES+=("$LOADER_OBJ")
    echo "  Linking ${#OBJ_FILES[@]} objects..."

    OUT_BIN="$BUILD_DIR/proot-$abi"
    "$CC" -static "${OBJ_FILES[@]}" "$BUILD_DIR/libtalloc.a" \
        -o "$OUT_BIN" -Wl,-z,noexecstack -Wl,--gc-sections -Wl,--icf=all \
        -Wl,--strip-all "${PAGE_FLAGS[@]}"

    echo "==> Stripping and installing ($abi)"
    "$STRIP" "$OUT_BIN"
    cp "$OUT_BIN" "$JNI_DIR/libproot.so"
  )

  echo "==> Done: $JNI_DIR/libproot.so"
  file "$JNI_DIR/libproot.so"
  ls -lh "$JNI_DIR/libproot.so"
done

mkdir -p "$ROOT_DIR/app/src/main/assets/native-licenses"
cp "$TALLOC_SRC/LICENSE" "$ROOT_DIR/app/src/main/assets/native-licenses/COPYING-talloc"
