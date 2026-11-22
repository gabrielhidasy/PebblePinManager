#!/usr/bin/env python3
"""Host-side HTTP bridge between the Android PebbleKit2 Emulator Bridge APK and Pebble QEMU.

The Android Emulator Bridge APK receives real PebbleKit Android 2 IPC calls from the
unmodified PebblePinManager app and sends HTTP requests to http://127.0.0.1:<port>
(forwarded from the Android emulator via `adb reverse tcp:<port> tcp:<port>`).

This daemon translates each request into `pebble insert-pin` / `pebble delete-pin`
against the running Pebble QEMU emulator (`basalt` or `emery`) and appends a structured
JSONL audit log for automated E2E assertions.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

DEFAULT_WATCHAPP_UUID = "e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042"


def utc_now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_pebble_timeline_pin(payload: dict[str, Any]) -> dict[str, Any]:
    """Build a valid Pebble Timeline JSON pin document from a bridge payload."""
    pin_id = str(payload.get("id", "")).strip()
    if not pin_id:
        raise ValueError("Pin 'id' is required")

    pin_time = str(payload.get("time", "")).strip()
    if not pin_time:
        raise ValueError("Pin 'time' (ISO-8601 UTC) is required")

    raw_layout = payload.get("layout")
    if not isinstance(raw_layout, dict):
        raise ValueError("Pin 'layout' object is required")

    layout: dict[str, Any] = {
        "type": str(raw_layout.get("type") or "genericPin"),
    }
    for key in (
        "title",
        "subtitle",
        "body",
        "tinyIcon",
        "smallIcon",
        "largeIcon",
        "primaryColor",
        "secondaryColor",
        "backgroundColor",
    ):
        val = raw_layout.get(key)
        if val is not None and str(val).strip() != "":
            layout[key] = str(val)

    pin_doc: dict[str, Any] = {
        "id": pin_id,
        "time": pin_time,
        "layout": layout,
    }
    duration = payload.get("duration")
    if duration is not None:
        dur_int = int(duration)
        if dur_int > 0:
            pin_doc["duration"] = dur_int

    return pin_doc


class BridgeState:
    def __init__(
        self,
        platform: str,
        pebble_bin: str,
        watchapp_uuid: str,
        events_file: str | None,
        dry_run: bool,
        strict_delete: bool,
    ) -> None:
        self.platform = platform
        self.pebble_bin = pebble_bin
        self.watchapp_uuid = watchapp_uuid
        self.events_file = events_file
        self.dry_run = dry_run
        self.strict_delete = strict_delete
        self.lock = threading.Lock()
        self.active_pins: dict[str, dict[str, Any]] = {}
        self.events: list[dict[str, Any]] = []

    def _record_event(self, event: dict[str, Any]) -> None:
        self.events.append(event)
        if self.events_file:
            os.makedirs(os.path.dirname(os.path.abspath(self.events_file)), exist_ok=True)
            with open(self.events_file, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(event, sort_keys=True) + "\n")

    def _run_pebble(self, args: list[str]) -> tuple[int, str, str]:
        cmd = [self.pebble_bin, *args]
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()

    def insert_pin(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        try:
            pin_doc = build_pebble_timeline_pin(payload)
        except (TypeError, ValueError) as exc:
            return 400, {"status": "error", "message": str(exc)}

        pin_id = pin_doc["id"]
        app_uuid = str(payload.get("watchappUuid") or self.watchapp_uuid).strip()
        calling_package = payload.get("callingPackage")

        with self.lock:
            action = "update" if pin_id in self.active_pins else "insert"
            stdout = ""
            stderr = ""
            rc = 0
            if not self.dry_run:
                tmp_path = None
                try:
                    with tempfile.NamedTemporaryFile(
                        mode="w",
                        suffix=".json",
                        prefix="pebble-pin-",
                        delete=False,
                        encoding="utf-8",
                    ) as tmp:
                        json.dump(pin_doc, tmp, indent=2)
                        tmp_path = tmp.name

                    rc, stdout, stderr = self._run_pebble(
                        [
                            "insert-pin",
                            "--emulator",
                            self.platform,
                            "--app-uuid",
                            app_uuid,
                            "--id",
                            pin_id,
                            tmp_path,
                        ]
                    )
                finally:
                    if tmp_path and os.path.exists(tmp_path):
                        os.remove(tmp_path)

            event = {
                "timestamp": utc_now_iso(),
                "action": action,
                "platform": self.platform,
                "pinId": pin_id,
                "watchappUuid": app_uuid,
                "callingPackage": calling_package,
                "pin": pin_doc,
                "dryRun": self.dry_run,
                "returncode": rc,
                "stdout": stdout,
                "stderr": stderr,
            }
            self._record_event(event)

            if rc != 0:
                return 502, {
                    "status": "error",
                    "action": action,
                    "id": pin_id,
                    "message": stderr or stdout or f"pebble insert-pin exited with {rc}",
                }

            self.active_pins[pin_id] = pin_doc
            return 200, {
                "status": "ok",
                "action": action,
                "id": pin_id,
                "platform": self.platform,
            }

    def delete_pin(
        self,
        pin_id: str,
        watchapp_uuid: str | None = None,
        calling_package: str | None = None,
    ) -> tuple[int, dict[str, Any]]:
        clean_id = pin_id.strip()
        if not clean_id:
            return 400, {"status": "error", "message": "Pin ID is required"}

        with self.lock:
            existed = clean_id in self.active_pins
            if self.strict_delete and not existed:
                return 404, {"status": "not_found", "id": clean_id}

            stdout = ""
            stderr = ""
            rc = 0
            if not self.dry_run:
                rc, stdout, stderr = self._run_pebble(
                    [
                        "delete-pin",
                        "--emulator",
                        self.platform,
                        "--id",
                        clean_id,
                    ]
                )

            event = {
                "timestamp": utc_now_iso(),
                "action": "delete",
                "platform": self.platform,
                "pinId": clean_id,
                "watchappUuid": watchapp_uuid or self.watchapp_uuid,
                "callingPackage": calling_package,
                "existed": existed,
                "dryRun": self.dry_run,
                "returncode": rc,
                "stdout": stdout,
                "stderr": stderr,
            }
            self._record_event(event)

            if rc != 0:
                return 502, {
                    "status": "error",
                    "action": "delete",
                    "id": clean_id,
                    "message": stderr or stdout or f"pebble delete-pin exited with {rc}",
                }

            self.active_pins.pop(clean_id, None)
            return 200, {
                "status": "ok",
                "action": "delete",
                "id": clean_id,
                "platform": self.platform,
            }


def make_handler(state: BridgeState) -> type[BaseHTTPRequestHandler]:
    class BridgeHandler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            sys.stderr.write(
                f"[pebble-bridge:{state.platform}] {self.address_string()} - "
                + (format % args)
                + "\n"
            )
            sys.stderr.flush()

        def _send_json(self, status_code: int, payload: dict[str, Any]) -> None:
            encoded = json.dumps(payload).encode("utf-8")
            self.send_response(status_code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path == "/health":
                with state.lock:
                    self._send_json(
                        200,
                        {
                            "status": "ok",
                            "platform": state.platform,
                            "dryRun": state.dry_run,
                            "activePinIds": sorted(state.active_pins.keys()),
                            "eventCount": len(state.events),
                        },
                    )
                return

            if parsed.path == "/events":
                with state.lock:
                    self._send_json(
                        200,
                        {
                            "platform": state.platform,
                            "activePinIds": sorted(state.active_pins.keys()),
                            "events": list(state.events),
                        },
                    )
                return

            self._send_json(404, {"status": "not_found", "path": parsed.path})

        def do_POST(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path != "/pins":
                self._send_json(404, {"status": "not_found", "path": parsed.path})
                return

            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length > 0 else b""
            try:
                payload = json.loads(raw.decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("JSON body must be an object")
            except (UnicodeDecodeError, ValueError) as exc:
                self._send_json(400, {"status": "error", "message": f"Invalid JSON: {exc}"})
                return

            code, body = state.insert_pin(payload)
            self._send_json(code, body)

        def do_DELETE(self) -> None:
            parsed = urlparse(self.path)
            if not parsed.path.startswith("/pins/"):
                self._send_json(404, {"status": "not_found", "path": parsed.path})
                return

            pin_id = unquote(parsed.path[len("/pins/") :])
            qs = parse_qs(parsed.query)
            watchapp_uuid = qs.get("watchappUuid", [None])[0]
            calling_package = qs.get("callingPackage", [None])[0]

            code, body = state.delete_pin(
                pin_id=pin_id,
                watchapp_uuid=watchapp_uuid,
                calling_package=calling_package,
            )
            self._send_json(code, body)

    return BridgeHandler


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Bridge PebbleKit2 Emulator Bridge HTTP requests to Pebble QEMU timeline commands."
    )
    parser.add_argument(
        "--platform",
        default="basalt",
        choices=["basalt", "emery"],
        help="Target Pebble QEMU emulator platform (default: basalt)",
    )
    parser.add_argument(
        "--host",
        default="127.0.0.1",
        help="Bind host (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=18442,
        help="Bind port (default: 18442)",
    )
    parser.add_argument(
        "--pebble-bin",
        default="pebble",
        help="Path to pebble CLI binary (default: pebble)",
    )
    parser.add_argument(
        "--watchapp-uuid",
        default=DEFAULT_WATCHAPP_UUID,
        help="Default Pebble watchapp UUID",
    )
    parser.add_argument(
        "--events-file",
        default=None,
        help="Optional path to append JSONL bridge events",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Record events without invoking pebble CLI (for unit/mock testing)",
    )
    parser.add_argument(
        "--strict-delete",
        action="store_true",
        help="Return HTTP 404 when deleting a pin ID not seen in this bridge session",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    state = BridgeState(
        platform=args.platform,
        pebble_bin=args.pebble_bin,
        watchapp_uuid=args.watchapp_uuid,
        events_file=args.events_file,
        dry_run=args.dry_run,
        strict_delete=args.strict_delete,
    )
    server = ThreadingHTTPServer((args.host, args.port), make_handler(state))
    sys.stderr.write(
        f"[pebble-bridge:{args.platform}] Listening on http://{args.host}:{args.port} "
        f"(dry_run={args.dry_run})\n"
    )
    sys.stderr.flush()
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
