# Google Play Store Submission Pack (`Pin Manager for Pebble`)

This directory and [`fastlane/metadata/android/en-US/`](../fastlane/metadata/android/en-US/) contain the complete store listing copy, graphics, reviewer instructions, and Google Play Console declaration checklists for **`xyz.hidasy.pebblepinmanager`** (tracking parent issue `#45`, prepared under `#80`).

## Contents

| Artifact | Purpose |
| :--- | :--- |
| [`REVIEWER_NOTES.md`](REVIEWER_NOTES.md) | Copy-pasteable **App Access / Reviewer Instructions** for Google Play Console, including the **"Run without a watch"** hardware-free test walkthrough and justifications for optional `SYSTEM_ALERT_WINDOW` ("Instant Open") and `BIND_NOTIFICATION_LISTENER_SERVICE`. |
| [`CONSOLE_DECLARATIONS.md`](CONSOLE_DECLARATIONS.md) | Step-by-step Play Console form answers: **Data Safety** (No data collected / No data shared), **IARC Content Rating**, **Target Audience (13+ / 18+)**, **Trader Status Declaration (Non-trader)**, and permissions audit. |
| [`SCREENSHOTS.md`](SCREENSHOTS.md) | Phone and unfolded foldable screenshot shot list, Play Console technical rules, and capture commands using [`scripts/capture-play-screenshots.py`](../scripts/capture-play-screenshots.py). |
| [`fastlane/metadata/android/en-US/title.txt`](../fastlane/metadata/android/en-US/title.txt) | Store listing title: `Pin Manager for Pebble` (22 / 30 chars). |
| [`fastlane/metadata/android/en-US/short_description.txt`](../fastlane/metadata/android/en-US/short_description.txt) | Short description (74 / 80 chars). |
| [`fastlane/metadata/android/en-US/full_description.txt`](../fastlane/metadata/android/en-US/full_description.txt) | Full description with transparent permission notes and Core Devices / Pebble non-affiliation disclaimer. |
| [`fastlane/metadata/android/en-US/changelogs/default.txt`](../fastlane/metadata/android/en-US/changelogs/default.txt) | Initial release notes (≤500 chars). |
| [`fastlane/metadata/android/en-US/images/icon.png`](../fastlane/metadata/android/en-US/images/icon.png) | 512×512 opaque PNG store icon (without the removed notification dot badge from `#75`). |
| [`fastlane/metadata/android/en-US/images/featureGraphic.png`](../fastlane/metadata/android/en-US/images/featureGraphic.png) | 1024×500 opaque 24-bit RGB PNG feature graphic. |

## Pre-Submission Dependency Gate (Parent `#45`)

Before submitting the `.aab` in Google Play Console, verify all sibling Play readiness issues are complete:

- [ ] **`#74`** — App display name updated in-app to **`Pin Manager for Pebble`** & non-affiliation disclaimer added in `AppSettingsDialog` and `README.md`.
- [ ] **`#75`** — Teal notification dot badge removed from Android adaptive launcher icon (`ic_launcher_foreground.xml`).
- [ ] **`#76`** — In-app Privacy Policy link added to `AppSettingsDialog` and `PRIVACY.md` updated with developer contact (`Gabriel Hidasy`, `gabrielhidasy+play@gmail.com`) and local installed-apps disclosure.
- [ ] **`#77`** — Clean static HTTPS Privacy Policy page live (HTTP 200, non-geofenced, non-editable HTML) and wired into the app and Play Console.
- [ ] **`#78`** — Prominent Disclosure & affirmative consent modal shown before opening Android Notification Access Settings.
- [ ] **`#79`** — `raw_notifications_dev_log` excluded from Android Auto Backup (`data_extraction_rules.xml` + `backup_rules.xml`).
- [ ] **`#81`** — R8 code/resource shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`) verified on signed `:app:bundleRelease` (`.aab`).
- [ ] **Final Screenshots** — Run `scripts/capture-play-screenshots.py` on a post-`#74`/`#78` build so Phone & Foldable screenshots show the final `"Pin Manager for Pebble"` branding (see [`SCREENSHOTS.md`](SCREENSHOTS.md)).

## Validation & Regeneration Commands

```bash
# Validate all Fastlane metadata limits, required disclosures, and PNG dimensions/types (stdlib-only)
python3 scripts/generate-play-assets.py --validate-only

# Regenerate icon.png (512x512) and featureGraphic.png (1024x500) from vector SVG sources
python3 scripts/generate-play-assets.py

# Run the metadata & screenshot harness unit tests
python3 scripts/test-play-store-metadata.py
```
