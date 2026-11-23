#!/usr/bin/env python3
"""Render PRIVACY.md into a self-contained static HTML5 page at docs/privacy.html.

Zero external dependencies (Python 3 stdlib only), deterministic output, and no
third-party scripts, fonts, or stylesheets. Supports `--check` for CI drift
detection so `docs/privacy.html` always stays in sync with `PRIVACY.md`.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
from pathlib import Path
from typing import Sequence

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INPUT = REPO_ROOT / "PRIVACY.md"
DEFAULT_OUTPUT = REPO_ROOT / "docs" / "privacy.html"

HEADING_RE = re.compile(r"^(#{1,4})\s+(.+?)\s*#*\s*$")
UL_ITEM_RE = re.compile(r"^[-*]\s+(.*)$")
OL_ITEM_RE = re.compile(r"^\d+\.\s+(.*)$")
HR_RE = re.compile(r"^[-*_]{3,}\s*$")
BLOCKQUOTE_RE = re.compile(r"^>\s?(.*)$")

INLINE_TOKEN_RE = re.compile(
    r"`([^`]+)`"
    r"|\[([^\]]+)\]\(([^)\s]+)\)"
    r"|<((?:https?://|mailto:)[^>\s]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})>"
    r"|\*\*([^*]+)\*\*"
    r"|\*([^*]+)\*"
)

ALLOWED_URL_SCHEMES = ("https://", "http://", "mailto:", "/", "./", "../", "#")


def slugify(text: str) -> str:
    plain = re.sub(r"`([^`]+)`", r"\1", text)
    plain = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", plain)
    plain = re.sub(r"[*_~]", "", plain)
    slug = re.sub(r"[^a-z0-9]+", "-", plain.lower()).strip("-")
    return slug or "section"


def sanitize_href(url: str) -> str | None:
    cleaned = url.strip()
    lower = cleaned.lower()
    if any(lower.startswith(prefix) for prefix in ALLOWED_URL_SCHEMES):
        return cleaned
    return None


def render_inline(text: str) -> str:
    """Render inline Markdown (code, links, autolinks, bold, italic) safely to HTML."""
    out: list[str] = []
    pos = 0
    for match in INLINE_TOKEN_RE.finditer(text):
        start, end = match.span()
        if start > pos:
            out.append(html.escape(text[pos:start]))
        pos = end

        code_span, link_label, link_href, autolink, bold_text, italic_text = match.groups()
        if code_span is not None:
            out.append(f"<code>{html.escape(code_span)}</code>")
        elif link_label is not None and link_href is not None:
            safe_href = sanitize_href(link_href)
            rendered_label = render_inline(link_label)
            if safe_href is not None:
                extra = (
                    ' rel="noopener noreferrer"'
                    if safe_href.startswith(("http://", "https://"))
                    else ""
                )
                out.append(f'<a href="{html.escape(safe_href, quote=True)}"{extra}>{rendered_label}</a>')
            else:
                out.append(rendered_label)
        elif autolink is not None:
            href = autolink if autolink.startswith(("http://", "https://", "mailto:")) else f"mailto:{autolink}"
            safe_href = sanitize_href(href)
            label = autolink.removeprefix("mailto:")
            if safe_href is not None:
                extra = (
                    ' rel="noopener noreferrer"'
                    if safe_href.startswith(("http://", "https://"))
                    else ""
                )
                out.append(f'<a href="{html.escape(safe_href, quote=True)}"{extra}>{html.escape(label)}</a>')
            else:
                out.append(html.escape(label))
        elif bold_text is not None:
            out.append(f"<strong>{render_inline(bold_text)}</strong>")
        elif italic_text is not None:
            out.append(f"<em>{render_inline(italic_text)}</em>")

    if pos < len(text):
        out.append(html.escape(text[pos:]))
    return "".join(out)


def render_paragraph_lines(lines: list[str]) -> str:
    parts: list[str] = []
    for idx, raw_line in enumerate(lines):
        has_hard_break = raw_line.endswith("  ")
        stripped = raw_line.strip()
        if not stripped:
            continue
        rendered = render_inline(stripped)
        if idx < len(lines) - 1:
            parts.append(rendered + ("<br>" if has_hard_break else " "))
        else:
            parts.append(rendered)
    return "".join(parts)


def markdown_to_html(markdown_text: str) -> tuple[str, str]:
    """Convert Markdown text into `(page_title, html_body)`."""
    lines = markdown_text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    blocks: list[str] = []
    seen_slugs: dict[str, int] = {}
    page_title = "Privacy Policy"

    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        if HR_RE.match(stripped):
            blocks.append("<hr>")
            i += 1
            continue

        heading_match = HEADING_RE.match(line)
        if heading_match:
            level = len(heading_match.group(1))
            raw_heading = heading_match.group(2).strip()
            if level == 1 and page_title == "Privacy Policy":
                page_title = re.sub(r"[`*_]", "", raw_heading)
            base_slug = slugify(raw_heading)
            count = seen_slugs.get(base_slug, 0)
            seen_slugs[base_slug] = count + 1
            slug = base_slug if count == 0 else f"{base_slug}-{count + 1}"
            if level == 1:
                blocks.append(f"<h1>{render_inline(raw_heading)}</h1>")
            else:
                blocks.append(f'<h{level} id="{html.escape(slug, quote=True)}">{render_inline(raw_heading)}</h{level}>')
            i += 1
            continue

        if BLOCKQUOTE_RE.match(line):
            quote_lines: list[str] = []
            while i < n and (m := BLOCKQUOTE_RE.match(lines[i])):
                quote_lines.append(m.group(1))
                i += 1
            _, inner_html = markdown_to_html("\n".join(quote_lines))
            blocks.append(f"<blockquote>\n{inner_html}\n</blockquote>")
            continue

        if UL_ITEM_RE.match(line):
            items: list[str] = []
            while i < n and (m := UL_ITEM_RE.match(lines[i])):
                item_lines = [m.group(1)]
                i += 1
                while i < n and lines[i].startswith(("  ", "\t")) and not UL_ITEM_RE.match(lines[i].lstrip()):
                    item_lines.append(lines[i].strip())
                    i += 1
                items.append(f"  <li>{render_paragraph_lines(item_lines)}</li>")
            blocks.append("<ul>\n" + "\n".join(items) + "\n</ul>")
            continue

        if OL_ITEM_RE.match(line):
            items = []
            while i < n and (m := OL_ITEM_RE.match(lines[i])):
                item_lines = [m.group(1)]
                i += 1
                while i < n and lines[i].startswith(("  ", "\t")) and not OL_ITEM_RE.match(lines[i].lstrip()):
                    item_lines.append(lines[i].strip())
                    i += 1
                items.append(f"  <li>{render_paragraph_lines(item_lines)}</li>")
            blocks.append("<ol>\n" + "\n".join(items) + "\n</ol>")
            continue

        para_lines: list[str] = []
        while i < n:
            cur = lines[i]
            cur_stripped = cur.strip()
            if (
                not cur_stripped
                or HEADING_RE.match(cur)
                or UL_ITEM_RE.match(cur)
                or OL_ITEM_RE.match(cur)
                or BLOCKQUOTE_RE.match(cur)
                or HR_RE.match(cur_stripped)
            ):
                break
            para_lines.append(cur)
            i += 1
        blocks.append(f"<p>{render_paragraph_lines(para_lines)}</p>")

    return page_title, "\n".join(blocks)


def render_privacy_document(markdown_text: str) -> str:
    """Render full standalone HTML5 document from `PRIVACY.md` content."""
    title, body_html = markdown_to_html(markdown_text)
    escaped_title = html.escape(title)
    indented_body = "\n".join(f"      {line}" if line else "" for line in body_html.splitlines())

    return f"""<!DOCTYPE html>
<!-- Generated from PRIVACY.md by scripts/render-privacy-page.py — do not edit manually. -->
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="{escaped_title}">
  <meta name="robots" content="index, follow">
  <meta name="color-scheme" content="light dark">
  <link rel="canonical" href="https://pebblepinmanager.hidasy.xyz/privacy">
  <title>{escaped_title}</title>
  <style>
    :root {{
      --bg: #ffffff;
      --surface: #f6f8fa;
      --text: #1f2328;
      --muted: #59636e;
      --border: #d0d7de;
      --accent: #0969da;
      --code-bg: #eff1f3;
    }}
    @media (prefers-color-scheme: dark) {{
      :root {{
        --bg: #0d1117;
        --surface: #161b22;
        --text: #e6edf3;
        --muted: #8b949e;
        --border: #30363d;
        --accent: #58a6ff;
        --code-bg: #21262d;
      }}
    }}
    * {{
      box-sizing: border-box;
    }}
    body {{
      margin: 0;
      padding: 0;
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      font-size: 16px;
      line-height: 1.65;
      -webkit-font-smoothing: antialiased;
    }}
    main {{
      max-width: 760px;
      margin: 0 auto;
      padding: 2rem 1.25rem 3.5rem;
    }}
    h1 {{
      font-size: 1.85rem;
      line-height: 1.25;
      margin: 0 0 1rem;
      padding-bottom: 0.6rem;
      border-bottom: 1px solid var(--border);
    }}
    h2 {{
      font-size: 1.35rem;
      line-height: 1.3;
      margin: 2rem 0 0.75rem;
      padding-bottom: 0.35rem;
      border-bottom: 1px solid var(--border);
    }}
    h3 {{
      font-size: 1.08rem;
      line-height: 1.35;
      margin: 1.4rem 0 0.5rem;
    }}
    p {{
      margin: 0 0 1rem;
    }}
    ul, ol {{
      margin: 0 0 1rem;
      padding-left: 1.5rem;
    }}
    li {{
      margin: 0.35rem 0;
    }}
    a {{
      color: var(--accent);
      text-decoration: underline;
      text-underline-offset: 2px;
      word-break: break-word;
    }}
    a:hover {{
      text-decoration-thickness: 2px;
    }}
    code {{
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
      font-size: 0.88em;
      background: var(--code-bg);
      padding: 0.14em 0.38em;
      border-radius: 4px;
      word-break: break-word;
    }}
    blockquote {{
      margin: 1rem 0;
      padding: 0.5rem 1rem;
      border-left: 3px solid var(--border);
      background: var(--surface);
      color: var(--muted);
    }}
    blockquote p:last-child {{
      margin-bottom: 0;
    }}
    hr {{
      border: 0;
      border-top: 1px solid var(--border);
      margin: 1.75rem 0;
    }}
    footer {{
      margin-top: 2.5rem;
      padding-top: 1rem;
      border-top: 1px solid var(--border);
      color: var(--muted);
      font-size: 0.9rem;
    }}
    footer nav {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem 1.25rem;
    }}
    @media print {{
      body {{
        background: #ffffff;
        color: #000000;
      }}
      main {{
        max-width: none;
        padding: 0;
      }}
    }}
  </style>
</head>
<body>
  <main>
    <article>
{indented_body}
    </article>
    <footer>
      <nav aria-label="Related links">
        <a href="./">Project Home</a>
        <a href="https://github.com/gabrielhidasy/PebblePinManager" rel="noopener noreferrer">Source Code (GitHub)</a>
      </nav>
    </footer>
  </main>
</body>
</html>
"""


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Render PRIVACY.md into a static HTML5 page at docs/privacy.html."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=DEFAULT_INPUT,
        help=f"Source Markdown file (default: {DEFAULT_INPUT})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Destination HTML file (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify that the destination HTML file is up to date without modifying it.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if not args.input.is_file():
        print(f"Error: input file not found: {args.input}", file=sys.stderr)
        return 1

    rendered = render_privacy_document(args.input.read_text(encoding="utf-8"))

    if args.check:
        if not args.output.is_file():
            print(
                f"Error: {args.output} does not exist. Run `python3 scripts/render-privacy-page.py` to generate it.",
                file=sys.stderr,
            )
            return 1
        existing = args.output.read_text(encoding="utf-8")
        if existing != rendered:
            print(
                f"Error: {args.output} is out of date with {args.input}. "
                "Run `python3 scripts/render-privacy-page.py` to regenerate it.",
                file=sys.stderr,
            )
            return 1
        print(f"OK: {args.output} is up to date with {args.input}.")
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"Rendered {args.input} -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
