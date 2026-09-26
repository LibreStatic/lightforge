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
