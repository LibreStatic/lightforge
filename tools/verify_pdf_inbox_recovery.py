#!/usr/bin/env python3
"""Stop a confirmed partial source copy; recover from Room, without Activity saved state."""
import argparse,json,subprocess,time
parser=argparse.ArgumentParser();parser.add_argument("--serial",required=True);parser.add_argument("--portable",action="store_true");args=parser.parse_args()
base=["rtk","proxy","adb","-s",args.serial,"shell"];package="com.ugallery.feature.pdfstudio.test";runner=package+"/com.ugallery.feature.pdfstudio.PdfRecoveryProbeRunner"
phase="inbox-portable" if args.portable else "inbox"
with open("/tmp/pdf-inbox-writer-observer.log","w") as log:
    process=subprocess.Popen(base+["am","instrument","-w","-e","phase",phase+"-write",runner],stdout=log,stderr=subprocess.STDOUT)
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        result=subprocess.run(base+["run-as",package,"cat","files/pdf-inbox-recovery.json"],capture_output=True,text=True)
        if result.returncode==0:
            cp=json.loads(result.stdout);break
        assert process.poll() is None, "Writer finished before reaching real copy gate; see /tmp/pdf-inbox-writer-observer.log"
        time.sleep(.2)
    else: raise AssertionError("Writer gate observation timed out; inspect the existing live process, do not restart")
    live=subprocess.check_output(base+["pidof",package],text=True).split()
    assert str(cp["pid"]) in live and 0 < cp["copied"] < cp["total"] and cp["portable"]==args.portable, cp
    print("LIVE PARTIAL COPY VERIFIED: "+json.dumps(cp,sort_keys=True),flush=True)
    subprocess.run(base+["am","force-stop",package],check=True)
    process.wait(timeout=20)
time.sleep(2)
output=subprocess.check_output(base+["am","instrument","-w","-e","phase",phase+"-read",runner],text=True,timeout=90)
print(output.strip(),flush=True)
assert "PDF RECOVERY "+(phase+"-read").upper()+" PASS" in output
print(("PORTABLE" if args.portable else "IMAGE")+" ACTIVE IMPORT RECOVERY PASS: confirmed partial copy killed; Room inbox restored in new PID without SavedState; persisted source access retained then released; original target and hashes verified; stale staging cleaned; two-page PDF exported")
