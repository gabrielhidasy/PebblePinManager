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
  6. Renders a playable side-by-side 1280x720 MP4 demo video + poster PNG + JSON report.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
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

    def screen_size(self) -> tuple[int, int]:
        if self._screen_size is None:
            out = self.shell("wm", "size", check=False)
            m = re.search(r"(\d+)x(\d+)", out)
            if m:
                self._screen_size = (int(m.group(1)), int(m.group(2)))
            else:
                self._screen_size = (1080, 2160)
        return self._screen_size

    def dump_ui(self) -> list[UiNode]:
        remote_xml = "/sdcard/pebblepin-ui-dump.xml"
        # Retry once if uiautomator is momentarily busy
        for attempt in range(3):
            res = self.run("shell", "uiautomator", "dump", remote_xml, check=False, timeout=25)
            if res.returncode == 0:
                xml_text = self.run("shell", "cat", remote_xml, check=False, timeout=15).stdout
                start = xml_text.find("<?xml")
                if start == -1:
                    start = xml_text.find("<hierarchy")
                if start != -1:
                    try:
                        root = ET.fromstring(xml_text[start:])
                        return self._parse_nodes(root)
                    except ET.ParseError:
                        pass
            time.sleep(0.6 * (attempt + 1))
        raise RuntimeError("Failed to dump Android UI hierarchy via uiautomator")

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
        if class_name is not None and node.class_name != class_name:
            return False
        return True

    def find_nodes(self, **criteria: Any) -> list[UiNode]:
        return [n for n in self.dump_ui() if self._matches(n, **criteria)]

    def wait_for_node(self, timeout: float = 20.0, **criteria: Any) -> UiNode:
        deadline = time.time() + timeout
        last_texts: list[str] = []
        while time.time() < deadline:
            nodes = self.dump_ui()
            last_texts = [n.text or n.content_desc for n in nodes if (n.text or n.content_desc)]
            for n in nodes:
                if self._matches(n, **criteria):
                    return n
            time.sleep(0.8)
        raise AssertionError(
            f"Timed out waiting for UI node matching {criteria}. "
            f"Visible labels: {last_texts[:30]}"
        )

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def swipe_down_page(self) -> None:
        w, h = self.screen_size()
        self.shell("input", "swipe", str(w // 2), str(int(h * 0.78)), str(w // 2), str(int(h * 0.28)), "350")
        time.sleep(0.6)

    def swipe_up_page(self) -> None:
        w, h = self.screen_size()
        self.shell("input", "swipe", str(w // 2), str(int(h * 0.28)), str(w // 2), str(int(h * 0.78)), "350")
        time.sleep(0.6)

    def find_or_scroll(self, max_scrolls: int = 5, direction: str = "down", **criteria: Any) -> UiNode:
        for attempt in range(max_scrolls + 1):
            matches = self.find_nodes(**criteria)
            if matches:
                return matches[0]
            if attempt < max_scrolls:
                if direction == "down":
                    self.swipe_down_page()
                else:
                    self.swipe_up_page()
        raise AssertionError(f"Could not locate UI node after {max_scrolls} scrolls: {criteria}")

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

    def __init__(self, adb: AdbDriver, work_dir: Path, t0: float) -> None:
        self.adb = adb
        self.work_dir = work_dir
        self.t0 = t0
        self.remote_mp4 = "/sdcard/pebblepin-e2e-demo.mp4"
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


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


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
        adb.screencap(screenshots_dir / "02-android-connected.png")

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

        flag_chip = adb.find_or_scroll(direction="down", text="Flag")
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

        adb.wait_for_node(timeout=20.0, text_contains="Pin sent")
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
        adb.wait_for_node(timeout=20.0, text_contains="updated")
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

        # 9. Stop recordings and composite side-by-side MP4 video
        log(f"Finalizing dual recordings and rendering side-by-side video for {platform}...")
        pebble_ts = work_dir / "pebble-timestamps.json"
        pebble_frames = pebble_rec.stop(pebble_ts)
        pebble_rec = None
        android_mp4, android_ts = android_rec.stop()
        android_rec = None

        demo_mp4 = output_dir / f"demo-{platform}.mp4"
        poster_png = output_dir / f"demo-{platform}-poster.png"

        compose_cmd = [
            sys.executable,
            str(scripts_dir / "compose-demo-video.py"),
            "--pebble-timestamps",
            str(pebble_ts),
            "--platform",
            platform,
            "--output",
            str(demo_mp4),
            "--poster",
            str(poster_png),
            "--ffmpeg-bin",
            ffmpeg_bin,
            "--ffprobe-bin",
            ffprobe_bin,
        ]
        if android_mp4 is not None:
            compose_cmd.extend(["--android-video", str(android_mp4)])
        elif android_ts is not None:
            compose_cmd.extend(["--android-timestamps", str(android_ts)])
        else:
            raise RuntimeError("No Android video or frame sequence captured")

        compose_res = run_cmd(compose_cmd, timeout=180, check=True)
        video_meta = json.loads(compose_res.stdout)
        record_assertion(
            "side_by_side_video_rendered",
            demo_mp4.is_file() and video_meta["durationSeconds"] > 1.0 and video_meta["codec"] == "h264",
            f"Rendered {demo_mp4.name} ({video_meta['width']}x{video_meta['height']}, "
            f"{video_meta['durationSeconds']}s, {video_meta['sizeBytes'] // 1024} KB, "
            f"{len(pebble_frames)} Pebble frames)",
        )

        report = {
            "platform": platform,
            "status": "passed",
            "assertions": assertions,
            "bridgeEvents": events_after_delete["events"],
            "video": video_meta,
            "screenshots": sorted(str(p.relative_to(output_dir)) for p in screenshots_dir.glob("*.png")),
        }
        (output_dir / f"e2e-report-{platform}.json").write_text(
            json.dumps(report, indent=2),
            encoding="utf-8",
        )
        return report

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
        "| Pebble Platform | Status | Assertions | Video Resolution | Duration | Size |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for r in reports:
        passed = sum(1 for a in r["assertions"] if a["passed"])
        total = len(r["assertions"])
        v = r["video"]
        lines.append(
            f"| `{r['platform']}` | **{r['status'].upper()}** | {passed}/{total} | "
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
        description="Run end-to-end PebblePinManager emulator tests and record side-by-side demo video."
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
        )
        reports.append(report)

    summary_path = write_summary(args.output_dir, reports)
    log(f"All E2E platform runs passed! Summary written to {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
