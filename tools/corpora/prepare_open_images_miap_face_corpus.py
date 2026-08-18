#!/usr/bin/env python3
"""Build a deterministic, attributed Open Images + MIAP face evaluation corpus."""

from __future__ import annotations

import csv
import hashlib
import json
import urllib.request
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageOps


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "testdata/open-images-miap-face-corpus"
CACHE = ROOT / "build/test-corpora-cache/open-images-miap-face-corpus"
ASSETS = ROOT / "core/ml/src/androidTest/assets"
IMAGES = ASSETS / "images"
FACE_LABEL = "/m/0dzct"
LONG_EDGE = 1024
PER_PRESENTATION_STRATUM = 8
PER_CHALLENGE_STRATUM = 6

URLS = {
    "boxes": "https://storage.googleapis.com/openimages/v5/validation-annotations-bbox.csv",
    "miap": "https://storage.googleapis.com/openimages/open_images_extended_miap/open_images_extended_miap_boxes_val.csv",
    "metadata": "https://storage.googleapis.com/openimages/2018_04/validation/validation-images-with-rotation.csv",
}

PRESENTATION_STRATA = (
    ("Predominantly Feminine", "Middle"),
    ("Predominantly Masculine", "Middle"),
    ("Predominantly Feminine", "Older"),
    ("Predominantly Masculine", "Older"),
    ("Unknown", "Young"),
    ("Unknown", "Unknown"),
)


def download(url: str, destination: Path) -> Path:
    if destination.exists() and destination.stat().st_size:
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url, timeout=180) as response:
        destination.write_bytes(response.read())
    return destination


def rows(path: Path):
    with path.open(newline="", encoding="utf-8") as source:
        yield from csv.DictReader(source)


def contains(person: dict[str, str], face: dict[str, str]) -> bool:
    center_x = (float(face["XMin"]) + float(face["XMax"])) / 2
    center_y = (float(face["YMin"]) + float(face["YMax"])) / 2
    return (
        float(person["XMin"]) <= center_x <= float(person["XMax"])
        and float(person["YMin"]) <= center_y <= float(person["YMax"])
    )


def face_size(face: dict[str, str]) -> float:
    return min(float(face["XMax"]) - float(face["XMin"]), float(face["YMax"]) - float(face["YMin"]))


def stable_rank(image_id: str, salt: str) -> str:
    return hashlib.sha256(f"ugallery-m4-v1:{salt}:{image_id}".encode()).hexdigest()


def select_targets(boxes_path: Path, miap_path: Path) -> list[dict[str, object]]:
    faces: dict[str, list[dict[str, str]]] = defaultdict(list)
    for face in rows(boxes_path):
        if face["LabelName"] == FACE_LABEL and face["IsDepiction"] == "0" and face["IsGroupOf"] == "0":
            faces[face["ImageID"]].append(face)

    candidates: list[dict[str, object]] = []
    for person in rows(miap_path):
        image_faces = faces.get(person["ImageID"], ())
        matching = [face for face in image_faces if contains(person, face) and 0.10 <= face_size(face) <= 0.70]
        if not matching:
            continue
        face = max(matching, key=face_size)
        candidates.append({
            "imageId": person["ImageID"],
            "genderPresentation": person["GenderPresentation"],
            "agePresentation": person["AgePresentation"],
            "condition": "occluded" if face["IsOccluded"] == "1" else "truncated" if face["IsTruncated"] == "1" else "clean",
            "box": [float(face["XMin"]), float(face["YMin"]), float(face["XMax"]), float(face["YMax"])],
        })

    selected: list[dict[str, object]] = []
    used: set[str] = set()
    for gender, age in PRESENTATION_STRATA:
        stratum = [c for c in candidates if c["condition"] == "clean" and c["genderPresentation"] == gender and c["agePresentation"] == age]
        stratum.sort(key=lambda c: stable_rank(str(c["imageId"]), f"{gender}:{age}"))
        chosen = [c for c in stratum if c["imageId"] not in used][:PER_PRESENTATION_STRATUM]
        if len(chosen) != PER_PRESENTATION_STRATUM:
            raise RuntimeError(f"Insufficient candidates for {gender}/{age}: {len(chosen)}")
        selected.extend(chosen)
        used.update(str(c["imageId"]) for c in chosen)

    for condition in ("occluded", "truncated"):
        stratum = [c for c in candidates if c["condition"] == condition and c["imageId"] not in used]
        stratum.sort(key=lambda c: stable_rank(str(c["imageId"]), condition))
        chosen = stratum[:PER_CHALLENGE_STRATUM]
        if len(chosen) != PER_CHALLENGE_STRATUM:
            raise RuntimeError(f"Insufficient candidates for {condition}: {len(chosen)}")
        selected.extend(chosen)
        used.update(str(c["imageId"]) for c in chosen)
    return selected


def main() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    IMAGES.mkdir(parents=True, exist_ok=True)
    source_paths = {name: download(url, CACHE / f"{name}.csv") for name, url in URLS.items()}
    selected = select_targets(source_paths["boxes"], source_paths["miap"])
    selected_ids = {str(item["imageId"]) for item in selected}
    metadata = {row["ImageID"]: row for row in rows(source_paths["metadata"]) if row["ImageID"] in selected_ids}
    if metadata.keys() != selected_ids:
        raise RuntimeError(f"Missing metadata for {sorted(selected_ids - metadata.keys())}")

    entries = []
    for index, target in enumerate(selected):
        image_id = str(target["imageId"])
        original = download(
            f"https://open-images-dataset.s3.amazonaws.com/validation/{image_id}.jpg",
            CACHE / "originals" / f"{image_id}.jpg",
        )
        output = IMAGES / f"{index:03d}-{image_id}.jpg"
        with Image.open(original) as loaded:
            image = ImageOps.exif_transpose(loaded).convert("RGB")
            image.thumbnail((LONG_EDGE, LONG_EDGE), Image.Resampling.LANCZOS)
            image.save(output, "JPEG", quality=88, optimize=True)
            width, height = image.size
        meta = metadata[image_id]
        entries.append({
            **target,
            "file": output.name,
            "width": width,
            "height": height,
            "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            "license": meta["License"],
            "author": meta["Author"],
            "authorProfileUrl": meta["AuthorProfileURL"],
            "title": meta["Title"],
            "originalUrl": meta["OriginalURL"],
            "originalLandingUrl": meta["OriginalLandingURL"],
        })

    manifest = {
        "schemaVersion": 1,
        "dataset": "Open Images V7 validation + MIAP validation",
        "selectionVersion": "ugallery-m4-v1",
        "imageCount": len(entries),
        "annotationLicense": "https://creativecommons.org/licenses/by/4.0/",
        "imageLicensePolicy": "Images are listed by Open Images as CC BY; retain per-image attribution and upstream disclaimer.",
        "sources": URLS,
        "entries": entries,
    }
    manifest_text = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    (OUTPUT / "manifest.json").write_text(manifest_text, encoding="utf-8")
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "manifest.json").write_text(manifest_text, encoding="utf-8")
    print(f"Prepared {len(entries)} images in {IMAGES}")


if __name__ == "__main__":
    main()
