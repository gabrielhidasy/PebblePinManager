#!/usr/bin/env python3
"""Generate and validate Google Play Store listing assets & Fastlane metadata.

Outputs (under fastlane/metadata/android/en-US/images/):
  - icon.png            512x512 opaque RGB PNG (full-bleed Play Store icon,
                        matching android/app/src/main/res/drawable/ic_launcher_*
                        without the removed notification dot badge from #75)
  - featureGraphic.png  1024x500 opaque RGB PNG (Google Play feature banner)

Also validates all Fastlane Supply text metadata and Play Store image rules
using Python standard library only (`--validate-only`), so CI can enforce limits
without extra dependencies.
"""

from __future__ import annotations

import argparse
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_METADATA_DIR = REPO_ROOT / "fastlane" / "metadata" / "android" / "en-US"

TITLE_MAX_CHARS = 30
SHORT_DESC_MAX_CHARS = 80
FULL_DESC_MAX_CHARS = 4000
CHANGELOG_MAX_CHARS = 500

ICON_SIZE = (512, 512)
ICON_MAX_BYTES = 1024 * 1024  # 1 MiB Google Play limit
FEATURE_SIZE = (1024, 500)
FEATURE_MAX_BYTES = 15 * 1024 * 1024  # 15 MiB Google Play limit

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def build_icon_svg() -> str:
    """Build the 512x512 full-bleed Google Play store icon SVG.

    Mirrors android/app/src/main/res/drawable/ic_launcher_background.xml and
    ic_launcher_foreground.xml (with the teal notification badge dot removed per
    #75 / Google Play Metadata policy). Cropped to viewBox 14..94 (80x80) so the
    emblem fills the Google Play safe keyline cleanly without wasted margin.
    """
    return """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="14 14 80 80">
  <defs>
    <linearGradient id="bgGrad" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#273542"/>
      <stop offset="100%" stop-color="#141C24"/>
    </linearGradient>
    <radialGradient id="coralGlow" cx="67" cy="54" r="48" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#FF4700" stop-opacity="0.18"/>
      <stop offset="100%" stop-color="#FF4700" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="watchBodyGrad" x1="47.5" y1="37" x2="79.5" y2="71" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#FF6624"/>
      <stop offset="100%" stop-color="#E53A00"/>
    </linearGradient>
  </defs>

  <!-- Full-bleed background (Google Play applies its own rounded mask) -->
  <rect x="0" y="0" width="108" height="108" fill="url(#bgGrad)"/>
  <rect x="0" y="0" width="108" height="108" fill="url(#coralGlow)"/>

  <!-- Smartphone (back-left) with cutout halo separating it cleanly from the watch -->
  <path fill="#DCE6F0" d="M30,34 L45,34 A4.5,4.5 0 0,1 48.83,36.13 A9,9 0 0,0 45,43.5 L45,64.5 A9,9 0 0,0 48.83,71.87 A4.5,4.5 0 0,1 45,74 L30,74 A4.5,4.5 0 0,1 25.5,69.5 L25.5,38.5 A4.5,4.5 0 0,1 30,34 Z"/>
  <!-- Smartphone dark screen -->
  <path fill="#1C2630" d="M30.5,39.2 L46.09,39.2 A9,9 0 0,0 45,43.5 L45,64.5 A9,9 0 0,0 46.09,68.8 L30.5,68.8 A1.8,1.8 0 0,1 28.7,67 L28.7,41 A1.8,1.8 0 0,1 30.5,39.2 Z"/>
  <!-- Smartphone speaker pill & bottom bar -->
  <path fill="#7D92A6" d="M35.2,36 h4.8 a0.7,0.7 0 0,1 0,1.4 h-4.8 a0.7,0.7 0 0,1 0,-1.4 Z"/>
  <path fill="#7D92A6" d="M34.9,70.8 h5.4 a0.7,0.7 0 0,1 0,1.4 h-5.4 a0.7,0.7 0 0,1 0,-1.4 Z"/>
  <!-- Smartphone timeline pin rows -->
  <path fill="#4FD1C5" d="M33,42.8 h7.9 a1.8,1.8 0 0,1 1.8,1.8 v1.6 a1.8,1.8 0 0,1 -1.8,1.8 h-7.9 a1.8,1.8 0 0,1 -1.8,-1.8 v-1.6 a1.8,1.8 0 0,1 1.8,-1.8 Z"/>
  <path fill="#7C94AA" d="M32.7,50.8 h7.2 a1.5,1.5 0 0,1 0,3 h-7.2 a1.5,1.5 0 0,1 0,-3 Z"/>
  <path fill="#52687C" d="M32.7,56.4 h5 a1.5,1.5 0 0,1 0,3 h-5 a1.5,1.5 0 0,1 0,-3 Z"/>

  <!-- Pebble watch straps (top & bottom) with subtle lug shading -->
  <path fill="#5A6F82" d="M58,26.5 h11 c1.8,0 3,1.2 3,3 v7.5 h-17 v-7.5 c0,-1.8 1.2,-3 3,-3 Z"/>
  <path fill="#384958" d="M55,34.2 h17 v2.8 h-17 Z"/>
  <path fill="#5A6F82" d="M55,71 h17 v7.5 c0,1.8 -1.2,3 -3,3 h-11 c-1.8,0 -3,-1.2 -3,-3 Z"/>
  <path fill="#384958" d="M55,71 h17 v2.8 h-17 Z"/>

  <!-- Iconic Pebble 3 right-edge buttons (Up, Select, Down) -->
  <path fill="#DCE6F0" d="M78.8,42.8 h2.3 a1.4,1.4 0 0,1 1.4,1.4 v2 a1.4,1.4 0 0,1 -1.4,1.4 h-2.3 Z"/>
  <path fill="#DCE6F0" d="M78.8,51.1 h2.6 a1.4,1.4 0 0,1 1.4,1.4 v3 a1.4,1.4 0 0,1 -1.4,1.4 h-2.6 Z"/>
  <path fill="#DCE6F0" d="M78.8,60.4 h2.3 a1.4,1.4 0 0,1 1.4,1.4 v2 a1.4,1.4 0 0,1 -1.4,1.4 h-2.3 Z"/>

  <!-- Pebble smartwatch body (Pebble Coral gradient) -->
  <path fill="url(#watchBodyGrad)" d="M54.3,37 h18.4 a6.8,6.8 0 0,1 6.8,6.8 v20.4 a6.8,6.8 0 0,1 -6.8,6.8 h-18.4 a6.8,6.8 0 0,1 -6.8,-6.8 v-20.4 a6.8,6.8 0 0,1 6.8,-6.8 Z"/>

  <!-- Subtle inner display rim -->
  <path fill="none" stroke="#FFFFFF" stroke-opacity="0.24" stroke-width="1.2" d="M54.8,39.8 h17.4 a4.5,4.5 0 0,1 4.5,4.5 v19.4 a4.5,4.5 0 0,1 -4.5,4.5 h-17.4 a4.5,4.5 0 0,1 -4.5,-4.5 v-19.4 a4.5,4.5 0 0,1 4.5,-4.5 Z"/>

  <!-- Crisp white notification bell (no misleading notification dot badge) -->
  <path fill="#FFFFFF" d="M63.5,44.6 c-0.95,0 -1.65,0.72 -1.65,1.65 v0.58 c-3.1,0.72 -5.35,3.45 -5.35,6.82 v3.75 l-1.75,1.75 v1.05 h17.5 v-1.05 l-1.75,-1.75 v-3.75 c0,-3.37 -2.25,-6.1 -5.35,-6.82 v-0.58 c0,-0.93 -0.7,-1.65 -1.65,-1.65 Z"/>
  <path fill="#FFFFFF" d="M60.85,61.3 c0,1.46 1.19,2.65 2.65,2.65 s2.65,-1.19 2.65,-2.65 h-5.3 Z"/>
</svg>
"""


def build_feature_graphic_svg() -> str:
    """Build the 1024x500 Google Play Store feature graphic SVG.

    Keeps all primary artwork and typography well inside Google Play's safe zone,
    uses opaque backgrounds, and avoids promotional badges or misleading UI cues.
    """
    return """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <defs>
    <linearGradient id="canvasBg" x1="0" y1="0" x2="1024" y2="500" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#22303D"/>
      <stop offset="55%" stop-color="#17212B"/>
      <stop offset="100%" stop-color="#10171F"/>
    </linearGradient>
    <radialGradient id="heroCoralGlow" cx="230" cy="250" r="270" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#FF4700" stop-opacity="0.22"/>
      <stop offset="65%" stop-color="#FF4700" stop-opacity="0.06"/>
      <stop offset="100%" stop-color="#FF4700" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="accentTealGlow" cx="840" cy="120" r="260" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#4FD1C5" stop-opacity="0.14"/>
      <stop offset="100%" stop-color="#4FD1C5" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="tileBg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#2A3947"/>
      <stop offset="100%" stop-color="#141C24"/>
    </linearGradient>
    <linearGradient id="accentRule" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#FF5A1F"/>
      <stop offset="65%" stop-color="#FF4700"/>
      <stop offset="100%" stop-color="#4FD1C5"/>
    </linearGradient>
    <linearGradient id="watchGrad" x1="47.5" y1="37" x2="79.5" y2="71" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="#FF6624"/>
      <stop offset="100%" stop-color="#E53A00"/>
    </linearGradient>
    <filter id="tileShadow" x="-20%" y="-20%" width="140%" height="145%">
      <feDropShadow dx="0" dy="12" stdDeviation="18" flood-color="#060A0E" flood-opacity="0.45"/>
    </filter>
    <pattern id="dotGrid" width="28" height="28" patternUnits="userSpaceOnUse">
      <circle cx="2" cy="2" r="1.2" fill="#94A3B8" fill-opacity="0.12"/>
    </pattern>
  </defs>

  <!-- Opaque deep slate backdrop -->
  <rect x="0" y="0" width="1024" height="500" fill="url(#canvasBg)"/>
  <rect x="520" y="0" width="504" height="500" fill="url(#dotGrid)"/>
  <rect x="0" y="0" width="1024" height="500" fill="url(#heroCoralGlow)"/>
  <rect x="0" y="0" width="1024" height="500" fill="url(#accentTealGlow)"/>

  <!-- Subtle abstract timeline track lines on right background -->
  <line x1="934" y1="48" x2="934" y2="452" stroke="#334155" stroke-width="2" stroke-dasharray="5 7" stroke-opacity="0.45"/>
  <circle cx="934" cy="118" r="6" fill="#FF5A1F" fill-opacity="0.45"/>
  <circle cx="934" cy="248" r="6" fill="#4FD1C5" fill-opacity="0.45"/>
  <circle cx="934" cy="378" r="6" fill="#64748B" fill-opacity="0.45"/>

  <!-- Left Hero App Emblem Tile (centered vertically inside safe zone) -->
  <g filter="url(#tileShadow)">
    <rect x="92" y="114" width="272" height="272" rx="60" fill="url(#tileBg)"/>
    <rect x="92.75" y="114.75" width="270.5" height="270.5" rx="59.25" fill="none" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1.5"/>
  </g>

  <!-- Exact Phone + Pebble Watch vector artwork (no notification dot badge) -->
  <g transform="translate(92, 114) scale(3.4) translate(-14, -14)">
    <path fill="#DCE6F0" d="M30,34 L45,34 A4.5,4.5 0 0,1 48.83,36.13 A9,9 0 0,0 45,43.5 L45,64.5 A9,9 0 0,0 48.83,71.87 A4.5,4.5 0 0,1 45,74 L30,74 A4.5,4.5 0 0,1 25.5,69.5 L25.5,38.5 A4.5,4.5 0 0,1 30,34 Z"/>
    <path fill="#1C2630" d="M30.5,39.2 L46.09,39.2 A9,9 0 0,0 45,43.5 L45,64.5 A9,9 0 0,0 46.09,68.8 L30.5,68.8 A1.8,1.8 0 0,1 28.7,67 L28.7,41 A1.8,1.8 0 0,1 30.5,39.2 Z"/>
    <path fill="#7D92A6" d="M35.2,36 h4.8 a0.7,0.7 0 0,1 0,1.4 h-4.8 a0.7,0.7 0 0,1 0,-1.4 Z"/>
    <path fill="#7D92A6" d="M34.9,70.8 h5.4 a0.7,0.7 0 0,1 0,1.4 h-5.4 a0.7,0.7 0 0,1 0,-1.4 Z"/>
    <path fill="#4FD1C5" d="M33,42.8 h7.9 a1.8,1.8 0 0,1 1.8,1.8 v1.6 a1.8,1.8 0 0,1 -1.8,1.8 h-7.9 a1.8,1.8 0 0,1 -1.8,-1.8 v-1.6 a1.8,1.8 0 0,1 1.8,-1.8 Z"/>
    <path fill="#7C94AA" d="M32.7,50.8 h7.2 a1.5,1.5 0 0,1 0,3 h-7.2 a1.5,1.5 0 0,1 0,-3 Z"/>
    <path fill="#52687C" d="M32.7,56.4 h5 a1.5,1.5 0 0,1 0,3 h-5 a1.5,1.5 0 0,1 0,-3 Z"/>
    <path fill="#5A6F82" d="M58,26.5 h11 c1.8,0 3,1.2 3,3 v7.5 h-17 v-7.5 c0,-1.8 1.2,-3 3,-3 Z"/>
    <path fill="#384958" d="M55,34.2 h17 v2.8 h-17 Z"/>
    <path fill="#5A6F82" d="M55,71 h17 v7.5 c0,1.8 -1.2,3 -3,3 h-11 c-1.8,0 -3,-1.2 -3,-3 Z"/>
    <path fill="#384958" d="M55,71 h17 v2.8 h-17 Z"/>
    <path fill="#DCE6F0" d="M78.8,42.8 h2.3 a1.4,1.4 0 0,1 1.4,1.4 v2 a1.4,1.4 0 0,1 -1.4,1.4 h-2.3 Z"/>
    <path fill="#DCE6F0" d="M78.8,51.1 h2.6 a1.4,1.4 0 0,1 1.4,1.4 v3 a1.4,1.4 0 0,1 -1.4,1.4 h-2.6 Z"/>
    <path fill="#DCE6F0" d="M78.8,60.4 h2.3 a1.4,1.4 0 0,1 1.4,1.4 v2 a1.4,1.4 0 0,1 -1.4,1.4 h-2.3 Z"/>
    <path fill="url(#watchGrad)" d="M54.3,37 h18.4 a6.8,6.8 0 0,1 6.8,6.8 v20.4 a6.8,6.8 0 0,1 -6.8,6.8 h-18.4 a6.8,6.8 0 0,1 -6.8,-6.8 v-20.4 a6.8,6.8 0 0,1 6.8,-6.8 Z"/>
    <path fill="none" stroke="#FFFFFF" stroke-opacity="0.24" stroke-width="1.2" d="M54.8,39.8 h17.4 a4.5,4.5 0 0,1 4.5,4.5 v19.4 a4.5,4.5 0 0,1 -4.5,4.5 h-17.4 a4.5,4.5 0 0,1 -4.5,-4.5 v-19.4 a4.5,4.5 0 0,1 4.5,-4.5 Z"/>
    <path fill="#FFFFFF" d="M63.5,44.6 c-0.95,0 -1.65,0.72 -1.65,1.65 v0.58 c-3.1,0.72 -5.35,3.45 -5.35,6.82 v3.75 l-1.75,1.75 v1.05 h17.5 v-1.05 l-1.75,-1.75 v-3.75 c0,-3.37 -2.25,-6.1 -5.35,-6.82 v-0.58 c0,-0.93 -0.7,-1.65 -1.65,-1.65 Z"/>
    <path fill="#FFFFFF" d="M60.85,61.3 c0,1.46 1.19,2.65 2.65,2.65 s2.65,-1.19 2.65,-2.65 h-5.3 Z"/>
  </g>

  <!-- Right Copy Block (inside safe zone x=412..908) -->
  <text x="412" y="168" font-family="Roboto, Liberation Sans, sans-serif" font-size="16" font-weight="600" letter-spacing="2.2" fill="#4FD1C5">LOCAL PEBBLEKIT 2 COMPANION</text>

  <text x="412" y="232" font-family="Roboto, Liberation Sans, sans-serif" font-size="48" font-weight="700" fill="#F8FAFC">Pin Manager</text>
  <text x="412" y="286" font-family="Roboto, Liberation Sans, sans-serif" font-size="48" font-weight="700" fill="#FF6624">for Pebble</text>

  <rect x="412" y="308" width="148" height="4" rx="2" fill="url(#accentRule)"/>

  <text x="412" y="346" font-family="Roboto, Liberation Sans, sans-serif" font-size="21" font-weight="400" fill="#CBD5E1">Custom Timeline pins &amp; on-device Auto-Pins</text>

  <!-- Feature capability chips -->
  <g transform="translate(412, 374)">
    <rect x="0" y="0" width="156" height="36" rx="18" fill="#1E2B37" stroke="#334658" stroke-width="1.2"/>
    <circle cx="18" cy="18" r="4.5" fill="#FF5A1F"/>
    <text x="31" y="23" font-family="Roboto, Liberation Sans, sans-serif" font-size="14" font-weight="500" fill="#E2E8F0">Pebble Timeline</text>

    <rect x="168" y="0" width="142" height="36" rx="18" fill="#1E2B37" stroke="#334658" stroke-width="1.2"/>
    <circle cx="186" cy="18" r="4.5" fill="#4FD1C5"/>
    <text x="199" y="23" font-family="Roboto, Liberation Sans, sans-serif" font-size="14" font-weight="500" fill="#E2E8F0">100% Offline</text>

    <rect x="322" y="0" width="134" height="36" rx="18" fill="#1E2B37" stroke="#334658" stroke-width="1.2"/>
    <circle cx="340" cy="18" r="4.5" fill="#94A3B8"/>
    <text x="353" y="23" font-family="Roboto, Liberation Sans, sans-serif" font-size="14" font-weight="500" fill="#E2E8F0">Open Source</text>
  </g>
</svg>
"""


def read_png_header(path: Path) -> tuple[int, int, int, int]:
    """Read (width, height, bit_depth, color_type) from a PNG IHDR chunk using stdlib only."""
    data = path.read_bytes()
    if len(data) < 26 or data[:8] != PNG_SIGNATURE:
        raise ValueError(f"{path} is not a valid PNG file")
    chunk_len, chunk_type = struct.unpack(">I4s", data[8:16])
    if chunk_type != b"IHDR" or chunk_len != 13:
        raise ValueError(f"{path} has unexpected PNG header chunk: {chunk_type!r} (len={chunk_len})")
    width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    return width, height, bit_depth, color_type


def render_svg_to_rgb_png(svg_text: str, width: int, height: int, output_path: Path) -> None:
    """Rasterize SVG via rsvg-convert and ensure a clean opaque 24-bit RGB PNG."""
    rsvg_bin = shutil.which("rsvg-convert")
    if not rsvg_bin:
        raise RuntimeError("rsvg-convert is required to render Play Store SVG assets (install librsvg)")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="play-assets-") as tmp:
        tmp_dir = Path(tmp)
        svg_file = tmp_dir / "asset.svg"
        raw_png = tmp_dir / "raw.png"
        svg_file.write_text(svg_text, encoding="utf-8")

        subprocess.run(
            [
                rsvg_bin,
                "--width",
                str(width),
                "--height",
                str(height),
                "--output",
                str(raw_png),
                str(svg_file),
            ],
            check=True,
        )

        # Convert to opaque 24-bit RGB PNG (color_type=2) so Google Play accepts
        # both icon.png and featureGraphic.png without alpha-channel complaints.
        magick_bin = shutil.which("magick") or shutil.which("convert")
        if magick_bin:
            cmd = [
                magick_bin,
                str(raw_png),
                "-background",
                "#141C24",
                "-alpha",
                "remove",
                "-alpha",
                "off",
                "-strip",
                f"PNG24:{output_path}",
            ]
            subprocess.run(cmd, check=True)
        else:
            from PIL import Image

            with Image.open(raw_png) as img:
                bg = Image.new("RGB", img.size, (0x14, 0x1C, 0x24))
                if img.mode == "RGBA":
                    bg.paste(img, mask=img.split()[3])
                else:
                    bg.paste(img)
                bg.save(output_path, format="PNG", optimize=True)


def validate_metadata(metadata_dir: Path) -> list[str]:
    """Validate Fastlane / Google Play Store text and image constraints."""
    errors: list[str] = []

    title_path = metadata_dir / "title.txt"
    short_path = metadata_dir / "short_description.txt"
    full_path = metadata_dir / "full_description.txt"
    changelog_path = metadata_dir / "changelogs" / "default.txt"

    if not title_path.is_file():
        errors.append(f"Missing {title_path}")
    else:
        title = title_path.read_text(encoding="utf-8").strip()
        if not title:
            errors.append("title.txt is empty")
        elif len(title) > TITLE_MAX_CHARS:
            errors.append(f"title.txt exceeds {TITLE_MAX_CHARS} chars ({len(title)})")
        if title.lower().startswith("pebble"):
            errors.append("title.txt must not start with the 'Pebble' trademark")

    if not short_path.is_file():
        errors.append(f"Missing {short_path}")
    else:
        short_desc = short_path.read_text(encoding="utf-8").strip()
        if not short_desc:
            errors.append("short_description.txt is empty")
        elif len(short_desc) > SHORT_DESC_MAX_CHARS:
            errors.append(
                f"short_description.txt exceeds {SHORT_DESC_MAX_CHARS} chars ({len(short_desc)})"
            )
        if "\n" in short_desc:
            errors.append("short_description.txt must be a single line")

    if not full_path.is_file():
        errors.append(f"Missing {full_path}")
    else:
        full_desc = full_path.read_text(encoding="utf-8").strip()
        if not full_desc:
            errors.append("full_description.txt is empty")
        elif len(full_desc) > FULL_DESC_MAX_CHARS:
            errors.append(
                f"full_description.txt exceeds {FULL_DESC_MAX_CHARS} chars ({len(full_desc)})"
            )
        lower_full = full_desc.lower()
        required_snippets = [
            ("not affiliated", "independent / non-affiliation disclaimer"),
            ("core devices", "Core Devices non-affiliation reference"),
            ("display over other apps", "transparent SYSTEM_ALERT_WINDOW disclosure"),
            ("notification access", "transparent Notification Access disclosure"),
            ("internet", "zero INTERNET permission privacy note"),
        ]
        for needle, label in required_snippets:
            if needle not in lower_full:
                errors.append(f"full_description.txt missing {label} ({needle!r})")

    if changelog_path.is_file():
        changelog = changelog_path.read_text(encoding="utf-8").strip()
        if len(changelog) > CHANGELOG_MAX_CHARS:
            errors.append(
                f"changelogs/default.txt exceeds {CHANGELOG_MAX_CHARS} chars ({len(changelog)})"
            )

    images_dir = metadata_dir / "images"
    icon_path = images_dir / "icon.png"
    feature_path = images_dir / "featureGraphic.png"

    if not icon_path.is_file():
        errors.append(f"Missing {icon_path}")
    else:
        try:
            w, h, bit_depth, color_type = read_png_header(icon_path)
            if (w, h) != ICON_SIZE:
                errors.append(f"icon.png dimensions are {w}x{h}, expected {ICON_SIZE[0]}x{ICON_SIZE[1]}")
            if bit_depth != 8 or color_type not in (2, 6):
                errors.append(f"icon.png must be 8-bit RGB/RGBA PNG (got depth={bit_depth}, type={color_type})")
            if icon_path.stat().st_size > ICON_MAX_BYTES:
                errors.append(f"icon.png exceeds {ICON_MAX_BYTES} bytes")
        except ValueError as exc:
            errors.append(str(exc))

    if not feature_path.is_file():
        errors.append(f"Missing {feature_path}")
    else:
        try:
            w, h, bit_depth, color_type = read_png_header(feature_path)
            if (w, h) != FEATURE_SIZE:
                errors.append(
                    f"featureGraphic.png dimensions are {w}x{h}, expected {FEATURE_SIZE[0]}x{FEATURE_SIZE[1]}"
                )
            if bit_depth != 8 or color_type != 2:
                errors.append(
                    f"featureGraphic.png must be opaque 24-bit RGB PNG (got depth={bit_depth}, type={color_type})"
                )
            if feature_path.stat().st_size > FEATURE_MAX_BYTES:
                errors.append(f"featureGraphic.png exceeds {FEATURE_MAX_BYTES} bytes")
        except ValueError as exc:
            errors.append(str(exc))

    return errors


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate and validate Google Play Store listing graphics & Fastlane metadata.",
    )
    parser.add_argument(
        "--metadata-dir",
        type=Path,
        default=DEFAULT_METADATA_DIR,
        help="Path to Fastlane Android locale metadata directory (default: fastlane/metadata/android/en-US)",
    )
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Skip rendering images and only validate existing metadata & PNG constraints.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    metadata_dir: Path = args.metadata_dir

    if not args.validate_only:
        images_dir = metadata_dir / "images"
        render_svg_to_rgb_png(build_icon_svg(), ICON_SIZE[0], ICON_SIZE[1], images_dir / "icon.png")
        render_svg_to_rgb_png(
            build_feature_graphic_svg(),
            FEATURE_SIZE[0],
            FEATURE_SIZE[1],
            images_dir / "featureGraphic.png",
        )
        print(f"Generated {images_dir / 'icon.png'} ({ICON_SIZE[0]}x{ICON_SIZE[1]})")
        print(f"Generated {images_dir / 'featureGraphic.png'} ({FEATURE_SIZE[0]}x{FEATURE_SIZE[1]})")

    errors = validate_metadata(metadata_dir)
    if errors:
        for err in errors:
            print(f"ERROR: {err}", file=sys.stderr)
        return 1

    title = (metadata_dir / "title.txt").read_text(encoding="utf-8").strip()
    short_desc = (metadata_dir / "short_description.txt").read_text(encoding="utf-8").strip()
    full_desc = (metadata_dir / "full_description.txt").read_text(encoding="utf-8").strip()
    print(
        f"Play Store metadata OK: title={len(title)}/{TITLE_MAX_CHARS}, "
        f"short={len(short_desc)}/{SHORT_DESC_MAX_CHARS}, "
        f"full={len(full_desc)}/{FULL_DESC_MAX_CHARS}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
