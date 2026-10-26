#!/usr/bin/env bash
set -euo pipefail

# One feature, one .so: PulseAudio + libsndfile + libltdl are static archives.
# No D-Bus, GLib, session manager, module files or external PulseAudio daemon.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_DIR="$ROOT_DIR/native/pulse"
WORK_DIR="${PULSE_BUILD_DIR:-/tmp/opencode/pulse-cross-17.0}"
PREFIX="$WORK_DIR/deps"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android}}"
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_ROOT" ]]; then
    NDK_ROOT="$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
export CC="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android26-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export NM="$TOOLCHAIN/bin/llvm-nm"
export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export PATH="$HOME/.local/bin:$TOOLCHAIN/bin:$PATH"
[[ -x "$CC" ]] || { echo "Android API-26 ARM64 compiler not found: $CC" >&2; exit 1; }
mkdir -p "$WORK_DIR" "$PREFIX"

download() {
    local name="$1" url="$2" checksum="$3"
    if [[ ! -f "$WORK_DIR/$name" ]]; then
        curl -fL --retry 3 --connect-timeout 20 --max-time 600 "$url" -o "$WORK_DIR/$name.part"
        mv "$WORK_DIR/$name.part" "$WORK_DIR/$name"
    fi
    echo "$checksum  $WORK_DIR/$name" | sha256sum --check
}
download pulseaudio-17.0.tar.xz https://freedesktop.org/software/pulseaudio/releases/pulseaudio-17.0.tar.xz \
    053794d6671a3e397d849e478a80b82a63cb9d8ca296bd35b73317bb5ceb87b5
download libsndfile-1.2.2.tar.gz https://codeload.github.com/libsndfile/libsndfile/tar.gz/refs/tags/1.2.2 \
    ffe12ef8add3eaca876f04087734e6e8e029350082f3251f565fa9da55b52121
download libtool-2.4.7.tar.xz https://ftpmirror.gnu.org/libtool/libtool-2.4.7.tar.xz \
    4f7f217f057ce655ff22559ad221a0fd8ef84ad1fc5fcb6990cecc333aa1635d

if [[ ! -f "$PREFIX/lib/libltdl.a" ]]; then
    tar -xf "$WORK_DIR/libtool-2.4.7.tar.xz" -C "$WORK_DIR"
    mkdir -p "$WORK_DIR/ltdl-build"
    (
        cd "$WORK_DIR/ltdl-build"
        CFLAGS="-Os -fPIC" "$WORK_DIR/libtool-2.4.7/libltdl/configure" \
            --host=aarch64-linux-android --prefix="$PREFIX" --libdir="$PREFIX/lib" \
            --disable-shared --enable-static --with-pic --enable-ltdl-install
        make -j"$(nproc)"
        make install
    )
fi
if [[ ! -f "$PREFIX/lib/libsndfile.a" ]]; then
    tar -xf "$WORK_DIR/libsndfile-1.2.2.tar.gz" -C "$WORK_DIR"
    cmake -S "$WORK_DIR/libsndfile-1.2.2" -B "$WORK_DIR/sndfile-build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib \
        -DBUILD_SHARED_LIBS=OFF -DBUILD_PROGRAMS=OFF -DBUILD_EXAMPLES=OFF \
        -DBUILD_TESTING=OFF -DENABLE_EXTERNAL_LIBS=OFF -DENABLE_MPEG=OFF
    cmake --build "$WORK_DIR/sndfile-build" --parallel
    cmake --install "$WORK_DIR/sndfile-build"
fi

PA_SRC="$WORK_DIR/pulseaudio-17.0"
PA_BUILD="$WORK_DIR/build"
if [[ ! -d "$PA_SRC" ]]; then tar -xf "$WORK_DIR/pulseaudio-17.0.tar.xz" -C "$WORK_DIR"; fi
python3 "$HOST_DIR/prepare.py" "$PA_SRC"
cat > "$WORK_DIR/cross.ini" <<EOF
[binaries]
c = '$CC'
ar = '$AR'
strip = '$STRIP'
pkg-config = '$(command -v pkg-config)'
[built-in options]
c_args = ['-Os', '-fPIC', '-ffunction-sections', '-fdata-sections', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib']
pkg_config_path = ['$PREFIX/lib/pkgconfig']
[properties]
pkg_config_libdir = ['$PREFIX/lib/pkgconfig']
needs_exe_wrapper = true
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
SETUP_FLAGS=()
if [[ -d "$PA_BUILD/meson-private" ]]; then SETUP_FLAGS+=(--reconfigure); fi
meson setup "$PA_BUILD" "$PA_SRC" --cross-file "$WORK_DIR/cross.ini" \
    --default-library static --buildtype release "${SETUP_FLAGS[@]}" \
    -Dauto_features=disabled -Ddatabase=simple -Ddbus=disabled \
    -Dclient=true -Ddaemon=true -Dtests=false -Ddoxygen=false -Dman=false \
    -Dadrian-aec=true -Dbashcompletiondir=no -Dzshcompletiondir=no
ninja -C "$PA_BUILD" src/libpulsecommon-17.0.a src/pulse/libpulse.a \
    src/pulsecore/libpulsecore-17.0.a src/pulsecore/libprotocol-native.a

OUT="$WORK_DIR/out"
mkdir -p "$OUT"
"$CC" -shared -Os -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
    -DHAVE_CONFIG_H -D_GNU_SOURCE -D__INCLUDED_FROM_PULSE_AUDIO \
    -I"$PA_BUILD" -I"$PA_BUILD/src" -I"$PA_SRC/src" -I"$PREFIX/include" \
    "$HOST_DIR/pulse_host.c" \
    -Wl,--start-group \
    "$PA_BUILD/src/pulsecore/libprotocol-native.a" \
    "$PA_BUILD/src/pulsecore/libpulsecore-17.0.a" \
    "$PA_BUILD/src/libpulsecommon-17.0.a" "$PA_BUILD/src/pulse/libpulse.a" \
    "$PREFIX/lib/libsndfile.a" "$PREFIX/lib/libltdl.a" \
    -Wl,--end-group -Wl,--exclude-libs,ALL -Wl,--gc-sections \
    -Wl,-z,defs -Wl,-z,max-page-size=16384 -Wl,-soname,libpulsehost.so \
    -lm -llog -ldl -o "$OUT/libpulsehost.so"
"$STRIP" --strip-unneeded "$OUT/libpulsehost.so"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
cp "$OUT/libpulsehost.so" "$JNI_DIR/libpulsehost.so"
LICENSE_DIR="$ROOT_DIR/app/src/main/assets/native-licenses"
mkdir -p "$LICENSE_DIR"
cp "$PA_SRC/LICENSE" "$LICENSE_DIR/LICENSE-PulseAudio"
cp "$PA_SRC/LGPL" "$LICENSE_DIR/LGPL-2.1"
cp "$WORK_DIR/libsndfile-1.2.2/COPYING" "$LICENSE_DIR/COPYING-libsndfile"
cp "$WORK_DIR/libtool-2.4.7/libltdl/COPYING.LIB" "$LICENSE_DIR/COPYING-libltdl"
# Retire only the replaced audio artifact, keeping a backup.
if [[ -f "$JNI_DIR/libpipewirehost.so" ]]; then
    cp "$JNI_DIR/libpipewirehost.so" "$WORK_DIR/libpipewirehost.so.backup"
    rm "$JNI_DIR/libpipewirehost.so"
fi
echo "Installed single audio library: $JNI_DIR/libpulsehost.so"
