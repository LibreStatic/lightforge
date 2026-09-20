"""Tiny host contract tests; no ADB subprocess, emulator or Kotlin invocation."""
import importlib.util
from pathlib import Path
import tempfile
import io
import hashlib
from PIL import Image
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

PATH = Path(__file__).with_name("run_creation_process_restoration.py")
spec = importlib.util.spec_from_file_location("creation_process_runner", PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CreationProcessHostTest(unittest.TestCase):
    def row(self):
        return dict(_id=21, uri="content://media/external/images/media/21", ordinal=0,
                    _display_name="creation-process-fixture-0.png", relative_path="Pictures/creation-process-fixture/",
                    owner_package_name=module.PACKAGE, generation_added=5, generation_modified=8,
                    is_pending=0, _data="/storage/emulated/0/Pictures/creation-process-fixture/creation-process-fixture-0.png",
                    sha256="a" * 64)

    def probe(self):
        probe = module.Probe.__new__(module.Probe)
        probe.name = "creation-process-fixture"
        probe.args = SimpleNamespace(serial="127.0.0.1:5563")
        probe.shell = Mock()
        probe.call = Mock()
        probe.record = Mock()
        probe.guard_device = Mock()
        probe.death_confirmed = False
        probe.cleanup_complete = False
        probe.kill_requests = {}
        probe.native_completed = False
        probe.dumps = []
        return probe

    def manual_rows(self):
        return [self.row(), dict(self.row(), _id=22, ordinal=1, generation_added=6, generation_modified=9)]

    def manual_receipt(self, probe):
        return dict(fixtureUuid=probe.fixture, momentId="00000000-0000-4000-8000-000000000001",
                    requestFingerprint=module.manual_memory_fingerprint(self.manual_rows(), probe.manual_memory_title()),
                    orderedMediaIds=[22, 21], includeSpecialMedia=True, baselineManualMemoryAbsent=True,
                    manualMemoryRowsVerified=True, sourcesCurrent=True, manualMemoryCleaned=True, status="PASS")

    def manual_probe(self):
        probe = self.probe()
        probe.fixture = "00000000-0000-4000-8000-000000000002"
        probe.scenario = "manual-memory-draft"
        probe.manifest = dict(rows=self.manual_rows(), manualPhoto1Label="Photo 1 of 2", manualPhoto2Label="Photo 2 of 2",
                              selectionLabel="2 selected")
        def find(attr, tag, **kw):
            node = self.manual_node(probe, tag)
            if tag in ("moment-screen", "memories-browser-screen", "collections-all-memories"):
                probe.ui_nodes = [node]
                selection_present = (getattr(probe, "manual_moment_toolbar_present", False) if tag == "moment-screen" else
                    getattr(probe, "manual_result_selection_present", probe.scenario == "manual-memory-commit")
                    if tag == "collections-all-memories" else False)
                if selection_present:
                    probe.ui_nodes.append(module.ET.Element("node", {"package": module.PACKAGE,
                        "text": probe.manifest["selectionLabel"]}))
                if tag == "collections-all-memories" and getattr(probe, "manual_wrong_return_route", False):
                    probe.ui_nodes.append(module.ET.Element("node", {"package": module.PACKAGE,
                        "resource-id": "memories-browser-screen"}))
            return node
        probe.find = Mock(side_effect=find)
        probe.click = Mock()
        probe.tap = Mock()
        probe.ui_nodes = []
        probe.creation_outputs = Mock(return_value=())
        probe.source_hash = Mock(side_effect=lambda row: row["sha256"])
        probe.manual_output_baseline = {path: () for path in module.CREATION_OUTPUT_PATHS}
        return probe

    def manual_node(self, probe, tag):
        node = module.ET.Element("node", {"package": module.PACKAGE, "resource-id": tag,
             "text": probe.manual_memory_title() if tag == "manual-moment-title" else "",
             "checkable": "true", "checked": "true", "enabled": "true", "bounds": "[0,0][1080,2000]"})
        if tag.startswith("manual-moment-source-"):
            label = "Photo 1 of 2" if tag.endswith(":22") else "Photo 2 of 2"
            module.ET.SubElement(node, "node", {"package": module.PACKAGE, "text": label, "bounds": "[10,10][300,70]"})
        return node

    def test_manual_receipt_requires_exact_request_order_identity_and_completed_cleanup(self):
        probe = self.manual_probe()
        receipt = self.manual_receipt(probe)
        self.assertEqual(receipt, module.verify_manual_memory_receipt(module.json.dumps(receipt), probe.fixture,
                                                                      probe.manifest["rows"], probe.manual_memory_title()))
        for field, value in (("fixtureUuid", "other"), ("momentId", "not-uuid"), ("momentId", 7),
                             ("requestFingerprint", "manual-v1:"+"a"*64), ("orderedMediaIds", [21, 22]),
                             ("orderedMediaIds", [22.0, 21]), ("includeSpecialMedia", 1), ("baselineManualMemoryAbsent", False),
                             ("manualMemoryRowsVerified", False), ("manualMemoryCleaned", False), ("sourcesCurrent", False), ("status", "FAIL")):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_manual_memory_receipt(module.json.dumps(dict(receipt, **{field:value})), probe.fixture,
                                                    probe.manifest["rows"], probe.manual_memory_title())
        for output in ("", "[]", module.json.dumps(dict(receipt, extra=1))):
            with self.assertRaises(RuntimeError):
                module.verify_manual_memory_receipt(output, probe.fixture, probe.manifest["rows"], probe.manual_memory_title())

    def test_manual_partial_card_reacquires_its_own_visible_position_without_accepting_neighbor(self):
        # Derived from actual-api30/last-ui.xml: card1216 was clipped at1900 and exposed only preview.
        # Neighbor1217 already exposed Photo1; it must not satisfy the target card's position.
        package = module.PACKAGE
        partial = module.ET.fromstring(f'<node package="{package}" bounds="[0,0][1080,2340]">'
            f'<node package="{package}" resource-id="manual-moment-list" scrollable="true" bounds="[0,220][1080,1900]">'
            f'<node package="{package}" resource-id="manual-moment-source-external_primary:1217" bounds="[44,554][1036,1412]">'
            f'<node package="{package}" text="Photo 1 of 2" bounds="[77,1038][289,1093]" /></node>'
            f'<node package="{package}" resource-id="manual-moment-source-external_primary:1216" bounds="[44,1445][1036,1900]">'
            f'<node package="{package}" resource-id="manual-moment-preview-external_primary:1216" bounds="[77,1478][1003,1900]" />'
            '</node></node></node>')
        tag = "manual-moment-source-external_primary:1216"
        for label in ("Photo 2 of 2", "Photo 1 of 2"):
            with self.subTest(fresh_label=label):
                probe = self.manual_probe()
                full = module.ET.fromstring(module.ET.tostring(partial))
                card = list(full.iter("node"))[-2]
                card.set("bounds", "[44,500][1036,1360]")
                module.ET.SubElement(card, "node", {"package": package, "text": label, "bounds": "[77,1038][289,1093]"})
                frames = iter((partial, full))
                def find(attr, value, **kwargs):
                    root = next(frames)
                    probe.ui_nodes = list(root.iter("node"))
                    return next(node for node in probe.ui_nodes if node.get("resource-id") == value)
                probe.find.side_effect = find
                with patch.object(module.time, "sleep"):
                    if label == "Photo 2 of 2":
                        result = probe.manual_memory_card_position(tag, label)
                        self.assertIn(result, list(full.iter("node")))
                    else:
                        with self.assertRaisesRegex(RuntimeError, "order changed"):
                            probe.manual_memory_card_position(tag, "Photo 2 of 2")
                self.assertEqual(2, probe.find.call_count)
                probe.shell.assert_called_once_with("input", "swipe", 540, 1480, 540, 640, "250")

    def test_manual_absence_receipt_is_strict_and_never_substitutes_for_commit_receipt(self):
        receipt = dict(fixtureUuid="owned", sourcesCurrent=True, manualMemoryAbsent=True, status="PASS")
        self.assertEqual(receipt, module.verify_manual_memory_absence_receipt(module.json.dumps(receipt), "owned"))
        for field, value in (("fixtureUuid", "other"), ("sourcesCurrent", 1), ("manualMemoryAbsent", False), ("status", "FAIL")):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_manual_memory_absence_receipt(module.json.dumps(dict(receipt, **{field:value})), "owned")
        for output in ("", "[]", module.json.dumps(dict(receipt, manualMemoryRowsVerified=True))):
            with self.assertRaises(RuntimeError): module.verify_manual_memory_absence_receipt(output, "owned")

    def test_manual_absence_protocol_releases_cleanup_only_after_current_absence_receipt(self):
        for failure in (None, "save-attempted", "instrument", "receipt"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = self.manual_probe()
                probe.out = Path(directory)
                probe.native_completed = True
                probe.manual_save_executed = failure == "save-attempted"
                probe.manual_memory_verified = probe.manual_memory_absence_verified = False
                probe.accessibility_owner = "host"
                probe.shell.return_value = ("FAILURES!!!" if failure == "instrument" else "OK (1 test)", 0, "")
                receipt = dict(fixtureUuid=probe.fixture, sourcesCurrent=True, manualMemoryAbsent=True, status="PASS")
                probe.call.return_value = module.json.dumps(receipt), 1 if failure == "receipt" else 0, ""
                if failure:
                    with self.assertRaises(RuntimeError): probe.verify_manual_memory_absence_before_source_cleanup()
                    self.assertFalse(probe.manual_memory_absence_verified)
                else:
                    probe.verify_manual_memory_absence_before_source_cleanup()
                    self.assertTrue(probe.manual_memory_absence_verified)
                    probe.shell.assert_called_once_with("am", "instrument", "-w", "-r", "-e", "fixtureUuid", probe.fixture,
                        "-e", "manualMemoryExpected", "absent", "-e", "class", module.MANUAL_MEMORY_VERIFY_TEST, module.RUNNER,
                        timeout=90, check=False, include_stderr=True)
                    probe.shell.reset_mock()
                    probe.read_manifest = Mock(return_value={"rows": []})
                    probe.cleanup()
                    self.assertTrue(probe.cleanup_complete)
                self.assertFalse(probe.manual_memory_verified)
                if failure == "save-attempted": probe.shell.assert_not_called()

    def test_manual_save_attempt_is_recorded_before_tap_can_fail(self):
        probe = self.manual_probe()
        probe.manual_memory_review = Mock()
        probe.manual_save_executed = False
        probe.click.side_effect = RuntimeError("Tap result lost")
        with self.assertRaisesRegex(RuntimeError, "Tap result lost"):
            probe.verify_manual_memory_restored()
        self.assertTrue(probe.manual_save_executed)

    def commit_gate(self, probe):
        return dict(fixtureUuid=probe.fixture, token="00000000-0000-4000-8000-000000000003",
                    draftId="00000000-0000-4000-8000-000000000001", phase="AFTER_ROOM_COMMIT", pid=123, uid=10123,
                    requestFingerprint=module.manual_memory_fingerprint(probe.manifest["rows"], probe.manual_memory_title()),
                    deadlineElapsedRealtimeMillis=5000)

    def test_manual_commit_gate_receipt_binds_request_process_and_canonical_tokens(self):
        probe = self.manual_probe()
        gate = self.commit_gate(probe)
        self.assertEqual(gate, module.verify_manual_commit_gate(module.json.dumps(gate), probe.fixture,
                         probe.manifest["rows"], probe.manual_memory_title(), 123, 10123))
        for field, value in (("fixtureUuid", "other"), ("token", "bad"), ("draftId", 3), ("phase", "BEFORE_COMMIT"),
                             ("pid", 124), ("uid", 10124), ("requestFingerprint", "different"), ("deadlineElapsedRealtimeMillis", 0)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_manual_commit_gate(module.json.dumps(dict(gate, **{field:value})), probe.fixture,
                    probe.manifest["rows"], probe.manual_memory_title(), 123, 10123)
        for output in ("", "[]", module.json.dumps(dict(gate, extra=True))):
            with self.assertRaises(RuntimeError): module.verify_manual_commit_gate(output, probe.fixture,
                    probe.manifest["rows"], probe.manual_memory_title(), 123, 10123)

    def test_manual_commit_seed_arms_only_explicit_commit_scenario(self):
        for scenario in ("manual-memory-draft", "manual-memory-commit"):
            probe = self.manual_probe()
            probe.scenario = scenario
            probe.args = SimpleNamespace(serial="emulator-5554")
            probe.threads = []; probe.stdout = []; probe.stderr = []
            process = Mock(stdout=[], stderr=[])
            with patch.object(module.subprocess, "Popen", return_value=process), patch.object(module.threading, "Thread"):
                probe.start_native()
            self.assertEqual(scenario == "manual-memory-commit", "-e armManualCommitGap true" in probe.native_command[-1])
            self.assertIn(module.TEST, probe.native_command[-1])

    def test_manual_commit_setup_saves_once_and_requires_postcommit_paused_callback(self):
        with tempfile.TemporaryDirectory() as directory:
            probe = self.manual_probe()
            probe.out = Path(directory)
            probe.manifest.update(armManualCommitGap=True, uid=10123)
            probe.manual_commit_gate = None
            probe.draft_evidence = {}
            probe.process_identity = Mock()
            probe.require_manual_commit_gate_held = Mock()
            gate = self.commit_gate(probe)
            probe.call.return_value = module.json.dumps(gate), 0, ""
            probe.shell.side_effect = [("123", 0), ("", 1, ""), ("", 0, "")]
            def find(attr, tag, **kwargs):
                node = self.manual_node(probe, tag)
                if tag == "manual-moment-save": node.set("enabled", "false")
                return node
            probe.find.side_effect = find
            with patch.object(module.time, "sleep"):
                probe.setup_manual_memory_commit_gate()
            probe.click.assert_called_once_with("manual-moment-save")
            probe.process_identity.assert_called_once_with(123)
            probe.require_manual_commit_gate_held.assert_called_once_with()
            self.assertEqual(gate, probe.manual_commit_gate)
            self.assertTrue(probe.manual_save_executed)
            self.assertFalse(probe.draft_evidence["callbackAcknowledged"])

    def test_manual_commit_held_rejects_timeout_or_release_before_process_kill(self):
        for failure in (None, "timeout", "release"):
            probe = self.manual_probe()
            probe.manual_commit_gate = self.commit_gate(probe)
            probe.shell.side_effect = [("5.01 0.00" if failure == "timeout" else "4.00 0.00", 0),
                                      ("", 1 if failure == "release" else 0), ("", 0)]
            if failure:
                with self.assertRaises(RuntimeError): probe.require_manual_commit_gate_held()
            else:
                probe.require_manual_commit_gate_held()
                self.assertEqual(3, probe.shell.call_count)

    def test_manual_commit_recovery_requires_explicit_open_and_never_repeats_save(self):
        for enabled in ("true", "false"):
            probe = self.manual_probe()
            probe.manual_commit_gate = self.commit_gate(probe)
            probe.manual_save_executed = True
            probe.verify_manual_memory_result = Mock()
            probe.find.side_effect = lambda attr, tag, **kwargs: module.ET.Element("node", {
                "resource-id": tag, "package": module.PACKAGE, "enabled": enabled})
            if enabled == "true":
                probe.verify_manual_memory_commit_restored()
                self.assertTrue(probe.commit_recovery_opened)
                probe.tap.assert_called_once()
                probe.verify_manual_memory_result.assert_called_once_with()
            else:
                with self.assertRaises(RuntimeError): probe.verify_manual_memory_commit_restored()
                probe.tap.assert_not_called()
            probe.click.assert_not_called()  # Recovery opens the existing result; no create request.

    def test_manual_fingerprint_binds_both_generations_source_order_and_title(self):
        rows = self.manual_rows()
        expected = module.manual_memory_fingerprint(rows, "Title")
        self.assertEqual(expected, module.manual_memory_fingerprint(list(reversed(rows)), " Title "))
        for field in ("generation_added", "generation_modified", "_id"):
            changed = [dict(rows[0], **{field: rows[0][field]+10}), rows[1]]
            self.assertNotEqual(expected, module.manual_memory_fingerprint(changed, "Title"))
        self.assertNotEqual(expected, module.manual_memory_fingerprint([dict(rows[0], ordinal=1), dict(rows[1], ordinal=0)], "Title"))
        self.assertNotEqual(expected, module.manual_memory_fingerprint(rows, "Other"))
        with self.assertRaises(RuntimeError):
            module.manual_memory_fingerprint([rows[0], rows[0]], "Title")

    def test_manual_review_requires_title_order_consent_and_saveable_draft(self):
        probe = self.manual_probe()
        probe.manual_memory_review("restored")
        probe.click.assert_not_called()
        for tag, attribute, value in (("manual-moment-title", "text", "lost"),
                                     ("manual-moment-include-special", "checkable", "false"),
                                     ("manual-moment-save", "enabled", "false")):
            def find(attr, actual, **kwargs):
                node = self.manual_node(probe, actual)
                if actual == tag:
                    node.set(attribute, value)
                return node
            probe.find.side_effect = find
            with self.subTest(tag=tag), self.assertRaises(RuntimeError):
                probe.manual_memory_review("restored")
        def wrong_order(attr, tag, **kwargs):
            node = self.manual_node(probe, tag)
            if tag.startswith("manual-moment-source-"):
                node[0].set("text", "Photo 2 of 2")
            return node
        probe.find.side_effect = wrong_order
        with self.assertRaisesRegex(RuntimeError, "order changed"):
            probe.manual_memory_review("restored")

    def test_manual_setup_enters_review_without_save_and_uses_owned_uuid_title(self):
        probe = self.manual_probe()
        probe.manual_memory_review = Mock()
        title_text = [""]
        def shell(*args, **kwargs):
            if args == ("dumpsys", "input_method"):
                return "Current Input Method Manager state:\n  mInputShown=false\n", 0
            if args[:2] == ("input", "text"):
                title_text[0] += args[2].replace("%s", " ")
            return "", 0
        probe.shell.side_effect = shell
        def find(attr, tag, **kwargs):
            node = self.manual_node(probe, tag)
            if tag == "manual-moment-title":
                node.set("text", title_text[0])
                node.set("focused", "true")
            if tag == "manual-moment-include-special": node.set("checked", "false")
            return node
        probe.find.side_effect = find
        probe.setup_manual_memory()
        self.assertEqual([("create-memory",), ("manual-moment-up-external_primary:22",)],
                         [call.args for call in probe.click.call_args_list])
        self.assertEqual(probe.manual_memory_title(), title_text[0])
        injected = [call.args[2].replace("%s", " ") for call in probe.shell.call_args_list
                    if call.args[:2] == ("input", "text")]
        self.assertEqual(probe.manual_memory_title(), "".join(injected))
        self.assertTrue(all(len(chunk) <= 8 for chunk in injected))
        self.assertFalse(probe.draft_evidence["saveExecuted"])
        self.assertFalse(probe.draft_evidence["commitInterruptionVerified"])
        self.assertFalse(probe.draft_evidence["draftUuidUiCompared"])

    def test_manual_restore_has_one_explicit_save_ordered_viewer_sources_and_no_outputs(self):
        probe = self.manual_probe()
        probe.manual_memory_review = Mock()
        probe.verify_manual_memory_restored()
        self.assertEqual([("manual-moment-save",), ("moment-next",)], [c.args for c in probe.click.call_args_list])
        self.assertEqual(2, probe.source_hash.call_count)
        self.assertTrue(probe.manual_save_executed)
        self.assertTrue(probe.no_auto_export_verified)
        probe.find.assert_any_call("resource-id", "moment-image-loaded-external_primary:22-9", timeout=30, scroll=False)
        probe.find.assert_any_call("resource-id", "moment-image-loaded-external_primary:21-8", timeout=30, scroll=False)
        probe.creation_outputs.return_value = (("unexpected",),)
        with self.assertRaisesRegex(RuntimeError, "unexpected media outputs"):
            probe.verify_manual_memory_restored()

    def test_manual_result_selection_is_consumed_by_save_but_preserved_by_commit_recovery(self):
        for scenario in ("manual-memory-draft", "manual-memory-commit"):
            for selection_present in (False, True):
                with self.subTest(scenario=scenario, selection_present=selection_present):
                    probe = self.manual_probe()
                    probe.scenario = scenario
                    probe.manual_result_selection_present = selection_present
                    expected_present = scenario == "manual-memory-commit"
                    if selection_present == expected_present:
                        probe.verify_manual_memory_result()
                        self.assertTrue(probe.no_auto_export_verified)
                        self.assertEqual(2, probe.source_hash.call_count)
                    else:
                        with self.assertRaisesRegex(RuntimeError, "selection"):
                            probe.verify_manual_memory_result()
                        probe.source_hash.assert_not_called()
                    self.assertEqual([("moment-next",)], [call.args for call in probe.click.call_args_list])
                    self.assertEqual([("input", "keyevent", "KEYCODE_BACK")] * 2,
                                     [call.args for call in probe.shell.call_args_list])
                    probe.find.assert_any_call("resource-id", "memories-browser-screen", timeout=30, scroll=False)
                    probe.find.assert_any_call("resource-id", "collections-all-memories", timeout=30)
                    probe.tap.assert_not_called()  # No Photos navigation or selection mutation.

    def test_manual_result_rejects_global_toolbar_inside_moment_in_both_scenarios(self):
        for scenario in ("manual-memory-draft", "manual-memory-commit"):
            with self.subTest(scenario=scenario):
                probe = self.manual_probe()
                probe.scenario = scenario
                probe.manual_moment_toolbar_present = True
                with self.assertRaisesRegex(RuntimeError, "toolbar.*Moment"):
                    probe.verify_manual_memory_result()
                probe.shell.assert_not_called()
                probe.source_hash.assert_not_called()

    def test_manual_result_refuses_selection_evidence_before_actual_root_collections(self):
        for scenario in ("manual-memory-draft", "manual-memory-commit"):
            with self.subTest(scenario=scenario):
                probe = self.manual_probe()
                probe.scenario = scenario
                probe.manual_wrong_return_route = True
                with self.assertRaisesRegex(RuntimeError, "Root Collections"):
                    probe.verify_manual_memory_result()
                probe.source_hash.assert_not_called()
                self.assertFalse(any("Selection count verified" in call.kwargs.get("assertion", "")
                                     for call in probe.record.call_args_list))

    def test_manual_native_verifier_requires_complete_ui_and_final_receipt_before_source_cas(self):
        for failure in (None, "instrument", "receipt", "cleanup", "sources"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = self.manual_probe()
                probe.out = Path(directory)
                probe.ui_complete = probe.death_confirmed = probe.native_completed = probe.manual_save_executed = True
                probe.accessibility_owner = "host"
                probe.manual_memory_verified = False
                probe.process_identity = Mock()
                probe.shell.return_value = ("FAILURES!!!" if failure == "instrument" else "OK (1 test)", 0, "")
                receipt = self.manual_receipt(probe)
                if failure == "cleanup": receipt["manualMemoryCleaned"] = False
                if failure == "sources": receipt["sourcesCurrent"] = False
                probe.call.return_value = module.json.dumps(receipt), 1 if failure == "receipt" else 0, ""
                if failure:
                    with self.assertRaises(RuntimeError): probe.verify_manual_memory_before_source_cleanup()
                    self.assertFalse(probe.manual_memory_verified)
                else:
                    probe.verify_manual_memory_before_source_cleanup()
                    self.assertTrue(probe.manual_memory_verified)
                self.assertEqual("native-verifier", probe.accessibility_owner)
                probe.shell.assert_called_once_with("am", "instrument", "-w", "-r", "-e", "fixtureUuid", probe.fixture,
                    "-e", "class", module.MANUAL_MEMORY_VERIFY_TEST, module.RUNNER, timeout=90, check=False, include_stderr=True)
                probe.process_identity.assert_not_called()
                if failure == "instrument": probe.call.assert_not_called()

    def test_manual_cleanup_blocks_sources_until_exact_memory_verified_and_removed(self):
        probe = self.manual_probe()
        probe.manual_memory_verified = False
        probe.read_manifest = Mock()
        with self.assertRaisesRegex(RuntimeError, "pre-deletion verification"):
            probe.cleanup()
        probe.read_manifest.assert_not_called()
        probe.shell.assert_not_called()

    def test_manual_execute_orders_save_verification_memory_cleanup_before_source_cas(self):
        for failure in (None, "ui", "verifier"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = module.Probe(SimpleNamespace(scenario="manual-memory-draft", evidence=str(Path(directory)/"run"),
                    serial="127.0.0.1:5563", apk_sha256="a"*64, test_apk_sha256="b"*64))
                probe.manifest = dict(state="SEEDED", rows=self.manual_rows())
                probe.stdout = ["OK (1 test)"]
                probe.process = Mock(returncode=0)
                probe.process.poll.return_value = 0
                for name in ("guard_device", "guard_observer", "apk", "start_native", "native_finished", "kill_owned", "launch_normal", "process_identity"):
                    setattr(probe, name, Mock())
                probe.read_manifest = Mock(return_value=probe.manifest)
                probe.shell = Mock(side_effect=lambda *args, **kwargs: (
                    module.RUNNER + " (target=" + module.PACKAGE + ")" if args[:3] == ("pm", "list", "instrumentation")
                    else "200" if args[0] == "pidof" else "", 0))
                probe.task_id = 8
                probe.old_pid = 100
                probe.task_ids = Mock(return_value={8})
                probe.setup_normal_app = Mock()
                order = []
                def restored():
                    order.append("restored-review-save-open-source-hash")
                    if failure == "ui": raise RuntimeError("UI/source mismatch")
                    probe.manual_save_executed = True
                probe.verify_manual_memory_restored = Mock(side_effect=restored)
                def verify():
                    self.assertTrue(probe.ui_complete and probe.manual_save_executed)
                    order.append("native-verify-and-memory-cleanup")
                    if failure == "verifier": raise RuntimeError("Receipt mismatch; sources retained")
                    probe.manual_memory_verified = True
                probe.verify_manual_memory_before_source_cleanup = Mock(side_effect=verify)
                def cleanup():
                    self.assertTrue(probe.manual_memory_verified)
                    order.append("CAS-sources")
                    probe.cleanup_complete = True
                probe.cleanup = Mock(side_effect=cleanup)
                probe.call = Mock(return_value=(b"diagnostic", 0))
                with patch("builtins.print"):
                    status = probe.execute()
                self.assertEqual(0 if failure is None else 1, status)
                self.assertEqual(["restored-review-save-open-source-hash"] +
                    ([] if failure == "ui" else ["native-verify-and-memory-cleanup"]) +
                    (["CAS-sources"] if failure is None else []), order)
                probe.kill_owned.assert_called_once_with(100)
                probe.process_identity.assert_called_once_with(200)
                probe.task_ids.assert_any_call(resumed=True)
                if failure: probe.cleanup.assert_not_called()
                self.assertFalse(module.json.loads((probe.out/"result.json").read_text())["commitInterruptionVerified"])

    def test_manual_commit_execute_keeps_gate_death_open_native_cleanup_order(self):
        for failure in (None, "ui", "verifier"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = module.Probe(SimpleNamespace(scenario="manual-memory-commit", evidence=str(Path(directory)/"run"),
                    serial="127.0.0.1:5563", apk_sha256="a"*64, test_apk_sha256="b"*64))
                probe.manifest = dict(state="SEEDED", rows=self.manual_rows())
                probe.stdout = ["OK (1 test)"]
                probe.process = Mock(returncode=0)
                probe.process.poll.return_value = 0
                for name in ("guard_device", "guard_observer", "apk", "start_native", "native_finished", "kill_owned", "launch_normal", "process_identity"):
                    setattr(probe, name, Mock())
                probe.read_manifest = Mock(return_value=probe.manifest)
                probe.shell = Mock(side_effect=lambda *args, **kwargs: (
                    module.RUNNER + " (target=" + module.PACKAGE + ")" if args[:3] == ("pm", "list", "instrumentation")
                    else "200" if args[0] == "pidof" else "", 0))
                probe.task_id = 8
                probe.old_pid = 100
                probe.task_ids = Mock(return_value={8})
                probe.setup_normal_app = Mock()
                probe.manual_save_executed = True
                probe.manual_commit_gate = {"pid":100}
                probe.require_manual_commit_gate_held = Mock()
                order = []
                def restored():
                    order.append("restored-review-save-open-source-hash")
                    if failure == "ui": raise RuntimeError("UI/source mismatch")
                    probe.manual_save_executed = True
                    probe.commit_recovery_opened = True
                probe.verify_manual_memory_commit_restored = Mock(side_effect=restored)
                def verify():
                    self.assertTrue(probe.ui_complete and probe.manual_save_executed)
                    order.append("native-verify-and-memory-cleanup")
                    if failure == "verifier": raise RuntimeError("Receipt mismatch; sources retained")
                    probe.manual_memory_verified = True
                probe.verify_manual_memory_before_source_cleanup = Mock(side_effect=verify)
                def cleanup():
                    self.assertTrue(probe.manual_memory_verified)
                    order.append("CAS-sources")
                    probe.cleanup_complete = True
                probe.cleanup = Mock(side_effect=cleanup)
                probe.call = Mock(return_value=(b"diagnostic", 0))
                with patch("builtins.print"):
                    status = probe.execute()
                self.assertEqual(0 if failure is None else 1, status)
                self.assertEqual(["restored-review-save-open-source-hash"] +
                    ([] if failure == "ui" else ["native-verify-and-memory-cleanup"]) +
                    (["CAS-sources"] if failure is None else []), order)
                probe.kill_owned.assert_called_once_with(100)
                probe.process_identity.assert_called_once_with(200)
                probe.task_ids.assert_any_call(resumed=True)
                if failure: probe.cleanup.assert_not_called()
                self.assertEqual(failure is None, module.json.loads((probe.out/"result.json").read_text())["commitInterruptionVerified"])
                probe.require_manual_commit_gate_held.assert_called_once_with()

    def publication_gate(self):
        return dict(fixtureUuid="owned", sessionId="00000000-0000-4000-8000-000000000001", token="00000000-0000-4000-8000-000000000002",
            phase="AFTER_MEDIASTORE_COMMIT", pid=100, uid=10123, deadlineElapsedRealtimeMillis=5000, renderSha256="a"*64, renderSizeBytes=128,
            destination=dict(uri="content://media/external/images/media/30", ownerPackage=module.PACKAGE,
                displayName="UGallery-collage-00000000-0000-4000-8000-000000000003.png", relativePath=module.COLLAGE_OUTPUT_PATH,
                mimeType="image/png", generationAdded=10, generationModified=12, sizeBytes=128, pending=False, trashed=False))

    def test_publication_gate_requires_exact_owned_published_output_and_process(self):
        gate = self.publication_gate()
        self.assertEqual(gate, module.verify_collage_publication_gate(module.json.dumps(gate), "owned", 100, 10123))
        for field, value in (("fixtureUuid", "other"), ("sessionId", "bad"), ("phase", "BEFORE_COMMIT"), ("pid", 101),
                             ("token", 4), ("renderSha256", "bad"), ("renderSizeBytes", 129), ("deadlineElapsedRealtimeMillis", 0)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_collage_publication_gate(module.json.dumps(dict(gate, **{field:value})), "owned", 100, 10123)
        for field, value in (("ownerPackage", "other"), ("relativePath", "Pictures/other/"), ("pending", True), ("trashed", True),
                             ("generationAdded", -1), ("generationModified", 12.0), ("sizeBytes", 129), ("mimeType", "image/jpeg")):
            changed = dict(gate, destination=dict(gate["destination"], **{field:value}))
            with self.subTest(destination=field), self.assertRaises(RuntimeError):
                module.verify_collage_publication_gate(module.json.dumps(changed), "owned", 100, 10123)

    def test_publication_inventory_requires_one_new_row_and_preserves_previous_outputs(self):
        probe = self.probe()
        probe.collage_publication_gate = self.publication_gate()
        row = module.collage_publication_destination_row(probe.collage_publication_gate["destination"])
        expected = tuple(str(row[field]) for field in module.FIELDS)
        probe.collage_output_baseline = ()
        probe.collage_outputs = Mock(return_value=(expected,))
        probe.require_one_collage_publication("restored")
        for actual in ((), (expected, tuple(["31", *expected[1:]])), (tuple([*expected[:-1], "1"]),)):
            probe.collage_outputs.return_value = actual
            with self.assertRaises(RuntimeError): probe.require_one_collage_publication("restored")
        probe.shell.assert_not_called()

    def handoff_receipt(self, action="android.intent.action.VIEW"):
        gate = self.publication_gate(); uri = gate["destination"]["uri"]
        return dict(fixtureUuid="owned", sessionId=gate["sessionId"], token=gate["token"], pid=200, uid=10123,
            phase="DISPATCHED", action=action, uri=uri, mimeType="image/png", targetReadGrant=True, targetClipUris=[uri],
            chooserReadGrant=True, chooserClipUris=[uri])

    def test_publication_handoff_receipt_binds_real_action_uri_mime_clipdata_and_new_process(self):
        gate = self.publication_gate()
        for action in ("android.intent.action.VIEW", "android.intent.action.SEND"):
            receipt = self.handoff_receipt(action)
            self.assertEqual(receipt, module.verify_collage_handoff_receipt(module.json.dumps(receipt), "owned", gate, action, 200, 10123))
            for field, value in (("fixtureUuid", "other"), ("token", "other"), ("pid", 100), ("uid", 10124), ("phase", "PREPARED"),
                                 ("action", "android.intent.action.CHOOSER"), ("uri", receipt["uri"]+"1"), ("mimeType", "image/jpeg"),
                                 ("targetReadGrant", 1), ("chooserReadGrant", False), ("targetClipUris", []),
                                 ("chooserClipUris", [receipt["uri"], receipt["uri"]])):
                with self.subTest(action=action, field=field), self.assertRaises(RuntimeError):
                    module.verify_collage_handoff_receipt(module.json.dumps(dict(receipt, **{field:value})), "owned", gate, action, 200, 10123)
            for output in ("", "[]", module.json.dumps(dict(receipt, extra=True))):
                with self.assertRaises(RuntimeError): module.verify_collage_handoff_receipt(output, "owned", gate, action, 200, 10123)

    def test_publication_chooser_dump_proves_resumption_not_hidden_target_intent(self):
        # Android36.1 Intent.toString hides EXTRA_INTENT and redacts ClipData; this is not an action/URI receipt.
        redacted = "mResumedActivity: ActivityRecord android/.ChooserActivity\nIntent { act=android.intent.action.CHOOSER clip={image/png U(content)} (has extras) }"
        module.require_collage_handoff_chooser(redacted)
        for output in ("ACTIVITY android/.ChooserActivity", redacted.replace("mResumedActivity", "mLastPausedActivity"),
                       "mResumedActivity: ActivityRecord com.ugallery.app/.MainActivity"):
            with self.assertRaises(RuntimeError): module.require_collage_handoff_chooser(output)

    def test_publication_handoff_requires_absence_then_fresh_receipt_plus_resumed_chooser(self):
        for failure in (None, "stale", "missing-receipt", "not-resumed", "wrong-action"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = self.probe(); probe.out = Path(directory); probe.fixture = "owned"; probe.new_pid = 200
                probe.manifest = {"uid":10123}; probe.collage_publication_gate = self.publication_gate()
                probe.process_identity = Mock(); probe.click = Mock(); probe.collage_result_ready = Mock()
                probe.require_one_collage_publication = Mock()
                receipt = self.handoff_receipt("android.intent.action.SEND" if failure == "wrong-action" else "android.intent.action.VIEW")
                probe.call.return_value = module.json.dumps(receipt), 0, ""
                def shell(*args, **kwargs):
                    if args[:4] == ("run-as", module.PACKAGE, "test", "!"):
                        return "", 1 if failure == "stale" else 0, ""
                    if args[:4] == ("run-as", module.PACKAGE, "test", "-f"):
                        return "", 1 if failure == "missing-receipt" else 0, ""
                    if args[0] == "dumpsys":
                        return ("mResumedActivity: ActivityRecord android/." + ("MainActivity" if failure == "not-resumed" else "ChooserActivity"), 0)
                    return "", 0
                probe.shell.side_effect = shell
                with patch.object(module.time, "monotonic", side_effect=[0, 0, 16]), patch.object(module.time, "sleep"):
                    if failure:
                        with self.assertRaises(RuntimeError): probe.collage_result_handoff("creation-collage-open", "android.intent.action.VIEW")
                        probe.collage_result_ready.assert_not_called()
                    else:
                        probe.collage_result_handoff("creation-collage-open", "android.intent.action.VIEW")
                        probe.collage_result_ready.assert_called_once_with()
                        probe.require_one_collage_publication.assert_called_once_with("after-view")
                        self.assertEqual(receipt, module.json.loads((probe.out/"collage-publication-handoff-VIEW.json").read_text()))
                if failure == "stale": probe.click.assert_not_called()
                else: probe.click.assert_called_once_with("creation-collage-open")


    def test_publication_receipt_requires_proof_and_all_cleanup_before_sources(self):
        gate = self.publication_gate()
        receipt = dict(fixtureUuid="owned",sessionId=gate["sessionId"],token=gate["token"],outputUri=gate["destination"]["uri"],
            outputSha256=gate["renderSha256"],sourcesCurrent=True,outputVerified=True,outputCountOne=True,publicationRetired=True,
            outputCleaned=True,gateCleaned=True,status="PASS")
        self.assertEqual(receipt, module.verify_collage_publication_receipt(module.json.dumps(receipt), "owned", gate))
        for field in ("sourcesCurrent", "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned"):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_collage_publication_receipt(module.json.dumps(dict(receipt, **{field:False})), "owned", gate)
        for output in ("[]", "", module.json.dumps(dict(receipt, extra=1))):
            with self.assertRaises(RuntimeError): module.verify_collage_publication_receipt(output, "owned", gate)
        probe = self.probe(); probe.scenario = "collage-publication"; probe.collage_publication_verified = False
        probe.read_manifest = Mock()
        with self.assertRaisesRegex(RuntimeError, "native predelete"):
            probe.cleanup()
        probe.read_manifest.assert_not_called()

    def test_publication_restoration_opens_and_shares_existing_result_without_export(self):
        probe = self.probe()
        probe.manifest = {"rows": [self.row()]}
        probe.collage_result_ready = Mock(); probe.require_one_collage_publication = Mock()
        probe.collage_preview = Mock(); probe.collage_result_handoff = Mock()
        probe.source_hash = Mock(return_value=self.row()["sha256"])
        probe.click = Mock()
        probe.verify_collage_publication_restored()
        self.assertEqual([("creation-collage-open", "android.intent.action.VIEW"), ("creation-collage-share", "android.intent.action.SEND")],
                         [c.args for c in probe.collage_result_handoff.call_args_list])
        probe.click.assert_not_called()
        self.assertTrue(probe.no_auto_export_verified)

    def test_publication_execute_requires_gate_death_handoffs_native_output_cleanup_then_source_cas(self):
        for failure in (None, "ui", "native"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = module.Probe(SimpleNamespace(scenario="collage-publication", evidence=str(Path(directory)/"run"),
                    serial="127.0.0.1:5563", apk_sha256="a"*64, test_apk_sha256="b"*64))
                probe.manifest = dict(state="SEEDED", rows=self.manual_rows())
                probe.stdout = ["OK (1 test)"]; probe.process = Mock(returncode=0); probe.process.poll.return_value = 0
                for name in ("guard_device", "guard_observer", "apk", "start_native", "native_finished", "kill_owned", "launch_normal", "process_identity"):
                    setattr(probe, name, Mock())
                probe.read_manifest = Mock(return_value=probe.manifest)
                probe.shell = Mock(side_effect=lambda *args, **kwargs: (
                    module.RUNNER + " (target=" + module.PACKAGE + ")" if args[:3] == ("pm", "list", "instrumentation")
                    else "200" if args[0] == "pidof" else "", 0))
                probe.task_id = 8; probe.old_pid = 100
                probe.task_ids = Mock(return_value={8}); probe.setup_normal_app = Mock()
                probe.collage_publication_gate = self.publication_gate(); probe.collage_export_attempted = True
                probe.require_collage_publication_gate_held = Mock()
                order = []
                def restored():
                    order.append("restored-result-open-share-sources")
                    if failure == "ui": raise RuntimeError("Handoff mismatch")
                probe.verify_collage_publication_restored = Mock(side_effect=restored)
                def verify():
                    self.assertTrue(probe.ui_complete)
                    order.append("native-proof-retire-output-cas")
                    if failure == "native": raise RuntimeError("Output changed; retain sources")
                    probe.collage_publication_verified = True
                probe.verify_collage_publication_before_source_cleanup = Mock(side_effect=verify)
                def cleanup():
                    self.assertTrue(probe.collage_publication_verified)
                    order.append("sources-cas"); probe.cleanup_complete = True
                probe.cleanup = Mock(side_effect=cleanup); probe.call = Mock(return_value=(b"diagnostic",0))
                with patch("builtins.print"): status = probe.execute()
                self.assertEqual(0 if failure is None else 1, status)
                self.assertEqual(["restored-result-open-share-sources"] + ([] if failure == "ui" else ["native-proof-retire-output-cas"]) +
                                 (["sources-cas"] if failure is None else []), order)
                probe.kill_owned.assert_called_once_with(100); probe.process_identity.assert_called_once_with(200)
                probe.require_collage_publication_gate_held.assert_called_once_with()
                result = module.json.loads((probe.out/"result.json").read_text())
                self.assertTrue(result["exportExecuted"])
                self.assertEqual(failure is None, result["publicationInterruptionVerified"])
                self.assertFalse(result["recipientRenderingVerified"])
                if failure: probe.cleanup.assert_not_called()

    def test_publication_postcommit_cancel_is_revealed_without_a_second_export_or_cancel_tap(self):
        # api30-process XML record210: Cancel[44,2175][230,2307], ScrollView[0,321][1080,2208].
        # The Export tap540,2098 and AFTER_MEDIASTORE_COMMIT receipt preceded this clipped control.
        package = module.PACKAGE
        xml = f'<node package="{package}" bounds="[0,0][1080,2340]"><node package="{package}" class="android.widget.ScrollView" scrollable="true" bounds="[0,321][1080,2208]"><node package="{package}" resource-id="creation-collage-cancel" bounds="[44,2175][230,2307]" /></node></node>'
        root = module.ET.fromstring(xml); nodes = list(root.iter("node")); cancel = nodes[-1]
        self.assertFalse(module.center_visible(cancel, nodes))
        with tempfile.TemporaryDirectory() as directory:
            probe = self.probe(); probe.out = Path(directory); probe.fixture = "owned"
            probe.manifest = dict(armCollagePublication=True, uid=10123)
            probe.collage_publication_gate = None; probe.ui_nodes = nodes; probe.draft_evidence = {}
            probe.process_identity = Mock(); probe.click = Mock()
            probe.require_collage_publication_gate_held = Mock(); probe.require_one_collage_publication = Mock()
            probe.shell.side_effect = [("100", 0), ("", 0, "")]
            probe.call.return_value = module.json.dumps(self.publication_gate()), 0, ""
            probe.find = Mock(return_value=cancel)
            probe.setup_collage_publication_gate()
            probe.find.assert_called_once_with("resource-id", "creation-collage-cancel", scroll=True)
            probe.click.assert_called_once_with("creation-collage-export")
            probe.require_collage_publication_gate_held.assert_called_once_with()
            probe.require_one_collage_publication.assert_called_once_with("before-process-death")

    def test_publication_saved_result_below_restored_scroll_is_revealed_without_export(self):
        # api30-process-cancel-fixed: result footer is below the restored top viewport.
        probe = self.probe()
        def find(attribute, tag, **kwargs):
            if tag == "creation-collage-saved":
                self.assertEqual(dict(timeout=30, scroll=True), kwargs)
            return module.ET.Element("node", enabled="false" if tag == "creation-collage-export" else "true")
        probe.find = Mock(side_effect=find); probe.click = Mock()
        probe.collage_result_ready()
        self.assertEqual(["creation-collage-saved", "creation-collage-open", "creation-collage-share", "creation-collage-export"],
                         [call.args[1] for call in probe.find.call_args_list])
        probe.click.assert_not_called()

    def test_publication_seed_uses_new_native_class_without_changing_shared_manual_seed(self):
        probe = self.manual_probe(); probe.scenario = "collage-publication"
        probe.args = SimpleNamespace(serial="emulator-5554"); probe.threads = []; probe.stdout = []; probe.stderr = []
        with patch.object(module.subprocess, "Popen", return_value=Mock(stdout=[], stderr=[])), patch.object(module.threading, "Thread"):
            probe.start_native()
        self.assertIn(module.COLLAGE_PUBLICATION_SEED, probe.native_command[-1])
        self.assertNotIn("armManualCommitGap", probe.native_command[-1])

    def gif_publication_gate(self):
        return dict(fixtureUuid="owned", sessionId="00000000-0000-4000-8000-000000000001", token="00000000-0000-4000-8000-000000000002",
            phase="AFTER_MEDIASTORE_COMMIT", pid=100, uid=10123, deadlineElapsedRealtimeMillis=5000, renderSha256="a"*64, renderSizeBytes=128,
            destination=dict(uri="content://media/external/images/media/30", ownerPackage=module.PACKAGE,
                displayName="UGallery-GIF-00000000-0000-4000-8000-000000000002.gif", relativePath=module.GIF_OUTPUT_PATH,
                mimeType="image/gif", generationAdded=10, generationModified=12, sizeBytes=128, pending=False, trashed=False))

    def test_gif_publication_gate_requires_exact_owned_published_output_and_process(self):
        gate = self.gif_publication_gate()
        self.assertEqual(gate, module.verify_gif_publication_gate(module.json.dumps(gate), "owned", 100, 10123))
        for field, value in (("fixtureUuid", "other"), ("sessionId", "bad"), ("phase", "BEFORE_COMMIT"), ("pid", 101),
                             ("token", 4), ("renderSha256", "bad"), ("renderSizeBytes", 129), ("deadlineElapsedRealtimeMillis", 0)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_gif_publication_gate(module.json.dumps(dict(gate, **{field:value})), "owned", 100, 10123)
        for field, value in (("ownerPackage", "other"), ("relativePath", "Pictures/other/"), ("pending", True), ("trashed", True),
                             ("generationAdded", -1), ("generationModified", 9), ("generationModified", 12.0), ("sizeBytes", 129), ("mimeType", "image/jpeg"), ("displayName", "UGallery-GIF-00000000-0000-4000-8000-000000000003.gif")):
            changed = dict(gate, destination=dict(gate["destination"], **{field:value}))
            with self.subTest(destination=field), self.assertRaises(RuntimeError):
                module.verify_gif_publication_gate(module.json.dumps(changed), "owned", 100, 10123)

    def test_gif_publication_inventory_requires_one_new_row_and_preserves_previous_outputs(self):
        probe = self.probe()
        probe.gif_publication_gate = self.gif_publication_gate()
        row = module.gif_publication_destination_row(probe.gif_publication_gate["destination"])
        expected = tuple(str(row[field]) for field in module.FIELDS)
        probe.gif_output_baseline = ()
        probe.creation_outputs = Mock(return_value=(expected,))
        probe.require_one_gif_publication("restored")
        for actual in ((), (expected, tuple(["31", *expected[1:]])), (tuple([*expected[:-1], "1"]),)):
            probe.creation_outputs.return_value = actual
            with self.assertRaises(RuntimeError): probe.require_one_gif_publication("restored")
        probe.shell.assert_not_called()

    def gif_handoff_receipt(self, action="android.intent.action.VIEW"):
        gate = self.gif_publication_gate(); uri = gate["destination"]["uri"]
        return dict(fixtureUuid="owned", sessionId=gate["sessionId"], token=gate["token"], pid=200, uid=10123,
            phase="DISPATCHED", action=action, uri=uri, mimeType="image/gif", targetReadGrant=True, targetClipUris=[uri],
            chooserReadGrant=True, chooserClipUris=[uri])

    def test_gif_publication_handoff_receipt_binds_real_action_uri_mime_clipdata_and_new_process(self):
        gate = self.gif_publication_gate()
        for action in ("android.intent.action.VIEW", "android.intent.action.SEND"):
            receipt = self.gif_handoff_receipt(action)
            self.assertEqual(receipt, module.verify_gif_handoff_receipt(module.json.dumps(receipt), "owned", gate, action, 200, 10123))
            for field, value in (("fixtureUuid", "other"), ("token", "other"), ("pid", 100), ("uid", 10124), ("phase", "PREPARED"),
                                 ("action", "android.intent.action.CHOOSER"), ("uri", receipt["uri"]+"1"), ("mimeType", "image/jpeg"),
                                 ("targetReadGrant", 1), ("chooserReadGrant", False), ("targetClipUris", []),
                                 ("chooserClipUris", [receipt["uri"], receipt["uri"]])):
                with self.subTest(action=action, field=field), self.assertRaises(RuntimeError):
                    module.verify_gif_handoff_receipt(module.json.dumps(dict(receipt, **{field:value})), "owned", gate, action, 200, 10123)
            for output in ("", "[]", module.json.dumps(dict(receipt, extra=True))):
                with self.assertRaises(RuntimeError): module.verify_gif_handoff_receipt(output, "owned", gate, action, 200, 10123)

    def test_gif_publication_chooser_dump_proves_resumption_not_hidden_target_intent(self):
        # Android36.1 Intent.toString hides EXTRA_INTENT and redacts ClipData; this is not an action/URI receipt.
        redacted = "mResumedActivity: ActivityRecord android/.ChooserActivity\nIntent { act=android.intent.action.CHOOSER clip={image/gif U(content)} (has extras) }"
        module.require_gif_handoff_chooser(redacted)
        for output in ("ACTIVITY android/.ChooserActivity", redacted.replace("mResumedActivity", "mLastPausedActivity"),
                       "mResumedActivity: ActivityRecord com.ugallery.app/.MainActivity"):
            with self.assertRaises(RuntimeError): module.require_gif_handoff_chooser(output)

    def test_gif_publication_handoff_requires_absence_then_fresh_receipt_plus_resumed_chooser(self):
        for failure in (None, "stale", "missing-receipt", "not-resumed", "wrong-action"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = self.probe(); probe.out = Path(directory); probe.fixture = "owned"; probe.new_pid = 200
                probe.manifest = {"uid":10123}; probe.gif_publication_gate = self.gif_publication_gate()
                probe.process_identity = Mock(); probe.click = Mock(); probe.gif_result_ready = Mock()
                probe.require_one_gif_publication = Mock()
                receipt = self.gif_handoff_receipt("android.intent.action.SEND" if failure == "wrong-action" else "android.intent.action.VIEW")
                probe.call.return_value = module.json.dumps(receipt), 0, ""
                def shell(*args, **kwargs):
                    if args[:4] == ("run-as", module.PACKAGE, "test", "!"):
                        return "", 1 if failure == "stale" else 0, ""
                    if args[:4] == ("run-as", module.PACKAGE, "test", "-f"):
                        return "", 1 if failure == "missing-receipt" else 0, ""
                    if args[0] == "dumpsys":
                        return ("mResumedActivity: ActivityRecord android/." + ("MainActivity" if failure == "not-resumed" else "ChooserActivity"), 0)
                    return "", 0
                probe.shell.side_effect = shell
                with patch.object(module.time, "monotonic", side_effect=[0, 0, 16]), patch.object(module.time, "sleep"):
                    if failure:
                        with self.assertRaises(RuntimeError): probe.gif_result_handoff("creation-gif-open", "android.intent.action.VIEW")
                        probe.gif_result_ready.assert_not_called()
                    else:
                        probe.gif_result_handoff("creation-gif-open", "android.intent.action.VIEW")
                        probe.gif_result_ready.assert_called_once_with()
                        probe.require_one_gif_publication.assert_called_once_with("after-view")
                        self.assertEqual(receipt, module.json.loads((probe.out/"gif-publication-handoff-VIEW.json").read_text()))
                if failure == "stale": probe.click.assert_not_called()
                else: probe.click.assert_called_once_with("creation-gif-open")


    def test_gif_publication_receipt_requires_proof_and_all_cleanup_before_sources(self):
        gate = self.gif_publication_gate()
        receipt = dict(fixtureUuid="owned",sessionId=gate["sessionId"],token=gate["token"],outputUri=gate["destination"]["uri"],
            outputSha256=gate["renderSha256"],sourcesCurrent=True,outputVerified=True,outputCountOne=True,publicationRetired=True,
            outputCleaned=True,gateCleaned=True,status="PASS")
        self.assertEqual(receipt, module.verify_gif_publication_receipt(module.json.dumps(receipt), "owned", gate))
        for field in ("sourcesCurrent", "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned"):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_gif_publication_receipt(module.json.dumps(dict(receipt, **{field:False})), "owned", gate)
        for output in ("[]", "", module.json.dumps(dict(receipt, extra=1))):
            with self.assertRaises(RuntimeError): module.verify_gif_publication_receipt(output, "owned", gate)
        probe = self.probe(); probe.scenario = "gif-publication"; probe.gif_publication_verified = False
        probe.read_manifest = Mock()
        with self.assertRaisesRegex(RuntimeError, "native predelete"):
            probe.cleanup()
        probe.read_manifest.assert_not_called()

    def test_gif_publication_restoration_opens_and_shares_existing_result_without_export(self):
        probe = self.probe()
        probe.manifest = {"rows": [self.row()]}
        probe.gif_result_ready = Mock(); probe.require_one_gif_publication = Mock()
        probe.find = Mock(return_value=module.ET.Element("node", checked="true")); probe.gif_result_handoff = Mock()
        probe.source_hash = Mock(return_value=self.row()["sha256"])
        probe.click = Mock()
        probe.verify_gif_publication_restored()
        self.assertEqual([("creation-gif-open", "android.intent.action.VIEW"), ("creation-gif-share", "android.intent.action.SEND")],
                         [c.args for c in probe.gif_result_handoff.call_args_list])
        probe.click.assert_not_called()
        self.assertTrue(probe.no_auto_export_verified)

    def test_gif_publication_execute_requires_gate_death_handoffs_native_output_cleanup_then_source_cas(self):
        for failure in (None, "ui", "native"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = module.Probe(SimpleNamespace(scenario="gif-publication", evidence=str(Path(directory)/"run"),
                    serial="127.0.0.1:5563", apk_sha256="a"*64, test_apk_sha256="b"*64))
                probe.manifest = dict(state="SEEDED", rows=self.manual_rows())
                probe.stdout = ["OK (1 test)"]; probe.process = Mock(returncode=0); probe.process.poll.return_value = 0
                for name in ("guard_device", "guard_observer", "apk", "start_native", "native_finished", "kill_owned", "launch_normal", "process_identity"):
                    setattr(probe, name, Mock())
                probe.read_manifest = Mock(return_value=probe.manifest)
                probe.shell = Mock(side_effect=lambda *args, **kwargs: (
                    module.RUNNER + " (target=" + module.PACKAGE + ")" if args[:3] == ("pm", "list", "instrumentation")
                    else "200" if args[0] == "pidof" else "", 0))
                probe.task_id = 8; probe.old_pid = 100
                probe.task_ids = Mock(return_value={8}); probe.setup_normal_app = Mock()
                probe.gif_publication_gate = self.gif_publication_gate(); probe.gif_export_attempted = True
                probe.require_gif_publication_gate_held = Mock()
                order = []
                def restored():
                    order.append("restored-result-open-share-sources")
                    if failure == "ui": raise RuntimeError("Handoff mismatch")
                probe.verify_gif_publication_restored = Mock(side_effect=restored)
                def verify():
                    self.assertTrue(probe.ui_complete)
                    order.append("native-proof-retire-output-cas")
                    if failure == "native": raise RuntimeError("Output changed; retain sources")
                    probe.gif_publication_verified = True
                probe.verify_gif_publication_before_source_cleanup = Mock(side_effect=verify)
                def cleanup():
                    self.assertTrue(probe.gif_publication_verified)
                    order.append("sources-cas"); probe.cleanup_complete = True
                probe.cleanup = Mock(side_effect=cleanup); probe.call = Mock(return_value=(b"diagnostic",0))
                with patch("builtins.print"): status = probe.execute()
                self.assertEqual(0 if failure is None else 1, status)
                self.assertEqual(["restored-result-open-share-sources"] + ([] if failure == "ui" else ["native-proof-retire-output-cas"]) +
                                 (["sources-cas"] if failure is None else []), order)
                probe.kill_owned.assert_called_once_with(100); probe.process_identity.assert_called_once_with(200)
                probe.require_gif_publication_gate_held.assert_called_once_with()
                result = module.json.loads((probe.out/"result.json").read_text())
                self.assertTrue(result["exportExecuted"])
                self.assertEqual(failure is None, result["publicationInterruptionVerified"])
                self.assertFalse(result["recipientRenderingVerified"])
                if failure: probe.cleanup.assert_not_called()

    def test_gif_publication_postcommit_cancel_is_revealed_without_a_second_export_or_cancel_tap(self):
        # GIF synthetic layout derived from accepted Collage XML record210: Cancel[44,2175][230,2307], ScrollView[0,321][1080,2208].
        # The Export tap540,2098 and AFTER_MEDIASTORE_COMMIT receipt preceded this clipped control.
        package = module.PACKAGE
        xml = f'<node package="{package}" bounds="[0,0][1080,2340]"><node package="{package}" class="android.widget.ScrollView" scrollable="true" bounds="[0,321][1080,2208]"><node package="{package}" resource-id="creation-gif-cancel" bounds="[44,2175][230,2307]" /></node></node>'
        root = module.ET.fromstring(xml); nodes = list(root.iter("node")); cancel = nodes[-1]
        self.assertFalse(module.center_visible(cancel, nodes))
        with tempfile.TemporaryDirectory() as directory:
            probe = self.probe(); probe.out = Path(directory); probe.fixture = "owned"
            probe.manifest = dict(armGifPublication=True, uid=10123)
            probe.gif_publication_gate = None; probe.ui_nodes = nodes; probe.draft_evidence = {}
            probe.process_identity = Mock(); probe.click = Mock()
            probe.require_gif_publication_gate_held = Mock(); probe.require_one_gif_publication = Mock()
            probe.shell.side_effect = [("100", 0), ("", 0, "")]
            probe.call.return_value = module.json.dumps(self.gif_publication_gate()), 0, ""
            probe.find = Mock(return_value=cancel)
            probe.setup_gif_publication_gate()
            probe.find.assert_called_once_with("resource-id", "creation-gif-cancel", scroll=True)
            probe.click.assert_called_once_with("creation-gif-export")
            probe.require_gif_publication_gate_held.assert_called_once_with()
            probe.require_one_gif_publication.assert_called_once_with("before-process-death")

    def test_gif_publication_saved_result_below_restored_scroll_is_revealed_without_export(self):
        # GIF synthetic footer uses the same scrolling contract as the Collage failure regression.
        probe = self.probe()
        def find(attribute, tag, **kwargs):
            if tag == "creation-gif-saved":
                self.assertEqual(dict(timeout=30, scroll=True), kwargs)
            return module.ET.Element("node", enabled="false" if tag == "creation-gif-export" else "true")
        probe.find = Mock(side_effect=find); probe.click = Mock()
        probe.gif_result_ready()
        self.assertEqual(["creation-gif-saved", "creation-gif-open", "creation-gif-share", "creation-gif-export"],
                         [call.args[1] for call in probe.find.call_args_list])
        probe.click.assert_not_called()

    def test_gif_publication_seed_uses_new_native_class_without_changing_shared_manual_seed(self):
        probe = self.manual_probe(); probe.scenario = "gif-publication"
        probe.args = SimpleNamespace(serial="emulator-5554"); probe.threads = []; probe.stdout = []; probe.stderr = []
        with patch.object(module.subprocess, "Popen", return_value=Mock(stdout=[], stderr=[])), patch.object(module.threading, "Thread"):
            probe.start_native()
        self.assertIn(module.GIF_PUBLICATION_SEED, probe.native_command[-1])
        self.assertNotIn("armManualCommitGap", probe.native_command[-1])

    def motion_publication_gate_frame(self):
        return dict(fixtureUuid="owned", publicationId="00000000-0000-4000-8000-000000000001", token="00000000-0000-4000-8000-000000000002",
            phase="AFTER_MEDIASTORE_COMMIT", pid=100, uid=10123, deadlineElapsedRealtimeMillis=5000, renderSha256="a"*64, renderSizeBytes=128,
            destination=dict(uri="content://media/external/images/media/30", ownerPackage=module.PACKAGE,
                displayName="UGallery-Motion-00000000-0000-4000-8000-000000000002.jpg", relativePath=module.MOTION_IMAGE_PATH,
                mimeType="image/jpeg", generationAdded=10, generationModified=12, sizeBytes=128, pending=False, trashed=False))

    def test_motion_publication_gate_requires_exact_owned_published_output_and_process_frame(self):
        gate = self.motion_publication_gate_frame()
        self.assertEqual(gate, module.verify_motion_publication_gate(module.json.dumps(gate), "owned", 100, 10123, "Frame"))
        for field, value in (("fixtureUuid", "other"), ("publicationId", "bad"), ("phase", "BEFORE_COMMIT"), ("pid", 101),
                             ("token", 4), ("renderSha256", "bad"), ("renderSizeBytes", 129), ("deadlineElapsedRealtimeMillis", 0)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_motion_publication_gate(module.json.dumps(dict(gate, **{field:value})), "owned", 100, 10123, "Frame")
        for field, value in (("ownerPackage", "other"), ("relativePath", "Pictures/other/"), ("pending", True), ("trashed", True),
                             ("generationAdded", -1), ("generationModified", 9), ("generationModified", 12.0), ("sizeBytes", 129), ("mimeType", "image/png"), ("displayName", "UGallery-Motion-00000000-0000-4000-8000-000000000003.jpg")):
            changed = dict(gate, destination=dict(gate["destination"], **{field:value}))
            with self.subTest(destination=field), self.assertRaises(RuntimeError):
                module.verify_motion_publication_gate(module.json.dumps(changed), "owned", 100, 10123, "Frame")

    def test_motion_publication_inventory_exact_target_and_other_collection_unchanged_frame(self):
        probe = self.probe(); probe.motion_publication_kind = "Frame"
        probe.motion_publication_gate = self.motion_publication_gate_frame()
        row = module.motion_publication_destination_row(probe.motion_publication_gate["destination"], "Frame")
        expected = tuple(str(row[field]) for field in module.FIELDS)
        other = module.MOTION_VIDEO_PATH if "Frame" == "Frame" else module.MOTION_IMAGE_PATH
        probe.motion_output_baseline = {module.MOTION_IMAGE_PATH: (), module.MOTION_VIDEO_PATH: ()}
        probe.creation_outputs = Mock(side_effect=lambda path: (expected,) if path == module.MOTION_IMAGE_PATH else ())
        probe.require_one_motion_publication("restored")
        probe.creation_outputs.side_effect = lambda path: (expected,)
        with self.assertRaises(RuntimeError): probe.require_one_motion_publication("extra-other-collection")
        probe.shell.assert_not_called()

    def motion_handoff_receipt_frame(self, action="android.intent.action.VIEW"):
        gate = self.motion_publication_gate_frame(); uri = gate["destination"]["uri"]
        return dict(fixtureUuid="owned", publicationId=gate["publicationId"], token=gate["token"], pid=200, uid=10123,
            phase="DISPATCHED", action=action, uri=uri, mimeType="image/jpeg", targetReadGrant=True, targetClipUris=[uri],
            chooserReadGrant=True, chooserClipUris=[uri])

    def test_motion_publication_handoff_receipt_binds_real_action_uri_mime_clipdata_and_new_process_frame(self):
        gate = self.motion_publication_gate_frame()
        for action in ("android.intent.action.VIEW", "android.intent.action.SEND"):
            receipt = self.motion_handoff_receipt_frame(action)
            self.assertEqual(receipt, module.verify_motion_handoff_receipt(module.json.dumps(receipt), "owned", gate, action, 200, 10123, "Frame"))
            for field, value in (("fixtureUuid", "other"), ("token", "other"), ("pid", 100), ("uid", 10124), ("phase", "PREPARED"),
                                 ("action", "android.intent.action.CHOOSER"), ("uri", receipt["uri"]+"1"), ("mimeType", "image/png"),
                                 ("targetReadGrant", 1), ("chooserReadGrant", False), ("targetClipUris", []),
                                 ("chooserClipUris", [receipt["uri"], receipt["uri"]])):
                with self.subTest(action=action, field=field), self.assertRaises(RuntimeError):
                    module.verify_motion_handoff_receipt(module.json.dumps(dict(receipt, **{field:value})), "owned", gate, action, 200, 10123, "Frame")
            for output in ("", "[]", module.json.dumps(dict(receipt, extra=True))):
                with self.assertRaises(RuntimeError): module.verify_motion_handoff_receipt(output, "owned", gate, action, 200, 10123, "Frame")

    def test_motion_publication_chooser_dump_proves_resumption_not_hidden_target_intent_frame(self):
        # Android36.1 Intent.toString hides EXTRA_INTENT and redacts ClipData; this is not an action/URI receipt.
        redacted = "mResumedActivity: ActivityRecord android/.ChooserActivity\nIntent { act=android.intent.action.CHOOSER clip={image/jpeg U(content)} (has extras) }"
        module.require_motion_handoff_chooser(redacted)
        for output in ("ACTIVITY android/.ChooserActivity", redacted.replace("mResumedActivity", "mLastPausedActivity"),
                       "mResumedActivity: ActivityRecord com.ugallery.app/.MainActivity"):
            with self.assertRaises(RuntimeError): module.require_motion_handoff_chooser(output)

    def test_motion_publication_handoff_requires_absence_then_fresh_receipt_plus_resumed_chooser_frame(self):
        for failure in (None, "stale", "missing-receipt", "not-resumed", "wrong-action"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = self.probe(); probe.motion_publication_kind = "Frame"; probe.out = Path(directory); probe.fixture = "owned"; probe.new_pid = 200
                probe.manifest = {"uid":10123}; probe.motion_publication_gate = self.motion_publication_gate_frame()
                probe.process_identity = Mock(); probe.click = Mock(); probe.motion_result_ready = Mock()
                probe.require_one_motion_publication = Mock()
                receipt = self.motion_handoff_receipt_frame("android.intent.action.SEND" if failure == "wrong-action" else "android.intent.action.VIEW")
                probe.call.return_value = module.json.dumps(receipt), 0, ""
                def shell(*args, **kwargs):
                    if args[:4] == ("run-as", module.PACKAGE, "test", "!"):
                        return "", 1 if failure == "stale" else 0, ""
                    if args[:4] == ("run-as", module.PACKAGE, "test", "-f"):
                        return "", 1 if failure == "missing-receipt" else 0, ""
                    if args[0] == "dumpsys":
                        return ("mResumedActivity: ActivityRecord android/." + ("MainActivity" if failure == "not-resumed" else "ChooserActivity"), 0)
                    return "", 0
                probe.shell.side_effect = shell
                with patch.object(module.time, "monotonic", side_effect=[0, 0, 16]), patch.object(module.time, "sleep"):
                    if failure:
                        with self.assertRaises(RuntimeError): probe.motion_result_handoff("motion-open", "android.intent.action.VIEW")
                        probe.motion_result_ready.assert_not_called()
                    else:
                        probe.motion_result_handoff("motion-open", "android.intent.action.VIEW")
                        probe.motion_result_ready.assert_called_once_with()
                        probe.require_one_motion_publication.assert_called_once_with("after-view")
                        self.assertEqual(receipt, module.json.loads((probe.out/"motion-publication-handoff-VIEW.json").read_text()))
                if failure == "stale": probe.click.assert_not_called()
                else: probe.click.assert_called_once_with("motion-open")


    def test_motion_publication_receipt_requires_proof_and_all_cleanup_before_sources_frame(self):
        gate = self.motion_publication_gate_frame()
        receipt = dict(fixtureUuid="owned",publicationId=gate["publicationId"],token=gate["token"],outputUri=gate["destination"]["uri"],
            outputSha256=gate["renderSha256"],sourcesCurrent=True,outputVerified=True,outputCountOne=True,publicationRetired=True,
            outputCleaned=True,gateCleaned=True,status="PASS")
        self.assertEqual(receipt, module.verify_motion_publication_receipt(module.json.dumps(receipt), "owned", gate))
        for field in ("sourcesCurrent", "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned"):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_motion_publication_receipt(module.json.dumps(dict(receipt, **{field:False})), "owned", gate)
        for output in ("[]", "", module.json.dumps(dict(receipt, extra=1))):
            with self.assertRaises(RuntimeError): module.verify_motion_publication_receipt(output, "owned", gate)
        probe = self.probe(); probe.motion_publication_kind = "Frame"; probe.scenario = "motion-frame-publication"; probe.motion_publication_verified = False
        probe.read_manifest = Mock()
        with self.assertRaisesRegex(RuntimeError, "native predelete"):
            probe.cleanup()
        probe.read_manifest.assert_not_called()

    def test_motion_publication_restoration_opens_and_shares_existing_result_without_export_frame(self):
        probe = self.probe(); probe.motion_publication_kind = "Frame"
        probe.manifest = {"rows": [self.row()]}
        probe.motion_result_ready = Mock(); probe.require_one_motion_publication = Mock()
        probe.find = Mock(return_value=module.ET.Element("node", checked="true")); probe.motion_result_handoff = Mock()
        probe.source_hash = Mock(return_value=self.row()["sha256"])
        probe.click = Mock()
        probe.verify_motion_publication_restored()
        self.assertEqual([("motion-open", "android.intent.action.VIEW"), ("motion-share", "android.intent.action.SEND")],
                         [c.args for c in probe.motion_result_handoff.call_args_list])
        probe.click.assert_not_called()
        self.assertTrue(probe.no_auto_export_verified)

    def test_motion_publication_execute_requires_gate_death_handoffs_native_output_cleanup_then_source_cas_frame(self):
        for failure in (None, "ui", "native"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = module.Probe(SimpleNamespace(scenario="motion-frame-publication", evidence=str(Path(directory)/"run"),
                    serial="127.0.0.1:5563", apk_sha256="a"*64, test_apk_sha256="b"*64))
                probe.manifest = dict(state="SEEDED", rows=self.manual_rows()[:1])
                probe.stdout = ["OK (1 test)"]; probe.process = Mock(returncode=0); probe.process.poll.return_value = 0
                for name in ("guard_device", "guard_observer", "apk", "start_native", "native_finished", "kill_owned", "launch_normal", "process_identity"):
                    setattr(probe, name, Mock())
                probe.read_manifest = Mock(return_value=probe.manifest)
                probe.shell = Mock(side_effect=lambda *args, **kwargs: (
                    module.RUNNER + " (target=" + module.PACKAGE + ")" if args[:3] == ("pm", "list", "instrumentation")
                    else "200" if args[0] == "pidof" else "", 0))
                probe.task_id = 8; probe.old_pid = 100
                probe.task_ids = Mock(return_value={8}); probe.setup_normal_app = Mock()
                probe.motion_publication_gate = self.motion_publication_gate_frame(); probe.motion_export_attempted = True
                probe.require_motion_publication_gate_held = Mock()
                order = []
                def restored():
                    order.append("restored-result-open-share-sources")
                    if failure == "ui": raise RuntimeError("Handoff mismatch")
                probe.verify_motion_publication_restored = Mock(side_effect=restored)
                def verify():
                    self.assertTrue(probe.ui_complete)
                    order.append("native-proof-retire-output-cas")
                    if failure == "native": raise RuntimeError("Output changed; retain sources")
                    probe.motion_publication_verified = True
                probe.verify_motion_publication_before_source_cleanup = Mock(side_effect=verify)
                def cleanup():
                    self.assertTrue(probe.motion_publication_verified)
                    order.append("sources-cas"); probe.cleanup_complete = True
                probe.cleanup = Mock(side_effect=cleanup); probe.call = Mock(return_value=(b"diagnostic",0))
                with patch("builtins.print"): status = probe.execute()
                self.assertEqual(0 if failure is None else 1, status)
                self.assertEqual(["restored-result-open-share-sources"] + ([] if failure == "ui" else ["native-proof-retire-output-cas"]) +
                                 (["sources-cas"] if failure is None else []), order)
                probe.kill_owned.assert_called_once_with(100); probe.process_identity.assert_called_once_with(200)
                probe.require_motion_publication_gate_held.assert_called_once_with()
                result = module.json.loads((probe.out/"result.json").read_text())
                self.assertTrue(result["exportExecuted"])
                self.assertEqual(failure is None, result["publicationInterruptionVerified"])
                self.assertFalse(result["recipientRenderingVerified"])
                if failure: probe.cleanup.assert_not_called()

    def test_motion_publication_postcommit_cancel_is_revealed_without_a_second_export_or_cancel_tap_frame(self):
        # GIF synthetic layout derived from accepted Collage XML record210: Cancel[44,2175][230,2307], ScrollView[0,321][1080,2208].
        # The Export tap540,2098 and AFTER_MEDIASTORE_COMMIT receipt preceded this clipped control.
        package = module.PACKAGE
        xml = f'<node package="{package}" bounds="[0,0][1080,2340]"><node package="{package}" class="android.widget.ScrollView" scrollable="true" bounds="[0,321][1080,2208]"><node package="{package}" resource-id="motion-working" bounds="[44,2175][230,2307]" /></node></node>'
        root = module.ET.fromstring(xml); nodes = list(root.iter("node")); cancel = nodes[-1]
        self.assertFalse(module.center_visible(cancel, nodes))
        with tempfile.TemporaryDirectory() as directory:
            probe = self.probe(); probe.motion_publication_kind = "Frame"; probe.out = Path(directory); probe.fixture = "owned"
            probe.manifest = dict(armMotionPublication=True, motionPublicationKind="Frame", uid=10123)
            probe.motion_publication_gate = None; probe.ui_nodes = nodes; probe.draft_evidence = {}
            probe.process_identity = Mock(); probe.click = Mock()
            probe.require_motion_publication_gate_held = Mock(); probe.require_one_motion_publication = Mock()
            probe.shell.side_effect = [("100", 0), ("", 0, "")]
            probe.call.return_value = module.json.dumps(self.motion_publication_gate_frame()), 0, ""
            probe.find = Mock(return_value=cancel)
            probe.setup_motion_publication_gate()
            probe.find.assert_called_once_with("resource-id", "motion-screen", scroll=False)
            probe.click.assert_called_once_with("motion-export-jpeg")
            probe.require_motion_publication_gate_held.assert_called_once_with()
            probe.require_one_motion_publication.assert_called_once_with("before-process-death")

    def test_motion_publication_saved_result_below_scroll_and_no_enabled_exports_frame(self):
        probe = self.probe(); probe.motion_publication_kind = "Frame"; probe.ui_nodes = []
        probe.find = Mock(return_value=module.ET.Element("node", enabled="true")); probe.click = Mock()
        probe.motion_result_ready()
        probe.find.assert_any_call("resource-id", "motion-exported", timeout=30, scroll=True)
        self.assertEqual(["motion-exported", "motion-open", "motion-share"], [c.args[1] for c in probe.find.call_args_list])
        probe.click.assert_not_called()
        probe.ui_nodes = [module.ET.Element("node", package=module.PACKAGE, enabled="true", **{"resource-id":"motion-export-jpeg"})]
        with self.assertRaises(RuntimeError): probe.motion_result_ready()

    def test_motion_publication_seed_uses_new_native_class_without_changing_shared_manual_seed_frame(self):
        probe = self.manual_probe(); probe.motion_publication_kind = "Frame"; probe.scenario = "motion-frame-publication"
        probe.args = SimpleNamespace(serial="emulator-5554"); probe.threads = []; probe.stdout = []; probe.stderr = []
        with patch.object(module.subprocess, "Popen", return_value=Mock(stdout=[], stderr=[])), patch.object(module.threading, "Thread"):
            probe.start_native()
        self.assertIn(module.MOTION_PUBLICATION_SEED, probe.native_command[-1])
        self.assertIn("motionPublicationKind Frame", probe.native_command[-1])
        self.assertNotIn("armManualCommitGap", probe.native_command[-1])

    def motion_publication_gate_clip(self):
        return dict(fixtureUuid="owned", publicationId="00000000-0000-4000-8000-000000000001", token="00000000-0000-4000-8000-000000000002",
            phase="AFTER_MEDIASTORE_COMMIT", pid=100, uid=10123, deadlineElapsedRealtimeMillis=5000, renderSha256="a"*64, renderSizeBytes=128,
            destination=dict(uri="content://media/external/video/media/30", ownerPackage=module.PACKAGE,
                displayName="UGallery-Motion-00000000-0000-4000-8000-000000000002.mp4", relativePath=module.MOTION_VIDEO_PATH,
                mimeType="video/mp4", generationAdded=10, generationModified=12, sizeBytes=128, pending=False, trashed=False))

    def test_motion_publication_gate_requires_exact_owned_published_output_and_process_clip(self):
        gate = self.motion_publication_gate_clip()
        self.assertEqual(gate, module.verify_motion_publication_gate(module.json.dumps(gate), "owned", 100, 10123, "Clip"))
        for field, value in (("fixtureUuid", "other"), ("publicationId", "bad"), ("phase", "BEFORE_COMMIT"), ("pid", 101),
                             ("token", 4), ("renderSha256", "bad"), ("renderSizeBytes", 129), ("deadlineElapsedRealtimeMillis", 0)):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_motion_publication_gate(module.json.dumps(dict(gate, **{field:value})), "owned", 100, 10123, "Clip")
        for field, value in (("ownerPackage", "other"), ("relativePath", "Pictures/other/"), ("pending", True), ("trashed", True),
                             ("generationAdded", -1), ("generationModified", 9), ("generationModified", 12.0), ("sizeBytes", 129), ("mimeType", "image/jpeg"), ("displayName", "UGallery-Motion-00000000-0000-4000-8000-000000000003.mp4")):
            changed = dict(gate, destination=dict(gate["destination"], **{field:value}))
            with self.subTest(destination=field), self.assertRaises(RuntimeError):
                module.verify_motion_publication_gate(module.json.dumps(changed), "owned", 100, 10123, "Clip")

    def test_motion_publication_inventory_exact_target_and_other_collection_unchanged_clip(self):
        probe = self.probe(); probe.motion_publication_kind = "Clip"
        probe.motion_publication_gate = self.motion_publication_gate_clip()
        row = module.motion_publication_destination_row(probe.motion_publication_gate["destination"], "Clip")
        expected = tuple(str(row[field]) for field in module.FIELDS)
        other = module.MOTION_VIDEO_PATH if "Clip" == "Frame" else module.MOTION_IMAGE_PATH
        probe.motion_output_baseline = {module.MOTION_IMAGE_PATH: (), module.MOTION_VIDEO_PATH: ()}
        probe.creation_outputs = Mock(side_effect=lambda path: (expected,) if path == module.MOTION_VIDEO_PATH else ())
        probe.require_one_motion_publication("restored")
        probe.creation_outputs.side_effect = lambda path: (expected,)
        with self.assertRaises(RuntimeError): probe.require_one_motion_publication("extra-other-collection")
        probe.shell.assert_not_called()

    def motion_handoff_receipt_clip(self, action="android.intent.action.VIEW"):
        gate = self.motion_publication_gate_clip(); uri = gate["destination"]["uri"]
        return dict(fixtureUuid="owned", publicationId=gate["publicationId"], token=gate["token"], pid=200, uid=10123,
            phase="DISPATCHED", action=action, uri=uri, mimeType="video/mp4", targetReadGrant=True, targetClipUris=[uri],
            chooserReadGrant=True, chooserClipUris=[uri])

    def test_motion_publication_handoff_receipt_binds_real_action_uri_mime_clipdata_and_new_process_clip(self):
        gate = self.motion_publication_gate_clip()
        for action in ("android.intent.action.VIEW", "android.intent.action.SEND"):
            receipt = self.motion_handoff_receipt_clip(action)
            self.assertEqual(receipt, module.verify_motion_handoff_receipt(module.json.dumps(receipt), "owned", gate, action, 200, 10123, "Clip"))
            for field, value in (("fixtureUuid", "other"), ("token", "other"), ("pid", 100), ("uid", 10124), ("phase", "PREPARED"),
                                 ("action", "android.intent.action.CHOOSER"), ("uri", receipt["uri"]+"1"), ("mimeType", "image/jpeg"),
                                 ("targetReadGrant", 1), ("chooserReadGrant", False), ("targetClipUris", []),
                                 ("chooserClipUris", [receipt["uri"], receipt["uri"]])):
                with self.subTest(action=action, field=field), self.assertRaises(RuntimeError):
                    module.verify_motion_handoff_receipt(module.json.dumps(dict(receipt, **{field:value})), "owned", gate, action, 200, 10123, "Clip")
            for output in ("", "[]", module.json.dumps(dict(receipt, extra=True))):
                with self.assertRaises(RuntimeError): module.verify_motion_handoff_receipt(output, "owned", gate, action, 200, 10123, "Clip")

    def test_motion_publication_chooser_dump_proves_resumption_not_hidden_target_intent_clip(self):
        # Android36.1 Intent.toString hides EXTRA_INTENT and redacts ClipData; this is not an action/URI receipt.
        redacted = "mResumedActivity: ActivityRecord android/.ChooserActivity\nIntent { act=android.intent.action.CHOOSER clip={video/mp4 U(content)} (has extras) }"
        module.require_motion_handoff_chooser(redacted)
        for output in ("ACTIVITY android/.ChooserActivity", redacted.replace("mResumedActivity", "mLastPausedActivity"),
                       "mResumedActivity: ActivityRecord com.ugallery.app/.MainActivity"):
            with self.assertRaises(RuntimeError): module.require_motion_handoff_chooser(output)

    def test_motion_publication_handoff_requires_absence_then_fresh_receipt_plus_resumed_chooser_clip(self):
        for failure in (None, "stale", "missing-receipt", "not-resumed", "wrong-action"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = self.probe(); probe.motion_publication_kind = "Clip"; probe.out = Path(directory); probe.fixture = "owned"; probe.new_pid = 200
                probe.manifest = {"uid":10123}; probe.motion_publication_gate = self.motion_publication_gate_clip()
                probe.process_identity = Mock(); probe.click = Mock(); probe.motion_result_ready = Mock()
                probe.require_one_motion_publication = Mock()
                receipt = self.motion_handoff_receipt_clip("android.intent.action.SEND" if failure == "wrong-action" else "android.intent.action.VIEW")
                probe.call.return_value = module.json.dumps(receipt), 0, ""
                def shell(*args, **kwargs):
                    if args[:4] == ("run-as", module.PACKAGE, "test", "!"):
                        return "", 1 if failure == "stale" else 0, ""
                    if args[:4] == ("run-as", module.PACKAGE, "test", "-f"):
                        return "", 1 if failure == "missing-receipt" else 0, ""
                    if args[0] == "dumpsys":
                        return ("mResumedActivity: ActivityRecord android/." + ("MainActivity" if failure == "not-resumed" else "ChooserActivity"), 0)
                    return "", 0
                probe.shell.side_effect = shell
                with patch.object(module.time, "monotonic", side_effect=[0, 0, 16]), patch.object(module.time, "sleep"):
                    if failure:
                        with self.assertRaises(RuntimeError): probe.motion_result_handoff("motion-open", "android.intent.action.VIEW")
                        probe.motion_result_ready.assert_not_called()
                    else:
                        probe.motion_result_handoff("motion-open", "android.intent.action.VIEW")
                        probe.motion_result_ready.assert_called_once_with()
                        probe.require_one_motion_publication.assert_called_once_with("after-view")
                        self.assertEqual(receipt, module.json.loads((probe.out/"motion-publication-handoff-VIEW.json").read_text()))
                if failure == "stale": probe.click.assert_not_called()
                else: probe.click.assert_called_once_with("motion-open")


    def test_motion_publication_receipt_requires_proof_and_all_cleanup_before_sources_clip(self):
        gate = self.motion_publication_gate_clip()
        receipt = dict(fixtureUuid="owned",publicationId=gate["publicationId"],token=gate["token"],outputUri=gate["destination"]["uri"],
            outputSha256=gate["renderSha256"],sourcesCurrent=True,outputVerified=True,outputCountOne=True,publicationRetired=True,
            outputCleaned=True,gateCleaned=True,status="PASS")
        self.assertEqual(receipt, module.verify_motion_publication_receipt(module.json.dumps(receipt), "owned", gate))
        for field in ("sourcesCurrent", "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned"):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.verify_motion_publication_receipt(module.json.dumps(dict(receipt, **{field:False})), "owned", gate)
        for output in ("[]", "", module.json.dumps(dict(receipt, extra=1))):
            with self.assertRaises(RuntimeError): module.verify_motion_publication_receipt(output, "owned", gate)
        probe = self.probe(); probe.motion_publication_kind = "Clip"; probe.scenario = "motion-clip-publication"; probe.motion_publication_verified = False
        probe.read_manifest = Mock()
        with self.assertRaisesRegex(RuntimeError, "native predelete"):
            probe.cleanup()
        probe.read_manifest.assert_not_called()

    def test_motion_publication_restoration_opens_and_shares_existing_result_without_export_clip(self):
        probe = self.probe(); probe.motion_publication_kind = "Clip"
        probe.manifest = {"rows": [self.row()]}
        probe.motion_result_ready = Mock(); probe.require_one_motion_publication = Mock()
        probe.find = Mock(return_value=module.ET.Element("node", checked="true")); probe.motion_result_handoff = Mock()
        probe.source_hash = Mock(return_value=self.row()["sha256"])
        probe.click = Mock()
        probe.verify_motion_publication_restored()
        self.assertEqual([("motion-open", "android.intent.action.VIEW"), ("motion-share", "android.intent.action.SEND")],
                         [c.args for c in probe.motion_result_handoff.call_args_list])
        probe.click.assert_not_called()
        self.assertTrue(probe.no_auto_export_verified)

    def test_motion_publication_execute_requires_gate_death_handoffs_native_output_cleanup_then_source_cas_clip(self):
        for failure in (None, "ui", "native"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = module.Probe(SimpleNamespace(scenario="motion-clip-publication", evidence=str(Path(directory)/"run"),
                    serial="127.0.0.1:5563", apk_sha256="a"*64, test_apk_sha256="b"*64))
                probe.manifest = dict(state="SEEDED", rows=self.manual_rows()[:1])
                probe.stdout = ["OK (1 test)"]; probe.process = Mock(returncode=0); probe.process.poll.return_value = 0
                for name in ("guard_device", "guard_observer", "apk", "start_native", "native_finished", "kill_owned", "launch_normal", "process_identity"):
                    setattr(probe, name, Mock())
                probe.read_manifest = Mock(return_value=probe.manifest)
                probe.shell = Mock(side_effect=lambda *args, **kwargs: (
                    module.RUNNER + " (target=" + module.PACKAGE + ")" if args[:3] == ("pm", "list", "instrumentation")
                    else "200" if args[0] == "pidof" else "", 0))
                probe.task_id = 8; probe.old_pid = 100
                probe.task_ids = Mock(return_value={8}); probe.setup_normal_app = Mock()
                probe.motion_publication_gate = self.motion_publication_gate_clip(); probe.motion_export_attempted = True
                probe.require_motion_publication_gate_held = Mock()
                order = []
                def restored():
                    order.append("restored-result-open-share-sources")
                    if failure == "ui": raise RuntimeError("Handoff mismatch")
                probe.verify_motion_publication_restored = Mock(side_effect=restored)
                def verify():
                    self.assertTrue(probe.ui_complete)
                    order.append("native-proof-retire-output-cas")
                    if failure == "native": raise RuntimeError("Output changed; retain sources")
                    probe.motion_publication_verified = True
                probe.verify_motion_publication_before_source_cleanup = Mock(side_effect=verify)
                def cleanup():
                    self.assertTrue(probe.motion_publication_verified)
                    order.append("sources-cas"); probe.cleanup_complete = True
                probe.cleanup = Mock(side_effect=cleanup); probe.call = Mock(return_value=(b"diagnostic",0))
                with patch("builtins.print"): status = probe.execute()
                self.assertEqual(0 if failure is None else 1, status)
                self.assertEqual(["restored-result-open-share-sources"] + ([] if failure == "ui" else ["native-proof-retire-output-cas"]) +
                                 (["sources-cas"] if failure is None else []), order)
                probe.kill_owned.assert_called_once_with(100); probe.process_identity.assert_called_once_with(200)
                probe.require_motion_publication_gate_held.assert_called_once_with()
                result = module.json.loads((probe.out/"result.json").read_text())
                self.assertTrue(result["exportExecuted"])
                self.assertEqual(failure is None, result["publicationInterruptionVerified"])
                self.assertFalse(result["recipientRenderingVerified"])
                if failure: probe.cleanup.assert_not_called()

    def test_motion_publication_postcommit_cancel_is_revealed_without_a_second_export_or_cancel_tap_clip(self):
        # GIF synthetic layout derived from accepted Collage XML record210: Cancel[44,2175][230,2307], ScrollView[0,321][1080,2208].
        # The Export tap540,2098 and AFTER_MEDIASTORE_COMMIT receipt preceded this clipped control.
        package = module.PACKAGE
        xml = f'<node package="{package}" bounds="[0,0][1080,2340]"><node package="{package}" class="android.widget.ScrollView" scrollable="true" bounds="[0,321][1080,2208]"><node package="{package}" resource-id="motion-working" bounds="[44,2175][230,2307]" /></node></node>'
        root = module.ET.fromstring(xml); nodes = list(root.iter("node")); cancel = nodes[-1]
        self.assertFalse(module.center_visible(cancel, nodes))
        with tempfile.TemporaryDirectory() as directory:
            probe = self.probe(); probe.motion_publication_kind = "Clip"; probe.out = Path(directory); probe.fixture = "owned"
            probe.manifest = dict(armMotionPublication=True, motionPublicationKind="Clip", uid=10123)
            probe.motion_publication_gate = None; probe.ui_nodes = nodes; probe.draft_evidence = {}
            probe.process_identity = Mock(); probe.click = Mock()
            probe.require_motion_publication_gate_held = Mock(); probe.require_one_motion_publication = Mock()
            probe.shell.side_effect = [("100", 0), ("", 0, "")]
            probe.call.return_value = module.json.dumps(self.motion_publication_gate_clip()), 0, ""
            probe.find = Mock(return_value=cancel)
            probe.setup_motion_publication_gate()
            probe.find.assert_called_once_with("resource-id", "motion-screen", scroll=False)
            probe.click.assert_called_once_with("motion-export-mp4")
            probe.require_motion_publication_gate_held.assert_called_once_with()
            probe.require_one_motion_publication.assert_called_once_with("before-process-death")

    def test_motion_publication_saved_result_below_scroll_and_no_enabled_exports_clip(self):
        probe = self.probe(); probe.motion_publication_kind = "Clip"; probe.ui_nodes = []
        probe.find = Mock(return_value=module.ET.Element("node", enabled="true")); probe.click = Mock()
        probe.motion_result_ready()
        probe.find.assert_any_call("resource-id", "motion-exported", timeout=30, scroll=True)
        self.assertEqual(["motion-exported", "motion-open", "motion-share"], [c.args[1] for c in probe.find.call_args_list])
        probe.click.assert_not_called()
        probe.ui_nodes = [module.ET.Element("node", package=module.PACKAGE, enabled="true", **{"resource-id":"motion-export-jpeg"})]
        with self.assertRaises(RuntimeError): probe.motion_result_ready()

    def test_motion_publication_seed_uses_new_native_class_without_changing_shared_manual_seed_clip(self):
        probe = self.manual_probe(); probe.motion_publication_kind = "Clip"; probe.scenario = "motion-clip-publication"
        probe.args = SimpleNamespace(serial="emulator-5554"); probe.threads = []; probe.stdout = []; probe.stderr = []
        with patch.object(module.subprocess, "Popen", return_value=Mock(stdout=[], stderr=[])), patch.object(module.threading, "Thread"):
            probe.start_native()
        self.assertIn(module.MOTION_PUBLICATION_SEED, probe.native_command[-1])
        self.assertIn("motionPublicationKind Clip", probe.native_command[-1])
        self.assertNotIn("armManualCommitGap", probe.native_command[-1])

    def test_query_denial_is_not_an_absent_row(self):
        self.assertIsNone(module.parse_row("No result found.\n"))
        for output in ("Error: java.lang.SecurityException", "No result found.\nError: denied", ""):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                module.parse_row(output)
        row = self.row()
        output = "Row: 0 " + ", ".join(field + "=" + str(row[field]) for field in module.FIELDS)
        self.assertEqual(str(row["_id"]), module.parse_row(output)["_id"])

    def test_changed_or_denied_source_is_retained_without_delete(self):
        for reason in ("changed", "denied"):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as directory:
                probe = self.probe()
                probe.out = Path(directory)
                row = self.row()
                probe.read_manifest = Mock(return_value={"rows": [row]})
                current = {field: str(row[field]) for field in module.FIELDS}
                current["generation_modified"] = "9"
                probe.current_row = Mock(return_value=current)
                if reason == "denied":
                    probe.current_row.side_effect = RuntimeError("query denied")
                probe.cleanup()
                self.assertFalse(probe.cleanup_complete)
                probe.shell.assert_not_called()
                self.assertIn(row["uri"], (probe.out / "cleanup.json").read_text())

    def test_reused_pid_blocks_kill(self):
        probe = self.probe()
        probe.process_identity = Mock(side_effect=["100", "101"])
        with self.assertRaisesRegex(RuntimeError, "reused"):
            probe.kill_owned(4321)
        probe.shell.assert_not_called()

    def test_host_observer_never_competes_with_native(self):
        probe = self.probe()
        probe.accessibility_owner = "native"
        with self.assertRaises(RuntimeError):
            probe.tree()
        probe.shell.assert_not_called()
        probe.call.assert_not_called()

    def png(self):
        output = io.BytesIO()
        with Image.new("RGB", (80, 80), "red") as image:
            image.save(output, format="PNG")
        return output.getvalue()

    def test_terminal_observer_requires_valid_remote_status(self):
        probe = self.probe()
        probe.shell.return_value = "UGALLERY_PROC_STATUS:0\n", 0
        self.assertTrue(probe.proc_exists(4321))
        probe.shell.return_value = "UGALLERY_PROC_STATUS:1\n", 1
        self.assertFalse(probe.proc_exists(4321))
        for output, code in (("cat: /proc/4321/stat: No such file or directory", 0),
                             ("run-as: Permission denied", 1), ("", 124),
                             ("UGALLERY_PROC_STATUS:1\n", 0)):
            with self.subTest(output=output):
                probe.shell.return_value = output, code
                with self.assertRaises(RuntimeError):
                    probe.proc_exists(4321)
        probe.call.assert_not_called()

    def test_terminal_wait_timeout_is_not_death_and_does_not_rekill(self):
        probe = self.probe()
        probe.proc_exists = Mock(return_value=True)
        with self.assertRaisesRegex(RuntimeError, "still alive"):
            probe.await_owned_terminal(4321, timeout=0)
        probe.shell.assert_not_called()
        probe.record.assert_not_called()

    def test_reentering_kill_only_waits_after_first_signal(self):
        probe = self.probe()
        probe.process_identity = Mock(return_value="100")
        probe.proc_exists = Mock(side_effect=[True, False, False])
        probe.kill_owned(4321)
        probe.kill_owned(4321)
        probe.shell.assert_called_once_with("run-as", module.PACKAGE, "kill", "-9", "4321")
        self.assertEqual(2, probe.process_identity.call_count)

    def test_api30_uses_background_manager_kill_once_never_force_stop(self):
        probe = self.probe()
        probe.args.serial = "emulator-5554"
        probe.process_identity = Mock(return_value="100")
        probe.proc_exists = Mock(side_effect=[True, False, False])
        probe.kill_owned(4321)
        probe.kill_owned(4321)
        probe.shell.assert_called_once_with("am", "kill", "--user", "0", module.PACKAGE)
        self.assertEqual(2, probe.process_identity.call_count)

    def test_provider_png_hash_replaces_fuse_read(self):
        probe = self.probe()
        data = self.png()
        probe.call.return_value = data, 0
        self.assertEqual(hashlib.sha256(data).hexdigest(), probe.source_hash(self.row()))
        probe.call.assert_called_once_with("exec-out", "content", "read", "--user", "0", "--uri", self.row()["uri"], binary=True)
        probe.shell.assert_not_called()
        for data in (b"Permission denied", b"", data + b"java.lang.SecurityException"):
            with self.subTest(data=data[:20]):
                probe.call.return_value = data, 0
                with self.assertRaises(RuntimeError):
                    probe.source_hash(self.row())

    def test_api30_hash_reads_only_exact_journaled_public_path(self):
        probe = self.probe()
        probe.args.serial = "emulator-5554"
        data = self.png()
        probe.call.return_value = data, 0
        self.assertEqual(hashlib.sha256(data).hexdigest(), probe.source_hash(self.row()))
        probe.call.assert_called_once_with("exec-out", "cat", self.row()["_data"], binary=True)
        probe.call.reset_mock()
        row = self.row()
        row["_data"] = "/sdcard/someone-else.png"
        with self.assertRaises(RuntimeError):
            probe.source_hash(row)
        probe.call.assert_not_called()

    def test_cleanup_deletes_only_matching_provider_verified_png(self):
        probe = self.probe()
        row = self.row()
        data = self.png()
        row["sha256"] = hashlib.sha256(data).hexdigest()
        probe.read_manifest = Mock(return_value={"rows": [row]})
        probe.current_row = Mock(side_effect=[{field: str(row[field]) for field in module.FIELDS}, None])
        probe.call.return_value = data, 0
        with tempfile.TemporaryDirectory() as directory:
            probe.out = Path(directory)
            probe.cleanup()
            self.assertTrue(probe.cleanup_complete)
            self.assertIn("content", probe.shell.call_args.args)
            self.assertIn("delete", probe.shell.call_args.args)
            self.assertIn(row["uri"], probe.shell.call_args.args)
            self.assertEqual(1, probe.shell.call_count)
            self.assertIn("generation_modified=8", probe.shell.call_args.args[-1])

    def observer(self):
        probe = self.probe()
        probe.native_completed = True
        probe.accessibility_owner = "host"
        probe.shell.side_effect = lambda *args, **kwargs: ("UI hierchary dumped to: " + args[2] + "\n", 0, "")
        probe.call.return_value = '<hierarchy><node text="ready"/></hierarchy>', 0, ""
        return probe

    def test_host_observer_after_seed_before_death_is_allowed(self):
        probe = self.observer()
        with tempfile.TemporaryDirectory() as directory:
            probe.out = Path(directory)
            self.assertEqual("ready", probe.tree()[0].get("text"))
            self.assertEqual(probe.call.return_value[0], (probe.out / "last-ui.xml").read_text())
        self.assertFalse(probe.death_confirmed)
        self.assertEqual(probe.dumps[0], probe.call.call_args.args[2])

    def test_null_root_retries_only_terminal_dump_with_fresh_path(self):
        probe = self.observer()
        success = probe.shell.side_effect
        probe.shell.side_effect = lambda *args, **kwargs: (
            ("", 0, "ERROR: null root node returned by UiTestAutomationBridge.\n")
            if probe.shell.call_count == 1 else success(*args, **kwargs))
        with tempfile.TemporaryDirectory() as directory, patch.object(module.time, "sleep") as sleep:
            probe.out = Path(directory)
            self.assertEqual(1, len(probe.tree()))
        self.assertEqual(2, probe.shell.call_count)
        self.assertEqual(1, probe.call.call_count)
        self.assertEqual(2, len(set(probe.dumps)))
        self.assertEqual(probe.dumps[1], probe.call.call_args.args[2])
        sleep.assert_called_once_with(.5)
        self.assertEqual({}, probe.kill_requests)
        self.assertFalse(probe.death_confirmed)

    def test_null_root_wait_is_bounded_and_never_reads_stale_xml(self):
        probe = self.observer()
        probe.shell.side_effect = None
        probe.shell.return_value = "", 0, "ERROR: null root node returned by UiTestAutomationBridge.\n"
        with patch.object(module.time, "sleep"), self.assertRaisesRegex(RuntimeError, "null-root persisted"):
            probe.tree()
        self.assertEqual(2, probe.shell.call_count)
        probe.call.assert_not_called()
        with self.assertRaisesRegex(RuntimeError, "budget exhausted"):
            probe.tree(timeout=0)
        self.assertEqual(2, probe.shell.call_count)

    def test_dump_unknown_failure_is_not_retried_or_read(self):
        for result in (("", 0, "Unknown accessibility error"), ("", 0, ""),
                       ("UI hierchary dumped to: /stale.xml", 0, ""),
                       ("", 1, "ERROR: null root node returned by UiTestAutomationBridge.")):
            probe = self.observer()
            probe.shell.side_effect = None
            probe.shell.return_value = result
            with self.subTest(result=result), self.assertRaisesRegex(RuntimeError, "success unconfirmed"):
                probe.tree()
            self.assertEqual(1, probe.shell.call_count)
            probe.call.assert_not_called()

    def test_missing_xml_even_with_exit_zero_is_observer_failure(self):
        for result in (("cat: /sdcard/file.xml: No such file or directory\n", 0, ""),
                       ("", 0, ""), ("<hierarchy><node/></hierarchy>", 0, "Permission denied")):
            probe = self.observer()
            probe.call.return_value = result
            with self.subTest(result=result), self.assertRaisesRegex(RuntimeError, "XML unavailable"):
                probe.tree()
            self.assertEqual(1, probe.shell.call_count)

    def test_invalid_or_empty_hierarchy_is_not_accepted(self):
        for xml, reason in (("<hierarchy><node>", "invalid XML"),
                            ("<hierarchy/>", "no usable hierarchy"),
                            ("<error><node/></error>", "no usable hierarchy")):
            probe = self.observer()
            probe.call.return_value = xml, 0, ""
            with self.subTest(xml=xml), self.assertRaisesRegex(RuntimeError, reason):
                probe.tree()
            self.assertEqual(1, probe.shell.call_count)

    def test_each_successful_tree_uses_a_new_exact_dump_path(self):
        probe = self.observer()
        with tempfile.TemporaryDirectory() as directory:
            probe.out = Path(directory)
            probe.tree()
            probe.tree()
        self.assertEqual(2, len(set(probe.dumps)))
        self.assertTrue(all(path.startswith("/sdcard/" + probe.name + "-") for path in probe.dumps))
        self.assertEqual(probe.dumps, [call.args[2] for call in probe.call.call_args_list])

    def test_call_can_expose_stderr_without_changing_existing_pair_contract(self):
        probe = self.probe()
        probe.args = SimpleNamespace(serial="mock-serial")
        probe.phase = "host-test"
        result = SimpleNamespace(stdout=b"", stderr=b"ERROR: null root node returned by UiTestAutomationBridge.\n", returncode=0)
        with patch.object(module.subprocess, "run", return_value=result):
            self.assertEqual(("", 0), module.Probe.call(probe, "shell", "mock"))
            self.assertEqual(("", 0, result.stderr.decode()),
                             module.Probe.call(probe, "shell", "mock", include_stderr=True))

    def clipped_nodes(self, target="[40,90][60,130]", viewport="[0,0][100,100]", checked="false"):
        root = module.ET.fromstring(
            '<node bounds="[0,0][200,200]" package="' + module.PACKAGE + '">'
            '<node scrollable="true" bounds="' + viewport + '" package="' + module.PACKAGE + '">'
            '<node resource-id="seconds-4" enabled="true" checkable="true" selected="false" checked="' + checked
            + '" bounds="' + target + '" package="' + module.PACKAGE + '"/></node></node>')
        return list(root.iter("node"))

    def test_scroll_ancestor_clips_exported_child_bounds(self):
        nodes = self.clipped_nodes()
        self.assertEqual((40, 90, 60, 100), module.visible_bounds(nodes[-1], nodes))
        self.assertFalse(module.center_visible(nodes[-1], nodes))
        # Both the nested viewport and its outer scroll clip contribute.
        outer = module.ET.Element("node", {"bounds": "[0,0][50,95]", "scrollable": "true"})
        outer.append(nodes[0])
        self.assertEqual((40, 90, 50, 95), module.visible_bounds(nodes[-1], [outer, *nodes]))

    def test_candidate_scroll_direction_and_swipe_are_inside_viewport(self):
        for target, expected in (("[40,90][60,130]", (50,75,50,25)),
                                 ("[40,0][60,20]", (50,40,50,80)),
                                 ("[90,40][130,60]", (75,50,25,50)),
                                 ("[0,40][20,60]", (40,50,80,50))):
            viewport = "[0,20][100,100]" if expected == (50,40,50,80) else ("[20,0][100,100]" if expected == (40,50,80,50) else "[0,0][100,100]")
            probe = self.probe()
            probe.ui_nodes = self.clipped_nodes(target, viewport)
            with self.subTest(target=target):
                probe.scroll_towards(probe.ui_nodes[-1])
                probe.shell.assert_called_once_with("input", "swipe", *expected, "250")

    def test_find_scrolls_clipped_candidate_then_reacquires_before_tap(self):
        probe = self.probe()
        before, after = self.clipped_nodes(), self.clipped_nodes("[40,40][60,60]")
        probe.manifest = {}
        probe.tree = Mock(side_effect=[before, after])
        with patch.object(module.time, "sleep"):
            probe.click("seconds-4")
        self.assertEqual(2, probe.tree.call_count)
        self.assertEqual(("input", "swipe", 50,75,50,25,"250"), probe.shell.call_args_list[0].args)
        self.assertEqual(("input", "tap",50,50), probe.shell.call_args_list[1].args)

    def test_tap_reacquires_clipped_target_and_rejects_old_hierarchy(self):
        probe = self.probe()
        before, after = self.clipped_nodes(), self.clipped_nodes("[40,40][60,60]")
        probe.ui_nodes = before
        probe.manifest = {}
        probe.tree = Mock(side_effect=[before, after])
        with patch.object(module.time, "sleep"):
            probe.tap(before[-1])
        self.assertEqual(("input", "tap",50,50), probe.shell.call_args.args)
        with self.assertRaisesRegex(RuntimeError, "current observed hierarchy"):
            probe.tap(before[-1])

    def test_visible_unchecked_candidate_waits_without_scrolling(self):
        probe = self.probe()
        before = self.clipped_nodes("[40,40][60,60]")
        after = self.clipped_nodes("[40,40][60,60]", checked="true")
        probe.manifest = {}
        probe.tree = Mock(side_effect=[before, after])
        with patch.object(module.time, "sleep"):
            self.assertIs(after[-1], probe.find("resource-id", "seconds-4", checked=True))
        probe.shell.assert_not_called()
        self.assertEqual(2, probe.tree.call_count)

    def test_unchecked_state_timeout_is_not_missing_or_a_scroll(self):
        probe = self.probe()
        probe.manifest = {}
        probe.tree = Mock(return_value=self.clipped_nodes("[40,40][60,60]"))
        with patch.object(module.time, "monotonic", side_effect=[0, 0, 19]), patch.object(module.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "present but checked state"):
                probe.find("resource-id", "seconds-4", checked=True)
        probe.shell.assert_not_called()
        self.assertEqual(1, probe.tree.call_count)

    def test_selection_uses_exact_checkable_checked_tile_not_selected_attribute(self):
        node = self.clipped_nodes(checked="true")[-1]
        module.require_selected_tile(node, "seconds-4")  # selected=false is correct Compose semantics.
        for field, value in (("checked", "false"), ("checkable", "false"),
                             ("resource-id", "thumbnail"), ("package", "other.app")):
            wrong = module.ET.Element("node", dict(node.attrib))
            wrong.set(field, value)
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                module.require_selected_tile(wrong, "seconds-4")

    def test_saved_state_belongs_to_exact_stopped_task(self):
        block = """  * Task{fixture #648}
    * Hist #0: ActivityRecord{fixture u0 ACTIVITY t648}
      mHaveState=true mIcicle=Bundle[mParcelledData.dataSize=12284]
      state=STOPPED finishing=false
  * Task{other #540}
    * Hist #0: ActivityRecord{other u0 unrelated/.Activity t540}
      mHaveState=true
      state=STOPPED finishing=false
""".replace("ACTIVITY", module.ACTIVITY)
        self.assertTrue(module.saved_stopped_activity(block, 648)[0])
        self.assertFalse(module.saved_stopped_activity(block.replace("mHaveState=true", "mHaveState=false", 1), 648)[0])
        self.assertFalse(module.saved_stopped_activity(block.replace("state=STOPPED", "state=RESUMED", 1), 648)[0])
        with self.assertRaises(RuntimeError):
            module.saved_stopped_activity(block, 999)

    def process_dump(self, active=False):
        # Identity/field spelling copied from root's real API35 inactive and API30 active captures.
        identity = ("10253", "30129", "b7ecc13", "u0a253") if active else ("10215", "28429", "1e77ba2", "u0a215")
        uid, pid, token, user = identity
        record = ("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n"
                  "  All known processes:\n"
                  f"  *APP* UID {uid} ProcessRecord{{{token} {pid}:" + module.PACKAGE + f"/{user}}}\n"
                  "    packageList={" + module.PACKAGE + "}\n")
        if active:
            record += "    mInstr=ActiveInstrumentation{24c7b80 {" + module.TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner} 1 procs}\n"
        record += f"    pid={pid}" + (" starting=false" if active else "") + "\n"
        record += "  *PERS* UID 1000 ProcessRecord{cdb5a2d 561:system/1000}\n    packageList={android}\n"
        return record + "  mProcessesReady=true mSystemReady=true mBooted=true mFactoryTest=0\n"

    def test_active_or_unknown_instrumentation_state_blocks_measurement(self):
        probe = self.probe()
        probe.old_pid = 28429
        probe.manifest = {"uid": 10215}
        probe.shell.return_value = self.process_dump(), 0
        probe.require_no_instrumentation()
        probe.shell.assert_called_with("dumpsys", "activity", "processes")
        self.assertEqual(28429, module.uninstrumented_process(self.process_dump())["pid"])
        for output in ("Unknown command instrumentation", self.process_dump(active=True),
                       self.process_dump().replace("mProcessesReady=true", "")):
            with self.subTest(output=output[:80]), self.assertRaises(RuntimeError):
                module.uninstrumented_process(output)

    def test_process_dump_scopes_record_and_checks_expected_identity(self):
        dump = self.process_dump().replace("    packageList={android}",
               "    packageList={android}\n    mInstr=ActiveInstrumentation{other {unrelated.test/Runner} 1 procs}")
        self.assertEqual(28429, module.uninstrumented_process(dump, 28429, 10215)["pid"])
        for pid, uid in ((99, 10215), (28429, 99)):
            with self.subTest(pid=pid, uid=uid), self.assertRaises(RuntimeError):
                module.uninstrumented_process(dump, pid, uid)
        missing = dump.replace(":" + module.PACKAGE + "/", ":other.process/")
        self.assertIsNone(module.uninstrumented_process(missing))
        with self.assertRaises(RuntimeError):
            module.uninstrumented_process(missing, 28429)

    def test_source_uri_and_path_are_exact(self):
        probe = self.probe()
        probe.validate_source(self.row())
        for field, value in (("uri", "content://media/external/images/media/22"),
                             ("owner_package_name", "another.package"),
                             ("_data", "/storage/emulated/0/Pictures/unrelated.png")):
            row = self.row()
            row[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                probe.validate_source(row)

    def collage_node(self, tag="creation-collage-zoom-value", description="Zoom: 2"):
        return module.ET.Element("node", {"package": module.PACKAGE, "resource-id": tag,
                   "text": description, "content-desc": "", "enabled": "true", "checked": "true",
                   "bounds": "[0,0][200,200]"})

    def test_collage_crop_is_a_numeric_localized_observable_not_static_label(self):
        for description in ("Zoom: 2", "Agrandissement: 1,925", "تكبير: ٢٫١٥"):
            self.assertEqual(description, module.collage_crop_description(self.collage_node(description=description)))
        for description in ("Zoom", "Zoom2", "Zoom: NaN", "Zoom: 0", "Zoom: 3.1"):
            with self.subTest(description=description), self.assertRaises(RuntimeError):
                module.collage_crop_description(self.collage_node(description=description))
        for attribute, value in (("package", "other"),
                                 ("resource-id", "creation-collage-horizontal")):
            node = self.collage_node()
            node.set(attribute, value)
            with self.subTest(attribute=attribute), self.assertRaises(RuntimeError):
                module.collage_crop_description(node)

    def test_collage_output_snapshot_is_owned_pending_inclusive_and_strict(self):
        row = self.row()
        row.update(relative_path=module.COLLAGE_OUTPUT_PATH, is_pending=1)
        output = "Row: 0 " + ", ".join(field + "=" + str(row[field]) for field in module.FIELDS)
        snapshot = module.parse_collage_outputs(output + "\n")
        self.assertEqual("1", snapshot[0][-1])
        self.assertEqual((), module.parse_collage_outputs("No result found.\n"))
        for bad in ("", "Error: permission denied", "No result found.\nError: denied",
                    output + "\nError: denied", output.replace(module.PACKAGE, "other"),
                    output.replace(module.COLLAGE_OUTPUT_PATH, "Pictures/other/"),
                    output + "\n" + output.replace("Row: 0", "Row: 1")):
            with self.subTest(output=bad), self.assertRaises(RuntimeError):
                module.parse_collage_outputs(bad)
        probe = self.probe()
        probe.shell.return_value = output, 0
        self.assertEqual(snapshot, probe.collage_outputs())
        args = probe.shell.call_args.args
        self.assertIn("content://media/external/images/media?includePending=1", args)
        self.assertIn("owner_package_name='" + module.PACKAGE + "' AND relative_path='" + module.COLLAGE_OUTPUT_PATH + "'", args)
        self.assertNotIn("delete", args)

    def test_collage_no_export_requires_unchanged_actual_inventory(self):
        probe = self.probe()
        probe.collage_output_baseline = (("owned-existing-output",),)
        probe.collage_outputs = Mock(return_value=probe.collage_output_baseline)
        probe.require_no_collage_export("before-home")
        for changed in ((), (("owned-existing-output",), ("new-pending-output",))):
            probe.collage_outputs.return_value = changed
            with self.assertRaisesRegex(RuntimeError, "outputs changed"):
                probe.require_no_collage_export("restored-draft")
        probe.shell.assert_not_called()  # Inventory failure never adopts or deletes an output.

    def test_collage_setup_changes_order_and_crop_without_export(self):
        probe = self.probe()
        probe.collage_outputs = Mock(return_value=())
        probe.click = Mock()
        probe.tap = Mock()
        probe.collage_ready = Mock()
        probe.collage_preview = Mock()
        probe.require_no_collage_export = Mock()
        descriptions = iter(("Zoom: 1", "Zoom: 2"))
        probe.find = Mock(side_effect=lambda attr, tag, **kwargs: self.collage_node(
            tag, next(descriptions) if tag == "creation-collage-zoom-value" else ""))
        probe.setup_collage()
        self.assertEqual([("create-collage",), ("creation-collage-later",)],
                         [call.args for call in probe.click.call_args_list])
        self.assertEqual("Zoom: 2", probe.collage_zoom)
        self.assertEqual([1, 0], probe.draft_evidence["order"])
        self.assertFalse(probe.draft_evidence["cropVisualAppearanceVerified"])
        probe.tap.assert_called_once()
        self.assertEqual("creation-collage-zoom", probe.tap.call_args.args[0].get("resource-id"))
        probe.require_no_collage_export.assert_called_once_with("before-home")
        self.assertEqual(((0, 0, 255), (255, 0, 0)), probe.collage_preview.call_args.args[0])

    def test_collage_unchanged_crop_gesture_fails_before_kill(self):
        probe = self.probe()
        for name in ("click", "tap", "collage_ready", "collage_preview", "require_no_collage_export"):
            setattr(probe, name, Mock())
        probe.collage_outputs = Mock(return_value=())
        probe.find = Mock(side_effect=lambda attr, tag, **kwargs: self.collage_node(tag, "Zoom: 1"))
        with self.assertRaisesRegex(RuntimeError, "gesture did not change"):
            probe.setup_collage()
        probe.require_no_collage_export.assert_not_called()
        probe.shell.assert_not_called()

    def test_collage_restoration_requires_selected_slot_and_exact_crop_before_order_proof(self):
        for value in ("Zoom: 2", "Zoom: 1"):
            probe = self.probe()
            probe.collage_zoom = "Zoom: 2"
            probe.collage_ready = Mock()
            probe.collage_preview = Mock()
            probe.require_no_collage_export = Mock()
            probe.find = Mock(side_effect=lambda attr, tag, **kwargs: self.collage_node(tag, value))
            if value == "Zoom: 1":
                with self.assertRaisesRegex(RuntimeError, "crop differs"):
                    probe.verify_collage_restored()
                probe.collage_preview.assert_not_called()
                probe.require_no_collage_export.assert_not_called()
            else:
                probe.verify_collage_restored()
                probe.find.assert_any_call("resource-id", "creation-collage-slot-1", checked=True)
                probe.require_no_collage_export.assert_called_once_with("restored-draft")

    def test_collage_grid2_samples_both_cells_not_just_whole_preview_center(self):
        probe = self.probe()
        node = self.collage_node("creation-collage-preview", "Collage preview")
        probe.ui_nodes = [node]
        probe.find = Mock(return_value=node)
        bitmap = Image.new("RGB", (200, 200), (255, 0, 0))
        bitmap.paste((0, 0, 255), (0, 0, 100, 200))
        stream = io.BytesIO()
        bitmap.save(stream, format="PNG")
        probe.call.return_value = stream.getvalue(), 0
        with tempfile.TemporaryDirectory() as directory:
            probe.out = Path(directory)
            probe.collage_preview(((0, 0, 255), (255, 0, 0)), "two-owned-cells")
        self.assertEqual([(0, 0, 255), (255, 0, 0)], probe.record.call_args.kwargs["rgb"])

    def test_scenario_routes_share_selection_and_saved_stopped_protocol(self):
        for scenario, method in (("memory-video", "setup_video"), ("collage-draft", "setup_collage"), ("gif-draft", "setup_gif")):
            probe = self.probe()
            probe.scenario = scenario
            probe.manifest = dict(rows=[dict(_id=21, ordinal=0), dict(_id=22, ordinal=1)],
                                  selectionLabel="2 selected", createLabel="Create")
            for name in ("require_no_instrumentation", "launch_normal", "find", "tap", "click",
                         "setup_video", "setup_collage", "setup_gif", "stop_normal_task"):
                setattr(probe, name, Mock())
            probe.setup_normal_app()
            getattr(probe, method).assert_called_once_with()
            for other in {"setup_collage", "setup_video", "setup_gif"} - {method}:
                getattr(probe, other).assert_not_called()
            probe.stop_normal_task.assert_called_once_with()
            probe.click.assert_called_once_with("media_external_primary_22")

    def test_collage_ready_rejects_published_or_uncertain_draft(self):
        for forbidden in ("creation-collage-saved", "creation-collage-cancel", "creation-collage-error",
                          "creation-collage-publication-uncertain"):
            probe = self.probe()
            probe.find = Mock(return_value=self.collage_node("creation-collage-export", "Export"))
            probe.ui_nodes = [self.collage_node(forbidden, "")]
            with self.subTest(tag=forbidden), self.assertRaisesRegex(RuntimeError, "unpublished"):
                probe.collage_ready()

    def test_creation_execute_keeps_real_death_task_selection_hash_and_cleanup_gates(self):
        cases = [(s, c, False) for s in ("collage-draft", "gif-draft", "motion-draft", "video-editor-draft") for c in (False, True)]
        for scenario, changed, verifier_error in cases + [("motion-draft", False, True)]:
            with self.subTest(scenario=scenario, source_changed=changed, verifier_error=verifier_error), tempfile.TemporaryDirectory() as directory:
                probe = module.Probe(SimpleNamespace(scenario=scenario, evidence=str(Path(directory)/"run"),
                    serial="127.0.0.1:5563", apk_sha256="a"*64, test_apk_sha256="b"*64))
                rows = [dict(_id=21, sha256="hash0"), dict(_id=22, sha256="hash1")]
                if scenario in ("motion-draft", "video-editor-draft"):
                    rows = rows[:1]
                probe.manifest = dict(state="SEEDED", rows=rows, selectionLabel="2 selected")
                probe.stdout = ["OK (1 test)"]
                probe.process = Mock(returncode=0)
                probe.process.poll.return_value = 0
                probe.guard_device = Mock()
                probe.apk = Mock()
                probe.start_native = Mock()
                probe.native_finished = Mock()
                probe.read_manifest = Mock(return_value=probe.manifest)
                probe.shell = Mock(side_effect=lambda *args, **kwargs: (
                    module.RUNNER + " (target=" + module.PACKAGE + ")" if args[:3] == ("pm", "list", "instrumentation")
                    else "200" if args[0] == "pidof" else "", 0))
                def setup():
                    probe.old_pid = 100
                    probe.task_id = 8
                probe.setup_normal_app = Mock(side_effect=setup)
                probe.kill_owned = Mock()
                probe.task_ids = Mock(return_value={8})
                probe.launch_normal = Mock()
                probe.process_identity = Mock()
                probe.verify_collage_restored = Mock()
                probe.verify_gif_restored = Mock(side_effect=lambda: setattr(probe, "no_auto_play_verified", True))
                probe.verify_video_restored = Mock()
                probe.verify_motion_restored = Mock(side_effect=lambda: setattr(probe, "no_auto_play_verified", True))
                probe.verify_video_editor_restored = Mock(side_effect=lambda: setattr(probe, "no_auto_play_verified", True))
                order = []
                def verify_keyframe(row):
                    self.assertTrue(probe.ui_complete)
                    order.append("native-verifier")
                    if verifier_error:
                        raise RuntimeError("Keyframe exists; retain source before FK cascade")
                    probe.keyframe_rows_verified = True
                probe.verify_motion_no_saved_keyframe = Mock(side_effect=verify_keyframe)
                probe.find = Mock(side_effect=lambda attr, tag, **kwargs: module.ET.Element("node", {
                    "package": module.PACKAGE, "resource-id": tag, "checkable": "true", "checked": "true"}))
                probe.source_hash = Mock(side_effect=lambda row: "changed" if changed else row["sha256"])
                def motion_back(row):
                    order.append("normal-ui-and-source-hash")
                    module.require(probe.source_hash(row) == row["sha256"], "Owned Motion original changed")
                    probe.no_auto_export_verified = True
                probe.verify_motion_back = Mock(side_effect=motion_back)
                probe.verify_video_editor_back = Mock(side_effect=motion_back)
                probe.require_no_collage_export = Mock()
                probe.require_no_gif_export = Mock()
                def cleanup():
                    if scenario == "motion-draft":
                        self.assertTrue(probe.keyframe_rows_verified)
                    order.append("CAS-cleanup")
                    probe.cleanup_complete = True
                probe.cleanup = Mock(side_effect=cleanup)
                with patch("builtins.print"):
                    status = probe.execute()
                self.assertEqual(1 if changed or verifier_error else 0, status)
                probe.kill_owned.assert_called_once_with(100)
                probe.process_identity.assert_called_once_with(200)
                probe.task_ids.assert_any_call(resumed=True)
                getattr(probe, {"collage-draft": "verify_collage_restored", "gif-draft": "verify_gif_restored", "motion-draft": "verify_motion_restored", "video-editor-draft": "verify_video_editor_restored"}[scenario]).assert_called_once_with()
                probe.verify_video_restored.assert_not_called()
                self.assertEqual(not changed, probe.no_auto_export_verified)
                if scenario == "motion-draft" and (changed or verifier_error):
                    probe.cleanup.assert_not_called()
                    if changed:
                        probe.verify_motion_no_saved_keyframe.assert_not_called()
                else:
                    probe.cleanup.assert_called_once_with()
                if scenario == "motion-draft" and not changed:
                    probe.verify_motion_no_saved_keyframe.assert_called_once_with(rows[0])
                    self.assertEqual(["normal-ui-and-source-hash", "native-verifier"] + ([] if verifier_error else ["CAS-cleanup"]), order)
                if scenario == "motion-draft":
                    probe.verify_motion_back.assert_called_once_with(rows[0])
                elif scenario == "video-editor-draft":
                    probe.verify_video_editor_back.assert_called_once_with(rows[0])
                    probe.verify_motion_no_saved_keyframe.assert_not_called()
                elif not changed:
                    getattr(probe, "require_no_collage_export" if scenario == "collage-draft" else "require_no_gif_export").assert_called_once_with("back-original-selection")

    def captured_partial_create_sheet(self):
        # Minimal hierarchy derived without changing retained node attributes from actual-api30/last-ui.xml.
        # Original capture SHA256: f0d37cfd935b9d12b23096af8336da8f0bb393fae7c34e699058fd4a2c9109a7
        xml = '<hierarchy><node package="com.ugallery.app.pdfacceptance" bounds="[0,0][1080,2072]"><node package="com.ugallery.app.pdfacceptance" bounds="[0,1170][1080,2072]"><node index="0" text="" resource-id="" class="android.view.View" package="com.ugallery.app.pdfacceptance" content-desc="Drag handle" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[496,1231][584,1242]" /><node index="3" text="" resource-id="create-memory-video" class="android.view.View" package="com.ugallery.app.pdfacceptance" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[55,1589][1025,1721]" /><node index="7" text="" resource-id="create-collage" class="android.view.View" package="com.ugallery.app.pdfacceptance" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][0,0]" /></node></node></hierarchy>'
        return list(module.ET.fromstring(xml).iter("node"))

    def test_captured_zero_collage_bounds_expand_handle_once_then_reacquire_before_tap(self):
        probe = self.probe()
        probe.manifest = {}
        before = self.captured_partial_create_sheet()
        after = self.captured_partial_create_sheet()
        target = next(n for n in after if n.get("resource-id") == "create-collage")
        target.set("bounds", "[55,1700][1025,1832]")
        after.append(module.ET.SubElement(target, "node", {"package": module.PACKAGE,
                     "text": "Collage", "bounds": "[450,1740][630,1792]"}))
        probe.tree = Mock(side_effect=[before, after])
        with patch.object(module.time, "sleep"):
            probe.click("create-collage")
        self.assertEqual(2, probe.tree.call_count)
        self.assertEqual([("input", "swipe", 540, 1236, 540, 518, "350"),
                          ("input", "tap", 540, 1766)], [c.args for c in probe.shell.call_args_list])
        self.assertIn(target, probe.ui_nodes)

    def test_zero_bounds_still_rejected_globally_and_sheet_expansion_is_bounded(self):
        probe = self.probe()
        probe.manifest = {}
        nodes = self.captured_partial_create_sheet()
        candidate = next(n for n in nodes if n.get("resource-id") == "create-collage")
        with self.assertRaisesRegex(RuntimeError, "Empty UI bounds"):
            module.bounds(candidate)
        probe.tree = Mock(return_value=nodes)
        with patch.object(module.time, "monotonic", side_effect=[0, 0, 1, 2, 3]), patch.object(module.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "remained unmeasured"):
                probe.find("resource-id", "create-collage", timeout=3)
        probe.shell.assert_called_once_with("input", "swipe", 540, 1236, 540, 518, "350")
        self.assertEqual(3, probe.tree.call_count)

    def test_unmeasured_sheet_requires_visible_real_handle_and_scroll_permission(self):
        for change in ("no-handle", "empty-handle", "scroll-disabled"):
            probe = self.probe()
            probe.manifest = {}
            nodes = self.captured_partial_create_sheet()
            handle = next(n for n in nodes if n.get("content-desc") == "Drag handle")
            if change == "no-handle":
                handle.set("content-desc", "Other control")
            if change == "empty-handle":
                handle.set("bounds", "[0,0][0,0]")
            probe.tree = Mock(return_value=nodes)
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                probe.find("resource-id", "create-collage", scroll=change != "scroll-disabled")
            probe.shell.assert_not_called()

    def test_unmeasured_scroll_child_uses_measured_ancestor_then_fresh_bounds(self):
        probe = self.probe()
        probe.manifest = {}
        xml = '<hierarchy><node package="' + module.PACKAGE + '" bounds="[0,0][400,800]">'
        xml += '<node package="' + module.PACKAGE + '" scrollable="true" bounds="[20,100][380,700]">'
        xml += '<node package="' + module.PACKAGE + '" resource-id="creation-collage-zoom" enabled="true" bounds="[0,0][0,0]" />'
        xml += '</node></node></hierarchy>'
        before = list(module.ET.fromstring(xml).iter("node"))
        after = list(module.ET.fromstring(xml.replace('[0,0][0,0]', '[20,350][380,450]')).iter("node"))
        probe.tree = Mock(side_effect=[before, after])
        with patch.object(module.time, "sleep"):
            result = probe.find("resource-id", "creation-collage-zoom")
        self.assertIs(after[-1], result)
        probe.shell.assert_called_once_with("input", "swipe", 200, 550, 200, 250, "250")

    def test_hidden_collage_prefers_real_scroll_ancestor_over_sheet_expansion(self):
        probe = self.probe()
        probe.manifest = {}
        before = self.captured_partial_create_sheet()
        after = self.captured_partial_create_sheet()
        for nodes in (before, after):
            nodes[1].set("scrollable", "true")
        target = next(n for n in after if n.get("resource-id") == "create-collage")
        target.set("bounds", "[55,1700][1025,1832]")
        after.append(module.ET.SubElement(target, "node", {"package": module.PACKAGE,
                     "text": "Collage", "bounds": "[450,1740][630,1792]"}))
        probe.tree = Mock(side_effect=[before, after])
        probe.expand_creation_sheet = Mock()
        with patch.object(module.time, "sleep"):
            probe.click("create-collage")
        probe.expand_creation_sheet.assert_not_called()
        self.assertEqual(2, probe.tree.call_count)
        self.assertEqual([("input", "swipe", 540, 1847, 540, 1395, "250"),
                          ("input", "tap", 540, 1766)], [call.args for call in probe.shell.call_args_list])

    def test_captured_thin_collage_edge_scrolls_without_tapping_hidden_text(self):
        # Exact node from expanded-api30/last-ui.xml, SHA256
        # b20ec2258ac63822ac3ac44ebafdea25f4912b5f45838fae01893bef647f9e3c.
        xml = '<node index="7" text="" resource-id="create-collage" class="android.view.View" package="com.ugallery.app.pdfacceptance" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[55,2054][1025,2072]"><node index="0" text="Collage" resource-id="" class="android.widget.TextView" package="com.ugallery.app.pdfacceptance" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][0,0]" /><node index="1" text="" resource-id="" class="android.widget.Button" package="com.ugallery.app.pdfacceptance" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[55,2065][1025,2072]" /></node>'
        probe = self.probe()
        probe.manifest = {}
        window = module.ET.Element("node", {"package": module.PACKAGE, "bounds": "[0,0][1080,2072]"})
        container = module.ET.SubElement(window, "node", {"package": module.PACKAGE,
                    "bounds": "[0,1019][1080,2072]", "scrollable": "true"})
        candidate = module.ET.fromstring(xml)
        container.append(candidate)
        before = list(window.iter("node"))
        probe.ui_nodes = before
        with self.assertRaisesRegex(RuntimeError, "label remains clipped"):
            probe.tap(candidate)
        probe.shell.assert_not_called()
        restored = module.ET.fromstring(module.ET.tostring(window, encoding="unicode"))
        target = next(n for n in restored.iter("node") if n.get("resource-id") == "create-collage")
        target.set("bounds", "[55,1700][1025,1832]")
        next(n for n in target.iter("node") if n.get("text") == "Collage").set("bounds", "[450,1740][630,1792]")
        after = list(restored.iter("node"))
        probe.tree = Mock(side_effect=[before, after])
        probe.expand_creation_sheet = Mock()
        with patch.object(module.time, "sleep"):
            probe.click("create-collage")
        probe.expand_creation_sheet.assert_not_called()
        self.assertEqual([("input", "swipe", 540, 1809, 540, 1282, "250"),
                          ("input", "tap", 540, 1766)], [call.args for call in probe.shell.call_args_list])

    def test_real_display_observer_requires_exact_hash_path_and_assigned_lane(self):
        probe = self.probe()
        probe.args = SimpleNamespace(serial="emulator-5554", observer_sha256="a"*64)
        expected = "/data/local/tmp/ugallery-ui-observer-" + "a"*64 + ".jar"
        probe.shell.return_value = "a"*64 + "  " + expected + "\n", 0
        self.assertEqual(expected, probe.guard_observer())
        probe.shell.assert_called_once_with("sha256sum", expected)
        for output in ("b"*64 + "  " + expected, "a"*64 + "  /other.jar", "", "Permission denied"):
            probe.shell.return_value = output, 0
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                probe.guard_observer()
        probe.args.serial = "127.0.0.1:5563"
        probe.shell.reset_mock()
        probe.shell.return_value = "a"*64 + "  " + expected + "\n", 0
        self.assertEqual(expected, probe.guard_observer())
        probe.shell.assert_called_once_with("sha256sum", expected)
        for serial in ("emulator-5562", "unassigned-device", None):
            probe.args.serial = serial
            probe.shell.reset_mock()
            with self.subTest(serial=serial), self.assertRaisesRegex(RuntimeError, "assigned API30/API35"):
                probe.guard_observer()
            probe.shell.assert_not_called()
        for digest in (None, "", "a"*63, "A"*64, "../../file"):
            with self.subTest(digest=digest), self.assertRaises(RuntimeError):
                module.observer_remote_path(digest)

    def real_display_receipt(self, path, nodes=2):
        return dict(version=1, observer="builtin-real-display", path=path, appWidth=1080, appHeight=2072,
                    realWidth=1080, realHeight=2340, rotation=0, rawRootBounds="0 0 1080 2340", nodes=nodes)

    def test_real_display_receipt_preserves_real_dimensions_and_rejects_bad_protocol(self):
        path = "/sdcard/creation-process-fixture-fresh.xml"
        receipt = self.real_display_receipt(path)
        self.assertEqual(receipt, module.real_display_receipt(module.json.dumps(receipt), path))
        for field, value in (("path", "/sdcard/stale.xml"), ("observer", "another-observer"),
                             ("version", True), ("nodes", 0), ("nodes", True), ("realHeight", 2000),
                             ("appWidth", -1), ("rawRootBounds", ""), ("rotation", 4)):
            changed = dict(receipt, **{field: value})
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                module.real_display_receipt(module.json.dumps(changed), path)
        for output in ("", "[]", "ERROR: null root", module.json.dumps(receipt) + "\nnoise"):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                module.real_display_receipt(output, path)

    def test_real_display_tree_uses_exclusive_app_process_and_fresh_receipt_before_xml(self):
        for mismatch in (None, "path", "nodes"):
            with self.subTest(mismatch=mismatch), tempfile.TemporaryDirectory() as directory:
                probe = self.probe()
                probe.args = SimpleNamespace(serial="emulator-5554", observer_sha256="a"*64)
                probe.out = Path(directory)
                probe.native_completed = True
                probe.accessibility_owner = "host"
                expected = module.observer_remote_path("a"*64)
                def shell(*args, **kwargs):
                    if args[0] == "sha256sum":
                        return "a"*64 + "  " + expected, 0
                    self.assertEqual(("env", "CLASSPATH=/system/framework/uiautomator.jar:" + expected,
                                      "app_process", "/system/bin", "com.ugallery.tools.RealDisplayDump"), args[:-1])
                    receipt = self.real_display_receipt(args[-1], nodes=3 if mismatch == "nodes" else 2)
                    if mismatch == "path":
                        receipt["path"] = "/sdcard/stale.xml"
                    return module.json.dumps(receipt), 0, ""
                probe.shell.side_effect = shell
                xml = '<hierarchy rotation="0"><node bounds="[0,0][1080,2340]">'
                xml += '<node resource-id="create-collage" text="Collage" bounds="[55,2054][1025,2186]" /></node></hierarchy>'
                probe.call.return_value = xml, 0, ""
                if mismatch:
                    with self.assertRaises(RuntimeError):
                        probe.tree()
                    if mismatch == "path":
                        probe.call.assert_not_called()
                else:
                    nodes = probe.tree()
                    self.assertEqual((55, 2054, 1025, 2186), module.bounds(nodes[-1]))
                    probe.call.assert_called_once_with("exec-out", "cat", probe.dumps[-1], timeout=5,
                                                      check=False, include_stderr=True)
                self.assertEqual(1, len(probe.dumps))

    def test_video_fixture_settle_is_bounded_and_never_counts_as_ui_verification(self):
        probe = self.probe()
        probe.manifest = {"durationMillis": 3033}
        with patch.object(module.time, "sleep") as sleep:
            probe.settle_video_fixture_preview()
            sleep.assert_called_once_with(4.033)
        for invalid in (True, 0, 60001, "3033", None):
            probe.manifest = {"durationMillis": invalid}
            with self.assertRaises(RuntimeError), patch.object(module.time, "sleep") as sleep:
                probe.settle_video_fixture_preview()
            sleep.assert_not_called()

    def test_real_display_terminal_null_root_retries_only_once_with_fresh_path(self):
        known = ("java.lang.IllegalStateException: Null active accessibility root\n"
                 "\tat com.ugallery.tools.RealDisplayDump.main(RealDisplayDump.java:36)\n"
                 "\tat com.android.internal.os.RuntimeInit.nativeFinishInit(Native Method)\n"
                 "\tat com.android.internal.os.RuntimeInit.main(RuntimeInit.java:399)\n")
        for failure in ("transient", "persistent", "other"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory, patch.object(module.time, "sleep"):
                probe = self.probe()
                probe.args = SimpleNamespace(serial="emulator-5554", observer_sha256="a"*64)
                probe.out = Path(directory)
                probe.native_completed = True
                probe.accessibility_owner = "host"
                paths = []
                def shell(*args, **kwargs):
                    if args[0] == "sha256sum":
                        return "a"*64 + "  " + module.observer_remote_path("a"*64), 0
                    paths.append(args[-1])
                    if failure == "other": return "", 1, known + "unexpected additional error\n"
                    if failure == "persistent" or len(paths) == 1: return "", 1, known
                    return module.json.dumps(self.real_display_receipt(args[-1], nodes=1)), 0, ""
                probe.shell.side_effect = shell
                probe.call.return_value = '<hierarchy rotation="0"><node bounds="[0,0][1080,2340]" /></hierarchy>', 0, ""
                if failure == "transient":
                    self.assertEqual(1, len(probe.tree()))
                    probe.call.assert_called_once_with("exec-out", "cat", paths[-1], timeout=5, check=False, include_stderr=True)
                else:
                    with self.assertRaises(RuntimeError): probe.tree()
                    probe.call.assert_not_called()
                self.assertEqual(1 if failure == "other" else 2, len(paths))
                self.assertEqual(len(paths), len(set(paths)))

    def test_collage_crop_value_requires_actual_text_and_ignores_slider_metadata(self):
        node = self.collage_node(description="Zoom: 2")
        node.set("enabled", "false")  # Reading a Text value does not depend on slider action state.
        self.assertEqual("Zoom: 2", module.collage_crop_description(node))
        node.set("text", "")
        node.set("content-desc", "Zoom: 2")
        with self.assertRaisesRegex(RuntimeError, "no observable numeric value"):
            module.collage_crop_description(node)
        node.set("resource-id", "creation-collage-zoom")
        node.set("text", "Zoom: 2")
        with self.assertRaisesRegex(RuntimeError, "value is not ready"):
            module.collage_crop_description(node)

    def test_manual_back_only_dismisses_a_verified_visible_ime(self):
        header = "Current Input Method Manager state:\n"
        for output, expected in ((header + "mInputShown=true", True), (header + "mInputShown=false", False),
                                 ("denied", None), (header + "mInputShown=true mInputShown=false", None)):
            with self.subTest(output=output):
                probe = self.probe()
                probe.find = Mock()
                probe.shell.return_value = output, 0
                if expected is None:
                    with self.assertRaisesRegex(RuntimeError, "no Back sent"):
                        probe.dismiss_manual_keyboard_if_shown()
                    probe.find.assert_not_called()
                else:
                    probe.dismiss_manual_keyboard_if_shown()
                    probe.find.assert_called_once_with("resource-id", "manual-moment-screen", scroll=False)
                backs = [c for c in probe.shell.call_args_list if c.args == ("input", "keyevent", "KEYCODE_BACK")]
                self.assertEqual(1 if expected else 0, len(backs))

    def test_manual_title_waits_for_focus_and_acknowledges_exact_chunks(self):
        for failure in (None, "wrong", "focus"):
            with self.subTest(failure=failure), patch.object(module.time, "sleep"):
                probe = self.probe()
                probe.fixture = "3fa22e2f-416d-4e24-a515-59ca00ccb453"
                state = {"text": "", "focused": "false"}
                node = lambda: module.ET.Element("node", dict(state))
                probe.tap = Mock()
                finds = []
                def find(*args, **kwargs):
                    finds.append(1)
                    if len(finds) >= 2:
                        state["focused"] = "true"
                    if failure == "focus" and state["text"]:
                        state["focused"] = "false"
                    return node()
                probe.find = Mock(side_effect=find)
                def shell(*args, **kwargs):
                    self.assertEqual(("input", "text"), args[:2])
                    state["text"] += args[2].replace("%s", " ")
                    if failure == "wrong": state["text"] = "unexpected"
                    return "", 0
                probe.shell.side_effect = shell
                if failure:
                    with self.assertRaises(RuntimeError):probe.enter_manual_memory_title(node())
                    self.assertEqual(1, probe.shell.call_count)
                    probe.record.assert_not_called()
                else:
                    probe.enter_manual_memory_title(node())
                    self.assertEqual(probe.manual_memory_title(), state["text"])
                    self.assertEqual(7, probe.shell.call_count)
                    self.assertEqual(9, len(finds))
                    probe.record.assert_called_once()

    def test_memory_video_output_inventory_uses_video_collection_and_rejects_changes(self):
        probe = self.probe()
        probe.shell.return_value = "No result found.", 0
        probe.memory_video_output_baseline = ()
        probe.require_no_memory_video_export("restored-draft")
        args = probe.shell.call_args.args
        self.assertIn("content://media/external/video/media?includePending=1", args)
        self.assertIn("owner_package_name='" + module.PACKAGE + "' AND relative_path='Movies/UGallery/Memories/'", args)
        probe.record.assert_called_once()
        row = self.row()
        row.update(relative_path=module.MEMORY_VIDEO_OUTPUT_PATH, _display_name="UGallery-Memory-owned.mp4", is_pending=1)
        probe.shell.return_value = "Row: 0 " + ", ".join(field + "=" + str(row[field]) for field in module.FIELDS), 0
        with self.assertRaisesRegex(RuntimeError, "outputs changed"):
            probe.require_no_memory_video_export("back-original-selection")
        probe.record.assert_called_once()  # Failure adds no successful proof.
        probe.shell.return_value = "Error: denied", 0
        with self.assertRaises(RuntimeError):
            probe.require_no_memory_video_export("denied")

    def test_gif_output_inventory_reuses_exact_owner_path_pending_guards(self):
        row = self.row()
        row.update(relative_path=module.GIF_OUTPUT_PATH, _display_name="UGallery-GIF-owned.gif", is_pending=1)
        output = "Row: 0 " + ", ".join(field + "=" + str(row[field]) for field in module.FIELDS)
        snapshot = module.parse_creation_outputs(output, module.GIF_OUTPUT_PATH)
        self.assertEqual("1", snapshot[0][-1])
        for bad in (output.replace(module.GIF_OUTPUT_PATH, module.COLLAGE_OUTPUT_PATH),
                    output.replace(module.PACKAGE, "other.package"), "Error: denied", ""):
            with self.subTest(output=bad), self.assertRaises(RuntimeError):
                module.parse_creation_outputs(bad, module.GIF_OUTPUT_PATH)
        probe = self.probe()
        probe.shell.return_value = output, 0
        self.assertEqual(snapshot, probe.creation_outputs(module.GIF_OUTPUT_PATH))
        self.assertIn("owner_package_name='" + module.PACKAGE + "' AND relative_path='Pictures/UGallery/GIF/'",
                      probe.shell.call_args.args)
        self.assertIn("content://media/external/images/media?includePending=1", probe.shell.call_args.args)
        probe.gif_output_baseline = snapshot
        probe.require_no_gif_export("before-home")
        probe.shell.return_value = "No result found.", 0
        with self.assertRaisesRegex(RuntimeError, "outputs changed"):
            probe.require_no_gif_export("restored-draft")
        self.assertFalse(any("delete" in call.args for call in probe.shell.call_args_list))

    def test_gif_play_label_uses_actual_localized_text_and_rejects_ambiguity(self):
        node = self.collage_node("creation-gif-play", "")
        module.ET.SubElement(node, "node", {"package": module.PACKAGE, "text": "Reproducir"})
        self.assertEqual("Reproducir", module.gif_play_text(node))
        for attribute, value in (("enabled", "false"), ("resource-id", "other"), ("package", "other")):
            bad = module.ET.fromstring(module.ET.tostring(node))
            bad.set(attribute, value)
            with self.subTest(attribute=attribute), self.assertRaises(RuntimeError):
                module.gif_play_text(bad)
        module.ET.SubElement(node, "node", {"package": module.PACKAGE, "text": "Pause"})
        with self.assertRaisesRegex(RuntimeError, "ambiguous"):
            module.gif_play_text(node)
        with self.assertRaisesRegex(RuntimeError, "missing"):
            module.gif_play_text(self.collage_node("creation-gif-play", ""))

    def test_gif_setup_preserves_second_position_four_seconds_and_never_starts_playback(self):
        probe = self.probe()
        probe.creation_outputs = Mock(return_value=())
        probe.click = Mock()
        probe.preview = Mock()
        probe.require_no_gif_export = Mock()
        positions = iter(("Photo 1 of 2", "Photo 2 of 2", "Photo 1 of 2", "Photo 2 of 2"))
        probe.find = Mock(side_effect=lambda attr, tag, **kwargs: self.collage_node(tag,
            next(positions) if tag == "creation-gif-position" else "Play" if tag == "creation-gif-play" else ""))
        probe.setup_gif()
        self.assertEqual([("create-gif",), ("creation-gif-later",), ("creation-gif-seconds-4",),
                          ("creation-gif-previous",), ("creation-gif-next",)], [call.args for call in probe.click.call_args_list])
        probe.find.assert_any_call("resource-id", "creation-gif-seconds-4", checked=True)
        self.assertEqual([1, 0], probe.draft_evidence["order"])
        self.assertEqual(4, probe.draft_evidence["seconds"])
        self.assertEqual(1, probe.draft_evidence["current"])
        self.assertFalse(probe.draft_evidence["playbackStarted"])
        self.assertEqual([(255, 0, 0), (255, 0, 0), (0, 0, 255), (255, 0, 0)],
                         [call.args[0] for call in probe.preview.call_args_list])
        self.assertTrue(all(call.kwargs == {"preview_tag": "creation-gif-preview"} for call in probe.preview.call_args_list))
        probe.require_no_gif_export.assert_called_once_with("before-home")

    def test_gif_restoration_verifies_paused_interval_before_any_navigation(self):
        for failure in (None, "autoplay", "position", "advances"):
            probe = self.probe()
            probe.gif_position1, probe.gif_position2, probe.gif_play_label = "Photo 1 of 2", "Photo 2 of 2", "Play"
            probe.no_auto_play_verified = False
            probe.preview = Mock()
            probe.click = Mock()
            probe.require_no_gif_export = Mock()
            positions = iter(("Photo 1 of 2" if failure == "position" else "Photo 2 of 2",
                              "Photo 1 of 2" if failure == "advances" else "Photo 2 of 2", "Photo 1 of 2"))
            probe.find = Mock(side_effect=lambda attr, tag, **kwargs: self.collage_node(tag,
                next(positions) if tag == "creation-gif-position" else
                ("Pause" if failure == "autoplay" else "Play") if tag == "creation-gif-play" else ""))
            with patch.object(module.time, "monotonic", side_effect=[10, 11]), patch.object(module.time, "sleep") as sleep:
                if failure:
                    with self.subTest(failure=failure), self.assertRaises(RuntimeError):
                        probe.verify_gif_restored()
                    probe.click.assert_not_called()
                    probe.require_no_gif_export.assert_not_called()
                    self.assertFalse(probe.no_auto_play_verified)
                else:
                    probe.verify_gif_restored()
                    sleep.assert_called_once_with(3.25)
                    self.assertTrue(probe.no_auto_play_verified)
                    probe.click.assert_called_once_with("creation-gif-previous")
                    self.assertEqual([(255, 0, 0), (0, 0, 255)], [call.args[0] for call in probe.preview.call_args_list])
                    probe.require_no_gif_export.assert_called_once_with("restored-draft")

    def motion_bytes(self):
        # Tiny container-only mock; real MP4 decoding belongs to the native corpus fixture.
        stream = io.BytesIO()
        Image.new("RGB", (320, 240), (255, 0, 255)).save(stream, format="JPEG")
        jpeg = stream.getvalue()
        xmp = b"http://ns.adobe.com/xap/1.0/\x00MotionPhoto"
        app1 = b"\xff\xe1" + (len(xmp)+2).to_bytes(2, "big") + xmp
        still = jpeg[:2] + app1 + jpeg[2:]
        clip = (16).to_bytes(4, "big") + b"ftypisom" + bytes(4)
        data = still + bytes(7) + clip
        return data, dict(byteSize=len(data), videoOffset=len(still)+7, videoLength=len(clip))

    def motion_manifest(self):
        return dict(motionLabel="Motion Photo", playLabel="Play motion", expectedFrame4TimeLabel="1600 / 2000 ms",
                    viewerMoreLabel="More", viewerDetailsLabel="Details", viewerPhotoLabel="Photo")

    def test_motion_whole_container_hash_rejects_truncation_offset_and_wrong_bytes(self):
        data, metadata = self.motion_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), module.verify_motion_source_bytes(data, metadata))
        for changed in (dict(metadata, videoOffset=1), dict(metadata, videoLength=1), dict(metadata, byteSize=True), {}):
            with self.subTest(metadata=changed), self.assertRaises(RuntimeError):
                module.verify_motion_source_bytes(data, changed)
        for changed in (data[:-1], data.replace(b"ftyp", b"nope"), data.replace(b"MotionPhoto", b"OtherHeader"), b"Error: denied"):
            with self.subTest(data=changed[:12]), self.assertRaises(RuntimeError):
                module.verify_motion_source_bytes(changed, metadata)

    def test_motion_source_uses_owned_jpeg_path_and_whole_envelope_without_weakening_png(self):
        probe = self.probe()
        probe.scenario = "motion-draft"
        probe.args.serial = "emulator-5554"
        row = self.row()
        row["_display_name"] = probe.name + "-0.jpg"
        row["_data"] = "/storage/emulated/0/" + row["relative_path"] + row["_display_name"]
        data, probe.manifest = self.motion_bytes()
        probe.call.return_value = data, 0
        self.assertEqual(hashlib.sha256(data).hexdigest(), probe.source_hash(row))
        probe.call.assert_called_once_with("exec-out", "cat", row["_data"], binary=True)
        for field, value in (("ordinal", 1), ("_display_name", probe.name + "-0.png"),
                             ("owner_package_name", "other"), ("_data", "/storage/emulated/0/other.jpg")):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                probe.validate_source(dict(row, **{field: value}))
        probe.scenario = "memory-video"
        with self.assertRaises(RuntimeError):
            probe.validate_source(row)

    def test_motion_output_inventories_use_separate_real_collections_and_never_delete(self):
        probe = self.probe()
        probe.shell.return_value = "No result found.", 0
        self.assertEqual((), probe.creation_outputs(module.MOTION_IMAGE_PATH))
        self.assertIn("content://media/external/images/media?includePending=1", probe.shell.call_args.args)
        self.assertEqual((), probe.creation_outputs(module.MOTION_VIDEO_PATH))
        self.assertIn("content://media/external/video/media?includePending=1", probe.shell.call_args.args)
        self.assertIn("owner_package_name='" + module.PACKAGE + "' AND relative_path='Movies/UGallery/Motion/'", probe.shell.call_args.args)
        probe.motion_output_baseline = {module.MOTION_IMAGE_PATH: (), module.MOTION_VIDEO_PATH: ()}
        probe.require_no_motion_export("restored-draft")
        probe.creation_outputs = Mock(side_effect=[(), (("new-video-output",),)])
        with self.assertRaisesRegex(RuntimeError, "outputs changed"):
            probe.require_no_motion_export("back-original-viewer")
        self.assertFalse(any("delete" in call.args for call in probe.shell.call_args_list))

    def test_motion_frame_selection_accepts_backend_mapping_but_not_unselected_or_other_frame(self):
        node = self.collage_node("motion-frame-4", "")
        node.set("checkable", "true")
        module.require_motion_frame_selected(node)
        node.set("checked", "false")
        node.set("selected", "true")
        module.require_motion_frame_selected(node)
        for attributes in ({"selected": "false"}, {"resource-id": "motion-frame-3"}, {"enabled": "false"}, {"package": "other"}):
            bad = module.ET.fromstring(module.ET.tostring(node))
            bad.attrib.update(attributes)
            with self.subTest(attributes=attributes), self.assertRaises(RuntimeError):
                module.require_motion_frame_selected(bad)

    def test_motion_state_requires_same_time_and_paused_label(self):
        for wrong in (None, "time", "playing"):
            probe = self.probe()
            probe.manifest = self.motion_manifest()
            def find(attr, tag, **kwargs):
                label = "1599 / 2000 ms" if wrong == "time" else probe.manifest["expectedFrame4TimeLabel"]
                if tag == "motion-play":
                    label = "Pause" if wrong == "playing" else probe.manifest["playLabel"]
                node = self.collage_node(tag, label)
                node.set("checkable", "true")
                return node
            probe.find = Mock(side_effect=find)
            if wrong:
                with self.subTest(wrong=wrong), self.assertRaises(RuntimeError):
                    probe.verify_motion_state("restored-draft")
            else:
                probe.verify_motion_state("restored-draft")
                self.assertEqual("1600 / 2000 ms", probe.record.call_args.kwargs["time"])

    def test_motion_setup_uses_single_unselected_source_and_back_details_exact_name(self):
        probe = self.probe()
        probe.scenario = "motion-draft"
        row = self.row()
        row["_display_name"] = probe.name + "-0.jpg"
        probe.manifest = dict(self.motion_manifest(), rows=[row])
        for name in ("require_no_instrumentation", "launch_normal", "find", "tap", "click", "preview",
                     "stop_normal_task", "verify_motion_state", "require_no_motion_export"):
            setattr(probe, name, Mock())
        probe.creation_outputs = Mock(return_value=())
        probe.setup_normal_app()
        self.assertEqual([("motion-frame-4",)], [call.args for call in probe.click.call_args_list])
        probe.find.assert_any_call("resource-id", "media_external_primary_21", timeout=45)
        self.assertEqual(2, probe.tap.call_count)  # Source and Motion action, never long-press selection.
        self.assertEqual({}, probe.tap.call_args_list[0].kwargs)
        probe.stop_normal_task.assert_called_once_with()
        self.assertFalse(probe.draft_evidence["setKeyFrameExecuted"])
        self.assertFalse(probe.draft_evidence["keyFrameRowsVerified"])
        probe.source_hash = Mock(return_value=row["sha256"])
        probe.verify_motion_back(row)
        probe.find.assert_any_call("text", row["_display_name"])
        probe.find.assert_any_call("content-desc", "More")
        probe.find.assert_any_call("text", "Details")
        probe.source_hash.assert_called_once_with(row)
        self.assertTrue(probe.no_auto_export_verified)
        self.assertEqual(2, sum(call.args == ("input", "keyevent", "KEYCODE_BACK") for call in probe.shell.call_args_list))

    def test_motion_native_selector_is_one_seed_only_without_normal_app_instrumentation(self):
        probe = self.probe()
        probe.scenario = "motion-draft"
        probe.fixture = "owned-uuid"
        probe.stdout, probe.stderr, probe.threads = [], [], []
        process = Mock(stdout=io.StringIO(""), stderr=io.StringIO(""))
        with patch.object(module.subprocess, "Popen", return_value=process) as popen:
            probe.start_native()
        for thread in probe.threads:
            thread.join()
        command = popen.call_args.args[0]
        self.assertIn(module.MOTION_TEST, command[-1])
        self.assertNotIn(module.TEST, command[-1])
        self.assertIn("am instrument -w -r", command[-1])

    def motion_keyframe_receipt(self):
        return dict(fixtureUuid="owned-uuid", mediaId=21, baselineKeyFrameAbsent=True,
                    keyFrameRowsVerified=True, sourceCurrent=True, status="PASS")

    def test_motion_keyframe_receipt_requires_current_source_and_exact_identity_booleans(self):
        receipt = self.motion_keyframe_receipt()
        self.assertEqual(receipt, module.verify_motion_keyframe_receipt(module.json.dumps(receipt), "owned-uuid", 21))
        for field, value in (("fixtureUuid", "other"), ("mediaId", 22), ("mediaId", 21.0), ("status", "FAIL"),
                             ("baselineKeyFrameAbsent", False), ("keyFrameRowsVerified", 1), ("sourceCurrent", False)):
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                module.verify_motion_keyframe_receipt(module.json.dumps(dict(receipt, **{field: value})), "owned-uuid", 21)
        for output in ("", "[]", module.json.dumps(dict(receipt, extra="not-allowed"))):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                module.verify_motion_keyframe_receipt(output, "owned-uuid", 21)

    def test_motion_native_verifier_handoff_is_terminal_before_receipt_and_never_checks_normal_pid(self):
        for failure in (None, "instrument", "receipt", "source-missing"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                probe = self.probe()
                probe.out = Path(directory)
                probe.fixture = "owned-uuid"
                probe.ui_complete = probe.death_confirmed = probe.native_completed = True
                probe.accessibility_owner = "host"
                probe.keyframe_rows_verified = False
                probe.process_identity = Mock()
                probe.cleanup = Mock()
                probe.shell.return_value = ("FAILURES!!!" if failure == "instrument" else "OK (1 test)\n", 0, "")
                receipt = self.motion_keyframe_receipt()
                if failure == "source-missing":
                    receipt["sourceCurrent"] = False
                probe.call.return_value = module.json.dumps(receipt), 1 if failure == "receipt" else 0, ""
                if failure:
                    with self.assertRaises(RuntimeError):
                        probe.verify_motion_no_saved_keyframe(self.row())
                    self.assertFalse(probe.keyframe_rows_verified)
                    if failure == "instrument":
                        probe.call.assert_not_called()
                else:
                    probe.verify_motion_no_saved_keyframe(self.row())
                    self.assertTrue(probe.keyframe_rows_verified)
                    self.assertEqual(receipt, module.json.loads((probe.out/"keyframe-verification.json").read_text()))
                self.assertEqual("native-verifier", probe.accessibility_owner)
                probe.shell.assert_called_once_with("am", "instrument", "-w", "-r", "-e", "fixtureUuid", "owned-uuid",
                    "-e", "class", module.MOTION_VERIFY_TEST, module.RUNNER, timeout=90, check=False, include_stderr=True)
                probe.process_identity.assert_not_called()
                probe.cleanup.assert_not_called()

    def test_motion_cleanup_itself_blocks_cascade_without_predeletion_verification(self):
        probe = self.probe()
        probe.scenario = "motion-draft"
        probe.keyframe_rows_verified = False
        probe.read_manifest = Mock()
        with self.assertRaisesRegex(RuntimeError, "pre-deletion keyframe verification"):
            probe.cleanup()
        probe.read_manifest.assert_not_called()
        probe.shell.assert_not_called()

    def test_fullscreen_original_preview_never_swipes_to_find_missing_photo_description(self):
        probe = self.probe()
        probe.manifest = {}
        # A scrollable photo viewport is not an editor list: searching it by gesture can dismiss the viewer.
        xml = '<hierarchy><node package="' + module.PACKAGE + '" scrollable="true" bounds="[0,0][1080,2340]">'
        xml += '<node package="' + module.PACKAGE + '" class="android.widget.ImageView" content-desc="" bounds="[0,0][1080,2340]" /></node></hierarchy>'
        probe.tree = Mock(return_value=list(module.ET.fromstring(xml).iter("node")))
        with patch.object(module.time, "monotonic", side_effect=range(30)), patch.object(module.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "Restored UI missing content-desc=Photo"):
                probe.preview((255, 0, 255), "unobserved-original", preview_description="Photo")
        probe.shell.assert_not_called()
        probe.call.assert_not_called()  # No screenshot/color assertion before the actual image is identified.

    def test_only_explicit_fullscreen_description_disables_preview_search_scroll(self):
        for options, expected_args, expected_kwargs in (
            ({"preview_description": "Photo"}, ("content-desc", "Photo"), {"scroll": False}),
            ({"preview_tag": "creation-gif-preview"}, ("resource-id", "creation-gif-preview"), {}),
            ({}, ("content-desc", "Video photo preview"), {}),
        ):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                probe = self.probe()
                probe.out = Path(directory)
                probe.manifest = {"previewLabel": "Video photo preview"}
                probe.find = Mock(return_value=self.collage_node())
                stream = io.BytesIO()
                Image.new("RGB", (200, 200), (255, 0, 255)).save(stream, format="PNG")
                probe.call.return_value = stream.getvalue(), 0
                probe.preview((255, 0, 255), "identified-original", **options)
                probe.find.assert_called_once_with(*expected_args, **expected_kwargs)
                probe.shell.assert_not_called()

    def editor_manifest(self):
        return dict(viewerEditLabel="Edit", viewerMoreLabel="More", viewerDetailsLabel="Details", editorAudioLabel="Audio",
                    editorOriginalAudioLabel="Original audio", editorPlayLabel="Play preview", editorPauseLabel="Pause preview",
                    editorDiscardTitleLabel="Discard edits?", editorDiscardConfirmLabel="Discard", durationMillis=3033)

    def editor_find_value(self, attribute, value, **kwargs):
        text = {"video-editor-trim-value": "Trim from 0:00.500 to 0:02.500",
                "video-editor-position-value": "Position 0:01.500 of 0:02.500"}.get(value, "")
        return self.collage_node(value, text)

    def test_editor_timecodes_retain_exact_milliseconds_and_reject_truncated_or_invalid_values(self):
        node = self.collage_node("video-editor-trim-value", "Recortar de 0:00.500 a 0:02.678")
        text, values = module.editor_timecodes(node, "video-editor-trim-value")
        self.assertEqual((500, 2678), values)
        self.assertEqual(node.get("text"), text)
        for text in ("Trim 0:00 to 0:02", "", "Trim -0:00.500 to 0:02.678", "Trim 0:70.500 to 0:02.678",
                     "Trim 0:00.5001 to 0:02.678", "0:00.500 0:01.000 0:02.678"):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                module.editor_timecodes(self.collage_node("video-editor-trim-value", text), "video-editor-trim-value")

    def test_editor_draft_values_require_both_trim_ends_and_interior_position(self):
        probe = self.probe()
        probe.manifest = self.editor_manifest()
        probe.find = Mock(side_effect=self.editor_find_value)
        self.assertEqual(dict(trimText="Trim from 0:00.500 to 0:02.500", positionText="Position 0:01.500 of 0:02.500",
                              startMillis=500, endMillis=2500, positionMillis=1500), probe.video_editor_values())
        for tag, bad in (("video-editor-trim-value", "Trim 0:00.000 to 0:02.500"),
                         ("video-editor-trim-value", "Trim 0:00.500 to 0:03.033"),
                         ("video-editor-position-value", "Position 0:00.500 of 0:02.500"),
                         ("video-editor-position-value", "Position 0:01.500 of 0:02.499")):
            probe.find.side_effect = lambda attr, value, **kw: self.collage_node(value, bad) if value == tag else self.editor_find_value(attr, value, **kw)
            with self.subTest(tag=tag, bad=bad), self.assertRaises(RuntimeError):
                probe.video_editor_values()

    def test_editor_trim_gestures_are_bounded_to_fresh_observed_control(self):
        probe = self.probe()
        first = self.collage_node("video-editor-trim", "")
        first.set("bounds", "[100,500][1100,600]")
        second = module.ET.fromstring(module.ET.tostring(first))
        second.set("bounds", "[200,500][1200,600]")
        nodes = iter((first, second))
        def find(*args, **kwargs):
            node = next(nodes)
            probe.ui_nodes = [node]
            return node
        probe.find = Mock(side_effect=find)
        probe.adjust_video_editor_trim()
        self.assertEqual([("input", "swipe", 150, 550, 300, 550, "400"),
                          ("input", "swipe", 1150, 550, 1000, 550, "400")], [call.args for call in probe.shell.call_args_list])
        self.assertEqual(2, probe.find.call_count)
        probe.shell.reset_mock()
        probe.find = Mock(return_value=first)
        probe.ui_nodes = []
        with self.assertRaisesRegex(RuntimeError, "not ready"):
            probe.adjust_video_editor_trim()
        probe.shell.assert_not_called()

    def test_editor_source_is_exact_owned_video_collection_and_whole_mp4_bytes(self):
        probe = self.probe()
        probe.scenario = "video-editor-draft"
        probe.args.serial = "emulator-5554"
        row = self.row()
        row.update(uri="content://media/external/video/media/21", _display_name=probe.name+"-0.mp4", relative_path="Movies/"+probe.name+"/")
        row["_data"] = "/storage/emulated/0/" + row["relative_path"] + row["_display_name"]
        data = (16).to_bytes(4, "big") + b"ftypisom" + bytes(4)
        probe.manifest = dict(byteSize=len(data), durationMillis=3033)
        probe.call.return_value = data, 0
        self.assertEqual(hashlib.sha256(data).hexdigest(), probe.source_hash(row))
        probe.call.assert_called_once_with("exec-out", "cat", row["_data"], binary=True)
        for changed in (data[:-1], b"Error: denied", data.replace(b"ftyp", b"nope")):
            with self.assertRaises(RuntimeError):
                module.verify_video_source_bytes(changed, probe.manifest)
        for field, value in (("uri", "content://media/external/images/media/21"), ("ordinal", 1), ("owner_package_name", "other")):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                probe.validate_source(dict(row, **{field: value}))
        probe.shell.return_value = "No result found.", 0
        self.assertEqual((), probe.creation_outputs(module.VIDEO_EDITOR_OUTPUT_PATH))
        self.assertIn("content://media/external/video/media?includePending=1", probe.shell.call_args.args)
        self.assertIn("owner_package_name='"+module.PACKAGE+"' AND relative_path='Movies/UGallery/'", probe.shell.call_args.args)

    def test_editor_setup_and_restore_preserve_audio_and_paused_values_without_reselecting_tab(self):
        probe = self.probe()
        probe.scenario = "video-editor-draft"
        probe.manifest = dict(self.editor_manifest(), rows=[self.row()])
        for name in ("require_no_instrumentation", "launch_normal", "stop_normal_task", "tap", "adjust_video_editor_trim", "require_no_video_editor_export"):
            setattr(probe, name, Mock())
        probe.creation_outputs = Mock(return_value=())
        probe.find = Mock(side_effect=self.editor_find_value)
        probe.setup_normal_app()
        probe.find.assert_any_call("resource-id", "media_external_primary_21", timeout=45)
        probe.find.assert_any_call("text", "Audio")
        probe.find.assert_any_call("text", "Original audio", scroll=False)
        probe.stop_normal_task.assert_called_once_with()
        self.assertEqual((500, 2500, 1500), tuple(probe.editor_draft[k] for k in ("startMillis", "endMillis", "positionMillis")))
        probe.find.reset_mock()
        probe.tap.reset_mock()
        probe.verify_video_editor_restored()
        probe.tap.assert_not_called()
        self.assertFalse(any(call.args == ("text", "Audio") for call in probe.find.call_args_list))
        probe.find.assert_any_call("text", "Original audio", scroll=False)
        probe.find.assert_any_call("content-desc", "Play preview", scroll=False)
        self.assertTrue(probe.no_auto_play_verified)
        probe.editor_draft = dict(probe.editor_draft, positionMillis=1000)
        with self.assertRaisesRegex(RuntimeError, "differs"):
            probe.verify_video_editor_restored()

    def test_editor_back_requires_discard_exact_original_details_hash_and_unchanged_outputs(self):
        probe = self.probe()
        probe.manifest = self.editor_manifest()
        probe.find = Mock()
        probe.tap = Mock()
        probe.source_hash = Mock(return_value=self.row()["sha256"])
        probe.require_no_video_editor_export = Mock()
        probe.verify_video_editor_back(self.row())
        probe.find.assert_any_call("text", "Discard edits?", scroll=False)
        probe.find.assert_any_call("text", "Discard", scroll=False)
        probe.find.assert_any_call("text", self.row()["_display_name"])
        probe.require_no_video_editor_export.assert_called_once_with("back-original-viewer")
        self.assertTrue(probe.no_auto_export_verified)
        probe.video_editor_output_baseline = ()
        probe.creation_outputs = Mock(return_value=(("unexpected-output",),))
        with self.assertRaisesRegex(RuntimeError, "outputs changed"):
            module.Probe.require_no_video_editor_export(probe, "restored-draft")

    def test_editor_native_seed_selects_one_owned_video_method(self):
        probe = self.probe()
        probe.scenario = "video-editor-draft"
        probe.fixture = "owned-uuid"
        probe.stdout, probe.stderr, probe.threads = [], [], []
        process = Mock(stdout=io.StringIO(""), stderr=io.StringIO(""))
        with patch.object(module.subprocess, "Popen", return_value=process) as popen:
            probe.start_native()
        for thread in probe.threads:
            thread.join()
        command = popen.call_args.args[0][-1]
        self.assertIn(module.VIDEO_EDITOR_TEST, command)
        self.assertNotIn(module.MOTION_TEST, command)
        self.assertNotIn(module.TEST, command)


if __name__ == "__main__":
    unittest.main()
