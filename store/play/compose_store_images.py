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
# Brand: launcher-star palette on the promo video's deep violet-black
BG, FG, SUB = "#0d0b12", "#F4EFF8", "#CAC4D0"
GRAD = "linear-gradient(90deg,#FFC247,#F2607A,#7B61F0)"
BLOBS = [("#7B61F0", .42), ("#F2607A", .30), ("#FFC247", .22)]
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

BASE_CSS = f"""
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{background:{BG};overflow:hidden}}
body{{font-family:'Open Sans','Adwaita Sans','Noto Sans',sans-serif;color:{FG};-webkit-font-smoothing:antialiased}}
.blob{{position:absolute;border-radius:50%;filter:blur(120px)}}
.bar{{height:8px;border-radius:4px;background:{GRAD}}}
"""

def screenshot_html(w, h, caption, shot, kind):
    if kind == "phone":
        fs, pad, top_h = 72, 60, 350
        dev_w = 960; dev_x = (w - dev_w) // 2; dev_y = top_h - 10
        radius, bez = 60, 14
    else:
        fs, pad, top_h = 82, 120, 300
        dev_w = 2060; dev_x = (w - dev_w) // 2; dev_y = top_h - 10
        radius, bez = 60, 18
    blobs = "".join(f'<div class="blob" style="background:{c};opacity:{o};width:{w*0.55}px;height:{w*0.55}px;left:{l}px;top:{t}px"></div>'
                    for (c, o), l, t in zip(BLOBS, (-w*.15, w*.55, w*.2), (-w*.25, -w*.2, h*.75)))
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{BASE_CSS}
html,body{{width:{w}px;height:{h}px}}
#cap{{position:absolute;left:{pad}px;right:{pad}px;top:{60 if kind=='phone' else 50}px;height:{top_h-110}px;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center}}
#cap .bar{{width:120px;margin-bottom:28px}}
#cap h1{{font-size:{fs}px;line-height:1.08;font-weight:800;letter-spacing:-1.5px;color:{FG};text-wrap:balance}}
#dev{{position:absolute;left:{dev_x}px;top:{dev_y}px;width:{dev_w}px;padding:{bez}px;border-radius:{radius}px;background:linear-gradient(145deg,#2c2933,#15131a);
  box-shadow:0 0 0 2px #46424f inset,0 0 0 1px #000,0 40px 120px rgba(0,0,0,.6)}}
#dev img{{display:block;width:100%;border-radius:{radius-bez}px}}
</style></head><body>{blobs}
<div id="cap"><div class="bar"></div><h1>{html.escape(caption)}</h1></div>
<div id="dev"><img src="{shot}"></div></body></html>"""

def feature_html(loc, title, tagline, icon):
    w, h = 1024, 500
    blobs = "".join(f'<div class="blob" style="background:{c};opacity:{o};width:520px;height:520px;left:{l}px;top:{t}px"></div>'
                    for (c, o), l, t in zip(BLOBS, (-120, 640, 380), (-200, -160, 300)))
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{BASE_CSS}
html,body{{width:{w}px;height:{h}px}}
#icon{{position:absolute;left:70px;top:134px;width:232px;height:232px;border-radius:52px;box-shadow:0 18px 60px rgba(0,0,0,.55)}}
#t{{position:absolute;left:340px;right:50px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center}}
#t .bar{{width:84px;margin-bottom:22px}}
#t h1{{font-size:58px;line-height:1.05;font-weight:800;letter-spacing:-1.5px}}
#t p{{margin-top:18px;font-size:26px;line-height:1.3;color:{SUB};max-width:620px}}
</style></head><body>{blobs}<img id="icon" src="{icon}">
<div id="t"><div class="bar"></div><h1>{html.escape(title)}</h1><p>{html.escape(tagline)}</p></div></body></html>"""

def render(page, html_str, w, h, out, fmt="PNG"):
    page.set_viewport_size({"width": w, "height": h})
    page.set_content(html_str, wait_until="load")
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
                    render(page, screenshot_html(w, h, caps[i], shot, kind), w, h, OUT / loc / sub / f"{i+1:02d}.png")
            title = read(loc, "title.txt").split(":")[0].strip() or "Lightforge Studio"
            tag = read(loc, "short_description.txt")
            render(page, feature_html(loc, title, tag, icon_b64), 1024, 500, OUT / loc / "featureGraphic.png")
            print(f"[{loc}] done")
        # default (en-US) feature graphic at the images root
        render(page, feature_html("en-US", read("en-US", "title.txt").split(":")[0], read("en-US", "short_description.txt"), icon_b64), 1024, 500, OUT / "featureGraphic.png")
        br.close()

if __name__ == "__main__":
    main()
