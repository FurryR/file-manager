#!/usr/bin/env bash
# Compatibility wrapper: the Rust build now covers every ABI.
# The old aarch64-only entry point keeps its fast single-ABI default.
set -euo pipefail
if (( $# == 0 )); then
  set -- arm64-v8a
fi
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-android.sh" "$@"
