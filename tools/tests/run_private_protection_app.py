#!/usr/bin/env python3
"""Production private migration/empty-setup acceptance using one owned demo UID.

preflight: require target absent or exact previous accepted APK + native cleanup receipt,
           assigned AVD/API and CredentialNone; record the admitted installation identity.
run: verify explicit APK hashes and recheck reused UID/install time/receipt/private absence;
     drive real platform auth, then clean owned files before restoring original None.
Never installs, uninstalls, clears app data, or operates on another application.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import threading
import time
import uuid

PACKAGE = "com.librestatic.lightforge.demo.pdfacceptance"
TEST_PACKAGE = PACKAGE + ".test"
CLASS = "com.librestatic.lightforge.PrivateProtectionAppDeviceTest"
COMPONENT = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
AVD = "Lightforge_M2_API30"
SERIAL = "emulator-5554"  # Backward-compatible default only; commands use args.serial.
LANES = {
    SERIAL: (AVD, 30, "ro.kernel.qemu.avd_name"),
    "127.0.0.1:5563": ("Lightforge_PDF_API35", 35, "ro.boot.qemu.avd_name"),
}
SCENARIOS = {
    "legacy": ("bootstrapOwnedLegacyVault", "legacyVaultMigratesThroughProductionUiAndReopens", ("OPEN", "MIGRATION", "REOPEN")),
    "empty-setup": ("prepareOwnedEmptyFixture", "emptyVaultConfiguresImportsAndReopens", ("OPEN", "SETUP", "REOPEN")),
}


def require(condition, message):
    """Operational invariants remain active under python -O / PYTHONOPTIMIZE."""
    if not condition:
        raise RuntimeError(message)


def target_lane(serial):
    require(serial in LANES, "Serial is not assigned")
    return LANES[serial]


def validate_target(serial, avd, api, emulator):
    expected = target_lane(serial)
    require((avd.strip(), api.strip(), emulator.strip()) == (expected[0], str(expected[1]), "1"),
            "Exact assigned serial/AVD/API/emulator identity required")
    return expected


def ticket_matches_lane(ticket, serial):
    avd, api, _ = target_lane(serial)
    return (ticket.get("serial") == serial and ticket.get("avd") == avd
            and type(ticket.get("api")) is int and ticket["api"] == api)


def credential_none_from_dump(raw):
    # Both assigned Android versions emit a User State block. The later User 0
    # [/data/.../spblob] listing is not a second credential-state record.
    lines = raw.splitlines()
    headers = [i for i, line in enumerate(lines) if re.fullmatch(r"[ \t]*User State:[ \t]*", line)]
    if len(headers) != 1:
        raise RuntimeError("Unrecognized User State block; no credential changes")
    start = headers[0]
    indent = len(lines[start]) - len(lines[start].lstrip(" \t"))
    body = []
    for line in lines[start + 1:]:
        if line.strip() and len(line) - len(line.lstrip(" \t")) <= indent:
            break
        body.append(line)
    sections = re.findall(r"(?ms)^[ \t]*User 0:?[ \t]*\n(.*?)(?=^[ \t]*User [0-9]+\b|\Z)", "\n".join(body) + "\n")
    if len(sections) != 1:
        raise RuntimeError("Unrecognized credential user-0 section; no credential changes")
    quality = re.findall(r"(?m)^[ \t]*Quality: (\d+)[ \t]*$", sections[0])
    kind = re.findall(r"(?m)^[ \t]*CredentialType: ([^\r\n]+)$", sections[0])
    if len(quality) != 1 or len(kind) != 1:
        raise RuntimeError("Unrecognized credential fields; no credential changes")
    return quality[0] == "0" and kind[0].strip() in ("None", "NONE")

class Runner:
    def __init__(self, args):
        target_lane(args.serial)  # Reject before evidence directory or commands.
        self.args = args
        self.out = Path(args.evidence).resolve()
        self.out.mkdir(parents=True, exist_ok=False)
        self.events = []
        self.deadline = time.monotonic() + 420
        self.pin = None
        self.pin_attempted = False
        self.bootstrap_started = False
        self.cleaned = False
        self.process = None
        self.stdout = []
        self.stderr = []
        self.threads = []
        self.fixture = None
        self.stage = args.phase
        self.save()

    @property
    def scenario(self):
        value = getattr(self.args, "scenario", "legacy")
        require(value in SCENARIOS, "Unknown private acceptance scenario")
        return value

    @property
    def scenario_methods(self):
        return SCENARIOS[self.scenario]

    def installed_hash(self, package):
        raw, _ = self.shell("pm", "path", package)
        paths = [line.removeprefix("package:") for line in raw.splitlines() if line.startswith("package:")]
        require(len(paths) == 1, "Expected one explicitly installed APK")
        digest, _ = self.shell("sha256sum", paths[0])
        return digest.split()[0]

    def install_identity(self):
        raw, _ = self.shell("dumpsys", "package", PACKAGE)
        uid = re.search(r"^\s*(?:userId|appId)=(\d+)\s*$", raw, re.MULTILINE)
        first = re.search(r"^\s*firstInstallTime=(.+)$", raw, re.MULTILINE)
        require(uid is not None and first is not None, "Unrecognized acceptance installation identity")
        return dict(uid=int(uid.group(1)), firstInstallTime=first.group(1).strip())

    def read_device_receipt(self, fixture):
        require(str(uuid.UUID(fixture)) == fixture, "Noncanonical previous fixture UUID")
        raw, _ = self.shell("run-as", PACKAGE, "cat", "files/private-protection-fixture-" + fixture + ".json")
        return raw

    def admit_previous_clean_install(self):
        require(self.scenario == "empty-setup", "Reuse is only admitted for the explicit empty-setup cut")
        names = ("previous_ticket", "previous_result", "previous_receipt")
        require(all(getattr(self.args, name, None) for name in names), "Exact previous ticket, result and receipt required")
        previous_ticket = json.loads(Path(self.args.previous_ticket).read_text())
        previous_result = json.loads(Path(self.args.previous_result).read_text())
        receipt_bytes = Path(self.args.previous_receipt).read_bytes()
        receipt = json.loads(receipt_bytes)
        previous = receipt["uuid"]
        require(str(uuid.UUID(previous)) == previous, "Previous UUID malformed")
        require(previous_ticket.get("version") == 1 and previous_ticket.get("packageInitiallyAbsent") is True and previous_ticket.get("credentialInitiallyNone") is True,
                "Original fresh-UID admission required")
        require(previous_ticket.get("package") == PACKAGE and ticket_matches_lane(previous_ticket, self.args.serial) and
                previous_ticket.get("fixtureUuid") == previous, "Previous ticket target/UUID differs")
        require(previous_result.get("status") == "PASS" and previous_result.get("package") == PACKAGE and
                previous_result.get("fixtureUuid") == previous and previous_result.get("aliasesAndFilesConfirmedAbsent") is True and
                previous_result.get("credentialRestoredNone") is True, "Previous complete acceptance/cleanup required")
        require(receipt.get("package") == PACKAGE and receipt.get("verified") is True and
                receipt.get("cleanupConfirmed") is True, "Previous native verified cleanup receipt required")
        require(self.read_device_receipt(previous).encode() == receipt_bytes, "Native receipt differs from exact previous receipt bytes")
        old_hash = previous_result.get("expectedApkSha256", "")
        require(re.fullmatch(r"[0-9a-f]{64}", old_hash) is not None and self.installed_hash(PACKAGE) == old_hash,
                "Installed app must still be the exact previously accepted APK before admission")
        require(self.installed_hash(TEST_PACKAGE) == self.args.test_apk_sha256, "Install the exact reviewed fixture APK before admission")
        identity = self.install_identity()
        self.fixture = previous
        output, passed = self.instrument("verifyOwnedEmptyAdmission")
        require(passed and self.marker("EMPTY_ADMISSION_CONFIRMED") in output, "Native absence/alias admission failed")
        return dict(previousFixtureUuid=previous, receiptSha256=hashlib.sha256(receipt_bytes).hexdigest(),
                    acceptedApkSha256=old_hash, installation=identity)

    def verify_reuse_admission(self, reuse):
        previous = reuse["previousFixtureUuid"]
        require(self.install_identity() == reuse["installation"], "Acceptance UID/install identity changed; no reinstall admission")
        raw = self.read_device_receipt(previous)
        require(hashlib.sha256(raw.encode()).hexdigest() == reuse["receiptSha256"], "Previous cleanup receipt changed since admission")
        record = json.loads(raw)
        require(record.get("uuid") == previous and record.get("package") == PACKAGE and
                record.get("verified") is True and record.get("cleanupConfirmed") is True, "Previous native cleanup no longer confirmed")
        current = self.fixture
        try:
            self.fixture = previous
            output, passed = self.instrument("verifyOwnedEmptyAdmission")
            require(passed and self.marker("EMPTY_ADMISSION_CONFIRMED") in output, "Private state appeared after admission")
        finally:
            self.fixture = current

    def save(self):
        (self.out / "commands.json").write_text(json.dumps(self.events, indent=2) + "\n")

    def run(self, *args, secret=False, timeout=30, check=True):
        command = ["rtk", "proxy", *map(str, args)]
        try:
            result = subprocess.run(command, capture_output=True, text=True,
                                    timeout=min(timeout, max(1, self.deadline - time.monotonic())))
            stdout, stderr, code = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired:
            stdout, stderr, code = "", "Timed out", 124
        self.events.append(dict(command=["PRIVATE_INPUT_OR_UI"] if secret else command,
                                input=self.stage, stdout="[private]" if secret else stdout,
                                stderr="[private]" if secret else stderr, exit=code))
        self.save()
        if check and code:
            raise RuntimeError("Command failed during " + self.stage)
        return stdout, code

    def adb(self, *args, **kwargs):
        return self.run("adb", "-s", self.args.serial, *args, **kwargs)

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs)

    def guard(self):
        _, _, avd_property = target_lane(self.args.serial)
        avd, _ = self.shell("getprop", avd_property)
        api, _ = self.shell("getprop", "ro.build.version.sdk")
        emulator, _ = self.shell("getprop", "ro.kernel.qemu")
        validate_target(self.args.serial, avd, api, emulator)

    def credential_none(self):
        raw, _ = self.shell("dumpsys", "lock_settings", secret=True)
        value = credential_none_from_dump(raw)
        self.events.append(dict(assertion="Credential state", credentialNone=value, exit=0))
        self.save()
        return value

    def preflight(self):
        self.guard()
        require(self.credential_none(), "Existing credential preserved")
        listed, _ = self.shell("pm", "list", "packages", "--user", "0", PACKAGE)
        installed = "package:" + PACKAGE in listed.splitlines()
        reuse = None
        if installed:
            reuse = self.admit_previous_clean_install()
        else:
            require(not any(getattr(self.args, name, None) for name in ("previous_ticket", "previous_result", "previous_receipt")),
                    "Previous-receipt reuse cannot authorize a missing/reinstalled package")
            raw, _ = self.shell("pm", "path", PACKAGE, check=False)
            require("package:" not in raw, "Fresh target package must be absent before root installation")
        avd, api, _ = target_lane(self.args.serial)
        ticket = dict(version=1, package=PACKAGE, serial=self.args.serial, avd=avd, api=api,
                      fixtureUuid=str(uuid.uuid4()), credentialInitiallyNone=True,
                      packageInitiallyAbsent=not installed, reuseAdmission=reuse, scenario=self.scenario, createdAt=time.time())
        path = Path(self.args.ticket).resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as out:
            out.write(json.dumps(ticket, indent=2) + "\n"); out.flush(); os.fsync(out.fileno())
        (self.out / "result.json").write_text(json.dumps(dict(status="PREFLIGHT_PASS", ticket=str(path), **ticket), indent=2) + "\n")
        print(json.dumps(dict(status="PREFLIGHT_PASS", ticket=str(path), fixtureUuid=ticket["fixtureUuid"])))
        return 0

    def instrument(self, method, timeout=60):
        output, code = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
                                 "-e", "scenario", self.scenario, "-e", "class", CLASS + "#" + method, COMPONENT,
                                 timeout=timeout, check=False)
        passed = code == 0 and "OK (1 test)" in output and "FAILURES!!!" not in output
        return output, passed

    def start_test(self):
        command = ["rtk", "proxy", "adb", "-s", self.args.serial, "shell", "am", "instrument", "-w", "-r",
                   "-e", "fixtureUuid", self.fixture, "-e", "scenario", self.scenario, "-e", "class",
                   CLASS + "#" + self.scenario_methods[1], COMPONENT]
        self.process_command = command
        self.process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        text=True, bufsize=1)
        def drain(pipe, lines):
            for line in pipe:
                lines.append(line)
            pipe.close()
        for pipe, lines in ((self.process.stdout, self.stdout), (self.process.stderr, self.stderr)):
            thread = threading.Thread(target=drain, args=(pipe, lines), daemon=True)
            thread.start(); self.threads.append(thread)

    def authenticate(self, phase):
        self.stage = "platform-auth-" + phase
        end = min(self.deadline - 70, time.monotonic() + 65)
        sent = False
        while time.monotonic() < end:
            output = "".join(self.stdout)
            if self.marker("AUTH_SUCCEEDED_" + phase) in output:
                require(sent, "Success without actual credential interaction")
                self.events.append(dict(assertion="Production auth UI success", phase=phase, exit=0))
                self.save(); return
            require(self.process.poll() is None, "Instrumentation exited during authentication")
            # Native UiDevice reads the actual Settings/SystemUI field using its existing
            # accessibility connection. A separate `uiautomator dump` competes with it.
            match = re.search(re.escape(self.marker("AUTH_INPUT_" + phase)) + r" (\d+) (\d+)(?:\r?\n|$)", output)
            if match and not sent:
                x, y = map(int, match.groups())
                require(0 < x < 20_000 and 0 < y < 20_000, "Invalid credential field coordinates")
                self.shell("input", "tap", x, y, secret=True)
                self.shell("input", "text", self.pin, secret=True)
                self.shell("input", "keyevent", "KEYCODE_ENTER", secret=True)
                sent = True
            time.sleep(.25)
        raise TimeoutError("Authentication timed out: " + phase)

    def marker(self, name):
        return "AUTH_FIXTURE " + self.fixture + " " + name

    def cleanup(self):
        if not self.bootstrap_started:
            return
        self.stage = "close-only-owned-target"
        self.shell("am", "force-stop", PACKAGE, timeout=8)
        if self.process is not None:
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=5)
            for thread in self.threads:
                thread.join(timeout=2)
            self.events.append(dict(command=self.process_command, input=self.fixture,
                                    stdout="".join(self.stdout), stderr="".join(self.stderr),
                                    exit=self.process.returncode))
            self.save()
        self.stage = "cleanup-exact-owned-aliases-and-files"
        output, passed = self.instrument("cleanupOwnedFixture", timeout=55)
        self.cleaned = passed and self.marker("CLEANUP_CONFIRMED") in output
        require(self.cleaned, "Cleanup unconfirmed; temporary credential retained")
        receipt = self.read_device_receipt(self.fixture)
        native = json.loads(receipt)
        self.cleaned = self.cleaned and native.get("uuid") == self.fixture and native.get("package") == PACKAGE and native.get("cleanupConfirmed") is True
        require(self.cleaned, "Cleanup receipt missing or changed; temporary credential retained")
        (self.out / "owned-receipt.json").write_text(receipt)
        if self.pin_attempted:
            self.stage = "restore-original-credential-none"
            self.shell("locksettings", "clear", "--old", self.pin, secret=True, timeout=8)
            require(self.credential_none(), "Original None credential not restored")
            (self.out / "credential-recovery.private").unlink(missing_ok=True)
            self.pin = None
        self.shell("rm", "-f", self.dump_path, timeout=5)

    def execute(self):
        failure = None
        cleanup_failure = None
        completed = False
        try:
            self.guard()
            ticket = json.loads(Path(self.args.ticket).read_text())
            require(ticket["version"] == 1 and ticket["package"] == PACKAGE, 'Private protection guard failed: ticket["version"] == 1 and ticket["package"] == PACKAGE')
            require(ticket_matches_lane(ticket, self.args.serial), "Preflight ticket belongs to another lane")
            require(ticket.get("scenario", "legacy") == self.scenario, "Ticket scenario differs")
            require(ticket.get("credentialInitiallyNone") is True, "Original None credential required")
            reuse = ticket.get("reuseAdmission")
            require((ticket.get("packageInitiallyAbsent") is True and reuse is None) or
                    (ticket.get("packageInitiallyAbsent") is False and self.scenario == "empty-setup" and isinstance(reuse, dict)),
                    "Fresh target or exact previous-clean-install admission required")
            require(0 <= time.time() - ticket["createdAt"] < 86_400, "Fresh preflight ticket required")
            self.fixture = str(uuid.UUID(ticket["fixtureUuid"]))
            require(self.fixture == ticket["fixtureUuid"], 'Private protection guard failed: self.fixture == ticket["fixtureUuid"]')
            self.dump_path = "/sdcard/private-protection-app-" + self.fixture + ".xml"
            require(self.credential_none(), 'Private protection guard failed: self.credential_none()')
            raw, _ = self.shell("pm", "path", PACKAGE)
            paths = [line.removeprefix("package:") for line in raw.splitlines() if line.startswith("package:")]
            require(len(paths) == 1, 'Private protection guard failed: len(paths) == 1')
            actual, _ = self.shell("sha256sum", paths[0])
            require(actual.split()[0] == self.args.apk_sha256, "Root-installed APK differs")
            test_raw, _ = self.shell("pm", "path", TEST_PACKAGE)
            test_paths = [line.removeprefix("package:") for line in test_raw.splitlines() if line.startswith("package:")]
            require(len(test_paths) == 1, 'Private protection guard failed: len(test_paths) == 1')
            test_actual, _ = self.shell("sha256sum", test_paths[0])
            require(test_actual.split()[0] == self.args.test_apk_sha256, "Root-installed fixture APK differs")
            info, _ = self.shell("dumpsys", "package", PACKAGE)
            require("Package [" + PACKAGE + "]" in info, 'Private protection guard failed: "Package [" + PACKAGE + "]" in info')
            require(re.search(r"(?:pkgFlags|flags)=\[[^\]]*DEBUGGABLE", info), 'Private protection guard failed: re.search(r"(?:pkgFlags|flags)=\\[[^\\]]*DEBUGGABLE", info)')
            runners, _ = self.shell("pm", "list", "instrumentation")
            require(COMPONENT + " (target=" + PACKAGE + ")" in runners, 'Private protection guard failed: COMPONENT + " (target=" + PACKAGE + ")" in runners')
            if reuse is not None:
                self.verify_reuse_admission(reuse)
            self.stage = "bootstrap-owned-legacy-two-jpeg" if self.scenario == "legacy" else "prepare-owned-empty-source-only"
            self.bootstrap_started = True
            output, passed = self.instrument(self.scenario_methods[0], timeout=65)
            require(passed and self.marker("BOOTSTRAP_READY") in output, 'Private protection guard failed: passed and self.marker("BOOTSTRAP_READY") in output')
            self.pin = "".join(secrets.choice("0123456789") for _ in range(8))
            fd = os.open(self.out / "credential-recovery.private", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as secret:
                secret.write(self.pin); secret.flush(); os.fsync(secret.fileno())
            self.pin_attempted = True
            self.shell("locksettings", "set-pin", self.pin, secret=True, timeout=8)
            require(not self.credential_none(), 'Private protection guard failed: not self.credential_none()')
            self.stage = "production-protection-flow"
            self.start_test()
            handled = set()
            while self.process.poll() is None and time.monotonic() < self.deadline - 75:
                output = "".join(self.stdout)
                for phase in self.scenario_methods[2]:
                    if phase not in handled and self.marker("AUTH_REQUIRED_" + phase) in output:
                        self.authenticate(phase); handled.add(phase)
                time.sleep(.2)
            require(self.process.poll() is not None, "Production fixture exceeded bounded runtime")
            for thread in self.threads:
                thread.join(timeout=2)
            output = "".join(self.stdout)
            require(self.process.returncode == 0 and "OK (1 test)" in output and "FAILURES!!!" not in output, 'Private protection guard failed: self.process.returncode == 0 and "OK (1 test)" in output and "FAILURES!!!" not in output')
            require(handled == set(self.scenario_methods[2]) and self.marker("PROTECTION_APP_PASS") in output,
                    "Every selected scenario auth phase and native PASS must be observed")
            completed = True
        except Exception as error:
            failure = dict(stage=self.stage, type=type(error).__name__, reason=str(error))
        finally:
            try:
                self.cleanup()
            except Exception as error:
                cleanup_failure = dict(stage=self.stage, type=type(error).__name__, reason=str(error))
            passed = completed and failure is None and cleanup_failure is None and self.cleaned and self.pin is None
            result = dict(status="PASS" if passed else "FAIL", package=PACKAGE, fixtureUuid=self.fixture, scenario=self.scenario,
                          expectedApkSha256=self.args.apk_sha256, expectedTestApkSha256=self.args.test_apk_sha256,
                          failure=failure, cleanupFailure=cleanup_failure,
                          aliasesAndFilesConfirmedAbsent=self.cleaned,
                          credentialRestoredNone=self.pin_attempted and self.pin is None)
            (self.out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result))
        return 0 if passed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("preflight", "run"), required=True)
    parser.add_argument("--serial", choices=tuple(LANES), default=SERIAL)
    parser.add_argument("--ticket", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--apk-sha256")
    parser.add_argument("--test-apk-sha256")
    parser.add_argument("--scenario", choices=tuple(SCENARIOS), default="legacy")
    parser.add_argument("--previous-ticket", help="Original fresh-UID ticket, only for reviewed installed-UID reuse")
    parser.add_argument("--previous-result", help="Previous successful host result including verified credential cleanup")
    parser.add_argument("--previous-receipt", help="Exact native cleanup receipt bytes copied by root")
    args = parser.parse_args()
    if args.phase == "run" and not re.fullmatch(r"[0-9a-f]{64}", args.apk_sha256 or ""):
        parser.error("run requires --apk-sha256 of the exact root-installed APK")
    if args.phase == "run" and not re.fullmatch(r"[0-9a-f]{64}", args.test_apk_sha256 or ""):
        parser.error("run requires --test-apk-sha256 of the exact root-installed fixture APK")
    if any((args.previous_ticket, args.previous_result, args.previous_receipt)):
        if args.phase != "preflight" or args.scenario != "empty-setup" or not all((args.previous_ticket, args.previous_result, args.previous_receipt)):
            parser.error("reuse requires empty-setup preflight and all three previous evidence files")
        if not re.fullmatch(r"[0-9a-f]{64}", args.test_apk_sha256 or ""):
            parser.error("reuse preflight requires the exact reviewed --test-apk-sha256")
    runner = Runner(args)
    return runner.preflight() if args.phase == "preflight" else runner.execute()


if __name__ == "__main__":
    raise SystemExit(main())
