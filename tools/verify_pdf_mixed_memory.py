#!/usr/bin/env python3
"""Measure a full acceptance app and its pinned renderer during a mixed PDF queue.

Install the opt-in application/test APKs first. This observer never clears,
uninstalls or restarts an app. A timeout leaves the same instrumentation and its
log available for inspection. Kernel VmHWM is not a universal Java heap limit.
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
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
pkg = "com.librestatic.lightforge.pdfacceptance"
adb = ["rtk", "proxy", "adb", "-s", a.serial]

def shell(*args):
    return subprocess.check_output(adb + ["shell", *args], text=True, timeout=30, stderr=subprocess.PIPE)

def state():
    return json.loads(shell("run-as", pkg, "cat", "files/pdf-mixed-memory/state.json"))

shell("run-as", pkg, "ls")
existing = subprocess.run(adb + ["shell", "run-as", pkg, "test", "-d", "files/pdf-mixed-memory"], capture_output=True)
assert existing.returncode == 1, "Inspect the existing fixture; do not restart a live workload"
log = (a.output / "instrumentation.log").open("w")
process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "class",
    "com.librestatic.lightforge.PdfMixedMemoryDeviceTest", pkg + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
(a.output / "observer.json").write_text(json.dumps({"instrumentationObserverPid": process.pid, "serial": a.serial}) + "\n")
started = time.monotonic()
while True:
    assert process.poll() is None, "Terminal instrumentation; inspect its log and native state"
    try:
        current = state()
        if current.get("stage") == "ready": break
        assert current.get("stage") != "failed", current
    except (subprocess.CalledProcessError, ValueError):
        pass
    assert time.monotonic() - started < 180, "Observation timeout: inspect this same workload"
    time.sleep(.3)
rows = [line.split() for line in shell("ps", "-A", "-o", "PID,NAME").splitlines()]
renderers = [int(row[0]) for row in rows if len(row) == 2 and row[1].startswith(pkg + ":pdf_processing")]
assert len(renderers) == 1, renderers
pids = {"app": current["host"], "renderer": renderers[0]}
assert pids["app"] != pids["renderer"]
(a.output / "input.json").write_text(json.dumps(dict(current, pids=pids), indent=2) + "\n")

def memory(label, pid):
    raw = shell("cat", f"/proc/{pid}/status")
    values = {k: int(v) for k, v in re.findall(r"^(VmRSS|VmHWM|VmData):\s+(\d+) kB", raw, re.M)}
    assert "VmRSS" in values and "VmHWM" in values
    return dict(values, process=label, pid=pid, seconds=time.monotonic() - started), raw

samples = []
for label, pid in pids.items():
    sample, raw = memory(label, pid); samples.append(sample)
    (a.output / f"{label}-before-status.txt").write_text(raw)
    (a.output / f"{label}-before-meminfo.txt").write_text(shell("dumpsys", "meminfo", str(pid)))
shell("run-as", pkg, "touch", "files/pdf-mixed-memory/start")
stages = []
trimmed = False
while True:
    assert process.poll() is None, "Instrumentation ended; inspect the existing process/log"
    current = state()
    if not stages or stages[-1]["stage"] != current["stage"]:
        stages.append(dict(current, seconds=time.monotonic() - started))
        (a.output / "stages.json").write_text(json.dumps(stages, indent=2) + "\n")
    for label, pid in pids.items():
        sample, raw = memory(label, pid); samples.append(sample)
        (a.output / f"{label}-after-status.txt").write_text(raw)
    (a.output / "samples.json").write_text(json.dumps(samples, indent=2) + "\n")
    assert current["stage"] != "failed", current
    if current["stage"] in ("workload", "exporting") and not trimmed:
        literal = shell("am", "send-trim-memory", pkg, "RUNNING_LOW")
        (a.output / "trim-memory.txt").write_text(literal)
        assert "Error" not in literal, literal
        trimmed = True
    if current["stage"] == "result": break
    assert time.monotonic() - started < 360, "Observation timeout; inspect the same running fixture"
    time.sleep(.25)
for label, pid in pids.items():
    (a.output / f"{label}-after-meminfo.txt").write_text(shell("dumpsys", "meminfo", str(pid)))
assert str(pids["app"]) in shell("pidof", pkg).split()
shell("run-as", pkg, "touch", "files/pdf-mixed-memory/measured")
process.wait(timeout=60)
log.close()
literal = (a.output / "instrumentation.log").read_text()
assert process.returncode == 0 and "OK (1 test)" in literal and "FAILURES!!!" not in literal, literal
current = state()
assert current["stage"] == "done" and current["status"] == "PASS" and current["cleanup"]
assert trimmed, "Workload finished without observing the memory callback request"
archive = subprocess.check_output(adb + ["exec-out", "run-as", pkg, "tar", "-cf", "-", "files/pdf-mixed-memory"], timeout=60)
(a.output / "native-evidence.tar").write_bytes(archive)
summary = dict(current, pids=pids, requestedRunningLow=True,
    rssHighWaterKiB={label: max(s["VmHWM"] for s in samples if s["process"] == label) for label in pids},
    initialRssKiB={label: next(s["VmRSS"] for s in samples if s["process"] == label) for label in pids},
    sampleCount=len(samples))
(a.output / "result.json").write_text(json.dumps(summary, indent=2) + "\n")
print("MIXED APP MEMORY PASS: " + json.dumps(summary, sort_keys=True))
