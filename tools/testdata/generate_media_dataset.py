#!/usr/bin/env python3
"""Generate deterministic, non-personal media fixtures and a checksum manifest."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path

# Valid 1x1 PNG; files are deterministic and intentionally tiny for index/load tests.
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--count", type=int, required=True, choices=(1_000, 10_000, 100_000, 250_000))
    parser.add_argument("--materialize", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256(PNG).hexdigest()
    if args.materialize:
        for index in range(args.count):
            bucket = args.output / f"bucket-{index % 32:02d}"
            bucket.mkdir(exist_ok=True)
            (bucket / f"fixture-{index:06d}.png").write_bytes(PNG)
    manifest = {
        "schema": "lightforge.synthetic-media.v1",
        "count": args.count,
        "materialized": args.materialize,
        "buckets": 32,
        "fixtureSha256": digest,
        "containsPersonalData": False,
    }
    (args.output / "dataset.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest))


if __name__ == "__main__":
    main()

