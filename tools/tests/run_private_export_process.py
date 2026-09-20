#!/usr/bin/env python3
"""One owned uninstrumented Ready→HOME→am kill→same-task recovery; no normal app or credentials."""
import argparse, hashlib, json, re, shlex, subprocess, sys, time, uuid
from pathlib import Path
import xml.etree.ElementTree as ET
import run_creation_process_restoration as shared

PACKAGE = "com.ugallery.feature.privatealbum.test"
ACTIVITY = PACKAGE + "/com.ugallery.feature.privatealbum.PrivateExportProcessActivity"
DEVICES = {"emulator-5554": ("UGallery_M2_API30", "30", "ro.kernel.qemu.avd_name"),
           "127.0.0.1:5563": ("UGallery_PDF_API35", "35", "ro.boot.qemu.avd_name")}
OBSERVER_SHA = "a8c8d1fec35e6477e05cff19c2c828583411bbafadbacc26d9757185804ddbe0"
shared.PACKAGE = PACKAGE
shared.TEST_PACKAGE = PACKAGE
shared.ACTIVITY = ACTIVITY

def require(value, message):
    if not value: raise RuntimeError(message)

def fixture_id(value):
    require(isinstance(value, str) and str(uuid.UUID(value)) == value, "Canonical fixture UUID required")
    return value

def target(serial):
    require(serial in DEVICES, "Assigned emulator serial required")
    return DEVICES[serial]

def validate_receipt(value, identity):
    require(isinstance(value, dict) and value.get("fixture") == identity, "Fixture receipt identity differs")
    return value

def process_absent_result(stdout, code):
    require(code in (0, 1) and stdout.strip() == f"UGALLERY_PROC_STATUS:{code}", "Process observation is not authoritative")
    return code == 1

def observer_xml_path(identity, counter):
    fixture_id(identity)
    require(type(counter) is int and counter > 0, "Positive observer counter required")
    return f"/sdcard/creation-process-private-export-{identity}-{counter}.xml"

def confirmation_target(nodes, name):
    expected=("Forget recovery record", name,
        "Close this recovery record without deleting any exported file? An unencrypted copy may still exist outside the private album. Exporting again may create duplicates.", "Confirm", "Cancel")
    selected=[]
    for text in expected:
        matches=[n for n in nodes if n.get("package")==PACKAGE and n.get("text")==text and n.get("enabled")=="true"]
        require(len(matches)==1,"Exact owned English recovery confirmation is missing or ambiguous")
        selected.append(matches[0])
    return selected[3]

def state_ready(value, state):
    return value.get("state") == state and (state != "Acked" or value.get("databaseClosed") is True)

def require_missing_receipt(code, stderr, path):
    require(code == 1 and "No such file or directory" in stderr and path in stderr, "Receipt read denied or ambiguous")

class Run:
    def __init__(self, args):
        self.args = args; self.id = fixture_id(args.fixture_uuid); target(args.serial)
        self.out = Path(args.output); self.out.mkdir(parents=True, exist_ok=False)
        self.events = []; self.dumps = []; self.counter = 0; self.identity = {}
    def record(self, **event):
        self.events.append(event); (self.out/'commands.json').write_text(json.dumps(self.events, indent=2))
    def call(self, *args, check=True, timeout=30):
        command = ['rtk','proxy','adb','-s',self.args.serial,*map(str,args)]
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        stdout=result.stdout.decode(errors='replace'); stderr=result.stderr.decode(errors='replace')
        self.record(command=command,input=self.id,stdout=stdout,stderr=stderr,exit=result.returncode)
        require(not check or result.returncode == 0, "ADB command failed")
        return stdout, result.returncode, stderr
    def shell(self, *args, **kwargs): return self.call('shell',shlex.join(map(str,args)),**kwargs)
    def guard(self):
        avd,api,prop = target(self.args.serial)
        require(self.shell('getprop',prop)[0].strip()==avd, "AVD differs")
        require(self.shell('getprop','ro.build.version.sdk')[0].strip()==api, "API differs")
        require(self.shell('getprop','ro.kernel.qemu')[0].strip()=='1', "Emulator required")
        require('Active instrumentation' not in self.shell('dumpsys','activity','processes')[0], "Instrumentation is active")
        apk=Path(self.args.apk).resolve(); require(apk.is_file(), "Test APK missing")
        badging=subprocess.run(['rtk','proxy','/home/user/Android/Sdk/build-tools/36.0.0/aapt','dump','badging',str(apk)],capture_output=True,text=True)
        self.record(command=badging.args,input=str(apk),stdout=badging.stdout,stderr=badging.stderr,exit=badging.returncode)
        require(badging.returncode==0 and "package: name='"+PACKAGE+"'" in badging.stdout, "Wrong APK package")
        require('Success' in self.call('install','-r','--no-streaming',apk,timeout=120)[0], "Test APK install failed")
        installed=self.shell('pm','path',PACKAGE)[0].strip(); require(installed.startswith('package:') and len(installed.splitlines())==1, "Ambiguous installed APK")
        sha=hashlib.sha256(apk.read_bytes()).hexdigest(); require(self.shell('sha256sum',installed[8:])[0].split()[0]==sha, "Installed APK changed")
        path=shared.observer_remote_path(OBSERVER_SHA)
        require(self.shell('sha256sum',path)[0].split()==[OBSERVER_SHA,path], "Observer bytes differ")
        self.identity=dict(apkSha256=sha,installedPath=installed[8:],uid=int(self.shell('run-as',PACKAGE,'id','-u')[0].strip()))
        self.record(identity=self.identity)
    def receipt(self, optional=False):
        path=f'files/private-export-process-{self.id}.json'
        stdout,code,stderr=self.shell('run-as',PACKAGE,'cat',path,check=False)
        if optional and code != 0:
            require_missing_receipt(code, stderr, path)
            return None
        require(code==0 and not stderr, "Receipt unavailable")
        value=validate_receipt(json.loads(stdout),self.id)
        self.counter+=1;(self.out/f'receipt-{self.counter:03d}.json').write_text(stdout)
        return value
    def wait_state(self, state, timeout=35):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            value=self.receipt(optional=True)
            if value:
                require(value.get('state')!='Failed' and 'failure' not in value, 'Fixture failed: '+json.dumps(value))
                if state_ready(value,state):return value
            time.sleep(.25)
        raise RuntimeError('Expected fixture state not observed: '+state)
    def start(self, mode):
        result=self.shell('am','start','-W','-f','0x14000000','-n',ACTIVITY,'--es','mode',mode,'--es','fixtureUuid',self.id)
        require('Error:' not in result[0]+result[2] and 'Status: ok' in result[0], "Activity launch not confirmed")
    def process(self, pid):
        require(type(pid) is int and pid>1, "Invalid PID")
        require(self.shell('pidof',PACKAGE)[0].split()==[str(pid)], "Process is not sole fixture package")
        require(self.call('exec-out','run-as',PACKAGE,'cat',f'/proc/{pid}/cmdline')[0].rstrip('\x00\r\n')==PACKAGE, "PID command differs")
        status=shared.uninstrumented_process(self.shell('dumpsys','activity','processes')[0],expected_pid=pid,expected_uid=self.identity['uid'])
        self.record(normalProcess=status,pid=pid)
        return self.call('exec-out','run-as',PACKAGE,'cat',f'/proc/{pid}/stat')[0].rsplit(')',1)[1].split()[19]
    def tree(self):
        self.counter+=1;path=observer_xml_path(self.id,self.counter);self.dumps.append(path)
        observer=shared.observer_remote_path(OBSERVER_SHA)
        stdout,code,stderr=self.shell('env','CLASSPATH=/system/framework/uiautomator.jar:'+observer,'app_process','/system/bin','com.ugallery.tools.RealDisplayDump',path,timeout=40)
        require(code==0 and not stderr, "UI observer failed")
        proof=shared.real_display_receipt(stdout,path)
        xml=self.call('exec-out','cat',path)[0];nodes=list(ET.fromstring(xml).iter('node'))
        require(len(nodes)==proof['nodes'], "Observer hierarchy mismatch")
        (self.out/f'ui-{self.counter:03d}.xml').write_text(xml)
        return nodes
    def wait_node(self, tag):
        until=time.monotonic()+25
        while time.monotonic()<until:
            nodes=self.tree();found=[n for n in nodes if n.get('package')==PACKAGE and n.get('resource-id')==tag and n.get('enabled')=='true']
            if len(found)==1:
                node=found[0];require(shared.center_visible(node,nodes), "UI target clipped")
                return node
            require(len(found)<2, "Ambiguous UI target")
            time.sleep(.25)
        raise RuntimeError('UI target not observed: '+tag)
    def click(self, tag):
        node=self.wait_node(tag);x1,y1,x2,y2=shared.bounds(node)
        self.shell('input','tap',(x1+x2)//2,(y1+y2)//2)
    def confirm_close(self, recovered):
        nodes=self.tree()
        node=confirmation_target(nodes,recovered['readySnapshot']['name'])
        require(shared.center_visible(node,nodes), "Confirmation target clipped")
        x1,y1,x2,y2=shared.bounds(node)
        self.shell('input','tap',(x1+x2)//2,(y1+y2)//2)
    def execute(self):
        self.guard()
        require(self.receipt(optional=True) is None, "Existing receipt must be preserved")
        self.start('prepare');ready=self.wait_state('Ready')
        old=ready['preparePid'];task=ready['prepareTaskId'];ticks=self.process(old)
        require(ready['pid']==old, "Ready process differs")
        self.shell('input','keyevent','KEYCODE_HOME')
        until=time.monotonic()+15
        while True:
            state=self.receipt();dump=self.shell('dumpsys','activity','activities',PACKAGE)[0]
            stopped,block=shared.saved_stopped_activity(dump,task)
            if stopped and state.get('stopped') and state.get('stopPid')==old:break
            require(time.monotonic()<until,"Owned normal task did not STOP/save")
            time.sleep(.2)
        require(self.process(old)==ticks, "Process changed before kill")
        self.record(beforeKill=dict(pid=old,task=task,startTicks=ticks,activityRecord=block))
        self.shell('am','kill','--user','0',PACKAGE)
        until=time.monotonic()+15
        while True:
            script=f'test -d /proc/{old}; status=$?; printf "UGALLERY_PROC_STATUS:%s\\n" "$status"; exit "$status"'
            stdout,code,_=self.shell('run-as',PACKAGE,'sh','-c',script,check=False)
            if process_absent_result(stdout,code):break
            require(time.monotonic()<until,"Old process remains live; no repeat kill")
            time.sleep(.2)
        self.record(deathConfirmed=dict(pid=old,task=task))
        self.start('recover');recovered=self.wait_state('RecoveredReady')
        require(recovered['pid']!=old and recovered['taskId']==task, "Expected new PID and retained task")
        self.process(recovered['pid'])
        require(recovered['outputUri']==ready['outputUri'], "Output URI changed during recovery")
        self.finish_recovery(ready,recovered,old,task)
    def finish_recovery(self, ready, recovered, old, task):
        export_id=recovered['exportId'];row='private-export-recovery-row-'+export_id
        self.click(row);self.click('private-export-recovery-complete')
        self.wait_node('private-export-recovery-refresh')
        self.click('private-export-process-verify');completed=self.wait_state('Completed')
        self.click(row);self.click('private-export-recovery-close-result');self.confirm_close(recovered)
        self.finish_ack(ready,recovered,old,task)
    def finish_ack(self, ready, recovered, old, task):
        self.wait_node('private-export-recovery-refresh')
        self.click('private-export-process-verify');acked=self.wait_state('Acked')
        require(acked['outputUri']==ready['outputUri'] and acked['databaseClosed'], "ACK/source snapshot incomplete")
        self.start('cleanup');cleaned=self.wait_state('Cleaned')
        require(cleaned['cleanupComplete'], "Cleanup unconfirmed")
        root=f'cache/private-export-process-{self.id}'
        require(self.shell('run-as',PACKAGE,'sh','-c',f'test ! -e {root} && test ! -L {root} && echo OWNED_ROOT_ABSENT')[0].strip()=='OWNED_ROOT_ABSENT', "Owned root remains")
        require(self.shell('content','query','--uri',ready['outputUri'],'--projection','_id')[0].strip()=='No result found.', "Owned output remains")
        for path in self.dumps:self.shell('rm',path)
        result=dict(status='PASS',fixture=self.id,oldPid=old,newPid=recovered['pid'],taskId=task,outputUri=ready['outputUri'],apkSha256=self.identity['apkSha256'],cleanupComplete=True)
        (self.out/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);parser.add_argument('--fixture-uuid',required=True)
    parser.add_argument('--apk',default='feature/privatealbum/build/outputs/apk/androidTest/debug/privatealbum-debug-androidTest.apk');parser.add_argument('--output',required=True)
    Run(parser.parse_args()).execute()
if __name__=='__main__':main()
