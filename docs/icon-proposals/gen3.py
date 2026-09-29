"""More D (6-fold) and E (5-fold) faceted-petal variants."""
import math, os, subprocess, shutil
from gen2 import DEFS, SPECTRUM6
OUT = os.path.dirname(os.path.abspath(__file__))

WARM6 = [("#FF6F61", "#E04E45"), ("#FF9A4D", "#EE7A2A"), ("#FFC94A", "#F0A91E"),
         ("#F2709C", "#D94F7F"), ("#C46BF0", "#A34ED6"), ("#FF8FB1", "#E86C93")]
JEWEL6 = [("#F25F5C", "#C9403E"), ("#FFB140", "#DB8C1C"), ("#3FBF8F", "#249C70"),
          ("#2E9CCA", "#1C7AA6"), ("#5D5FEF", "#3F40C9"), ("#C04CD9", "#9A30B3")]
def five(p): return [p[i] for i in (0, 1, 3, 4, 5)]

def pt(r, a, c=(54, 54)):
    return (c[0] + r*math.cos(math.radians(a)), c[1] + r*math.sin(math.radians(a)))
def P(points):
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points) + " Z"

def petals(n, width, length, gap, rr, colors, mono, start=-90, side=0.5, twist=0, split="axis", gem=0, mono_width=None):
    out = []
    if mono and mono_width: width = mono_width
    for i in range(n):
        a = start + i*360/n
        b, t = pt(gap, a), pt(gap + length, a + twist)
        # side vertices: at `side` fraction along base->tip, offset by +-width/2 perpendicular
        mx, my = b[0] + (t[0]-b[0])*side, b[1] + (t[1]-b[1])*side
        ang = math.atan2(t[1]-b[1], t[0]-b[0])
        nx, ny = -math.sin(ang), math.cos(ang)
        L, R = (mx - nx*width/2, my - ny*width/2), (mx + nx*width/2, my + ny*width/2)
        st = f'stroke-width="{2*rr}" stroke-linejoin="round"'
        if mono:
            out.append(f'<path d="{P([b, R, t, L])}" fill="#000" stroke="#000" {st}/>')
            continue
        lc, dc = colors[i % len(colors)]
        if split == "axis":
            f1, f2 = [b, R, t], [b, t, L]
        else:  # inner/outer facets: light near the center, dark toward the tip
            f1, f2 = [b, R, L], [L, R, t]
        out.append(f'<path d="{P(f1)}" fill="{lc}" stroke="{lc}" {st}/>')
        out.append(f'<path d="{P(f2)}" fill="{dc}" stroke="{dc}" {st}/>')
    if gem:
        g = [pt(gem, start + 180/n + k*360/n) for k in range(n)]
        out.append(f'<path d="{P(g)}" fill="{"#000" if mono else "#FFFFFF"}" stroke="{"#000" if mono else "#FFFFFF"}" stroke-width="1.6" stroke-linejoin="round"/>')
    return "".join(out)

D = {
  "D1 · base":              dict(n=6, width=14.4, length=25, gap=3.4, rr=1.2, colors=SPECTRUM6),
  "D2 · wide petals":    dict(n=6, width=19, length=24, gap=3.4, rr=1.4, colors=SPECTRUM6),
  "D3 · juntos, sin aire":  dict(n=6, width=14.4, length=26, gap=1.3, rr=1.2, colors=SPECTRUM6),
  "D4 · girado 30°":        dict(n=6, width=14.4, length=25, gap=3.4, rr=1.2, colors=SPECTRUM6, start=-60),
  "D5 · remolino":          dict(n=6, width=15, length=25, gap=3.4, rr=1.2, colors=SPECTRUM6, twist=14, side=0.42),
  "D6 · facetas centro/punta": dict(n=6, width=16, length=25, gap=3.4, rr=1.2, colors=SPECTRUM6, split="cross", side=0.4),
  "D7 · paleta joya":       dict(n=6, width=16, length=25, gap=3.4, rr=1.3, colors=JEWEL6, side=0.45),
  "D8 · warm palette":     dict(n=6, width=16, length=25, gap=3.4, rr=1.3, colors=WARM6, side=0.45),
}
E = {
  "E1 · base":              dict(n=5, width=16.7, length=23, gap=3.4, rr=1.2, colors=five(SPECTRUM6)),
  "E2 · wide petals":    dict(n=5, width=21, length=23, gap=3.4, rr=1.5, colors=five(SPECTRUM6)),
  "E3 · punta abajo":       dict(n=5, width=16.7, length=23, gap=3.4, rr=1.2, colors=five(SPECTRUM6), start=90),
  "E4 · kite estilizado":   dict(n=5, width=17, length=27, gap=3.0, rr=1.2, colors=five(SPECTRUM6), side=0.36),
  "E5 · remolino":          dict(n=5, width=17, length=24, gap=3.4, rr=1.2, colors=five(SPECTRUM6), twist=16, side=0.42),
  "E6 · facetas centro/punta": dict(n=5, width=19, length=24, gap=3.4, rr=1.3, colors=five(SPECTRUM6), split="cross", side=0.4),
  "E7 · paleta joya":       dict(n=5, width=18, length=24, gap=3.4, rr=1.3, colors=five(JEWEL6), side=0.45),
  "E8 · warm palette":     dict(n=5, width=18, length=24, gap=3.4, rr=1.3, colors=five(WARM6), side=0.45),
}

CLIPS = {"sq": '<rect x="18" y="18" width="72" height="72" rx="21.6"/>', "ci": '<circle cx="54" cy="54" r="36"/>'}
def render(path, body, clip, px):
    s = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="{px}" height="{px}"><defs>{DEFS}'
         f'<clipPath id="c">{CLIPS[clip]}</clipPath></defs><g clip-path="url(#c)">{body}</g></svg>')
    open(path + ".svg", "w").write(s)
    subprocess.run(["rsvg-convert", path + ".svg", "-o", path + ".png"], check=True)

def sheet(variants, out):
    tmp = f"{OUT}/cells"; os.makedirs(tmp, exist_ok=True)
    tiles = []
    for i, (label, kw) in enumerate(variants.items()):
        color = '<rect width="108" height="108" fill="url(#bg)"/>' + petals(mono=False, **kw)
        mono = '<rect width="108" height="108" fill="#1F2B3D"/>' + petals(mono=True, **kw).replace('"#000"', '"#AEC6FF"')
        mono_l = '<rect width="108" height="108" fill="#D6E3FF"/>' + petals(mono=True, **kw).replace('"#000"', '"#284777"')
        render(f"{tmp}/{i}-sq", color, "sq", 140); render(f"{tmp}/{i}-ci", color, "ci", 140)
        render(f"{tmp}/{i}-ml", mono_l, "sq", 140); render(f"{tmp}/{i}-md", mono, "sq", 140)
        render(f"{tmp}/{i}-48", color, "sq", 48)
        subprocess.run(["magick", "-size", "140x140", "xc:none", f"{tmp}/{i}-48.png", "-gravity", "center", "-composite", "+repage", f"{tmp}/{i}-48b.png"], check=True)
        subprocess.run(["magick", *[f"{tmp}/{i}-{k}.png" for k in ("sq", "ci", "ml", "md", "48b")], "+repage", "-gravity", "northwest", "-background", "none",
                        "-splice", "14x0", "+append", "-gravity", "north", "-splice", "0x34", "-font", "DejaVu-Sans-Bold",
                        "-pointsize", "17", "-fill", "#222", "-gravity", "northwest", "-annotate", "+16+6", label,
                        "-bordercolor", "none", "-border", "10", "+repage", f"{tmp}/tile{i}.png"], check=True)
        tiles.append(f"{tmp}/tile{i}.png")
    rows = []
    for r in range(0, len(tiles), 2):
        rows.append(f"{tmp}/row{r}.png")
        subprocess.run(["magick", *tiles[r:r+2], "+repage", "-gravity", "northwest", "-background", "none", "-splice", "20x0", "+append", rows[-1]], check=True)
    subprocess.run(["magick", *rows, "+repage", "-gravity", "northwest", "-background", "#ECEAF0", "-append", "+repage", "-flatten", f"{OUT}/{out}"], check=True)
    shutil.rmtree(tmp)

if __name__ == "__main__":
    sheet(D, "sheet-d.png"); sheet(E, "sheet-e.png")
