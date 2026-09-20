#!/usr/bin/env python3
"""Compile one shell-only real-display observer; no APK, Gradle, ADB or target instrumentation."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="Fresh output directory")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    classes = args.output / "classes"
    dex = args.output / "dex"
    classes.mkdir()
    dex.mkdir()
    source = Path(__file__).with_name("RealDisplayDump.java")
    library = args.sdk / "platforms/android-35/android.jar"
    commands = [
        ["rtk", "proxy", "javac", "--release", "8", "-Xlint:-options", "-classpath", str(library),
         "-d", str(classes), str(source)],
        ["rtk", "proxy", str(args.sdk / "build-tools/35.0.0/d8"), "--min-api", "30", "--lib", str(library),
         "--output", str(dex), str(classes / "com/ugallery/tools/RealDisplayDump.class")],
    ]
    evidence = []
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True)
        evidence.append(dict(command=command, stdout=result.stdout, stderr=result.stderr, exit=result.returncode))
        (args.output / "build.json").write_text(json.dumps(evidence, indent=2) + "\n")
        if result.returncode:
            raise SystemExit(result.returncode)
    jar = args.output / "real-display-observer.jar"
    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_STORED) as archive:
        entry = zipfile.ZipInfo("classes.dex", (2000, 1, 1, 0, 0, 0))
        entry.external_attr = 0o644 << 16
        archive.writestr(entry, (dex / "classes.dex").read_bytes())
    print(json.dumps(dict(path=str(jar.resolve()), sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                         sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest())))


if __name__ == "__main__":
    main()
