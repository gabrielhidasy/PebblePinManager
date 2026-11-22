#!/usr/bin/env python3
"""Composite synchronized Android + Pebble emulator recordings into a side-by-side MP4 demo.

Accepts:
  - Android stream: an MP4 from `adb shell screenrecord` OR a directory of timestamped
    `adb exec-out screencap` PNG frames (`android-timestamps.json`).
  - Pebble stream: a directory of timestamped QEMU monitor `screendump` PPM/PNG frames
    (`pebble-timestamps.json`).

Produces:
  - A crisp 1280x720 H.264 MP4 (`yuv420p`, `+faststart`) with Android on the left and
    nearest-neighbor upscaled Pebble watch display inside a styled watch bezel on the right.
  - Optional hero poster PNG frame for CI job summaries & previews.
  - Automated `ffprobe` validation verifying the rendered video is playable.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

# Unlit-to-lit brightness normalization maps for Pebble QEMU screendumps.
# When the emulator backlight times out, Pebble OS scales the 4-level RGB channel
# values {0, 85, 170, 255} down to a lower linear scale. Lifting unlit values back
# to full display brightness keeps the watch crisp throughout the entire demo video.
UNLIT_CHANNEL_MAX_THRESHOLD = 210

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
]


def find_font() -> str | None:
    for path in FONT_CANDIDATES:
        if os.path.isfile(path):
            return path
    return None


def ffmpeg_has_drawtext(ffmpeg_bin: str) -> bool:
    proc = subprocess.run(
        [ffmpeg_bin, "-hide_banner", "-filters"],
        capture_output=True,
        text=True,
        check=False,
    )
    return "drawtext" in proc.stdout


def escape_drawtext(text: str) -> str:
    return (
        text.replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("'", "\\'")
        .replace("%", "\\%")
    )


def normalize_pebble_ppm(src_path: Path, dst_path: Path) -> None:
    """Normalize a binary P6 PPM frame from QEMU so unlit backlight frames stay bright."""
    data = src_path.read_bytes()
    if not data.startswith(b"P6"):
        shutil.copy2(src_path, dst_path)
        return

    # Parse P6 header: magic, width, height, maxval (ignoring # comments)
    idx = 2
    tokens: list[bytes] = []
    n = len(data)
    while len(tokens) < 3 and idx < n:
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
            tokens.append(data[start:idx])
    if len(tokens) < 3 or idx >= n:
        shutil.copy2(src_path, dst_path)
        return

    # Single whitespace byte after maxval before pixel payload
    pixel_offset = idx + 1
    header = data[:pixel_offset]
    pixels = data[pixel_offset:]
    if not pixels:
        shutil.copy2(src_path, dst_path)
        return

    max_val = max(pixels)
    if 20 < max_val <= UNLIT_CHANNEL_MAX_THRESHOLD:
        scale = 255.0 / float(max_val)
        lut = bytes(min(255, int(round(v * scale))) for v in range(256))
        pixels = pixels.translate(lut)
        dst_path.write_bytes(header + pixels)
    else:
        shutil.copy2(src_path, dst_path)


def build_concat_file(
    frames: list[dict[str, Any]],
    concat_path: Path,
    work_dir: Path,
    normalize_ppm: bool = False,
    min_duration: float = 0.04,
    last_frame_hold: float = 1.5,
) -> float:
    """Write an ffmpeg concat demuxer file from timestamped frame metadata."""
    valid_entries: list[tuple[Path, float]] = []
    for entry in frames:
        fpath = Path(entry["path"])
        if fpath.is_file() and fpath.stat().st_size > 0:
            valid_entries.append((fpath, float(entry["t"])))

    if not valid_entries:
        raise RuntimeError(f"No valid captured frames found for {concat_path.name}")

    valid_entries.sort(key=lambda item: item[1])
    norm_dir = work_dir / f"norm-{concat_path.stem}"
    norm_dir.mkdir(parents=True, exist_ok=True)

    lines: list[str] = ["ffconcat version 1.0"]
    total_duration = 0.0
    prepared_paths: list[Path] = []

    for idx, (src_path, _) in enumerate(valid_entries):
        if normalize_ppm and src_path.suffix.lower() == ".ppm":
            dst_path = norm_dir / f"frame_{idx:05d}.ppm"
            normalize_pebble_ppm(src_path, dst_path)
            prepared_paths.append(dst_path)
        else:
            prepared_paths.append(src_path)

    for idx, fpath in enumerate(prepared_paths):
        if idx + 1 < len(valid_entries):
            dt = max(min_duration, valid_entries[idx + 1][1] - valid_entries[idx][1])
            dt = min(dt, 5.0)
        else:
            dt = last_frame_hold
        safe_path = str(fpath.resolve()).replace("'", "'\\''")
        lines.append(f"file '{safe_path}'")
        lines.append(f"duration {dt:.4f}")
        total_duration += dt

    # ffmpeg concat demuxer requires repeating the final file line without duration
    last_safe = str(prepared_paths[-1].resolve()).replace("'", "'\\''")
    lines.append(f"file '{last_safe}'")

    concat_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return total_duration


def probe_video(ffprobe_bin: str, video_path: Path) -> dict[str, Any]:
    proc = subprocess.run(
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
        capture_output=True,
        text=True,
        check=True,
    )
    info = json.loads(proc.stdout)
    streams = [s for s in info.get("streams", []) if s.get("codec_type") == "video"]
    if not streams:
        raise RuntimeError(f"Rendered video {video_path} has no video stream")
    vstream = streams[0]
    duration = float(info.get("format", {}).get("duration") or vstream.get("duration") or 0.0)
    if duration <= 0.0:
        raise RuntimeError(f"Rendered video {video_path} has zero duration")
    return {
        "path": str(video_path),
        "sizeBytes": video_path.stat().st_size,
        "width": int(vstream["width"]),
        "height": int(vstream["height"]),
        "codec": vstream.get("codec_name", "unknown"),
        "pixFmt": vstream.get("pix_fmt", "unknown"),
        "durationSeconds": round(duration, 2),
    }


def compose_video(
    android_video: Path | None,
    android_timestamps: Path | None,
    pebble_timestamps: Path,
    platform: str,
    output_path: Path,
    poster_path: Path | None = None,
    title: str | None = None,
    ffmpeg_bin: str = "ffmpeg",
    ffprobe_bin: str = "ffprobe",
) -> dict[str, Any]:
    pebble_frames = json.loads(pebble_timestamps.read_text(encoding="utf-8"))
    if not isinstance(pebble_frames, list) or not pebble_frames:
        raise RuntimeError("pebble_timestamps JSON must contain a non-empty list of frames")

    platform_label = (
        "Pebble Time 2 (emery)" if platform == "emery" else "Pebble Time (basalt)"
    )
    banner_title = title or f"PebblePinManager E2E Demo  |  Android + {platform_label}"

    output_path.parent.mkdir(parents=True, exist_ok=True)
    if poster_path:
        poster_path.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="pebble-compose-") as tmp:
        tmp_dir = Path(tmp)
        pebble_concat = tmp_dir / "pebble.ffconcat"
        pebble_dur = build_concat_file(
            frames=pebble_frames,
            concat_path=pebble_concat,
            work_dir=tmp_dir,
            normalize_ppm=True,
        )

        # Render Pebble intermediate MP4 so concat timing is baked cleanly
        pebble_raw_mp4 = tmp_dir / "pebble-raw.mp4"
        subprocess.run(
            [
                ffmpeg_bin,
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(pebble_concat),
                "-vsync",
                "cfr",
                "-r",
                "20",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-preset",
                "fast",
                "-crf",
                "16",
                str(pebble_raw_mp4),
            ],
            check=True,
            capture_output=True,
            text=True,
        )

        if android_video and android_video.is_file() and android_video.stat().st_size > 1024:
            android_input = android_video
            android_dur = probe_video(ffprobe_bin, android_input)["durationSeconds"]
        elif android_timestamps and android_timestamps.is_file():
            android_frames = json.loads(android_timestamps.read_text(encoding="utf-8"))
            android_concat = tmp_dir / "android.ffconcat"
            android_dur = build_concat_file(
                frames=android_frames,
                concat_path=android_concat,
                work_dir=tmp_dir,
                normalize_ppm=False,
            )
            android_input = tmp_dir / "android-raw.mp4"
            subprocess.run(
                [
                    ffmpeg_bin,
                    "-y",
                    "-f",
                    "concat",
                    "-safe",
                    "0",
                    "-i",
                    str(android_concat),
                    "-vsync",
                    "cfr",
                    "-r",
                    "20",
                    "-c:v",
                    "libx264",
                    "-pix_fmt",
                    "yuv420p",
                    "-preset",
                    "fast",
                    "-crf",
                    "20",
                    str(android_input),
                ],
                check=True,
                capture_output=True,
                text=True,
            )
        else:
            raise RuntimeError("Either --android-video or --android-timestamps must be provided")

        target_duration = max(android_dur, pebble_dur)

        # Layout on 1280x720 dark slate canvas:
        #   - Header bar: y=0..64
        #   - Left pane (Android): box x=48..608, y=96..668 (560x572)
        #   - Right pane (Pebble watch bezel): box x=672..1232, y=96..668 (560x572)
        #     Pebble display upscaled with nearest-neighbor:
        #       basalt (144x168) -> 2x/2.5x -> 360x420 (exact 2.5x? Better exact integer 2x=288x336 or 3x=432x504!
        #       Wait: 3x basalt = 432x504 fits right inside 560x572!!
        #       And 2x emery (200x228) = 400x456 fits right inside 560x572!!)
        if platform == "emery":
            pebble_w, pebble_h = 400, 456
        else:
            pebble_w, pebble_h = 360, 420

        bezel_pad = 24
        bezel_w = pebble_w + bezel_pad * 2
        bezel_h = pebble_h + bezel_pad * 2
        bezel_x = 672 + (560 - bezel_w) // 2
        bezel_y = 96 + (572 - bezel_h) // 2
        pebble_x = bezel_x + bezel_pad
        pebble_y = bezel_y + bezel_pad

        # Watch physical side buttons on the bezel (Back on left; Up/Select/Down on right)
        btn_filters = (
            f"drawbox=x={bezel_x - 8}:y={bezel_y + 90}:w=8:h=58:color=0x475569:t=fill,"
            f"drawbox=x={bezel_x + bezel_w}:y={bezel_y + 72}:w=8:h=52:color=0x475569:t=fill,"
            f"drawbox=x={bezel_x + bezel_w}:y={bezel_y + (bezel_h // 2) - 32}:w=8:h=64:color=0x64748b:t=fill,"
            f"drawbox=x={bezel_x + bezel_w}:y={bezel_y + bezel_h - 124}:w=8:h=52:color=0x475569:t=fill,"
            f"drawbox=x={bezel_x}:y={bezel_y}:w={bezel_w}:h={bezel_h}:color=0x1e293b:t=fill,"
            f"drawbox=x={bezel_x}:y={bezel_y}:w={bezel_w}:h={bezel_h}:color=0x64748b:t=3"
        )

        font = find_font()
        use_text = font is not None and ffmpeg_has_drawtext(ffmpeg_bin)
        text_chain = ""
        if use_text and font:
            safe_title = escape_drawtext(banner_title)
            safe_left = escape_drawtext("Android Emulator  —  PebblePinManager")
            safe_right = escape_drawtext(f"Pebble QEMU  —  {platform_label}")
            text_chain = (
                f",drawtext=fontfile='{font}':text='{safe_title}':fontcolor=0xf8fafc:fontsize=24:"
                f"x=(w-text_w)/2:y=20"
                f",drawtext=fontfile='{font}':text='{safe_left}':fontcolor=0x94a3b8:fontsize=16:"
                f"x=48+(560-text_w)/2:y=684"
                f",drawtext=fontfile='{font}':text='{safe_right}':fontcolor=0x94a3b8:fontsize=16:"
                f"x=672+(560-text_w)/2:y=684"
            )

        filter_complex = (
            f"color=c=0x0f172a:s=1280x720:r=20:d={target_duration + 0.5:.2f},"
            f"drawbox=x=0:y=0:w=1280:h=64:color=0x1e293b:t=fill,"
            f"drawbox=x=0:y=63:w=1280:h=2:color=0xff5500:t=fill,"
            f"drawbox=x=48:y=96:w=560:h=572:color=0x111827:t=fill,"
            f"drawbox=x=48:y=96:w=560:h=572:color=0x334155:t=2,"
            f"{btn_filters}[bg];"
            f"[0:v]fps=20,scale=540:552:force_original_aspect_ratio=decrease,"
            f"pad=540:552:(ow-iw)/2:(oh-ih)/2:color=0x111827,"
            f"tpad=stop_mode=clone:stop_duration=30[android];"
            f"[1:v]fps=20,scale={pebble_w}:{pebble_h}:flags=neighbor,"
            f"tpad=stop_mode=clone:stop_duration=30[pebble];"
            f"[bg][android]overlay=x=58:y=106:shortest=1[s1];"
            f"[s1][pebble]overlay=x={pebble_x}:y={pebble_y}:shortest=1{text_chain}[out]"
        )

        subprocess.run(
            [
                ffmpeg_bin,
                "-y",
                "-i",
                str(android_input),
                "-i",
                str(pebble_raw_mp4),
                "-filter_complex",
                filter_complex,
                "-map",
                "[out]",
                "-t",
                f"{target_duration:.2f}",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-preset",
                "medium",
                "-crf",
                "20",
                "-movflags",
                "+faststart",
                str(output_path),
            ],
            check=True,
            capture_output=True,
            text=True,
        )

        probe_info = probe_video(ffprobe_bin, output_path)

        if poster_path:
            seek_t = max(0.2, min(probe_info["durationSeconds"] * 0.45, probe_info["durationSeconds"] - 0.2))
            subprocess.run(
                [
                    ffmpeg_bin,
                    "-y",
                    "-ss",
                    f"{seek_t:.2f}",
                    "-i",
                    str(output_path),
                    "-frames:v",
                    "1",
                    str(poster_path),
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            probe_info["posterPath"] = str(poster_path)

        return probe_info


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Composite Android + Pebble emulator recordings into a side-by-side MP4 video."
    )
    parser.add_argument("--android-video", type=Path, default=None)
    parser.add_argument("--android-timestamps", type=Path, default=None)
    parser.add_argument("--pebble-timestamps", type=Path, required=True)
    parser.add_argument("--platform", choices=["basalt", "emery"], default="basalt")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--poster", type=Path, default=None)
    parser.add_argument("--title", type=str, default=None)
    parser.add_argument("--ffmpeg-bin", type=str, default="ffmpeg")
    parser.add_argument("--ffprobe-bin", type=str, default="ffprobe")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    info = compose_video(
        android_video=args.android_video,
        android_timestamps=args.android_timestamps,
        pebble_timestamps=args.pebble_timestamps,
        platform=args.platform,
        output_path=args.output,
        poster_path=args.poster,
        title=args.title,
        ffmpeg_bin=args.ffmpeg_bin,
        ffprobe_bin=args.ffprobe_bin,
    )
    print(json.dumps(info, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
