import importlib.util
import json
import os
from pathlib import Path
import unittest

path = Path(os.environ.get("PRIVATE_INDEX_AUTH_RUNNER", Path(__file__).with_name("run_private_index_auth.py")))
spec = importlib.util.spec_from_file_location("index_auth_target", path)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
NONE = "User State:\n  User 0\n    Quality: 0\n    CredentialType: None\n"

class TargetContract(unittest.TestCase):
    def test_api30_exact(self):
        self.assertEqual(runner.validate_target("emulator-5554", "UGallery_M2_API30\n", "30\n", "1\n")[:2], ("UGallery_M2_API30", "30"))
    def test_api35_exact(self):
        self.assertEqual(runner.validate_target("127.0.0.1:5563", "UGallery_PDF_API35", "35", "1")[:2], ("UGallery_PDF_API35", "35"))
    def test_reject_unassigned_and_alias(self):
        for serial in ("localhost:5563", "emulator-5562", "127.0.0.1:5038", "SAMSUNG", "emulator-5554 "):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                runner.validate_target(serial, "UGallery_PDF_API35", "35", "1")
    def test_reject_tuple_mismatch(self):
        for avd, api, qemu in (("UGallery_M2_API30", "35", "1"), ("UGallery_PDF_API35", "30", "1"), ("UGallery_PDF_API35", "35", "0"), ("UGallery_PDF_API35X", "35", "1")):
            with self.subTest(identity=(avd,api,qemu)), self.assertRaises(ValueError):
                runner.validate_target("127.0.0.1:5563", avd, api, qemu)
    def test_none_is_exact(self):
        self.assertTrue(runner.credential_none_from_dump(NONE))
        self.assertFalse(runner.credential_none_from_dump(NONE.replace("Quality: 0", "Quality: 131072").replace("None", "PIN")))
    def test_unknown_fields_fail(self):
        for raw in ("", "Unknown command", NONE.replace("CredentialType:", "NewCredentialFormat:"), NONE+NONE):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                runner.credential_none_from_dump(raw)
    def test_other_user_none_never_authorizes(self):
        raw = "User State:\n  User 0\n    Quality: 131072\n  User 10\n    Quality: 0\n    CredentialType: None\n"
        with self.assertRaises(ValueError):
            runner.credential_none_from_dump(raw)
        self.assertFalse(runner.credential_none_from_dump(raw.replace("Quality: 131072", "Quality: 131072\n    CredentialType: PIN")))

    def test_observed_api35_uppercase_and_spblob_not_second_user_state(self):
        raw = NONE.replace("None", "NONE") + "  User 0 [/data/system_de/0/spblob]:\n    userId=0, primaryAuthFlags=0\n"
        self.assertTrue(runner.credential_none_from_dump(raw))
    def test_observed_api30_mixed_case_and_spblob(self):
        self.assertTrue(runner.credential_none_from_dump(NONE + "  User 0 [/data/system_de/0/spblob]:\n"))
    def test_spblob_or_outside_block_cannot_supply_missing_fields(self):
        for suffix in ("  User 0 [/data/system_de/0/spblob]:\n    CredentialType: NONE\n",
                       "Other section:\n  User 0\n    CredentialType: NONE\n"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                runner.credential_none_from_dump(NONE.replace("    CredentialType: None\n", "") + suffix)
    def test_duplicate_plain_user0_or_path_only_rejected(self):
        for raw in (NONE + "  User 0\n    Quality: 0\n    CredentialType: NONE\n",
                    NONE.replace("User 0\n", "User 0 [/data/system_de/0/spblob]:\n"),
                    NONE.replace("User State:", "Unknown state:")):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                runner.credential_none_from_dump(raw)

    def test_real_display_receipt_preserves_auth_control_attributes(self):
        path = "/sdcard/creation-process-private-auth-00000000-0000-0000-0000-000000000001-0.xml"
        receipt = dict(version=1, observer="builtin-real-display", path=path, appWidth=2560,
            appHeight=1504, realWidth=2560, realHeight=1600, rotation=0, rawRootBounds="0 0 2560 1600", nodes=1)
        xml = '<hierarchy><node package="com.ugallery.feature.privatealbum.test" resource-id="own-button" content-desc="private-auth-fixture-ready" enabled="true" bounds="[4,8][100,80]" /></hierarchy>'
        root, actual = runner.active_display_tree(json.dumps(receipt), xml, path)
        self.assertEqual(actual, receipt)
        node = list(root.iter("node"))[0]
        self.assertEqual(node.get("package"), runner.PACKAGE)
        self.assertEqual(node.get("content-desc"), "private-auth-fixture-ready")
        self.assertEqual(node.get("resource-id"), "own-button")
        self.assertEqual(runner.observer_remote_path(runner.OBSERVER_SHA256),
                         "/data/local/tmp/ugallery-ui-observer-" + runner.OBSERVER_SHA256 + ".jar")
    def test_real_display_rejects_stale_receipt_missing_xml_and_count(self):
        path = "/sdcard/creation-process-private-auth-00000000-0000-0000-0000-000000000001-0.xml"
        base = dict(version=1, observer="builtin-real-display", path=path, appWidth=100,
            appHeight=90, realWidth=100, realHeight=100, rotation=0, rawRootBounds="0 0 100 100", nodes=1)
        for change, xml in (({"path": path+"stale"}, '<hierarchy><node /></hierarchy>'),
                            ({"nodes": 2}, '<hierarchy><node /></hierarchy>'),
                            ({"extra": 1}, '<hierarchy><node /></hierarchy>'),
                            ({}, "cat: No such file or directory"), ({}, '<wrong><node /></wrong>')):
            with self.subTest(change=change,xml=xml), self.assertRaises((ValueError,RuntimeError)):
                runner.active_display_tree(json.dumps(dict(base, **change)), xml, path)

    def test_real_tree_uses_fresh_java_accepted_paths_without_host_retry(self):
        instance = object.__new__(runner.Runner)
        instance.id = "00000000-0000-0000-0000-000000000001"
        instance.dumps, instance.events = [], []
        instance.save = lambda: None
        commands = []
        xml = '<hierarchy><node package="com.android.settings" /></hierarchy>'
        def shell(*args, **kwargs):
            commands.append(args)
            if args[0] == "sha256sum":
                return runner.OBSERVER_SHA256 + "  " + args[1] + "\n", 0, ""
            self.assertEqual(args[:4], ("env", "CLASSPATH=/system/framework/uiautomator.jar:" + runner.observer_remote_path(runner.OBSERVER_SHA256), "app_process", "/system/bin"))
            self.assertEqual(args[4], "com.ugallery.tools.RealDisplayDump")
            self.assertRegex(args[5], r"^/(?:sdcard|data/local/tmp)/creation-process-[a-z0-9-]+\.xml$")
            self.assertEqual(kwargs["timeout"], 35)
            return json.dumps(dict(version=1,observer="builtin-real-display",path=args[5],appWidth=100,appHeight=90,realWidth=100,realHeight=100,rotation=0,rawRootBounds="0 0 100 100",nodes=1)), 0, ""
        def adb(*args, **kwargs):
            self.assertEqual(args, ("exec-out", "cat", instance.dumps[-1]))
            return xml, 0, ""
        instance.shell, instance.adb = shell, adb
        for _ in range(2):
            self.assertEqual(instance.tree().tag, "hierarchy")
        self.assertEqual(len(set(instance.dumps)), 2)
        self.assertEqual(len(commands), 4)

    def test_failed_observer_is_terminal_without_read_or_host_retry(self):
        instance = object.__new__(runner.Runner)
        instance.id = "00000000-0000-0000-0000-000000000001"
        instance.dumps = []
        instance.guard_observer = lambda: runner.observer_remote_path(runner.OBSERVER_SHA256)
        calls = []
        def shell(*args, **kwargs):
            calls.append(args)
            return "", 0, "ERROR: null active root"
        instance.shell = shell
        instance.adb = lambda *a, **k: self.fail("Failed observer XML must not be read")
        with self.assertRaises(RuntimeError):
            instance.tree()
        self.assertEqual(len(calls), 1)

if __name__ == "__main__":
    unittest.main()
