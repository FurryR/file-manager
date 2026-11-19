#!/usr/bin/env python3
"""Recompress, align and sign the release APK.

AGP deflates every entry at zlib level 1 and aapt2 stores resources.arsc, so
the packaged release APK is about 2 MB larger than a level-9 rewrite. The
rewrite changes every entry offset, which invalidates any signature, so
recompression, zipalign and apksigner have to run as one post-packaging step.

Signing key, in order of precedence:
  1. keystore.properties at the repository root (gitignored):
         storeFile=/path/to/release.jks
         storePassword=...
         keyAlias=...
         keyPassword=...
  2. ANDROID_KEYSTORE / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS /
     ANDROID_KEY_PASSWORD environment variables.
  3. ~/.android/debug.keystore (local sideload only; prints a warning).

Usage: postprocess-release.py <unsigned.apk> <output.apk>
"""

from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

LEVEL = 9
ROOT = Path(__file__).resolve().parent.parent
# v1 META-INF files would be stale after the rewrite and apksigner replaces
# them anyway; dropping them keeps the archive deterministic.
V1_SIGNATURE = re.compile(r"^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$", re.I)


def die(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def sdk_root() -> Path:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value:
            return Path(value)
    for candidate in (Path.home() / ".local/share/android", Path.home() / "Android/Sdk"):
        if candidate.is_dir():
            return candidate
    die("Android SDK not found; set ANDROID_HOME")


def build_tool(name: str) -> str:
    # The nix dev shell puts build-tools on PATH; a plain SDK install does not.
    on_path = shutil.which(name)
    if on_path:
        return on_path
    pattern = str(sdk_root() / "build-tools" / "*" / name)
    candidates = glob.glob(pattern)
    if not candidates:
        die(f"{name} not found on PATH or under {sdk_root()}/build-tools")
    candidates.sort(key=lambda p: [int(x) for x in re.findall(r"\d+", Path(p).parent.name)])
    return candidates[-1]


def android_user_home() -> Path:
    """Matches where AGP puts the debug keystore, including the nix shell."""
    value = os.environ.get("ANDROID_USER_HOME")
    if value:
        return Path(value).expanduser()
    legacy = os.environ.get("ANDROID_SDK_HOME")
    if legacy:
        return Path(legacy).expanduser() / ".android"
    return Path.home() / ".android"


def target_sdk(apk: Path) -> int:
    aapt2 = build_tool("aapt2")
    output = subprocess.run(
        [aapt2, "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    match = re.search(r"targetSdkVersion:'(\d+)'", output)
    return int(match.group(1)) if match else 0


def recompress(source: Path, target: Path, store_arsc: bool) -> None:
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w") as dst:
        for info in src.infolist():
            if V1_SIGNATURE.match(info.filename):
                continue
            # Read with the original method before rewriting it: ZipFile.read
            # resolves the name back to this same ZipInfo object.
            data = src.read(info.filename)
            # targetSdk 30+ requires an uncompressed resources.arsc for mmap;
            # below that it is just the largest stored entry.
            if store_arsc and info.filename == "resources.arsc":
                info.compress_type = zipfile.ZIP_STORED
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
            dst.writestr(info, data, compresslevel=LEVEL)


def keystore_arguments() -> tuple[list[str], dict[str, str], str]:
    """Returns apksigner key arguments, env additions and a signer label."""
    props = ROOT / "keystore.properties"
    if props.is_file():
        values: dict[str, str] = {}
        for line in props.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
        required = ("storeFile", "storePassword", "keyAlias", "keyPassword")
        missing = [key for key in required if not values.get(key)]
        if missing:
            die(f"{props} is missing: {', '.join(missing)}")
        path = Path(values["storeFile"]).expanduser()
        if not path.is_absolute():
            path = ROOT / path
        if not path.is_file():
            die(f"keystore not found: {path}")
        env = {
            "FM_KS_PASS": values["storePassword"],
            "FM_KEY_PASS": values["keyPassword"],
        }
        args = [
            "--ks", str(path),
            "--ks-key-alias", values["keyAlias"],
            "--ks-pass", "env:FM_KS_PASS",
            "--key-pass", "env:FM_KEY_PASS",
        ]
        return args, env, f"{values['keyAlias']} ({path})"

    store = os.environ.get("ANDROID_KEYSTORE")
    if store:
        alias = os.environ.get("ANDROID_KEY_ALIAS")
        if not alias:
            die("ANDROID_KEYSTORE is set but ANDROID_KEY_ALIAS is not")
        env = {
            "ANDROID_KEYSTORE_PASSWORD": os.environ.get("ANDROID_KEYSTORE_PASSWORD", ""),
            "ANDROID_KEY_PASSWORD": os.environ.get("ANDROID_KEY_PASSWORD", ""),
        }
        args = [
            "--ks", store,
            "--ks-key-alias", alias,
            "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD",
            "--key-pass", "env:ANDROID_KEY_PASSWORD",
        ]
        return args, env, f"{alias} ({store})"

    debug = android_user_home() / "debug.keystore"
    if debug.is_file():
        print("warning: signing with the Android debug keystore (local sideload only);")
        print("         create keystore.properties or set ANDROID_KEYSTORE for a release key")
        args = [
            "--ks", str(debug),
            "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
        ]
        return args, {}, "androiddebugkey (~/.android/debug.keystore)"
    die(
        "no signing key: create keystore.properties, set ANDROID_KEYSTORE* "
        "or build a debug APK first"
    )


def main() -> None:
    if len(sys.argv) != 3:
        die("usage: postprocess-release.py <unsigned.apk> <output.apk>")
    source = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    if not source.is_file():
        die(f"input APK not found: {source}")

    zipalign = build_tool("zipalign")
    apksigner = build_tool("apksigner")
    key_args, key_env, signer = keystore_arguments()

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="apk-postprocess-") as tmp:
        tmpdir = Path(tmp)
        recompressed = tmpdir / "recompressed.apk"
        aligned = tmpdir / "aligned.apk"
        signed = tmpdir / "signed.apk"

        recompress(source, recompressed, store_arsc=target_sdk(source) >= 30)
        subprocess.run(
            [zipalign, "-p", "-f", "4", str(recompressed), str(aligned)],
            check=True,
        )
        env = os.environ.copy()
        env.update(key_env)
        subprocess.run(
            [apksigner, "sign", *key_args, "--out", str(signed), str(aligned)],
            check=True,
            env=env,
        )
        subprocess.run(
            [apksigner, "verify", "--print-certs", str(signed)],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        shutil.move(str(signed), output)

    source_size = source.stat().st_size
    output_size = output.stat().st_size
    saved = (1 - output_size / source_size) * 100
    print(f"postprocessed {output}")
    print(f"  unsigned {source_size:,} B -> signed {output_size:,} B ({saved:.1f}% smaller)")
    print(f"  signer   {signer}")


if __name__ == "__main__":
    main()
