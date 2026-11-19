#!/usr/bin/env python3
"""Draws the README banner and subtitles into .github/assets/readme/.

The banner copies the ii shell's repository banner: the shell's own topography texture
(a numpy port of modules/ii/easyEffects/components/shaders/topography.frag, same palette)
behind the logo and the name in Google Sans Flex. Needs numpy and Pillow.

    python3 tools/make-readme-assets.py
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / ".github" / "assets" / "readme"
FONT = ROOT / "app" / "src" / "main" / "res" / "font" / "google_sans_flex.ttf"
LOGO = ROOT / ".github" / "assets" / "stelnet-tools-512.png"

SURFACE = (20, 12, 31)       # the shell's banner surface
ON_SURFACE = (242, 242, 242)  # contour lines, drawn at 36 % like the EasyEffects hero
LAVENDER = (204, 189, 255)    # the name
ACCENT = (197, 157, 255)      # the subtitle's emphasis, as in the shell's


# --- topography.frag, ported -------------------------------------------------------

def fract(x):
    return x - np.floor(x)


def hash3(x, y, z):
    px, py, pz = (fract(v * 0.3183099 + o) * 17.0 for v, o in ((x, 0.1), (y, 0.2), (z, 0.3)))
    return fract(px * py * pz * (px + py + pz))


def noise(x, y, z):
    ix, iy, iz = np.floor(x), np.floor(y), np.floor(z)
    fx, fy, fz = (f * f * (3.0 - 2.0 * f) for f in (x - ix, y - iy, z - iz))

    def h(dx, dy, dz):
        return hash3(ix + dx, iy + dy, iz + dz)

    def mix(a, b, t):
        return a + (b - a) * t

    return mix(
        mix(mix(h(0, 0, 0), h(1, 0, 0), fx), mix(h(0, 1, 0), h(1, 1, 0), fx), fy),
        mix(mix(h(0, 0, 1), h(1, 0, 1), fx), mix(h(0, 1, 1), h(1, 1, 1), fx), fy),
        fz,
    )


def fbm(x, y, z):
    total, amplitude = 0.0, 0.5
    for _ in range(4):
        total = total + amplitude * noise(x, y, z)
        x, y, z = x * 2.03 + 11.7, y * 2.03 + 3.1, z * 2.03 + 5.3
        amplitude *= 0.5
    return total


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def topography(w, h, hill=380.0, levels=12.0, thin=2.6, index=4.4, time=3.0):
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float64)
    px, py = xs / hill, ys / hill
    strength = 1.9
    wx = fbm(px * 0.9, py * 0.9, np.full_like(px, time * 0.6))
    wy = fbm(px * 0.9 + 7.3, py * 0.9 + 7.3, np.full_like(px, time * 0.6 + 3.1))
    height = fbm(px + strength * wx, py + strength * wy, np.full_like(px, time * 0.35))
    v = ((height - 0.5) * 3.2 + 0.5) * levels
    nearest = np.floor(v + 0.5)
    gy, gx = np.gradient(v)
    grad = np.maximum(np.abs(gx) + np.abs(gy), 1e-4)
    dist = np.abs(v - nearest) / grad
    is_index = np.mod(nearest, 5.0) < 0.5
    width = np.where(is_index, index, thin)
    line = 1.0 - smoothstep(width * 0.5 - 0.5, width * 0.5 + 0.5, dist)
    return line * np.where(is_index, 1.0, 0.8)


def rounded_mask(w, h, r, scale=4):
    big = Image.new("L", (w * scale, h * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, w * scale - 1, h * scale - 1), r * scale, fill=255)
    return big.resize((w, h), Image.LANCZOS)


# --- text -------------------------------------------------------------------------

def face(size, wdth=100, wght=400, rond=0, slnt=0):
    f = ImageFont.truetype(str(FONT), size)
    f.set_variation_by_axes([wdth, wght, rond, slnt])
    return f


def banner(w=3074, h=474):
    line = topography(w, h)
    base = np.zeros((h, w, 3)) + SURFACE
    base += (np.array(ON_SURFACE) - base) * (line * 0.36)[..., None]
    img = Image.fromarray(base.astype(np.uint8)).convert("RGBA")

    logo_h = 252
    logo = Image.open(LOGO).convert("RGBA").resize((logo_h, logo_h), Image.LANCZOS)
    name = face(224, wdth=132, wght=860, rond=100)
    draw = ImageDraw.Draw(img)
    box = draw.textbbox((0, 0), "Stelnet Tools", font=name)
    gap = 52
    total = logo_h + gap + (box[2] - box[0])
    x0 = (w - total) // 2
    img.alpha_composite(logo, (x0, (h - logo_h) // 2))
    tx = x0 + logo_h + gap - box[0]
    ty = (h - (box[3] - box[1])) // 2 - box[1]
    draw.text((tx, ty), "Stelnet Tools", font=name, fill=LAVENDER + (255,))

    img.putalpha(rounded_mask(w, h, 52))
    img.save(OUT / "logo-banner.png", optimize=True)


def subtitle(name, plain, dim):
    """One line, mixed weights like the shell's: light, bold accent, light, italic."""
    parts = [
        ("The ", face(60, 100, 400), plain, 0.92),
        ("Android companion", face(60, 125, 720, 100), ACCENT, 1.0),
        (" for the ", face(60, 100, 400), plain, 0.92),
        ("ii shell", face(60, 125, 760, 100, -10), plain, 0.55),
    ]
    probe = ImageDraw.Draw(Image.new("L", (1, 1)))
    widths = [probe.textlength(t, font=f) for t, f, _, _ in parts]
    img = Image.new("RGBA", (int(sum(widths)) + 8, 96), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    x = 4.0
    for (text, font, colour, alpha), wd in zip(parts, widths):
        draw.text((x, 48), text, font=font, fill=colour + (int(255 * alpha),), anchor="lm")
        x += wd
    img.save(OUT / name, optimize=True)
    return img.size


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    banner()
    print(subtitle("subtitle-dark.png", (246, 246, 246), 0.55))
    print(subtitle("subtitle-light.png", (21, 21, 24), 0.55))
