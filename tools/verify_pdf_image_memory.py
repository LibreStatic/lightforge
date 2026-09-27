#!/usr/bin/env python3
"""Measure a pinned, real isolated renderer with four photos repeated 288 times.

Install the PDF module test APK first. No device settings, user package, or
user media are changed. VmHWM is the kernel RSS high-water mark for this PID,
not Java heap allocation or a guarantee for arbitrary documents/devices.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", required=True)
p.add_argument("--output", required=True, type=Path)
p.add_argument("--baseline", action="store_true")
args = p.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
pkg = "com.librestatic.lightforge.feature.pdfstudio.test"
adb = ["rtk", "proxy", "adb", "-s", args.serial]


def shell(*parts):
    return subprocess.check_output(adb + ["shell", *parts], text=True, timeout=30)


def state():
    return json.loads(shell("run-as", pkg, "cat", "files/pdf-image-memory/state.json"))


shell("run-as", pkg, "ls")  # Verify the installed fixture UID before interpreting test -d.
existing = subprocess.run(adb + ["shell", "run-as", pkg, "test", "-d", "files/pdf-image-memory"], capture_output=True)
assert existing.returncode == 1, "Inspect the existing probe; never restart a live run"
phase = "image-memory-baseline" if args.baseline else "image-memory"
log = (args.output / "instrumentation.log").open("w")
process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "phase", phase,
                                pkg + "/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner"],
                           stdout=log, stderr=subprocess.STDOUT)
deadline = time.monotonic() + 120
while True:
    assert process.poll() is None, "Fixture terminated; inspect instrumentation.log"
    try:
        current = state()
        if current.get("stage") == "ready":
            break
    except (subprocess.CalledProcessError, ValueError):
        pass
    assert time.monotonic() < deadline, "Observation timeout: inspect this existing fixture, do not restart"
    time.sleep(.2)
rows = [line.split() for line in shell("ps", "-A", "-o", "PID,NAME").splitlines()]
pids = [int(row[0]) for row in rows if len(row) == 2 and row[1].startswith(pkg + ":pdf_processing")]
assert len(pids) == 1, pids
pid = pids[0]
assert pid != current["host"]
(args.output / "input.json").write_text(json.dumps(dict(current, renderer=pid), indent=2) + "\n")


def memory():
    literal = shell("cat", f"/proc/{pid}/status")
    values = {key: int(value) for key, value in re.findall(r"^(VmRSS|VmHWM|VmData):\s+(\d+) kB", literal, re.M)}
    assert "VmHWM" in values and "VmRSS" in values
    return dict(values, seconds=time.monotonic() - started), literal


started = time.monotonic()
samples = []
first, raw = memory()
samples.append(first)
(args.output / "before-status.txt").write_text(raw)
(args.output / "before-meminfo.txt").write_text(shell("dumpsys", "meminfo", str(pid)))
shell("run-as", pkg, "touch", "files/pdf-image-memory/start")
deadline = time.monotonic() + 180
while True:
    assert process.poll() is None, "Fixture terminated; inspect the same run"
    current = state()
    sample, raw = memory()
    samples.append(sample)
    if current["stage"] == "result":
        break
    assert time.monotonic() < deadline, "Observation timeout: the existing fixture needs inspection"
    time.sleep(.15)
(args.output / "after-status.txt").write_text(raw)
(args.output / "after-meminfo.txt").write_text(shell("dumpsys", "meminfo", str(pid)))
(args.output / "samples.json").write_text(json.dumps(samples, indent=2) + "\n")
summary = dict(current, renderer=pid, rssHighWaterKiB=sample["VmHWM"],
               initialRssKiB=first["VmRSS"], sampleCount=len(samples))
assert str(current["host"]) in shell("pidof", pkg).split()
shell("run-as", pkg, "touch", "files/pdf-image-memory/measured")
process.wait(timeout=30)
log.close()
literal = (args.output / "instrumentation.log").read_text()
assert process.returncode == 0 and f"PDF RECOVERY {phase.upper()} PASS" in literal, literal
assert state()["stage"] == "done"
assert "source-" not in shell("run-as", pkg, "ls", "files/pdf-image-memory")
summary["status"] = "PASS"
(args.output / "result.json").write_text(json.dumps(summary, indent=2) + "\n")
if not args.baseline:
    assert summary["error"] is None and summary["sourceBytesPreserved"]
    data = subprocess.check_output(adb + ["exec-out", "run-as", pkg, "cat", "files/pdf-image-memory/preview.png"], timeout=30)
    (args.output / "preview.png").write_bytes(data)
print("IMAGE MEMORY " + ("BASELINE" if args.baseline else "MODIFIED") + ": " + json.dumps(summary, sort_keys=True))
