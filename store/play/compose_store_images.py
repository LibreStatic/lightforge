#!/usr/bin/env python3
"""Compose Google Play store graphics (framed screenshots with localized captions, feature graphics, icon).

Usage: python3 store/play/compose_store_images.py [--only locale ...]
Inputs : store/play/raw/<locale>/{phone,tablet}/NN.png, store/play/listings/<locale>/{screenshot_captions,title,short_description}.txt
Outputs: store/play/images/<locale>/{phoneScreenshots,tabletScreenshots}/NN.png, featureGraphic.png, store/play/images/{icon,featureGraphic}.png
Rendering uses headless Chrome through Playwright (same approach as scripts/screenshot-mockups.js).
"""
import argparse, base64, html, io, pathlib, sys
from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent
LISTINGS, RAW, OUT = ROOT / "listings", ROOT / "raw", ROOT / "images"
LOCALES = ["en-US", "es-419", "es-ES", "pt-BR", "pt-PT", "fr-FR", "de-DE", "it-IT"]
RAW_ALIAS = {"es-ES": "es-419", "pt-PT": "pt-BR"}     # reuse captures taken in the sibling locale
CHROME = "/usr/bin/google-chrome-stable"
# Brand: light "Google-ish" canvas with Material 3 Expressive shapes in the launcher star's tonal containers
BG, FG, SUB = "#FFFFFF", "#1B1B1F", "#47464F"
FONTS = "https://fonts.googleapis.com/css2?family=Google+Sans+Flex:opsz,wght@6..144,400..800&family=Roboto+Flex:opsz,wght@8..144,400..800&display=block"
TONES = ["#E8DEFF", "#FFD9DF", "#FFE7B3", "#D3E3FD", "#C9EED6"]   # violet, coral, amber, blue, green containers
TAGLINES = {  # feature-graphic tagline (from the locale short descriptions' message)
}

def b64(path, fmt="PNG", quality=92, max_w=None):
    im = Image.open(path).convert("RGB")
    if max_w and im.width > max_w:
        im = im.resize((max_w, round(im.height * max_w / im.width)), Image.LANCZOS)
    buf = io.BytesIO(); im.save(buf, fmt, quality=quality)
    return f"data:image/{fmt.lower()};base64," + base64.b64encode(buf.getvalue()).decode()

def read(loc, name):
    p = LISTINGS / loc / name
    return p.read_text(encoding="utf-8").strip() if p.exists() else ""

def _polar(n, amp, r=100, steps=720, rot=0.0):
    """Closed SVG path for r(t) = R(1 + amp*cos(n t)): cookies/flowers/clovers of the M3 Expressive shape set."""
    import math
    pts = []
    for k in range(steps):
        t = 2 * math.pi * k / steps
        rr = r * (1 + amp * math.cos(n * (t + rot))) / (1 + amp)
        pts.append(f"{100 + rr*math.cos(t):.2f},{100 + rr*math.sin(t):.2f}")
    return "M" + " L".join(pts) + "Z"

SHAPES = {
    "cookie9": _polar(9, .07), "cookie12": _polar(12, .05), "clover4": _polar(4, .22, rot=.39),
    "flower8": _polar(8, .12), "sunny": _polar(16, .035),
    "pill": "M60,20 H140 A40,40 0 0 1 140,180 H60 A40,40 0 0 1 60,20Z",
    "arch": "M20,190 V100 A80,80 0 0 1 180,100 V190Z",
    "semi": "M10,140 A90,90 0 0 1 190,140Z",
    "circle": _polar(1, 0),
}

def shape(name, color, x, y, size, rot=0):
    return (f'<svg class="shp" viewBox="0 0 200 200" style="left:{x}px;top:{y}px;width:{size}px;height:{size}px;'
            f'transform:rotate({rot}deg)"><path d="{SHAPES[name]}" fill="{color}"/></svg>')

# Per-shot layouts (fractions of the canvas): (shape, tone index, x, y, size as a fraction of width, rotation).
# One large shape sits centred behind the device and peeks out on both sides; two small ones accent the corners.
_BIG = ["cookie9", "clover4", "flower8", "sunny", "cookie12", "clover4", "flower8", "cookie9"]
_SMALL = [("pill", "circle"), ("semi", "cookie12"), ("circle", "arch"), ("clover4", "pill"),
          ("arch", "circle"), ("cookie9", "semi"), ("pill", "flower8"), ("circle", "clover4")]
LAYOUTS = [
    [(_BIG[i], i % 5, None, .30, 1.12, i * 11),
     (_SMALL[i][0], (i + 1) % 5, .80, .06, .24, -30 + i * 9),
     (_SMALL[i][1], (i + 2) % 5, -.08, .84, .30, 20 - i * 7)]
    for i in range(8)
]

BASE_CSS = f"""
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{background:{BG};overflow:hidden}}
body{{font-family:'Google Sans Flex','Roboto Flex','Noto Sans',sans-serif;color:{FG};-webkit-font-smoothing:antialiased}}
.shp{{position:absolute;overflow:visible}}
"""

def head(extra):
    return f'<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="{FONTS}"><style>{BASE_CSS}{extra}</style></head>'

def backdrop(w, h, idx, scale_ref):
    # x=None centres the shape horizontally
    return "".join(shape(n, TONES[c], (w - s * scale_ref) / 2 if x is None else x * w, y * h, s * scale_ref, r)
                   for n, c, x, y, s, r in LAYOUTS[idx % len(LAYOUTS)])

def screenshot_html(w, h, caption, shot, kind, idx=0):
    if kind == "phone":
        fs, pad, top_h = 76, 70, 360
        dev_w = 800; dev_x = (w - dev_w) // 2; dev_y = top_h
        radius, bez = 64, 12
        bg = backdrop(w, h, idx, w)
    else:
        fs, pad, top_h = 84, 140, 290
        dev_w = 2000; dev_x = (w - dev_w) // 2; dev_y = top_h
        radius, bez = 56, 16
        bg = backdrop(w, h, idx, h * 1.1)
    return head(f"""
html,body{{width:{w}px;height:{h}px}}
#cap{{position:absolute;left:{pad}px;right:{pad}px;top:{50 if kind=='phone' else 30}px;height:{top_h-70}px;display:flex;align-items:center;justify-content:center;text-align:center}}
#cap h1{{font-size:{fs}px;line-height:1.08;font-weight:700;letter-spacing:-1px;color:{FG};text-wrap:balance;font-variation-settings:"opsz" 144}}
#dev{{position:absolute;left:{dev_x}px;top:{dev_y}px;width:{dev_w}px;padding:{bez}px;border-radius:{radius}px;background:#1B1B1F;
  box-shadow:0 0 0 2px #3a3940 inset,0 30px 80px rgba(27,27,31,.22),0 6px 18px rgba(27,27,31,.12)}}
#dev img{{display:block;width:100%;border-radius:{radius-bez}px}}
""") + f"""<body>{bg}
<div id="cap"><h1>{html.escape(caption)}</h1></div>
<div id="dev"><img src="{shot}"></div></body></html>"""

def feature_html(loc, title, tagline, icon):
    w, h = 1024, 500
    bg = (shape("cookie12", TONES[0], 640, -150, 520, 0) + shape("clover4", TONES[1], 860, 260, 300, 18)
          + shape("pill", TONES[2], -90, 360, 260, -28) + shape("circle", TONES[3], 560, 380, 120, 0))
    return head(f"""
html,body{{width:{w}px;height:{h}px}}
#icon{{position:absolute;left:70px;top:134px;width:232px;height:232px;border-radius:52px;box-shadow:0 18px 50px rgba(27,27,31,.22)}}
#t{{position:absolute;left:340px;right:60px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center}}
#t h1{{font-size:60px;line-height:1.05;font-weight:700;letter-spacing:-1px;font-variation-settings:"opsz" 144}}
#t p{{margin-top:18px;font-size:26px;line-height:1.3;color:{SUB};max-width:600px}}
""") + f"""<body>{bg}<img id="icon" src="{icon}">
<div id="t"><h1>{html.escape(title)}</h1><p>{html.escape(tagline)}</p></div></body></html>"""

def render(page, html_str, w, h, out, fmt="PNG"):
    page.set_viewport_size({"width": w, "height": h})
    page.set_content(html_str, wait_until="networkidle")
    page.evaluate("document.fonts.ready")
    page.wait_for_timeout(150)
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = page.screenshot(type="png", clip={"x": 0, "y": 0, "width": w, "height": h})
    Image.open(io.BytesIO(tmp)).convert("RGB").save(out, "PNG", optimize=True)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--only", nargs="*"); a = ap.parse_args()
    locs = a.only or LOCALES
    icon_src = ROOT.parent.parent / "docs/icon-proposals/final-playstore-512.png"
    icon = Image.open(icon_src).convert("RGBA").resize((512, 512), Image.LANCZOS)
    OUT.mkdir(parents=True, exist_ok=True); icon.save(OUT / "icon.png", optimize=True)
    icon_b64 = b64(OUT / "icon.png")
    with sync_playwright() as p:
        br = p.chromium.launch(executable_path=CHROME, args=["--no-sandbox"])
        page = br.new_page()
        for loc in locs:
            caps = read(loc, "screenshot_captions.txt").splitlines()
            if len(caps) < 8:
                print(f"[{loc}] WARNING: captions missing, falling back to en-US"); caps = read("en-US", "screenshot_captions.txt").splitlines()
            raw_loc = RAW_ALIAS.get(loc, loc)
            for kind, (w, h), sub in (("phone", (1080, 2160), "phoneScreenshots"), ("tablet", (2560, 1440), "tabletScreenshots")):
                for i in range(8):
                    src = RAW / raw_loc / kind / f"{i+1:02d}.png"
                    if not src.exists():
                        print(f"[{loc}] missing raw {src}"); continue
                    # tablet raw frames are scaled to the device width used in the layout (saves memory)
                    shot = b64(src, "JPEG", 94, max_w=2000 if kind == "phone" else 2100)
                    render(page, screenshot_html(w, h, caps[i], shot, kind, i), w, h, OUT / loc / sub / f"{i+1:02d}.png")
            title = read(loc, "title.txt").split(":")[0].strip() or "Lightforge Studio"
            tag = read(loc, "short_description.txt")
            render(page, feature_html(loc, title, tag, icon_b64), 1024, 500, OUT / loc / "featureGraphic.png")
            print(f"[{loc}] done")
        # default (en-US) feature graphic at the images root
        render(page, feature_html("en-US", read("en-US", "title.txt").split(":")[0], read("en-US", "short_description.txt"), icon_b64), 1024, 500, OUT / "featureGraphic.png")
        br.close()

if __name__ == "__main__":
    main()
