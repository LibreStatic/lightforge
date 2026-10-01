#!/usr/bin/env python3
"""Fail CI if the release gains network capabilities beyond signed model/opt-in map delivery and explicit own-server storage."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_PERMISSIONS = {
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
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
    violations = sorted(permission for permission in FORBIDDEN_PERMISSIONS if permission in declared)
    # Provider auto-start happens before Application.onCreate or the explicit model opt-in.
    if re.search(r'<provider[^>]+android:name="ai\.onnxruntime\.TelemetryInitializer"', source):
        violations.append("autoInitializer:ai.onnxruntime.TelemetryInitializer")
    return violations


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


def verify_network_sources() -> list[str]:
    """Keep network clients confined to signed model delivery and reviewed own-storage transports."""
    markers = ("HttpURLConnection", "java.net.URL", "okhttp3.", "retrofit2.", "java.net.Socket", "javax.net.SocketFactory", "net.schmizz.sshj", "com.hierynomus.smbj", "javax.net.ssl.", "java.net.ServerSocket")
    violations: list[str] = []
    for root_name in ("app", "core", "feature"):
        for path in (ROOT / root_name).glob("**/src/main/**/*.kt"):
            if path.name == "SemanticModelStorage.kt" and "feature/semanticsearch/" in path.as_posix():
                continue
            if path.relative_to(ROOT).as_posix() in {
                "core/ml/src/main/kotlin/com/librestatic/lightforge/core/ml/PinnedModelDownload.kt",
                "feature/petrecognition/src/main/kotlin/com/librestatic/lightforge/feature/petrecognition/PetModelStore.kt",
                "feature/localsharing/src/main/kotlin/com/librestatic/lightforge/feature/localsharing/LocalSharingTls.kt",
                "feature/localsharing/src/main/kotlin/com/librestatic/lightforge/feature/localsharing/LocalSharingClient.kt",
                "feature/localsharing/src/main/kotlin/com/librestatic/lightforge/feature/localsharing/LocalSharingReceiver.kt",
                "feature/localsharing/src/main/kotlin/com/librestatic/lightforge/feature/localsharing/LocalSharingRunner.kt",
                "feature/semanticsearch/src/main/kotlin/com/librestatic/lightforge/feature/semanticsearch/SemanticDownloadCancellation.kt",
                "feature/places/src/main/kotlin/com/librestatic/lightforge/feature/places/OfflinePlacesController.kt",
                "core/remotestorage/src/main/kotlin/com/librestatic/lightforge/core/remotestorage/sftp/SftpRemoteConnectionFactory.kt",
                "core/remotestorage/src/main/kotlin/com/librestatic/lightforge/core/remotestorage/sftp/SftpRemoteConnection.kt",
                "core/remotestorage/src/main/kotlin/com/librestatic/lightforge/core/remotestorage/smb/SmbRemoteConnectionFactory.kt",
            }:
                continue
            source = path.read_text(encoding="utf-8")
            if any(marker in source for marker in markers):
                violations.append(str(path.relative_to(ROOT)))
    return sorted(violations)


def main() -> int:
    manifest = merged_manifest()
    permissions = verify_manifest(manifest)
    dependencies = verify_dependencies()
    network_sources = verify_network_sources()
    if permissions or dependencies or network_sources:
        print(f"forbiddenPermissions={permissions}")
        print(f"unapprovedDependencies={dependencies}")
        print(f"unapprovedNetworkSources={network_sources}")
        return 1
    print(f"privacy guard passed: {manifest.relative_to(ROOT)} (signed model delivery, opt-in pinned map packages and user-configured SFTP/SMB; renderer HTTP denied)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
