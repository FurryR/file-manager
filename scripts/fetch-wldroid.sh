#!/usr/bin/env bash
# Fetch the prebuilt wldroid Maven repository (compositor + virgl + shims + ui
# AARs, with the cross-compiled wlroots/virglrenderer natives inside) and
# extract it to third_party/wldroid-maven, which settings.gradle.kts registers
# as a Maven repository for the group nu.shel.wldroid.
#
# wldroid publishes no artifacts to Maven Central; each GitHub Release attaches
# a self-contained maven-repo zip. Downloading it here keeps the repo free of
# binary blobs and avoids the (much heavier) local meson/NDK rebuild of wlroots
# and virglrenderer.
#
# GitHub is tried directly first; on networks where that fails the gh-proxy.com
# mirror is used as a fallback.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT_DIR/third_party/wldroid-maven"
WORK_DIR="$(mktemp -d /tmp/wldroid-fetch-XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

WLDROID_VERSION="0.0.5"
WLDROID_REPO="https://github.com/Shelnutt2/wldroid"
ZIP_NAME="wldroid-maven-repo.zip"
RELEASE_BASE="$WLDROID_REPO/releases/download/v$WLDROID_VERSION"

echo "==> Fetching wldroid maven repo v$WLDROID_VERSION"
mkdir -p "$DEST_DIR"

URLS=(
    "$RELEASE_BASE/$ZIP_NAME"
    "https://gh-proxy.com/$RELEASE_BASE/$ZIP_NAME"
)
ZIP_FILE="$WORK_DIR/$ZIP_NAME"
downloaded=0
for url in "${URLS[@]}"; do
    echo "    trying $url"
    if curl -fsSL --retry 2 -o "$ZIP_FILE" "$url"; then
        downloaded=1
        break
    fi
done
if [ "$downloaded" != "1" ]; then
    echo "ERROR: could not download $ZIP_NAME from any mirror" >&2
    exit 1
fi

echo "==> Extracting to $DEST_DIR"
rm -rf "$DEST_DIR"/*
# The zip contains the repository either at the root or under a single
# top-level directory; normalize to the repository root.
unzip -q "$ZIP_FILE" -d "$WORK_DIR/unpacked"
repo_root="$WORK_DIR/unpacked"
top_level=("$repo_root"/*)
if [ ${#top_level[@]} -eq 1 ] && [ -d "${top_level[0]}" ] &&
    [ -d "${top_level[0]}/nu/shel/wldroid" ]; then
    repo_root="${top_level[0]}"
fi
if [ ! -d "$repo_root/nu/shel/wldroid" ]; then
    echo "ERROR: archive does not look like a wldroid maven repo (missing nu/shel/wldroid)" >&2
    exit 1
fi
cp -a "$repo_root"/. "$DEST_DIR"/

echo "==> Verifying artifacts"
for artifact in wldroid-compositor wldroid-virgl wldroid-shims wldroid-ui; do
    aar="$DEST_DIR/nu/shel/wldroid/$artifact/$WLDROID_VERSION/$artifact-$WLDROID_VERSION.aar"
    pom="$DEST_DIR/nu/shel/wldroid/$artifact/$WLDROID_VERSION/$artifact-$WLDROID_VERSION.pom"
    if [ ! -f "$aar" ] || [ ! -f "$pom" ]; then
        echo "ERROR: missing $artifact $WLDROID_VERSION in extracted repo" >&2
        exit 1
    fi
    echo "    ok: $artifact-$WLDROID_VERSION"
done

echo "==> Done: $DEST_DIR"
