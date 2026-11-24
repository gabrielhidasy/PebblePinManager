#!/usr/bin/env python3
"""Automated Google Play Store screenshot capture for Phone & Foldable form factors.

Drives an Android emulator or ADB-connected device in Watchless ("Run without a watch")
mode — requiring zero physical Pebble hardware — and captures Google Play compliant
PNGs into Fastlane Supply screenshot directories:

  - phone    (1080x1920 @ 420 dpi, 9:16, SINGLE_PANE)  -> images/phoneScreenshots/
  - foldable (1812x2176 @ 360 dpi, ~805 dp, TWO_PANE)  -> images/tenInchScreenshots/

Use `--dry-run` to preview the capture plan without a connected device.
"""

from __future__ import annotations

import argparse
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_METADATA_DIR = REPO_ROOT / "fastlane" / "metadata" / "android" / "en-US"

RELEASE_PACKAGE = "xyz.hidasy.pebblepinmanager"
DEBUG_PACKAGE = "xyz.hidasy.pebblepinmanager.debug"
MAIN_ACTIVITY = "xyz.hidasy.pebblepinmanager.MainActivity"

PLAY_MIN_DIM_PX = 320
PLAY_MAX_DIM_PX = 3840
PLAY_MAX_ASPECT_RATIO = 2.0
PLAY_MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


@dataclass(frozen=True)
class ViewportProfile:
    name: str
    width_px: int
    height_px: int
    density_dpi: int
    fastlane_subdir: str
    expected_two_pane: bool
    shots: tuple[str, ...]

    @property
    def width_dp(self) -> float:
        return self.width_px * 160.0 / self.density_dpi

    @property
    def height_dp(self) -> float:
        return self.height_px * 160.0 / self.density_dpi

    @property
    def aspect_ratio(self) -> float:
        return max(self.width_px, self.height_px) / min(self.width_px, self.height_px)


PROFILES: dict[str, ViewportProfile] = {
    "phone": ViewportProfile(
        name="phone",
        width_px=1080,
        height_px=1920,
        density_dpi=420,
        fastlane_subdir="phoneScreenshots",
        expected_two_pane=False,
        shots=(
            "01-pin-composer.png",
            "02-recent-pins.png",
            "03-simulated-timeline.png",
            "04-auto-pins.png",
            "05-watch-handoff-dialog.png",
        ),
    ),
    "foldable": ViewportProfile(
        name="foldable",
        width_px=1812,
        height_px=2176,
        density_dpi=360,
        fastlane_subdir="tenInchScreenshots",
        expected_two_pane=True,
        shots=(
            "01-foldable-two-pane-composer.png",
            "02-foldable-simulated-timeline.png",
            "03-foldable-auto-pins.png",
        ),
    ),
}


def validate_screenshot_png(path: Path) -> list[str]:
    """Validate a captured screenshot against Google Play dimension, ratio, and size limits."""
    errors: list[str] = []
    if not path.is_file():
        return [f"Missing screenshot: {path}"]
    data = path.read_bytes()
    if len(data) < 26 or data[:8] != PNG_SIGNATURE:
        return [f"{path} is not a valid PNG"]
    chunk_len, chunk_type = struct.unpack(">I4s", data[8:16])
    if chunk_type != b"IHDR" or chunk_len != 13:
        return [f"{path} has invalid PNG IHDR chunk"]
    width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    min_dim, max_dim = min(width, height), max(width, height)
    if min_dim < PLAY_MIN_DIM_PX:
        errors.append(f"{path.name}: min dimension {min_dim}px < {PLAY_MIN_DIM_PX}px")
    if max_dim > PLAY_MAX_DIM_PX:
        errors.append(f"{path.name}: max dimension {max_dim}px > {PLAY_MAX_DIM_PX}px")
    ratio = max_dim / min_dim if min_dim else 999.0
    if ratio > PLAY_MAX_ASPECT_RATIO:
        errors.append(f"{path.name}: aspect ratio {ratio:.2f}:1 exceeds {PLAY_MAX_ASPECT_RATIO:.1f}:1")
    if bit_depth != 8 or color_type not in (2, 6):
        errors.append(f"{path.name}: unexpected PNG depth={bit_depth} color_type={color_type}")
    if len(data) > PLAY_MAX_SCREENSHOT_BYTES:
        errors.append(f"{path.name}: size {len(data)} exceeds {PLAY_MAX_SCREENSHOT_BYTES} bytes")
    return errors


class AdbScreenshotRunner:
    def __init__(self, adb_bin: str = "adb", serial: str | None = None) -> None:
        self.adb_bin = adb_bin
        self.serial = serial

    def _base(self) -> list[str]:
        cmd = [self.adb_bin]
        if self.serial:
            cmd.extend(["-s", self.serial])
        return cmd

    def run(self, *args: str, check: bool = True, timeout: int = 60) -> subprocess.CompletedProcess[str]:
        proc = subprocess.run(
            [*self._base(), *args],
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if check and proc.returncode != 0:
            raise RuntimeError(
                f"adb {' '.join(args)} failed (rc={proc.returncode}): {proc.stderr.strip()}"
            )
        return proc

    def shell(self, *args: str, check: bool = True, timeout: int = 60) -> str:
        return self.run("shell", *args, check=check, timeout=timeout).stdout

    def capture_png(self, output_path: Path) -> None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        proc = subprocess.run(
            [*self._base(), "exec-out", "screencap", "-p"],
            check=False,
            capture_output=True,
            timeout=30,
        )
        if proc.returncode != 0 or not proc.stdout.startswith(PNG_SIGNATURE):
            raise RuntimeError(f"screencap failed for {output_path.name} (rc={proc.returncode})")
        output_path.write_bytes(proc.stdout)

    def dump_nodes(self) -> list[tuple[str, str, tuple[int, int, int, int]]]:
        remote = "/sdcard/play-shot-ui.xml"
        for _ in range(3):
            res = self.run("shell", "uiautomator", "dump", remote, check=False, timeout=25)
            if res.returncode == 0:
                xml_text = self.shell("cat", remote, check=False, timeout=15)
                start = xml_text.find("<?xml")
                if start != -1:
                    root = ET.fromstring(xml_text[start:])
                    nodes: list[tuple[str, str, tuple[int, int, int, int]]] = []
                    for elem in root.iter("node"):
                        m = BOUNDS_RE.match(elem.attrib.get("bounds", ""))
                        if not m:
                            continue
                        bounds = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
                        nodes.append(
                            (elem.attrib.get("text", ""), elem.attrib.get("content-desc", ""), bounds)
                        )
                    return nodes
            time.sleep(0.5)
        return []

    def tap_matching(self, needle: str, timeout_s: float = 8.0) -> bool:
        deadline = time.time() + timeout_s
        lower = needle.lower()
        while time.time() < deadline:
            for text, desc, (x1, y1, x2, y2) in self.dump_nodes():
                if lower in text.lower() or lower in desc.lower():
                    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                    self.shell("input", "tap", str(cx), str(cy))
                    time.sleep(0.7)
                    return True
            time.sleep(0.5)
        return False

    def type_text_in_field(self, field_label: str, value: str) -> None:
        if not self.tap_matching(field_label):
            raise RuntimeError(f"Could not find input field matching {field_label!r}")
        encoded = value.replace(" ", "%s")
        self.shell("input", "text", encoded)
        self.shell("input", "keyevent", "111")  # ESC to hide soft keyboard
        time.sleep(0.5)

    def swipe_down_list(self, profile: ViewportProfile) -> None:
        cx = profile.width_px // 2
        y_start = int(profile.height_px * 0.78)
        y_end = int(profile.height_px * 0.22)
        self.shell("input", "swipe", str(cx), str(y_start), str(cx), str(y_end), "350")
        time.sleep(0.8)


def enable_watchless_and_seed_pins(runner: AdbScreenshotRunner, package: str) -> None:
    """Launch app cleanly, enable Run without a watch, and seed representative sample pins."""
    runner.shell("am", "force-stop", package, check=False)
    runner.shell("pm", "clear", package, check=False)
    runner.shell("am", "start", "-n", f"{package}/{MAIN_ACTIVITY}")
    time.sleep(2.0)

    # Tap "Run without a watch" on the home status card (or fall back to App settings dialog)
    if not runner.tap_matching("Run without a watch", timeout_s=5.0):
        runner.tap_matching("App settings", timeout_s=5.0)
        runner.tap_matching("Run without a watch", timeout_s=5.0)
        runner.tap_matching("Done", timeout_s=4.0)

    # Seed Pin 1: Team Standup
    runner.type_text_in_field("Event Title", "Team Standup")
    runner.type_text_in_field("Description", "Daily sync in Room 4B")
    runner.tap_matching("+15 min", timeout_s=4.0)
    runner.tap_matching("Simulate Timeline Pin", timeout_s=5.0)
    time.sleep(1.0)

    # Seed Pin 2: Dentist appointment (and leave it composed or sent for rich history)
    runner.type_text_in_field("Event Title", "Dentist appointment")
    runner.type_text_in_field("Description", "Building B, 3rd floor")
    runner.tap_matching("+1 hour", timeout_s=4.0)
    runner.tap_matching("Simulate Timeline Pin", timeout_s=5.0)
    time.sleep(1.0)


def capture_profile(
    runner: AdbScreenshotRunner,
    profile: ViewportProfile,
    package: str,
    metadata_dir: Path,
) -> list[Path]:
    out_dir = metadata_dir / "images" / profile.fastlane_subdir
    out_dir.mkdir(parents=True, exist_ok=True)
    captured: list[Path] = []

    try:
        runner.shell("wm", "size", f"{profile.width_px}x{profile.height_px}")
        runner.shell("wm", "density", str(profile.density_dpi))
        time.sleep(1.2)

        enable_watchless_and_seed_pins(runner, package)

        # Prefill composer form for Shot 1
        runner.type_text_in_field("Event Title", "Flight to NYC")
        runner.type_text_in_field("Description", "Gate B12, boarding starts 40 min prior")
        runner.tap_matching("Tomorrow 09:00", timeout_s=4.0)

        shot1 = out_dir / profile.shots[0]
        runner.capture_png(shot1)
        captured.append(shot1)

        if profile.name == "phone":
            # Shot 2: Scroll to Recent Sent Pins on single-pane phone
            runner.swipe_down_list(profile)
            runner.swipe_down_list(profile)
            shot2 = out_dir / profile.shots[1]
            runner.capture_png(shot2)
            captured.append(shot2)

            # Shot 3: Simulated Timeline tab
            runner.tap_matching("Timeline", timeout_s=5.0)
            time.sleep(0.8)
            shot3 = out_dir / profile.shots[2]
            runner.capture_png(shot3)
            captured.append(shot3)

            # Shot 4: Auto-Pins tab
            runner.tap_matching("Auto-Pins", timeout_s=5.0)
            time.sleep(0.8)
            shot4 = out_dir / profile.shots[3]
            runner.capture_png(shot4)
            captured.append(shot4)

            # Shot 5: Watch Handoff dialog
            runner.tap_matching("Pins", timeout_s=5.0)
            runner.tap_matching("Watch handoff settings", timeout_s=5.0)
            time.sleep(0.6)
            shot5 = out_dir / profile.shots[4]
            runner.capture_png(shot5)
            captured.append(shot5)
        else:
            # Foldable Shot 2: Simulated Timeline
            runner.tap_matching("Timeline", timeout_s=5.0)
            time.sleep(0.8)
            shot2 = out_dir / profile.shots[1]
            runner.capture_png(shot2)
            captured.append(shot2)

            # Foldable Shot 3: Auto-Pins
            runner.tap_matching("Auto-Pins", timeout_s=5.0)
            time.sleep(0.8)
            shot3 = out_dir / profile.shots[2]
            runner.capture_png(shot3)
            captured.append(shot3)
    finally:
        runner.shell("wm", "size", "reset", check=False)
        runner.shell("wm", "density", "reset", check=False)

    return captured


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Capture Google Play Store Phone & Foldable screenshots via ADB in Watchless mode.",
    )
    parser.add_argument("--adb", default="adb", help="Path to adb binary (default: adb)")
    parser.add_argument("--serial", default=None, help="Optional ADB device serial")
    parser.add_argument(
        "--apk",
        type=Path,
        default=None,
        help="Optional APK to install before capturing screenshots",
    )
    parser.add_argument(
        "--skip-install",
        action="store_true",
        help="Skip APK installation and use the already-installed app",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help=f"Target debug package ({DEBUG_PACKAGE}) instead of release ({RELEASE_PACKAGE})",
    )
    parser.add_argument(
        "--profiles",
        nargs="+",
        choices=sorted(PROFILES.keys()),
        default=["phone", "foldable"],
        help="Viewport profiles to capture (default: phone foldable)",
    )
    parser.add_argument(
        "--metadata-dir",
        type=Path,
        default=DEFAULT_METADATA_DIR,
        help="Fastlane Android locale metadata directory",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print planned viewport presets and output files without running ADB commands",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    package = DEBUG_PACKAGE if args.debug else RELEASE_PACKAGE

    if args.dry_run:
        print(f"Dry-run plan for package={package}:")
        for profile_name in args.profiles:
            profile = PROFILES[profile_name]
            layout = "TWO_PANE" if profile.expected_two_pane else "SINGLE_PANE"
            print(
                f"  - {profile.name}: {profile.width_px}x{profile.height_px} @ {profile.density_dpi}dpi "
                f"({profile.width_dp:.1f}x{profile.height_dp:.1f}dp, ratio={profile.aspect_ratio:.2f}:1, {layout})"
            )
            for shot in profile.shots:
                dest = args.metadata_dir / "images" / profile.fastlane_subdir / shot
                print(f"      -> {dest}")
        return 0

    runner = AdbScreenshotRunner(adb_bin=args.adb, serial=args.serial)
    if args.apk and not args.skip_install:
        runner.run("install", "-r", str(args.apk), timeout=120)

    all_errors: list[str] = []
    for profile_name in args.profiles:
        profile = PROFILES[profile_name]
        print(f"Capturing {profile.name} screenshots ({profile.width_px}x{profile.height_px}@{profile.density_dpi}dpi)...")
        captured = capture_profile(runner, profile, package, args.metadata_dir)
        for path in captured:
            errs = validate_screenshot_png(path)
            all_errors.extend(errs)
            status = "OK" if not errs else f"ERRORS: {errs}"
            print(f"  {path} [{status}]")

    if all_errors:
        for err in all_errors:
            print(f"ERROR: {err}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
