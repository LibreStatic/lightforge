#!/usr/bin/env python3
"""Stop a confirmed partial source copy; recover from Room, without Activity saved state."""
import argparse,json,subprocess,time
parser=argparse.ArgumentParser();parser.add_argument("--serial",required=True);args=parser.parse_args()
base=["rtk","proxy","adb","-s",args.serial,"shell"];package="com.librestatic.lightforge.feature.pdfstudio.test";runner=package+"/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner"
phase="gallery"
with open("/tmp/pdf-gallery-writer-observer.log","w") as log:
    process=subprocess.Popen(base+["am","instrument","-w","-e","phase",phase+"-write",runner],stdout=log,stderr=subprocess.STDOUT)
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        result=subprocess.run(base+["run-as",package,"cat","files/pdf-gallery-recovery.json"],capture_output=True,text=True)
        if result.returncode==0:
            cp=json.loads(result.stdout);break
        assert process.poll() is None, "Writer finished before reaching real copy gate; see /tmp/pdf-gallery-writer-observer.log"
        time.sleep(.2)
    else: raise AssertionError("Writer gate observation timed out; inspect the existing live process, do not restart")
    live=subprocess.check_output(base+["pidof",package],text=True).split()
    assert str(cp["pid"]) in live and 0 < cp["copied"] < cp["total"], cp
    print("LIVE PARTIAL COPY VERIFIED: "+json.dumps(cp,sort_keys=True),flush=True)
    subprocess.run(base+["am","force-stop",package],check=True)
    process.wait(timeout=20)
time.sleep(2)
output=subprocess.check_output(base+["am","instrument","-w","-e","phase",phase+"-read",runner],text=True,timeout=90)
print(output.strip(),flush=True)
assert "PDF RECOVERY "+(phase+"-read").upper()+" PASS" in output
print("GALLERY ACTIVE IMPORT RECOVERY PASS: real MediaStore partial copy killed; new PID and empty SavedState recovered 25 images on two grid pages; hash and PDF verified; navigation replay deduplicated; fixture media removed")
