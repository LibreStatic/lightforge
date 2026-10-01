#!/usr/bin/env python3
"""Kill a stopped fixture Activity behind Android DocumentsUI; verify actual saved result recovery."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--resume", action="store_true", help="Continue an existing fixture host after an observation failure")
parser.add_argument("--portable", action="store_true", help="Exercise the portable ZIP project destination contract")
args = parser.parse_args()
base = ["adb", "-s", args.serial]
package = "com.librestatic.lightforge.feature.pdfstudio.test"


def shell(*args):
    return subprocess.check_output(base + ["shell", *args], text=True, stderr=subprocess.PIPE, timeout=40)


def marker(name):
    return json.loads(shell("run-as", package, "cat", "files/pdf-picker-" + name + ".json"))


def await_marker(name, predicate, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = marker(name)
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            time.sleep(0.2)
            continue
        if predicate(value):
            return value
        time.sleep(0.2)
    raise AssertionError("Timed out waiting for " + name)


def nodes():
    for attempt in range(3):
        try:
            shell("uiautomator", "dump", "/sdcard/pdf-picker-window.xml")
            break
        except subprocess.CalledProcessError as error:
            if error.returncode != 137 or attempt == 2:
                raise
            print("UI observer was terminated; re-observing the unchanged activity", flush=True)
            time.sleep(1)
    return list(ET.fromstring(shell("cat", "/sdcard/pdf-picker-window.xml")).iter("node"))


def tap_match(predicate):
    matches = [n for n in nodes() if predicate(n.attrib)]
    assert matches, "Control not found in Android UI"
    bounds = list(map(int, re.findall(r"\d+", matches[0].get("bounds"))))
    if bounds[0] == bounds[2] or bounds[1] == bounds[3]:
        # DocumentsUI on API 30 can expose a visible bottom action with zero accessibility bounds.
        # Use keyboard focus + Enter, never tap an unverified (0,0) coordinate.
        for _ in range(16):
            shell("input", "keyevent", "61")
            if any(predicate(n.attrib) and n.get("focused") == "true" for n in nodes()):
                shell("input", "keyevent", "66")
                break
        else:
            raise AssertionError("Control could not receive keyboard focus")
    else:
        shell("input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
    time.sleep(0.6)


if not args.resume:
    prepare = "portable-picker-prepare" if args.portable else "picker-prepare"
    out = shell("am", "instrument", "-w", "-e", "phase", prepare, package + "/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner")
    print(out.strip(), flush=True)
    assert "PDF RECOVERY " + prepare.upper() + " PASS" in out
    checkpoint = marker("checkpoint")
    time.sleep(2)
    shell("am", "start", "-W", "-n", package + "/com.librestatic.lightforge.feature.pdfstudio.PdfPickerProbeActivity", "--es", "jobId", checkpoint["job"], "--es", "projectId", checkpoint["project"], "--ez", "portable", str(args.portable).lower())
else:
    checkpoint = marker("checkpoint")
created = await_marker("created", lambda x: True)
if any(n.get("text") == "Choose PDF destination" for n in nodes()):
    tap_match(lambda n: n.get("text") == "Choose PDF destination")
    # Platform Downloads belongs to another app; the fixture caller stays stopped.
    tap_match(lambda n: n.get("content-desc") in ("Show roots", "Mostrar raíces") or n.get("resource-id") == "android:id/home")
    tap_match(lambda n: n.get("text") in ("Downloads", "Descargas"))
else:
    assert args.resume, "Expected the fixture host"
saved = await_marker("saved", lambda x: x.get("pending") == checkpoint["job"])
assert saved["pid"] == created["pid"]
assert any("documentsui" in n.get("package", "") for n in nodes())
live = subprocess.run(base + ["shell", "pidof", package], capture_output=True, text=True, timeout=10)
assert live.returncode in (0, 1), live.stderr
if str(created["pid"]) in live.stdout.split():
    shell("am", "kill", "--user", "0", package)
    time.sleep(1)
    stopped = subprocess.run(base + ["shell", "pidof", package], capture_output=True, text=True, timeout=10)
    assert stopped.returncode in (0, 1) and str(created["pid"]) not in stopped.stdout.split(), "Caller still active"
else:
    print("Caller PID already terminated while stopped; continuing its existing saved request", flush=True)
time.sleep(1)
tap_match(lambda n: n.get("resource-id", "").endswith(":id/action_menu_save") or n.get("text") in ("SAVE", "GUARDAR"))
done = await_marker("done", lambda x: True)
assert done["status"] == "PASS", done
assert done["portable"] == args.portable
restored = marker("created")
assert restored["restored"] and restored["pending"] == checkpoint["job"]
assert restored["pid"] == done["pid"] and done["pid"] != created["pid"]
shell("am", "force-stop", package)
shell("rm", "/sdcard/pdf-picker-window.xml")
if args.portable:
    print("PORTABLE PICKER RECOVERY PASS: real ZIP CreateDocument; new PID; saved result restored; archive hashes and project import verified; grants and fixture output cleaned")
else:
    print("PDF PICKER RECOVERY PASS: real DocumentsUI; stopped caller killed; SavedState and ActivityResult restored in new PID; valid PDF verified; grants and fixture output cleaned")
