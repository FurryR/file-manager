#!/usr/bin/env bash
# Build every native artifact the APK packages.
#
# Debug builds pass their single ABI (arm64-v8a by default); release builds
# pass all four. The APK build calls this once per variant and reuses the
# staged jniLibs when a later variant only needs a subset.
#
# Usage: bash scripts/build-native.sh [abi ...]
#   abi: arm64-v8a, armeabi-v7a, x86, x86_64
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ALL_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
if (( $# == 0 )); then
    ABIS=("${ALL_ABIS[@]}")
else
    ABIS=("$@")
fi

for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a|armeabi-v7a|x86|x86_64) ;;
        *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
    esac
done

echo "==> [1/6] libsepol + container policy shim (${ABIS[*]})"
for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a) native_abi=aarch64 ;;
        armeabi-v7a) native_abi=armv7 ;;
        x86) native_abi=x86 ;;
        x86_64) native_abi=x86_64 ;;
    esac
    bash native/build-libsepol.sh "$native_abi"
done

echo "==> [2/6] libxkbcommon (${ABIS[*]})"
native_abis=()
for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a) native_abis+=(aarch64) ;;
        armeabi-v7a) native_abis+=(armv7) ;;
        x86) native_abis+=(x86) ;;
        x86_64) native_abis+=(x86_64) ;;
    esac
done
bash native/build-xkbcommon.sh "${native_abis[@]}"

echo "==> [3/6] xkeyboard-config data"
bash scripts/fetch-xkbdata.sh

echo "==> [4/6] file daemon, compositor, ad blocker and CLI utilities"
bash native/build-android.sh "${ABIS[@]}"

echo "==> [5/6] proot"
bash native/build-proot.sh "${ABIS[@]}"

echo "==> [6/6] PulseAudio host"
bash native/build-pulse.sh "${ABIS[@]}"
