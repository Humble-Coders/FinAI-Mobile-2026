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
        "haze": "#D4F1E4", "hill": "#BDE9D6", "hill_near": "#A6E1C8", "hill_low": "#DDF4EA",
        "mount_top": "#4FC495", "mount_base": "#B9E9D4", "mount_face": "#FFFFFF",
        "river_glow": "#9BE2C4", "river_edge": "#7FD6B1", "river": "#2FB57F", "river_deep": "#17895A",
        "card_top": "#FFFFFF", "card": "#F6FBF8", "card_line": "#E1F2E9", "shadow": "#0B3D2A",
        "icon": "#15855A", "icon_soft": "#2DAA74", "on_icon": "#FFFFFF",
        "leaf": "#58CC98", "leaf_dark": "#239A67", "vein": "#EAF8F1",
        "sun": "#F1D57A", "sun_core": "#FAF0C8", "halo": "#F6E6A6",
        "cloud": "#E3F4EC", "sparkle": "#8FDDBB", "dot": "#C4E9D7",
    },
    "dark": {
        "haze": "#15291F", "hill": "#1A3429", "hill_near": "#1F3E31", "hill_low": "#183127",
        "mount_top": "#3A9E75", "mount_base": "#1C3F31", "mount_face": "#7FE0B6",
        "river_glow": "#2A6B51", "river_edge": "#2F7E5E", "river": "#41C28C", "river_deep": "#1D6E4C",
        "card_top": "#243630", "card": "#1B2A24", "card_line": "#2A3F35", "shadow": "#000000",
        "icon": "#72DDAF", "icon_soft": "#4CC792", "on_icon": "#10201A",
        "leaf": "#54C08F", "leaf_dark": "#2A8A60", "vein": "#23473A",
        "sun": "#CDB464", "sun_core": "#E2D08F", "halo": "#8F7F45",
        "cloud": "#1B3329", "sparkle": "#3F9A73", "dot": "#27493C",
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

def sample(points, steps=10):
    """Points along the same Catmull-Rom curve `smooth` draws, close together."""
    pts = [points[0]] + points + [points[-1]]
    out = [points[0]]
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        for k in range(1, steps + 1):
            t = k / steps
            m = 1 - t
            out.append((
                m ** 3 * p1[0] + 3 * m * m * t * c1[0] + 3 * m * t * t * c2[0] + t ** 3 * p2[0],
                m ** 3 * p1[1] + 3 * m * m * t * c1[1] + 3 * m * t * t * c2[1] + t ** 3 * p2[1],
            ))
    return out


def ribbon(route, start_width, end_width, dy=0.0):
    """A band along `route` that narrows from `start_width` to `end_width`.

    The narrowing is what makes the path read as going into the distance: wide
    in the foreground on the left, fine where it reaches the mountain.
    """
    pts = sample(route)
    n = len(pts)
    left, right = [], []
    for i, (x, y) in enumerate(pts):
        a, b = pts[max(i - 1, 0)], pts[min(i + 1, n - 1)]
        tx, ty = b[0] - a[0], b[1] - a[1]
        length = math.hypot(tx, ty) or 1.0
        nx, ny = -ty / length, tx / length
        half = (start_width + (end_width - start_width) * (i / (n - 1)) ** 0.8) / 2
        left.append((x + nx * half, y + ny * half + dy))
        right.append((x - nx * half, y - ny * half + dy))
    left, right = left[::2] + [left[-1]], right[::2] + [right[-1]]
    forward = smooth(left)
    back = smooth(list(reversed(right)))
    return f"{forward} L{back[1:]} Z"


def sparkle(cx, cy, r):
    """A four-pointed glint."""
    return (f"M{f(cx)},{f(cy - r)} Q{f(cx)},{f(cy)} {f(cx + r)},{f(cy)} Q{f(cx)},{f(cy)} {f(cx)},{f(cy + r)} "
            f"Q{f(cx)},{f(cy)} {f(cx - r)},{f(cy)} Q{f(cx)},{f(cy)} {f(cx)},{f(cy - r)} Z")


def scene(p):
    """Every shape, back to front. Coordinates are in the 1800 × 600 scene."""
    s = []

    def fill(d, paint, alpha=1.0):
        s.append({"d": d, "fill": paint, "fill_alpha": alpha})

    def stroke(d, paint, width, alpha=1.0):
        s.append({"d": d, "stroke": paint, "width": width, "stroke_alpha": alpha})

    def fade_down(colour, top, bottom, alpha):
        return linear(0, top, 0, bottom, (0, colour, alpha), (1, colour, 0))

    # Sky: a halo round the sun, soft clouds, a few glints.
    fill(circle(410, 240, 78), radial(410, 240, 78, (0, p["halo"], 0.55), (0.45, p["halo"], 0.2), (1, p["halo"], 0)))
    for cx, cy, w, h in ((600, 168, 150, 26), (1165, 150, 190, 30), (1505, 200, 118, 22)):
        fill(rounded_rect(cx - w / 2, cy - h / 2, w, h, h / 2), p["cloud"], 0.9)
        fill(circle(cx - w * 0.14, cy - h * 0.32, h * 0.62), p["cloud"], 0.9)
        fill(circle(cx + w * 0.12, cy - h * 0.18, h * 0.46), p["cloud"], 0.9)
    fill(circle(410, 240, 34), radial(398, 228, 42, (0, p["sun_core"], 1), (1, p["sun"], 1)))
    fill(circle(335, 208, 6), p["dot"])
    fill(circle(297, 256, 4), p["dot"])
    for cx, cy, r in ((560, 250, 9), (985, 232, 7), (1560, 238, 8), (1760, 214, 6)):
        fill(sparkle(cx, cy, r), p["sparkle"])

    # Three layers of hills, palest furthest away, each fading into the ground.
    fill("M0,400 C150,332 320,350 470,372 C640,398 760,332 900,318 C1060,304 1200,368 1350,358 "
         "C1500,348 1640,298 1800,318 L1800,560 L0,560 Z", fade_down(p["haze"], 300, 560, 0.7))
    fill("M0,452 C110,396 230,398 330,418 C430,438 520,420 620,470 C680,500 720,530 760,560 L0,560 Z",
         fade_down(p["hill"], 396, 560, 0.95))
    fill("M740,560 C850,470 975,340 1080,332 C1190,324 1290,392 1400,410 C1470,421 1525,470 1580,560 Z",
         fade_down(p["hill"], 330, 560, 0.8))

    # The path narrows into the distance and runs in behind the mountain.
    route = [(170, 540), (290, 480), (400, 428), (504, 352), (630, 362), (738, 405), (864, 398),
             (990, 360), (1080, 356), (1197, 388), (1287, 410), (1377, 404), (1485, 372), (1580, 336),
             (1650, 304)]
    along = (160, 1660)

    def along_x(*stops):
        return linear(along[0], 0, along[1], 0, *stops)

    fill(ribbon(route, 96, 40), along_x((0, p["river_glow"], 0), (0.14, p["river_glow"], 0.4), (1, p["river_glow"], 0.3)))
    fill(ribbon(route, 50, 18, dy=6), along_x((0, p["river_deep"], 0), (0.18, p["river_deep"], 0.28),
                                              (1, p["river_deep"], 0.2)))
    fill(ribbon(route, 50, 18), along_x((0, p["river_edge"], 0), (0.1, p["river_edge"], 1),
                                        (0.3, p["river"], 1), (0.92, p["river"], 1), (1, p["river_edge"], 1)))
    stroke(smooth([(x, y - 7) for x, y in route[3:13]]), along_x((0, p["card_top"], 0.35), (1, p["card_top"], 0.1)), 4)

    # The mountain covers the path's far end, then the shoulder in front of it.
    fill("M1250,560 C1360,500 1470,410 1560,340 C1600,308 1630,282 1660,264 Q1672,256 1684,263 "
         "C1720,285 1760,318 1800,345 L1800,560 Z",
         linear(1672, 256, 1610, 560, (0, p["mount_top"], 1), (0.55, p["mount_base"], 0.85), (1, p["mount_base"], 0)))
    fill("M1672,258 C1664,330 1646,420 1622,540 L1716,540 C1700,440 1686,340 1672,258 Z",
         linear(1672, 258, 1672, 540, (0, p["mount_face"], 0.28), (1, p["mount_face"], 0)))
    fill("M1440,570 C1540,500 1640,430 1720,405 C1755,395 1785,398 1800,402 L1800,570 Z",
         fade_down(p["hill_low"], 395, 570, 1))

    # Leaves: shaded from a dark base to a light tip.
    def leaves(*specs):
        for x, y, length, width, angle, dark in specs:
            blade, vein = leaf(x, y, length, width, angle)
            a = math.radians(angle)
            tip = (x + length * math.sin(a), y - length * math.cos(a))
            base_colour = p["leaf_dark"]
            tip_colour = p["leaf_dark"] if dark else p["leaf"]
            fill(blade, linear(x, y, tip[0], tip[1], (0, base_colour, 1), (1, tip_colour, 1)))
            stroke(vein, p["vein"], 1.6, 0.6)

    leaves((92, 457, 82, 34, -8, False), (97, 453, 48, 22, 40, True))
    leaves((772, 422, 52, 28, -80, False), (777, 424, 42, 24, -126, True))
    leaves((1296, 422, 48, 26, -78, False), (1301, 424, 40, 22, -124, True))
    leaves((1432, 326, 48, 26, 18, False), (1437, 330, 40, 22, 64, True))
    leaves((1442, 394, 42, 22, 52, True))

    # Cards: a wide, faint shadow in four layers, then a gently shaded card.
    def card(x, y, w, h):
        for grow, drop, alpha in ((10, 16, 0.02), (6, 11, 0.028), (2, 7, 0.035), (-2, 3, 0.04)):
            fill(rounded_rect(x - grow, y + drop - grow / 2, w + grow * 2, h + grow, 18 + grow), p["shadow"], alpha)
        fill(rounded_rect(x, y, w, h, 18), linear(0, y, 0, y + h, (0, p["card_top"], 1), (1, p["card"], 1)))
        stroke(rounded_rect(x, y, w, h, 18), p["card_line"], 1.2)

    # Step 1 — income: a wallet, and a coin with a rising mark. A plain mark
    # rather than a currency sign: the currency comes from the household's
    # region, never from the art.
    card(273, 312, 144, 115)
    fill(rounded_rect(313, 329, 52, 18, 7), p["icon_soft"])
    fill(rounded_rect(306, 340, 72, 54, 11), p["icon"])
    fill(rounded_rect(350, 357, 30, 22, 9), p["on_icon"])
    fill(circle(363, 368, 4.5), p["icon"])
    fill(circle(391, 424, 29), p["shadow"], 0.1)
    fill(circle(388, 419, 27), p["card_top"])
    stroke(circle(388, 419, 18), p["icon_soft"], 2.6)
    stroke("M388,428 L388,412", p["icon"], 3)
    stroke("M381,418 L388,411 L395,418", p["icon"], 3)

    # Step 2 — expenses: a receipt, with a clock for the monthly rhythm.
    card(787, 313, 146, 115)
    fill(rounded_rect(818, 336, 54, 66, 10), p["icon"])
    for y, w in ((353, 30), (367, 24), (381, 17)):
        stroke(f"M830,{y} L{830 + w},{y}", p["on_icon"], 4)
    fill(circle(879, 400, 22), p["card_top"])
    fill(circle(879, 400, 17), p["icon_soft"])
    stroke("M879,400 L879,390", p["on_icon"], 2.8)
    stroke("M879,400 L886,404", p["on_icon"], 2.8)

    # Step 3 — debts and investments: a rising chart, and the flag ahead.
    card(1313, 313, 125, 113)
    fill(rounded_rect(1340, 372, 15, 26, 5), p["icon_soft"])
    fill(rounded_rect(1361, 356, 15, 42, 5), p["icon_soft"])
    fill(rounded_rect(1382, 340, 15, 58, 5), p["icon"])
    stroke("M1338,356 L1356,344 L1370,350 L1398,328", p["icon"], 3)
    stroke("M1388,328 L1398,328 L1398,338", p["icon"], 3)
    stroke("M1672,210 L1672,258", p["icon"], 4)
    fill("M1674,211 C1688,205 1698,218 1713,212 L1706,223 L1713,234 C1698,240 1688,227 1674,233 Z", p["icon_soft"])

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
