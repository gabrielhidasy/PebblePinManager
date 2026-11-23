#!/usr/bin/env python3
"""Offline unit tests for scripts/run-emulator-e2e.py UI matching & scrolling logic."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
