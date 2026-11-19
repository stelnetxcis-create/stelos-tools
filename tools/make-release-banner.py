#!/usr/bin/env python3
"""Draws a release announcement banner (1920x1080) into .github/assets/releases/.

The style of a "feature drop" card: the name huge and wide, a hairline subtitle, the
highlights as white chips, the version big in a corner, fluid shapes and a scalloped
Material shape holding the icon, all over the shell's topography texture (the same port
as tools/make-readme-assets.py) in Stelnet Tools' purple. Needs numpy and Pillow.

    python3 tools/make-release-banner.py
"""
import importlib.util
import math
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / ".github" / "assets" / "releases"
LOGO = ROOT / ".github" / "assets" / "stelnet-tools-512.png"

sys.dont_write_bytecode = True  # no __pycache__ next to the tools
_spec = importlib.util.spec_from_file_location("readme_assets", Path(__file__).with_name("make-readme-assets.py"))
assets = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(assets)
face = assets.face

VERSION = sys.argv[1] if len(sys.argv) > 1 else re.search(r'versionName = "([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
TITLE = "Stelnet Tools"
SUBTITLE = "for StelOS"
HIGHLIGHTS = [
    "Stelnet Link",
    "New camera-island icon",
    "Updates from GitHub",
]
FOOTER = "github.com/stelnetxcis-create"

W, H = 1920, 1080
S = 2  # drawn at twice the size, then scaled down: smooth curves and hairlines

DEEP = np.array((20, 12, 31), float)      # the shell's banner surface
VIOLET = np.array((126, 87, 194), float)  # the icon's body
GLOW = np.array((176, 132, 240), float)
LAVENDER = (204, 189, 255)
INK = (24, 14, 40)


def background():
    w, h = W * S, H * S
    ys, xs = np.mgrid[0:h, 0:w].astype(float)
    # Deep at the top left, the icon's violet rising from the bottom right.
    t = np.clip((xs / w * 0.55 + ys / h * 0.75) - 0.25, 0, 1) ** 1.6
    base = DEEP + (VIOLET - DEEP) * t[..., None]
    glow = np.exp(-(((xs - w * 0.86) / (w * 0.32)) ** 2 + ((ys - h * 0.18) / (h * 0.45)) ** 2))
    base += (GLOW - base) * (glow * 0.55)[..., None]
    line = assets.topography(w, h, hill=380.0 * S, thin=2.6 * S, index=4.4 * S, time=7.0)
    # A diagonal fade: clear at the top right, gone past the middle towards the bottom left.
    d = (xs / w + (1 - ys / h)) / 2
    fade = assets.smoothstep(0.15, 0.6, d)
    base += (np.array((242, 242, 242)) - base) * (line * 0.2 * fade)[..., None]
    return Image.fromarray(np.clip(base, 0, 255).astype(np.uint8)).convert("RGBA")


def blob(points_fn, n=360):
    return [points_fn(i / n * 2 * math.pi) for i in range(n)]


def fluid(img):
    """Layered wavy masses in the corner, like the reference's liquid shapes."""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    w, h = img.size
    colours = [((92, 58, 156), 150), ((126, 87, 194), 140), ((160, 120, 230), 110), ((204, 189, 255), 70)]
    for k, (c, a) in enumerate(colours):
        cx, cy = w * (0.02 + 0.04 * k), h * (1.08 - 0.02 * k)
        r = h * (0.62 - 0.11 * k)

        def pt(th, cx=cx, cy=cy, r=r, k=k):
            rr = r * (1 + 0.07 * math.sin(3 * th + k) + 0.04 * math.sin(5 * th - k * 0.7))
            return (cx + rr * math.cos(th) * 1.45, cy + rr * math.sin(th))

        d.polygon(blob(pt), fill=c + (a,))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(1.2 * S)))


def cookie(cx, cy, r, lobes=12, depth=0.075):
    """The M3E scalloped cookie: a circle with soft, even lobes."""
    return blob(lambda th: (cx + r * (1 + depth * math.cos(lobes * th)) * math.cos(th),
                            cy + r * (1 + depth * math.cos(lobes * th)) * math.sin(th)), 720)


def chip_row(draw, x0, y0, max_x, label_font, chip_font):
    """'Highlights:' then white pills that wrap like text."""
    pad_x, chip_h, gap = 26 * S, 66 * S, 14 * S
    draw.text((x0, y0 + chip_h / 2), "Highlights:", font=label_font, fill=(255, 255, 255), anchor="lm")
    x = x0 + draw.textlength("Highlights:", font=label_font) + 26 * S
    y = y0
    for text in HIGHLIGHTS + ["+"]:
        tw = draw.textlength(text, font=chip_font)
        cw = tw + pad_x * 2 if text != "+" else chip_h
        if x + cw > max_x:
            x, y = x0, y + chip_h + gap
        draw.rounded_rectangle((x, y, x + cw, y + chip_h), chip_h / 2, fill=(255, 255, 255))
        draw.text((x + cw / 2, y + chip_h / 2 + 1 * S), text, font=chip_font, fill=INK, anchor="mm")
        x += cw + gap
    return y + chip_h


def main():
    img = background()
    fluid(img)
    draw = ImageDraw.Draw(img)
    m = 96 * S

    # The icon on a lavender cookie, top right, bleeding a little off the edge.
    shapes = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shapes)
    ccx, ccy, cr = W * S - 250 * S, 250 * S, 205 * S
    sd.polygon(cookie(ccx, ccy, cr), fill=LAVENDER + (255,))
    img.alpha_composite(shapes)
    logo = Image.open(LOGO).convert("RGBA").resize((int(cr * 1.18),) * 2, Image.LANCZOS)
    mask = Image.new("L", logo.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, logo.size[0] - 1, logo.size[1] - 1), logo.size[0] * 0.26, fill=255)
    logo.putalpha(mask)
    img.alpha_composite(logo, (int(ccx - logo.size[0] / 2), int(ccy - logo.size[1] / 2)))

    # Name and subtitle.
    size = 210
    while draw.textlength(TITLE, font=face(size * S, wdth=132, wght=860, rond=100)) > 1330 * S:
        size -= 4
    draw.text((m - 6 * S, 78 * S + (210 - size) * S * 0.45), TITLE, font=face(size * S, wdth=132, wght=860, rond=100), fill=(255, 255, 255), anchor="lt")
    draw.text((m, 318 * S), SUBTITLE, font=face(104 * S, wdth=151, wght=230, rond=0), fill=(255, 255, 255), anchor="lt")

    # Highlights: the left column, clear of the version in the right one.
    chip_row(draw, m, 500 * S, 1180 * S, face(40 * S, wdth=110, wght=820, rond=100), face(32 * S, wdth=100, wght=520, rond=100))

    # Footer pill: the icon and where to get it.
    fy, fh = H * S - m - 50 * S, 92 * S
    foot = face(36 * S, wdth=125, wght=560, rond=100)
    fw = fh + 24 * S + draw.textlength(FOOTER, font=foot) + 44 * S
    draw.rounded_rectangle((m - 20 * S, fy, m - 20 * S + fw, fy + fh), fh / 2, fill=(255, 255, 255))
    small = Image.open(LOGO).convert("RGBA").resize((fh - 20 * S,) * 2, Image.LANCZOS)
    sm = Image.new("L", small.size, 0)
    ImageDraw.Draw(sm).ellipse((0, 0, small.size[0] - 1, small.size[1] - 1), fill=255)
    small.putalpha(sm)
    img.alpha_composite(small, (m - 10 * S, fy + 10 * S))
    draw.text((m - 10 * S + fh + 14 * S, fy + fh / 2), FOOTER, font=foot, fill=(98, 64, 170), anchor="lm")

    # The version, big in the bottom right, and its line.
    right = W * S - m + 10 * S
    draw.text((right, H * S - m - 66 * S), "v" + VERSION, font=face(190 * S, wdth=132, wght=860, rond=100), fill=LAVENDER, anchor="rs")
    # The font has no arrow: draw one at the end of the line, as the reference does.
    ay = H * S - m + 16 * S
    ax1 = right - 8 * S
    ax0 = ax1 - 34 * S
    cy = ay - 15 * S
    white = (255, 255, 255)
    draw.line((ax0, cy, ax1, cy), fill=white, width=4 * S)
    draw.line((ax1 - 14 * S, cy - 13 * S, ax1, cy, ax1 - 14 * S, cy + 13 * S), fill=white, width=4 * S, joint="curve")
    draw.text((ax0 - 16 * S, ay), "Update in the app or on GitHub", font=face(38 * S, wdth=125, wght=520, rond=100), fill=white, anchor="rs")

    out = img.resize((W, H), Image.LANCZOS).convert("RGB")
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"stelnet-tools-{VERSION}.png"
    out.save(path, optimize=True)
    print(path)


if __name__ == "__main__":
    main()
