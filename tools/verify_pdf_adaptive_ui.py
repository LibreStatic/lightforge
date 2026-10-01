#!/usr/bin/env python3
"""Exercise the real PDF screen in constrained native parents; never modify device settings."""
import argparse, json, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',required=True);p.add_argument('--dynamic',action='store_true');p.add_argument('--folds',action='store_true');p.add_argument('--media',action='store_true');p.add_argument('--text',action='store_true');p.add_argument('--multi',action='store_true');p.add_argument('--drag',action='store_true');p.add_argument('--panes',action='store_true');args=p.parse_args()
out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
b=['adb','-s',args.serial];pkg='com.librestatic.lightforge.feature.pdfstudio.test'
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
def any_focused_edittext():
    root,_=dump()
    return any(n.get('class')=='android.widget.EditText' and n.get('focused')=='true' for n in root.iter('node'))
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
def long_press(label):
    # Phase G2: `input swipe` with identical start/end coordinates and a duration long enough to
    # clear the long-press timeout is the standard ADB-only way to synthesize a long-press (no
    # separate "long tap" verb exists in the `input` tool).
    root,_=dump();n=node_for(root,label)
    parents={c:p for p in root.iter() for c in p}
    while n.get('clickable')!='true' and n in parents:n=parents[n]
    assert n.get('enabled')=='true', (label,n.attrib)
    x,y,r,d=bounds(n)
    cx,cy=(x+r)//2,(y+d)//2
    shell('input','swipe',str(cx),str(cy),str(cx),str(cy),'600');time.sleep(.6)
def dismiss_to_editor(label):
    for _ in range(5):
        root,_=dump()
        if any(n.get('content-desc')==label for n in root.iter('node')):return
        shell('input','keyevent','4');time.sleep(.4)
    raise AssertionError('Sheet did not dismiss')
def open_details(snap_name):
    # The overflow menu animates in; retry until the details sheet (with the name field) shows.
    for _ in range(3):
        tap(ls['pdf_project_details']);root=snap(snap_name)
        if any(n.get('text')==ls['pdf_name'] or n.get('content-desc')==ls['pdf_name'] for n in root.iter('node')):return root
        if not any(n.get('text')==ls['pdf_project_details'] for n in root.iter('node')):
            tap(ls['pdf_project_actions'])
    raise AssertionError('Project details sheet did not open')
def snap(name):
    root,raw=dump();(out/(name+'.xml')).write_bytes(raw)
    (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'));return root
def cleanup():
    shell('am','force-stop',pkg)
    result=shell('am','instrument','-w','-e','phase','ui-cleanup',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfRecoveryProbeRunner')
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
    shell('am','start','-W','-n',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfUiProbeActivity','--ei','width',str(width),'--ei','height',str(height),'--ef','font',str(font),'--es','locale',locale,'--ez','dark',str(dark).lower(),'--ez','rtl',str(rtl).lower(),'--ez','dynamic',str(args.dynamic).lower())
    initial=wait_ready();time.sleep(.5);ls=labels(locale)
    # The previous case's activity can still be on screen for a moment; wait for this case's
    # canvas (in this case's locale) before taking the reference snapshot.
    for _ in range(20):
        root=snap(name)
        if any(n.get('content-desc')==ls['pdf_canvas_label'] for n in root.iter('node')):break
        time.sleep(.5)
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
    for key in ('surface','primary','secondaryContainer','surfaceContainer','surfaceVariantText','surfacePrimaryText','canvasBadge','snapMeasurementChip','sheetContainerText','sheetContainerError','historyDot','ruler','statusBar','hoverTooltip','mediaThumbnail'):assert theme[key]>=4.5,(key,theme)
    for key in ('outline','contextualToolbar','contextualToolbarDelete','shortcutKeycap'):assert theme[key]>=3,(key,theme)
    assert theme['dynamic']==args.dynamic
    (out/(name+'-theme.json')).write_text(json.dumps(theme,indent=2)+'\n')
    # Export is a filled top-bar action now; the overflow only holds the less frequent actions.
    node_for(root,ls['pdf_export'])
    tap(ls['pdf_project_actions']);menu=snap(name+'-menu');node_for(menu,ls['pdf_queue']);node_for(menu,ls['pdf_portable'])
    details=open_details(name+'-details');node_for(details,ls['pdf_name'])
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
    tap(ls['pdf_project_actions']);restored=open_details(name+'-restored')
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
fold_results=[]
if args.folds:
    # Phase F2 item D: two synthetic-fold cases exercised on a normal (non-foldable) emulator via
    # PdfUiProbeActivity's optional `fold`/`hingePx`/`foldPos` extras. Sizes chosen to fit a normal
    # emulator screen: a vertical hinge splitting an 840x640 window (HingeSplit), and a horizontal
    # hinge splitting a 400x800 window (Tabletop).
    fold_cases=[(840,640,'vertical',16,0.5,'fold-hingesplit'),(400,800,'horizontal',16,0.5,'fold-tabletop')]
    for width,height,orientation,hinge_dp,fold_pos,name in fold_cases:
        shell('run-as',pkg,'rm','-f','files/pdf-ui-state.json')
        subprocess.run(b+['shell','run-as',pkg,'tee','files/pdf-ui-config.json'],input=json.dumps(dict(width=width,font=1,locale='en')).encode(),stdout=subprocess.DEVNULL,check=True)
        shell('am','start','-W','-n',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfUiProbeActivity',
              '--ei','width',str(width),'--ei','height',str(height),'--ef','font','1','--es','locale','en',
              '--ez','dark','false','--ez','rtl','false','--ez','dynamic',str(args.dynamic).lower(),
              '--es','fold',orientation,'--ei','hingePx',str(hinge_dp),'--ef','foldPos',str(fold_pos),
              '--ez','multi','true')
        wait_ready();time.sleep(.5);ls=labels('en')
        root=None
        for _ in range(20):
            root=snap(name)
            if any(n.get('content-desc')==ls['pdf_canvas_label'] for n in root.iter('node')):break
            time.sleep(.5)
        pixels=int.from_bytes((out/(name+'.png')).read_bytes()[16:20],'big')
        px_per_dp=pixels/width
        if orientation=='vertical':
            center=width*fold_pos
            hinge=(center-hinge_dp/2,0.0,center+hinge_dp/2,float(height))
        else:
            center=height*fold_pos
            hinge=(0.0,center-hinge_dp/2,float(width),center+hinge_dp/2)
        hinge_px=tuple(v*px_per_dp for v in hinge)
        def intersects(node_bounds):
            x0,y0,x1,y1=node_bounds
            return not (x1<=hinge_px[0] or x0>=hinge_px[2] or y1<=hinge_px[1] or y0>=hinge_px[3])
        canvas=bounds(node_for(root,ls['pdf_canvas_label']))
        assert not intersects(canvas),(name,'canvas overlaps the hinge',canvas,hinge_px)
        # Entirely on one side: either fully left/above, or fully right/below, the hinge band.
        if orientation=='vertical':
            on_one_side=canvas[2]<=hinge_px[0] or canvas[0]>=hinge_px[2]
        else:
            on_one_side=canvas[3]<=hinge_px[1] or canvas[1]>=hinge_px[3]
        assert on_one_side,(name,'canvas not entirely on one side of the hinge',canvas,hinge_px)
        clickable=[n for n in root.iter('node') if n.get('clickable')=='true' and n.get('bounds')]
        offenders=[n.get('content-desc') or n.get('text') for n in clickable if intersects(bounds(n))]
        assert not offenders,(name,'clickable node(s) under the hinge',offenders)
        theme=json.loads(shell('run-as',pkg,'cat','files/pdf-ui-theme.json'))
        for key in ('surface','primary','secondaryContainer','surfaceContainer','ruler','statusBar','hoverTooltip','mediaThumbnail'):assert theme[key]>=4.5,(name,key,theme)
        for key in ('outline','contextualToolbar','shortcutKeycap'):assert theme[key]>=3,(name,key,theme)
        # Fix-round item 4: the multi-select bar (and per-member/group outlines) must never sit on
        # the fold either - long-press the fixture image, tap the fixture text to form a 2-element
        # group, and check the "N selected" bar's own bounds the same way as every clickable node
        # above.
        image_label=ls['pdf_image_label'].replace('%1$d','1').replace('%d','1')
        text_label=ls['pdf_text_label'].replace('%1$s','B').replace('%s','B')
        long_press(image_label)
        for _ in range(20):
            if state().get('selectedCount',0)==1:break
            time.sleep(.2)
        tap(text_label)
        for _ in range(20):
            if state().get('groupSelected'):break
            time.sleep(.2)
        assert state().get('selectedCount')==2 and state().get('groupSelected'),(name,state())
        root2=snap(name+'-2-selected')
        bar_nodes=[n for n in root2.iter('node') if n.get('text')=='2 selected']
        assert bar_nodes,(name,'2 selected bar not shown in this fold layout')
        assert not intersects(bounds(bar_nodes[0])),(name,'multi-select bar overlaps the hinge',bounds(bar_nodes[0]),hinge_px)
        row=dict(case=name,orientation=orientation,canvasBounds=canvas,hingeBoundsPx=list(hinge_px),noClickableUnderHinge=True,canvasOnOneSide=True,multiSelectBarOffHinge=True)
        fold_results.append(row);print('FOLD CASE PASS: '+json.dumps(row,sort_keys=True),flush=True)
        cleanup()
media_result=None
if args.media:
    # Phase F item 3 review fix: exercises the Media panel (chips/tiles labeled and 48dp, tap ->
    # image appended, undo -> removed) via PdfUiProbeActivity's `fakeMedia` fake PdfMediaSource -
    # no real gallery data or permissions needed. 840x640 (no fold) puts the panel in the
    # expanded inspector's Page/Media tab row.
    name='media-840x640'
    shell('run-as',pkg,'rm','-f','files/pdf-ui-state.json')
    subprocess.run(b+['shell','run-as',pkg,'tee','files/pdf-ui-config.json'],input=json.dumps(dict(width=840,font=1,locale='en')).encode(),stdout=subprocess.DEVNULL,check=True)
    shell('am','start','-W','-n',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfUiProbeActivity',
          '--ei','width','840','--ei','height','640','--ef','font','1','--es','locale','en',
          '--ez','dark','false','--ez','rtl','false','--ez','dynamic',str(args.dynamic).lower(),
          '--ez','fakeMedia','true')
    baseline=wait_ready();time.sleep(.5);ls=labels('en')
    # The probe fixture page already holds one image; measure the insert as a delta.
    before=baseline.get('currentPageImages',0)
    root=None
    for _ in range(20):
        root=snap(name)
        if any(n.get('content-desc')==ls['pdf_canvas_label'] for n in root.iter('node')):break
        time.sleep(.5)
    tap(ls['pdf_media']);tab=snap(name+'-tab')
    pixels=int.from_bytes((out/(name+'-tab.png')).read_bytes()[16:20],'big');px_per_dp=pixels/840
    # Chips: every one is present, labeled, and at least a 48dp touch target.
    for chip_key in ('pdf_media_all','pdf_media_photos','pdf_media_documents','pdf_media_in_project'):
        chip=node_for(tab,ls[chip_key])
        # The label is a child TextView; measure the clickable chip that owns it.
        parents={c:p for p in tab.iter() for c in p}
        while chip.get('clickable')!='true' and chip in parents:chip=parents[chip]
        x,y,r,d=bounds(chip)
        assert (r-x)>=48*px_per_dp*0.9 and (d-y)>=48*px_per_dp*0.9, (chip_key,'chip below 48dp target',chip.attrib)
    # Tiles: the fake source's items are labeled "<name>. Add to current page" and >=48dp.
    item_label=ls['pdf_media_item_label'].replace('%1$s','Fake photo 0')
    tile=node_for(tab,item_label);tx,ty,tr,td=bounds(tile)
    pixels=int.from_bytes((out/(name+'-tab.png')).read_bytes()[16:20],'big');px_per_dp=pixels/840
    assert (tr-tx)>=48*px_per_dp*0.9 and (td-ty)>=48*px_per_dp*0.9, ('tile below 48dp target',tile.attrib)
    # Tap the tile: the current page must gain exactly one image.
    tap(item_label)
    for _ in range(30):
        if state().get('currentPageImages',0)==before+1:break
        time.sleep(.2)
    assert state()['currentPageImages']==before+1,state()
    # Undo removes it again.
    tap(ls['pdf_undo'])
    for _ in range(30):
        if state().get('currentPageImages',0)==before:break
        time.sleep(.2)
    assert state()['currentPageImages']==before,state()
    media_result=dict(case=name,chipsLabeled=True,tilesLabeledAndSized=True,tapAppendsImage=True,undoRemovesImage=True)
    print('MEDIA CASE PASS: '+json.dumps(media_result,sort_keys=True),flush=True)
    cleanup()
text_results=[]
if args.text:
    # Phase G1b: Insert -> Text on both a compact (360dp, bottom-sheet Insert panel) and an
    # expanded (840dp, always-visible inspector Insert section) width - add a text, type a
    # Latin/diacritic/Greek probe string (all within the bundled fonts' cmap - PdfTextSupport),
    # commit it via an outside tap (clears focus - PdfInlineTextEditor's onFocusChanged then
    # commits), assert the page gains a text (currentPageTexts, mirroring --media's
    # currentPageImages), then undo removes it. Also checks the two new contrast keys the inline
    # editor's glyph-error banner and the ink swatch's selection ring need.
    for width,height in ((840,640),(360,640)):
        name=f'text-{width}x{height}'
        shell('run-as',pkg,'rm','-f','files/pdf-ui-state.json')
        subprocess.run(b+['shell','run-as',pkg,'tee','files/pdf-ui-config.json'],input=json.dumps(dict(width=width,font=1,locale='en')).encode(),stdout=subprocess.DEVNULL,check=True)
        shell('am','start','-W','-n',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfUiProbeActivity',
              '--ei','width',str(width),'--ei','height',str(height),'--ef','font','1','--es','locale','en',
              '--ez','dark','false','--ez','rtl','false','--ez','dynamic',str(args.dynamic).lower())
        baseline=wait_ready();time.sleep(.5);ls=labels('en')
        before=baseline.get('currentPageTexts',0)
        root=None
        for _ in range(20):
            root=snap(name)
            if any(n.get('content-desc')==ls['pdf_canvas_label'] for n in root.iter('node')):break
            time.sleep(.5)
        canvas=bounds(node_for(root,ls['pdf_canvas_label']))
        # Compact (<840dp): Insert lives in the bottom tool bar's own sheet; expanded (>=840dp):
        # it's always visible in the inspector column - no tab/sheet to open first.
        if width<840:tap(ls['pdf_insert'])
        tap(ls['pdf_add_text'])
        # Insert -> Text selects the new text and opens it straight into inline editing with the
        # placeholder fully typed and the cursor at its end; clear it one DEL per character, then
        # type the probe string (Latin + diacritic + Greek, all within the bundled fonts' cmap).
        # A few extra presses beyond the placeholder's own length are harmless (DEL on an already-
        # empty field no-ops) and cheaper than risking a dropped keyevent leaving a stray character.
        for _ in range(len(ls['pdf_text_placeholder'])+3):shell('input','keyevent','67')
        # ASCII only (round-2 fix, item H): `adb shell input text` exits 255 on non-ASCII (Greek
        # Ελλάδα failed on-device) - no AOSP IME-agnostic way to inject it reliably from this
        # script, so the probe stays within what `input text` actually supports.
        probe_text='Hola Lightforge'
        shell('input','text',probe_text.replace(' ','%s'))
        time.sleep(.3)
        # Commit via an outside tap (not Enter, which would insert a newline in this multi-line
        # field, and not Escape/back, which cancels). NOT canvas[0]+15,canvas[1]+15 - the fixture
        # page already has an image sitting right at the page's own top-left corner (10mm margin),
        # so that point used to land ON the image (selecting it) rather than empty background.
        # canvas[1]+150 clears the image's bottom edge (it only reaches roughly the first ~70px)
        # while staying above the new text's own default vertical center.
        tx,ty=canvas[0]+15,canvas[1]+150
        for _ in range(10):
            shell('input','tap',str(tx),str(ty));time.sleep(.5)
            if not any_focused_edittext():break
        assert not any_focused_edittext(),'inline editor never lost focus (commit did not fire)'
        for _ in range(30):
            if state().get('currentPageTexts',0)==before+1:break
            time.sleep(.2)
        assert state()['currentPageTexts']==before+1,state()
        # Evidence: the committed, selected text (handles + contextual toolbar) - reviewed
        # visually (rendering, no handle/toolbar overlap, light-theme contrast).
        snap(name+'-committed')
        theme=json.loads(shell('run-as',pkg,'cat','files/pdf-ui-theme.json'))
        assert theme['textErrorBanner']>=4.5,(name,theme)
        assert theme['inkSwatchSelection']>=3,(name,theme)
        # Two undo steps were pushed (round-2 fix, item H): addText (placeholder text appended)
        # and the inline editor's own commit (placeholder -> probe_text), each a separate undo
        # transaction. The first Undo only reverts the CONTENT commit - the text element is still
        # there, just back to its placeholder - so the count must stay before+1 after it; a
        # second Undo then removes the element itself, back to `before`.
        tap(ls['pdf_undo'])
        time.sleep(.3)
        assert state()['currentPageTexts']==before+1,state()
        tap(ls['pdf_undo'])
        for _ in range(30):
            if state().get('currentPageTexts',0)==before:break
            time.sleep(.2)
        assert state()['currentPageTexts']==before,state()
        row=dict(case=name,textAdded=True,contentCommitted=True,contentUndoKeepsElement=True,secondUndoRemovesText=True)
        text_results.append(row);print('TEXT CASE PASS: '+json.dumps(row,sort_keys=True),flush=True)
        cleanup()
multi_results=[]
if args.multi:
    # Phase G2: long-press the fixture image to enter multi-select, tap the fixture text to add
    # it (2 selected -> the group bar appears), Align left, then undo. The base compact/expanded
    # widths mirror --text's two-width shape; the 200%-font and RTL cases are Fix-round item 9 -
    # the Done/close button must stay reachable (scroll or wrap) and Align-left must remain
    # PHYSICAL left even in RTL (never mirrored), matching the existing base UI cases' own
    # physicalCoordinates check.
    multi_cases=[
        (840,640,1,'en',False),  # expanded, always-visible inspector
        (360,640,1,'en',False),  # compact
        (360,640,2,'en',False),  # 200% font scale
        (360,640,1,'en',True),   # RTL (pseudo-locale layout direction; page coords never mirror)
    ]
    for width,height,font,locale,rtl in multi_cases:
        name=f'multi-{width}x{height}-font{font}{"-rtl" if rtl else ""}'
        shell('run-as',pkg,'rm','-f','files/pdf-ui-state.json')
        subprocess.run(b+['shell','run-as',pkg,'tee','files/pdf-ui-config.json'],input=json.dumps(dict(width=width,font=font,locale=locale)).encode(),stdout=subprocess.DEVNULL,check=True)
        shell('am','start','-W','-n',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfUiProbeActivity',
              '--ei','width',str(width),'--ei','height',str(height),'--ef','font',str(font),'--es','locale',locale,
              '--ez','dark','false','--ez','rtl',str(rtl).lower(),'--ez','dynamic',str(args.dynamic).lower(),'--ez','multi','true')
        wait_ready();time.sleep(.5);ls=labels(locale)
        snap(name+'-baseline')
        image_label=ls['pdf_image_label'].replace('%1$d','1').replace('%d','1')
        text_label=ls['pdf_text_label'].replace('%1$s','B').replace('%s','B')
        long_press(image_label)
        for _ in range(20):
            if state().get('selectedCount',0)==1 and state().get('busy') is False:break
            time.sleep(.2)
        assert state()['selectedCount']==1,state()
        tap(text_label)
        for _ in range(20):
            if state().get('groupSelected'):break
            time.sleep(.2)
        assert state()['selectedCount']==2 and state()['groupSelected'],state()
        root=snap(name+'-2-selected')
        # The bar's own "N selected" plural literal text (not a contentDescription - see
        # PdfMultiSelectBar) is the visible, screenshot-reviewable proof the bar is showing.
        bar_count_nodes=[n for n in root.iter('node') if n.get('text') and 'selected' in n.get('text')]
        assert bar_count_nodes,(name,'no "N selected" bar text shown')
        # Fix-round item 9: the Done/close button (pdf_multiselect_exit content-desc) must stay
        # reachable - present in the tree at all (the bar's Row is horizontally scrollable if it
        # doesn't fit) even at 200% font / RTL, not just at 100% en.
        done_label=ls['pdf_multiselect_exit']
        assert any(n.get('content-desc')==done_label for n in root.iter('node')),(name,'Done button not reachable')
        theme=json.loads(shell('run-as',pkg,'cat','files/pdf-ui-theme.json'))
        assert theme['multiSelectBar']>=4.5,(name,theme)
        assert theme['multiSelectBarDelete']>=4.5,(name,theme)
        align_label=ls['pdf_toolbar_align']
        tap(align_label)
        tap(ls['pdf_align_left'])
        time.sleep(.3)
        assert state()['groupSelected'],state()
        aligned_root=snap(name+'-aligned')
        # Physical-left check (Fix-round item 9): Align-left must land both members at the SAME
        # physical x - regardless of RTL layout direction - by reading their bounds back from the
        # accessibility tree (both elements' left edges coincide on screen).
        img_bounds=bounds(node_for(aligned_root,image_label))
        txt_bounds=bounds(node_for(aligned_root,text_label))
        assert abs(img_bounds[0]-txt_bounds[0])<=3,(name,'Align left did not land on the same physical x',img_bounds,txt_bounds)
        tap(ls['pdf_undo'])
        for _ in range(20):
            if not state().get('busy'):break
            time.sleep(.2)
        row=dict(case=name,enteredMultiSelect=True,groupBarShown=True,alignApplied=True,undoOk=True,doneReachable=True,physicalLeftAlign=True)
        multi_results.append(row);print('MULTI CASE PASS: '+json.dumps(row,sort_keys=True),flush=True)
        cleanup()
drag_results=[]
if args.drag:
    # Device-verification pass for the drag/pan/fit bug fixes (PdfCanvas.kt): exercises a REAL
    # touch drag via `adb shell input swipe` (not a screenshot/accessibility-tree heuristic) on
    # the `multi` 2-element fixture (one image at (10,10,85x85)mm, one text "B"), at a compact
    # (360dp, single-pane) and an expanded (860dp, three-pane) width - the two layouts the user's
    # bug report contrasted. Reads element rects (mm) and the viewport straight out of
    # PdfUiProbeActivity's probe-state JSON (added alongside canvasRectPx/pageBoxRectPx, the real
    # on-screen pixel rects of the canvas pane and the page box post zoom/pan) rather than
    # screenshot-diffing, so a wrong-by-a-few-px regression is caught exactly, not just "looks
    # off". NOTE: this harness virtualizes the layout width purely through PdfUiProbeActivity's
    # own `width`/`height` intent extras and its internal LocalDensity override (see its
    # attachBaseContext/onCreate) - the SAME mechanism every other case in this script already
    # uses - so this does not touch `wm density`/`wm size` (no device-wide state to reset, and no
    # risk of interfering with a concurrent adb session on this serial).
    drag_cases=[(360,640,'compact'),(860,640,'expanded')]
    for width,height,tag in drag_cases:
        name=f'drag-{tag}-{width}x{height}'
        shell('run-as',pkg,'rm','-f','files/pdf-ui-state.json')
        subprocess.run(b+['shell','run-as',pkg,'tee','files/pdf-ui-config.json'],input=json.dumps(dict(width=width,font=1,locale='en')).encode(),stdout=subprocess.DEVNULL,check=True)
        shell('am','start','-W','-n',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfUiProbeActivity',
              '--ei','width',str(width),'--ei','height',str(height),'--ef','font','1','--es','locale','en',
              '--ez','dark','false','--ez','rtl','false','--ez','dynamic',str(args.dynamic).lower(),'--ez','multi','true')
        wait_ready();ls=labels('en')
        # wait_ready() returns on the first snapshot where the project is ready and not busy -
        # which can race PdfUiProbeActivity's own Compose layout pass (canvasRectPx/pageBoxRectPx
        # only populate once PdfCanvas actually measures), so re-poll state() itself (the probe
        # activity's own write loop keeps refreshing the file every ~150ms) until the rects show
        # up, rather than trusting wait_ready()'s possibly-stale snapshot.
        baseline=None
        for _ in range(50):
            baseline=state()
            if baseline.get('canvasRectPx') and baseline.get('pageBoxRectPx'):break
            time.sleep(.1)
        # (a) Fit-at-rest: the whole page box lies inside the canvas viewport (the reported "page
        # clipped on the right, ruler starts around -120" symptom - checked as real pixel rects,
        # not inferred from a screenshot).
        canvas_rect=baseline['canvasRectPx'];page_rect=baseline['pageBoxRectPx']
        assert canvas_rect is not None and page_rect is not None,(name,'missing probe rects',baseline)
        assert (page_rect['left']>=canvas_rect['left']-1 and page_rect['top']>=canvas_rect['top']-1
                and page_rect['right']<=canvas_rect['right']+1 and page_rect['bottom']<=canvas_rect['bottom']+1), \
            (name,'page box not fit inside the canvas at rest',page_rect,canvas_rect)
        assert baseline['zoom']==1 and baseline['panX']==0 and baseline['panY']==0, \
            (name,'unexpected non-identity initial viewport',baseline)
        px_per_mm=(page_rect['right']-page_rect['left'])/baseline['elementsMm']['pageWidthMm']
        img0=baseline['elementsMm']['images'][0];txt0=baseline['elementsMm']['texts'][0]
        cx=page_rect['left']+(img0['x']+img0['width']/2)*px_per_mm
        cy=page_rect['top']+(img0['y']+img0['height']/2)*px_per_mm
        # 60mm (not 20mm): Compose's detectDragGestures reports the accumulated onDrag delta as
        # the RAW pointer travel MINUS the touch-slop distance the gesture had to clear before it
        # was even recognized as a drag (viewConfiguration.touchSlop, ~8dp - a real, expected,
        # well-documented Compose behavior, not a bug: confirmed by direct measurement here, an
        # `input swipe` for a 20mm intended delta measurably lands ~10-14mm, consistent with an
        # ~7-8mm slop deduction). A larger requested delta makes that fixed deduction a small
        # fraction of the total, so the tolerance below stays meaningful evidence of "moved
        # correctly" rather than papering over a real discrepancy.
        delta_mm=60.0;delta_px=delta_mm*px_per_mm
        x1,y1,x2,y2=int(cx),int(cy),int(cx+delta_px),int(cy+delta_px)
        # The correctness-critical swipe runs to completion synchronously (a plain, blocking
        # `input swipe`, nothing concurrent) so a timing hiccup in the best-effort during-drag
        # screenshot (a SEPARATE, decoupled throwaway drag+undo cycle further below) can never
        # affect these assertions.
        shell('input','swipe',str(x1),str(y1),str(x2),str(y2),'800')
        time.sleep(.4)
        after=state()
        (out/(name+'-after-release.png')).write_bytes(adb('exec-out','screencap','-p'))
        # (b) A moved by ~delta (touch-slop tolerance - see above; no snap in this fixture project,
        # PdfProject.snap defaults to false, so this is purely slop, not grid rounding), B is
        # untouched, and the viewport itself never moved (the "whole page pans while dragging an
        # element" / gesture-arbitration symptom).
        img1=after['elementsMm']['images'][0];txt1=after['elementsMm']['texts'][0]
        moved_x=img1['x']-img0['x'];moved_y=img1['y']-img0['y']
        assert abs(moved_x-delta_mm)<=12 and abs(moved_y-delta_mm)<=12 and moved_x>30 and moved_y>30, \
            (name,'image did not move by the drag delta',img0,img1)
        assert txt1==txt0,(name,'the OTHER (non-dragged) element moved',txt0,txt1)
        assert after['zoom']==baseline['zoom'] and after['panX']==baseline['panX'] and after['panY']==baseline['panY'], \
            (name,'viewport changed during an element drag (pan/zoom leaked into the gesture)',baseline,after)
        # (c) One Undo returns A to EXACTLY its pre-drag position (one committed undo step, no
        # residual offset/jump).
        tap(ls['pdf_undo'])
        restored=None
        for _ in range(30):
            s=state();restored=s['elementsMm']['images'][0]
            if restored['x']==img0['x'] and restored['y']==img0['y']:break
            time.sleep(.2)
        assert restored['x']==img0['x'] and restored['y']==img0['y'], \
            (name,'undo did not exactly restore the pre-drag position',img0,restored)
        # (d) Corner-handle resize: only A's size changes (position and B untouched).
        root,_=dump()
        resize_label=ls['pdf_resize_label'].replace('%1$d','1').replace('%d','1')
        hx,hy,hr,hd=bounds(node_for(root,resize_label))
        hcx,hcy=(hx+hr)//2,(hy+hd)//2
        before_resize=state()['elementsMm']['images'][0];before_text=state()['elementsMm']['texts'][0]
        shell('input','swipe',str(hcx),str(hcy),str(hcx+120),str(hcy+120),'400');time.sleep(.6)
        after_resize=state()['elementsMm']['images'][0];after_text=state()['elementsMm']['texts'][0]
        assert after_resize['width']!=before_resize['width'] or after_resize['height']!=before_resize['height'], \
            (name,'corner resize did not change size',before_resize,after_resize)
        assert after_resize['x']==before_resize['x'] and after_resize['y']==before_resize['y'], \
            (name,'corner resize moved the element instead of only resizing it',before_resize,after_resize)
        assert after_text==before_text,(name,'corner resize on A affected B',before_text,after_text)
        tap(ls['pdf_undo'])
        # (e) During-drag screenshot: a SEPARATE, decoupled throwaway drag (undone afterward, no
        # bearing on the assertions above) with a concurrent mid-gesture `screencap` - best-effort
        # only, so the timing/contention risk noted above can never fail the case, only leave a
        # less-than-perfectly-centered mid-drag frame.
        img_now=state()['elementsMm']['images'][0]
        tcx=page_rect['left']+(img_now['x']+img_now['width']/2)*px_per_mm
        tcy=page_rect['top']+(img_now['y']+img_now['height']/2)*px_per_mm
        tx2,ty2=tcx+delta_px,tcy+delta_px
        proc=subprocess.Popen(b+['shell','input','swipe',str(int(tcx)),str(int(tcy)),str(int(tx2)),str(int(ty2)),'1500'])
        time.sleep(.7)
        try:(out/(name+'-mid-drag.png')).write_bytes(adb('exec-out','screencap','-p'))
        except subprocess.SubprocessError:pass
        proc.wait(timeout=5)
        time.sleep(.3)
        tap(ls['pdf_undo'])
        for _ in range(30):
            if state()['elementsMm']['images'][0]['x']==img0['x']:break
            time.sleep(.2)
        row=dict(case=name,pageFitAtRest=True,movedByDeltaMm=[round(moved_x,2),round(moved_y,2)],
                 otherElementUntouched=True,viewportUnchangedDuringDrag=True,undoExact=True,
                 cornerResizeIsolated=True)
        drag_results.append(row);print('DRAG CASE PASS: '+json.dumps(row,sort_keys=True),flush=True)
        cleanup()
panes_results=[]
if args.panes:
    # User feedback item 1: the expanded three-pane layout's collapsible pages-rail/inspector
    # toggles. Hiding a side pane must widen the canvas and the page must still be fully fit
    # (zoom stays ~1.0 — PdfCanvas's own resize-triggered re-fit, not a separate call this tool
    # has to trigger); showing it again must restore both. 840x640 puts the layout in
    # ExpandedThreePane (matches the --media/--text 840-wide cases above).
    name='panes-840x640'
    shell('run-as',pkg,'rm','-f','files/pdf-ui-state.json')
    subprocess.run(b+['shell','run-as',pkg,'tee','files/pdf-ui-config.json'],input=json.dumps(dict(width=840,font=1,locale='en')).encode(),stdout=subprocess.DEVNULL,check=True)
    shell('am','start','-W','-n',pkg+'/com.librestatic.lightforge.feature.pdfstudio.PdfUiProbeActivity',
          '--ei','width','840','--ei','height','640','--ef','font','1','--es','locale','en',
          '--ez','dark','false','--ez','rtl','false','--ez','dynamic',str(args.dynamic).lower())
    wait_ready();time.sleep(.5);ls=labels('en')
    root=None
    for _ in range(20):
        root=snap(name+'-both-shown')
        if any(n.get('content-desc')==ls['pdf_canvas_label'] for n in root.iter('node')):break
        time.sleep(.5)
    def canvas_width():
        r=state()['canvasRectPx'];return r['right']-r['left']
    both_visible_width=canvas_width()
    assert abs(state()['zoom']-1.0)<0.05,(name,'page not fit with both panes shown',state()['zoom'])
    # Hide the pages rail: canvas widens, page stays fit, and the button now offers to show it.
    tap(ls['pdf_hide_pages_panel'])
    for _ in range(20):
        if canvas_width()>both_visible_width:break
        time.sleep(.2)
    pages_hidden_width=canvas_width()
    assert pages_hidden_width>both_visible_width,(name,'canvas did not widen when the pages panel hid',both_visible_width,pages_hidden_width)
    assert abs(state()['zoom']-1.0)<0.05,(name,'page not still fit with the pages panel hidden',state()['zoom'])
    root2=snap(name+'-pages-hidden')
    assert any(n.get('content-desc')==ls['pdf_show_pages_panel'] for n in root2.iter('node')),(name,'no "Show pages panel" control after hiding it')
    # Show it again: canvas returns to its original width.
    tap(ls['pdf_show_pages_panel'])
    for _ in range(20):
        if abs(canvas_width()-both_visible_width)<=2:break
        time.sleep(.2)
    assert abs(canvas_width()-both_visible_width)<=2,(name,'canvas did not return to its original width',both_visible_width,canvas_width())
    # Hide the inspector: canvas widens again, page stays fit.
    tap(ls['pdf_hide_inspector'])
    for _ in range(20):
        if canvas_width()>both_visible_width:break
        time.sleep(.2)
    inspector_hidden_width=canvas_width()
    assert inspector_hidden_width>both_visible_width,(name,'canvas did not widen when the inspector hid',both_visible_width,inspector_hidden_width)
    assert abs(state()['zoom']-1.0)<0.05,(name,'page not still fit with the inspector hidden',state()['zoom'])
    root3=snap(name+'-inspector-hidden')
    assert any(n.get('content-desc')==ls['pdf_show_inspector'] for n in root3.iter('node')),(name,'no "Show inspector" control after hiding it')
    tap(ls['pdf_show_inspector'])
    for _ in range(20):
        if abs(canvas_width()-both_visible_width)<=2:break
        time.sleep(.2)
    assert abs(canvas_width()-both_visible_width)<=2,(name,'canvas did not return to its original width after restoring the inspector',both_visible_width,canvas_width())
    row=dict(case=name,bothVisibleWidthPx=both_visible_width,pagesHiddenWidthPx=pages_hidden_width,inspectorHiddenWidthPx=inspector_hidden_width,canvasWidensOnCollapse=True,pageStaysFit=True,restoresOriginalWidth=True)
    panes_results.append(row);print('PANES CASE PASS: '+json.dumps(row,sort_keys=True),flush=True)
    cleanup()
assert device_settings()==settings_before, 'Device settings changed'
(out/'device-settings.json').write_text(json.dumps(settings_before,indent=2)+'\n')
(out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
if args.folds:(out/'fold-results.json').write_text(json.dumps(fold_results,indent=2)+'\n')
if args.media and media_result:(out/'media-result.json').write_text(json.dumps(media_result,indent=2)+'\n')
if args.text and text_results:(out/'text-results.json').write_text(json.dumps(text_results,indent=2)+'\n')
if args.multi and multi_results:(out/'multi-results.json').write_text(json.dumps(multi_results,indent=2)+'\n')
if args.drag and drag_results:(out/'drag-results.json').write_text(json.dumps(drag_results,indent=2)+'\n')
if args.panes and panes_results:(out/'panes-results.json').write_text(json.dumps(panes_results,indent=2)+'\n')
print(f'ADAPTIVE UI PASS: {len(cases)} native parent configurations; accessible actions; title edit/undo; page add/undo; '+('dynamic' if args.dynamic else 'static')+' light/dark contrast; fixture projects removed; device settings unchanged'+(f'; {len(fold_results)} synthetic-fold cases' if args.folds else '')+('; media panel exercised' if args.media else '')+(f'; {len(text_results)} text-layer cases' if args.text else '')+(f'; {len(multi_results)} multi-select cases' if args.multi else '')+(f'; {len(drag_results)} drag cases' if args.drag else '')+(f'; {len(panes_results)} pane-toggle cases' if args.panes else ''))
