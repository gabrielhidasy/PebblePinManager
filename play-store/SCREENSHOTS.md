# Phone & Foldable Store Screenshots Guide

Google Play requires screenshots that faithfully depict the **actual in-app experience**.

> **Timing Note:** Capture and commit the final store screenshots **after `#74` (rename top bar to `Pin Manager for Pebble`), `#76` (Privacy Policy row), and `#78` (Prominent Disclosure modal)** are merged to `main`, so the captured screens match the exact Play Store title and policy flows.

---

## 1. Google Play Technical Requirements

| Asset Slot | Fastlane Directory | Recommended Resolution | Constraints |
| :--- | :--- | :--- | :--- |
| **Phone screenshots** | `fastlane/metadata/android/en-US/images/phoneScreenshots/` | **`1080 × 1920`** (exact 9:16 portrait, 420 dpi) | 2–8 screenshots; PNG/JPEG; 320–3840 px; aspect ratio ≤ 2:1 (recommended 9:16 portrait); max 8 MB each. |
| **Foldable / Large-Screen screenshots** | `fastlane/metadata/android/en-US/images/tenInchScreenshots/` | **`1812 × 2176`** @ 360 dpi (~805 dp wide unfolded foldable) | Triggers `PinComposerLayoutMode.TWO_PANE` (breakpoint `≥ 600 dp` in `PinComposerLayout.kt`); ratio 1:1.20 (well within Play's 2:1 max). Upload to Play Console's **Tablet / Large screen** screenshot section. |

*(Do not place non-image files inside `phoneScreenshots/` or `tenInchScreenshots/` so Fastlane Supply stays clean.)*

---

## 2. Recommended Shot List

### Phone Portrait (`phoneScreenshots/`)
1. **`01-pin-composer.png`** — **Pins** tab in Watchless mode with a filled example pin (*"Dentist appointment"*, `"Building B, 3rd floor"`, `+15 min`, custom icon, duration & accent color) ready to send.
2. **`02-recent-pins.png`** — **Pins** tab scrolled to **Recent Sent Pins** showing saved pins with custom icons, accent badges, and in-place **Edit** / **Delete** controls.
3. **`03-simulated-timeline.png`** — **Timeline** tab (Simulated Timeline) showing chronological pin cards with an expanded **PebbleKit 2** payload card.
4. **`04-auto-pins.png`** — **Auto-Pins** tab showing the **`100% On-Device • Opt-In Only`** header, per-app allowlist controls, and parsing/regex rules.
5. **`05-watch-handoff-dialog.png`** — **"Open from Pebble Watch"** dialog (bell icon) demonstrating optional Tap-to-Open Notification & Instant Background Open controls.

### Unfolded Foldable (`tenInchScreenshots/`)
1. **`01-foldable-two-pane-composer.png`** — **Pins** tab in native **`TWO_PANE`** layout: status card + Pin Composer on the left pane, **Recent Sent Pins** on the right pane simultaneously.
2. **`02-foldable-simulated-timeline.png`** — **Timeline** tab on the wide unfolded canvas.
3. **`03-foldable-auto-pins.png`** — **Auto-Pins** tab on the wide unfolded canvas.

---

## 3. Automated Capture via `scripts/capture-play-screenshots.py`

You can capture all Phone and Foldable screenshots automatically on any Android emulator (or ADB-connected test device) **without a physical Pebble watch** using Watchless mode:

```bash
# Preview the capture plan without touching a device
python3 scripts/capture-play-screenshots.py --dry-run

# Capture Phone + Foldable screenshots from a running Android emulator or device
# (defaults to release package xyz.hidasy.pebblepinmanager; pass --debug for .debug builds)
python3 scripts/capture-play-screenshots.py \
  --apk dist/pebblepinmanager-android-release.apk \
  --profiles phone foldable

# Or capture from an already-installed debug APK
python3 scripts/capture-play-screenshots.py --debug --skip-install --profiles phone foldable
```

The script automatically:
1. Configures the target viewport (`wm size` / `wm density`) for each profile (`phone` -> `1080x1920@420`, `foldable` -> `1812x2176@360`, yielding `~805 dp` width so `PinComposerLayoutPolicy` enters `TWO_PANE`).
2. Enables **Run without a watch** via UI Automator, creates sample pins (*"Team Standup"* and *"Dentist appointment"*), navigates through every screen in the shot list, and saves crisp PNGs directly into `fastlane/metadata/android/en-US/images/phoneScreenshots/` and `tenInchScreenshots/`.
3. Restores `wm size reset` and `wm density reset` in a `finally` block so the emulator/device is never left altered.
4. Validates every captured screenshot against Google Play's minimum/maximum pixel dimensions, aspect ratio (≤ 2:1), and 8 MB file-size limit.
