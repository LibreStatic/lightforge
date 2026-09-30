"""Faceted-petal launcher icon variants: same language as the current icon, own geometry and palette."""
import math, os
OUT = os.path.dirname(os.path.abspath(__file__))

DEFS = """<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
  <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E8EEF6"/></linearGradient>"""

# (light facet, dark facet) - a spectrum, deliberately off Google's four hues
SPECTRUM6 = [("#FF7A59", "#E8573A"), ("#FFB938", "#F2961B"), ("#8FD14F", "#6DB432"),
             ("#22C3B0", "#12A193"), ("#5B7CFA", "#3F5CE0"), ("#B266F2", "#9146D6")]
SPECTRUM5 = [SPECTRUM6[i] for i in (0, 1, 3, 4, 5)]

def pt(r, a):
    return (54 + r*math.cos(math.radians(a)), 54 + r*math.sin(math.radians(a)))

def P(points):
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points) + " Z"

def petals(n, half_angle, length, gap, round_r, colors, mono, start=-90):
    out = []
    for i in range(n):
        a = start + i*360/n
        base = gap
        tip = gap + length
        side = (gap + length/2)
        # side vertices sit where a rhombus with the given half-angle at the base reaches
        w = (length/2) * math.tan(math.radians(half_angle))
        mid = pt(side, a)
        nx, ny = -math.sin(math.radians(a)), math.cos(math.radians(a))
        left = (mid[0] - nx*w, mid[1] - ny*w)
        right = (mid[0] + nx*w, mid[1] + ny*w)
        b, t = pt(base, a), pt(tip, a)
        # shrink by round_r so the round stroke restores the size with soft corners
        def inset(p, c=(54, 54)):
            return p
        if mono:
            out.append(f'<path d="{P([b, right, t, left])}" fill="#000" stroke="#000" stroke-width="{2*round_r}" stroke-linejoin="round"/>')
        else:
            lc, dc = colors[i % len(colors)]
            out.append(f'<path d="{P([b, right, t])}" fill="{lc}" stroke="{lc}" stroke-width="{2*round_r}" stroke-linejoin="round"/>')
            out.append(f'<path d="{P([b, t, left])}" fill="{dc}" stroke="{dc}" stroke-width="{2*round_r}" stroke-linejoin="round"/>')
    return "".join(out)

def hex_bloom(mono):   # 6 rhombi, 60 deg at the base: tiles into a hexagram
    return petals(6, 30, 25, 3.4, 1.2, SPECTRUM6, mono)
def penta(mono):       # 5 rhombi, 36 deg half-angle
    return petals(5, 36, 23, 3.4, 1.2, SPECTRUM5, mono)
def spark(mono):       # 6 slender rays with air between them: a light burst
    return petals(6, 19, 26, 3.4, 1.4, SPECTRUM6, mono)

CONCEPTS = {"d-hexbloom": hex_bloom, "e-penta": penta, "f-spark": spark}

def svg(body, bg=True):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108"><defs>{DEFS}</defs>'
            + ('<rect width="108" height="108" fill="url(#bg)"/>' if bg else "") + body + "</svg>")

if __name__ == "__main__":
    for name, fn in CONCEPTS.items():
        open(f"{OUT}/{name}.svg", "w").write(svg(fn(False)))
        open(f"{OUT}/{name}-mono.svg", "w").write(svg(fn(True), bg=False))
