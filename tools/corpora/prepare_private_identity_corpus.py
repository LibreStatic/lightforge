#!/usr/bin/env python3
"""Validate and stage a user-supplied identity corpus for local device tests only."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[2]
DESTINATION = ROOT / "core/ml/src/androidTest/assets/private_identity"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_members(archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    members = archive.infolist()
    for member in members:
        path = PurePosixPath(member.filename)
        if path.is_absolute() or ".." in path.parts or member.is_dir() and member.filename == "../":
            raise ValueError(f"Unsafe ZIP member: {member.filename}")
        mode = member.external_attr >> 16
        if mode & 0o170000 == 0o120000:
            raise ValueError(f"Symlinks are not allowed: {member.filename}")
    return members


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("zip_path", type=Path)
    args = parser.parse_args()
    zip_path = args.zip_path.resolve(strict=True)

    with tempfile.TemporaryDirectory(prefix="ugallery-private-identity-") as temporary:
        extracted = Path(temporary)
        with zipfile.ZipFile(zip_path) as archive:
            members = safe_members(archive)
            archive.extractall(extracted, members)

        manifest_path = extracted / "manifest.json"
        metadata_path = extracted / "metadata.csv"
        license_path = extracted / "LICENSE.txt"
        if not all(path.is_file() for path in (manifest_path, metadata_path, license_path)):
            raise ValueError("Corpus must contain manifest.json, metadata.csv, and LICENSE.txt")

        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if not isinstance(manifest, dict):
            raise ValueError("manifest.json must be a path-to-metadata object")
        extracted_files = {
            path.relative_to(extracted).as_posix()
            for path in extracted.rglob("*")
            if path.is_file() and path != manifest_path
        }
        if extracted_files != set(manifest):
            missing = sorted(set(manifest) - extracted_files)
            unexpected = sorted(extracted_files - set(manifest))
            raise ValueError(f"Manifest coverage mismatch; missing={missing}, unexpected={unexpected}")
        for relative, expected in manifest.items():
            path = extracted / relative
            if not path.is_file():
                raise ValueError(f"Missing manifest entry: {relative}")
            if path.stat().st_size != expected["size_bytes"] or sha256(path) != expected["sha256"]:
                raise ValueError(f"Hash/size mismatch: {relative}")

        if DESTINATION.exists():
            shutil.rmtree(DESTINATION)
        shutil.copytree(extracted, DESTINATION)
        (DESTINATION / "source.json").write_text(
            json.dumps(
                {
                    "sourceZipSha256": sha256(zip_path),
                    "fileCount": len(manifest),
                    "stagedFor": "local-androidTest-only",
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
    print(f"Staged private identity corpus in {DESTINATION}")


if __name__ == "__main__":
    main()
