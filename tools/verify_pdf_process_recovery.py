#!/usr/bin/env python3
"""Verify a real process boundary using only the PDF instrumentation fixture package."""
import argparse
import subprocess
parser=argparse.ArgumentParser()
parser.add_argument("--serial",required=True)
args=parser.parse_args()
prefix=["adb","-s",args.serial,"shell"]
package="com.librestatic.lightforge.feature.pdfstudio.test"
runner=package+"/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner"
def phase(name):
    output=subprocess.check_output(prefix+["am","instrument","-w","-e","phase",name,runner],text=True)
    print(output.strip())
    assert "PDF RECOVERY "+name.upper()+" PASS" in output, "recovery phase failed"
phase("write")
subprocess.run(prefix+["am","force-stop",package],check=True)
phase("read")
print("PDF PROCESS RECOVERY PASS: new PID, selection, viewport, preferences, history and export restored")
