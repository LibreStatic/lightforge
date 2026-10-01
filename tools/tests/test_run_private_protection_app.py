"""Host-only regression; all device operations are mocks, including optimized Python."""
import ast
import importlib.util
from importlib.machinery import SourceFileLoader
import os
import json
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, Mock, patch
import io

PATH = Path(os.environ.get("PRIVATE_PROTECTION_RUNNER", str(Path(__file__).with_name("run_private_protection_app.py"))))
spec = importlib.util.spec_from_file_location("private_protection_runner", PATH, loader=SourceFileLoader("private_protection_runner", str(PATH)))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrivateProtectionHostGuardTest(unittest.TestCase):
    def runner(self):
        runner = module.Runner.__new__(module.Runner)
        runner.args = SimpleNamespace(serial=module.SERIAL)
        runner.bootstrap_started = True
        runner.process = None
        runner.pin_attempted = True
        runner.pin = "fixture-pin-not-a-device-credential"
        runner.fixture = "f5f36338-9cb4-4a91-ae0a-3051433c4e2e"
        runner.cleaned = False
        runner.out = MagicMock()
        runner.dump_path = "/fixture-only/no-device"
        runner.shell = Mock(return_value=("", 0))
        runner.instrument = Mock(return_value=("FAILURES!!!", False))
        runner.credential_none = Mock(return_value=True)
        return runner

    def test_operational_checks_are_not_optimizable_asserts(self):
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(ast.parse(PATH.read_text()))))

    def test_wrong_avd_or_api_or_serial_rejected_before_mutation(self):
        for property_values in (("Wrong_AVD", "30", "1"), (module.AVD, "35", "1"), (module.AVD, "30", "0")):
            with self.subTest(properties=property_values):
                runner = self.runner()
                runner.shell.side_effect = [(value, 0) for value in property_values]
                with self.assertRaises((RuntimeError, AssertionError)):
                    runner.guard()
                self.assertTrue(all(call.args[0] == "getprop" for call in runner.shell.call_args_list))
        runner = self.runner()
        runner.args.serial = "not-assigned"
        with self.assertRaises((RuntimeError, AssertionError)):
            runner.guard()
        runner.shell.assert_not_called()

    def test_failed_cleanup_preserves_pin_and_never_clears_credential(self):
        for output, passed in (("FAILURES!!!", False), ("OK (1 test)", True),
                               ("AUTH_FIXTURE f5f36338-9cb4-4a91-ae0a-3051433c4e2e CLEANUP_CONFIRMED", False)):
            with self.subTest(output=output, passed=passed):
                runner = self.runner()
                original_pin = runner.pin
                runner.instrument.return_value = output, passed
                with self.assertRaises((RuntimeError, AssertionError)):
                    runner.cleanup()
                self.assertFalse(runner.cleaned)
                self.assertEqual(original_pin, runner.pin)
                runner.credential_none.assert_not_called()
                self.assertEqual([("am", "force-stop", module.PACKAGE)], [call.args for call in runner.shell.call_args_list])

    def test_bootstrap_retry_rejected_by_owned_receipt_never_changes_pin(self):
        # Instrumentation results are mocked: Kotlin rejects the old completed receipt.
        # This checks host handling only, not execution of the Android ownership guard.
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner()
            runner.pin = None
            runner.pin_attempted = False
            runner.bootstrap_started = False
            runner.guard = Mock()
            runner.out = Path(directory)
            runner.stage = "run"
            runner.args.ticket = str(Path(directory) / "ticket.json")
            runner.args.apk_sha256 = "a" * 64
            runner.args.test_apk_sha256 = "b" * 64
            Path(runner.args.ticket).write_text(json.dumps(dict(
                version=1, package=module.PACKAGE, serial=module.SERIAL, avd=module.AVD,
                api=30, packageInitiallyAbsent=True, credentialInitiallyNone=True,
                createdAt=time.time(), fixtureUuid=runner.fixture,
            )))
            def shell(*args, **kwargs):
                if args[:2] == ("pm", "path"):
                    return "package:/fixture/" + args[2] + ".apk", 0
                if args[0] == "sha256sum":
                    return (runner.args.test_apk_sha256 if module.TEST_PACKAGE in args[1]
                            else runner.args.apk_sha256) + "  " + args[1], 0
                if args[:2] == ("dumpsys", "package"):
                    return "Package [" + module.PACKAGE + "] flags=[ DEBUGGABLE ]", 0
                if args[:3] == ("pm", "list", "instrumentation"):
                    return module.COMPONENT + " (target=" + module.PACKAGE + ")", 0
                if args == ("am", "force-stop", module.PACKAGE):
                    return "", 0
                self.fail("Unexpected shell mutation: " + repr(args))
            runner.shell.side_effect = shell
            runner.instrument.side_effect = [("FAILURES!!! Existing vault", False),
                                             ("FAILURES!!! Completed receipt", False)]
            self.assertEqual(1, runner.execute())
            self.assertFalse(runner.pin_attempted)
            self.assertIsNone(runner.pin)
            self.assertFalse(runner.cleaned)
            self.assertEqual(["bootstrapOwnedLegacyVault", "cleanupOwnedFixture"],
                             [call.args[0] for call in runner.instrument.call_args_list])
            result = json.loads((runner.out / "result.json").read_text())
            self.assertEqual("cleanup-exact-owned-aliases-and-files", result["cleanupFailure"]["stage"])
            self.assertFalse(result["credentialRestoredNone"])

    def test_auth_uses_native_field_marker_not_second_accessibility_client(self):
        runner = self.runner()
        runner.deadline = time.monotonic() + 100
        runner.events = []
        runner.save = Mock()
        runner.process = Mock()
        runner.process.poll.return_value = None
        runner.stdout = [runner.marker("AUTH_INPUT_OPEN") + " 400 700\n"]
        def shell(*args, **kwargs):
            if args == ("input", "keyevent", "KEYCODE_ENTER"):
                runner.stdout.append(runner.marker("AUTH_SUCCEEDED_OPEN"))
            return "", 0
        runner.shell.side_effect = shell
        runner.authenticate("OPEN")
        self.assertEqual([("input", "tap", 400, 700), ("input", "text", runner.pin),
                          ("input", "keyevent", "KEYCODE_ENTER")],
                         [call.args for call in runner.shell.call_args_list])
        self.assertEqual("OPEN", runner.events[-1]["phase"])

    def test_auth_rejects_success_without_actual_input(self):
        runner = self.runner()
        runner.deadline = time.monotonic() + 100
        runner.stdout = [runner.marker("AUTH_SUCCEEDED_OPEN")]
        with self.assertRaisesRegex(RuntimeError, "without actual credential"):
            runner.authenticate("OPEN")
        runner.shell.assert_not_called()

    def test_unrecognized_credential_state_rejected(self):
        runner = self.runner()
        del runner.credential_none
        runner.shell.return_value = "unrecognized output", 0
        with self.assertRaises((RuntimeError, AssertionError)):
            runner.credential_none()

    def previous_fixture(self, directory):
        runner = self.runner()
        runner.args.scenario = "empty-setup"
        runner.args.test_apk_sha256 = "b" * 64
        runner.args.previous_ticket = str(Path(directory) / "previous-ticket.json")
        runner.args.previous_result = str(Path(directory) / "previous-result.json")
        runner.args.previous_receipt = str(Path(directory) / "previous-receipt.json")
        ticket = dict(version=1, package=module.PACKAGE, serial=module.SERIAL, avd=module.AVD,
                      api=30, fixtureUuid=runner.fixture, packageInitiallyAbsent=True, credentialInitiallyNone=True)
        result = dict(status="PASS", package=module.PACKAGE, fixtureUuid=runner.fixture,
                      expectedApkSha256="a" * 64, aliasesAndFilesConfirmedAbsent=True, credentialRestoredNone=True)
        receipt = dict(uuid=runner.fixture, package=module.PACKAGE, verified=True, cleanupConfirmed=True)
        Path(runner.args.previous_ticket).write_text(json.dumps(ticket))
        Path(runner.args.previous_result).write_text(json.dumps(result))
        Path(runner.args.previous_receipt).write_text(json.dumps(receipt))
        runner.read_device_receipt = Mock(return_value=json.dumps(receipt))
        runner.installed_hash = Mock(side_effect=lambda package: "a" * 64 if package == module.PACKAGE else "b" * 64)
        runner.install_identity = Mock(return_value=dict(uid=10123, firstInstallTime="2026-09-06 00:00:00"))
        runner.instrument = Mock(return_value=(runner.marker("EMPTY_ADMISSION_CONFIRMED"), True))
        return runner

    def test_empty_scenario_routes_native_methods_and_exact_auth_protocol(self):
        runner = self.runner()
        self.assertEqual(("OPEN", "MIGRATION", "REOPEN"), runner.scenario_methods[2])
        runner.args.scenario = "empty-setup"
        self.assertEqual(("prepareOwnedEmptyFixture", "emptyVaultConfiguresImportsAndReopens",
                          ("OPEN", "SETUP", "REOPEN")), runner.scenario_methods)
        del runner.instrument
        runner.shell.return_value = "OK (1 test)", 0
        self.assertTrue(runner.instrument(runner.scenario_methods[0])[1])
        command = runner.shell.call_args.args
        self.assertIn("empty-setup", command)
        self.assertIn(module.CLASS + "#prepareOwnedEmptyFixture", command)
        runner.stdout = []; runner.stderr = []; runner.threads = []
        process = Mock(stdout=io.StringIO(""), stderr=io.StringIO(""))
        with patch.object(module.subprocess, "Popen", return_value=process) as spawn:
            runner.start_test()
            for thread in runner.threads:
                thread.join(timeout=1)
            actual = spawn.call_args.args[0]
        self.assertIn(module.CLASS + "#emptyVaultConfiguresImportsAndReopens", actual)
        self.assertIn("empty-setup", actual)
        runner.args.scenario = "unknown"
        with self.assertRaisesRegex(RuntimeError, "Unknown"):
            _ = runner.scenario_methods

    def test_installed_uid_admission_requires_exact_previous_evidence_and_native_absence(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.previous_fixture(directory)
            admission = runner.admit_previous_clean_install()
            self.assertEqual(runner.fixture, admission["previousFixtureUuid"])
            self.assertEqual("a" * 64, admission["acceptedApkSha256"])
            self.assertEqual(runner.install_identity.return_value, admission["installation"])
            runner.instrument.assert_called_once_with("verifyOwnedEmptyAdmission")
            runner.shell.assert_not_called()
            self.assertEqual("fixture-pin-not-a-device-credential", runner.pin)

    def test_reuse_rejects_wrong_lineage_or_unconfirmed_cleanup_without_mutation(self):
        variants = ("missing-evidence", "not-original-fresh", "wrong-ticket-uuid", "failed-host",
                    "host-cleanup-false", "credential-not-restored", "native-unverified", "native-cleanup-false",
                    "different-native-bytes", "wrong-main-apk", "wrong-test-apk", "dirty-native-state")
        for variant in variants:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as directory:
                runner = self.previous_fixture(directory)
                if variant == "missing-evidence":
                    runner.args.previous_receipt = None
                elif variant in ("not-original-fresh", "wrong-ticket-uuid"):
                    path = Path(runner.args.previous_ticket); record = json.loads(path.read_text())
                    record["packageInitiallyAbsent" if variant == "not-original-fresh" else "fixtureUuid"] = False if variant == "not-original-fresh" else "another-uuid"
                    path.write_text(json.dumps(record))
                elif variant in ("failed-host", "host-cleanup-false", "credential-not-restored"):
                    path = Path(runner.args.previous_result); record = json.loads(path.read_text())
                    field = {"failed-host": "status", "host-cleanup-false": "aliasesAndFilesConfirmedAbsent",
                             "credential-not-restored": "credentialRestoredNone"}[variant]
                    record[field] = "FAIL" if field == "status" else False
                    path.write_text(json.dumps(record))
                elif variant in ("native-unverified", "native-cleanup-false"):
                    path = Path(runner.args.previous_receipt); record = json.loads(path.read_text())
                    record["verified" if variant == "native-unverified" else "cleanupConfirmed"] = False
                    path.write_text(json.dumps(record)); runner.read_device_receipt.return_value = json.dumps(record)
                elif variant == "different-native-bytes":
                    runner.read_device_receipt.return_value += "\n"
                elif variant in ("wrong-main-apk", "wrong-test-apk"):
                    runner.installed_hash.side_effect = lambda package: "c" * 64 if package == (module.PACKAGE if variant == "wrong-main-apk" else module.TEST_PACKAGE) else ("a" * 64 if package == module.PACKAGE else "b" * 64)
                elif variant == "dirty-native-state":
                    runner.instrument.return_value = "OK (1 test)", True
                with self.assertRaises(RuntimeError):
                    runner.admit_previous_clean_install()
                runner.shell.assert_not_called()
                self.assertEqual("fixture-pin-not-a-device-credential", runner.pin)

    def test_reuse_run_allows_hash_upgrade_but_not_uid_or_install_time_change(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.previous_fixture(directory)
            admission = runner.admit_previous_clean_install()
            new_fixture = "d28c6023-17cd-4f48-935f-dfa23f1bb5ac"
            runner.fixture = new_fixture
            # Root may update the exact reviewed main APK in-place; installation identity stays.
            runner.installed_hash.side_effect = lambda package: "c" * 64
            runner.verify_reuse_admission(admission)
            self.assertEqual(new_fixture, runner.fixture)
            for changed in (dict(uid=20234, firstInstallTime=admission["installation"]["firstInstallTime"]),
                            dict(uid=10123, firstInstallTime="2026-09-07 00:00:00")):
                runner.install_identity.return_value = changed
                with self.assertRaisesRegex(RuntimeError, "identity changed"):
                    runner.verify_reuse_admission(admission)
                self.assertEqual(new_fixture, runner.fixture)
            runner.shell.assert_not_called()

    def test_reuse_run_rechecks_receipt_and_native_absence_before_bootstrap(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.previous_fixture(directory)
            admission = runner.admit_previous_clean_install()
            raw = runner.read_device_receipt.return_value
            runner.read_device_receipt.return_value += " "
            with self.assertRaisesRegex(RuntimeError, "receipt changed"):
                runner.verify_reuse_admission(admission)
            runner.read_device_receipt.return_value = raw
            runner.instrument.return_value = "FAILURES!!! private files exist", False
            current = runner.fixture
            with self.assertRaisesRegex(RuntimeError, "Private state appeared"):
                runner.verify_reuse_admission(admission)
            self.assertEqual(current, runner.fixture)
            runner.shell.assert_not_called()

    def test_preflight_rejects_existing_unrelated_install_before_ticket_or_bootstrap(self):
        runner = self.runner()
        runner.args.scenario = "empty-setup"
        runner.guard = Mock()
        runner.shell.return_value = "package:" + module.PACKAGE + "\n", 0
        with self.assertRaisesRegex(RuntimeError, "Exact previous"):
            runner.preflight()
        self.assertEqual([("pm", "list", "packages", "--user", "0", module.PACKAGE)],
                         [call.args for call in runner.shell.call_args_list])
        runner.instrument.assert_not_called()

    def test_setup_auth_uses_same_native_handshake_without_legacy_migration_phase(self):
        runner = self.runner()
        runner.args.scenario = "empty-setup"
        runner.deadline = time.monotonic() + 100
        runner.events = []; runner.save = Mock(); runner.process = Mock()
        runner.process.poll.return_value = None
        runner.stdout = [runner.marker("AUTH_INPUT_SETUP") + " 300 600\n"]
        def shell(*args, **kwargs):
            if args == ("input", "keyevent", "KEYCODE_ENTER"):
                runner.stdout.append(runner.marker("AUTH_SUCCEEDED_SETUP"))
            return "", 0
        runner.shell.side_effect = shell
        runner.authenticate("SETUP")
        self.assertEqual("SETUP", runner.events[-1]["phase"])
        self.assertEqual(3, runner.shell.call_count)
        self.assertNotIn("MIGRATION", runner.scenario_methods[2])

    def test_install_identity_uses_uid_and_original_install_timestamp(self):
        runner = self.runner()
        runner.shell.return_value = "Package [fixture]\n  userId=10123\n  firstInstallTime=2026-09-06 12:34:56\n  lastUpdateTime=2026-09-07 13:00:00\n", 0
        self.assertEqual(dict(uid=10123, firstInstallTime="2026-09-06 12:34:56"), runner.install_identity())
        for raw in ("userId=10123", "firstInstallTime=2026-09-06 12:34:56", "unrecognized identity"):
            runner.shell.return_value = raw, 0
            with self.assertRaisesRegex(RuntimeError, "Unrecognized acceptance"):
                runner.install_identity()

    def test_cleanup_marker_without_matching_native_receipt_preserves_credential(self):
        runner = self.runner()
        runner.instrument.return_value = runner.marker("CLEANUP_CONFIRMED"), True
        runner.read_device_receipt = Mock(return_value=json.dumps(dict(uuid=runner.fixture, package=module.PACKAGE, cleanupConfirmed=False)))
        with self.assertRaisesRegex(RuntimeError, "Cleanup receipt"):
            runner.cleanup()
        self.assertEqual("fixture-pin-not-a-device-credential", runner.pin)
        self.assertFalse(any(call.args[0] == "locksettings" for call in runner.shell.call_args_list))
        runner.credential_none.assert_not_called()

    def test_native_source_hash_or_cas_failure_never_clears_temporary_credential(self):
        failures = ("Missing legacy source", "Changed legacy source", "Changed public generation",
                    "Public source CAS deletion failed", "Public source absence unconfirmed")
        for message in failures:
            with self.subTest(message=message):
                runner = self.runner()
                runner.instrument.return_value = "FAILURES!!! " + message, False
                with self.assertRaisesRegex(RuntimeError, "Cleanup unconfirmed"):
                    runner.cleanup()
                self.assertFalse(runner.cleaned)
                self.assertEqual("fixture-pin-not-a-device-credential", runner.pin)
                runner.credential_none.assert_not_called()
                self.assertFalse(any(call.args[0] == "locksettings" for call in runner.shell.call_args_list))

    def test_native_cleanup_source_contract_is_ordered_before_key_retirement(self):
        # Source regression only, not a substitute for coordinator-run Android CAS acceptance.
        root = Path(os.environ.get("PRIVATE_PROTECTION_SOURCE_ROOT", PATH.resolve().parents[2]))
        native = (root / "app/src/androidTest/kotlin/com/librestatic/lightforge/PrivateProtectionAppDeviceTest.kt").read_text()
        prepare = native[native.index("@Test fun prepareOwnedEmptyFixture()"):native.index("private data class PublicSourceSnapshot")]
        self.assertLess(prepare.index("put(MediaStore.MediaColumns.IS_PENDING, 0)"),
                        prepare.index('source.put("generationAdded", published.generationAdded)'))
        cleanup = native[native.index("@Test fun cleanupOwnedFixture()"):]
        self.assertLess(cleanup.index("requireLegacySourceHashes(expectedLegacySources"), cleanup.index("confirmPublicDeletion(publicSource"))
        self.assertLess(cleanup.index("confirmPublicDeletion(publicSource"), cleanup.index("check(validatedPublicSource(ownership) == null)"))
        self.assertLess(cleanup.index("check(validatedPublicSource(ownership) == null)"), cleanup.index("keys.deleteEntry(it)"))
        delete = native[native.index("private fun deletePublicSourceCas("):native.index("/** No resolver/Keystore calls:")]
        for column in ("_ID", "OWNER_PACKAGE_NAME", "DISPLAY_NAME", "RELATIVE_PATH", "GENERATION_ADDED", "GENERATION_MODIFIED", "IS_PENDING"):
            self.assertIn("MediaStore.MediaColumns." + column, delete)
        self.assertNotIn("delete(publicSource, null, null)", cleanup)



class PrivateProtectionLaneTest(unittest.TestCase):
    def test_exact_lanes_and_property_selection(self):
        for serial, avd, api, prop in (("emulator-5554", "Lightforge_M2_API30", 30, "ro.kernel.qemu.avd_name"),
                                      ("127.0.0.1:5563", "Lightforge_PDF_API35", 35, "ro.boot.qemu.avd_name")):
            with self.subTest(serial=serial):
                runner = module.Runner.__new__(module.Runner)
                runner.args = SimpleNamespace(serial=serial)
                runner.shell = Mock(side_effect=[(avd+"\n", 0), (str(api)+"\n", 0), ("1\n", 0)])
                runner.guard()
                self.assertEqual(runner.shell.call_args_list[0].args, ("getprop", prop))
                self.assertTrue(module.ticket_matches_lane(dict(serial=serial,avd=avd,api=api),serial))
    def test_reject_alias_before_directory_or_commands(self):
        for serial in ("localhost:5563", "emulator-5562", "127.0.0.1:5038", "unknown", "emulator-5554 "):
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as directory:
                evidence = Path(directory)/"must-not-exist"
                with self.assertRaises(RuntimeError):
                    module.Runner(SimpleNamespace(serial=serial,evidence=str(evidence)))
                self.assertFalse(evidence.exists())
    def test_mismatch_and_cross_lane_ticket_rejected(self):
        for avd,api,qemu in (("Lightforge_M2_API30","35","1"),("Lightforge_PDF_API35","30","1"),("Lightforge_PDF_API35","35","0")):
            with self.assertRaises(RuntimeError):
                module.validate_target("127.0.0.1:5563",avd,api,qemu)
        old=dict(serial="emulator-5554",avd="Lightforge_M2_API30",api=30)
        self.assertFalse(module.ticket_matches_lane(old,"127.0.0.1:5563"))
        self.assertFalse(module.ticket_matches_lane(dict(serial="127.0.0.1:5563",avd="Lightforge_PDF_API35",api="35"),"127.0.0.1:5563"))
    def test_actual_credential_formats_and_fail_closed_boundaries(self):
        for kind in ("None", "NONE"):
            raw = "User State:\n  User 0\n    Quality: 0\n    CredentialType: "+kind+"\n  User 0 [/data/system_de/0/spblob]:\n"
            self.assertTrue(module.credential_none_from_dump(raw))
            self.assertFalse(module.credential_none_from_dump(raw.replace("Quality: 0", "Quality: 131072").replace(kind,"PIN")))
        for raw in ("Unknown command", "User State:\n  User 0\n    Quality: 0\n  User 10\n    CredentialType: NONE\n",
                    "User State:\n  User 0 [/data/path]:\n    Quality: 0\n    CredentialType: NONE\n",
                    "User State:\n  User 0\n    Quality: 0\nOther:\n    CredentialType: NONE\n"):
            with self.subTest(raw=raw), self.assertRaises(RuntimeError):
                module.credential_none_from_dump(raw)
    def test_api35_reuse_requires_same_lane_receipt_before_native_admission(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = PrivateProtectionHostGuardTest().previous_fixture(directory)
            runner.args.serial = "127.0.0.1:5563"
            with self.assertRaisesRegex(RuntimeError,"target/UUID differs"):
                runner.admit_previous_clean_install()
            runner.instrument.assert_not_called()
            ticket_path=Path(runner.args.previous_ticket)
            ticket=json.loads(ticket_path.read_text())
            ticket.update(serial=runner.args.serial,avd="Lightforge_PDF_API35",api=35)
            ticket_path.write_text(json.dumps(ticket))
            admitted=runner.admit_previous_clean_install()
            self.assertEqual(admitted["previousFixtureUuid"],runner.fixture)
            runner.instrument.assert_called_once_with("verifyOwnedEmptyAdmission")
            runner.args.scenario="legacy"
            with self.assertRaisesRegex(RuntimeError,"Reuse is only admitted"):
                runner.admit_previous_clean_install()

    def test_api35_start_test_uses_selected_lane_and_unchanged_native_handshake(self):
        runner = module.Runner.__new__(module.Runner)
        runner.args = SimpleNamespace(serial="127.0.0.1:5563",scenario="empty-setup")
        runner.fixture="00000000-0000-0000-0000-000000000001"
        runner.stdout=[]; runner.stderr=[]; runner.threads=[]
        with patch.object(module.subprocess,"Popen") as popen, patch.object(module.threading,"Thread"):
            runner.start_test()
        command=popen.call_args.args[0]
        self.assertEqual(command[2],"127.0.0.1:5563")
        self.assertIn(module.CLASS+"#emptyVaultConfiguresImportsAndReopens",command)
        self.assertEqual(command[-1],"com.librestatic.lightforge.demo.pdfacceptance.test/androidx.test.runner.AndroidJUnitRunner")

if __name__ == "__main__":
    unittest.main()
