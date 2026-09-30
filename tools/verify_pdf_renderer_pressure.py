#!/usr/bin/env python3
"""Crash a confirmed renderer during a write-through syscall; verify host survival and rebound."""
import argparse,json,subprocess,time
parser=argparse.ArgumentParser();parser.add_argument("--serial",required=True);args=parser.parse_args()
b=["rtk","proxy","adb","-s",args.serial,"shell"];package="com.librestatic.lightforge.feature.pdfstudio.test";runner=package+"/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner"
def shell(*args): return subprocess.check_output(b+list(args),text=True,timeout=30)
def observe(stage, process):
    deadline=time.monotonic()+50
    while time.monotonic()<deadline:
        result=subprocess.run(b+["run-as",package,"cat","files/pdf-renderer-pressure.json"],capture_output=True,text=True)
        if result.returncode==0:
            cp=json.loads(result.stdout)
            if cp.get("stage")==stage:return cp
        assert process.poll() is None, "Fixture terminated; inspect /tmp/pdf-renderer-instrumentation.log"
        time.sleep(.2)
    raise AssertionError("Observation timed out; inspect the existing fixture, do not restart")
def renderer():
    rows=[line.split() for line in shell("ps","-A","-o","PID,NAME").splitlines()]
    matches=[int(row[0]) for row in rows if len(row)==2 and row[1].startswith(package+":pdf_processing")]
    assert len(matches)==1, matches
    return matches[0]
def record_pid(stage,pid):
    subprocess.run(b+["run-as",package,"tee","files/pdf-renderer-"+stage+".pid"], input=str(pid)+"\n", text=True, capture_output=True, check=True, timeout=30)
with open("/tmp/pdf-renderer-instrumentation.log","w") as log:
    process=subprocess.Popen(b+["am","instrument","-w","-e","phase","renderer-pressure",runner],stdout=log,stderr=subprocess.STDOUT)
    cp=observe("blocked",process);first=renderer()
    assert cp["bytes"]>0 and cp["host"]!=first and str(cp["host"]) in shell("pidof",package).split()
    record_pid("blocked",first)
    print("LIVE WRITE VERIFIED: "+json.dumps(dict(cp,renderer=first),sort_keys=True),flush=True)
    print(shell("am","crash",str(first)).strip(),flush=True)
    # A dying process can retain a FUSE syscall until the test releases its output callback.
    # Observe Android's actual SIGKILL/crash record before releasing, then verify termination.
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        logs=shell("logcat","-d","-t","500","-s","Process:I")
        if "Sending signal. PID: "+str(first)+" SIG: 9" in logs:break
        time.sleep(.1)
    else:raise AssertionError("No renderer termination signal observed")
    shell("run-as",package,"touch","files/pdf-renderer-blocked.release")
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        rows=[line.split() for line in shell("ps","-A","-o","PID,STAT,NAME").splitlines()]
        live=[row for row in rows if len(row)>=3 and row[0]==str(first) and not row[1].startswith("Z")]
        if not live:break
        time.sleep(.1)
    else:raise AssertionError("Target renderer still live after releasing its blocked syscall")
    rebound=observe("rebound",process);second=renderer()
    assert rebound["host"]==cp["host"] and second not in (first,cp["host"])
    record_pid("rebound",second)
    print("LIVE REBOUND VERIFIED: "+json.dumps(dict(rebound,renderer=second),sort_keys=True),flush=True)
    shell("run-as",package,"touch","files/pdf-renderer-rebound.release")
    process.wait(timeout=70)
output=open("/tmp/pdf-renderer-instrumentation.log").read();print(output.strip(),flush=True)
assert "PDF RECOVERY RENDERER-PRESSURE PASS" in output
restored=json.loads(shell("run-as",package,"cat","files/pdf-renderer-pressure.json"))
assert restored["status"]=="PASS" and restored["host"]==cp["host"] and restored["replacement"]==second
shell("run-as",package,"rm","files/pdf-renderer-pressure.json")
print("RENDERER PRESSURE PASS: live isolated renderer crashed during output write; host PID survived; typed RendererUnavailable; partial output removed; fresh renderer exported and verified three pages")
