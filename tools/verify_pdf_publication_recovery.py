#!/usr/bin/env python3
"""Force-stop actual SAF publication; verify partial retry and completed-output idempotency."""
import argparse
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
args = parser.parse_args()
prefix = ["adb", "-s", args.serial, "shell"]
package = "com.librestatic.lightforge.feature.pdfstudio.test"
runner = package + "/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner"


def phase(name):
    output = subprocess.check_output(
        prefix + ["am", "instrument", "-w", "-e", "phase", name, runner], text=True, timeout=90
    )
    print(output.strip(), flush=True)
    assert "PDF RECOVERY " + name.upper() + " PASS" in output


for scenario in ("publication", "publication-complete"):
    phase(scenario + "-write")
    # Android 11 can still be tearing down the runner/provider after instrument returns.
    time.sleep(2)
    subprocess.run(prefix + ["am", "force-stop", package], check=True, timeout=20)
    time.sleep(2)
    phase(scenario + "-read")
    time.sleep(2)
print("PDF PUBLICATION RECOVERY PASS: new PIDs; partial resumed; complete not rewritten; hashes and PDFs verified; owned grants released")
