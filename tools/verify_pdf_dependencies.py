#!/usr/bin/env python3
"""Verify reviewed PDF crypto versions and license records; not a vulnerability scanner."""
from pathlib import Path
import json
ROOT = Path(__file__).resolve().parents[1]
expected = {"bcprov-jdk15to18": "1.85.2", "bcpkix-jdk15to18": "1.85", "bcutil-jdk15to18": "1.85.1"}
lock = (ROOT / "app/gradle.lockfile").read_text().splitlines()
build = (ROOT / "feature/pdfstudio/build.gradle.kts").read_text()
licenses = json.loads((ROOT / "app/src/main/assets/third_party_licenses.json").read_text())
for artifact, version in expected.items():
    coordinate = f"org.bouncycastle:{artifact}:{version}"
    lines = [line for line in lock if line.startswith(f"org.bouncycastle:{artifact}:")]
    assert len(lines) == 1 and lines[0].split("=")[0] == coordinate, (artifact, lines)
    assert set(lines[0].split("=")[1].split(",")) >= {"offlineDebugRuntimeClasspath", "offlineReleaseRuntimeClasspath"}
    assert coordinate in build, coordinate
    component = [c for c in licenses["components"] if c["group"] == "org.bouncycastle" and c["name"] == artifact]
    assert len(component) == 1 and component[0]["version"] == version, component
    assert component[0]["licenseTextAsset"] == "licenses/Bouncy-Castle.txt"
# AGP's own lint and test-result tooling brings jdk18on; it never reaches an app or test classpath.
tooling = {"androidLintTool", "unified-test-platform-android-test-plugin-result-listener-gradle"}
for line in lock:
    if line.startswith("org.bouncycastle:") and ("jdk15on:" in line or "jdk18on:" in line):
        assert "jdk18on:" in line and set(line.split("=")[1].split(",")) <= tooling, line
assert "2000-2023" in (ROOT / "app/src/main/assets/licenses/Bouncy-Castle.txt").read_text()
rules = (ROOT / "feature/pdfstudio/consumer-rules.pro").read_text()
assert 'consumerProguardFiles("consumer-rules.pro")' in build
assert {line.strip() for line in rules.splitlines() if line.startswith("-dontwarn")} == {
    "-dontwarn com.gemalto.jp2.JP2Decoder", "-dontwarn com.gemalto.jp2.JP2Encoder"}
print("PDF DEPENDENCIES PASS: provider 1.85.2; PKIX 1.85; utility 1.85.1; debug/release locks and license catalog agree; one artifact family")

# Phase G1a: bundled Noto fonts (text layer). Not a Maven artifact, so — like the ncnn/SFace/
# LibRaw bundled binaries above them — they are NOT in the generated third_party_licenses.json;
# they get a canonical license copy next to the fonts, a verbatim bundled copy under app assets,
# and a byte-equality check here (the same contract app/build.gradle.kts's
# verifyVerbatimLicenseCopies enforces at build time, checked here without needing Gradle/a device).
fonts_dir = ROOT / "feature/pdfstudio/src/main/assets/fonts"
expected_fonts = {
    "NotoSans-Regular.ttf": (550_000, 700_000),
    "NotoSans-Bold.ttf": (550_000, 700_000),
    "NotoSerif-Regular.ttf": (650_000, 800_000),
    "NotoSerif-Bold.ttf": (650_000, 800_000),
}
for name, (low, high) in expected_fonts.items():
    path = fonts_dir / name
    assert path.is_file(), path
    size = path.stat().st_size
    assert low <= size <= high, (name, size)
    data = path.read_bytes()
    # Reject a variable font: an 'fvar' table means a single TTF carries every weight, which is
    # not what "Regular"/"Bold" as separate static files promises the isolated renderer (it loads
    # each file expecting exactly one weight's outlines).
    num_tables = int.from_bytes(data[4:6], "big")
    tags = {data[12 + i * 16 : 12 + i * 16 + 4] for i in range(num_tables)}
    assert b"fvar" not in tags, f"{name} is a variable font, not a static weight"
canonical_ofl = fonts_dir / "OFL.txt"
bundled_ofl = ROOT / "app/src/main/assets/licenses/Noto-OFL-1.1.txt"
assert canonical_ofl.read_bytes() == bundled_ofl.read_bytes(), "Noto OFL license copy drifted"
assert "SIL OPEN FONT LICENSE Version 1.1" in canonical_ofl.read_text()
assert "The Noto Project Authors" in canonical_ofl.read_text()
copies = (ROOT / "app/build.gradle.kts").read_text()
assert (
    '"feature/pdfstudio/src/main/assets/fonts/OFL.txt" to' in copies
    and '"app/src/main/assets/licenses/Noto-OFL-1.1.txt"' in copies
), "Noto OFL license copy is missing from verbatimLicenseCopies"
print("PDF FONT LICENSE PASS: 4 static Noto Sans/Serif Regular/Bold TTFs, OFL-1.1 copy verified byte-identical and registered")
