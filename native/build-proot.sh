#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_DIR="$ROOT_DIR/native"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
WORK_DIR="$(mktemp -d /tmp/proot-build-XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> Building proot for aarch64-linux-android (static with embedded loader)"

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
  echo "Android NDK not found. Install it with: sdkmanager 'ndk;27.2.12479018'" >&2
  exit 1
fi

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$TOOLCHAIN/aarch64-linux-android26-clang"
AR="$TOOLCHAIN/llvm-ar"
STRIP="$TOOLCHAIN/llvm-strip"
OBJCOPY="$TOOLCHAIN/llvm-objcopy"
READELF="$TOOLCHAIN/llvm-readelf"

if [[ ! -x "$CC" ]]; then
  echo "Android ARM64 clang not found: $CC" >&2
  exit 1
fi

echo "NDK toolchain: $CC"

# --- Clone sources ---
echo "==> Cloning sources"

PROOT_SRC="$WORK_DIR/proot"
TALLOC_SRC="$WORK_DIR/talloc"
BUILD_DIR="$WORK_DIR/build"
mkdir -p "$BUILD_DIR"
OBJ_DIR="$BUILD_DIR/obj"
mkdir -p "$OBJ_DIR"

if ! git clone --depth 1 --branch v5.1.107.79 https://github.com/termux/proot.git "$PROOT_SRC" 2>/dev/null; then
  echo "Failed to clone proot" >&2
  exit 1
fi

# Download talloc
TALLOC_VER="2.4.3"
TALLOC_URL="https://download.samba.org/pub/talloc/talloc-${TALLOC_VER}.tar.gz"
if ! curl -sL "$TALLOC_URL" | tar xz -C "$WORK_DIR"; then
  echo "Failed to download talloc" >&2
  exit 1
fi
mv "$WORK_DIR/talloc-${TALLOC_VER}" "$TALLOC_SRC"

# --- Create minimal build.h ---
cat > "$BUILD_DIR/build.h" << 'EOF'
#ifndef BUILD_H
#define BUILD_H
#define VERSION "5.1.107.79"
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
echo "==> Compiling talloc"
CFLAGS_TALLOC=(-I"$TALLOC_SRC" -Os -fPIC)
"$CC" "${CFLAGS_TALLOC[@]}" -c "$TALLOC_SRC/talloc.c" -o "$BUILD_DIR/talloc.o"
"$AR" rcs "$BUILD_DIR/libtalloc.a" "$BUILD_DIR/talloc.o"

# ============================================================
# 1. Build the loader executable (without loader-info)
# ============================================================
echo "==> Building loader executable"

LOADER_SRC_DIR="$PROOT_SRC/src/loader"
LOADER_BIN="$BUILD_DIR/loader_exe"

"$CC" -nostdlib -static -ffreestanding -Os \
    -I"$PROOT_SRC/src" -I"$BUILD_DIR" \
    "$LOADER_SRC_DIR/loader.c" \
    "$LOADER_SRC_DIR/assembly.S" \
    -o "$LOADER_BIN" -Wl,-z,noexecstack

# ============================================================
# 2. Generate loader-info.c using awk
# ============================================================
echo "==> Generating loader-info.c with awk"

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

echo "Generated loader-info.c:"
cat "$BUILD_DIR/loader-info.c"

# ============================================================
# 3. Convert loader executable to object for embedding
#    Make sure symbol names are _binary_loader_exe_start/_end
# ============================================================
echo "==> Embedding loader into object file"

# Change to BUILD_DIR and copy loader_exe to loader.exe to get clean symbol names
cd "$BUILD_DIR"
cp loader_exe loader.exe
"$OBJCOPY" -I binary -O elf64-littleaarch64 -B aarch64 \
    loader.exe loader_exe.o
LOADER_OBJ="$BUILD_DIR/loader_exe.o"

# ============================================================
# 4. Compile loader-info.c for main program
# ============================================================
echo "==> Compiling loader-info for main program"
"$CC" -Os -I"$BUILD_DIR" -c "$BUILD_DIR/loader-info.c" -o "$BUILD_DIR/loader-info.o"

# ============================================================
# 5. Compile main proot sources (excluding loader/ and .check_*)
# ============================================================
echo "==> Compiling proot"

CFLAGS=(-D_GNU_SOURCE -I"$PROOT_SRC/src" -I"$TALLOC_SRC" -I"$BUILD_DIR" -Os -fPIC -Wall -Wno-macro-redefined)

cd "$BUILD_DIR" # already there

FAILED=0
SUCCESS=0

# Find all .c files in src/, but exclude loader/ and any .check_* files
for src in $(find "$PROOT_SRC/src" -name "*.c" ! -name ".check_*" | sort); do
    rel="${src#$PROOT_SRC/src/}"
    # Skip loader/ directory
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
    echo "Compilation errors encountered" >&2
    exit 1
fi

# Remove any stray .check_*.o files (just in case)
find "$OBJ_DIR" -name ".check_*.o" -delete 2>/dev/null || true

# ============================================================
# 6. Link everything together
# ============================================================
echo "==> Linking"

# Collect all object files from OBJ_DIR (main program objects)
OBJ_FILES=()
while IFS= read -r -d '' obj; do
    OBJ_FILES+=("$obj")
done < <(find "$OBJ_DIR" -name "*.o" -print0 | sort -z)

# Add loader-info.o and embedded loader object
OBJ_FILES+=("$BUILD_DIR/loader-info.o")
OBJ_FILES+=("$LOADER_OBJ")

echo "  Linking ${#OBJ_FILES[@]} objects..."

OUT_BIN="$BUILD_DIR/proot-aarch64"
"$CC" -static "${OBJ_FILES[@]}" "$BUILD_DIR/libtalloc.a" \
    -o "$OUT_BIN" -Wl,-z,noexecstack

# ARM64 bionic TLS alignment fix
if [[ -f "$NATIVE_DIR/align_fix.py" ]]; then
    python3 "$NATIVE_DIR/align_fix.py" "$OUT_BIN"
else
    echo "Warning: align_fix.py not found; skipping TLS alignment fix." >&2
fi

# --- Strip and install ---
echo "==> Stripping and installing"
"$STRIP" "$OUT_BIN"

mkdir -p "$JNI_DIR"
cp "$OUT_BIN" "$JNI_DIR/libproot.so"

echo "==> Done"
echo "Binary: $JNI_DIR/libproot.so"
file "$JNI_DIR/libproot.so"
ls -lh "$JNI_DIR/libproot.so"
