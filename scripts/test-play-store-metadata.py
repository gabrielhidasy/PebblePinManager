#!/usr/bin/env python3
"""Offline stdlib-only unit tests for Google Play Store metadata, assets, docs & scripts."""

from __future__ import annotations

import importlib.util
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = REPO_ROOT / "scripts"
METADATA_DIR = REPO_ROOT / "fastlane" / "metadata" / "android" / "en-US"
DOCS_DIR = REPO_ROOT / "play-store"


def load_module(name: str, filename: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, SCRIPTS_DIR / filename)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


assets_mod = load_module("generate_play_assets", "generate-play-assets.py")
shots_mod = load_module("capture_play_screenshots", "capture-play-screenshots.py")


def write_fake_png(path: Path, width: int, height: int, bit_depth: int = 8, color_type: int = 2) -> None:
    """Write a minimal PNG file with a valid signature and IHDR chunk for header-validation tests."""
    ihdr_data = struct.pack(">IIBBBBB", width, height, bit_depth, color_type, 0, 0, 0)
    ihdr_chunk = struct.pack(">I4s", len(ihdr_data), b"IHDR") + ihdr_data + b"\x00\x00\x00\x00"
    iend_chunk = struct.pack(">I4s", 0, b"IEND") + b"\x00\x00\x00\x00"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(assets_mod.PNG_SIGNATURE + ihdr_chunk + iend_chunk)


class PlayStoreMetadataTests(unittest.TestCase):
    def test_repo_fastlane_metadata_and_images_are_valid(self) -> None:
        errors = assets_mod.validate_metadata(METADATA_DIR)
        self.assertEqual(errors, [], f"Play Store metadata validation failed: {errors}")

    def test_validate_only_cli_succeeds(self) -> None:
        proc = subprocess.run(
            [sys.executable, str(SCRIPTS_DIR / "generate-play-assets.py"), "--validate-only"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)
        self.assertIn("Play Store metadata OK", proc.stdout)

    def test_title_and_descriptions_match_issue_80_requirements(self) -> None:
        title = (METADATA_DIR / "title.txt").read_text(encoding="utf-8").strip()
        short_desc = (METADATA_DIR / "short_description.txt").read_text(encoding="utf-8").strip()
        full_desc = (METADATA_DIR / "full_description.txt").read_text(encoding="utf-8").strip()

        self.assertEqual(title, "Pin Manager for Pebble")
        self.assertLessEqual(len(title), 30)
        self.assertLessEqual(len(short_desc), 80)
        self.assertLessEqual(len(full_desc), 4000)
        self.assertIn("not affiliated", full_desc.lower())
        self.assertIn("core devices", full_desc.lower())
        self.assertIn("display over other apps", full_desc.lower())
        self.assertIn("notification access", full_desc.lower())
        self.assertIn("run without a watch", full_desc.lower())

    def test_generated_svgs_are_valid_xml_and_omit_notification_dot(self) -> None:
        icon_svg = assets_mod.build_icon_svg()
        feature_svg = assets_mod.build_feature_graphic_svg()

        icon_root = ET.fromstring(icon_svg)
        feature_root = ET.fromstring(feature_svg)
        self.assertEqual(icon_root.attrib["width"], "512")
        self.assertEqual(icon_root.attrib["height"], "512")
        self.assertEqual(feature_root.attrib["width"], "1024")
        self.assertEqual(feature_root.attrib["height"], "500")

        # Ensure the old teal notification dot badge coordinates (70.8, 43.7) are absent
        self.assertNotIn("70.8,43.7", icon_svg)
        self.assertNotIn("70.8,43.7", feature_svg)

    def test_validator_rejects_bad_metadata_and_images(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            meta = Path(tmp)
            (meta / "title.txt").write_text("Pebble Something Way Too Long For Google Play Store\n", encoding="utf-8")
            (meta / "short_description.txt").write_text("x" * 85 + "\n", encoding="utf-8")
            (meta / "full_description.txt").write_text("Minimal text without disclosures\n", encoding="utf-8")
            write_fake_png(meta / "images" / "icon.png", 256, 256, color_type=6)
            write_fake_png(meta / "images" / "featureGraphic.png", 1024, 500, color_type=6)

            errors = assets_mod.validate_metadata(meta)
            joined = "\n".join(errors)
            self.assertIn("exceeds 30 chars", joined)
            self.assertIn("must not start with the 'Pebble' trademark", joined)
            self.assertIn("exceeds 80 chars", joined)
            self.assertIn("non-affiliation disclaimer", joined)
            self.assertIn("256x256, expected 512x512", joined)
            self.assertIn("opaque 24-bit RGB PNG", joined)


class PlayStoreDocsTests(unittest.TestCase):
    def test_reviewer_notes_cover_all_issue_80_requirements(self) -> None:
        notes = (DOCS_DIR / "REVIEWER_NOTES.md").read_text(encoding="utf-8")
        for expected in (
            "Run without a watch",
            "Simulated Timeline",
            "Auto-Pins",
            "SYSTEM_ALERT_WINDOW",
            "Background Activity Launch",
            "WindowManager.addView",
            "tap-to-open notification",
            "Open Phone App",
            "BIND_NOTIFICATION_LISTENER_SERVICE",
            "100% on-device",
            "INTERNET",
        ):
            self.assertIn(expected, notes, f"REVIEWER_NOTES.md missing {expected!r}")

    def test_console_declarations_cover_all_issue_80_requirements(self) -> None:
        decl = (DOCS_DIR / "CONSOLE_DECLARATIONS.md").read_text(encoding="utf-8")
        for expected in (
            "Data Safety",
            "No data collected",
            "No data shared",
            "PRIVACY.md",
            "Prominent Disclosure",
            "IARC",
            "Target Audience",
            "13–15",
            "18 and over",
            "Families Policy",
            "Trader Status Declaration",
            "Feb 1, 2027",
            "Non-trader",
            "Gabriel Hidasy",
            "gabrielhidasy+play@gmail.com",
        ):
            self.assertIn(expected, decl, f"CONSOLE_DECLARATIONS.md missing {expected!r}")

    def test_readme_and_screenshots_guide_exist(self) -> None:
        self.assertTrue((DOCS_DIR / "README.md").is_file())
        guide = (DOCS_DIR / "SCREENSHOTS.md").read_text(encoding="utf-8")
        self.assertIn("1080 × 1920", guide)
        self.assertIn("1812 × 2176", guide)
        self.assertIn("TWO_PANE", guide)


class ScreenshotHarnessTests(unittest.TestCase):
    def test_viewport_profiles_satisfy_play_and_layout_constraints(self) -> None:
        phone = shots_mod.PROFILES["phone"]
        foldable = shots_mod.PROFILES["foldable"]

        self.assertLessEqual(phone.aspect_ratio, 2.0)
        self.assertLess(phone.width_dp, 600.0)
        self.assertFalse(phone.expected_two_pane)

        self.assertLessEqual(foldable.aspect_ratio, 2.0)
        self.assertGreaterEqual(foldable.width_dp, 600.0)
        self.assertTrue(foldable.expected_two_pane)

    def test_dry_run_cli_succeeds(self) -> None:
        proc = subprocess.run(
            [sys.executable, str(SCRIPTS_DIR / "capture-play-screenshots.py"), "--dry-run"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)
        self.assertIn("SINGLE_PANE", proc.stdout)
        self.assertIn("TWO_PANE", proc.stdout)
        self.assertIn("01-pin-composer.png", proc.stdout)
        self.assertIn("01-foldable-two-pane-composer.png", proc.stdout)

    def test_screenshot_png_validator_enforces_ratio_and_dimensions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            ok_phone = tmp_dir / "phone.png"
            ok_fold = tmp_dir / "foldable.png"
            bad_tall = tmp_dir / "too-tall.png"
            bad_small = tmp_dir / "too-small.png"

            write_fake_png(ok_phone, 1080, 1920)
            write_fake_png(ok_fold, 1812, 2176)
            write_fake_png(bad_tall, 1080, 2340)  # 2.17:1 > 2.0:1 Play limit
            write_fake_png(bad_small, 200, 300)

            self.assertEqual(shots_mod.validate_screenshot_png(ok_phone), [])
            self.assertEqual(shots_mod.validate_screenshot_png(ok_fold), [])
            self.assertTrue(any("aspect ratio" in e for e in shots_mod.validate_screenshot_png(bad_tall)))
            self.assertTrue(any("min dimension" in e for e in shots_mod.validate_screenshot_png(bad_small)))


if __name__ == "__main__":
    unittest.main()
