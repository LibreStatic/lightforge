#!/usr/bin/env python3
import importlib.util
import hashlib
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

    def test_pdfbox_exact_coordinate_links_bundled_terms_and_notices(self):
        document = self.generate_line("com.tom-roush:pdfbox-android:2.0.27.0")
        component = document["components"][0]
        self.assertEqual(component["licenseId"], "LicenseRef-PDFBox-Android-Bundled")
        self.assertEqual(
            component["licenseTextAsset"], "licenses/PDFBox-Android-Notices.txt"
        )
        # The override is exact, not applied to every artifact in the group.
        self.assertEqual(
            licenses.license_for("com.tom-roush", "another-artifact"), licenses.APACHE
        )
        with self.assertRaisesRegex(ValueError, "No reviewed license rule"):
            licenses.license_for("com.example", "pdfbox-android")

    def test_pdfbox_asset_preserves_complete_tagged_upstream_bytes_offline(self):
        document = self.generate_line("com.tom-roush:pdfbox-android:2.0.27.0")
        assets = MODULE_PATH.parents[1] / "app/src/main/assets"
        licenses.validate_license_assets(document, assets)
        asset = assets / document["components"][0]["licenseTextAsset"]
        data = asset.read_bytes()
        prefix = (
            b"PDFBox-Android 2.0.27.0 - upstream license and attribution inventory\n\n"
            b"https://raw.githubusercontent.com/TomRoush/PdfBox-Android/v2.0.27.0/LICENSE.txt\n\n"
        )
        separator = (
            b"\n\nhttps://raw.githubusercontent.com/TomRoush/PdfBox-Android/v2.0.27.0/NOTICE.txt\n\n"
        )
        self.assertTrue(data.startswith(prefix))
        self.assertEqual(data.count(separator), 1)
        license_bytes, notice_bytes = data[len(prefix):].split(separator)
        # Pinned byte lengths and SHA-256 from the reviewed upstream tag; no
        # network or separately installed evidence directory is needed by tests.
        self.assertEqual(len(license_bytes), 16295)
        self.assertEqual(len(notice_bytes), 652)
        self.assertEqual(
            hashlib.sha256(license_bytes).hexdigest(),
            "8ceed6051cbd7f6d5d56bad8ca5e73f0414fb13cbe0c4170f7c4c9beaa1a7679",
        )
        self.assertEqual(
            hashlib.sha256(notice_bytes).hexdigest(),
            "8191c60848b9e5666a1ee50a65a7f6eab7339dc1ea20b52092f3eac5ef10d0cd",
        )

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
