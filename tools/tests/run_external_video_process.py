#!/usr/bin/env python3
"""One normal external-video process death; no instrumentation, installation or reset.

Requires the exact app/provider APKs and real-display observer already installed by
coordination. Reuses the existing creation observer without changing that harness.
Failures retain the exact owned provider document and evidence; never rerun a kill.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import uuid

PACKAGE = "com.librestatic.lightforge.pdfacceptance"
OWNER = "com.librestatic.lightforge.mediaprovider.fixture"
AUTHORITY = OWNER + ".documents"
SOURCE_SHA256 = "ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861"
SOURCE_SIZE = 145922
ASSIGNED_LANES = {
    "emulator-5554": ("Lightforge_M2_API30", "30"),
    "127.0.0.1:5563": ("Lightforge_PDF_API35", "35"),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def probe_type(h):
    class ExternalProbe(h.Probe):
        def __init__(self, args):
            super().__init__(args)
            self.name = "creation-process-" + self.fixture
            self.owner_uid = None
            self.prepared = None
            self.task_id = None
            self.editor_draft = None
            self.output_baseline = None
            self.failure = None

        def owner(self, action):
            require(action in {"prepare", "edit", "revoke", "inspect", "grant", "cleanup"}, "Unexpected owner action")
            self.guard_device()
            self.apk(OWNER, self.args.provider_apk_sha256)
            nonce = str(uuid.uuid4())
            expected = ["--es", "expectedSha256", SOURCE_SHA256] if action == "cleanup" else []
            self.shell("am", "start", "-f", "0x18000000", "-n", OWNER + "/.ControlActivity", "--es", "action", action,
                       "--es", "doc", self.fixture, "--es", "nonce", nonce, *expected)
            deadline = time.monotonic() + 15
            while True:
                raw, code, error = self.shell("run-as", OWNER, "cat", "files/control-" + nonce + ".json",
                                               check=False, include_stderr=True)
                if code == 0 and not error.strip():
                    try:
                        receipt = json.loads(raw)
                    except ValueError as failure:
                        raise RuntimeError("Owner receipt is corrupt; action is not repeated") from failure
                    break
                require(time.monotonic() < deadline, "Owner receipt unavailable; document retained, action not repeated")
                time.sleep(.1)
            require(isinstance(receipt, dict), "Owner receipt is not an object")
            for key, expected_value in {"action": action, "doc": self.fixture, "nonce": nonce,
                    "targetPackage": PACKAGE, "targetUid": self.manifest["uid"], "uid": self.owner_uid,
                    "uri": "content://" + AUTHORITY + "/document/" + self.fixture,
                    "sha256": SOURCE_SHA256, "size": SOURCE_SIZE, "deleted": action == "cleanup"}.items():
                require(receipt.get(key) == expected_value, "Owner receipt differs: " + key)
            require(type(receipt.get("pid")) is int and receipt["pid"] > 1, "Owner PID missing")
            (self.out / (action + "-" + nonce + ".json")).write_text(json.dumps(receipt, indent=2) + "\n")
            self.record(assertion="Exact UUID owner receipt", action=action, receipt=receipt)
            return receipt

        def launch_normal(self):
            # Launcher RESET_TASK_IF_NEEDED may put a new MAIN above a saved external EDIT.
            # Reorder the existing component, without changing its URI grant or resetting its task.
            self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-f", "0x30020000",
                       "-n", PACKAGE + "/com.librestatic.lightforge.MainActivity")

        def normal_process(self, expected_pid=None):
            dump = self.shell("dumpsys", "activity", "processes")[0]
            return h.uninstrumented_process(dump, expected_pid, self.manifest["uid"])

        def task_ids(self, resumed=False):
            dump = self.shell("dumpsys", "activity", "activities", PACKAGE)[0]
            require(re.search(r"(?m)^ACTIVITY MANAGER ACTIVITIES \(dumpsys activity activities\)\s*$", dump),
                    "Activity history dump is unrecognized; task absence is unverified")
            prefix = (r"^\s*(?:mResumedActivity|ResumedActivity):\s*ActivityRecord\{" if resumed
                      else r"^\s*\*?\s*Hist\s+#\d+:\s*ActivityRecord\{")
            component = re.escape(PACKAGE + "/com.librestatic.lightforge.MainActivity")
            target = r"\bu0\s+" + component + r"(?=\s)"
            tasks = set()
            for line in dump.splitlines():
                if not re.search(prefix, line) or not re.search(target, line):
                    continue
                task = re.search(target + r"\s+t([1-9][0-9]*)\b", line)
                require(task is not None, "Owned active ActivityRecord has no valid task ID")
                tasks.add(int(task.group(1)))
            return tasks

        def unchanged_outputs(self, label):
            current = self.creation_outputs(h.VIDEO_EDITOR_OUTPUT_PATH)
            require(current == self.output_baseline, "App video output inventory changed without requested export")
            self.record(assertion="No new app-owned video output or pending row", phase=label,
                        before=self.output_baseline, after=current)

        def editor_values(self):
            return self.video_editor_values()

        def gate(self, label):
            self.find("resource-id", "external-video-access-blocked", timeout=30, scroll=False)
            deadline = time.monotonic() + 20
            while True:
                retry = self.find("resource-id", "external-video-access-retry", timeout=20, scroll=False)
                if retry.get("enabled") == "true":
                    break
                require(time.monotonic() < deadline, "Access check did not return to a usable gate")
                time.sleep(.15)
            require(not any(node.get("package") == PACKAGE and node.get("resource-id") == "video-editor-screen"
                            for node in self.ui_nodes), "Editor remounted without explicit successful access check")
            require(self.task_ids(resumed=True) == {self.task_id}, "Gate moved to another task")
            self.record(assertion="Source access gate retained; no editor automatically mounted", position=label)

        def stop_saved(self):
            pids = self.shell("pidof", PACKAGE)[0].split()
            require(len(pids) == 1, "Normal process ambiguous")
            self.old_pid = int(pids[0])
            self.process_identity(self.old_pid)
            self.normal_process(self.old_pid)
            tasks = self.task_ids(resumed=True)
            require(len(tasks) == 1, "Expected exactly one resumed target task")
            self.task_id = next(iter(tasks))
            self.shell("input", "keyevent", "KEYCODE_HOME")
            deadline = time.monotonic() + 15
            while True:
                dump = self.shell("dumpsys", "activity", "activities", PACKAGE)[0]
                saved, block = h.saved_stopped_activity(dump, self.task_id)
                if saved:
                    break
                require(time.monotonic() < deadline, "Task did not reach Saved STOPPED; no kill issued")
                time.sleep(.2)
            self.normal_process(self.old_pid)
            self.record(assertion="Normal process Saved STOPPED before single kill", pid=self.old_pid,
                        taskId=self.task_id, activityRecord=block, draft=self.editor_draft)

        def run(self):
            try:
                self.guard_device()
                require(self.guard_observer() is not None, "Exact real-display observer is required")
                self.apk(PACKAGE, self.args.apk_sha256)
                self.apk(OWNER, self.args.provider_apk_sha256)
                uid = int(self.shell("run-as", PACKAGE, "id", "-u")[0].strip())
                self.owner_uid = int(self.shell("run-as", OWNER, "id", "-u")[0].strip())
                require(uid >= 10000 and self.owner_uid >= 10000 and uid != self.owner_uid, "Independent installed UIDs required")
                self.manifest = {"uid": uid}
                self.normal_process()
                processes = self.shell("ps", "-A", "-o", "ARGS")[0]
                require(not any("com.librestatic.lightforge.tools.RealDisplayDump" in line or "uiautomator" in line
                                for line in processes.splitlines()), "Another accessibility observer is active")
                # Compatibility gate for the imported observer: no native seed is used here.
                # Set only after positively recognizing absence of target instrumentation.
                self.native_completed = True
                self.accessibility_owner = "host"
                self.record(assertion="Host-only protocol; no instrumentation started", uid=uid, providerUid=self.owner_uid,
                            helperSha256=hashlib.sha256(Path(self.args.harness).read_bytes()).hexdigest())
                self.output_baseline = self.creation_outputs(h.VIDEO_EDITOR_OUTPUT_PATH)
                self.phase = "owner-prepare-and-normal-edit"
                self.prepared = self.owner("prepare")
                self.owner("edit")
                self.find("resource-id", "video-editor-screen", timeout=45, scroll=False)
                self.find("content-desc", self.args.play_label, timeout=30, scroll=False)
                _, (initial_start, initial_end) = h.editor_timecodes(
                    self.find("resource-id", "video-editor-trim-value", scroll=False), "video-editor-trim-value")
                require(initial_start == 0 and 2500 <= initial_end <= 3500, "Fresh fixture did not open with full three-second trim")
                self.manifest["durationMillis"] = initial_end
                self.adjust_video_editor_trim()
                self.tap(self.find("resource-id", "video-editor-position", scroll=False))
                self.editor_draft = self.editor_values()
                self.find("content-desc", self.args.play_label, scroll=False)
                self.unchanged_outputs("edited-before-home")
                self.phase = "saved-stop-single-background-kill"
                self.stop_saved()
                self.kill_owned(self.old_pid)  # API30: exactly one am kill --user 0, no force-stop.
                self.death_confirmed = True
                require(self.task_id in self.task_ids(), "Killed app lost the saved task")
                self.phase = "owner-revoke-after-confirmed-death"
                self.owner("revoke")
                self.owner("inspect")
                require(not self.proc_exists(self.old_pid), "Original PID unexpectedly returned")
                require(self.task_id in self.task_ids(), "Provider action removed original task")
                self.phase = "restore-same-task-with-denied-grant"
                self.launch_normal()
                self.gate("restored-before-check")
                pids = self.shell("pidof", PACKAGE)[0].split()
                require(len(pids) == 1, "Restored process ambiguous")
                self.new_pid = int(pids[0])
                require(self.new_pid != self.old_pid, "Activity recreation is not process death")
                self.process_identity(self.new_pid)
                self.normal_process(self.new_pid)
                self.click("external-video-access-retry")
                self.gate("explicit-check-still-denied")
                self.unchanged_outputs("denied-check")
                self.phase = "owner-regrant-no-automatic-unlock"
                self.owner("grant")
                self.launch_normal()
                self.gate("regrant-before-explicit-check")
                self.normal_process(self.new_pid)
                self.phase = "explicit-check-restores-exact-draft-paused"
                self.click("external-video-access-retry")
                self.find("resource-id", "video-editor-screen", timeout=30, scroll=False)
                require(self.editor_values() == self.editor_draft, "Restored trim/position differs from pre-death draft")
                self.find("content-desc", self.args.play_label, timeout=30, scroll=False)
                require(self.editor_values() == self.editor_draft, "Paused restored draft changed position")
                require(self.task_ids(resumed=True) == {self.task_id}, "Successful check replaced the original task")
                self.process_identity(self.new_pid)
                self.unchanged_outputs("recovered-paused")
                self.ui_complete = True
                self.phase = "explicit-discard-before-owned-provider-cleanup"
                self.shell("input", "keyevent", "KEYCODE_BACK")
                self.find("text", self.args.discard_title, scroll=False)
                self.tap(self.find("text", self.args.discard_confirm, scroll=False))
                deadline = time.monotonic() + 15
                while self.task_id in self.task_ids():
                    require(time.monotonic() < deadline, "Explicit discard did not finish external task; source retained")
                    time.sleep(.2)
                self.unchanged_outputs("after-explicit-discard")
                self.owner("inspect")
                self.owner("cleanup")  # Owner verifies expected SHA, exact UUID, deletion and directory fsync.
                self.cleanup_complete = True
            except Exception as failure:
                self.failure = str(failure)
                self.record(result="FAIL; do not repeat fixture/kill automatically", phase=self.phase, error=self.failure,
                            retainedDocument=self.fixture, uri="content://" + AUTHORITY + "/document/" + self.fixture)
                try:
                    image = self.call("exec-out", "screencap", "-p", binary=True)[0]
                    require(image.startswith(b"\x89PNG\r\n\x1a\n"), "Screenshot is not PNG")
                    (self.out / "failure.png").write_bytes(image)
                except Exception as screenshot_failure:
                    self.record(result="Failure screenshot unavailable", error=str(screenshot_failure))
            finally:
                summary = dict(state="PASS" if self.failure is None and self.cleanup_complete else "FAIL",
                    fixtureUuid=self.fixture, package=PACKAGE, oldPid=self.old_pid, newPid=self.new_pid,
                    sameTaskId=self.task_id, deathConfirmed=self.death_confirmed, draft=self.editor_draft,
                    uiComplete=self.ui_complete, cleanupComplete=self.cleanup_complete, error=self.failure,
                    sourceSha256=SOURCE_SHA256, processDeath=self.death_confirmed, instrumentationStarted=False,
                    historyOrTabVerified=False, permissionDenialExceptionVerified=False)
                (self.out / "result.json").write_text(json.dumps(summary, indent=2) + "\n")
                print(json.dumps(summary, indent=2))
            return 0 if self.failure is None and self.cleanup_complete else 1
    return ExternalProbe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", choices=tuple(ASSIGNED_LANES), required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--provider-apk-sha256", required=True)
    parser.add_argument("--observer-sha256", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--play-label", required=True, help="Current localized editor Play accessibility label")
    parser.add_argument("--discard-title", required=True, help="Current localized unsaved-edits dialog title")
    parser.add_argument("--discard-confirm", required=True, help="Current localized explicit Discard action")
    parser.add_argument("--harness", type=Path, default=Path(__file__).with_name("run_creation_process_restoration.py"))
    args = parser.parse_args()
    for digest in (args.apk_sha256, args.provider_apk_sha256, args.observer_sha256):
        require(re.fullmatch(r"[0-9a-f]{64}", digest), "Exact SHA256 values are required")
    for label in (args.play_label, args.discard_title, args.discard_confirm):
        require(label.strip(), "Localized control labels must not be empty")
    require(args.harness.name == "run_creation_process_restoration.py" and args.harness.is_file(), "Existing observer harness required")
    spec = importlib.util.spec_from_file_location("external_video_creation_observer", args.harness)
    require(spec is not None and spec.loader is not None, "Observer helper cannot be loaded")
    helper = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = helper
    spec.loader.exec_module(helper)
    require(helper.PACKAGE == PACKAGE and helper.DEVICES.get(args.serial) == ASSIGNED_LANES[args.serial], "Helper target contract differs")
    args.scenario = "video-editor-draft"
    return probe_type(helper)(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
