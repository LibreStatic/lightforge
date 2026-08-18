#!/usr/bin/env python3
"""Fail CI if the offline release gains network permissions or unapproved runtime groups."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.ACCESS_LOCAL_NETWORK",
}


def merged_manifest() -> Path:
    candidates = [
        path
        for path in (ROOT / "app" / "build" / "intermediates").rglob("AndroidManifest.xml")
        if "offlinerelease" in path.as_posix().lower() and "merged" in path.as_posix().lower()
    ]
    if not candidates:
        raise RuntimeError("offlineRelease merged manifest not found; assemble it before running this guard")
    return max(candidates, key=lambda item: item.stat().st_mtime_ns)


def verify_manifest(path: Path) -> list[str]:
    # Keep this guard independent of host XML libraries so it also runs in minimal CI images.
    source = path.read_text(encoding="utf-8")
    declared = set(re.findall(r'<uses-permission(?:-sdk-23)?[^>]+android:name="([^"]+)"', source))
    return sorted(permission for permission in FORBIDDEN_PERMISSIONS if permission in declared)


def verify_dependencies() -> list[str]:
    allowlist = [
        line.strip()
        for line in (ROOT / "config" / "offline-dependency-allowlist.txt").read_text().splitlines()
        if line.strip() and not line.startswith("#")
    ]
    process = subprocess.run(
        [str(ROOT / "gradlew"), ":app:dependencies", "--configuration", "offlineReleaseRuntimeClasspath", "--console=plain"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    coordinates = set(re.findall(r"--- ([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):", process.stdout))
    def approved(group: str, artifact: str) -> bool:
        coordinate = f"{group}:{artifact}"
        return any(
            coordinate == rule if ":" in rule else group.startswith(rule)
            for rule in allowlist
        )

    return sorted(f"{group}:{artifact}" for group, artifact in coordinates if not approved(group, artifact))


def main() -> int:
    manifest = merged_manifest()
    permissions = verify_manifest(manifest)
    dependencies = verify_dependencies()
    if permissions or dependencies:
        print(f"forbiddenPermissions={permissions}")
        print(f"unapprovedDependencies={dependencies}")
        return 1
    print(f"offline guard passed: {manifest.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
