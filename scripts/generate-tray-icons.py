#!/usr/bin/env python3
"""Rasterize the charter tray SVGs into notification-area assets for Windows.

Source of truth: docs/design/icons/tray-*.svg (16-grid, single polyline mark).
Rules from docs/design/icons.md:

  * Every ICO carries 16/20/24/32 frames, each rasterized from the vector
    geometry at its native size - never downscaled from a larger raster.
  * ``currentColor`` resolves to the ``t1`` text token per taskbar theme:
    day ``#1c2733`` (light taskbar) / night ``#e3e9f0`` (dark taskbar).
  * The attention dot is the only colored pixel. The SVG bakes the compromise
    value ``#C08A3E``; the per-theme sets replace it with the ``act`` token:
    day ``#9b6b24`` / night ``#d9a15c``.

Outputs to windows/ClipSync.App/Assets/Icons/:
  tray-<state>-<theme>.ico          multi-resolution ICO (16/20/24/32)
  tray-<state>-<theme>-{16,32}.png  loose PNG exports

ICO frames are stored as classic 32-bit BGRA bitmaps (not PNG-compressed)
so System.Drawing.Icon picks individual frames reliably.

Requires: pip install cairosvg pillow
Usage:    python3 scripts/generate-tray-icons.py
"""

from __future__ import annotations

import io
import struct
from pathlib import Path

import cairosvg
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parent.parent
SVG_DIR = REPO_ROOT / "docs" / "design" / "icons"
OUT_DIR = REPO_ROOT / "windows" / "ClipSync.App" / "Assets" / "Icons"

STATES = ("flow", "attention", "paused", "private")

# Theme suffix -> (t1 stroke, act dot). The suffix names the taskbar the set
# serves: "day" = light taskbar (dark strokes), "night" = dark taskbar.
THEMES = {
    "day": ("#1c2733", "#9b6b24"),
    "night": ("#e3e9f0", "#d9a15c"),
}

SVG_DOT_COMPROMISE = "#C08A3E"
ICO_SIZES = (16, 20, 24, 32)
PNG_SIZES = (16, 32)


def rasterize(svg_text: str, size: int) -> Image.Image:
    png_bytes = cairosvg.svg2png(
        bytestring=svg_text.encode("utf-8"),
        output_width=size,
        output_height=size,
    )
    image = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    if not image.getbbox():
        raise ValueError(f"rasterized {size}px frame is fully transparent")
    return image


def bmp_frame(image: Image.Image) -> bytes:
    """Encode one icon frame: BITMAPINFOHEADER + bottom-up BGRA + AND mask."""
    width, height = image.size
    mask_stride = ((width + 31) // 32) * 4
    header = struct.pack(
        "<IiiHHIIiiII",
        40,                # biSize
        width,
        height * 2,        # XOR + AND blocks share the height field
        1,                 # biPlanes
        32,                # biBitCount
        0,                 # BI_RGB
        width * height * 4 + mask_stride * height,
        0, 0, 0, 0,
    )
    rgba = image.tobytes()
    stride = width * 4
    rows = []
    for y in reversed(range(height)):
        row = bytearray(rgba[y * stride : (y + 1) * stride])
        row[0::4], row[2::4] = row[2::4], row[0::4]  # RGBA -> BGRA
        rows.append(bytes(row))
    and_mask = b"\x00" * (mask_stride * height)  # alpha channel does the masking
    return header + b"".join(rows) + and_mask


def write_ico(path: Path, frames: list[Image.Image]) -> None:
    blobs = [bmp_frame(frame) for frame in frames]
    offset = 6 + 16 * len(frames)
    directory = []
    for frame, blob in zip(frames, blobs):
        width, height = frame.size
        directory.append(
            struct.pack(
                "<BBBBHHII",
                width % 256, height % 256, 0, 0, 1, 32, len(blob), offset,
            )
        )
        offset += len(blob)
    path.write_bytes(
        struct.pack("<HHH", 0, 1, len(frames)) + b"".join(directory) + b"".join(blobs)
    )


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for state in STATES:
        svg_text = (SVG_DIR / f"tray-{state}.svg").read_text(encoding="utf-8")
        for theme, (stroke, dot) in THEMES.items():
            themed = svg_text.replace("currentColor", stroke).replace(
                SVG_DOT_COMPROMISE, dot
            )
            frames = [rasterize(themed, size) for size in ICO_SIZES]
            ico_path = OUT_DIR / f"tray-{state}-{theme}.ico"
            write_ico(ico_path, frames)
            for size in PNG_SIZES:
                png_path = OUT_DIR / f"tray-{state}-{theme}-{size}.png"
                frames[ICO_SIZES.index(size)].save(png_path, format="PNG")
            print(f"{ico_path.relative_to(REPO_ROOT)}  frames={ICO_SIZES}")


if __name__ == "__main__":
    main()
