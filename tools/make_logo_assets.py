"""Cut the circle mark out of the PM's logo and produce every icon and splash asset.

Run from anywhere: `python3 tools/make_logo_assets.py` (needs Pillow and numpy).
Regenerate from here rather than editing the PNGs by hand. When a vector or a
larger source arrives, replace design/logo/finai-logo-source.png and re-run;
the circle is located automatically.
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFilter
try:
    import numpy as np
except ImportError:
    np = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)  # output paths below are relative to the repo root
SRC = "design/logo/finai-logo-source.png"
im = Image.open(SRC).convert("RGB")
W, H = im.size
px = im.load()

# --- locate the circle precisely (the band above the wordmark) ---
def ink(p): return sum(p) / 3 < 235
xs, ys = [], []
for y in range(250, 700):
    for x in range(300, 950):
        if ink(px[x, y]):
            xs.append(x); ys.append(y)
x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
cx, cy = (x0 + x1 + 1) / 2, (y0 + y1 + 1) / 2
r = ((x1 - x0 + 1) + (y1 - y0 + 1)) / 4
print(f"circle centre=({cx:.1f},{cy:.1f}) radius={r:.1f}")
INSET = 2.0  # source px shaved off the edge, so no white fringe shows on a dark ground

def mark(d):
    """The circle mark, d px across, transparent outside the circle."""
    box = (cx - r, cy - r, cx + r, cy + r)
    rgb = im.resize((d, d), Image.LANCZOS, box=box)
    ss = 4
    inset = INSET * d / (2 * r) * ss
    mask = Image.new("L", (d * ss, d * ss), 0)
    ImageDraw.Draw(mask).ellipse((inset, inset, d * ss - inset, d * ss - inset), fill=255)
    mask = mask.resize((d, d), Image.LANCZOS)
    out = rgb.convert("RGBA"); out.putalpha(mask)
    return out

def on_canvas(size, diameter, background=None):
    canvas = background.copy().convert("RGBA") if background else Image.new("RGBA", (size, size), (0, 0, 0, 0))
    m = mark(diameter)
    off = (size - diameter) // 2
    canvas.alpha_composite(m, (off, off))
    return canvas

def full_bleed(size, diameter):
    """The mark with its circle's edge colours extended radially to the square's
    edges, so a launcher mask never shows a seam where circle meets background."""
    box = (cx - r, cy - r, cx + r, cy + r)
    rgb = np.asarray(im.resize((diameter, diameter), Image.LANCZOS, box=box), dtype=float)
    # Sample a ring well inside the anti-aliased edge, then blur what was carried
    # outward: copying single edge pixels straight out draws visible streaks.
    edge = diameter / 2 - (INSET + 4) * diameter / (2 * r)
    yy, xx = np.mgrid[0:size, 0:size].astype(float)
    dx, dy = xx + 0.5 - size / 2, yy + 0.5 - size / 2
    dist = np.hypot(dx, dy)
    scale = np.where(dist > edge, edge / np.maximum(dist, 1e-9), 1.0)
    mx = np.clip((dx * scale + diameter / 2).astype(int), 0, diameter - 1)
    my = np.clip((dy * scale + diameter / 2).astype(int), 0, diameter - 1)
    base = Image.fromarray(rgb[my, mx].round().astype("uint8"), "RGB")
    base = base.filter(ImageFilter.GaussianBlur(radius=size * 0.03)).convert("RGBA")
    off = (size - diameter) // 2
    base.alpha_composite(mark(diameter), (off, off))
    return base

def monochrome(img):
    """White silhouette of the light wallet and cards, for Android themed icons."""
    out = Image.new("RGBA", img.size, (255, 255, 255, 0))
    src, dst = img.load(), out.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            rr, gg, bb, aa = src[x, y]
            lum = 0.299 * rr + 0.587 * gg + 0.114 * bb
            alpha = min(1.0, max(0.0, (lum - 160) / 50)) * (aa / 255)
            dst[x, y] = (255, 255, 255, round(alpha * 255))
    return out

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)

A = "androidApp/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, s in DENSITIES.items():
    # Adaptive icon: one full-bleed layer (80dp circle, edges extended) as the
    # background; the foreground is empty. The wallet stays inside the 66dp safe zone.
    save(full_bleed(round(108 * s), round(80 * s)).convert("RGB"), f"{A}/mipmap-{name}/ic_launcher_background.png")
    save(monochrome(on_canvas(round(108 * s), round(80 * s))), f"{A}/mipmap-{name}/ic_launcher_monochrome.png")
    legacy = on_canvas(round(48 * s), round(46 * s))                     # API 24-25 launchers
    save(legacy, f"{A}/mipmap-{name}/ic_launcher.png")
    save(legacy, f"{A}/mipmap-{name}/ic_launcher_round.png")
# The in-app splash logo, 120dp. (The system splash deliberately has no icon.)
save(mark(360), f"{A}/drawable-xxhdpi/logo_mark.png")

I = "iosApp/iosApp/Assets.xcassets"
os.makedirs(f"{I}/LogoMark.imageset", exist_ok=True)
for suffix, d in (("", 120), ("@2x", 240), ("@3x", 360)):
    save(mark(d), f"{I}/LogoMark.imageset/LogoMark{suffix}.png")
json.dump({
    "images": [{"filename": f"LogoMark{s}.png", "idiom": "universal", "scale": sc}
               for s, sc in (("", "1x"), ("@2x", "2x"), ("@3x", "3x"))],
    "info": {"author": "xcode", "version": 1},
}, open(f"{I}/LogoMark.imageset/Contents.json", "w"), indent=2)

ICON = 1024; D = 820
light = full_bleed(ICON, D).convert("RGB")                       # App Store icons must be opaque
dark = on_canvas(ICON, D)                                        # iOS 18 dark: system supplies the ground
tinted = dark.copy(); lum = tinted.convert("L"); tinted = Image.merge("RGBA", (lum, lum, lum, dark.getchannel("A")))
save(light, f"{I}/AppIcon.appiconset/AppIcon.png")
save(dark, f"{I}/AppIcon.appiconset/AppIcon-dark.png")
save(tinted, f"{I}/AppIcon.appiconset/AppIcon-tinted.png")
json.dump({
    "images": [
        {"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "dark"}], "filename": "AppIcon-dark.png",
         "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "tinted"}], "filename": "AppIcon-tinted.png",
         "idiom": "universal", "platform": "ios", "size": "1024x1024"},
    ],
    "info": {"author": "xcode", "version": 1},
}, open(f"{I}/AppIcon.appiconset/Contents.json", "w"), indent=2)

