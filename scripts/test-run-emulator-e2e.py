#!/usr/bin/env python3
"""Offline unit tests for scripts/run-emulator-e2e.py UI matching & scrolling logic."""

from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
E2E_SCRIPT = REPO_ROOT / "scripts" / "run-emulator-e2e.py"

_spec = importlib.util.spec_from_file_location("run_emulator_e2e", E2E_SCRIPT)
assert _spec and _spec.loader
e2e = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = e2e
_spec.loader.exec_module(e2e)


class FakeAdbDriver(e2e.AdbDriver):
    """In-memory AdbDriver that serves scripted UI dumps and records swipes."""

    def __init__(self, dumps: list[list[e2e.UiNode]], screen: tuple[int, int] = (1080, 2160)) -> None:
        super().__init__(adb_bin="adb")
        self._dumps = list(dumps)
        self._screen_size = screen
        self._dump_idx = 0
        self.swipes: list[str] = []

    def dump_ui(self) -> list[e2e.UiNode]:
        if not self._dumps:
            return []
        idx = min(self._dump_idx, len(self._dumps) - 1)
        self._dump_idx += 1
        return list(self._dumps[idx])

    def swipe_down_page(self) -> None:
        self.swipes.append("down")

    def swipe_up_page(self) -> None:
        self.swipes.append("up")


ICON_GRID_XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node text="Pin Icon" content-desc="" class="android.widget.TextView" clickable="false" bounds="[48,900][260,948]" />
  <node text="Calendar" content-desc="" class="android.view.View" clickable="true" bounds="[48,964][278,1112]" />
  <node text="" content-desc="Flag" class="android.view.View" clickable="true" bounds="[298,964][528,1112]" />
  <node text="" content-desc="Sports" class="android.view.View" clickable="true" bounds="[548,964][778,1112]" />
  <node text="" content-desc="Checkmark" class="android.view.View" clickable="true" bounds="[798,964][1028,1112]" />
  <node text="Pins" content-desc="Timeline Pins" class="android.view.View" clickable="true" bounds="[0,1830][540,2034]" />
  <node text="Auto-Pins" content-desc="Auto-Pins" class="android.view.View" clickable="true" bounds="[540,1830][1080,2034]" />
</hierarchy>
"""


class RunEmulatorE2ETests(unittest.TestCase):
    def test_label_matches_unselected_icon_content_desc_and_selected_icon_text(self) -> None:
        nodes = e2e.AdbDriver._parse_nodes(ET.fromstring(ICON_GRID_XML))
        driver = FakeAdbDriver([nodes])

        flag = driver.find_or_scroll(direction="down", label="Flag")
        self.assertEqual(flag.content_desc, "Flag")
        self.assertEqual(flag.text, "")
        self.assertEqual(flag.center, (413, 1038))
        self.assertEqual(driver.swipes, [])

        # Selected icon ("Calendar") exposes text instead of content-desc and also matches label=
        driver_cal = FakeAdbDriver([nodes])
        calendar = driver_cal.find_or_scroll(direction="down", label="Calendar")
        self.assertEqual(calendar.text, "Calendar")
        self.assertEqual(calendar.content_desc, "")

    def test_find_or_scroll_skips_zero_size_offscreen_nodes(self) -> None:
        offscreen_flag = e2e.UiNode(
            text="",
            content_desc="Flag",
            class_name="android.view.View",
            bounds=(298, 1830, 528, 1830),
            clickable=True,
        )
        visible_flag = e2e.UiNode(
            text="",
            content_desc="Flag",
            class_name="android.view.View",
            bounds=(298, 964, 528, 1112),
            clickable=True,
        )
        nav_pins = e2e.UiNode("Pins", "Timeline Pins", "android.view.View", (0, 1830, 540, 2034), True)
        nav_auto = e2e.UiNode("Auto-Pins", "Auto-Pins", "android.view.View", (540, 1830, 1080, 2034), True)

        driver = FakeAdbDriver(
            [
                [offscreen_flag, nav_pins, nav_auto],
                [visible_flag, nav_pins, nav_auto],
            ]
        )
        node = driver.find_or_scroll(direction="down", label="Flag")
        self.assertEqual(node.bounds, visible_flag.bounds)
        self.assertEqual(driver.swipes, ["down"])

    def test_find_or_scroll_scrolls_node_out_from_bottom_navigation_bar_zone(self) -> None:
        nav_pins = e2e.UiNode("Pins", "Timeline Pins", "android.view.View", (0, 1830, 540, 2034), True)
        nav_auto = e2e.UiNode("Auto-Pins", "Auto-Pins", "android.view.View", (540, 1830, 1080, 2034), True)
        # Partially occluded near NavigationBar (center y = 1850 > 1830 - 36)
        clipped_flag = e2e.UiNode("", "Flag", "android.view.View", (298, 1780, 528, 1920), True)
        centered_flag = e2e.UiNode("", "Flag", "android.view.View", (298, 920, 528, 1068), True)

        driver = FakeAdbDriver(
            [
                [clipped_flag, nav_pins, nav_auto],
                [centered_flag, nav_pins, nav_auto],
            ]
        )
        node = driver.find_or_scroll(direction="down", label="Flag")
        self.assertEqual(node.bounds, centered_flag.bounds)
        self.assertEqual(driver.swipes, ["down"])

    def test_find_or_scroll_accepts_bottom_boundary_node_when_scroll_stops_moving(self) -> None:
        nav_pins = e2e.UiNode("Pins", "Timeline Pins", "android.view.View", (0, 1830, 540, 2034), True)
        nav_auto = e2e.UiNode("Auto-Pins", "Auto-Pins", "android.view.View", (540, 1830, 1080, 2034), True)
        bottom_delete = e2e.UiNode("Delete", "", "android.widget.TextView", (820, 1765, 1020, 1825), True)

        driver = FakeAdbDriver(
            [
                [bottom_delete, nav_pins, nav_auto],
                [bottom_delete, nav_pins, nav_auto],
            ]
        )
        node = driver.find_or_scroll(direction="down", text="Delete")
        self.assertEqual(node.bounds, bottom_delete.bounds)
        self.assertEqual(driver.swipes, ["down"])

    def test_find_or_scroll_error_includes_visible_labels(self) -> None:
        nav_pins = e2e.UiNode("Pins", "Timeline Pins", "android.view.View", (0, 1830, 540, 2034), True)
        icon_tile = e2e.UiNode("", "Flag", "android.view.View", (298, 964, 528, 1112), True)
        driver = FakeAdbDriver([[icon_tile, nav_pins]])

        with self.assertRaises(AssertionError) as ctx:
            driver.find_or_scroll(max_scrolls=1, direction="down", text="Nonexistent")

        self.assertIn("Visible labels:", str(ctx.exception))
        self.assertIn("Flag", str(ctx.exception))

    def test_wait_for_node_scrolls_when_banner_renders_below_fold(self) -> None:
        send_btn = e2e.UiNode("Send to Pebble Timeline", "", "android.widget.Button", (48, 1680, 1032, 1785), True)
        banner = e2e.UiNode("Pin sent to Pebble Timeline", "", "android.widget.TextView", (48, 1400, 1032, 1455), False)
        driver = FakeAdbDriver(
            [
                [send_btn],
                [send_btn],
                [send_btn, banner],
            ]
        )
        node = driver.wait_for_node(timeout=5.0, text_contains="Pin sent", scroll_direction="down")
        self.assertEqual(node.text, "Pin sent to Pebble Timeline")
        self.assertEqual(driver.swipes, ["down"])

    def test_visual_content_analysis_detects_blank_and_rendered_screens(self) -> None:
        ffmpeg = shutil.which("ffmpeg")
        ffprobe = shutil.which("ffprobe")
        if not ffmpeg or not ffprobe:
            self.skipTest("ffmpeg/ffprobe not available")

        with tempfile.TemporaryDirectory(prefix="pebble-e2e-visual-") as tmp:
            tmp_dir = pathlib.Path(tmp)
            blank_png = tmp_dir / "blank.png"
            ui_png = tmp_dir / "ui.png"
            poster_blank_android = tmp_dir / "poster-blank-android.png"
            blank_mp4 = tmp_dir / "blank.mp4"
            ui_mp4 = tmp_dir / "ui.mp4"

            # Pure black 360x720 screenshot -> blank
            subprocess.run(
                [ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "color=c=black:s=360x720", "-frames:v", "1", str(blank_png)],
                check=True,
            )
            blank_stats = e2e.analyze_image_visual_content(blank_png, ffmpeg_bin=ffmpeg)
            self.assertTrue(blank_stats["isBlank"])
            self.assertEqual(blank_stats["colorBuckets"], 1)

            # Synthetic Android UI with distinct header, cards, and accent button -> non-blank
            subprocess.run(
                [
                    ffmpeg,
                    "-y",
                    "-v",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=0xf8fafc:s=360x720",
                    "-vf",
                    (
                        "drawbox=x=0:y=0:w=360:h=80:color=0x1e293b:t=fill,"
                        "drawbox=x=24:y=110:w=312:h=140:color=0xe2e8f0:t=fill,"
                        "drawbox=x=24:y=280:w=312:h=64:color=0xff5500:t=fill,"
                        "drawbox=x=24:y=380:w=180:h=40:color=0x16a34a:t=fill"
                    ),
                    "-frames:v",
                    "1",
                    str(ui_png),
                ],
                check=True,
            )
            ui_stats = e2e.analyze_image_visual_content(ui_png, ffmpeg_bin=ffmpeg)
            self.assertFalse(ui_stats["isBlank"])
            self.assertGreaterEqual(ui_stats["colorBuckets"], 4)
            self.assertGreater(ui_stats["lumaStd"], 10.0)

            # Side-by-side 1280x720 poster with blank Android left pane + colorful Pebble right pane:
            # cropping the Android content rect MUST still detect the blank Android screen.
            subprocess.run(
                [
                    ffmpeg,
                    "-y",
                    "-v",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=0x0f172a:s=1280x720",
                    "-vf",
                    (
                        "drawbox=x=190:y=106:w=276:h=552:color=0x000000:t=fill,"
                        "drawbox=x=752:y=154:w=400:h=456:color=0xff5500:t=fill,"
                        "drawbox=x=780:y=200:w=300:h=120:color=0xffffff:t=fill"
                    ),
                    "-frames:v",
                    "1",
                    str(poster_blank_android),
                ],
                check=True,
            )
            poster_stats = e2e.analyze_image_visual_content(
                poster_blank_android,
                ffmpeg_bin=ffmpeg,
                crop=e2e.POSTER_ANDROID_CONTENT_CROP,
            )
            self.assertTrue(poster_stats["isBlank"])

            # Video analysis: solid black MP4 -> blank; synthetic UI MP4 -> non-blank
            subprocess.run(
                [
                    ffmpeg,
                    "-y",
                    "-v",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=black:s=360x720:r=10:d=2",
                    "-c:v",
                    "libx264",
                    "-pix_fmt",
                    "yuv420p",
                    str(blank_mp4),
                ],
                check=True,
            )
            self.assertTrue(e2e.analyze_video_visual_content(blank_mp4, ffmpeg_bin=ffmpeg, ffprobe_bin=ffprobe)["isBlank"])

            subprocess.run(
                [
                    ffmpeg,
                    "-y",
                    "-v",
                    "error",
                    "-loop",
                    "1",
                    "-i",
                    str(ui_png),
                    "-t",
                    "2",
                    "-r",
                    "10",
                    "-c:v",
                    "libx264",
                    "-pix_fmt",
                    "yuv420p",
                    str(ui_mp4),
                ],
                check=True,
            )
            ui_vid_stats = e2e.analyze_video_visual_content(ui_mp4, ffmpeg_bin=ffmpeg, ffprobe_bin=ffprobe)
            self.assertFalse(ui_vid_stats["isBlank"])
            self.assertEqual(ui_vid_stats["nonBlankSamples"], ui_vid_stats["samplesChecked"])

    def test_demo_notification_arg_builders_and_shell_quoting(self) -> None:
        args = e2e.build_broadcast_post_notification_args(
            notif_id=2002,
            tag="demo-flight",
            title="Flight DL123 Boarding",
            text="Gate B12 departs in 15 min",
            big_text="Gate B12 departs in 15 min - Terminal 2",
        )
        self.assertIn(e2e.BRIDGE_POST_NOTIFICATION_ACTION, args)
        self.assertIn(e2e.BRIDGE_NOTIFICATION_RECEIVER, args)
        self.assertIn("Flight DL123 Boarding", args)
        self.assertIn("Gate B12 departs in 15 min - Terminal 2", args)

        cancel_single = e2e.build_broadcast_cancel_notification_args(notif_id=2001, tag="demo-promo")
        self.assertIn(e2e.BRIDGE_CANCEL_NOTIFICATION_ACTION, cancel_single)
        self.assertIn("2001", cancel_single)
        self.assertIn("demo-promo", cancel_single)

        cancel_all = e2e.build_broadcast_cancel_notification_args(cancel_all=True)
        self.assertIn("cancel_all", cancel_all)
        self.assertIn("true", cancel_all)

        fallback_args = e2e.build_shell_post_notification_args(
            tag="demo-flight",
            title="Flight DL123 Boarding",
            text="Gate B12 departs in 15 min",
            big_text="Gate B12 departs in 15 min - Terminal 2",
        )
        self.assertEqual(
            fallback_args,
            [
                "cmd",
                "notification",
                "post",
                "-S",
                "bigtext",
                "-t",
                "Flight DL123 Boarding",
                "demo-flight",
                "Gate B12 departs in 15 min - Terminal 2",
            ],
        )

        # Verify shell_quoted properly quotes arguments with spaces into a single shell command
        captured_calls: list[tuple[str, ...]] = []

        class RecordingAdbDriver(e2e.AdbDriver):
            def run(self, *run_args: str, timeout: int = 60, check: bool = True):
                captured_calls.append(run_args)
                return subprocess.CompletedProcess(list(run_args), 0, stdout="", stderr="")

        rec = RecordingAdbDriver()
        e2e.post_demo_notification(
            rec,
            use_bridge=True,
            notif_id=2002,
            tag="demo-flight",
            title="Flight DL123 Boarding",
            text="Gate B12 departs in 15 min",
        )
        self.assertEqual(len(captured_calls), 1)
        self.assertEqual(captured_calls[0][0], "shell")
        self.assertIn("'Flight DL123 Boarding'", captured_calls[0][1])
        self.assertIn("'Gate B12 departs in 15 min'", captured_calls[0][1])

    def test_write_summary_lists_manual_and_auto_pins_videos(self) -> None:
        with tempfile.TemporaryDirectory(prefix="pebble-e2e-summary-") as tmp:
            out_dir = pathlib.Path(tmp)
            report = {
                "platform": "emery",
                "status": "passed",
                "assertions": [
                    {"name": "pin_sent_over_pebblekit2", "passed": True, "details": "ok"},
                    {"name": "auto_pin_flight_created", "passed": True, "details": "ok"},
                ],
                "video": {
                    "width": 1280,
                    "height": 720,
                    "codec": "h264",
                    "durationSeconds": 40.5,
                    "sizeBytes": 512 * 1024,
                },
                "videos": [
                    {
                        "scenario": "manual-pins",
                        "label": "Manual Pins",
                        "width": 1280,
                        "height": 720,
                        "codec": "h264",
                        "durationSeconds": 40.5,
                        "sizeBytes": 512 * 1024,
                    },
                    {
                        "scenario": "auto-pins",
                        "label": "Auto-Pins",
                        "width": 1280,
                        "height": 720,
                        "codec": "h264",
                        "durationSeconds": 48.2,
                        "sizeBytes": 640 * 1024,
                    },
                ],
            }
            summary_path = e2e.write_summary(out_dir, [report])
            text = summary_path.read_text(encoding="utf-8")
            self.assertIn("| `emery` | Manual Pins | **PASSED** | 2/2 | `1280x720` (h264) | 40.5s | 512 KB |", text)
            self.assertIn("| `emery` | Auto-Pins | **PASSED** | 2/2 | `1280x720` (h264) | 48.2s | 640 KB |", text)

    def test_qemu_controller_isolated_tmpdir_and_tracked_pid_sigkill(self) -> None:
        with tempfile.TemporaryDirectory(prefix="pebble-emu-cleanup-test-") as tmp:
            iso_tmp = pathlib.Path(tmp) / "pebble-tmp"
            pbw = pathlib.Path(tmp) / "app.pbw"
            pbw.write_bytes(b"pbw")
            ctrl = e2e.PebbleQemuController(platform="emery", pebble_bin="pebble", tmpdir=iso_tmp)

            recorded_envs: list[dict[str, str] | None] = []

            def fake_run_cmd(args: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
                env = kwargs.get("env")
                recorded_envs.append(env if isinstance(env, dict) else None)
                if args[:2] == ["pebble", "install"]:
                    info = {
                        "emery": {
                            "4.44": {
                                "qemu": {"pid": 900001, "monitor": 45678},
                                "pypkjs": {"pid": 900002, "port": 45679},
                            }
                        }
                    }
                    (iso_tmp / "pb-emulator.json").write_text(json.dumps(info), encoding="utf-8")
                return subprocess.CompletedProcess(args, 0, "", "")

            killed: list[tuple[int, int]] = []

            with (
                mock.patch.object(e2e, "run_cmd", side_effect=fake_run_cmd),
                mock.patch.object(e2e, "_is_pebble_emulator_pid", side_effect=lambda pid: pid == 900001),
                mock.patch.object(e2e.os, "kill", side_effect=lambda pid, sig: killed.append((pid, sig))),
                mock.patch.object(e2e.time, "sleep"),
            ):
                mon = ctrl.install_and_boot(pbw)
                self.assertEqual(mon, 45678)
                self.assertEqual(ctrl._tracked_pids, {900001, 900002})
                # Simulate pb-emulator.json disappearing before kill()
                (iso_tmp / "pb-emulator.json").unlink()
                ctrl.kill()

            self.assertTrue(all(env and env.get("TMPDIR") == str(iso_tmp) for env in recorded_envs))
            self.assertEqual(killed, [(900001, e2e.signal.SIGKILL)])
            self.assertEqual(ctrl._tracked_pids, set())

    def test_qemu_controller_cleans_up_when_install_fails_after_spawning(self) -> None:
        with tempfile.TemporaryDirectory(prefix="pebble-emu-fail-test-") as tmp:
            iso_tmp = pathlib.Path(tmp) / "pebble-tmp"
            pbw = pathlib.Path(tmp) / "app.pbw"
            pbw.write_bytes(b"pbw")
            ctrl = e2e.PebbleQemuController(platform="basalt", pebble_bin="pebble", tmpdir=iso_tmp)

            def fake_run_cmd(args: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
                if args[:2] == ["pebble", "install"]:
                    info = {
                        "basalt": {
                            "4.44": {
                                "qemu": {"pid": 900010, "monitor": 45680},
                                "pypkjs": {"pid": 900011, "port": 45681},
                            }
                        }
                    }
                    (iso_tmp / "pb-emulator.json").write_text(json.dumps(info), encoding="utf-8")
                    raise subprocess.TimeoutExpired(args, 120)
                return subprocess.CompletedProcess(args, 0, "", "")

            killed: list[tuple[int, int]] = []
            with (
                mock.patch.object(e2e, "run_cmd", side_effect=fake_run_cmd),
                mock.patch.object(e2e, "_is_pebble_emulator_pid", return_value=True),
                mock.patch.object(e2e.os, "kill", side_effect=lambda pid, sig: killed.append((pid, sig))),
                mock.patch.object(e2e.time, "sleep"),
            ):
                with self.assertRaises(subprocess.TimeoutExpired):
                    ctrl.install_and_boot(pbw)

            self.assertEqual(killed, [(900010, e2e.signal.SIGKILL), (900011, e2e.signal.SIGKILL)])
            self.assertFalse((iso_tmp / "pb-emulator.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
