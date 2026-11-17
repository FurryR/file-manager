#!/usr/bin/env python3
"""Regenerate app/src/main/assets/licenses.json.

Sources:
  * Gradle's dependency-license-report JSON (Android/JVM runtime classpath)
  * `cargo metadata` for the Rust daemon and utilities (normal deps only)
  * licenses/native.json for prebuilt C libraries that have no package manager

Run it through:
    ./gradlew :app:generateLicenses
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_REPORT = ROOT / "app/build/reports/licenses/licenses.json"
NATIVE_MANIFEST = ROOT / "licenses/native.json"
CARGO_MANIFEST = ROOT / "native/Cargo.toml"
OUTPUT = ROOT / "app/src/main/assets/licenses.json"

# POMs are inconsistent: normalise the common spellings to SPDX names.
LICENSE_ALIASES = {
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "mit license": "MIT",
    "lgpl v2.1": "LGPL-2.1-only",
    "eclipse public license version 2.0": "EPL-2.0",
    "eclipse public license - v 2.0": "EPL-2.0",
    "cc0 1.0 universal": "CC0-1.0",
    "bsd-3-clause": "BSD-3-Clause",
    "revised bsd": "BSD-3-Clause",
}

# Artifacts whose POM does not describe the actual distribution license.
OVERRIDES = {
    "com.termux.termux-app:terminal-emulator": (
        "GPL-3.0-only",
        "https://github.com/termux/termux-app",
    ),
    "com.termux.termux-app:terminal-view": (
        "GPL-3.0-only",
        "https://github.com/termux/termux-app",
    ),
    "com.github.mwiede:jsch": (
        "BSD-3-Clause / ISC",
        "https://github.com/mwiede/jsch",
    ),
}


def normalize_license(value: str | None) -> str:
    if not value:
        return "Unknown"
    value = value.strip()
    lower = value.lower()
    if lower in LICENSE_ALIASES:
        return LICENSE_ALIASES[lower]
    if lower.startswith("revised bsd"):
        return "BSD-3-Clause"
    return value


def gradle_components() -> list[dict]:
    if not GRADLE_REPORT.is_file():
        raise SystemExit(
            f"missing {GRADLE_REPORT}; run ./gradlew :app:generateLicenseReport first"
        )
    report = json.loads(GRADLE_REPORT.read_text())
    result: list[dict] = []
    for item in report.get("dependencies", []):
        name = item.get("moduleName") or ""
        if not name:
            continue
        license_name, url = OVERRIDES.get(
            name,
            (
                normalize_license(item.get("moduleLicense")),
                item.get("moduleUrl") or item.get("moduleLicenseUrl"),
            ),
        )
        result.append(
            {
                "group": "Android / JVM",
                "name": name,
                "version": item.get("moduleVersion"),
                "license": license_name,
                "url": url,
            }
        )
    return result


def cargo_metadata() -> dict:
    command = [
        "cargo",
        "metadata",
        "--manifest-path",
        str(CARGO_MANIFEST),
        "--format-version",
        "1",
        "--locked",
        "--filter-platform",
        "aarch64-linux-android",
    ]
    try:
        return json.loads(
            subprocess.check_output([*command, "--offline"], stderr=subprocess.DEVNULL)
        )
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    try:
        return json.loads(subprocess.check_output(command, stderr=subprocess.DEVNULL))
    except (subprocess.CalledProcessError, FileNotFoundError) as error:
        raise SystemExit(f"cargo metadata failed: {error}")


def rust_components() -> list[dict]:
    metadata = cargo_metadata()
    packages = {package["id"]: package for package in metadata["packages"]}
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    roots = [pid for pid, package in packages.items() if package.get("source") is None]

    # Follow only normal dependencies: build tools (prost-build, syn, ...) are
    # not distributed in the APK and would drown the list.
    used: set[str] = set()
    pending = list(roots)
    while pending:
        current = pending.pop()
        if current in used:
            continue
        used.add(current)
        for dependency in nodes.get(current, {}).get("deps", []):
            kinds = dependency.get("dep_kinds", [])
            if any(kind.get("kind") is None for kind in kinds):
                pending.append(dependency["pkg"])

    result: list[dict] = []
    for package_id in used:
        package = packages[package_id]
        if package.get("source") is None:
            continue
        name = package.get("name") or ""
        if not name:
            continue
        url = (
            package.get("repository")
            or package.get("homepage")
            or f"https://crates.io/crates/{name}"
        )
        result.append(
            {
                "group": "Rust",
                "name": name,
                "version": package.get("version"),
                "license": normalize_license(package.get("license")),
                "url": url,
            }
        )
    return result


def native_components() -> list[dict]:
    entries = json.loads(NATIVE_MANIFEST.read_text())
    return [
        {
            "group": entry.get("group", "Native"),
            "name": entry["name"],
            "version": entry.get("version"),
            "license": entry.get("license", "Unknown"),
            "url": entry.get("url"),
        }
        for entry in entries
    ]


def main() -> None:
    group_order = {"Android / JVM": 0, "Rust": 1, "Bundled web": 2, "Native": 3}
    components = gradle_components() + rust_components() + native_components()
    unique: dict[tuple, dict] = {}
    for component in components:
        key = (
            component["group"],
            component["name"],
            component.get("version") or "",
            component["license"],
        )
        unique.setdefault(key, component)
    ordered = sorted(
        unique.values(),
        key=lambda item: (
            group_order.get(item["group"], 99),
            item["name"].lower(),
            item.get("version") or "",
        ),
    )
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        json.dumps({"components": ordered}, ensure_ascii=False, indent=2) + "\n"
    )
    counts = {group: 0 for group in group_order}
    for component in ordered:
        counts[component["group"]] = counts.get(component["group"], 0) + 1
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(ordered)} components {counts}")


if __name__ == "__main__":
    main()
