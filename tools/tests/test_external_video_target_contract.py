#!/usr/bin/env python3
"""Real external-runner CLI/guard tests; fake imported helper, no adb or device calls.

Set EXTERNAL_VIDEO_RUNNER_SOURCE to run the same tests against pristine source.
"""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

PACKAGE = "com.ugallery.app.pdfacceptance"
LANES = {"emulator-5554": ("UGallery_M2_API30", "30"),
         "127.0.0.1:5563": ("UGallery_PDF_API35", "35")}


class ExternalVideoTargetContractTest(unittest.TestCase):
    def setUp(self):
        source = Path(os.environ.get("EXTERNAL_VIDEO_RUNNER_SOURCE",
                                     Path(__file__).with_name("run_external_video_process.py")))
        spec = importlib.util.spec_from_file_location("external_target_under_test", source)
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)
        self.temporary = tempfile.TemporaryDirectory(prefix="external-target-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.harness = self.root / "run_creation_process_restoration.py"
        self.import_marker = self.root / "helper-imported"
        self.write_helper()

    def write_helper(self, package=PACKAGE, lanes=None):
        self.harness.write_text("from pathlib import Path\n"
                                + "PACKAGE = " + repr(package) + "\n"
                                + "DEVICES = " + repr(LANES if lanes is None else lanes) + "\n"
                                + "Path(" + repr(str(self.import_marker)) + ").write_text('imported')\n")

    def invoke(self, serial, *, expected_error=None, digest="a" * 64):
        # A factory/constructor spy is the exact boundary between parsed metadata and Probe actions.
        probe = mock.Mock()
        probe.run.return_value = 19
        constructor = mock.Mock(return_value=probe)
        factory = mock.Mock(return_value=constructor)
        args = ["run_external_video_process.py", "--serial", serial,
                "--apk-sha256", digest, "--provider-apk-sha256", "b" * 64,
                "--observer-sha256", "c" * 64, "--evidence", str(self.root / "evidence"),
                "--play-label", "Play preview", "--discard-title", "Discard edits?",
                "--discard-confirm", "Discard", "--harness", str(self.harness)]
        # Even an accidental real Probe construction fails before reaching an executable command.
        with mock.patch.object(self.runner, "probe_type", factory), mock.patch.object(sys, "argv", args), \
                mock.patch.dict(sys.modules), contextlib.redirect_stderr(io.StringIO()):
            if expected_error:
                with self.assertRaises(expected_error):
                    self.runner.main()
                factory.assert_not_called()
                constructor.assert_not_called()
                probe.run.assert_not_called()
            else:
                self.assertEqual(19, self.runner.main())
                factory.assert_called_once()
                constructor.assert_called_once()
                probe.run.assert_called_once_with()
                helper = factory.call_args.args[0]
                parsed = constructor.call_args.args[0]
                self.assertEqual(PACKAGE, helper.PACKAGE)
                self.assertEqual(LANES[serial], helper.DEVICES[serial])
                self.assertEqual(serial, parsed.serial)
                self.assertEqual("video-editor-draft", parsed.scenario)
                self.assertEqual("a" * 64, parsed.apk_sha256)
                self.assertEqual("b" * 64, parsed.provider_apk_sha256)
                self.assertEqual("c" * 64, parsed.observer_sha256)
                self.assertEqual("Play preview", parsed.play_label)
        self.assertFalse((self.root / "evidence").exists(), "Metadata parsing created device/fixture evidence")

    def test_api30_exact_lane_admitted(self):
        self.invoke("emulator-5554")
        self.assertTrue(self.import_marker.exists())

    def test_api35_exact_lane_admitted(self):
        self.invoke("127.0.0.1:5563")
        self.assertTrue(self.import_marker.exists())

    def test_alias_and_other_serial_rejected_before_import_or_probe(self):
        for serial in ("localhost:5563", "emulator-5562", "emulator-5556", "127.0.0.1:5038", "unassigned-device"):
            with self.subTest(serial=serial):
                self.invoke(serial, expected_error=SystemExit)
                self.assertFalse(self.import_marker.exists())

    def test_helper_api30_name_mismatch_rejected_before_probe(self):
        self.write_helper(lanes={"emulator-5554": ("UGallery_PDF_API35", "30")})
        self.invoke("emulator-5554", expected_error=RuntimeError)
        self.assertTrue(self.import_marker.exists())

    def test_helper_api35_sdk_mismatch_rejected_before_probe(self):
        self.write_helper(lanes={"127.0.0.1:5563": ("UGallery_PDF_API35", "30")})
        self.invoke("127.0.0.1:5563", expected_error=RuntimeError)
        self.assertTrue(self.import_marker.exists())

    def test_helper_missing_lane_rejected_before_probe(self):
        self.write_helper(lanes={})
        self.invoke("emulator-5554", expected_error=RuntimeError)
        self.assertTrue(self.import_marker.exists())

    def test_helper_package_mismatch_rejected_before_probe(self):
        self.write_helper(package="com.ugallery.app.demo.pdfacceptance")
        self.invoke("emulator-5554", expected_error=RuntimeError)
        self.assertTrue(self.import_marker.exists())

    def task_ids(self, dump, resumed=False):
        # Equivalent baseline inherited helper semantics in this tiny no-device stand-in.
        class ExistingHelper:
            def task_ids(self, resumed=False):
                output = self.shell("dumpsys", "activity", "activities", PACKAGE)[0]
                activity = PACKAGE + "/com.ugallery.app.MainActivity"
                lines = [line for line in output.splitlines() if activity in line and
                         (not resumed or "ResumedActivity" in line)]
                import re
                return {int(match.group(1)) for line in lines for match in re.finditer(r"\bt(\d+)\b", line)}
        cls = self.runner.probe_type(SimpleNamespace(Probe=ExistingHelper))
        probe = object.__new__(cls)
        probe.shell = mock.Mock(return_value=(dump, 0))
        result = probe.task_ids(resumed=resumed)
        probe.shell.assert_called_once_with("dumpsys", "activity", "activities", PACKAGE)
        return result

    def test_historical_positive_reference_is_not_active_history(self):
        # Synthetic positive t1314 isolates the risk; the accepted API30 stale record was t-1.
        dump = ("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                "  mLastPausedActivity: ActivityRecord{7ab8494 u0 " + PACKAGE +
                "/com.ugallery.app.MainActivity t1314}\n")
        self.assertEqual(set(), self.task_ids(dump))
        self.assertEqual(set(), self.task_ids(dump, resumed=True))

    def test_real_api30_history_and_resumed_shapes_keep_exact_task(self):
        # Record shapes retained from api30-same-fixture-final/commands.json event16.
        dump = ("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                "    mResumedActivity: ActivityRecord{7ab8494 u0 " + PACKAGE + "/com.ugallery.app.MainActivity t1314}\n"
                "    mLastPausedActivity: ActivityRecord{fe3fd5e u0 " + PACKAGE + "/com.ugallery.app.MainActivity t-1 f}}\n"
                "      * Hist #0: ActivityRecord{7ab8494 u0 " + PACKAGE + "/com.ugallery.app.MainActivity t1314}\n"
                "  ResumedActivity: ActivityRecord{7ab8494 u0 " + PACKAGE + "/com.ugallery.app.MainActivity t1314}\n")
        self.assertEqual({1314}, self.task_ids(dump))
        self.assertEqual({1314}, self.task_ids(dump, resumed=True))

    def test_actual_api35_double_spaced_history_keeps_saved_task(self):
        # Actual post-death API35 event125: task and saved ActivityRecord remain present.
        dump = ("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                "    * Hist  #0: ActivityRecord{27e2cf3 u0 " + PACKAGE + "/com.ugallery.app.MainActivity t677}\n")
        self.assertEqual({677}, self.task_ids(dump))

    def test_unknown_dump_or_malformed_owned_history_never_proves_absence(self):
        for dump in ("Unknown command: activities\n", "", "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                     " * Hist #0: ActivityRecord{abc u0 " + PACKAGE + "/com.ugallery.app.MainActivity tUNKNOWN}\n"):
            with self.subTest(dump=dump), self.assertRaises(RuntimeError):
                self.task_ids(dump)

    def test_malformed_digest_rejected_before_helper_import_or_probe(self):
        self.invoke("emulator-5554", expected_error=RuntimeError, digest="not-an-apk-sha")
        self.assertFalse(self.import_marker.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
