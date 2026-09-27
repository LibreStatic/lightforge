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
parser.add_argument("--portable", action="store_true", help="Exercise the portable ZIP project source contract")
args = parser.parse_args()
base = ["rtk", "proxy", "adb", "-s", args.serial]
package = "com.librestatic.lightforge.feature.pdfstudio.test"


def shell(*args):
    return subprocess.check_output(base + ["shell", *args], text=True, stderr=subprocess.PIPE, timeout=40)


def marker(name):
    return json.loads(shell("run-as", package, "cat", "files/pdf-import-" + name + ".json"))


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
            shell("uiautomator", "dump", "/sdcard/pdf-import-window.xml")
            break
        except subprocess.CalledProcessError as error:
            if error.returncode != 137 or attempt == 2:
                raise
            print("UI observer was terminated; re-observing the unchanged activity", flush=True)
            time.sleep(1)
    return list(ET.fromstring(shell("cat", "/sdcard/pdf-import-window.xml")).iter("node"))


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
    shell("am", "force-stop", package)
    for name in ("created", "saved", "checkpoint", "done"):
        shell("run-as", package, "rm", "-f", "files/pdf-import-" + name + ".json")
    shell("am", "start", "-W", "-n", package + "/com.librestatic.lightforge.feature.pdfstudio.PdfImportProbeActivity", "--ez", "portable", str(args.portable).lower())
checkpoint = await_marker("checkpoint", lambda x: bool(x.get("name")))
created = await_marker("created", lambda x: True)
if any(n.get("text") == "Choose import source" for n in nodes()):
    tap_match(lambda n: n.get("text") == "Choose import source" and n.get("enabled") == "true")
    tap_match(lambda n: n.get("content-desc") in ("Show roots", "Mostrar raíces") or n.get("resource-id") == "android:id/home")
    tap_match(lambda n: n.get("text") in ("Downloads", "Descargas"))
else:
    assert args.resume, "Expected fixture host"
checkpoint = marker("checkpoint")
saved = await_marker("saved", lambda x: x.get("pending") == checkpoint["request"])
assert saved["pid"] == created["pid"]
if not args.portable:
    assert saved["page"] == checkpoint["page"]
assert any("documentsui" in n.get("package", "") for n in nodes())
live = subprocess.run(base + ["shell", "pidof", package], capture_output=True, text=True, timeout=10)
assert live.returncode in (0, 1), live.stderr
if str(created["pid"]) in live.stdout.split():
    shell("am", "kill", "--user", "0", package)
    time.sleep(1)
    stopped = subprocess.run(base + ["shell", "pidof", package], capture_output=True, text=True, timeout=10)
    assert stopped.returncode in (0, 1) and str(created["pid"]) not in stopped.stdout.split(), "Caller still active"
# Choose an actual platform-owned Downloads document only after caller death.
def is_source(n):
    return n.get("text") == checkpoint["name"] or n.get("content-desc", "").startswith(checkpoint["name"] + ",")
# Activate the verified row with keyboard focus: grid thumbnails may only gain focus on a tap.
for _ in range(24):
    current = nodes()
    if any(n.get("focused") == "true" and any(is_source(child.attrib) for child in n.iter("node")) for n in current):
        shell("input", "keyevent", "66")
        break
    shell("input", "keyevent", "61")
else:
    raise AssertionError("Fixture source did not receive keyboard focus")
done = await_marker("done", lambda x: True)
assert done["status"] == "PASS", done
assert done["portable"] == args.portable
restored = marker("created")
assert restored["restored"] and restored["pending"] == checkpoint["request"]
assert restored["pid"] == done["pid"] and done["pid"] != created["pid"]
assert done["request"] == checkpoint["request"] and done["pages"] == 2
shell("am", "force-stop", package)
shell("rm", "/sdcard/pdf-import-window.xml")
print(("PORTABLE" if args.portable else "IMAGE") + " IMPORT PICKER RECOVERY PASS: real OpenDocument; stopped caller killed; saved owner and ActivityResult restored in new PID; correct project/page and source hashes verified; fixture cleaned")
