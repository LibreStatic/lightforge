#!/usr/bin/env python3
"""Verify the immutable inputs used to port the HTML prototype to Android."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MOCK_ROOT = ROOT / "docs" / "mock"
SCREENS_ROOT = MOCK_ROOT / "screens"
MANIFEST_PATH = ROOT / "DESIGN-MANIFEST.json"
DEFAULT_OUTPUT = ROOT / "docs" / "design" / "HANDOFF_INVENTORY.json"
CSS_REFERENCE = re.compile(r"(?:url\(|@import\s+url\()[\"']?([^\"')]+)")


class ReferenceParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.references: list[str] = []

    def handle_starttag(self, _tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        for key in ("href", "src", "poster"):
            value = values.get(key)
            if value:
                self.references.append(value)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def is_local(reference: str) -> bool:
    return not reference.startswith(("http:", "https:", "data:", "#"))


def css_references(path: Path) -> list[str]:
    return [match for match in CSS_REFERENCE.findall(path.read_text(encoding="utf-8")) if is_local(match)]


def build_inventory() -> tuple[dict[str, object], list[str]]:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    expected = sorted(manifest["sourceFiles"]["html"])
    actual = sorted(path.name for path in SCREENS_ROOT.glob("*.html"))
    errors: list[str] = []
    if actual != expected:
        errors.append(f"screen set differs: expected={expected!r}, actual={actual!r}")

    files: dict[str, dict[str, object]] = {}
    queue = [SCREENS_ROOT / name for name in actual]
    visited: set[Path] = set()

    while queue:
        path = queue.pop(0).resolve()
        if path in visited:
            continue
        visited.add(path)
        relative = path.relative_to(ROOT).as_posix()
        if not path.is_file():
            errors.append(f"missing reference: {relative}")
            continue

        references: list[str] = []
        if path.suffix == ".html":
            parser = ReferenceParser()
            parser.feed(path.read_text(encoding="utf-8"))
            references = [reference for reference in parser.references if is_local(reference)]
        elif path.suffix == ".css":
            references = css_references(path)

        resolved: list[str] = []
        for reference in references:
            target = (path.parent / reference).resolve()
            try:
                target_relative = target.relative_to(ROOT).as_posix()
            except ValueError:
                errors.append(f"reference escapes repository: {relative} -> {reference}")
                continue
            resolved.append(target_relative)
            if not target.is_file():
                errors.append(f"missing reference: {relative} -> {reference}")
            elif target.suffix in {".css", ".js"}:
                queue.append(target)

        files[relative] = {
            "bytes": path.stat().st_size,
            "sha256": digest(path),
            "references": sorted(resolved),
        }

    inventory: dict[str, object] = {
        "schema": "ugallery.design-handoff-inventory.v1",
        "entryFile": f"docs/mock/screens/{manifest['entryFile']}",
        "screenCount": len(actual),
        "files": dict(sorted(files.items())),
        "errors": sorted(errors),
    }
    return inventory, errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="write the deterministic inventory snapshot")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    inventory, errors = build_inventory()
    if args.write:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"screenCount": inventory["screenCount"], "fileCount": len(inventory["files"]), "errors": errors}))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
