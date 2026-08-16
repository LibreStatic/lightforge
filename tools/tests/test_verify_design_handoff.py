import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_design_handoff.py"
SPEC = importlib.util.spec_from_file_location("verify_design_handoff", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class HandoffIntegrityTest(unittest.TestCase):
    def test_handoff_has_expected_screens_and_no_broken_references(self) -> None:
        inventory, errors = MODULE.build_inventory()

        self.assertEqual([], errors)
        self.assertEqual(32, inventory["screenCount"])
        self.assertIn("docs/mock/styles/gallery-components.css", inventory["files"])
        self.assertIn("docs/mock/scripts/gallery-prototype.js", inventory["files"])
        self.assertIn("docs/mock/styles/gallery-tokens.css", inventory["files"])


if __name__ == "__main__":
    unittest.main()
