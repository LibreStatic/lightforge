#!/usr/bin/env python3
"""Exercise the real PDF screen in constrained native parents; never modify device settings."""
import argparse, json, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',required=True);p.add_argument('--dynamic',action='store_true');args=p.parse_args()
out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
b=['rtk','proxy','adb','-s',args.serial];pkg='com.ugallery.feature.pdfstudio.test'
def adb(*a): return subprocess.check_output(b+list(a),timeout=45)
def shell(*a): return adb('shell',*a).decode().strip()
def state(): return json.loads(subprocess.check_output(b+['shell','run-as',pkg,'cat','files/pdf-ui-state.json'],stderr=subprocess.DEVNULL,timeout=10))
def wait_ready():
    for _ in range(100):
        try:
            s=state()
            if s.get('project') and not s['busy']:return s
        except (subprocess.CalledProcessError,ValueError):pass
        time.sleep(.1)
    raise AssertionError('Editor did not become ready')
def dump():
    for _ in range(4):
        try:
            shell('uiautomator','dump','/sdcard/pdf-ui.xml')
            raw=adb('exec-out','cat','/sdcard/pdf-ui.xml')
            return ET.fromstring(raw),raw
        except (subprocess.CalledProcessError,ET.ParseError):time.sleep(.3)
    raise AssertionError('No accessibility tree')
def bounds(node): return list(map(int,re.findall(r'\d+',node.get('bounds'))))
def node_for(root,label):
    nodes=[n for n in root.iter('node') if n.get('text')==label or n.get('content-desc')==label]
    assert nodes, 'Missing accessible control: '+label
    return nodes[0]
def tap(label):
    root,_=dump();n=node_for(root,label)
    parents={c:p for p in root.iter() for c in p}
    while n.get('clickable')!='true' and n in parents:n=parents[n]
    assert n.get('enabled')=='true', (label,n.attrib)
    x,y,r,d=bounds(n)
    assert r>x and d>y, (label,n.attrib)
    # Menus and sheets animate in; give them time before the next dump.
    shell('input','tap',str((x+r)//2),str((y+d)//2));time.sleep(.7)
def dismiss_to_editor(label):
    for _ in range(5):
        root,_=dump()
        if any(n.get('content-desc')==label for n in root.iter('node')):return
        shell('input','keyevent','4');time.sleep(.4)
    raise AssertionError('Sheet did not dismiss')
def snap(name):
    root,raw=dump();(out/(name+'.xml')).write_bytes(raw)
    (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'));return root
def cleanup():
    shell('am','force-stop',pkg)
    result=shell('am','instrument','-w','-e','phase','ui-cleanup',pkg+'/com.ugallery.feature.pdfstudio.PdfRecoveryProbeRunner')
    assert 'PDF RECOVERY UI-CLEANUP PASS' in result,result
    time.sleep(.3)
def labels(locale):
    folder='values' if locale=='en' else 'values-'+locale
    root=ET.parse('feature/pdfstudio/src/main/res/'+folder+'/strings.xml').getroot()
    return {n.get('name'):n.text for n in root}
cases=[(360,640,2,'es',True,False),(320,640,1,'fr',False,False),(840,320,1,'en',False,False),(840,640,2,'de',True,True),(840,640,1,'en',True,False)]
if args.dynamic:
    assert int(shell('getprop','ro.build.version.sdk'))>=31
    cases=[(360,640,1,'en',False,False),(360,640,1,'en',True,False)]
def device_settings():
    return {key:shell(*command) for key,command in {
        'size':('wm','size'), 'density':('wm','density'),
        'font':('settings','get','system','font_scale'),
        'locale':('settings','get','system','system_locales'),
    }.items()}
settings_before=device_settings()
results=[]
cleanup()
for width,height,font,locale,dark,rtl in cases:
    name=f'{width}x{height}-font{font}-{locale}-'+('dark' if dark else 'light')+('-rtl' if rtl else '')
    shell('run-as',pkg,'rm','-f','files/pdf-ui-state.json')
    subprocess.run(b+['shell','run-as',pkg,'tee','files/pdf-ui-config.json'], input=json.dumps(dict(width=width,font=font,locale=locale)).encode(),stdout=subprocess.DEVNULL,check=True)
    shell('am','start','-W','-n',pkg+'/com.ugallery.feature.pdfstudio.PdfUiProbeActivity','--ei','width',str(width),'--ei','height',str(height),'--ef','font',str(font),'--es','locale',locale,'--ez','dark',str(dark).lower(),'--ez','rtl',str(rtl).lower(),'--ez','dynamic',str(args.dynamic).lower())
    initial=wait_ready();time.sleep(.5);ls=labels(locale);root=snap(name)
    pixels=int.from_bytes((out/(name+'.png')).read_bytes()[16:20],'big')
    canvas=bounds(node_for(root,ls['pdf_canvas_label']));canvas_dp=(canvas[3]-canvas[1])*width/pixels
    assert canvas_dp>=120,(name,canvas_dp)
    image_label=ls['pdf_image_label'].replace('%1$d','1').replace('%d','1')
    image_bounds=bounds(node_for(root,image_label))
    assert image_bounds[0]+image_bounds[2] < canvas[0]+canvas[2], 'Physical PDF image coordinates mirrored in RTL'
    theme=json.loads(shell('run-as',pkg,'cat','files/pdf-ui-theme.json'))
    # Phase C: the canvas's floating page/zoom badges and drag measurement chip carry text, so
    # they need the text threshold; the contextual toolbar (icon buttons only) and its Delete
    # action (an icon-only errorContainer/onErrorContainer button) need only the icon threshold.
    for key in ('surface','primary','secondaryContainer','surfaceContainer','surfaceVariantText','surfacePrimaryText','canvasBadge','snapMeasurementChip','sheetContainerText','sheetContainerError'):assert theme[key]>=4.5,(key,theme)
    for key in ('outline','contextualToolbar','contextualToolbarDelete'):assert theme[key]>=3,(key,theme)
    assert theme['dynamic']==args.dynamic
    (out/(name+'-theme.json')).write_text(json.dumps(theme,indent=2)+'\n')
    # Export is a filled top-bar action now; the overflow only holds the less frequent actions.
    node_for(root,ls['pdf_export'])
    tap(ls['pdf_project_actions']);menu=snap(name+'-menu');node_for(menu,ls['pdf_queue']);node_for(menu,ls['pdf_portable'])
    tap(ls['pdf_project_details']);details=snap(name+'-details');node_for(details,ls['pdf_name'])
    # Edit actual title, then undo through the top bar's own Undo button; reopening must reflect
    # the restored state.
    edit=[n for n in details.iter('node') if n.get('class')=='android.widget.EditText'][0]
    x,y,r,d=bounds(edit);shell('input','tap',str((x+r)//2),str((y+d)//2));time.sleep(.5);shell('input','keyevent','123');time.sleep(.2);shell('input','text','X');time.sleep(.4)
    edited=state()['name']
    assert edited!=initial['name'] and edited.replace('X','',1)==initial['name'],state()
    dismiss_to_editor(ls['pdf_project_actions'])
    tap(ls['pdf_undo']);assert state()['name']==initial['name'],state()
    # The title lives in the top app bar (tap to rename), not in an inline text field.
    editor=snap(name+'-undo-editor')
    assert any(n.get('text')==initial['name'] and n.get('class')!='android.widget.EditText' for n in editor.iter('node')), 'Top bar title did not follow undo'
    tap(ls['pdf_project_actions']);tap(ls['pdf_project_details']);restored=snap(name+'-restored')
    assert any(n.get('text')==initial['name'] for n in restored.iter('node') if n.get('class')=='android.widget.EditText')
    dismiss_to_editor(ls['pdf_project_actions'])
    if width < 840*font:tap(ls['pdf_pages'])
    page_tree,_=dump()
    # The "Add page" grid tile exposes its label as a contentDescription, not element text (R1
    # review fix), so the presence check must accept either; only tap the drag handle when it is
    # actually present — Pages panel sheets now open fully expanded (D4), and in the expanded
    # (>=840dp) layout there is no sheet/drag handle at all, so tapping it unconditionally could
    # fail or hit an unrelated control.
    addpage_visible=any(n.get('text')==ls['pdf_addpage'] or n.get('content-desc')==ls['pdf_addpage'] for n in page_tree.iter('node'))
    handle_present=any(n.get('text')=='Drag handle' or n.get('content-desc')=='Drag handle' for n in page_tree.iter('node'))
    if not addpage_visible and handle_present:
        tap('Drag handle')
    tap(ls['pdf_addpage']);assert state()['pages']==2,state()
    dismiss_to_editor(ls['pdf_project_actions'])
    tap(ls['pdf_undo']);assert state()['pages']==1,state()
    row=dict(physicalCoordinates=True,pageAddUndo=True,case=name,canvasHeightDp=round(canvas_dp,2),titleUndo=True,actionsReachable=True,semanticContrast=True)
    results.append(row);print('UI CASE PASS: '+json.dumps(row,sort_keys=True),flush=True)
    cleanup()
assert device_settings()==settings_before, 'Device settings changed'
(out/'device-settings.json').write_text(json.dumps(settings_before,indent=2)+'\n')
(out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
print(f'ADAPTIVE UI PASS: {len(cases)} native parent configurations; accessible actions; title edit/undo; page add/undo; '+('dynamic' if args.dynamic else 'static')+' light/dark contrast; fixture projects removed; device settings unchanged')
