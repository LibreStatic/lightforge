#!/usr/bin/env python3
"""Exact assigned API30/API35 UUID-only auth fixture. Never launches or changes an application vault."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "com.librestatic.lightforge.feature.privatealbum.test"
CLASS = "com.librestatic.lightforge.feature.privatealbum.PrivateAuthenticatedIndexSessionDeviceTest"
METHOD = "authenticatedIndexClosesReopensAndRetainsPreparedCiphertext"
CLEANUP = "cleanupUuidAliasesAfterInterruptedFixture"

LANES = {
    "emulator-5554": ("Lightforge_M2_API30", "30", "ro.kernel.qemu.avd_name"),
    "127.0.0.1:5563": ("Lightforge_PDF_API35", "35", "ro.boot.qemu.avd_name"),
}


def validate_target(serial, avd, api, qemu):
    expected = LANES.get(serial)
    if expected is None or (avd.strip(), api.strip(), qemu.strip()) != (expected[0], expected[1], "1"):
        raise ValueError("Exact assigned serial/AVD/API/emulator identity required")
    return expected


def credential_none_from_dump(raw):
    # Both assigned Android versions emit a User State block. The later User 0
    # [/data/.../spblob] listing is not a second credential-state record.
    lines = raw.splitlines()
    headers = [i for i, line in enumerate(lines) if re.fullmatch(r"[ \t]*User State:[ \t]*", line)]
    if len(headers) != 1:
        raise ValueError("Unrecognized User State block; no credential changes")
    start = headers[0]
    indent = len(lines[start]) - len(lines[start].lstrip(" \t"))
    body = []
    for line in lines[start + 1:]:
        if line.strip() and len(line) - len(line.lstrip(" \t")) <= indent:
            break
        body.append(line)
    sections = re.findall(r"(?ms)^[ \t]*User 0:?[ \t]*\n(.*?)(?=^[ \t]*User [0-9]+\b|\Z)", "\n".join(body) + "\n")
    if len(sections) != 1:
        raise ValueError("Unrecognized credential user-0 section; no credential changes")
    quality = re.findall(r"(?m)^[ \t]*Quality: (\d+)[ \t]*$", sections[0])
    kind = re.findall(r"(?m)^[ \t]*CredentialType: ([^\r\n]+)$", sections[0])
    if len(quality) != 1 or len(kind) != 1:
        raise ValueError("Unrecognized credential fields; no credential changes")
    return quality[0] == "0" and kind[0].strip() in ("None", "NONE")


# Accepted same-connection active-root observer, never rebuilt or replaced by this runner.
OBSERVER_SHA256 = "a8c8d1fec35e6477e05cff19c2c828583411bbafadbacc26d9757185804ddbe0"

def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def observer_remote_path(digest):
    require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest),
            "Exact observer SHA256 is required")
    return "/data/local/tmp/lightforge-ui-observer-" + digest + ".jar"


def real_display_receipt(output, expected_path):
    try:
        receipt = json.loads(output)
    except (ValueError, TypeError) as failure:
        raise RuntimeError("Invalid real-display observer receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {
        "version", "observer", "path", "appWidth", "appHeight", "realWidth", "realHeight",
        "rotation", "rawRootBounds", "nodes"}, "Unexpected real-display observer receipt fields")
    require(type(receipt["version"]) is int and receipt["version"] == 1
            and receipt["observer"] == "builtin-real-display" and receipt["path"] == expected_path,
            "Real-display observer identity/path differs")
    for field in ("appWidth", "appHeight", "realWidth", "realHeight", "nodes"):
        require(type(receipt[field]) is int and receipt[field] > 0, "Invalid real-display observer dimensions/count")
    require(receipt["appWidth"] <= receipt["realWidth"] and receipt["appHeight"] <= receipt["realHeight"],
            "Real-display observer reports inconsistent dimensions")
    require(type(receipt["rotation"]) is int and receipt["rotation"] in (0, 1, 2, 3),
            "Invalid real-display observer rotation")
    require(isinstance(receipt["rawRootBounds"], str)
            and re.fullmatch(r"-?\d+ -?\d+ -?\d+ -?\d+", receipt["rawRootBounds"]),
            "Missing raw accessibility root bounds")
    return receipt



def active_display_tree(output, raw, expected_path):
    receipt = real_display_receipt(output, expected_path)
    if not raw.lstrip().startswith("<"):
        raise ValueError("Fresh observer XML missing or denied")
    root = ET.fromstring(raw)
    nodes = list(root.iter("node"))
    if root.tag != "hierarchy" or not nodes or len(nodes) != receipt["nodes"]:
        raise ValueError("Active-display receipt/XML hierarchy mismatch")
    return root, receipt


class Runner:
    def __init__(self, args):
        if args.serial not in LANES:
            raise ValueError("Unassigned serial; no commands or fixture directory created")
        self.args = args
        self.id = str(uuid.uuid4())
        self.out = Path(args.evidence).resolve()
        self.out.mkdir(parents=True, exist_ok=False)
        self.events = []
        self.stage = "preflight"
        self.started = time.monotonic()
        self.deadline = self.started + 240
        self.pin = None
        self.pin_attempted = False
        self.aliases_confirmed = False
        self.instrumentation_started = False
        self.process = None
        self.readers = []
        self.stdout = []
        self.stderr = []
        self.process_command = None
        self.recorded = False
        self.dumps = []
        self.initial_none = False
        self.final_none = False
        self.failure = None
        self.cleanup_failure = None
        self.marker_prefix = "AUTH_FIXTURE " + self.id + " "
        self.save()

    def save(self):
        (self.out / "commands.json").write_text(json.dumps(self.events, indent=2) + "\n")

    def run(self, command, *, secret=False, check=True, timeout=30, include_stderr=False):
        cmd = ["rtk", "proxy", *map(str, command)]
        limit = min(timeout, max(1, self.deadline - time.monotonic()))
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=limit)
            output, error, code = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired:
            output, error, code = "", "Command timed out", 124
        self.events.append(dict(command=["REDACTED_PRIVATE_INPUT_OR_UI"] if secret else cmd,
            input="Ephemeral credential or private UI; omitted" if secret else self.stage,
            stdout="[redacted]" if secret else output,
            stderr="[redacted]" if secret else error, exit=code))
        self.save()
        if check and code != 0:
            raise RuntimeError("Command failed during " + self.stage)
        return (output, code, error) if include_stderr else (output, code)

    def adb(self, *parts, **kwargs):
        return self.run(["adb", "-s", self.args.serial, *parts], **kwargs)

    def shell(self, *parts, **kwargs):
        return self.adb("shell", *parts, **kwargs)

    def credential_none(self):
        raw, _ = self.shell("dumpsys", "lock_settings", secret=True)
        none = credential_none_from_dump(raw)
        self.events.append(dict(assertion="Credential state inspected", credentialNone=none, exit=0))
        self.save()
        return none

    def guard_observer(self):
        path = observer_remote_path(OBSERVER_SHA256)
        output, code, error = self.shell("sha256sum", path, include_stderr=True)
        require(code == 0 and not error.strip() and output.split() == [OBSERVER_SHA256, path],
                "Installed accepted observer hash/path differs")
        return path

    def tree(self):
        # Reuse the accepted active-display serializer. Its receipt follows disconnect;
        # its own bounded same-connection root wait is the only retry, not a second host loop.
        path = "/sdcard/creation-process-private-auth-" + self.id + "-" + str(len(self.dumps)) + ".xml"
        self.dumps.append(path)
        observer = self.guard_observer()
        output, code, error = self.shell("env", "CLASSPATH=/system/framework/uiautomator.jar:" + observer,
            "app_process", "/system/bin", "com.librestatic.lightforge.tools.RealDisplayDump", path,
            timeout=35, check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "Active-display dump did not finish successfully; XML not read")
        # Validate the fresh receipt before touching the output file.
        real_display_receipt(output, path)
        raw, code, error = self.adb("exec-out", "cat", path, secret=True, timeout=5,
            check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "Active-display XML could not be read")
        root, receipt = active_display_tree(output, raw, path)
        self.events.append(dict(observer="verified-active-display", receipt=receipt, exit=0))
        self.save()
        return root

    def tap(self, node):
        attributes = node.attrib
        if attributes.get("enabled") != "true":
            raise AssertionError("Disabled authentication control")
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attributes.get("bounds", ""))
        if not match:
            raise AssertionError("Malformed authentication control bounds")
        x1, y1, x2, y2 = map(int, match.groups())
        if x2 <= x1 or y2 <= y1:
            raise AssertionError("Authentication control has empty bounds")
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def authenticate(self, phase):
        self.stage = "authentication-" + phase
        end = min(self.deadline - 50, time.monotonic() + 35)
        tapped_ready = False
        pin_sent = False
        while time.monotonic() < end:
            if self.process.poll() is not None:
                raise AssertionError("Instrumentation terminated while awaiting authentication")
            tree = self.tree()
            nodes = list(tree.iter("node"))
            if not tapped_ready:
                ready = [node for node in nodes if node.attrib.get("content-desc") == "private-auth-fixture-ready"
                         and node.attrib.get("package") == PACKAGE]
                if len(ready) == 1:
                    self.tap(ready[0])
                    tapped_ready = True
            else:
                edits = [node for node in nodes if node.attrib.get("class") == "android.widget.EditText"
                         and node.attrib.get("package") in ("com.android.settings", "com.android.systemui")
                         and node.attrib.get("enabled") == "true"]
                if len(edits) == 1 and not pin_sent:
                    self.tap(edits[0])
                    self.shell("input", "text", self.pin, secret=True)
                    self.shell("input", "keyevent", "KEYCODE_ENTER")
                    pin_sent = True
                elif not pin_sent:
                    alternate = [node for node in nodes if node.attrib.get("package") in ("com.android.settings", "com.android.systemui")
                        and re.search(r"use.*pin|use.*password|device.*credential", node.attrib.get("text", ""), re.I)]
                    if len(alternate) == 1:
                        self.tap(alternate[0])
            if self.marker_prefix + "AUTH_SUCCEEDED_" + phase in "".join(self.stdout):
                if not pin_sent:
                    raise AssertionError("Authentication completed without the fixture credential interaction")
                self.events.append(dict(assertion="Real DEVICE_CREDENTIAL callback", phase=phase, exit=0))
                self.save()
                return
            time.sleep(.3)
        raise TimeoutError("Authentication checkpoint timed out during " + phase)

    def start_instrumentation(self):
        command = ["rtk", "proxy", "adb", "-s", self.args.serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "fixtureUuid", self.id, "-e", "class", CLASS + "#" + METHOD,
            PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"]
        self.process_command = command
        self.process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        self.instrumentation_started = True
        def drain(pipe, values):
            for line in pipe:
                values.append(line)
            pipe.close()
        for pipe, target in [(self.process.stdout, self.stdout), (self.process.stderr, self.stderr)]:
            thread = threading.Thread(target=drain, args=(pipe, target), daemon=True)
            thread.start()
            self.readers.append(thread)

    def record_instrumentation(self):
        if self.process is None or self.recorded:
            return
        for thread in self.readers:
            thread.join(timeout=2)
        stdout, stderr = "".join(self.stdout), "".join(self.stderr)
        (self.out / "instrumentation.stdout").write_text(stdout)
        (self.out / "instrumentation.stderr").write_text(stderr)
        self.events.append(dict(command=self.process_command, input=dict(fixtureUuid=self.id, method=METHOD),
                                stdout=stdout, stderr=stderr, exit=self.process.poll()))
        self.recorded = True
        self.aliases_confirmed = self.marker_prefix + "CLEANUP_CONFIRMED" in stdout
        self.save()

    def cleanup(self):
        self.stage = "cleanup-owned-uuid-keys"
        if self.process is not None and self.process.poll() is None:
            # This self-targeting test APK only. No main/acceptance package is stopped.
            self.shell("am", "force-stop", PACKAGE, timeout=5)
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:
                    self.process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=2)
        self.record_instrumentation()
        if self.instrumentation_started and not self.aliases_confirmed:
            output, code = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.id,
                "-e", "class", CLASS + "#" + CLEANUP,
                PACKAGE + "/androidx.test.runner.AndroidJUnitRunner", timeout=35, check=False)
            self.aliases_confirmed = code == 0 and "OK (1 test)" in output and "FAILURES!!!" not in output \
                and self.marker_prefix + "CLEANUP_CONFIRMED" in output
            if not self.aliases_confirmed:
                raise AssertionError("UUID alias cleanup unconfirmed; temporary credential retained")
        if self.pin_attempted:
            # Before instrumentation started no fixture key-creation code has run.
            if self.instrumentation_started and not self.aliases_confirmed:
                raise AssertionError("Credential removal requires exact alias absence confirmation")
            self.stage = "restore-initial-credential-none"
            self.shell("locksettings", "clear", "--old", self.pin, secret=True, timeout=5)
            self.final_none = self.credential_none()
            if not self.final_none:
                raise AssertionError("Original None credential state not restored")
            (self.out / "credential-recovery.private").unlink(missing_ok=True)
            self.pin = None
        elif self.initial_none:
            self.final_none = self.credential_none()
        for path in self.dumps:
            self.shell("rm", "-f", path, timeout=3)

    def execute(self):
        status = "FAIL"
        try:
            lane = LANES[self.args.serial]
            avd, _ = self.shell("getprop", lane[2])
            api, _ = self.shell("getprop", "ro.build.version.sdk")
            qemu, _ = self.shell("getprop", "ro.kernel.qemu")
            validate_target(self.args.serial, avd, api, qemu)
            processes, _ = self.shell("dumpsys", "activity", "processes")
            if not re.search(r"(?m)^ACTIVITY MANAGER RUNNING PROCESSES \(dumpsys activity processes\)", processes) or "ActiveInstrumentation{" in processes:
                raise ValueError("Recognized process dump without active instrumentation required")
            self.initial_none = self.credential_none()
            if not self.initial_none:
                raise AssertionError("Existing credential preserved; fixture not started")
            apk = Path(self.args.apk).resolve(strict=True)
            sha = hashlib.sha256(apk.read_bytes()).hexdigest()
            if sha != self.args.expected_sha256:
                raise AssertionError("APK hash does not match assigned build")
            badging, _ = self.run([self.args.aapt, "dump", "badging", apk])
            if not re.search(r"^package: name='" + re.escape(PACKAGE) + r"'", badging, re.M):
                raise AssertionError("Only privatealbum self-targeting test APK can be installed")
            xml, _ = self.run([self.args.aapt, "dump", "xmltree", apk, "AndroidManifest.xml"])
            if not re.search(r"targetPackage[^\r\n]*[\"]" + re.escape(PACKAGE) + r"[\"]", xml):
                raise AssertionError("Instrumentation target must be the isolated test APK")
            self.apk_sha = sha
            # Read-only observer format probe before installation or any credential change.
            self.tree()
            self.adb("install", "-r", "-t", apk, timeout=35)
            self.pin = "".join(secrets.choice("0123456789") for _ in range(8))
            recovery = self.out / "credential-recovery.private"
            fd = os.open(recovery, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as secret_file:
                secret_file.write(self.pin); secret_file.flush(); os.fsync(secret_file.fileno())
            self.pin_attempted = True
            self.shell("locksettings", "set-pin", self.pin, secret=True, timeout=8)
            if self.credential_none():
                raise AssertionError("Temporary credential not established")
            self.stage = "native-authenticated-migration"
            self.start_instrumentation()
            handled = set()
            # Reserve the final 50s of the overall 240s budget for fallback cleanup and PIN restoration.
            while self.process.poll() is None and time.monotonic() < self.deadline - 50:
                text = "".join(self.stdout)
                for phase in ("MIGRATION", "REOPEN"):
                    if phase not in handled and self.marker_prefix + "AUTH_REQUIRED_" + phase in text:
                        self.authenticate(phase)
                        handled.add(phase)
                time.sleep(.2)
            if self.process.poll() is None:
                raise TimeoutError("Native fixture exceeded bounded runtime")
            self.record_instrumentation()
            text = "".join(self.stdout)
            if self.process.returncode != 0 or "OK (1 test)" not in text or "FAILURES!!!" in text:
                raise AssertionError("Native fixture did not pass")
            if handled != {"MIGRATION", "REOPEN"} or self.marker_prefix + "INDEX_SESSION_PASS" not in text:
                raise AssertionError("Required real authentication/migration checkpoints missing")
            status = "PASS"
        except Exception as error:
            self.failure = dict(stage=self.stage, type=type(error).__name__, reason=str(error))
        finally:
            try:
                # Never send device mutations after a failed identity/credential preflight.
                if self.initial_none:
                    self.cleanup()
            except Exception as error:
                self.cleanup_failure = dict(stage=self.stage, type=type(error).__name__, reason=str(error))
                status = "FAIL"
            if self.failure is not None:
                status = "FAIL"
            result = dict(status=status, fixtureUuid=self.id, serial=self.args.serial,
                expectedApkSha256=self.args.expected_sha256, failure=self.failure,
                cleanupFailure=self.cleanup_failure, aliasesConfirmedAbsent=self.aliases_confirmed,
                credentialInitiallyNone=self.initial_none, credentialRestoredNone=self.final_none,
                elapsedSeconds=time.monotonic()-self.started)
            (self.out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result))
        return 0 if status == "PASS" else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", choices=tuple(LANES), default="emulator-5554")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", args.expected_sha256):
        parser.error("Expected APK SHA256 is required")
    return Runner(args).execute()


if __name__ == "__main__":
    sys.exit(main())
