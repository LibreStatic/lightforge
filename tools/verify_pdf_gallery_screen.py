#!/usr/bin/env python3
"""Install an opt-in, separate app ID and exercise the real gallery-to-PDF UI.

Build both APKs with -Plightforge.pdfAcceptance=true first. The target must be a
fresh acceptance install. This observer never clears or uninstalls an app and
leaves evidence/failed state available for inspection rather than retrying.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    target = "com.librestatic.lightforge.pdfacceptance"
    test = target + ".test"
    adb = ["rtk", "proxy", "adb", "-s", args.serial]
    args.output.mkdir(parents=True, exist_ok=True)

    def command(*parts):
        return subprocess.check_output(list(parts), timeout=60)

    for apk, expected in ((args.app_apk, target), (args.test_apk, test)):
        badging = command("rtk", "proxy", str(args.aapt), "dump", "badging", str(apk)).decode()
        assert re.search(r"package: name='([^']+)'", badging)[1] == expected
    hashes = {}
    for apk in (args.app_apk, args.test_apk):
        with apk.open("rb") as stream:
            hashes[str(apk)] = hashlib.file_digest(stream, "sha256").hexdigest()
    (args.output / "inputs.json").write_text(json.dumps({
        "serial": args.serial,
        "apks": hashes,
        "target": target,
    }, indent=2) + "\n")
    manifest = command("rtk", "proxy", str(args.aapt), "dump", "xmltree",
                       str(args.test_apk), "--file", "AndroidManifest.xml").decode()
    assert re.search(r'targetPackage[^\n]*="' + re.escape(target) + '"', manifest)
    packages = command(*adb, "shell", "pm", "list", "packages").decode().splitlines()
    assert "package:" + target not in packages, "Inspect the existing acceptance run; use a fresh fixture only after it ends"
    assert "package:" + test not in packages, "Existing acceptance test APK must be inspected first"
    def user_install():
        installed = command(*adb, "shell", "pm", "list", "packages").decode().splitlines()
        if "package:com.librestatic.lightforge.debug" not in installed:
            return None
        return command(*adb, "shell", "pm", "path", "com.librestatic.lightforge.debug")

    user_path = user_install()
    for apk in (args.app_apk, args.test_apk):
        output = command(*adb, "install", str(apk)).decode()
        assert "Success" in output, output
    run = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-e", "class",
                               "com.librestatic.lightforge.PdfGallerySelectionUiDeviceTest",
                               test + "/androidx.test.runner.AndroidJUnitRunner"],
                         capture_output=True, timeout=360)
    literal = (run.stdout + run.stderr).decode()
    (args.output / "instrumentation.log").write_text(literal)
    # Capture native evidence even on a terminal test failure. adb's zero exit
    # status does not imply AndroidJUnitRunner passed.
    archive = command(*adb, "exec-out", "run-as", target, "tar", "-cf", "-", "files/pdf-gallery-screen")
    (args.output / "native-evidence.tar").write_bytes(archive)
    with tarfile.open(fileobj=io.BytesIO(archive)) as contents:
        for entry in contents.getmembers():
            if not entry.isfile():
                continue
            name = Path(entry.name)
            assert name.parent == Path("files/pdf-gallery-screen")
            (args.output / name.name).write_bytes(contents.extractfile(entry).read())
    assert user_path == user_install()
    assert run.returncode == 0 and re.search(r"OK \(1 test\)", literal) and "FAILURES!!!" not in literal, literal
    result = json.loads((args.output / "result.json").read_text())
    assert result["status"] == "PASS" and result["selected"] == 3 and result["projects"] == 1
    assert all(result[key] for key in ("permissionUi", "sourceHashes", "selectionRestored", "clearedByUser", "recreated", "reopenedWithoutDuplicate"))
    print("GALLERY UI PASS: Android permission + explicit onboarding choice; 3 selected images; exact hashes; 1 project/1 PDF page; Activity recreation; selection retained; explicit clear; reopen without duplicate; user app install unchanged")


if __name__ == "__main__":
    main()
