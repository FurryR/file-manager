#!/usr/bin/env bash
# Build every native artifact the APK packages.
#
# Single entry point shared by Gradle's `preBuild` hook, CI and manual builds.
# The individual scripts stay incremental where they can; Gradle skips this
# whole task when no declared native input changed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> [1/6] libsepol + container policy shim"
bash native/build-libsepol.sh aarch64

echo "==> [2/6] libxkbcommon"
bash native/build-xkbcommon.sh aarch64

echo "==> [3/6] xkeyboard-config data"
bash scripts/fetch-xkbdata.sh

echo "==> [4/6] file daemon, compositor and CLI utilities"
bash native/build-android-aarch64.sh

echo "==> [5/6] proot"
bash native/build-proot.sh

echo "==> [6/6] PulseAudio host"
bash native/build-pulse.sh
