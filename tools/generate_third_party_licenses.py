#!/usr/bin/env python3
"""Generate the offline dependency-license catalog shipped in UGallery."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


APACHE_PREFIXES = (
    "androidx.",
    "com.google.ai.edge.litert",
    "com.google.android.datatransport",
    "com.google.auto.service",
    "com.google.code.findbugs",
    "com.google.dagger",
    "com.google.errorprone",
    "com.google.firebase",
    "com.google.guava",
    "com.google.j2objc",
    "com.squareup.okhttp3",
    "com.squareup.okio",
    "jakarta.inject",
    "javax.inject",
    "org.jetbrains",
    "org.jspecify",
)

RUNTIME_CONFIGURATION = "offlineReleaseRuntimeClasspath"
APACHE = {
    "licenseId": "Apache-2.0",
    "licenseName": "Apache License 2.0",
    "licenseTextAsset": "licenses/Apache-2.0.txt",
}
BSD_PROTOBUF = {
    "licenseId": "BSD-3-Clause",
    "licenseName": "BSD 3-Clause License",
    "licenseTextAsset": "licenses/BSD-3-Clause-Protobuf.txt",
}
ANDROID_SDK = {
    "licenseId": "LicenseRef-Android-SDK",
    "licenseName": "Android Software Development Kit License Agreement",
    "licenseTextAsset": "licenses/Android-SDK-License.txt",
}
ML_KIT = {
    "licenseId": "LicenseRef-ML-Kit-Terms",
    "licenseName": "ML Kit Terms of Service",
    "licenseTextAsset": "licenses/ML-Kit-Terms.txt",
}


def parse_release_runtime(lockfile: Path) -> list[tuple[str, str, str]]:
    modules: set[tuple[str, str, str]] = set()
    for raw in lockfile.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        coordinate, configurations = line.split("=", 1)
        if RUNTIME_CONFIGURATION not in configurations.split(","):
            continue
        parts = coordinate.split(":")
        if len(parts) == 3:
            modules.add((parts[0], parts[1], parts[2]))
    return sorted(modules)


def license_for(group: str, name: str) -> dict[str, str]:
    if group == "net.zetetic" and name == "sqlcipher-android":
        return {"licenseId": "BSD-3-Clause AND Apache-2.0 AND WTFPL", "licenseName": "SQLCipher Android and bundled LibTomCrypt notices", "licenseTextAsset": "licenses/SQLCipher-Android-Notices.txt"}
    if f"{group}:{name}" in {
        "androidx.appsearch:appsearch-external-protobuf",
        "androidx.datastore:datastore-preferences-external-protobuf",
    }:
        return BSD_PROTOBUF
    if group == "com.google.mlkit" or (
        group == "com.google.android.gms" and "mlkit" in name
    ):
        return ML_KIT
    if group in ("com.google.android.gms", "com.google.android.odml"):
        return ANDROID_SDK
    if group == "org.maplibre.gl" and name == "android-sdk-opengl":
        return {"licenseId": "BSD-2-Clause", "licenseName": "MapLibre Native and bundled notices", "licenseTextAsset": "licenses/MapLibre-Android-Notices.txt"}
    if group == "org.maplibre.gl" and name == "maplibre-android-gestures":
        return {"licenseId": "BSD-2-Clause", "licenseName": "MapLibre Gestures BSD 2-Clause", "licenseTextAsset": "licenses/MapLibre-Gestures-BSD-2-Clause.txt"}
    if f"{group}:{name}" in {"org.maplibre.gl:android-sdk-geojson", "org.maplibre.gl:android-sdk-turf", "com.google.code.gson:gson", "com.jakewharton.timber:timber"}:
        return APACHE
    if group == "com.microsoft.onnxruntime" and name == "onnxruntime-android":
        return {"licenseId": "MIT", "licenseName": "ONNX Runtime MIT and bundled third-party notices", "licenseTextAsset": "licenses/ONNX-Runtime-Notices.txt"}
    if group == "com.google.zxing" and name == "core":
        return {"licenseId": "Apache-2.0", "licenseName": "ZXing Apache License and bundled notices", "licenseTextAsset": "licenses/ZXing-Notices.txt"}
    if group == "com.google.crypto.tink" and name == "tink-android":
        return {"licenseId": "Apache-2.0 AND BSD-3-Clause", "licenseName": "Tink Apache License and bundled Protocol Buffers notices", "licenseTextAsset": "licenses/Tink-Android-Notices.txt"}
    if group == "com.tom-roush" and name == "pdfbox-android":
        return {
            "licenseId": "LicenseRef-PDFBox-Android-Bundled",
            "licenseName": "PDFBox-Android Apache license, bundled component terms and notices",
            "licenseTextAsset": "licenses/PDFBox-Android-Notices.txt",
        }
    if group == "org.bouncycastle":
        return {"licenseId": "MIT", "licenseName": "Bouncy Castle License (MIT)", "licenseTextAsset": "licenses/Bouncy-Castle.txt"}
    if group == "org.slf4j" and name == "slf4j-api":
        return {"licenseId": "MIT", "licenseName": "SLF4J License (MIT)", "licenseTextAsset": "licenses/SLF4J.txt"}
    if group == "net.engio" and name == "mbassador":
        return {"licenseId": "MIT", "licenseName": "MBassador License (MIT)", "licenseTextAsset": "licenses/MBassador.txt"}
    if f"{group}:{name}" in {"com.hierynomus:sshj", "com.hierynomus:smbj", "com.hierynomus:asn-one"}:
        return APACHE
    if group == "com.tom-roush" or group.startswith(APACHE_PREFIXES):
        return APACHE
    raise ValueError(f"No reviewed license rule for dependency: {group}:{name}")


def generate(lockfile: Path) -> dict[str, object]:
    components = []
    for group, name, version in parse_release_runtime(lockfile):
        components.append(
            {
                "group": group,
                "name": name,
                "version": version,
                **license_for(group, name),
            }
        )
    if not components:
        raise ValueError("No offlineReleaseRuntimeClasspath dependencies found")
    return {
        "schemaVersion": 1,
        "configuration": RUNTIME_CONFIGURATION,
        "components": components,
    }


def validate_license_assets(document: dict[str, object], assets_directory: Path) -> None:
    referenced = {component["licenseTextAsset"] for component in document["components"]}
    for relative_path in referenced:
        asset = assets_directory / relative_path
        if not asset.is_file() or not asset.read_text(encoding="utf-8").strip():
            raise ValueError(f"Missing or empty bundled license text: {asset}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lockfile", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    document = generate(args.lockfile)
    validate_license_assets(document, args.output.parent)
    rendered = json.dumps(document, indent=2, sort_keys=True) + "\n"
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != rendered:
            raise SystemExit(f"stale third-party license catalog: {args.output}")
        print(f"verified {args.output} ({len(document['components'])} components)")
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.output} ({len(document['components'])} components)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
