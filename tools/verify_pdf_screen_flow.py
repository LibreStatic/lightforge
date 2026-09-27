#!/usr/bin/env python3
"""Drive the actual Compose Studio and Android DocumentsUI; fixture-only data and controls."""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',required=True);p.add_argument('--resume',action='store_true');p.add_argument('--baseline',action='store_true');args=p.parse_args()
out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
b=['rtk','proxy','adb','-s',args.serial];pkg='com.ugallery.feature.pdfstudio.test'
def adb(*a):return subprocess.check_output(b+list(a),timeout=45)
def shell(*a):return adb('shell',*a).decode().strip()
def marker(name):return json.loads(shell('run-as',pkg,'cat','files/pdf-flow-'+name+'.json'))
def wait(predicate,seconds=60):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            s=marker('state')
            if predicate(s):return s
        except (subprocess.CalledProcessError,ValueError):pass
        time.sleep(.2)
    raise AssertionError('State did not reach expected transition: '+repr(marker('state')))
def dump():
    for _ in range(4):
        try:
            shell('uiautomator','dump','/sdcard/pdf-flow-window.xml')
            raw=adb('exec-out','cat','/sdcard/pdf-flow-window.xml');return ET.fromstring(raw),raw
        except (subprocess.CalledProcessError,ET.ParseError):time.sleep(.4)
    raise AssertionError('Accessibility observer failed; inspect same live Activity')
def match(n,label):return n.get('text')==label or n.get('content-desc')==label
# The quality cards compose their own contentDescription as "<label>, <estimate>" (so TalkBack
# reads the live size estimate, not just the label) instead of exposing exactly the bare label.
def match_prefix(n,label):
    text=n.get('text') or n.get('content-desc') or ''
    return text==label or text.startswith(label+', ')
def visible_prefix(label):return any(match_prefix(n,label) for n in dump()[0].iter('node'))
def tap_where(predicate):
    # UI transitions (sheets closing, library/editor swaps) are not instantaneous: retry briefly
    # before failing, and keep the screen that was actually showing when it does fail.
    for attempt in range(12):
        root,raw=dump();parents={c:p for p in root.iter() for c in p};matches=[]
        for n in root.iter('node'):
            if not predicate(n):continue
            original=n
            while n.get('clickable')!='true' and n in parents:n=parents[n]
            if n.get('clickable')=='true' and n.get('enabled')=='true':matches.append(n)
            elif 'documentsui' in original.get('package','') and original.get('enabled')=='true':matches.append(original)
        if matches:break
        time.sleep(.5)
    if not matches:
        (out/'tap-failure.xml').write_bytes(raw);(out/'tap-failure.png').write_bytes(adb('exec-out','screencap','-p'))
    assert matches,'Enabled UI control missing'
    n=matches[-1];x,y,r,d=map(int,re.findall(r'\d+',n.get('bounds')))
    if r>x and d>y:shell('input','tap',str((x+r)//2),str((y+d)//2))
    else:
        for _ in range(20):
            shell('input','keyevent','61');tree,_=dump()
            if any(predicate(x) and x.get('focused')=='true' for x in tree.iter('node')):
                shell('input','keyevent','66');break
        else:raise AssertionError('Zero-size control did not receive focus')
    time.sleep(.4)
def tap(label):tap_where(lambda n:match(n,label))
def visible(label):return any(match(n,label) for n in dump()[0].iter('node'))
def snap(name):
    root,raw=dump();(out/(name+'.xml')).write_bytes(raw);(out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    (out/(name+'-state.json')).write_text(json.dumps(marker('state'),indent=2)+'\n')
def editor():
    for _ in range(5):
        if visible('Project actions'):return
        shell('input','keyevent','4');time.sleep(.5)
    raise AssertionError('Editor not visible')
def downloads():
    if not any(n.get('resource-id','').endswith('/roots_list') for n in dump()[0].iter('node')):
        tap_where(lambda n:n.get('content-desc') in ('Show roots','Mostrar raíces') or n.get('resource-id')=='android:id/home')
    tap_where(lambda n:n.get('text') in ('Downloads','Descargas'))
def choose_file(name):
    def item(n):return n.get('text')==name or n.get('content-desc','').startswith(name+',')
    for _ in range(30):
        root,_=dump()
        if any(n.get('focused')=='true' and any(item(c) for c in n.iter('node')) for n in root.iter('node')):
            shell('input','keyevent','66');return
        shell('input','keyevent','61')
    raise AssertionError('Source filename did not receive focus: '+name)
def save_file(name):
    downloads()
    root,_=dump();edits=[n for n in root.iter('node') if n.get('class')=='android.widget.EditText'];assert edits
    n=edits[-1];x,y,r,d=map(int,re.findall(r'\d+',n.get('bounds')))
    if r>x and d>y:shell('input','tap',str((x+r)//2),str((y+d)//2))
    for _ in range(20):
        if any(n.get('class')=='android.widget.EditText' and n.get('focused')=='true' for n in dump()[0].iter('node')):break
        shell('input','keyevent','61')
    else:raise AssertionError('Filename input did not receive focus')
    shell('input','keyevent','123',*['67']*90)
    shell('input','text',name)
    assert any(n.get('class')=='android.widget.EditText' and n.get('text')==name for n in dump()[0].iter('node')), 'Filename edit was not applied'
    tap_where(lambda n:n.get('text','').upper() in ('SAVE','GUARDAR') or n.get('resource-id')=='android:id/button1')

checkpoint=out/'checkpoint.json'
if not args.resume:
    assert not checkpoint.exists(),'Use --resume to retain the existing fixture'
    for name in ('state','done'):shell('run-as',pkg,'rm','-f','files/pdf-flow-'+name+'.json')
    shell('am','start','-W','-n',pkg+'/com.ugallery.feature.pdfstudio.PdfFlowProbeActivity')
    wait(lambda s:not s['busy']);cp={'step':0,'name':'UGallery-screen-flow-'+str(time.time_ns())};checkpoint.write_text(json.dumps(cp))
else:cp=json.loads(checkpoint.read_text())
def run(number,name,action):
    if cp['step']>=number:return
    action();snap(name);cp['step']=number;checkpoint.write_text(json.dumps(cp));print('SCREEN FLOW STEP PASS: '+name,flush=True)

def create():
    if not marker('state').get('project'):
        # New project opens a setup sheet (paper, orientation, photos per page); accept defaults.
        tap('New project');snap('new-project-sheet');tap('Create')
        wait(lambda s:s['pages']==1 and not s['busy'])
    if marker('state')['assets']==1:return
    if not any('documentsui' in n.get('package','') for n in dump()[0].iter('node')):
        tap('Insert');tap('Import images / PDF')
    downloads();choose_file(marker('input')['name'])
    wait(lambda s:s['assets']==1 and s['images']==1 and not s['busy'])
run(1,'native-import',create)
def edit():
    tap('Pages');tap('Duplicate page');wait(lambda s:s['pages']==2)
    # Undo/Redo are top-bar icon buttons now (no longer behind the overflow menu), and there is no
    # manual Save action any more: autosave persists every edit, reported by the top bar subtitle.
    editor();tap('Undo');wait(lambda s:s['pages']==1)
    tap('Redo');wait(lambda s:s['pages']==2)
    if not args.baseline:
        wait(lambda s:not s['busy'])
        assert visible('Saved · just now')
run(2,'duplicate-undo-redo',edit)
def pdf():
    # Phase B moved the picker before rendering: exporting a regular PDF never enters Ready any
    # more (the worker publishes as soon as it renders), so the only wait is for verification.
    jobs=[j for j in marker('state')['jobs'] if not j['portable']]
    if jobs and jobs[0]['verified']:return
    if not jobs:
        # Export is a filled top-bar action, opening the destination-first export sheet.
        if not visible_prefix('Compact'):tap('Export')
        snap('quality-dialog')
        if args.baseline:
            switches=[n for n in dump()[0].iter('node') if n.get('checkable')=='true']
            assert switches and all(not n.get('text') and not n.get('content-desc') for n in switches)
            print('BASELINE LABEL MISSING: native Compact switch has no accessible label',flush=True)
            tap_where(lambda n:n.get('checkable')=='true')
        else:
            # The Original/Compact quality picker is two selectable cards now, not a single
            # switch; each card is one checkable node owning its own contentDescription, composed
            # as "Original, <estimate>" / "Compact, <estimate>" so TalkBack reads the live size
            # estimate too, not just the label.
            def labeled_cards():
                return [n for n in dump()[0].iter('node') if n.get('checkable')=='true' and match_prefix(n,'Compact')]
            cards=labeled_cards();assert len(cards)==1,'Label must belong to the checkable card'
            if cards[0].get('checked')!='true':tap_where(lambda n:match_prefix(n,'Compact'))
            cards=labeled_cards()
            # A selected radio exposes no click action (Android drops ACTION_CLICK once checked),
            # so require the checked state and a real label instead of clickability.
            assert len(cards)==1 and cards[0].get('checked')=='true' and cards[0].get('NAF')!='true'
            x,y,r,d=map(int,re.findall(r'\d+',cards[0].get('bounds')));assert r>x and d>y
            snap('quality-selected')
        # The sticky Export action launches CreateDocument immediately: the destination is chosen
        # before anything is queued or rendered.
        tap('Export')
        save_file(cp['name']+'.pdf')
    queued=wait(lambda s:any(j['phase']!='Ready' and not j['portable'] for j in s['jobs']))
    if not args.baseline:assert all(j['compact'] for j in queued['jobs'] if not j['portable'])
    wait(lambda s:any(j['verified'] and not j['portable'] for j in s['jobs']))
    # Phase B item 6: reaching Published pops the "PDF saved" sheet (Open/Share/Done); dismiss it
    # with Done so the editor is the frontmost thing again for the steps that follow.
    if not args.baseline and visible('PDF saved'):
        snap('export-saved-sheet')
        tap('Done')
run(3,'native-pdf-saved',pdf)
def portable():
    editor();tap('Insert');tap('Download project')
    wait(lambda s:any(j['phase']=='Ready' and j['portable'] for j in s['jobs']))
    tap('Download project');save_file(cp['name']+'.ugpdfproject')
    wait(lambda s:any(j['verified'] and j['portable'] for j in s['jobs']))
    # The result sheet and the full-screen Exports history stay on top; close both so the
    # following steps start from the editor.
    if not args.baseline and visible('Project file saved'):
        snap('project-saved-sheet');tap('Done')
    # "Download project" opens the full-screen Exports history; the editor behind it still shows
    # up in the accessibility dump, so close the history explicitly.
    for _ in range(3):
        if not visible('Exports'):break
        shell('input','keyevent','4');time.sleep(.5)
run(4,'native-project-saved',portable)
def restore():
    if not any('documentsui' in n.get('package','') for n in dump()[0].iter('node')):
        editor();tap('My projects');tap('Import project')
    downloads()
    # Android may append .zip to CreateDocument names whose suffix is not the MIME extension.
    root,_=dump();names=[n.get('text') or n.get('content-desc','').split(',')[0] for n in root.iter('node')]
    names=[n for n in names if n.startswith(cp['name']+'.ugpdfproject') or (args.baseline and n=='Untitled project.ugpdfproject.zip')];assert names,names
    choose_file(names[0]);wait(lambda s:len(s['seen'])==2 and s['pages']==2 and s['images']==2 and not s['busy'])
run(5,'native-project-restored',restore)
if cp['step']==5:
    shell('run-as',pkg,'touch','files/pdf-flow-finish')
    end=time.monotonic()+40
    while time.monotonic()<end:
        try:done=marker('done');break
        except subprocess.CalledProcessError:time.sleep(.2)
    else:raise AssertionError('Fixture finish not observed')
    assert done.get('status')=='PASS',done
    (out/'result.json').write_text(json.dumps(done,indent=2)+'\n');cp['step']=6;checkpoint.write_text(json.dumps(cp))
    shell('rm','-f','/sdcard/pdf-flow-window.xml')
print('SCREEN FLOW PASS: native creation/import, duplicate/undo/redo, actual PDF and portable CreateDocument, verified destination bytes, portable OpenDocument round-trip; exact fixture files/projects/jobs removed')
