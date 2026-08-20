#!/usr/bin/env python3
"""Capture the native root surface at the canonical responsive window sizes.

Requires one booted emulator/device and an installed offlineDebug build. The script
restores the original `wm size` and `wm density` overrides before exiting.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Viewport:
    name: str
    pixels: str
    density: int
    dp_label: str


VIEWPORTS = (
    Viewport("phone", "1280x2856", 480, "426x952"),
    Viewport("foldable", "1200x1920", 320, "600x960"),
    Viewport("tablet", "1230x1770", 240, "820x1180"),
    Viewport("desktop", "1440x900", 160, "1440x900"),
)


def run(adb: str, *args: str, capture: bool = False) -> bytes:
    return subprocess.run(
        [adb, *args],
        check=True,
        capture_output=capture,
    ).stdout


def current_override(adb: str, setting: str) -> str | None:
    output = run(adb, "shell", "wm", setting, capture=True).decode()
    match = re.search(r"Override (?:size|density):\s*([^\n]+)", output)
    return match.group(1).strip() if match else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="com.ugallery.app.debug")
    parser.add_argument(
        "--activity",
        default="com.ugallery.app.MainActivity",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("docs/evidence/generated/emulator-api36/material-you-responsive"),
    )
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    original_size = current_override(args.adb, "size")
    original_density = current_override(args.adb, "density")
    try:
        for viewport in VIEWPORTS:
            run(args.adb, "shell", "wm", "size", viewport.pixels)
            run(args.adb, "shell", "wm", "density", str(viewport.density))
            run(args.adb, "shell", "am", "force-stop", args.package)
            run(
                args.adb,
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                f"{args.package}/{args.activity}",
            )
            time.sleep(1.5)
            screenshot = run(args.adb, "exec-out", "screencap", "-p", capture=True)
            path = args.output / f"{viewport.name}-{viewport.dp_label}-photos.png"
            path.write_bytes(screenshot)
            print(path)
    finally:
        run(args.adb, "shell", "wm", "size", original_size or "reset")
        run(args.adb, "shell", "wm", "density", original_density or "reset")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

