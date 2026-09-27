#!/usr/bin/env python3
"""Stop a confirmed running PDF WorkManager fixture; recover its immutable export in a new PID."""
import argparse
import subprocess
parser=argparse.ArgumentParser();parser.add_argument("--serial",required=True);args=parser.parse_args()
prefix=["rtk","proxy","adb","-s",args.serial,"shell"]
package="com.librestatic.lightforge.feature.pdfstudio.test"
runner=package+"/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner"
def phase(name):
    output=subprocess.check_output(prefix+["am","instrument","-w","-e","phase",name,runner],text=True)
    print(output.strip(),flush=True)
    assert "PDF RECOVERY "+name.upper()+" PASS" in output
phase("queue-write")
subprocess.run(prefix+["am","force-stop",package],check=True)
phase("queue-read")
print("PDF QUEUE RECOVERY PASS: running WorkManager job resumed in new PID; deleted project sources retained; real two-page PDF verified")
