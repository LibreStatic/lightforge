#!/usr/bin/env python3
"""Recover a confirmed running portable project export in another Android process."""
import argparse
import subprocess
import time
parser=argparse.ArgumentParser();parser.add_argument("--serial",required=True);args=parser.parse_args()
prefix=["adb","-s",args.serial,"shell"]
package="com.librestatic.lightforge.feature.pdfstudio.test"
runner=package+"/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner"
def phase(name):
    output=subprocess.check_output(prefix+["am","instrument","-w","-e","phase",name,runner],text=True,timeout=100)
    print(output.strip(),flush=True)
    assert "PDF RECOVERY "+name.upper()+" PASS" in output
phase("portable-write")
time.sleep(2)
subprocess.run(prefix+["am","force-stop",package],check=True)
time.sleep(2)
phase("portable-read")
print("PDF PORTABLE RECOVERY PASS: running archive job recovered in new PID; deleted project sources retained; ZIP hashes verified; two-page project imported")
