#!/usr/bin/env python3
"""Writes the tiny synthetic DNGs used by RawCameraFramingDeviceTest (Python 3, stdlib only).

    python3 core/raw/src/androidTest/fixtures/make_default_crop_dngs.py

Output goes to core/raw/src/androidTest/assets/. Each file is a 64x48 uncompressed 16-bit RGGB
CFA DNG (DNGVersion 1.4) whose scene is a blue field (outside the camera framing) with an orange
rectangle (the framed area) holding a bright white patch in its top-left corner:

  default_crop_even.dng  DefaultCropOrigin 10,8   DefaultCropSize 30x20  (framed 30x20)
  default_crop_odd.dng   DefaultCropOrigin 11,9   DefaultCropSize 30x20  (odd origin; LibRaw
                         rounds it up to the even 12,10, so the framed size is 29x19)
  no_crop.dng            no DefaultCrop tags

The orange rectangle and white patch always follow the *declared* crop (even variant: raw x 10..39,
y 8..27; odd variant: x 11..40, y 9..28), the white patch being its first 8x8 pixels. The crop stays
away from the right and bottom edges because LibRaw only promotes a DefaultCrop that ends strictly
inside the raw area.
"""
import os
import struct

WIDTH, HEIGHT = 64, 48
BLACK, WHITE = 256, 16383
BLUE = (1500, 3500, 12000)      # R, G, B in raw counts above black
ORANGE = (12000, 5000, 1200)
PATCH = (15000, 15000, 15000)
PATTERN = (0, 1, 1, 2)          # RGGB: 0=R 1=G 2=B

BYTE, ASCII, SHORT, LONG, RATIONAL, SRATIONAL = 1, 2, 3, 4, 5, 10
SIZES = {BYTE: 1, ASCII: 1, SHORT: 2, LONG: 4, RATIONAL: 8, SRATIONAL: 8}


def scene(crop):
    """Raw CFA samples (row-major, 16 bit) for a scene painted around [crop] (x, y, w, h) or None."""
    samples = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            colour = BLUE
            if crop:
                cx, cy, cw, ch = crop
                if cx <= x < cx + cw and cy <= y < cy + ch:
                    colour = PATCH if x < cx + 8 and y < cy + 8 else ORANGE
            channel = PATTERN[(y % 2) * 2 + (x % 2)]
            samples.append(min(WHITE, BLACK + colour[channel]))
    return samples


def rational(value, denominator=10000):
    return (round(value * denominator), denominator)


def build(crop):
    entries = []  # (tag, type, values)

    def add(tag, kind, values):
        entries.append((tag, kind, values))

    pixel_bytes = WIDTH * HEIGHT * 2
    add(254, LONG, [0])
    add(256, LONG, [WIDTH])
    add(257, LONG, [HEIGHT])
    add(258, SHORT, [16])
    add(259, SHORT, [1])
    add(262, SHORT, [32803])  # CFA
    add(271, ASCII, b"Lightforge\0")
    add(272, ASCII, b"Synthetic DNG\0")
    add(273, LONG, [0])  # patched below once the layout is known
    add(274, SHORT, [1])
    add(277, SHORT, [1])
    add(278, LONG, [HEIGHT])
    add(279, LONG, [pixel_bytes])
    add(284, SHORT, [1])
    add(33421, SHORT, [2, 2])
    add(33422, BYTE, bytes(PATTERN))
    add(50706, BYTE, bytes([1, 4, 0, 0]))
    add(50707, BYTE, bytes([1, 1, 0, 0]))
    add(50708, ASCII, b"Lightforge Synthetic DNG\0")
    add(50714, SHORT, [BLACK])
    add(50717, SHORT, [WHITE])
    if crop:
        # The declared crop is relative to the full raw area; ActiveArea is the whole frame.
        add(50719, SHORT, [crop[0], crop[1]])
        add(50720, SHORT, [crop[2], crop[3]])
    identity = [1, 0, 0, 0, 1, 0, 0, 0, 1]
    # XYZ(D50) -> camera: an identity-ish matrix is enough for LibRaw to accept the colour data.
    add(50721, SRATIONAL, [x for v in identity for x in rational(v)])
    add(50728, RATIONAL, [x for v in (0.5, 1.0, 0.6) for x in rational(v)])
    add(50778, SHORT, [21])  # CalibrationIlluminant1 = D65
    entries.sort(key=lambda e: e[0])

    count = len(entries)
    ifd_size = 2 + count * 12 + 4
    extra_start = 8 + ifd_size
    extra = bytearray()
    placed = {}
    for tag, kind, values in entries:
        raw = encode(kind, values)
        if len(raw) > 4:
            placed[tag] = extra_start + len(extra)
            extra += raw + (b"\0" if len(raw) % 2 else b"")
    pixel_offset = extra_start + len(extra)
    out = bytearray(b"II*\0" + struct.pack("<I", 8))
    out += struct.pack("<H", count)
    for tag, kind, values in entries:
        raw = encode(kind, values)
        n = len(raw) // SIZES[kind]
        if tag == 273:
            values, raw = [pixel_offset], struct.pack("<I", pixel_offset)
        field = struct.pack("<I", placed[tag]) if tag in placed else raw.ljust(4, b"\0")
        out += struct.pack("<HHI", tag, kind, n) + field
    out += struct.pack("<I", 0)
    assert len(out) == extra_start
    out += extra
    out += struct.pack("<%dH" % (WIDTH * HEIGHT), *scene(crop))
    return bytes(out)


def encode(kind, values):
    if kind in (ASCII, BYTE):
        return bytes(values)
    if kind == SHORT:
        return struct.pack("<%dH" % len(values), *values)
    if kind == LONG:
        return struct.pack("<%dI" % len(values), *values)
    if kind == RATIONAL:
        return struct.pack("<%dI" % len(values), *values)
    if kind == SRATIONAL:
        return struct.pack("<%di" % len(values), *values)
    raise ValueError(kind)


def main():
    target = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")
    os.makedirs(target, exist_ok=True)
    for name, crop in (
        ("default_crop_even.dng", (10, 8, 30, 20)),
        ("default_crop_odd.dng", (11, 9, 30, 20)),
        ("no_crop.dng", None),
    ):
        with open(os.path.join(target, name), "wb") as handle:
            handle.write(build(crop))
        print("wrote", name)


if __name__ == "__main__":
    main()
