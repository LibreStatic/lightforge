"""Export the chosen icon (E6, amber->violet ramp) as Android VectorDrawables."""
import os, re, subprocess
from gen3 import petals
from gen4 import E6, PALETTES
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(f"{HERE}/../../app/src/main/res/drawable")
KW = dict(E6, colors=PALETTES["Amber→violet ramp"])
PATH_RE = re.compile(r'<path d="([^"]+)" fill="([^"]+)" stroke="[^"]+" stroke-width="([^"]+)"')

def vector(svg_paths, color=None):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for d, fill, sw in PATH_RE.findall(svg_paths):
        c = color or "#FF" + fill.lstrip("#").upper()
        out += ['    <path', f'        android:fillColor="{c}"', f'        android:pathData="{d}"',
                f'        android:strokeColor="{c}"', f'        android:strokeLineJoin="round"',
                f'        android:strokeWidth="{sw}" />']
    return "\n".join(out + ["</vector>", ""])

open(f"{RES}/ic_launcher_foreground.xml", "w").write(vector(petals(mono=False, **KW)))
open(f"{RES}/ic_launcher_monochrome.xml", "w").write(vector(petals(mono=True, **KW), color="#FF000000"))

# 512 px Play Store icon (full-bleed square; Play applies its own mask)
body = '<rect width="108" height="108" fill="url(#bg)"/>' + petals(mono=False, **KW)
from gen2 import DEFS
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="512" height="512"><defs>{DEFS}</defs>{body}</svg>')
open(f"{HERE}/final-playstore-512.svg", "w").write(svg)
subprocess.run(["rsvg-convert", f"{HERE}/final-playstore-512.svg", "-o", f"{HERE}/final-playstore-512.png"], check=True)
