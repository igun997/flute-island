#!/usr/bin/env bash
# Flute Island art pipeline.
#   tools/make_art.sh icon   Derive design/assets/icon*.svg and preview PNGs from
#                            the launcher VectorDrawable XML.
#
# The previews are built from the same XML the app ships: the background gradient
# is read from its <aapt:attr> stops and drawn with Pillow (ImageMagick's internal
# SVG renderer cannot draw gradients), the solid foreground/monochrome layers are
# rasterised with ImageMagick, and the layers are composited like the launcher does.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

icon() {
  mkdir -p design/assets
  python3 - <<'PY'
import math, pathlib, subprocess, tempfile, xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

A = "{http://schemas.android.com/aapt}"
N = "{http://schemas.android.com/apk/res/android}"
DRAW = pathlib.Path("app/src/main/res/drawable")
ASSETS = pathlib.Path("design/assets")
SIZE = 512                       # master raster; smaller previews are downscaled from it
BACKDROP = (244, 241, 234, 255)  # warm white #F4F1EA, behind the masked previews


def colour(c):
    """#AARRGGBB -> (#RRGGBB, opacity)."""
    a, r, g, b = (int(c[i:i + 2], 16) for i in (1, 3, 5, 7))
    return f"#{r:02X}{g:02X}{b:02X}", a / 255


def rgba(c):
    col, op = colour(c)
    return tuple(int(col[i:i + 2], 16) for i in (1, 3, 5)) + (round(op * 255),)


def load(name):
    return ET.parse(DRAW / f"{name}.xml").getroot()


def svg_paths(root):
    """Solid-fill <path> elements as SVG markup (gradients are handled separately)."""
    out = []
    for p in root.findall("path"):
        if p.find(f"{A}attr") is not None:
            continue
        col, op = colour(p.get(N + "fillColor"))
        rule = ' fill-rule="evenodd"' if p.get(N + "fillType") == "evenOdd" else ""
        out.append(f'<path d="{p.get(N + "pathData")}" fill="{col}" fill-opacity="{op}"{rule}/>')
    return out


def svg_doc(body, size):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 108 108">{"".join(body)}</svg>\n')


def rasterise(body, size):
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg_doc(body, size))
    try:
        out = subprocess.run(["convert", "-background", "none", f.name, "PNG32:-"],
                             check=True, capture_output=True).stdout
    finally:
        pathlib.Path(f.name).unlink()
    import io
    return Image.open(io.BytesIO(out)).convert("RGBA")


def gradient_layer(root, size):
    """Render the vertical linear gradient of a layer's <aapt:attr> fill."""
    grad = root.find(f"path/{A}attr/gradient")
    stops = [(float(i.get(N + "offset")), rgba(i.get(N + "color"))) for i in grad.findall("item")]
    x0, y0 = float(grad.get(N + "startX")), float(grad.get(N + "startY"))
    x1, y1 = float(grad.get(N + "endX")), float(grad.get(N + "endY"))
    assert x0 == x1, "only vertical gradients are supported"
    img = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(img)
    for row in range(size):
        v = (row + 0.5) * 108 / size
        t = min(max((v - y0) / (y1 - y0), 0.0), 1.0)
        for (o0, c0), (o1, c1) in zip(stops, stops[1:]):
            if o0 <= t <= o1:
                k = (t - o0) / (o1 - o0)
                col = tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
                break
        else:
            col = stops[-1][1] if t > stops[-1][0] else stops[0][1]
        draw.line([(0, row), (size - 1, row)], fill=col)
    return img


def superellipse_mask(size, n):
    """Anti-aliased mask |x|^n + |y|^n <= 1 spanning the full icon box."""
    s = 4
    m = Image.new("L", (size * s, size * s), 0)
    c, r = size * s / 2, size * s / 2
    pts = []
    for k in range(720):
        t = 2 * math.pi * k / 720
        x = math.copysign(abs(math.cos(t)) ** (2 / n), math.cos(t))
        y = math.copysign(abs(math.sin(t)) ** (2 / n), math.sin(t))
        pts.append((c + r * x, c + r * y))
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    return superellipse_mask(size, 2)


def on_backdrop(img, mask=None):
    if mask is not None:
        img = img.copy()
        img.putalpha(mask)
    base = Image.new("RGBA", img.size, BACKDROP)
    base.alpha_composite(img)
    return base


def safezone_guide(img):
    """Dashed ring at the 66dp safe-zone circle (radius 33 of 108)."""
    out = img.copy()
    d = ImageDraw.Draw(out)
    s = img.size[0] / 108
    c, r = 54 * s, 33 * s
    for k in range(0, 360, 12):
        d.arc([c - r, c - r, c + r, c + r], k, k + 6, fill=(255, 255, 255, 230), width=2)
    return out


bg_root = load("ic_launcher_background")
fg_root = load("ic_launcher_foreground")
mono_root = load("ic_launcher_monochrome")

# Design source: the full-colour SVG as the adaptive icon composes it.
defs = []
grad = bg_root.find(f"path/{A}attr/gradient")
stops = "".join(
    f'<stop offset="{i.get(N + "offset")}" stop-color="{colour(i.get(N + "color"))[0]}" '
    f'stop-opacity="{colour(i.get(N + "color"))[1]}"/>' for i in grad.findall("item"))
defs.append(f'<linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="{grad.get(N + "startX")}" '
            f'y1="{grad.get(N + "startY")}" x2="{grad.get(N + "endX")}" y2="{grad.get(N + "endY")}">{stops}</linearGradient>')
design = [f'<defs>{"".join(defs)}</defs>', '<path d="M0,0H108V108H0Z" fill="url(#bg)"/>']
design += svg_paths(fg_root)
(ASSETS / "icon.svg").write_text(svg_doc(design, 512))
(ASSETS / "icon_monochrome.svg").write_text(svg_doc(svg_paths(mono_root), 512))

# Raster composite: gradient background + foreground, exactly the adaptive-icon stack.
bg = gradient_layer(bg_root, SIZE)
fg = rasterise(svg_paths(fg_root), SIZE)
full = bg.copy()
full.alpha_composite(fg)
full.save(ASSETS / "icon_preview_512.png")

mono = rasterise(svg_paths(mono_root), SIZE)
on_backdrop(mono).resize((192, 192), Image.LANCZOS).save(ASSETS / "icon_preview_monochrome.png")

full.resize((192, 192), Image.LANCZOS).save(ASSETS / "icon_preview_192.png")
full.resize((48, 48), Image.LANCZOS).save(ASSETS / "icon_preview_48.png")
on_backdrop(full, circle_mask(SIZE)).resize((192, 192), Image.LANCZOS).save(ASSETS / "icon_preview_circle.png")
on_backdrop(full, superellipse_mask(SIZE, 4)).resize((192, 192), Image.LANCZOS).save(ASSETS / "icon_preview_squircle.png")
safezone_guide(on_backdrop(full)).resize((512, 512), Image.LANCZOS).save(ASSETS / "icon_preview_safezone.png")
print("icon previews written to", ASSETS)
PY
}

art() {
  python3 - <<'PY'
import io, pathlib
from PIL import Image, ImageStat

ORIG = pathlib.Path("design/assets/originals")
RES = pathlib.Path("app/src/main/res/drawable-nodpi")
# (original PNG, resource name, width, height, byte budget, background target or None)
# A background target shifts the image so its corners land exactly on a brand colour.
JOBS = [
    ("hero_dark", "hero_dark", 1080, 600, 90_000, None),
    ("hero_light", "hero_light", 1080, 600, 90_000, None),
    ("empty_tools_dark", "empty_tools", 600, 600, 60_000, (0x0B, 0x1F, 0x2A)),
    ("empty_tools_light", "empty_tools_light", 600, 600, 60_000, (0xF4, 0xF1, 0xEA)),
]


def cover(img, w, h):
    """Centre-crop to the target aspect ratio, then resize with Lanczos."""
    iw, ih = img.size
    if iw * h > ih * w:
        nw = round(ih * w / h)
        x = (iw - nw) // 2
        box = (x, 0, x + nw, ih)
    else:
        nh = round(iw * h / w)
        y = (ih - nh) // 2
        box = (0, y, iw, y + nh)
    return img.crop(box).resize((w, h), Image.LANCZOS)


RES.mkdir(parents=True, exist_ok=True)
for src, name, w, h, budget, target in JOBS:
    out = cover(Image.open(ORIG / f"{src}.png").convert("RGB"), w, h)
    if target is not None:
        corner = ImageStat.Stat(out.crop((0, 0, 24, 24))).mean
        shift = [round(t - c) for t, c in zip(target, corner)]
        out = Image.merge("RGB", [ch.point(lambda v, d=d: min(255, max(0, v + d)))
                                  for ch, d in zip(out.split(), shift)])
    for q in range(78, 40, -4):
        buf = io.BytesIO()
        out.save(buf, "WEBP", quality=q, method=6)
        if len(buf.getvalue()) <= budget:
            break
    size = len(buf.getvalue())
    if size > budget:
        raise SystemExit(f"{name}: {size} bytes exceeds budget {budget}")
    (RES / f"{name}.webp").write_bytes(buf.getvalue())
    print(f"{name}.webp {w}x{h} q{q} {size} bytes")
PY
}

case "${1:-}" in
  icon) icon ;;
  art) art ;;
  *) echo "usage: tools/make_art.sh icon|art" >&2; exit 2 ;;
esac
