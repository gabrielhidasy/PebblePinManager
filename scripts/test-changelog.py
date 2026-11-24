#!/usr/bin/env python3
"""Offline stdlib-only unit & integration tests for CHANGELOG.md and scripts/changelog.py."""

from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = REPO_ROOT / "scripts"


def load_module(name: str, filename: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, SCRIPTS_DIR / filename)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


cl = load_module("changelog_mod", "changelog.py")


def git(args: list[str], cwd: Path) -> str:
    env = {
        **os.environ,
        "GIT_AUTHOR_NAME": "Test Author",
        "GIT_AUTHOR_EMAIL": "test@example.com",
        "GIT_COMMITTER_NAME": "Test Author",
        "GIT_COMMITTER_EMAIL": "test@example.com",
    }
    proc = subprocess.run(["git", *args], cwd=str(cwd), env=env, text=True, capture_output=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed:\n{proc.stdout}\n{proc.stderr}")
    return proc.stdout.strip()


def commit_file(repo: Path, rel_path: str, content: str, message: str) -> str:
    full = repo / rel_path
    full.parent.mkdir(parents=True, exist_ok=True)
    full.write_text(content, encoding="utf-8")
    git(["add", rel_path], repo)
    git(["commit", "-m", message], repo)
    return git(["rev-parse", "HEAD"], repo)


class ChangelogFormattingTests(unittest.TestCase):
    def test_classify_changed_files_filters_strictly_to_apk_and_pbw(self) -> None:
        self.assertEqual(cl.classify_changed_files(["android/app/src/main/java/App.kt"]), (True, False))
        self.assertEqual(cl.classify_changed_files(["android/app/build.gradle.kts"]), (True, False))
        self.assertEqual(cl.classify_changed_files(["android/gradle/libs.versions.toml"]), (True, False))
        self.assertEqual(cl.classify_changed_files(["watchapp/src/c/main.c"]), (False, True))
        self.assertEqual(
            cl.classify_changed_files(["android/app/src/main/res/xml/backup.xml", "watchapp/package.json"]),
            (True, True),
        )
        # Non-APK / non-PBW paths are excluded
        self.assertEqual(
            cl.classify_changed_files([
                "android/emulator-bridge/src/main/java/Bridge.kt",
                "android/app/src/test/java/UnitTest.kt",
                "docs/privacy.html",
                "fastlane/metadata/android/en-US/title.txt",
                "play-store/REVIEWER_NOTES.md",
                "scripts/run-emulator-e2e.py",
                "README.md",
                "PRIVACY.md",
            ]),
            (False, False),
        )

    def test_format_commit_entry_sanitizes_merge_and_conventional_titles(self) -> None:
        entry = cl.format_commit_entry(
            "Merge pull request 'feat(android): add Prominent Disclosure modal (#78)' (#87) from issue-78 into main",
            "",
            ["android/app/src/main/java/Dialog.kt"],
        )
        self.assertEqual(entry, "- **Android (APK):** Add Prominent Disclosure modal.")

        both = cl.format_commit_entry(
            "Merge pull request 'feat(watchapp,android): sync recent pins (#5)' (#12) from issue-5 into main",
            "",
            ["android/app/src/main/java/Sync.kt", "watchapp/src/c/main.c"],
        )
        self.assertEqual(both, "- **Watchapp (PBW) & Android (APK):** Sync recent pins.")

        # CI / docs / test commits are omitted even if touching build config
        self.assertIsNone(
            cl.format_commit_entry(
                "ci(release): tweak signing block (#49)",
                "",
                ["android/app/build.gradle.kts"],
            )
        )
        self.assertIsNone(
            cl.format_commit_entry(
                "docs(play): prepare Play Store assets (#80)",
                "",
                ["play-store/REVIEWER_NOTES.md", "fastlane/metadata/android/en-US/title.txt"],
            )
        )

        # Explicit Changelog trailer override and skip
        custom = cl.format_commit_entry(
            "refactor(android): internal cleanup",
            "Details\n\nChangelog: Improve timeline sync speed by 2x",
            ["android/app/src/main/java/Sync.kt"],
        )
        self.assertEqual(custom, "- **Android (APK):** Improve timeline sync speed by 2x.")
        self.assertIsNone(
            cl.format_commit_entry(
                "refactor(android): internal cleanup",
                "Changelog: skip",
                ["android/app/src/main/java/Sync.kt"],
            )
        )

    def test_validate_and_cut_changelog(self) -> None:
        sample = (
            "# Changelog\n\n"
            "## [Unreleased]\n\n"
            "- **Android (APK):** Fix launcher icon badge.\n"
            "- **Watchapp (PBW):** Improve timeline text wrapping.\n\n"
            "## [v0.0.2] - 2026-11-23\n\n"
            "- **Android (APK):** Initial release.\n"
        )
        self.assertEqual(cl.validate_changelog(sample), [])

        cut = cl.cut_changelog_text(sample, tag="v0.0.3", release_date="2026-11-24")
        self.assertEqual(cl.validate_changelog(cut), [])
        self.assertIn("## [Unreleased]\n\n## [v0.0.3] - 2026-11-24\n\n- **Android (APK):** Fix launcher icon badge.", cut)

        with self.assertRaises(cl.ChangelogError):
            cl.cut_changelog_text(cut, tag="v0.0.3", release_date="2026-11-24")

        bad_errors = cl.validate_changelog("# Notes\n\n## [v0.0.1]\n\n")
        self.assertTrue(any("# Changelog" in e for e in bad_errors))
        self.assertTrue(any("[Unreleased]" in e for e in bad_errors))
        self.assertTrue(any("release date" in e for e in bad_errors))


class ChangelogGitIntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="test-changelog-")
        self.repo = Path(self.tmp.name)
        git(["init", "-q", "-b", "main"], self.repo)

        # Initial v0.0.2 state without CHANGELOG.md
        commit_file(self.repo, "android/app/src/main/java/App.kt", "class App\n", "feat(android): initial app (#1)")
        commit_file(self.repo, "watchapp/src/c/main.c", "int main() { return 0; }\n", "feat(watchapp): initial watchapp (#2)")
        git(["tag", "-a", "v0.0.2", "-m", "Release v0.0.2"], self.repo)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_git_fallback_filters_only_apk_and_pbw_commits(self) -> None:
        commit_file(self.repo, "docs/privacy.html", "<html/>\n", "feat(docs): add static privacy site (#77)")
        commit_file(
            self.repo,
            "android/emulator-bridge/src/main/java/Bridge.kt",
            "class Bridge\n",
            "feat(e2e): add emulator demo video (#67)",
        )
        commit_file(
            self.repo,
            "android/app/src/main/res/drawable/icon.xml",
            "<vector/>\n",
            "Merge pull request 'fix(android): remove notification dot badge (#75)' (#83) from issue-75 into main",
        )
        commit_file(
            self.repo,
            "watchapp/src/c/pins.c",
            "// pins\n",
            "Merge pull request 'fix(watchapp): prevent long title truncation (#91)' (#92) from issue-91 into main",
        )

        resolved = cl.resolve_release_changelog(repo=self.repo, tag="v0.0.3")
        self.assertEqual(resolved.previous_tag, "v0.0.2")
        self.assertEqual(resolved.source, "git-history")
        self.assertEqual(
            resolved.entries,
            (
                "- **Watchapp (PBW):** Prevent long title truncation.",
                "- **Android (APK):** Remove notification dot badge.",
            ),
        )

        notes = cl.render_release_notes(resolved, github_repo="gabrielhidasy/PebblePinManager")
        self.assertIn("## What's Changed", notes)
        self.assertIn("Prevent long title truncation.", notes)
        self.assertIn("Remove notification dot badge.", notes)
        self.assertNotIn("privacy site", notes)
        self.assertNotIn("emulator demo video", notes)
        self.assertIn("pebblepinmanager-android-v0.0.3.apk", notes)
        self.assertIn("pebblepinmanager-android-v0.0.3-dev.apk", notes)
        self.assertIn("pebblepinmanager-watchapp-v0.0.3.pbw", notes)
        self.assertIn("https://github.com/gabrielhidasy/PebblePinManager/compare/v0.0.2...v0.0.3", notes)

    def test_unreleased_curated_entries_plus_newer_uncurated_commit_and_stale_guard(self) -> None:
        # Add CHANGELOG.md with curated entry for an APK PR
        commit_file(
            self.repo,
            "android/app/src/main/java/Privacy.kt",
            "class Privacy\n",
            "feat(android): add privacy policy dialog (#76)",
        )
        commit_file(
            self.repo,
            "CHANGELOG.md",
            "# Changelog\n\n## [Unreleased]\n\n"
            "- **Android (APK):** Add an in-app Privacy Policy dialog.\n\n"
            "## [v0.0.2] - 2026-11-23\n\n"
            "- **Watchapp (PBW) & Android (APK):** Initial release.\n",
            "docs(changelog): document unreleased APK changes (#90)",
        )
        # Merge one more APK PR without touching CHANGELOG.md
        commit_file(
            self.repo,
            "android/app/src/main/java/Badge.kt",
            "class Badge\n",
            "fix(android): remove launcher dot badge (#75)",
        )
        # Plus a docs-only PR that should NOT appear
        commit_file(self.repo, "README.md", "# Readme\n", "docs: update README")

        resolved_v003 = cl.resolve_release_changelog(repo=self.repo, tag="v0.0.3")
        self.assertEqual(resolved_v003.source, "changelog-unreleased")
        self.assertEqual(
            resolved_v003.entries,
            (
                "- **Android (APK):** Remove launcher dot badge.",
                "- **Android (APK):** Add an in-app Privacy Policy dialog.",
            ),
        )

        # Tag v0.0.3 WITHOUT cutting ## [Unreleased] in CHANGELOG.md
        git(["tag", "-a", "v0.0.3", "-m", "Release v0.0.3"], self.repo)

        # Merge a watchapp PR after v0.0.3 without touching CHANGELOG.md
        commit_file(
            self.repo,
            "watchapp/src/c/menu.c",
            "// menu\n",
            "feat(watchapp): add quick delete shortcut (#95)",
        )

        # For v0.0.4, stale v0.0.3 Unreleased entries must NOT be re-emitted!
        resolved_v004 = cl.resolve_release_changelog(repo=self.repo, tag="v0.0.4")
        self.assertEqual(resolved_v004.previous_tag, "v0.0.3")
        self.assertEqual(resolved_v004.source, "git-history")
        self.assertEqual(
            resolved_v004.entries,
            ("- **Watchapp (PBW):** Add quick delete shortcut.",),
        )

    def test_explicit_version_section_wins_and_prerelease_tags_skipped_for_stable(self) -> None:
        commit_file(
            self.repo,
            "android/app/src/main/java/RcFeature.kt",
            "class RcFeature\n",
            "feat(android): add RC feature",
        )
        git(["tag", "-a", "v0.0.3-rc.1", "-m", "RC 1"], self.repo)

        commit_file(
            self.repo,
            "CHANGELOG.md",
            "# Changelog\n\n## [Unreleased]\n\n"
            "## [v0.0.3] - 2026-11-24\n\n"
            "- **Android (APK):** Curated v0.0.3 highlight.\n",
            "chore(release): prepare v0.0.3 changelog",
        )

        resolved = cl.resolve_release_changelog(repo=self.repo, tag="v0.0.3")
        self.assertEqual(resolved.previous_tag, "v0.0.2")
        self.assertEqual(resolved.source, "changelog-version")
        self.assertEqual(resolved.entries, ("- **Android (APK):** Curated v0.0.3 highlight.",))

        # Custom intro without changelog heading gets merged with What's Changed
        with_intro = cl.render_release_notes(resolved, intro="Play Store readiness release!")
        self.assertTrue(with_intro.startswith("Play Store readiness release!\n\n## What's Changed"))

        # Custom intro that already has ## Changelog is kept verbatim
        full_custom = cl.render_release_notes(resolved, intro="## Changelog\n\n- Custom hand-written note.")
        self.assertEqual(full_custom, "## Changelog\n\n- Custom hand-written note.\n")


class RepoChangelogTests(unittest.TestCase):
    def test_repo_changelog_passes_check_cli(self) -> None:
        proc = subprocess.run(
            [sys.executable, str(SCRIPTS_DIR / "changelog.py"), "check", "--repo", str(REPO_ROOT)],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr or proc.stdout)
        self.assertIn("Changelog OK", proc.stdout)

    def test_repo_unreleased_entries_cover_apk_prs_since_v002(self) -> None:
        text = (REPO_ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        sections = cl.parse_changelog(text)
        self.assertGreaterEqual(len(sections), 2)
        unreleased = sections[0]
        self.assertEqual(cl.normalize_tag_label(unreleased.label), "unreleased")
        joined = "\n".join(unreleased.entries)
        self.assertIn("Privacy Policy", joined)
        self.assertIn("notification dot badge", joined)
        self.assertIn("Prominent Disclosure", joined)
        self.assertIn("Auto Backup", joined)
        self.assertIn("-dev", joined)
        # Ensure non-APK/PBW PRs are omitted from Unreleased
        self.assertNotIn("Cloudflare", joined)
        self.assertNotIn("reviewer notes", joined.lower())

    def test_release_workflow_renders_changelog_notes(self) -> None:
        wf = (REPO_ROOT / ".gitea" / "workflows" / "release.yml").read_text(encoding="utf-8")
        self.assertIn("python3 scripts/changelog.py render-release-notes", wf)
        self.assertIn('--output "${RUNNER_TEMP}/release-notes.md"', wf)


if __name__ == "__main__":
    unittest.main(verbosity=2)
