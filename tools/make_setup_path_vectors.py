#!/usr/bin/env python3
"""Builds the setup wizard's path illustration as vector art, for both apps.

The scene is described once, below, and written out as:

  - Android vector drawables, one per wizard step, light and dark
      androidApp/src/main/res/drawable[-night]/setup_path_{1,2,3}.xml
  - iOS SVG assets, one per step, with a dark appearance
      iosApp/iosApp/Assets.xcassets/SetupPath{1,2,3}.imageset/
  - the whole scene, light and dark, for reviewing it as one picture
      design/illustration/setup-path[-dark].svg

It is a redraw of the painted source (design/illustration/setup-path-source.png),
not a trace: tracing soft gradients produces banded, heavy paths. Each step gets
only its own third, so a page draws one screen of art rather than three.

The background is left transparent, so the art sits on the screen's own ground
and the wizard's top and bottom fades blend into it in either theme.

Run from the repository root:  python3 tools/make_setup_path_vectors.py
"""

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The scene is 3:1, as the source is: three 600-unit squares, one per step.
WIDTH, HEIGHT, THIRD = 1800, 600, 600

PALETTES = {
    "light": {
        "haze": "#CFEFE1", "hill": "#B5E6D0", "hill_low": "#DDF4EA",
        "mount_top": "#5CCB9E", "mount_base": "#B9E9D4",
        "river_glow": "#86D9B6", "river_edge": "#8FDCBC", "river": "#34B783", "river_deep": "#1F9A69",
        "card": "#FFFFFF", "card_line": "#E3F3EB", "shadow": "#0B3D2A",
        "icon": "#17845A", "icon_soft": "#27A46F", "on_icon": "#FFFFFF",
        "leaf": "#3DBB86", "leaf_dark": "#2A9F6E", "vein": "#E8F8F0",
        "sun": "#F2DC8C", "sun_core": "#F8EDBF", "dot": "#C8EAD9",
    },
    "dark": {
        "haze": "#173026", "hill": "#1D3D30", "hill_low": "#18332A",
        "mount_top": "#3A9E75", "mount_base": "#1E4435",
        "river_glow": "#2F7A5B", "river_edge": "#2E6E54", "river": "#3FC08A", "river_deep": "#2A9C6C",
        "card": "#1C2A24", "card_line": "#26382F", "shadow": "#000000",
        "icon": "#6FDCAC", "icon_soft": "#4CC792", "on_icon": "#0F1C17",
        "leaf": "#3FAF80", "leaf_dark": "#2E8C64", "vein": "#244A3A",
        "sun": "#C9B36A", "sun_core": "#DCC98A", "dot": "#24463A",
    },
}


# ── Geometry helpers ─────────────────────────────────────────────────────

def f(value):
    """A coordinate, short enough to keep the files small."""
    text = f"{value:.1f}"
    return text[:-2] if text.endswith(".0") else text


def rounded_rect(x, y, w, h, r):
    return (
        f"M{f(x + r)},{f(y)} H{f(x + w - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w)},{f(y + r)} "
        f"V{f(y + h - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w - r)},{f(y + h)} "
        f"H{f(x + r)} A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y + h - r)} "
        f"V{f(y + r)} A{f(r)},{f(r)} 0 0 1 {f(x + r)},{f(y)} Z"
    )


def circle(cx, cy, r):
    return (
        f"M{f(cx - r)},{f(cy)} A{f(r)},{f(r)} 0 1 0 {f(cx + r)},{f(cy)} "
        f"A{f(r)},{f(r)} 0 1 0 {f(cx - r)},{f(cy)} Z"
    )


def smooth(points):
    """A curve through every point (Catmull-Rom, written as cubic Béziers)."""
    pts = [points[0]] + points + [points[-1]]
    d = f"M{f(points[0][0])},{f(points[0][1])}"
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d += f" C{f(c1[0])},{f(c1[1])} {f(c2[0])},{f(c2[1])} {f(p2[0])},{f(p2[1])}"
    return d


def leaf(x, y, length, width, angle):
    """A leaf growing from (x, y), turned `angle` degrees clockwise from upright."""
    a = math.radians(angle)
    cos, sin = math.cos(a), math.sin(a)

    def at(px, py):
        return (x + px * cos - py * sin, y + px * sin + py * cos)

    L, W = length, width
    base, tip = at(0, 0), at(0, -L)
    c = [at(W * 0.6, -L * 0.25), at(W * 0.55, -L * 0.8), at(-W * 0.55, -L * 0.8), at(-W * 0.6, -L * 0.25)]
    blade = (
        f"M{f(base[0])},{f(base[1])} C{f(c[0][0])},{f(c[0][1])} {f(c[1][0])},{f(c[1][1])} {f(tip[0])},{f(tip[1])} "
        f"C{f(c[2][0])},{f(c[2][1])} {f(c[3][0])},{f(c[3][1])} {f(base[0])},{f(base[1])} Z"
    )
    vein_end = at(0, -L * 0.78)
    vein = f"M{f(base[0])},{f(base[1])} L{f(vein_end[0])},{f(vein_end[1])}"
    return blade, vein


def linear(x1, y1, x2, y2, *stops):
    return ("linear", (x1, y1, x2, y2), stops)


def radial(cx, cy, r, *stops):
    return ("radial", (cx, cy, r), stops)


# ── The scene ────────────────────────────────────────────────────────────

def scene(p):
    """Every shape, back to front. Coordinates are in the 1800 × 600 scene."""
    s = []

    def fill(d, paint, alpha=1.0):
        s.append({"d": d, "fill": paint, "fill_alpha": alpha})

    def stroke(d, paint, width, alpha=1.0):
        s.append({"d": d, "stroke": paint, "width": width, "stroke_alpha": alpha})

    # Haze: the far hills, fading into the ground below them.
    fill("M0,430 C90,385 190,392 270,405 C335,415 372,395 430,380 C520,360 600,470 700,560 L0,560 Z",
         linear(0, 380, 0, 560, (0, p["hill"], 0.75), (1, p["hill"], 0)))
    fill("M780,500 C860,440 960,300 1060,295 C1160,292 1330,382 1420,402 C1480,415 1520,460 1560,500 Z",
         linear(0, 290, 0, 500, (0, p["haze"], 0.9), (1, p["haze"], 0)))

    # The mountain the path ends on, and the low shoulder in front of it.
    fill("M1250,560 C1330,520 1455,440 1640,288 Q1670,258 1702,284 C1742,318 1770,342 1800,360 "
         "L1800,560 Z",
         linear(1670, 258, 1600, 540, (0, p["mount_top"], 1), (1, p["mount_base"], 0)))
    fill("M1670,262 L1606,470 L1690,470 Z", linear(1670, 262, 1650, 470, (0, p["card"], 0.18), (1, p["card"], 0)))
    fill("M1470,570 C1560,500 1640,420 1715,390 C1750,381 1780,386 1800,390 L1800,570 Z",
         linear(0, 390, 0, 570, (0, p["hill_low"], 1), (1, p["hill_low"], 0)))

    # Sun and dots.
    fill(circle(410, 240, 34), radial(402, 232, 38, (0, p["sun_core"], 1), (1, p["sun"], 1)))
    fill(circle(335, 208, 6), p["dot"])
    fill(circle(297, 256, 4), p["dot"])

    # The path: a soft glow, a darker lower edge, the ribbon, a highlight.
    route = [(190, 520), (300, 470), (400, 425), (504, 350), (630, 362), (738, 405), (864, 398),
             (990, 360), (1080, 356), (1197, 388), (1287, 410), (1377, 404), (1485, 372), (1575, 334)]
    path = smooth(route)
    river = linear(180, 0, 1580, 0,
                   (0, p["river_edge"], 0.0), (0.12, p["river_edge"], 1), (0.3, p["river"], 1),
                   (0.85, p["river"], 1), (1, p["river_edge"], 0.9))
    stroke(path, linear(180, 0, 1580, 0, (0, p["river_glow"], 0), (0.15, p["river_glow"], 0.35),
                        (1, p["river_glow"], 0.25)), 62)
    stroke(smooth([(x, y + 5) for x, y in route]),
           linear(180, 0, 1580, 0, (0, p["river_deep"], 0), (0.2, p["river_deep"], 0.35),
                  (1, p["river_deep"], 0.25)), 30)
    stroke(path, river, 30)
    stroke(smooth([(x, y - 8) for x, y in route[3:12]]), p["card"], 5, 0.18)

    # Leaves, behind the cards they sit against.
    def leaves(*specs):
        for x, y, length, width, angle, dark in specs:
            blade, vein = leaf(x, y, length, width, angle)
            fill(blade, p["leaf_dark"] if dark else p["leaf"])
            stroke(vein, p["vein"], 1.8, 0.7)

    leaves((92, 455, 80, 34, -8, False), (96, 452, 46, 22, 38, True))
    leaves((774, 420, 50, 28, -78, False), (778, 422, 40, 24, -124, True))
    leaves((1298, 420, 46, 26, -76, False), (1302, 422, 38, 22, -122, True))
    leaves((1432, 326, 46, 26, 18, False), (1436, 330, 38, 22, 64, True))
    leaves((1440, 392, 40, 22, 52, True))

    # Cards: a soft two-layer shadow, then the card itself.
    def card(x, y, w, h):
        fill(rounded_rect(x - 6, y + 12, w + 12, h + 10, 22), p["shadow"], 0.025)
        fill(rounded_rect(x - 2, y + 8, w + 4, h + 6, 18), p["shadow"], 0.035)
        fill(rounded_rect(x + 2, y + 4, w - 4, h + 2, 15), p["shadow"], 0.04)
        fill(rounded_rect(x, y, w, h, 14), p["card"])
        stroke(rounded_rect(x, y, w, h, 14), p["card_line"], 1.2)

    # Step 1 — income: a wallet and a coin.
    card(273, 312, 144, 115)
    fill(rounded_rect(314, 331, 50, 16, 6), p["icon_soft"])
    fill(rounded_rect(309, 339, 66, 52, 9), p["icon"])
    fill(rounded_rect(353, 356, 26, 20, 8), p["on_icon"])
    fill(circle(365, 366, 4.5), p["icon"])
    fill(circle(390, 423, 28), p["shadow"], 0.08)
    fill(circle(387, 418, 27), p["card"])
    stroke(circle(387, 418, 17), p["icon_soft"], 3)
    # A plain upward mark rather than a currency sign: the currency comes from
    # the household's region, never from the art.
    stroke(f"M380,423 L387,412 L394,423", p["icon"], 3.2)

    # Step 2 — expenses: a list with a clock.
    card(787, 313, 146, 115)
    fill("M824,344 L878,344 L872,354 L819,354 Z", p["icon"])
    fill("M819,360 L866,360 L861,370 L814,370 Z", p["icon"])
    fill("M814,376 L856,376 L851,386 L809,386 Z", p["icon"])
    fill("M824,344 L834,344 L818,404 L808,404 Z", p["icon"])
    fill(circle(893, 396, 23), p["icon"])
    stroke("M893,396 L893,381", p["on_icon"], 3)
    stroke("M893,396 L903,401", p["on_icon"], 3)

    # Step 3 — debts and investments: a rising chart, and the flag ahead.
    card(1313, 313, 125, 113)
    fill(rounded_rect(1340, 372, 16, 26, 4), p["icon"])
    fill(rounded_rect(1362, 355, 16, 43, 4), p["icon_soft"])
    fill(rounded_rect(1384, 334, 18, 64, 4), p["icon"])
    stroke("M1655,212 L1655,264", p["icon"], 4)
    fill("M1657,213 L1692,222 L1657,233 Z", p["icon_soft"])

    return s


# ── Writers ──────────────────────────────────────────────────────────────

def argb(hex_colour, alpha):
    return f"#{round(alpha * 255):02X}{hex_colour.lstrip('#').upper()}"


def svg_document(shapes, view_x, view_w):
    defs, body = [], []

    def paint(value, key):
        if isinstance(value, str):
            return value, None
        kind, geometry, stops = value
        gid = f"g{len(defs)}"
        stop_xml = "".join(
            f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops
        )
        if kind == "linear":
            x1, y1, x2, y2 = geometry
            defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                        f'x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stop_xml}</linearGradient>')
        else:
            cx, cy, r = geometry
            defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                        f'cx="{cx}" cy="{cy}" r="{r}">{stop_xml}</radialGradient>')
        return f"url(#{gid})", None

    for shape in shapes:
        attrs = [f'd="{shape["d"]}"']
        if "fill" in shape:
            ref, _ = paint(shape["fill"], "fill")
            attrs.append(f'fill="{ref}"')
            if shape.get("fill_alpha", 1) != 1:
                attrs.append(f'fill-opacity="{shape["fill_alpha"]}"')
        else:
            attrs.append('fill="none"')
        if "stroke" in shape:
            ref, _ = paint(shape["stroke"], "stroke")
            attrs += [f'stroke="{ref}"', f'stroke-width="{shape["width"]}"',
                      'stroke-linecap="round"', 'stroke-linejoin="round"']
            if shape.get("stroke_alpha", 1) != 1:
                attrs.append(f'stroke-opacity="{shape["stroke_alpha"]}"')
        body.append(f'<path {" ".join(attrs)}/>')

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{view_w}" height="{HEIGHT}" '
        f'viewBox="0 0 {view_w} {HEIGHT}">\n'
        f'<defs>{"".join(defs)}</defs>\n'
        f'<g transform="translate({-view_x},0)">\n' + "\n".join(body) + "\n</g>\n</svg>\n"
    )


def vector_drawable(shapes, view_x):
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by tools/make_setup_path_vectors.py; edit the script, not this file. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        f'    android:width="{THIRD}dp" android:height="{HEIGHT}dp"',
        f'    android:viewportWidth="{THIRD}" android:viewportHeight="{HEIGHT}">',
        f'    <group android:translateX="{-view_x}">',
    ]

    def gradient_xml(value, attr):
        kind, geometry, stops = value
        if kind == "linear":
            x1, y1, x2, y2 = geometry
            head = (f'android:type="linear" android:startX="{x1}" android:startY="{y1}" '
                    f'android:endX="{x2}" android:endY="{y2}"')
        else:
            cx, cy, r = geometry
            head = (f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" '
                    f'android:gradientRadius="{r}"')
        items = "".join(f'<item android:offset="{o}" android:color="{argb(c, a)}"/>' for o, c, a in stops)
        return f'<aapt:attr name="android:{attr}"><gradient {head}>{items}</gradient></aapt:attr>'

    for shape in shapes:
        attrs = [f'android:pathData="{shape["d"]}"']
        children = []
        if "fill" in shape:
            if isinstance(shape["fill"], str):
                attrs.append(f'android:fillColor="{argb(shape["fill"], 1)}"')
            else:
                children.append(gradient_xml(shape["fill"], "fillColor"))
            if shape.get("fill_alpha", 1) != 1:
                attrs.append(f'android:fillAlpha="{shape["fill_alpha"]}"')
        if "stroke" in shape:
            if isinstance(shape["stroke"], str):
                attrs.append(f'android:strokeColor="{argb(shape["stroke"], 1)}"')
            else:
                children.append(gradient_xml(shape["stroke"], "strokeColor"))
            attrs += [f'android:strokeWidth="{shape["width"]}"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
            if shape.get("stroke_alpha", 1) != 1:
                attrs.append(f'android:strokeAlpha="{shape["stroke_alpha"]}"')
        if children:
            out.append(f'        <path {" ".join(attrs)}>' + "".join(children) + "</path>")
        else:
            out.append(f'        <path {" ".join(attrs)}/>')

    out += ["    </group>", "</vector>", ""]
    return "\n".join(out)


def main():
    res = ROOT / "androidApp/src/main/res"
    assets = ROOT / "iosApp/iosApp/Assets.xcassets"
    design = ROOT / "design/illustration"

    for name, palette in PALETTES.items():
        shapes = scene(palette)
        suffix = "" if name == "light" else "-dark"
        (design / f"setup-path{suffix}.svg").write_text(svg_document(shapes, 0, WIDTH))

        folder = res / ("drawable" if name == "light" else "drawable-night")
        folder.mkdir(parents=True, exist_ok=True)
        for step in range(3):
            (folder / f"setup_path_{step + 1}.xml").write_text(vector_drawable(shapes, step * THIRD))

            imageset = assets / f"SetupPath{step + 1}.imageset"
            imageset.mkdir(parents=True, exist_ok=True)
            svg_name = f"setup_path_{step + 1}{'' if name == 'light' else '_dark'}.svg"
            (imageset / svg_name).write_text(svg_document(shapes, step * THIRD, THIRD))

    for step in range(3):
        contents = {
            "images": [
                {"filename": f"setup_path_{step + 1}.svg", "idiom": "universal"},
                {
                    "appearances": [{"appearance": "luminosity", "value": "dark"}],
                    "filename": f"setup_path_{step + 1}_dark.svg",
                    "idiom": "universal",
                },
            ],
            "info": {"author": "xcode", "version": 1},
            "properties": {"preserves-vector-representation": True},
        }
        (assets / f"SetupPath{step + 1}.imageset" / "Contents.json").write_text(
            json.dumps(contents, indent=2) + "\n"
        )

    print("wrote 6 vector drawables, 6 SVG assets, 2 review SVGs")


if __name__ == "__main__":
    main()
