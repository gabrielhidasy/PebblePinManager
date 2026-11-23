#!/usr/bin/env python3
"""End-to-end PebblePinManager Emulator Test & Side-by-Side Demo Video Orchestrator.

Runs the complete automated user journey across BOTH emulators simultaneously:
  1. Deploys `pebblepinmanager-watchapp.pbw` to Pebble QEMU (`basalt` and/or `emery`).
  2. Starts `scripts/pebble-emulator-bridge.py` and configures `adb reverse` + the
     PebbleKit2 Emulator Bridge APK + `pebblepinmanager-android-debug.apk` on Android.
  3. Starts synchronized dual screen recording (Android `screenrecord` with automatic
     `screencap` fallback + Pebble QEMU monitor `screendump` thread).
  4. Drives the real Android Compose UI via `adb shell uiautomator` & `adb shell input`:
       - Verifies live "Watch connected" status for the active Pebble platform
       - Creates & sends a customized timeline pin (Title, Description, +15 min,
         Flag icon, 30 min duration, Orange accent color)
       - Navigates the Pebble emulator to Timeline & opens the pin detail card
       - Edits the pin in Android ("Updated Dentist Visit") & verifies update on Pebble
       - Deletes the pin in Android (with dialog confirmation) & verifies removal
  5. Asserts UI state, PebbleKit2 bridge event payloads, and Pebble screen transitions.
  6. Renders the first side-by-side 1280x720 MP4 demo video (`demo-<platform>.mp4`) + poster PNG.
  7. Starts a second synchronized recording (`demo-<platform>-auto-pins.mp4`) exploring Auto-Pins:
       - Opens the Auto-Pins tab, launches Android Notification Access Settings, and grants access
       - Allowlists the demo notification source app, configures include/exclude keywords & accent color
       - Sends live incoming notifications (an excluded promo ignored + Flight & Delivery notifications
         with relative event-time extraction) and verifies the resulting auto-pins on Android & Pebble
  8. Writes the JSON report + Markdown summary covering both videos.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any

APP_PACKAGE = "xyz.hidasy.pebblepinmanager.debug"
APP_ACTIVITY = "xyz.hidasy.pebblepinmanager.MainActivity"
BRIDGE_PACKAGE = "xyz.hidasy.pebblepinmanager.emulatorbridge"
BRIDGE_RECEIVER = f"{BRIDGE_PACKAGE}/.BridgeConfigReceiver"
BRIDGE_CONFIG_ACTION = f"{BRIDGE_PACKAGE}.CONFIGURE"
BRIDGE_NOTIFICATION_RECEIVER = f"{BRIDGE_PACKAGE}/.DemoNotificationReceiver"
BRIDGE_POST_NOTIFICATION_ACTION = f"{BRIDGE_PACKAGE}.POST_NOTIFICATION"
BRIDGE_CANCEL_NOTIFICATION_ACTION = f"{BRIDGE_PACKAGE}.CANCEL_NOTIFICATION"
NOTIFICATION_LISTENER_COMPONENT = (
    f"{APP_PACKAGE}/xyz.hidasy.pebblepinmanager.notifications.PebbleNotificationListenerService"
)
WATCHAPP_UUID = "e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042"

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def log(msg: str) -> None:
    ts = time.strftime("%H:%M:%S", time.gmtime())
    print(f"[{ts}] {msg}", flush=True)


def run_cmd(
    args: list[str],
    timeout: int = 60,
    check: bool = True,
    capture_output: bool = True,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        args,
        timeout=timeout,
        check=False,
        capture_output=capture_output,
        text=True,
        env=env,
    )
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"Command failed (rc={proc.returncode}): {' '.join(args)}\n"
            f"stdout: {proc.stdout.strip()}\nstderr: {proc.stderr.strip()}"
        )
    return proc


@dataclass
class UiNode:
    text: str
    content_desc: str
    class_name: str
    bounds: tuple[int, int, int, int]
    clickable: bool

    @property
    def width(self) -> int:
        return max(0, self.bounds[2] - self.bounds[0])

    @property
    def height(self) -> int:
        return max(0, self.bounds[3] - self.bounds[1])

    @property
    def is_visible_box(self) -> bool:
        return self.width >= 12 and self.height >= 12

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return ((x1 + x2) // 2, (y1 + y2) // 2)


class AdbDriver:
    def __init__(self, adb_bin: str = "adb", serial: str | None = None) -> None:
        self.adb_bin = adb_bin
        self.serial = serial
        self._screen_size: tuple[int, int] | None = None

    def _base(self) -> list[str]:
        cmd = [self.adb_bin]
        if self.serial:
            cmd.extend(["-s", self.serial])
        return cmd

    def run(self, *args: str, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run_cmd([*self._base(), *args], timeout=timeout, check=check)

    def shell(self, *args: str, timeout: int = 60, check: bool = True) -> str:
        return self.run("shell", *args, timeout=timeout, check=check).stdout

    def shell_quoted(self, *args: str, timeout: int = 60, check: bool = True) -> str:
        cmd_str = " ".join(shlex.quote(arg) for arg in args)
        return self.run("shell", cmd_str, timeout=timeout, check=check).stdout

    def screen_size(self) -> tuple[int, int]:
        if self._screen_size is None:
            out = self.shell("wm", "size", check=False)
            m = re.search(r"(\d+)x(\d+)", out)
            if m:
                self._screen_size = (int(m.group(1)), int(m.group(2)))
            else:
                self._screen_size = (1080, 2160)
        return self._screen_size

    def dump_ui_xml(self) -> str:
        remote_xml = "/sdcard/pebblepin-ui-dump.xml"
        # Retry if uiautomator is momentarily busy
        for attempt in range(3):
            res = self.run("shell", "uiautomator", "dump", remote_xml, check=False, timeout=25)
            if res.returncode == 0:
                xml_text = self.run("shell", "cat", remote_xml, check=False, timeout=15).stdout
                start = xml_text.find("<?xml")
                if start == -1:
                    start = xml_text.find("<hierarchy")
                if start != -1:
                    candidate = xml_text[start:]
                    try:
                        ET.fromstring(candidate)
                        return candidate
                    except ET.ParseError:
                        pass
            time.sleep(0.6 * (attempt + 1))
        raise RuntimeError("Failed to dump Android UI hierarchy via uiautomator")

    def dump_ui(self) -> list[UiNode]:
        root = ET.fromstring(self.dump_ui_xml())
        return self._parse_nodes(root)

    @staticmethod
    def _parse_nodes(root: ET.Element) -> list[UiNode]:
        nodes: list[UiNode] = []
        for elem in root.iter("node"):
            m = BOUNDS_RE.match(elem.attrib.get("bounds", ""))
            if not m:
                continue
            bounds = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
            nodes.append(
                UiNode(
                    text=elem.attrib.get("text", ""),
                    content_desc=elem.attrib.get("content-desc", ""),
                    class_name=elem.attrib.get("class", ""),
                    bounds=bounds,
                    clickable=elem.attrib.get("clickable", "false") == "true",
                )
            )
        return nodes

    @staticmethod
    def _matches(
        node: UiNode,
        text: str | None = None,
        text_contains: str | None = None,
        content_desc: str | None = None,
        content_desc_contains: str | None = None,
        label: str | None = None,
        label_contains: str | None = None,
        class_name: str | None = None,
    ) -> bool:
        if text is not None and node.text != text:
            return False
        if text_contains is not None and text_contains not in node.text:
            return False
        if content_desc is not None and node.content_desc != content_desc:
            return False
        if content_desc_contains is not None and content_desc_contains not in node.content_desc:
            return False
        if label is not None and node.text != label and node.content_desc != label:
            return False
        if (
            label_contains is not None
            and label_contains not in node.text
            and label_contains not in node.content_desc
        ):
            return False
        if class_name is not None and node.class_name != class_name:
            return False
        return True

    @staticmethod
    def _summarize_labels(nodes: list[UiNode], limit: int = 40) -> list[str]:
        labels: list[str] = []
        seen: set[str] = set()
        for n in nodes:
            if not n.is_visible_box:
                continue
            for val in (n.text, n.content_desc):
                if val and val not in seen:
                    seen.add(val)
                    labels.append(val)
                    if len(labels) >= limit:
                        return labels
        return labels

    def _safe_viewport_y_range(self, nodes: list[UiNode]) -> tuple[int, int]:
        _, h = self.screen_size()
        top_y = int(h * 0.11)
        bottom_y = int(h * 0.88)
        # Keep tap targets above the Material 3 bottom NavigationBar ("Pins" / "Auto-Pins")
        nav_tops = [
            n.bounds[1]
            for n in nodes
            if n.bounds[1] > int(h * 0.75)
            and (n.text in ("Pins", "Auto-Pins") or n.content_desc in ("Timeline Pins", "Auto-Pins"))
        ]
        if nav_tops:
            bottom_y = min(bottom_y, min(nav_tops) - 36)
        return (top_y, max(top_y + 100, bottom_y))

    def find_nodes(self, **criteria: Any) -> list[UiNode]:
        return [n for n in self.dump_ui() if self._matches(n, **criteria)]

    def wait_for_node(
        self,
        timeout: float = 20.0,
        scroll_direction: str | None = None,
        **criteria: Any,
    ) -> UiNode:
        deadline = time.time() + timeout
        last_labels: list[str] = []
        attempts = 0
        scrolls_done = 0
        while time.time() < deadline:
            nodes = self.dump_ui()
            last_labels = self._summarize_labels(nodes)
            for n in nodes:
                if n.is_visible_box and self._matches(n, **criteria):
                    return n
            attempts += 1
            if scroll_direction is not None and attempts >= 2 and scrolls_done < 3:
                if scroll_direction == "down":
                    self.swipe_down_page()
                else:
                    self.swipe_up_page()
                scrolls_done += 1
                continue
            time.sleep(0.8)
        raise AssertionError(
            f"Timed out waiting for UI node matching {criteria}. "
            f"Visible labels: {last_labels}"
        )

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def swipe_down_page(self) -> None:
        w, h = self.screen_size()
        self.shell("input", "swipe", str(w // 2), str(int(h * 0.74)), str(w // 2), str(int(h * 0.28)), "350")
        time.sleep(0.6)

    def swipe_up_page(self) -> None:
        w, h = self.screen_size()
        self.shell("input", "swipe", str(w // 2), str(int(h * 0.28)), str(w // 2), str(int(h * 0.74)), "350")
        time.sleep(0.6)

    def find_or_scroll(self, max_scrolls: int = 8, direction: str = "down", **criteria: Any) -> UiNode:
        last_labels: list[str] = []
        prev_edge_bounds: tuple[int, int, int, int] | None = None
        for attempt in range(max_scrolls + 1):
            nodes = self.dump_ui()
            last_labels = self._summarize_labels(nodes)
            top_y, bottom_y = self._safe_viewport_y_range(nodes)
            visible_matches = [n for n in nodes if n.is_visible_box and self._matches(n, **criteria)]
            if visible_matches:
                visible_matches.sort(key=lambda n: not n.clickable)
                in_safe_band = [n for n in visible_matches if top_y <= n.center[1] <= bottom_y]
                if in_safe_band:
                    return in_safe_band[0]
                best = visible_matches[0]
                # If already at the scroll boundary (node didn't move) or out of scrolls, accept it
                if prev_edge_bounds == best.bounds or attempt == max_scrolls:
                    return best
                prev_edge_bounds = best.bounds
            else:
                prev_edge_bounds = None

            if attempt < max_scrolls:
                if direction == "down":
                    self.swipe_down_page()
                else:
                    self.swipe_up_page()
        raise AssertionError(
            f"Could not locate UI node after {max_scrolls} scrolls: {criteria}. "
            f"Visible labels: {last_labels}"
        )

    def dismiss_ime_if_shown(self) -> None:
        out = self.shell("dumpsys", "input_method", check=False)
        if "mInputShown=true" in out:
            self.shell("input", "keyevent", "4", check=False)
            time.sleep(0.4)

    def input_text(self, text: str) -> None:
        # `adb shell input text` uses %s for spaces and avoids shell metacharacters
        escaped = text.replace(" ", "%s")
        self.shell("input", "text", escaped)
        time.sleep(0.35)

    def clear_focused_text(self, max_chars: int = 40) -> None:
        # Select-all (Ctrl+A) isn't uniform on every ATD image, so move to end + backspace batch
        self.shell("input", "keyevent", "123")  # KEYCODE_MOVE_END
        del_keys = ["67"] * max_chars  # KEYCODE_DEL
        self.shell("input", "keyevent", *del_keys)
        time.sleep(0.3)

    def screencap(self, dest: Path) -> None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        proc = subprocess.run(
            [*self._base(), "exec-out", "screencap", "-p"],
            capture_output=True,
            timeout=20,
            check=False,
        )
        if proc.returncode != 0 or not proc.stdout:
            raise RuntimeError(f"adb exec-out screencap failed (rc={proc.returncode})")
        dest.write_bytes(proc.stdout)


class PebbleQemuController:
    def __init__(self, platform: str, pebble_bin: str = "pebble") -> None:
        self.platform = platform
        self.pebble_bin = pebble_bin
        self.monitor_port: int | None = None

    def install_and_boot(self, pbw_path: Path) -> int:
        run_cmd([self.pebble_bin, "kill", "--force"], check=False, timeout=15)
        time.sleep(0.5)
        log(f"Installing watchapp ({pbw_path.name}) into Pebble QEMU ({self.platform})...")
        run_cmd(
            [self.pebble_bin, "install", "--emulator", self.platform, str(pbw_path)],
            timeout=120,
            check=True,
        )
        self.monitor_port = self._discover_monitor_port()
        log(f"Pebble QEMU ({self.platform}) booted; monitor port={self.monitor_port}")
        return self.monitor_port

    def _discover_monitor_port(self, timeout: float = 15.0) -> int:
        info_path = Path(tempfile.gettempdir()) / "pb-emulator.json"
        deadline = time.time() + timeout
        while time.time() < deadline:
            if info_path.is_file():
                try:
                    data = json.loads(info_path.read_text(encoding="utf-8"))
                    plat_map = data.get(self.platform, {})
                    for ver_info in plat_map.values():
                        mon = ver_info.get("qemu", {}).get("monitor")
                        if mon:
                            return int(mon)
                except (OSError, ValueError, TypeError):
                    pass
            time.sleep(0.3)
        raise RuntimeError(f"Could not discover QEMU monitor port for {self.platform} in {info_path}")

    def click(self, button: str, repeat: int = 1) -> None:
        cmd = [self.pebble_bin, "emu-button", "--emulator", self.platform, "click", button]
        if repeat > 1:
            cmd.extend(["--repeat", str(repeat), "--interval", "350"])
        run_cmd(cmd, timeout=20, check=True)

    def screendump(self, ppm_path: Path) -> bool:
        if self.monitor_port is None:
            return False
        ppm_path.parent.mkdir(parents=True, exist_ok=True)
        if ppm_path.exists():
            ppm_path.unlink()
        try:
            with socket.create_connection(("127.0.0.1", self.monitor_port), timeout=1.5) as sock:
                sock.settimeout(0.5)
                try:
                    sock.recv(4096)
                except OSError:
                    pass
                sock.sendall(f"screendump {ppm_path}\n".encode("utf-8"))
                try:
                    sock.recv(4096)
                except OSError:
                    pass
        except OSError:
            return False

        deadline = time.time() + 1.0
        while time.time() < deadline:
            if ppm_path.exists() and ppm_path.stat().st_size > 100:
                return True
            time.sleep(0.02)
        return False

    def capture_png(self, png_path: Path, ffmpeg_bin: str = "ffmpeg") -> Path:
        ppm_path = png_path.with_suffix(".ppm")
        if not self.screendump(ppm_path):
            raise RuntimeError(f"Failed to capture Pebble QEMU screendump to {ppm_path}")
        # Normalize unlit QEMU backlight frames so milestone PNGs match lit watch vibrancy
        data = ppm_path.read_bytes()
        if data.startswith(b"P6"):
            idx = 2
            tokens = 0
            n = len(data)
            while tokens < 3 and idx < n:
                while idx < n and data[idx] in b" \t\r\n":
                    idx += 1
                if idx < n and data[idx] == ord("#"):
                    while idx < n and data[idx] != ord("\n"):
                        idx += 1
                    continue
                start = idx
                while idx < n and data[idx] not in b" \t\r\n":
                    idx += 1
                if start < idx:
                    tokens += 1
            if tokens == 3 and idx + 1 < n:
                header, pixels = data[: idx + 1], data[idx + 1 :]
                max_val = max(pixels) if pixels else 0
                if 20 < max_val <= 210:
                    scale = 255.0 / float(max_val)
                    lut = bytes(min(255, int(round(v * scale))) for v in range(256))
                    ppm_path.write_bytes(header + pixels.translate(lut))
        run_cmd([ffmpeg_bin, "-y", "-i", str(ppm_path), str(png_path)], timeout=20, check=True)
        ppm_path.unlink(missing_ok=True)
        return png_path


class PebbleFrameRecorder:
    """Continuously records timestamped Pebble QEMU monitor frames on a background thread."""

    def __init__(self, controller: PebbleQemuController, frames_dir: Path, t0: float, fps: float = 5.0) -> None:
        self.controller = controller
        self.frames_dir = frames_dir
        self.t0 = t0
        self.interval = 1.0 / fps
        self.frames: list[dict[str, Any]] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def start(self) -> None:
        self.frames_dir.mkdir(parents=True, exist_ok=True)
        self._thread.start()

    def stop(self, timestamps_out: Path) -> list[dict[str, Any]]:
        self._stop.set()
        self._thread.join(timeout=5.0)
        timestamps_out.write_text(json.dumps(self.frames, indent=2), encoding="utf-8")
        return self.frames

    def _loop(self) -> None:
        idx = 0
        while not self._stop.is_set():
            tick = time.monotonic()
            ppm_path = self.frames_dir / f"pebble_{idx:05d}.ppm"
            if self.controller.screendump(ppm_path):
                self.frames.append({"path": str(ppm_path), "t": round(time.monotonic() - self.t0, 4)})
                idx += 1
            remaining = self.interval - (time.monotonic() - tick)
            if remaining > 0:
                self._stop.wait(remaining)


class AndroidScreenRecorder:
    """Records Android screen via `screenrecord`, automatically falling back to `screencap` polling."""

    def __init__(
        self,
        adb: AdbDriver,
        work_dir: Path,
        t0: float,
        remote_mp4: str = "/sdcard/pebblepin-e2e-demo.mp4",
    ) -> None:
        self.adb = adb
        self.work_dir = work_dir
        self.t0 = t0
        self.remote_mp4 = remote_mp4
        self.local_mp4 = work_dir / "android-screenrecord.mp4"
        self.frames_dir = work_dir / "android-frames"
        self.timestamps_file = work_dir / "android-timestamps.json"
        self.mode = "screenrecord"
        self._proc: subprocess.Popen[str] | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._frames: list[dict[str, Any]] = []

    def start(self) -> None:
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.adb.run("shell", "rm", "-f", self.remote_mp4, check=False)
        self._proc = subprocess.Popen(
            [
                *self.adb._base(),
                "shell",
                "screenrecord",
                "--bit-rate",
                "4000000",
                "--time-limit",
                "180",
                self.remote_mp4,
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        time.sleep(1.5)
        if self._proc.poll() is not None:
            stderr = self._proc.stderr.read() if self._proc.stderr else ""
            log(
                f"adb screenrecord exited immediately (rc={self._proc.returncode}: {stderr.strip()}); "
                "falling back to timestamped adb screencap recording."
            )
            self.mode = "screencap"
            self._proc = None
            self.frames_dir.mkdir(parents=True, exist_ok=True)
            self._thread = threading.Thread(target=self._screencap_loop, daemon=True)
            self._thread.start()
        else:
            log("Started Android screen recording via `adb shell screenrecord`.")

    def _screencap_loop(self) -> None:
        idx = 0
        while not self._stop.is_set():
            tick = time.monotonic()
            out_png = self.frames_dir / f"android_{idx:05d}.png"
            try:
                self.adb.screencap(out_png)
                self._frames.append({"path": str(out_png), "t": round(time.monotonic() - self.t0, 4)})
                idx += 1
            except Exception:
                pass
            remaining = 0.45 - (time.monotonic() - tick)
            if remaining > 0:
                self._stop.wait(remaining)

    def stop(self) -> tuple[Path | None, Path | None]:
        if self.mode == "screenrecord" and self._proc is not None:
            # Send SIGINT to screenrecord on device so MP4 moov atom is finalized cleanly
            self.adb.run("shell", "pkill", "-2", "screenrecord", check=False)
            try:
                self._proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self._proc.terminate()
                self._proc.wait(timeout=5)
            time.sleep(1.0)
            pull = self.adb.run("pull", self.remote_mp4, str(self.local_mp4), check=False)
            if pull.returncode == 0 and self.local_mp4.is_file() and self.local_mp4.stat().st_size > 2048:
                return self.local_mp4, None
            log("Warning: screenrecord file missing/empty after pull; capturing fallback frame.")
            self.frames_dir.mkdir(parents=True, exist_ok=True)
            snap = self.frames_dir / "android_00000.png"
            self.adb.screencap(snap)
            self._frames = [{"path": str(snap), "t": 0.0}]
            self.timestamps_file.write_text(json.dumps(self._frames, indent=2), encoding="utf-8")
            return None, self.timestamps_file

        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=8.0)
        self.timestamps_file.write_text(json.dumps(self._frames, indent=2), encoding="utf-8")
        return None, self.timestamps_file


def http_get_json(url: str, timeout: float = 5.0) -> dict[str, Any]:
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def wait_for_bridge_health(port: int, timeout: float = 15.0) -> dict[str, Any]:
    deadline = time.time() + timeout
    url = f"http://127.0.0.1:{port}/health"
    while time.time() < deadline:
        try:
            data = http_get_json(url, timeout=2.0)
            if data.get("status") == "ok":
                return data
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(0.3)
    raise RuntimeError(f"Host Pebble bridge at {url} did not become healthy within {timeout}s")


def wait_for_bridge_events(
    port: int,
    predicate: Any,
    timeout: float = 20.0,
    poll_interval: float = 0.5,
) -> dict[str, Any]:
    deadline = time.time() + timeout
    last_payload: dict[str, Any] = {"events": [], "activePinIds": []}
    while time.time() < deadline:
        try:
            last_payload = http_get_json(f"http://127.0.0.1:{port}/events", timeout=3.0)
            if predicate(last_payload):
                return last_payload
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(poll_interval)
    return last_payload


def build_broadcast_post_notification_args(
    *,
    notif_id: int,
    title: str,
    text: str,
    tag: str | None = None,
    big_text: str | None = None,
    sub_text: str | None = None,
    ongoing: bool = False,
) -> list[str]:
    args = [
        "am",
        "broadcast",
        "-a",
        BRIDGE_POST_NOTIFICATION_ACTION,
        "-n",
        BRIDGE_NOTIFICATION_RECEIVER,
        "--ei",
        "id",
        str(notif_id),
        "--es",
        "title",
        title,
        "--es",
        "text",
        text,
        "--ez",
        "ongoing",
        "true" if ongoing else "false",
    ]
    if tag:
        args.extend(["--es", "tag", tag])
    if big_text:
        args.extend(["--es", "big_text", big_text])
    if sub_text:
        args.extend(["--es", "sub_text", sub_text])
    return args


def build_broadcast_cancel_notification_args(
    *,
    notif_id: int | None = None,
    tag: str | None = None,
    cancel_all: bool = False,
) -> list[str]:
    args = [
        "am",
        "broadcast",
        "-a",
        BRIDGE_CANCEL_NOTIFICATION_ACTION,
        "-n",
        BRIDGE_NOTIFICATION_RECEIVER,
    ]
    if cancel_all:
        args.extend(["--ez", "cancel_all", "true"])
    else:
        if notif_id is not None:
            args.extend(["--ei", "id", str(notif_id)])
        if tag:
            args.extend(["--es", "tag", tag])
    return args


def build_shell_post_notification_args(
    *,
    tag: str,
    title: str,
    text: str,
    big_text: str | None = None,
) -> list[str]:
    body = big_text or text
    return [
        "cmd",
        "notification",
        "post",
        "-S",
        "bigtext",
        "-t",
        title,
        tag,
        body,
    ]


def bridge_supports_demo_notifications(adb: AdbDriver) -> bool:
    out = adb.shell(
        "cmd",
        "package",
        "resolve-receiver",
        "--brief",
        "-a",
        BRIDGE_POST_NOTIFICATION_ACTION,
        check=False,
        timeout=15,
    )
    return BRIDGE_PACKAGE in out


def post_demo_notification(
    adb: AdbDriver,
    *,
    use_bridge: bool,
    notif_id: int,
    tag: str,
    title: str,
    text: str,
    big_text: str | None = None,
    sub_text: str | None = None,
    ongoing: bool = False,
) -> None:
    if use_bridge:
        args = build_broadcast_post_notification_args(
            notif_id=notif_id,
            tag=tag,
            title=title,
            text=text,
            big_text=big_text,
            sub_text=sub_text,
            ongoing=ongoing,
        )
    else:
        args = build_shell_post_notification_args(
            tag=tag,
            title=title,
            text=text,
            big_text=big_text,
        )
    adb.shell_quoted(*args, timeout=20, check=True)


def cancel_demo_notifications(
    adb: AdbDriver,
    *,
    use_bridge: bool,
    notif_id: int | None = None,
    tag: str | None = None,
    cancel_all: bool = False,
) -> None:
    if not use_bridge:
        return
    args = build_broadcast_cancel_notification_args(
        notif_id=notif_id,
        tag=tag,
        cancel_all=cancel_all,
    )
    adb.shell_quoted(*args, timeout=20, check=False)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# Inner crop of the Android phone display inside the 1280x720 side-by-side demo poster
# (left pane is x=58..598, y=106..658 with the portrait phone centered at x=328).
POSTER_ANDROID_CONTENT_CROP = (218, 120, 220, 524)
BLANK_LUMA_STD_THRESHOLD = 5.0
BLANK_COLOR_BUCKETS_THRESHOLD = 4


def _compute_rgb_stats(raw_rgb: bytes) -> dict[str, Any]:
    usable = len(raw_rgb) - (len(raw_rgb) % 3)
    if usable < 3:
        return {
            "pixelCount": 0,
            "meanLuma": 0.0,
            "minLuma": 0.0,
            "maxLuma": 0.0,
            "lumaStd": 0.0,
            "colorBuckets": 0,
            "isBlank": True,
        }
    pixel_count = usable // 3
    sum_luma = 0.0
    sum_luma_sq = 0.0
    min_luma = 255.0
    max_luma = 0.0
    buckets: set[int] = set()
    mv = memoryview(raw_rgb)[:usable]
    for i in range(0, usable, 3):
        r = mv[i]
        g = mv[i + 1]
        b = mv[i + 2]
        luma = 0.299 * r + 0.587 * g + 0.114 * b
        sum_luma += luma
        sum_luma_sq += luma * luma
        if luma < min_luma:
            min_luma = luma
        if luma > max_luma:
            max_luma = luma
        buckets.add(((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4))

    mean_luma = sum_luma / pixel_count
    variance = max(0.0, (sum_luma_sq / pixel_count) - (mean_luma * mean_luma))
    luma_std = math.sqrt(variance)
    color_buckets = len(buckets)
    return {
        "pixelCount": pixel_count,
        "meanLuma": round(mean_luma, 2),
        "minLuma": round(min_luma, 2),
        "maxLuma": round(max_luma, 2),
        "lumaStd": round(luma_std, 2),
        "colorBuckets": color_buckets,
        "isBlank": luma_std < BLANK_LUMA_STD_THRESHOLD or color_buckets < BLANK_COLOR_BUCKETS_THRESHOLD,
    }


def _decode_frame_rgb(
    media_path: Path,
    ffmpeg_bin: str = "ffmpeg",
    seek_seconds: float | None = None,
    crop: tuple[int, int, int, int] | None = None,
    sample_width: int = 160,
) -> bytes:
    filters: list[str] = []
    if crop is not None:
        cx, cy, cw, ch = crop
        filters.append(f"crop={cw}:{ch}:{cx}:{cy}")
    if sample_width > 0:
        filters.append(f"scale='min(iw,{sample_width})':-2")

    cmd = [ffmpeg_bin, "-v", "error"]
    if seek_seconds is not None and seek_seconds > 0:
        cmd.extend(["-ss", f"{seek_seconds:.2f}"])
    cmd.extend(["-i", str(media_path)])
    if filters:
        cmd.extend(["-vf", ",".join(filters)])
    cmd.extend(["-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"])

    proc = subprocess.run(cmd, capture_output=True, timeout=20, check=False)
    if proc.returncode != 0 or not proc.stdout:
        err = proc.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"ffmpeg failed decoding visual frame from {media_path}: {err}")
    return proc.stdout


def analyze_image_visual_content(
    image_path: Path,
    ffmpeg_bin: str = "ffmpeg",
    crop: tuple[int, int, int, int] | None = None,
    sample_width: int = 160,
) -> dict[str, Any]:
    raw_rgb = _decode_frame_rgb(image_path, ffmpeg_bin=ffmpeg_bin, crop=crop, sample_width=sample_width)
    stats = _compute_rgb_stats(raw_rgb)
    stats["path"] = str(image_path)
    return stats


def analyze_video_visual_content(
    video_path: Path,
    ffmpeg_bin: str = "ffmpeg",
    ffprobe_bin: str = "ffprobe",
    crop: tuple[int, int, int, int] | None = None,
    sample_width: int = 160,
) -> dict[str, Any]:
    probe = run_cmd(
        [
            ffprobe_bin,
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            str(video_path),
        ],
        timeout=20,
        check=True,
    )
    info = json.loads(probe.stdout)
    streams = [s for s in info.get("streams", []) if s.get("codec_type") == "video"]
    duration = float(
        info.get("format", {}).get("duration") or (streams[0].get("duration") if streams else 0.0) or 0.0
    )
    sample_times = [0.0] if duration <= 0.4 else [
        round(min(max(0.0, duration * frac), max(0.0, duration - 0.1)), 2)
        for frac in (0.25, 0.50, 0.75)
    ]

    samples: list[dict[str, Any]] = []
    for ts in sample_times:
        raw_rgb = _decode_frame_rgb(
            video_path,
            ffmpeg_bin=ffmpeg_bin,
            seek_seconds=ts,
            crop=crop,
            sample_width=sample_width,
        )
        st = _compute_rgb_stats(raw_rgb)
        st["t"] = ts
        samples.append(st)

    best = max(samples, key=lambda s: (s["lumaStd"], s["colorBuckets"]))
    non_blank = sum(1 for s in samples if not s["isBlank"])
    return {
        "path": str(video_path),
        "durationSeconds": round(duration, 2),
        "samplesChecked": len(samples),
        "nonBlankSamples": non_blank,
        "bestLumaStd": best["lumaStd"],
        "bestColorBuckets": best["colorBuckets"],
        "isBlank": non_blank == 0,
    }


def _compose_and_validate_video(
    *,
    scripts_dir: Path,
    platform: str,
    pebble_ts: Path,
    pebble_frame_count: int,
    android_mp4: Path | None,
    android_ts: Path | None,
    output_mp4: Path,
    poster_png: Path,
    title: str | None,
    ffmpeg_bin: str,
    ffprobe_bin: str,
    assertion_prefix: str,
    record_assertion: Any,
) -> tuple[dict[str, Any], dict[str, Any] | None, dict[str, Any]]:
    compose_cmd = [
        sys.executable,
        str(scripts_dir / "compose-demo-video.py"),
        "--pebble-timestamps",
        str(pebble_ts),
        "--platform",
        platform,
        "--output",
        str(output_mp4),
        "--poster",
        str(poster_png),
        "--ffmpeg-bin",
        ffmpeg_bin,
        "--ffprobe-bin",
        ffprobe_bin,
    ]
    if title:
        compose_cmd.extend(["--title", title])
    if android_mp4 is not None:
        compose_cmd.extend(["--android-video", str(android_mp4)])
    elif android_ts is not None:
        compose_cmd.extend(["--android-timestamps", str(android_ts)])
    else:
        raise RuntimeError("No Android video or frame sequence captured")

    compose_res = run_cmd(compose_cmd, timeout=180, check=True)
    video_meta = json.loads(compose_res.stdout)
    record_assertion(
        f"{assertion_prefix}side_by_side_video_rendered",
        output_mp4.is_file() and video_meta["durationSeconds"] > 1.0 and video_meta["codec"] == "h264",
        f"Rendered {output_mp4.name} ({video_meta['width']}x{video_meta['height']}, "
        f"{video_meta['durationSeconds']}s, {video_meta['sizeBytes'] // 1024} KB, "
        f"{pebble_frame_count} Pebble frames)",
    )

    android_video_stats: dict[str, Any] | None = None
    if android_mp4 is not None and android_mp4.is_file():
        android_video_stats = analyze_video_visual_content(
            android_mp4,
            ffmpeg_bin=ffmpeg_bin,
            ffprobe_bin=ffprobe_bin,
        )
    poster_android_stats = analyze_image_visual_content(
        poster_png,
        ffmpeg_bin=ffmpeg_bin,
        crop=POSTER_ANDROID_CONTENT_CROP,
    )
    android_video_ok = (android_video_stats is None or not android_video_stats["isBlank"]) and (
        not poster_android_stats["isBlank"]
    )
    record_assertion(
        f"{assertion_prefix}android_video_and_poster_non_blank",
        android_video_ok,
        f"Android recording & poster left pane rendered visible UI "
        f"(poster lumaStd={poster_android_stats['lumaStd']}, "
        f"colorBuckets={poster_android_stats['colorBuckets']}"
        + (
            f"; screenrecord {android_video_stats['nonBlankSamples']}/{android_video_stats['samplesChecked']} "
            f"non-blank samples, bestLumaStd={android_video_stats['bestLumaStd']}"
            if android_video_stats is not None
            else ""
        )
        + ")",
    )
    return video_meta, android_video_stats, poster_android_stats


def run_platform_e2e(
    platform: str,
    apk_path: Path,
    bridge_apk_path: Path,
    pbw_path: Path,
    output_dir: Path,
    bridge_port: int,
    adb_bin: str,
    pebble_bin: str,
    ffmpeg_bin: str,
    ffprobe_bin: str,
    serial: str | None,
    include_auto_pins: bool = True,
) -> dict[str, Any]:
    scripts_dir = Path(__file__).resolve().parent
    work_dir = output_dir / f"work-{platform}"
    screenshots_dir = output_dir / "screenshots" / platform
    logs_dir = output_dir / "logs"
    work_dir.mkdir(parents=True, exist_ok=True)
    screenshots_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)

    events_file = output_dir / f"bridge-events-{platform}.jsonl"
    if events_file.exists():
        events_file.unlink()

    adb = AdbDriver(adb_bin=adb_bin, serial=serial)
    pebble = PebbleQemuController(platform=platform, pebble_bin=pebble_bin)

    bridge_log_path = logs_dir / f"bridge-{platform}.log"
    bridge_log_fh = open(bridge_log_path, "w", encoding="utf-8")
    bridge_proc: subprocess.Popen[str] | None = None
    pebble_rec: PebbleFrameRecorder | None = None
    android_rec: AndroidScreenRecorder | None = None

    assertions: list[dict[str, Any]] = []

    def record_assertion(name: str, passed: bool, details: str) -> None:
        assertions.append({"name": name, "passed": passed, "details": details})
        status = "PASS" if passed else "FAIL"
        log(f"[{status}] {name}: {details}")
        if not passed:
            raise AssertionError(f"{name} failed: {details}")

    try:
        # 1. Deploy watchapp to Pebble QEMU
        pebble.install_and_boot(pbw_path)
        time.sleep(2.0)
        pebble_watchapp_png = pebble.capture_png(screenshots_dir / "01-pebble-watchapp.png", ffmpeg_bin=ffmpeg_bin)
        record_assertion(
            "pebble_watchapp_deployed",
            pebble_watchapp_png.is_file() and pebble_watchapp_png.stat().st_size > 200,
            f"Installed {pbw_path.name} on {platform} QEMU emulator",
        )

        # 2. Start host Pebble bridge daemon
        bridge_proc = subprocess.Popen(
            [
                sys.executable,
                str(scripts_dir / "pebble-emulator-bridge.py"),
                "--platform",
                platform,
                "--port",
                str(bridge_port),
                "--pebble-bin",
                pebble_bin,
                "--watchapp-uuid",
                WATCHAPP_UUID,
                "--events-file",
                str(events_file),
            ],
            stdout=bridge_log_fh,
            stderr=bridge_log_fh,
            text=True,
        )
        wait_for_bridge_health(bridge_port)
        log(f"Host Pebble bridge healthy on 127.0.0.1:{bridge_port}")

        # 3. Configure Android emulator & install both APKs
        adb.run("reverse", f"tcp:{bridge_port}", f"tcp:{bridge_port}")
        log(f"Installing {bridge_apk_path.name} and {apk_path.name} onto Android emulator...")
        adb.run("install", "-r", "-g", str(bridge_apk_path), timeout=120)
        adb.run("install", "-r", "-g", str(apk_path), timeout=120)
        adb.shell("pm", "clear", APP_PACKAGE)

        adb.shell(
            "am",
            "broadcast",
            "-a",
            BRIDGE_CONFIG_ACTION,
            "-n",
            BRIDGE_RECEIVER,
            "--es",
            "platform",
            platform,
            "--es",
            "bridge_url",
            f"http://127.0.0.1:{bridge_port}",
            "--ez",
            "connected",
            "true",
        )
        time.sleep(1.0)

        # 4. Start synchronized dual screen recording
        t0 = time.monotonic()
        pebble_rec = PebbleFrameRecorder(pebble, work_dir / "pebble-frames", t0=t0, fps=5.0)
        android_rec = AndroidScreenRecorder(adb, work_dir, t0=t0)
        pebble_rec.start()
        android_rec.start()

        # 5. Scene 1: Launch PebblePinManager & verify live PebbleKit2 watch connection
        log("Scene 1: Launching PebblePinManager and verifying PebbleKit2 connection...")
        adb.shell("am", "start", "-n", f"{APP_PACKAGE}/{APP_ACTIVITY}")
        adb.wait_for_node(timeout=25.0, text="Watch connected")
        expected_platform_text = "Pebble Time 2 (emery)" if platform == "emery" else "Pebble Time (basalt)"
        watch_label_node = adb.wait_for_node(timeout=15.0, text_contains=expected_platform_text)
        record_assertion(
            "android_watch_connected",
            True,
            f"Android UI reports connected watch: '{watch_label_node.text}'",
        )
        time.sleep(1.8)
        connected_png = screenshots_dir / "02-android-connected.png"
        adb.screencap(connected_png)
        connected_visual = analyze_image_visual_content(connected_png, ffmpeg_bin=ffmpeg_bin)
        record_assertion(
            "android_display_rendering",
            not connected_visual["isBlank"],
            f"Android screenshot {connected_png.name} rendered visible UI "
            f"(lumaStd={connected_visual['lumaStd']}, colorBuckets={connected_visual['colorBuckets']}, "
            f"meanLuma={connected_visual['meanLuma']})"
            if not connected_visual["isBlank"]
            else f"Android screenshot {connected_png.name} is blank/uniform "
            f"(lumaStd={connected_visual['lumaStd']}, colorBuckets={connected_visual['colorBuckets']}, "
            f"meanLuma={connected_visual['meanLuma']}); ensure ANDROID_IMAGE_TAG=default and GPU rendering are enabled",
        )

        # 6. Scene 2: Create & send custom timeline pin (Title, Description, +15 min, Flag, 30 min, Orange)
        log("Scene 2: Creating and sending customized timeline pin...")
        title_node = adb.find_or_scroll(direction="up", text_contains="Event Title")
        adb.tap(*title_node.center)
        time.sleep(0.5)
        adb.input_text("Demo Dentist Visit")

        desc_node = adb.find_or_scroll(direction="down", text_contains="Description")
        adb.tap(*desc_node.center)
        time.sleep(0.5)
        adb.input_text("Suite 402 bring card")
        adb.dismiss_ime_if_shown()

        offset_chip = adb.find_or_scroll(direction="down", text="+15 min")
        adb.tap(*offset_chip.center)
        time.sleep(0.5)

        # Icon grid tiles show Text(label) only when selected and contentDescription(label) when unselected
        flag_chip = adb.find_or_scroll(direction="down", label="Flag")
        adb.tap(*flag_chip.center)
        time.sleep(0.5)

        dur_chip = adb.find_or_scroll(direction="down", text="30 min")
        adb.tap(*dur_chip.center)
        time.sleep(0.5)

        orange_swatch = adb.find_or_scroll(direction="down", content_desc="Orange accent color")
        adb.tap(*orange_swatch.center)
        time.sleep(0.5)

        send_btn = adb.find_or_scroll(direction="down", text="Send to Pebble Timeline")
        adb.tap(*send_btn.center)

        adb.wait_for_node(timeout=20.0, text_contains="Pin sent", scroll_direction="down")
        adb.find_or_scroll(direction="down", text="Recent Sent Pins (1)")
        adb.screencap(screenshots_dir / "03-android-pin-sent.png")

        events_after_send = http_get_json(f"http://127.0.0.1:{bridge_port}/events")["events"]
        insert_events = [e for e in events_after_send if e["action"] == "insert" and e["returncode"] == 0]
        record_assertion(
            "pin_sent_over_pebblekit2",
            len(insert_events) == 1
            and insert_events[0]["pin"]["layout"]["title"] == "Demo Dentist Visit"
            and insert_events[0]["pin"].get("duration") == 30
            and insert_events[0]["pin"]["layout"].get("tinyIcon") == "system://images/NOTIFICATION_FLAG"
            and insert_events[0]["pin"]["layout"].get("backgroundColor") == "#FF5500",
            f"Bridge forwarded real PebbleKit2 insertTimelinePin -> Pebble QEMU (pinId={insert_events[0]['pinId'] if insert_events else 'none'})",
        )
        sent_pin_id = insert_events[0]["pinId"]

        # Navigate Pebble watch from watchapp -> watchface -> future Timeline -> open pin detail
        log("Navigating Pebble emulator to Timeline pin detail...")
        time.sleep(0.8)
        pebble.click("back")
        time.sleep(1.0)
        pebble.click("down")
        time.sleep(1.2)
        pebble.click("select")
        time.sleep(2.4)
        pebble_pin_png = pebble.capture_png(screenshots_dir / "03-pebble-pin-detail.png", ffmpeg_bin=ffmpeg_bin)
        record_assertion(
            "pebble_timeline_pin_rendered",
            file_sha256(pebble_pin_png) != file_sha256(pebble_watchapp_png),
            "Pebble watch screen transitioned from companion watchapp to inserted Timeline pin detail",
        )

        # 7. Scene 3: Edit pin in Android & verify updated pin on Pebble
        log("Scene 3: Editing sent pin and verifying live update...")
        edit_btn = adb.find_or_scroll(direction="down", text="Edit")
        adb.tap(*edit_btn.center)
        adb.wait_for_node(timeout=15.0, text="Edit Timeline Pin")

        title_edit = adb.find_or_scroll(direction="up", text_contains="Demo Dentist Visit")
        adb.tap(*title_edit.center)
        time.sleep(0.5)
        adb.clear_focused_text(max_chars=30)
        adb.input_text("Updated Dentist Visit")
        adb.dismiss_ime_if_shown()

        update_btn = adb.find_or_scroll(direction="down", text="Update Timeline Pin")
        adb.tap(*update_btn.center)
        adb.wait_for_node(timeout=20.0, text_contains="updated", scroll_direction="down")
        adb.find_or_scroll(direction="down", text="Updated Dentist Visit")
        adb.screencap(screenshots_dir / "04-android-pin-updated.png")

        events_after_edit = http_get_json(f"http://127.0.0.1:{bridge_port}/events")["events"]
        update_events = [e for e in events_after_edit if e["action"] == "update" and e["returncode"] == 0]
        record_assertion(
            "pin_updated_over_pebblekit2",
            len(update_events) == 1
            and update_events[0]["pinId"] == sent_pin_id
            and update_events[0]["pin"]["layout"]["title"] == "Updated Dentist Visit",
            f"Bridge forwarded updated pin '{sent_pin_id}' with new title 'Updated Dentist Visit'",
        )

        # Re-open pin on Pebble to show updated title
        pebble.click("back")
        time.sleep(1.0)
        pebble.click("select")
        time.sleep(2.4)
        pebble_updated_png = pebble.capture_png(screenshots_dir / "04-pebble-pin-updated.png", ffmpeg_bin=ffmpeg_bin)
        record_assertion(
            "pebble_timeline_pin_updated",
            file_sha256(pebble_updated_png) != file_sha256(pebble_pin_png),
            "Pebble watch pin detail screen visibly refreshed with updated pin text",
        )

        # 8. Scene 4: Delete pin in Android & verify removal on both Android and Pebble
        log("Scene 4: Deleting pin and verifying removal...")
        delete_btn = adb.find_or_scroll(direction="down", text="Delete")
        adb.tap(*delete_btn.center)
        adb.wait_for_node(timeout=15.0, text="Delete Timeline Pin?")
        # Confirm dialog's Delete button is the last matching "Delete" text node
        confirm_nodes = adb.find_nodes(text="Delete")
        adb.tap(*confirm_nodes[-1].center)
        adb.wait_for_node(timeout=20.0, text_contains="Pin deleted")
        time.sleep(1.0)
        adb.screencap(screenshots_dir / "05-android-pin-deleted.png")

        events_after_delete = http_get_json(f"http://127.0.0.1:{bridge_port}/events")
        delete_events = [
            e for e in events_after_delete["events"] if e["action"] == "delete" and e["returncode"] == 0
        ]
        record_assertion(
            "pin_deleted_over_pebblekit2",
            len(delete_events) == 1
            and delete_events[0]["pinId"] == sent_pin_id
            and len(events_after_delete["activePinIds"]) == 0,
            f"Bridge forwarded deleteTimelinePin for '{sent_pin_id}' and active pin list is now empty",
        )

        # Return to watchface and re-open future Timeline on Pebble to show pin removed
        pebble.click("back", repeat=2)
        time.sleep(1.0)
        pebble.click("down")
        time.sleep(2.0)
        pebble_deleted_png = pebble.capture_png(
            screenshots_dir / "05-pebble-timeline-after-delete.png",
            ffmpeg_bin=ffmpeg_bin,
        )
        record_assertion(
            "pebble_timeline_pin_removed",
            file_sha256(pebble_deleted_png) != file_sha256(pebble_updated_png),
            "Pebble watch Timeline refreshed after pin deletion",
        )

        # 9. Stop recordings and composite Video #1 (Manual Pins side-by-side MP4)
        log(f"Finalizing dual recordings and rendering side-by-side video for {platform}...")
        pebble_ts = work_dir / "pebble-timestamps.json"
        pebble_frames = pebble_rec.stop(pebble_ts)
        pebble_rec = None
        android_mp4, android_ts = android_rec.stop()
        android_rec = None

        demo_mp4 = output_dir / f"demo-{platform}.mp4"
        poster_png = output_dir / f"demo-{platform}-poster.png"

        video_meta, android_video_stats, poster_android_stats = _compose_and_validate_video(
            scripts_dir=scripts_dir,
            platform=platform,
            pebble_ts=pebble_ts,
            pebble_frame_count=len(pebble_frames),
            android_mp4=android_mp4,
            android_ts=android_ts,
            output_mp4=demo_mp4,
            poster_png=poster_png,
            title=None,
            ffmpeg_bin=ffmpeg_bin,
            ffprobe_bin=ffprobe_bin,
            assertion_prefix="",
            record_assertion=record_assertion,
        )

        # Verify all Manual Pins Android milestone screenshots are non-blank
        android_milestone_stats: dict[str, dict[str, Any]] = {}
        blank_milestones: list[str] = []
        for png_name in (
            "02-android-connected.png",
            "03-android-pin-sent.png",
            "04-android-pin-updated.png",
            "05-android-pin-deleted.png",
        ):
            png_path = screenshots_dir / png_name
            st = analyze_image_visual_content(png_path, ffmpeg_bin=ffmpeg_bin)
            android_milestone_stats[png_name] = st
            if st["isBlank"]:
                blank_milestones.append(
                    f"{png_name}(lumaStd={st['lumaStd']},buckets={st['colorBuckets']})"
                )

        record_assertion(
            "android_screenshots_non_blank",
            len(blank_milestones) == 0,
            "All 4 Android milestone screenshots contain visible UI "
            f"(min lumaStd={min(s['lumaStd'] for s in android_milestone_stats.values())}, "
            f"min colorBuckets={min(s['colorBuckets'] for s in android_milestone_stats.values())})"
            if not blank_milestones
            else f"Blank Android milestone screenshot(s): {', '.join(blank_milestones)}",
        )

        # 10. Second Video: Auto-Pins Setup & Live Incoming Notifications Exploration
        auto_pins_video_meta: dict[str, Any] | None = None
        auto_pins_android_video_stats: dict[str, Any] | None = None
        auto_pins_poster_android_stats: dict[str, Any] | None = None
        final_bridge_events = events_after_delete["events"]

        if include_auto_pins:
            log(f"================ Starting Video #2: Auto-Pins exploration ({platform}) ================")
            # Return Pebble from Future Timeline to clean watchface before starting Video #2
            pebble.click("back")
            time.sleep(1.0)
            pebble_watchface_png = pebble.capture_png(
                screenshots_dir / "06-pebble-watchface.png",
                ffmpeg_bin=ffmpeg_bin,
            )

            # Ensure Notification Access starts disabled so setup is demonstrated from scratch
            adb.shell("cmd", "notification", "disallow_listener", NOTIFICATION_LISTENER_COMPONENT, check=False)
            time.sleep(0.5)

            use_bridge_notifications = bridge_supports_demo_notifications(adb)
            if use_bridge_notifications:
                cancel_demo_notifications(adb, use_bridge=True, cancel_all=True)
                notif_source_package = BRIDGE_PACKAGE
                log(f"Using Emulator Bridge DemoNotificationReceiver ({notif_source_package}) for Auto-Pin notifications.")
            else:
                notif_source_package = "com.android.shell"
                log("DemoNotificationReceiver not found in bridge APK; falling back to `cmd notification post` (com.android.shell).")

            baseline_events = http_get_json(f"http://127.0.0.1:{bridge_port}/events")["events"]
            baseline_event_count = len(baseline_events)

            work_dir_auto = output_dir / f"work-{platform}-auto-pins"
            work_dir_auto.mkdir(parents=True, exist_ok=True)
            t0_auto = time.monotonic()
            pebble_rec = PebbleFrameRecorder(pebble, work_dir_auto / "pebble-frames", t0=t0_auto, fps=5.0)
            android_rec = AndroidScreenRecorder(
                adb,
                work_dir_auto,
                t0=t0_auto,
                remote_mp4="/sdcard/pebblepin-e2e-auto-pins.mp4",
            )
            pebble_rec.start()
            android_rec.start()

            # Scene A1: Navigate to Auto-Pins tab & grant Android Notification Access
            log("Scene A1: Opening Auto-Pins tab and granting Notification Access...")
            auto_tab = adb.wait_for_node(timeout=15.0, text="Auto-Pins")
            adb.tap(*auto_tab.center)
            adb.wait_for_node(timeout=15.0, text="Notification Auto-Pins")
            adb.wait_for_node(timeout=15.0, text="Grant Notification Access")
            adb.wait_for_node(timeout=15.0, text="No auto-created pins yet")
            time.sleep(1.2)
            adb.screencap(screenshots_dir / "06-android-autopins-initial.png")

            open_settings_btn = adb.find_or_scroll(direction="down", text="Open Notification Access Settings")
            adb.tap(*open_settings_btn.center)
            time.sleep(1.5)

            # Grant Notification Access while Android's real Notification Access Settings screen is shown
            adb.shell("cmd", "notification", "allow_listener", NOTIFICATION_LISTENER_COMPONENT)
            time.sleep(1.0)
            adb.shell("input", "keyevent", "4", check=False)
            time.sleep(1.0)

            if not adb.find_nodes(text="Notification Auto-Pins"):
                adb.shell("am", "start", "-n", f"{APP_PACKAGE}/{APP_ACTIVITY}")
                time.sleep(1.0)
                if not adb.find_nodes(text="Notification Auto-Pins"):
                    auto_tab_again = adb.wait_for_node(timeout=10.0, text="Auto-Pins")
                    adb.tap(*auto_tab_again.center)

            adb.wait_for_node(timeout=15.0, text="Notification Access Granted")
            listener_deadline = time.time() + 15.0
            refreshed_once = False
            listener_active = False
            while time.time() < listener_deadline:
                if adb.find_nodes(text_contains="Listener service active"):
                    listener_active = True
                    break
                if not refreshed_once and time.time() > listener_deadline - 10.0:
                    refreshed_once = True
                    refresh_nodes = adb.find_nodes(content_desc_contains="Refresh notification access")
                    if refresh_nodes:
                        adb.tap(*refresh_nodes[0].center)
                time.sleep(0.8)
            time.sleep(0.8)
            adb.screencap(screenshots_dir / "07-android-autopins-access-granted.png")
            record_assertion(
                "auto_pin_notification_access_granted",
                listener_active,
                "Notification Access granted and PebbleNotificationListenerService bound ('Listener service active.')",
            )

            # Scene A2: Allowlist notification source package, set include/exclude keywords & accent color
            log("Scene A2: Configuring allowed source app, include/exclude filters, and accent color...")
            pkg_field = adb.find_or_scroll(max_scrolls=16, direction="down", text_contains="Add package manually")
            adb.tap(*pkg_field.center)
            time.sleep(0.5)
            adb.input_text(notif_source_package)
            adb.dismiss_ime_if_shown()

            add_btn = adb.find_or_scroll(max_scrolls=4, direction="down", text="Add")
            adb.tap(*add_btn.center)
            time.sleep(0.8)

            include_field = adb.find_or_scroll(
                max_scrolls=10,
                direction="down",
                text_contains="Global include keywords",
            )
            adb.tap(*include_field.center)
            time.sleep(0.5)
            adb.input_text("flight,delivery")
            adb.dismiss_ime_if_shown()

            exclude_field = adb.find_or_scroll(
                max_scrolls=10,
                direction="down",
                text_contains="Global exclude keywords",
            )
            adb.tap(*exclude_field.center)
            time.sleep(0.5)
            adb.input_text("promo")
            adb.dismiss_ime_if_shown()

            save_filters_btn = adb.find_or_scroll(max_scrolls=10, direction="down", text="Save Global Filters")
            adb.tap(*save_filters_btn.center)
            adb.wait_for_node(timeout=15.0, text_contains="rules saved locally", scroll_direction="down")

            green_swatch = adb.find_or_scroll(
                max_scrolls=10,
                direction="down",
                content_desc="Green notification pin accent color",
            )
            adb.tap(*green_swatch.center)
            time.sleep(0.8)
            adb.screencap(screenshots_dir / "08-android-autopins-configured.png")

            # Scroll back up so Allowed Source Apps (1) and Recent Auto-Pins (0) are shown before notifications arrive
            adb.find_or_scroll(max_scrolls=16, direction="up", text="Allowed Source Apps (1)")
            time.sleep(0.8)
            adb.find_or_scroll(max_scrolls=16, direction="up", text="Recent Auto-Pins (0)")
            time.sleep(1.0)
            record_assertion(
                "auto_pin_setup_configured",
                True,
                f"Allowlisted '{notif_source_package}', saved include='flight,delivery' / exclude='promo', "
                "and selected Green default accent color",
            )

            # Scene A3: Live incoming notifications arrive -> filtered vs auto-pinned on Android & Pebble
            log("Scene A3: Sending excluded promo notification (should be ignored)...")
            post_demo_notification(
                adb,
                use_bridge=use_bridge_notifications,
                notif_id=2001,
                tag="demo-promo",
                title="Flight Promo Sale",
                text="50 percent off promo fares today",
            )
            time.sleep(3.0)
            events_after_promo = http_get_json(f"http://127.0.0.1:{bridge_port}/events")["events"]
            promo_auto_events = events_after_promo[baseline_event_count:]
            cancel_demo_notifications(adb, use_bridge=use_bridge_notifications, notif_id=2001, tag="demo-promo")
            time.sleep(0.8)
            record_assertion(
                "auto_pin_excluded_notification_ignored",
                len(promo_auto_events) == 0,
                "Excluded notification ('Flight Promo Sale') matched 'promo' exclude filter and created 0 pins",
            )

            log("Scene A3: Sending matching Flight notification (+15 min extracted time)...")
            post_demo_notification(
                adb,
                use_bridge=use_bridge_notifications,
                notif_id=2002,
                tag="demo-flight",
                title="Flight DL123 Boarding",
                text="Gate B12 departs in 15 min",
                big_text="Gate B12 departs in 15 min - Terminal 2",
            )
            events_after_flight_payload = wait_for_bridge_events(
                bridge_port,
                lambda payload: any(
                    e["action"] == "insert" and e["returncode"] == 0
                    for e in payload["events"][baseline_event_count:]
                ),
                timeout=20.0,
            )
            flight_inserts = [
                e
                for e in events_after_flight_payload["events"][baseline_event_count:]
                if e["action"] == "insert" and e["returncode"] == 0
            ]
            adb.wait_for_node(timeout=20.0, text="Recent Auto-Pins (1)")
            adb.wait_for_node(timeout=10.0, text="Flight DL123 Boarding")
            time.sleep(1.0)
            adb.screencap(screenshots_dir / "09-android-autopin-flight.png")

            flight_pin = flight_inserts[0] if flight_inserts else None
            record_assertion(
                "auto_pin_flight_created",
                flight_pin is not None
                and flight_pin["pinId"].startswith("ppm-notif-")
                and flight_pin["pin"]["layout"].get("title") == "Flight DL123 Boarding"
                and flight_pin["pin"]["layout"].get("tinyIcon") == "system://images/NOTIFICATION_FLAG"
                and flight_pin["pin"]["layout"].get("backgroundColor") == "#00AA00",
                f"Incoming Flight notification auto-created Pebble Timeline pin "
                f"(pinId={flight_pin['pinId'] if flight_pin else 'none'}, color="
                f"{flight_pin['pin']['layout'].get('backgroundColor') if flight_pin else 'none'})",
            )

            # Open the newly auto-created Flight pin on Pebble Timeline
            pebble.click("down")
            time.sleep(1.2)
            pebble.click("select")
            time.sleep(2.4)
            pebble_flight_png = pebble.capture_png(
                screenshots_dir / "09-pebble-autopin-flight.png",
                ffmpeg_bin=ffmpeg_bin,
            )
            record_assertion(
                "pebble_autopin_flight_rendered",
                file_sha256(pebble_flight_png) != file_sha256(pebble_watchface_png),
                "Pebble watch displayed auto-created 'Flight DL123 Boarding' Timeline pin detail",
            )

            log("Scene A3: Sending second matching Delivery notification (+30 min extracted time)...")
            post_demo_notification(
                adb,
                use_bridge=use_bridge_notifications,
                notif_id=2003,
                tag="demo-delivery",
                title="Package Delivery",
                text="Courier arriving in 30 min",
                big_text="Courier arriving in 30 min - Front desk",
            )
            events_after_delivery_payload = wait_for_bridge_events(
                bridge_port,
                lambda payload: len(
                    [
                        e
                        for e in payload["events"][baseline_event_count:]
                        if e["action"] == "insert" and e["returncode"] == 0
                    ]
                )
                >= 2,
                timeout=20.0,
            )
            auto_inserts = [
                e
                for e in events_after_delivery_payload["events"][baseline_event_count:]
                if e["action"] == "insert" and e["returncode"] == 0
            ]
            adb.wait_for_node(timeout=20.0, text="Recent Auto-Pins (2)")
            adb.wait_for_node(timeout=10.0, text="Package Delivery")
            time.sleep(1.0)
            adb.screencap(screenshots_dir / "10-android-autopin-delivery.png")

            delivery_pin = auto_inserts[1] if len(auto_inserts) >= 2 else None
            record_assertion(
                "auto_pin_delivery_created",
                delivery_pin is not None
                and flight_pin is not None
                and delivery_pin["pinId"].startswith("ppm-notif-")
                and delivery_pin["pinId"] != flight_pin["pinId"]
                and delivery_pin["pin"]["layout"].get("title") == "Package Delivery"
                and delivery_pin["pin"]["layout"].get("backgroundColor") == "#00AA00",
                f"Incoming Delivery notification auto-created second Pebble Timeline pin "
                f"(pinId={delivery_pin['pinId'] if delivery_pin else 'none'})",
            )

            # Navigate Pebble from Flight pin detail -> Timeline list -> Delivery pin detail
            pebble.click("back")
            time.sleep(1.0)
            pebble.click("down")
            time.sleep(1.2)
            pebble.click("select")
            time.sleep(2.4)
            pebble_delivery_png = pebble.capture_png(
                screenshots_dir / "10-pebble-autopin-delivery.png",
                ffmpeg_bin=ffmpeg_bin,
            )
            record_assertion(
                "pebble_autopin_delivery_rendered",
                file_sha256(pebble_delivery_png) != file_sha256(pebble_flight_png),
                "Pebble watch navigated to second auto-created 'Package Delivery' Timeline pin detail",
            )

            # Stop Video #2 recordings & compose side-by-side MP4
            log(f"Finalizing Auto-Pins dual recordings and rendering second video for {platform}...")
            pebble_auto_ts = work_dir_auto / "pebble-timestamps.json"
            pebble_auto_frames = pebble_rec.stop(pebble_auto_ts)
            pebble_rec = None
            android_auto_mp4, android_auto_ts = android_rec.stop()
            android_rec = None

            cancel_demo_notifications(adb, use_bridge=use_bridge_notifications, cancel_all=True)

            auto_demo_mp4 = output_dir / f"demo-{platform}-auto-pins.mp4"
            auto_poster_png = output_dir / f"demo-{platform}-auto-pins-poster.png"
            platform_label = "Pebble Time 2 (emery)" if platform == "emery" else "Pebble Time (basalt)"

            (
                auto_pins_video_meta,
                auto_pins_android_video_stats,
                auto_pins_poster_android_stats,
            ) = _compose_and_validate_video(
                scripts_dir=scripts_dir,
                platform=platform,
                pebble_ts=pebble_auto_ts,
                pebble_frame_count=len(pebble_auto_frames),
                android_mp4=android_auto_mp4,
                android_ts=android_auto_ts,
                output_mp4=auto_demo_mp4,
                poster_png=auto_poster_png,
                title=f"PebblePinManager Auto-Pins Demo  |  Android + {platform_label}",
                ffmpeg_bin=ffmpeg_bin,
                ffprobe_bin=ffprobe_bin,
                assertion_prefix="auto_pins_",
                record_assertion=record_assertion,
            )

            auto_blank_milestones: list[str] = []
            auto_milestone_names = (
                "06-android-autopins-initial.png",
                "07-android-autopins-access-granted.png",
                "08-android-autopins-configured.png",
                "09-android-autopin-flight.png",
                "10-android-autopin-delivery.png",
            )
            for png_name in auto_milestone_names:
                png_path = screenshots_dir / png_name
                st = analyze_image_visual_content(png_path, ffmpeg_bin=ffmpeg_bin)
                android_milestone_stats[png_name] = st
                if st["isBlank"]:
                    auto_blank_milestones.append(
                        f"{png_name}(lumaStd={st['lumaStd']},buckets={st['colorBuckets']})"
                    )

            record_assertion(
                "auto_pins_android_screenshots_non_blank",
                len(auto_blank_milestones) == 0,
                f"All {len(auto_milestone_names)} Auto-Pins Android milestone screenshots contain visible UI "
                f"(min lumaStd={min(android_milestone_stats[n]['lumaStd'] for n in auto_milestone_names)}, "
                f"min colorBuckets={min(android_milestone_stats[n]['colorBuckets'] for n in auto_milestone_names)})"
                if not auto_blank_milestones
                else f"Blank Auto-Pins Android milestone screenshot(s): {', '.join(auto_blank_milestones)}",
            )

            final_bridge_events = events_after_delivery_payload["events"]

        videos_list: list[dict[str, Any]] = [
            {"scenario": "manual-pins", "label": "Manual Pins", **video_meta},
        ]
        if auto_pins_video_meta is not None:
            videos_list.append(
                {"scenario": "auto-pins", "label": "Auto-Pins", **auto_pins_video_meta}
            )

        visual_checks: dict[str, Any] = {
            "androidScreenshots": android_milestone_stats,
            "androidVideo": android_video_stats,
            "posterAndroidPane": poster_android_stats,
        }
        if auto_pins_video_meta is not None:
            visual_checks["autoPinsAndroidVideo"] = auto_pins_android_video_stats
            visual_checks["autoPinsPosterAndroidPane"] = auto_pins_poster_android_stats

        report: dict[str, Any] = {
            "platform": platform,
            "status": "passed",
            "assertions": assertions,
            "bridgeEvents": final_bridge_events,
            "video": video_meta,
            "videos": videos_list,
            "visualChecks": visual_checks,
            "screenshots": sorted(str(p.relative_to(output_dir)) for p in screenshots_dir.glob("*.png")),
        }
        if auto_pins_video_meta is not None:
            report["autoPinsVideo"] = auto_pins_video_meta
        (output_dir / f"e2e-report-{platform}.json").write_text(
            json.dumps(report, indent=2),
            encoding="utf-8",
        )
        return report

    except Exception:
        # Capture failure diagnostics (screenshot + UI hierarchy XML) for CI artifact upload
        try:
            adb.screencap(screenshots_dir / "99-android-failure.png")
            log(f"Saved failure screenshot: {screenshots_dir / '99-android-failure.png'}")
        except Exception:
            pass
        try:
            failure_xml = adb.dump_ui_xml()
            dump_path = logs_dir / f"android-ui-failure-{platform}.xml"
            dump_path.write_text(failure_xml, encoding="utf-8")
            log(f"Saved failure UI hierarchy dump: {dump_path}")
        except Exception:
            pass
        raise

    finally:
        if pebble_rec is not None:
            try:
                pebble_rec.stop(work_dir / "pebble-timestamps.json")
            except Exception:
                pass
        if android_rec is not None:
            try:
                android_rec.stop()
            except Exception:
                pass
        if bridge_proc is not None:
            bridge_proc.send_signal(signal.SIGTERM)
            try:
                bridge_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                bridge_proc.kill()
        bridge_log_fh.close()
        run_cmd([pebble_bin, "kill", "--force"], check=False, timeout=15)


def write_summary(output_dir: Path, reports: list[dict[str, Any]]) -> Path:
    lines = [
        "# PebblePinManager Emulator E2E & Demo Video Report",
        "",
        "| Pebble Platform | Scenario | Status | Assertions | Video Resolution | Duration | Size |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in reports:
        passed = sum(1 for a in r["assertions"] if a["passed"])
        total = len(r["assertions"])
        videos = r.get("videos")
        if not isinstance(videos, list) or not videos:
            videos = [{"label": "Manual Pins", **r["video"]}]
        for v in videos:
            label = v.get("label") or v.get("scenario") or "Manual Pins"
            lines.append(
                f"| `{r['platform']}` | {label} | **{r['status'].upper()}** | {passed}/{total} | "
                f"`{v['width']}x{v['height']}` ({v['codec']}) | {v['durationSeconds']}s | "
                f"{v['sizeBytes'] // 1024} KB |"
            )
    lines.append("")
    for r in reports:
        lines.append(f"## Assertions — `{r['platform']}`")
        lines.append("")
        for a in r["assertions"]:
            mark = "✅" if a["passed"] else "❌"
            lines.append(f"- {mark} **`{a['name']}`**: {a['details']}")
        lines.append("")

    summary_path = output_dir / "e2e-summary.md"
    summary_text = "\n".join(lines)
    summary_path.write_text(summary_text, encoding="utf-8")

    gh_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if gh_summary:
        with open(gh_summary, "a", encoding="utf-8") as fh:
            fh.write(summary_text + "\n")
    return summary_path


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run end-to-end PebblePinManager emulator tests and record side-by-side demo videos."
    )
    parser.add_argument(
        "--platforms",
        default="basalt",
        help="Comma-separated Pebble platforms to test: basalt, emery, or basalt,emery (or 'both')",
    )
    parser.add_argument("--apk", type=Path, required=True, help="Path to pebblepinmanager-android-debug.apk")
    parser.add_argument("--bridge-apk", type=Path, required=True, help="Path to emulator-bridge-debug.apk")
    parser.add_argument("--pbw", type=Path, required=True, help="Path to pebblepinmanager-watchapp.pbw")
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts"))
    parser.add_argument("--bridge-port", type=int, default=18442)
    parser.add_argument("--adb-bin", default="adb")
    parser.add_argument("--pebble-bin", default="pebble")
    parser.add_argument("--ffmpeg-bin", default="ffmpeg")
    parser.add_argument("--ffprobe-bin", default="ffprobe")
    parser.add_argument("--adb-serial", default=None)
    parser.add_argument(
        "--skip-auto-pins",
        action="store_true",
        help="Skip the second Auto-Pins setup & incoming notifications E2E video",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    raw_platforms = args.platforms.strip().lower()
    if raw_platforms == "both":
        platforms = ["basalt", "emery"]
    else:
        platforms = [p.strip() for p in raw_platforms.split(",") if p.strip()]
    for p in platforms:
        if p not in ("basalt", "emery"):
            raise SystemExit(f"Unsupported Pebble platform: {p} (expected basalt or emery)")

    for label, path in [("APK", args.apk), ("Bridge APK", args.bridge_apk), ("PBW", args.pbw)]:
        if not path.is_file():
            raise SystemExit(f"{label} file not found: {path}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    reports: list[dict[str, Any]] = []
    for platform in platforms:
        log(f"================ Starting E2E run for Pebble {platform} ================")
        report = run_platform_e2e(
            platform=platform,
            apk_path=args.apk,
            bridge_apk_path=args.bridge_apk,
            pbw_path=args.pbw,
            output_dir=args.output_dir,
            bridge_port=args.bridge_port,
            adb_bin=args.adb_bin,
            pebble_bin=args.pebble_bin,
            ffmpeg_bin=args.ffmpeg_bin,
            ffprobe_bin=args.ffprobe_bin,
            serial=args.adb_serial,
            include_auto_pins=not args.skip_auto_pins,
        )
        reports.append(report)

    summary_path = write_summary(args.output_dir, reports)
    log(f"All E2E platform runs passed! Summary written to {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
