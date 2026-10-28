#!/usr/bin/env python3
"""Make a device screenshot cheap for an LLM agent to look at.

Usage: shot-slim.py <full.png> [--edge PX] [--state-dir DIR]

Keeps <full.png> untouched as evidence and prints ONE line telling the agent
what to Read:

  UNCHANGED vs <prev> (diff 0.03%) -- do not Read
      the screen looks the same as the previous shot in this directory
  view <path> <w>x<h> x<scale> [changed <x0>,<y0>-<x1>,<y1>]
      a downscaled copy whose long edge is at most --edge px (default 1170:
      1080x2340 -> 540x1170, about half the image tokens of the full shot).
      Multiply coordinates you read on the view by <scale> to get device tap
      coordinates. "changed" is the bounding box,
      in DEVICE pixels, of what moved since the previous shot.
  crop <path> <w>x<h> at <x0>,<y0> x<scale> (only <pct>% of the screen changed)
      when the change is small, a crop of just that region; device coordinate
      = <x0> + <scale> * crop coordinate.

The comparison ignores the top status-bar band (clock, notifications) so a
ticking clock does not count as a change. Set SHOT_SLIM_DIFF (default 0.002 =
0.2% of sampled pixels) to tune what counts as unchanged.
"""
import argparse
import json
import os
import sys

from PIL import Image, ImageChops

SAMPLE_W = 216  # comparison grid width; cheap and robust to AA noise
PIXEL_TOL = 24  # per-channel difference below this is noise
STATUS_BAR_FRACTION = 0.035  # top band ignored when diffing
CROP_MAX_AREA = 0.35  # emit a crop when the change covers less than this


def sample(img):
    w, h = img.size
    small = img.convert("L").resize((SAMPLE_W, max(1, round(h * SAMPLE_W / w))), Image.BILINEAR)
    band = round(small.size[1] * STATUS_BAR_FRACTION)
    return small, band


def diff_box(prev_path, img):
    """Return (fraction changed, bbox in device px or None), or None if no comparable previous shot."""
    try:
        prev = Image.open(prev_path)
    except (OSError, FileNotFoundError):
        return None
    if prev.size != img.size:
        return None
    a, band = sample(prev)
    b, _ = sample(img)
    d = ImageChops.difference(a, b).point(lambda v: 255 if v > PIXEL_TOL else 0)
    d.paste(0, (0, 0, d.size[0], band))
    hist = d.histogram()
    frac = hist[255] / (d.size[0] * d.size[1])
    box = d.getbbox()
    if box:
        k = img.size[0] / d.size[0]
        x0, y0, x1, y1 = box
        pad = 2
        box = (max(0, int((x0 - pad) * k)), max(0, int((y0 - pad) * k)),
               min(img.size[0], int((x1 + pad) * k)), min(img.size[1], int((y1 + pad) * k)))
    return frac, box


def fmt(k):
    return f"{k:.2f}".rstrip("0").rstrip(".")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("full")
    ap.add_argument("--edge", type=int, default=int(os.environ.get("SHOT_SLIM_EDGE", "1170")))
    ap.add_argument("--state-dir", default=None)
    args = ap.parse_args()

    threshold = float(os.environ.get("SHOT_SLIM_DIFF", "0.002"))
    full = os.path.abspath(args.full)
    try:
        img = Image.open(full)
        img.load()
    except OSError as e:
        print(f"shot-slim: cannot open {full}: {e}", file=sys.stderr)
        print(f"view {full} (unprocessed)")
        return 1
    img = img.convert("RGB")
    w, h = img.size
    state_dir = args.state_dir or os.path.dirname(full)
    state_file = os.path.join(state_dir, ".shot-slim-last.json")
    stem = os.path.splitext(full)[0]

    prev = None
    try:
        with open(state_file) as f:
            prev = json.load(f).get("last")
    except (OSError, ValueError):
        pass

    result = diff_box(prev, img) if prev and os.path.abspath(prev) != full else None
    with open(state_file, "w") as f:
        json.dump({"last": full}, f)

    if result is not None:
        frac, box = result
        if frac <= threshold:
            print(f"UNCHANGED vs {os.path.basename(prev)} (diff {frac:.2%}) -- do not Read; same screen as before")
            return 0

    s = max(1.0, max(w, h) / args.edge)
    vw, vh = round(w / s), round(h / s)
    view_path = stem + ".view.png"
    img.resize((vw, vh), Image.LANCZOS).save(view_path, optimize=True)

    if result is not None and result[1]:
        frac, (x0, y0, x1, y1) = result
        area = (x1 - x0) * (y1 - y0) / (w * h)
        if area < CROP_MAX_AREA:
            crop_path = stem + ".crop.png"
            # small regions keep more detail: downscale less
            cs = 1.0 if area < 0.08 else s
            cw, ch = round((x1 - x0) / cs), round((y1 - y0) / cs)
            img.crop((x0, y0, x1, y1)).resize((cw, ch), Image.LANCZOS).save(crop_path, optimize=True)
            print(f"crop {crop_path} {cw}x{ch} at {x0},{y0} x{fmt(cs)} "
                  f"(only {area:.0%} of the screen changed; full view: {view_path} x{fmt(s)})")
            return 0
        print(f"view {view_path} {vw}x{vh} x{fmt(s)} changed {x0},{y0}-{x1},{y1}")
        return 0
    print(f"view {view_path} {vw}x{vh} x{fmt(s)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
