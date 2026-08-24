#!/usr/bin/env python3
"""Rasterize the charter app-icon SVGs into the Windows executable/window ICO.

Source of truth:
  docs/design/icons/app-icon.svg     large frames (48/64/128/256)
  docs/design/icons/app-icon-16.svg  small frames (16/20/24/32), redrawn on the
                                     16 grid so the polyline survives shrinking

Output: windows/ClipSync.App/Assets/Icons/app.ico
  Frames <= 48 px are stored as classic 32-bit BGRA bitmaps (maximum shell
  compatibility); the 64/128/256 frames are PNG-compressed (supported since
  Vista, keeps the file small).

Android needs no raster output: the launcher icon ships as an adaptive-icon
vector (res/drawable/ic_launcher_*.xml), minSdk 29 > 26.

Requires: pip install cairosvg pillow
Usage:    python3 scripts/generate-app-icons.py
"""

from __future__ import annotations

import io
import struct
from pathlib import Path

import cairosvg
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parent.parent
SVG_DIR = REPO_ROOT / "docs" / "design" / "icons"
OUT_PATH = REPO_ROOT / "windows" / "ClipSync.App" / "Assets" / "Icons" / "app.ico"

SMALL_SIZES = (16, 20, 24, 32)   # from app-icon-16.svg
LARGE_SIZES = (48, 64, 128, 256)  # from app-icon.svg
BMP_MAX_SIZE = 48                 # larger frames are stored PNG-compressed


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


def png_frame(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def write_ico(path: Path, frames: list[Image.Image]) -> None:
    blobs = [
        bmp_frame(frame) if frame.size[0] <= BMP_MAX_SIZE else png_frame(frame)
        for frame in frames
    ]
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
    small_svg = (SVG_DIR / "app-icon-16.svg").read_text(encoding="utf-8")
    large_svg = (SVG_DIR / "app-icon.svg").read_text(encoding="utf-8")
    frames = [rasterize(small_svg, size) for size in SMALL_SIZES]
    frames += [rasterize(large_svg, size) for size in LARGE_SIZES]
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    write_ico(OUT_PATH, frames)
    print(f"{OUT_PATH.relative_to(REPO_ROOT)}  frames={SMALL_SIZES + LARGE_SIZES}")


if __name__ == "__main__":
    main()
