import math, os
OUT = os.path.dirname(os.path.abspath(__file__))

def star(cx, cy, r, k=0.16):
    q = r * k
    return (f"M{cx},{cy-r} Q{cx+q},{cy-q} {cx+r},{cy} Q{cx+q},{cy+q} {cx},{cy+r} "
            f"Q{cx-q},{cy+q} {cx-r},{cy} Q{cx-q},{cy-q} {cx},{cy-r} Z")

DEFS = """
<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
  <stop offset="0" stop-color="#2E2438"/><stop offset="1" stop-color="#15111C"/></linearGradient>
<linearGradient id="amber" x1="0" y1="0" x2="1" y2="1">
  <stop offset="0" stop-color="#FFD36B"/><stop offset=".55" stop-color="#FF9A3C"/><stop offset="1" stop-color="#F0552E"/></linearGradient>
"""
CREAM = "#F3E9DC"

# ---------- A: aperture spark ----------
def aperture(mono):
    R, r_hex = 27, 12
    cx = cy = 54
    v = [(cx + r_hex*math.cos(math.radians(-90+60*k)), cy + r_hex*math.sin(math.radians(-90+60*k))) for k in range(6)]
    hexp = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in v) + " Z"
    lines = ""
    for k in range(6):
        (x1, y1), (x2, y2) = v[k], v[(k+1) % 6]
        dx, dy = x2-x1, y2-y1; n = math.hypot(dx, dy); dx, dy = dx/n, dy/n
        lines += f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2+dx*40:.2f}" y2="{y2+dy*40:.2f}"/>'
    fill = "#000" if mono else "url(#amber)"
    return f"""
<mask id="apm{mono}"><rect width="108" height="108" fill="#000"/>
  <circle cx="54" cy="54" r="{R}" fill="#fff"/>
  <g stroke="#000" stroke-width="2.6" stroke-linecap="round">{lines}</g>
  <path d="{hexp}" fill="#000" stroke="#000" stroke-width="2.6" stroke-linejoin="round"/>
  <path d="{star(54,54,8.5)}" fill="#fff"/></mask>
<rect width="108" height="108" fill="{fill}" mask="url(#apm{mono})"/>"""

# ---------- B: anvil + spark ----------
ANVIL = ("M40,52 L82,52 L82,60 L72,60 C68,61 66,64 66,68 L66,70 L73,70 L76,78 L36,78 L39,70 L46,70 "
         "L46,68 C46,64 44,62 40,62 C34,62 28,59 24,55 C29,53 35,52 40,52 Z")
def anvil(mono):
    a = "#000" if mono else CREAM
    s = "#000" if mono else "url(#amber)"
    return f"""<g transform="translate(54 56) scale(.88) translate(-53 -54)">
  <path d="{ANVIL}" fill="{a}"/>
  <path d="{star(58,34,15)}" fill="{s}"/>
  <path d="{star(75,27,5.5)}" fill="{s}"/>
  <circle cx="41" cy="31" r="2.2" fill="{s}"/></g>"""

# ---------- C: frame + spark ----------
def frame(mono):
    f = "#000" if mono else CREAM
    s = "#000" if mono else "url(#amber)"
    sc = (73, 38); sr = 13
    return f"""<g transform="translate(54 54) scale(.84) translate(-57 -51)">
<mask id="frm{mono}"><rect width="108" height="108" fill="#fff"/>
  <path d="{star(*sc, sr)}" fill="#000" stroke="#000" stroke-width="7" stroke-linejoin="round"/></mask>
<g mask="url(#frm{mono})">
  <rect x="31" y="40" width="42" height="36" rx="8" fill="none" stroke="{f}" stroke-width="5"/>
  <path d="M38,70 L48,57 L55,65 L60,60 L67,70 Z" fill="{f}" stroke="{f}" stroke-width="2" stroke-linejoin="round"/>
</g>
<path d="{star(*sc, sr)}" fill="{s}"/></g>"""

CONCEPTS = {"a-aperture": aperture, "b-anvil": anvil, "c-frame": frame}

def svg(body, bg=True):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108"><defs>{DEFS}</defs>'
            + ('<rect width="108" height="108" fill="url(#bg)"/>' if bg else "") + body + "</svg>")

for name, fn in CONCEPTS.items():
    open(f"{OUT}/{name}.svg", "w").write(svg(fn(False)))
    open(f"{OUT}/{name}-mono.svg", "w").write(svg(fn(True), bg=False))

# ---------- comparison sheet ----------
CUR = os.path.abspath(f"{OUT}/../../app/src/main/res/drawable-nodpi/ic_launcher_artwork.png")
CUR_BG = '<linearGradient id="cbg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E8EEF6"/></linearGradient>'
S, GAP, LBL = 150, 40, 230
cols = ["Squircle", "Circle", "Themed light", "Themed dark", "48 dp"]
rows = [("Actual", None), ("A · Obturador-chispa", "a-aperture"), ("B · Yunque de luz", "b-anvil"), ("C · Marco + destello", "c-frame")]
W = LBL + len(cols)*(S+GAP); H = 70 + len(rows)*(S+GAP)
o = [f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{W}" height="{H}"><defs>{DEFS}{CUR_BG}'
     '<clipPath id="sq" clipPathUnits="objectBoundingBox"><rect width="1" height="1" rx=".3"/></clipPath>'
     '<clipPath id="ci" clipPathUnits="objectBoundingBox"><circle cx=".5" cy=".5" r=".5"/></clipPath></defs>'
     f'<rect width="{W}" height="{H}" fill="#ECEAF0"/>']
for i, c in enumerate(cols):
    o.append(f'<text x="{LBL+i*(S+GAP)+S/2}" y="40" font-family="sans-serif" font-size="18" text-anchor="middle" fill="#333">{c}</text>')
THEMES = [("#D6E3FF", "#284777"), ("#1F2B3D", "#AEC6FF")]
for r, (label, name) in enumerate(rows):
    y = 70 + r*(S+GAP)
    o.append(f'<text x="20" y="{y+S/2+6}" font-family="sans-serif" font-size="19" font-weight="bold" fill="#222">{label}</text>')
    def inner(mono_color=None):
        if name is None:
            if mono_color: return None
            return f'<rect width="108" height="108" fill="url(#cbg)"/><image x="0" y="0" width="108" height="108" xlink:href="file://{CUR}"/>'
        if mono_color:
            return None
        return open(f"{OUT}/{name}.svg").read().split("</defs>",1)[1].rsplit("</svg>",1)[0]
    for ci in range(5):
        x = LBL + ci*(S+GAP)
        size = S if ci < 4 else 48
        xx, yy = (x, y) if ci < 4 else (x + (S-48)/2, y + (S-48)/2)
        clip = "ci" if ci == 1 else "sq"
        if ci in (2, 3):
            bgc, fgc = THEMES[ci-2]
            if name is None:
                o.append(f'<g transform="translate({x} {y})"><rect width="{S}" height="{S}" rx="{S*.3}" fill="none" stroke="#999" stroke-dasharray="6 6"/>'
                         f'<text x="{S/2}" y="{S/2-4}" font-family="sans-serif" font-size="14" text-anchor="middle" fill="#666">sin capa</text>'
                         f'<text x="{S/2}" y="{S/2+16}" font-family="sans-serif" font-size="14" text-anchor="middle" fill="#666">monochrome</text></g>')
                continue
            mono = open(f"{OUT}/{name}-mono.svg").read().split("</defs>",1)[1].rsplit("</svg>",1)[0].replace('fill="#000"', f'fill="{fgc}"').replace(f'<rect width="108" height="108" fill="{fgc}"', '<rect width="108" height="108" fill="#000"')
            body = f'<rect width="108" height="108" fill="{bgc}"/>' + mono
        else:
            body = inner()
        o.append(f'<svg x="{xx}" y="{yy}" width="{size}" height="{size}" viewBox="18 18 72 72" clip-path="url(#{clip})">'
                 f'<g clip-path="url(#{clip})">{body}</g></svg>')
o.append("</svg>")
open(f"{OUT}/sheet.svg", "w").write("".join(o))
