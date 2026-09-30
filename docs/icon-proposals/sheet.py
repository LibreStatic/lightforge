import os, subprocess
import importlib, sys
_m = importlib.import_module(sys.argv[1] if len(sys.argv) > 1 else "gen")
DEFS, CONCEPTS, OUT = _m.DEFS, _m.CONCEPTS, _m.OUT
CUR = os.path.abspath(f"{OUT}/../../app/src/main/res/drawable-nodpi/ic_launcher_artwork.png")
CUR_BG = '<linearGradient id="cbg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E8EEF6"/></linearGradient>'
import base64
B64 = base64.b64encode(open(CUR,"rb").read()).decode()
CLIPS = {"sq": '<rect x="18" y="18" width="72" height="72" rx="21.6"/>', "ci": '<circle cx="54" cy="54" r="36"/>'}
THEMES = [("#D6E3FF", "#284777"), ("#1F2B3D", "#AEC6FF")]
os.makedirs(f"{OUT}/cells", exist_ok=True)
def cell(path, body, clip, px):
    s = (f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="18 18 72 72" width="{px}" height="{px}">'
         f'<defs>{DEFS}{CUR_BG}<clipPath id="c">{CLIPS[clip]}</clipPath></defs><g clip-path="url(#c)">{body}</g></svg>')
    open(path + ".svg", "w").write(s)
    subprocess.run(["rsvg-convert", path + ".svg", "-o", path + ".png"], check=True)
rows = [("actual", None)] + [(n, fn) for n, fn in CONCEPTS.items()]
for name, fn in rows:
    color = (f'<rect width="108" height="108" fill="url(#cbg)"/><image width="108" height="108" xlink:href="data:image/png;base64,{B64}"/>'
             if fn is None else '<rect width="108" height="108" fill="url(#bg)"/>' + fn(False))
    cell(f"{OUT}/cells/{name}-0", color, "sq", 150)
    cell(f"{OUT}/cells/{name}-1", color, "ci", 150)
    for t, (bgc, fgc) in enumerate(THEMES):
        if fn is None: continue
        mono = fn(True)
        import re
        # recolour only non-mask shapes: shapes outside <mask>...</mask>
        parts = re.split(r'(<mask.*?</mask>)', mono, flags=re.S)
        mono = "".join(p if p.startswith("<mask") else p.replace('"#000"', f'"{fgc}"') for p in parts)
        cell(f"{OUT}/cells/{name}-{2+t}", f'<rect width="108" height="108" fill="{bgc}"/>' + mono, "sq", 150)
    cell(f"{OUT}/cells/{name}-4", color, "sq", 48)
