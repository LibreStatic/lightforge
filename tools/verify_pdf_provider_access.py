#!/usr/bin/env python3
"""Exercise Android URI revocation from an independently installed provider UID."""
import argparse,json,subprocess,time,uuid
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--baseline',action='store_true');args=p.parse_args()
b=['adb','-s',args.serial,'shell'];consumer='com.librestatic.lightforge.feature.pdfstudio.test';owner='com.librestatic.lightforge.pdfprovider.fixture'
def shell(*a):return subprocess.check_output(b+list(a),text=True,timeout=90).strip()
def control(action,doc='two'):
    nonce=str(uuid.uuid4())
    shell('am','start','-n',owner+'/.ControlActivity','--es','action',action,'--es','doc',doc,'--es','nonce',nonce)
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        result=subprocess.run(b+['run-as',owner,'cat','files/control.json'],text=True,capture_output=True)
        if result.returncode==0:
            value=json.loads(result.stdout)
            if value.get('nonce')==nonce:
                assert value['action']==action and value['doc']==doc;return value
        time.sleep(.1)
    raise AssertionError('Owner control not confirmed')
def phase(name):
    result=shell('am','instrument','-w','-e','phase',name,consumer+'/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner')
    print(result,flush=True);assert 'PDF RECOVERY '+name.upper()+' PASS' in result
    time.sleep(.3)
def phase_with_grant(name,action):
    log_path='/tmp/pdf-provider-'+name+'.log'
    shell('run-as',consumer,'rm','-f','files/pdf-provider-waiting','files/pdf-provider-ready')
    with open(log_path,'w') as log:
        process=subprocess.Popen(b+['am','instrument','-w','-e','phase','provider-'+name,consumer+'/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner'],stdout=log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            result=subprocess.run(b+['run-as',consumer,'cat','files/pdf-provider-waiting'],text=True,capture_output=True)
            if result.returncode==0 and result.stdout==name:break
            assert process.poll() is None, 'Instrumented receiver stopped before grant: '+log_path
            time.sleep(.1)
        else:raise AssertionError('Receiver observation expired; inspect the same process')
        owned=control(action)
        subprocess.run(b+['run-as',consumer,'tee','files/pdf-provider-ready'],input=name,text=True,stdout=subprocess.DEVNULL,check=True)
        process.wait(timeout=60)
    result=open(log_path).read();print(result.strip(),flush=True)
    assert 'PDF RECOVERY PROVIDER-'+name.upper()+' PASS' in result,result
    return owned
def checkpoint():return json.loads(shell('run-as',consumer,'cat','files/pdf-provider-access.json'))
owned=phase_with_grant('prepare','prepare');cp=checkpoint();assert cp['uid']!=owned['uid']
print('SEPARATE UIDS VERIFIED: '+json.dumps({'consumer':cp['uid'],'provider':owned['uid']},sort_keys=True),flush=True)
control('revoke');phase('provider-revoked-baseline' if args.baseline else 'provider-revoked');cp=checkpoint();assert cp['sourceError']==(-1 if args.baseline else 3)
print('OS REVOCATION PASS: persisted READ removed; openInputStream denied; unchanged project/editor; owned READ cleaned; external WRITE retained; source index '+str(cp['sourceError']),flush=True)
phase_with_grant('retry','grant')
if not args.baseline:
    component=owner+'/.FixtureDocumentsProvider'
    print(shell('pm','disable',component),flush=True)
    try:
        phase('provider-unavailable')
        phase('provider-finish')
    finally:print(shell('pm','enable',component),flush=True)
else:phase('provider-finish')
print('PROVIDER ACCESS PASS: cross-UID revocation; regrant/import retry; unchanged originals; permission ownership cleanup; real PDF verified'+('' if args.baseline else ' with provider disabled')+'; fixture project removed')
