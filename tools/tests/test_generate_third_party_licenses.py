#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "generate_third_party_licenses.py"
SPEC = importlib.util.spec_from_file_location("generate_third_party_licenses", MODULE_PATH)
assert SPEC and SPEC.loader
licenses = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(licenses)


class ThirdPartyLicenseUnitTest(unittest.TestCase):
    def generate_line(self, coordinate: str) -> dict[str, object]:
        with tempfile.TemporaryDirectory() as directory:
            lockfile = Path(directory) / "gradle.lockfile"
            lockfile.write_text(
                f"{coordinate}=offlineReleaseRuntimeClasspath\n",
                encoding="utf-8",
            )
            return licenses.generate(lockfile)

    def test_only_offline_release_runtime_is_included(self):
        with tempfile.TemporaryDirectory() as directory:
            lockfile = Path(directory) / "gradle.lockfile"
            lockfile.write_text(
                "androidx.core:core:1.0=offlineReleaseRuntimeClasspath\n"
                "junit:junit:4.13.2=offlineReleaseUnitTestRuntimeClasspath\n",
                encoding="utf-8",
            )
            document = licenses.generate(lockfile)
            self.assertEqual(len(document["components"]), 1)
            self.assertEqual(document["components"][0]["licenseId"], "Apache-2.0")

    def test_coordinate_override_uses_bsd_license(self):
        document = self.generate_line("androidx.appsearch:appsearch-external-protobuf:1.1")
        self.assertEqual(document["components"][0]["licenseId"], "BSD-3-Clause")

    def test_mlkit_play_services_uses_mlkit_terms(self):
        document = self.generate_line(
            "com.google.android.gms:play-services-mlkit-face-detection:17.1.0"
        )
        self.assertEqual(document["components"][0]["licenseId"], "LicenseRef-ML-Kit-Terms")

    def test_unknown_dependency_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "No reviewed license rule"):
            self.generate_line("com.example:unknown:1.0")

    def test_missing_license_text_fails_closed(self):
        document = self.generate_line("androidx.core:core:1.0")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "Missing or empty bundled license text"):
                licenses.validate_license_assets(document, Path(directory))


if __name__ == "__main__":
    unittest.main()
