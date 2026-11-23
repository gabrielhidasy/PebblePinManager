#!/usr/bin/env python3
"""Unit tests for scripts/render-privacy-page.py and docs/ static site integrity."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent


def load_module(name: str, filename: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, SCRIPT_DIR / filename)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


render_mod = load_module("render_privacy_page", "render-privacy-page.py")


class RenderPrivacyPageTests(unittest.TestCase):
    def test_markdown_blocks_and_inline_formatting(self) -> None:
        sample = (
            "# Privacy Policy for Pin Manager\n\n"
            "**Effective Date:** November 23, 2026  \n"
            "**Contact:** <user@example.com>\n\n"
            "## Summary\n\n"
            "- **Local only:** Uses `DataStore` and *never* uploads data.\n"
            "- See [GitHub](https://github.com/gabrielhidasy/PebblePinManager).\n\n"
            "> Note: Always offline.\n"
        )
        title, body = render_mod.markdown_to_html(sample)
        self.assertEqual(title, "Privacy Policy for Pin Manager")
        self.assertIn("<h1>Privacy Policy for Pin Manager</h1>", body)
        self.assertIn(
            '<p><strong>Effective Date:</strong> November 23, 2026<br><strong>Contact:</strong> '
            '<a href="mailto:user@example.com">user@example.com</a></p>',
            body,
        )
        self.assertIn('<h2 id="summary">Summary</h2>', body)
        self.assertIn("<li><strong>Local only:</strong> Uses <code>DataStore</code> and <em>never</em> uploads data.</li>", body)
        self.assertIn(
            '<a href="https://github.com/gabrielhidasy/PebblePinManager" rel="noopener noreferrer">GitHub</a>',
            body,
        )
        self.assertIn("<blockquote>\n<p>Note: Always offline.</p>\n</blockquote>", body)

    def test_html_escaping_and_unsafe_links_blocked(self) -> None:
        sample = (
            "# Policy <script>alert(1)</script>\n\n"
            "Click [bad](javascript:alert(1)) or [ok](mailto:privacy@example.com).\n"
        )
        doc = render_mod.render_privacy_document(sample)
        self.assertNotIn("<script>", doc)
        self.assertNotIn("javascript:", doc)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", doc)
        self.assertIn('href="mailto:privacy@example.com"', doc)

    def test_cli_generate_and_check_mode(self) -> None:
        with tempfile.TemporaryDirectory(prefix="test-privacy-render-") as tmp:
            root = Path(tmp)
            md_path = root / "PRIVACY.md"
            html_path = root / "docs" / "privacy.html"
            md_path.write_text("# Test Policy\n\nHello **world**.\n", encoding="utf-8")

            # Missing output fails --check
            self.assertNotEqual(
                render_mod.main(["--input", str(md_path), "--output", str(html_path), "--check"]),
                0,
            )

            # Generate succeeds and passes --check
            self.assertEqual(
                render_mod.main(["--input", str(md_path), "--output", str(html_path)]),
                0,
            )
            self.assertEqual(
                render_mod.main(["--input", str(md_path), "--output", str(html_path), "--check"]),
                0,
            )

            # Drift fails --check
            md_path.write_text("# Test Policy\n\nUpdated text.\n", encoding="utf-8")
            self.assertNotEqual(
                render_mod.main(["--input", str(md_path), "--output", str(html_path), "--check"]),
                0,
            )

    def test_repo_privacy_html_in_sync_and_self_contained(self) -> None:
        privacy_md = REPO_ROOT / "PRIVACY.md"
        privacy_html = REPO_ROOT / "docs" / "privacy.html"
        index_html = REPO_ROOT / "docs" / "index.html"
        nojekyll = REPO_ROOT / "docs" / ".nojekyll"
        headers_file = REPO_ROOT / "docs" / "_headers"

        self.assertTrue(privacy_md.is_file(), "PRIVACY.md must exist")
        self.assertTrue(privacy_html.is_file(), "docs/privacy.html must exist")
        self.assertTrue(index_html.is_file(), "docs/index.html must exist")
        self.assertTrue(nojekyll.is_file(), "docs/.nojekyll must exist")
        self.assertTrue(headers_file.is_file(), "docs/_headers must exist")

        rc = render_mod.main(["--input", str(privacy_md), "--output", str(privacy_html), "--check"])
        self.assertEqual(rc, 0, "docs/privacy.html must match rendered PRIVACY.md")

        html_text = privacy_html.read_text(encoding="utf-8")
        self.assertIn("<!DOCTYPE html>", html_text)
        self.assertIn('rel="canonical" href="https://pebblepinmanager.hidasy.xyz/privacy"', html_text)
        self.assertIn("xyz.hidasy.pebblepinmanager", html_text)
        self.assertNotIn("<script", html_text.lower())
        self.assertNotIn("fonts.googleapis.com", html_text.lower())


if __name__ == "__main__":
    unittest.main()
