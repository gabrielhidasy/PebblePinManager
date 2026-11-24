#!/usr/bin/env python3
"""Maintain CHANGELOG.md and render GitHub/Gitea release notes for APK & PBW changes.

Only changes relevant to the Android APK (`android/app`, Android build/dependency config)
or companion Pebble watchapp (`watchapp/`) are included in release changelogs.
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

DEFAULT_CHANGELOG_PATH = "CHANGELOG.md"
DEFAULT_GITHUB_REPO = "gabrielhidasy/PebblePinManager"

APK_EXACT_FILES: frozenset[str] = frozenset({
    "android/app/build.gradle.kts",
    "android/app/proguard-rules.pro",
    "android/build.gradle.kts",
    "android/gradle/libs.versions.toml",
})
APK_PREFIXES: tuple[str, ...] = (
    "android/app/src/main/",
    "android/app/src/release/",
    "android/app/src/releaseDev/",
)
PBW_PREFIXES: tuple[str, ...] = (
    "watchapp/",
)
PBW_IGNORED_PREFIXES: tuple[str, ...] = (
    "watchapp/tests/",
)
PBW_IGNORED_FILES: frozenset[str] = frozenset({
    "watchapp/README.md",
})

EXCLUDED_COMMIT_TYPES: frozenset[str] = frozenset({
    "ci",
    "docs",
    "test",
    "style",
})

SECTION_HEADING_RE = re.compile(
    r"^##\s+\[?(?P<label>[^\]\n]+?)\]?(?:\s+-\s+(?P<date>\d{4}-\d{2}-\d{2}))?\s*$"
)
SEMVER_TAG_RE = re.compile(
    r"^v(?P<major>\d+)\.(?P<minor>\d+)\.(?P<patch>\d+)(?:-(?P<pre>[0-9A-Za-z.-]+))?$"
)
MERGE_PR_RE = re.compile(
    r"^Merge pull request '(.+?)' \(#\d+\) from .+ into .+$"
)
GH_MERGE_PR_RE = re.compile(
    r"^Merge pull request #\d+ from .+$"
)
TRAILING_ISSUE_REF_RE = re.compile(r"\s+\(#\d+\)\s*$")
CONVENTIONAL_RE = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<desc>.+)$"
)
CHANGELOG_TRAILER_RE = re.compile(r"^Changelog:\s*(.+)$", re.IGNORECASE)
CHANGELOG_HEADING_IN_INTRO_RE = re.compile(
    r"^##\s+(?:what's changed|changelog)\b", re.IGNORECASE | re.MULTILINE
)


class ChangelogError(RuntimeError):
    """Raised when changelog validation, parsing, or git inspection fails."""


@dataclass(frozen=True)
class ChangelogSection:
    raw_heading: str
    label: str
    date: str | None
    entries: tuple[str, ...]
    start_line: int
    end_line: int


@dataclass(frozen=True)
class ResolvedChangelog:
    tag: str
    previous_tag: str | None
    entries: tuple[str, ...]
    source: str  # "changelog-version" | "changelog-unreleased" | "git-history"


def run_git(repo: Path, args: Sequence[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        ["git", *args],
        cwd=str(repo),
        text=True,
        capture_output=True,
    )
    if check and proc.returncode != 0:
        raise ChangelogError(
            f"git {' '.join(args)} failed ({proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
        )
    return proc


def normalize_tag_label(label: str) -> str:
    cleaned = label.strip()
    if cleaned.lower() == "unreleased":
        return "unreleased"
    if re.match(r"^\d+\.\d+\.\d+", cleaned):
        return f"v{cleaned}"
    return cleaned


def parse_semver(tag: str) -> tuple[int, int, int, int, tuple[tuple[int, int | str], ...]] | None:
    m = SEMVER_TAG_RE.match(tag.strip())
    if not m:
        return None
    major = int(m.group("major"))
    minor = int(m.group("minor"))
    patch = int(m.group("patch"))
    pre = m.group("pre")
    if not pre:
        return (major, minor, patch, 1, ())
    pre_parts: list[tuple[int, int | str]] = []
    for part in pre.split("."):
        if part.isdigit():
            pre_parts.append((0, int(part)))
        else:
            pre_parts.append((1, part))
    return (major, minor, patch, 0, tuple(pre_parts))


def is_prerelease_tag(tag: str) -> bool:
    parsed = parse_semver(tag)
    return bool(parsed is not None and parsed[3] == 0)


def classify_changed_files(files: Sequence[str]) -> tuple[bool, bool]:
    """Return `(affects_apk, affects_pbw)` for a commit's changed file paths."""
    affects_apk = False
    affects_pbw = False
    for raw in files:
        norm = raw.strip().lstrip("./")
        if not norm:
            continue
        if norm in APK_EXACT_FILES or norm.startswith(APK_PREFIXES):
            affects_apk = True
        if (
            norm.startswith(PBW_PREFIXES)
            and norm not in PBW_IGNORED_FILES
            and not norm.startswith(PBW_IGNORED_PREFIXES)
        ):
            affects_pbw = True
    return affects_apk, affects_pbw


def component_label(affects_apk: bool, affects_pbw: bool) -> str:
    if affects_apk and affects_pbw:
        return "Watchapp (PBW) & Android (APK)"
    if affects_pbw:
        return "Watchapp (PBW)"
    return "Android (APK)"


def _normalize_entry_key(entry: str) -> str:
    return re.sub(r"\s+", " ", entry.strip()).lower()


def _format_sentence(text: str) -> str:
    cleaned = text.strip()
    if not cleaned:
        return ""
    if cleaned[0].islower() and not cleaned.startswith(("libpebble", "adb", "pebble-tool")):
        cleaned = cleaned[0].upper() + cleaned[1:]
    if cleaned[-1] not in ".!?":
        cleaned += "."
    return cleaned


def format_commit_entry(
    subject: str,
    body: str,
    changed_files: Sequence[str],
) -> str | None:
    """Format an APK/PBW-relevant commit into a single 1-line markdown changelog bullet.

    Returns `None` if the commit does not affect the Android APK or Pebble watchapp,
    or is explicitly marked `Changelog: none` / `Changelog: skip`.
    """
    affects_apk, affects_pbw = classify_changed_files(changed_files)
    if not (affects_apk or affects_pbw):
        return None

    body_lines = [line.strip() for line in body.splitlines()]
    explicit_summary: str | None = None
    for line in body_lines:
        trailer = CHANGELOG_TRAILER_RE.match(line)
        if trailer:
            val = trailer.group(1).strip()
            if val.lower() in {"none", "skip", "n/a"}:
                return None
            explicit_summary = val
            break

    label = component_label(affects_apk, affects_pbw)
    if explicit_summary:
        return f"- **{label}:** {_format_sentence(explicit_summary)}"

    raw = subject.strip()
    if not raw:
        return None

    m = MERGE_PR_RE.match(raw)
    if m:
        raw = m.group(1).strip()
    elif GH_MERGE_PR_RE.match(raw):
        first_body = next((ln for ln in body_lines if ln), "")
        if first_body:
            raw = first_body

    raw = TRAILING_ISSUE_REF_RE.sub("", raw).strip()

    conv = CONVENTIONAL_RE.match(raw)
    if conv:
        ctype = conv.group("type").lower()
        if ctype in EXCLUDED_COMMIT_TYPES:
            return None
        desc = conv.group("desc").strip()
    else:
        desc = raw

    if not desc:
        return None
    return f"- **{label}:** {_format_sentence(desc)}"


def parse_entries(lines: Sequence[str]) -> tuple[str, ...]:
    """Parse markdown bullet items (supporting 1–2 line continuation bullets) from a section."""
    entries: list[str] = []
    current: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith(("- ", "* ")):
            if current:
                entries.append("\n".join(current).rstrip())
            current = ["- " + stripped[2:].strip()]
        elif stripped and current and (line.startswith("  ") or line.startswith("\t")):
            current.append("  " + stripped)
        elif not stripped:
            if current:
                entries.append("\n".join(current).rstrip())
                current = []
        else:
            # Non-bullet prose inside a section terminates any open bullet
            if current:
                entries.append("\n".join(current).rstrip())
                current = []
    if current:
        entries.append("\n".join(current).rstrip())
    return tuple(entries)


def parse_changelog(text: str) -> list[ChangelogSection]:
    lines = text.splitlines()
    matches: list[tuple[int, re.Match[str]]] = []
    for idx, line in enumerate(lines):
        m = SECTION_HEADING_RE.match(line)
        if m:
            matches.append((idx, m))

    sections: list[ChangelogSection] = []
    for pos, (start_idx, m) in enumerate(matches):
        end_idx = matches[pos + 1][0] if pos + 1 < len(matches) else len(lines)
        body_lines = lines[start_idx + 1 : end_idx]
        sections.append(
            ChangelogSection(
                raw_heading=lines[start_idx],
                label=m.group("label").strip(),
                date=m.group("date"),
                entries=parse_entries(body_lines),
                start_line=start_idx,
                end_line=end_idx,
            )
        )
    return sections


def validate_changelog(text: str) -> list[str]:
    """Validate CHANGELOG.md formatting and structure; return a list of error strings."""
    errors: list[str] = []
    lines = text.splitlines()
    first_non_empty = next((ln.strip() for ln in lines if ln.strip()), "")
    if first_non_empty != "# Changelog":
        errors.append("CHANGELOG.md must start with '# Changelog'.")

    sections = parse_changelog(text)
    if not sections:
        errors.append("CHANGELOG.md must contain at least an '## [Unreleased]' section.")
        return errors

    if normalize_tag_label(sections[0].label) != "unreleased":
        errors.append(
            f"First section in CHANGELOG.md must be '## [Unreleased]', found '{sections[0].raw_heading}'."
        )

    seen_labels: set[str] = set()
    for sec in sections:
        norm = normalize_tag_label(sec.label)
        if norm in seen_labels:
            errors.append(f"Duplicate changelog section for '{sec.label}'.")
        seen_labels.add(norm)

        if norm != "unreleased":
            if parse_semver(norm) is None:
                errors.append(
                    f"Version heading '{sec.raw_heading}' must use semantic version tag format (e.g. '## [v0.1.0] - YYYY-MM-DD')."
                )
            if not sec.date:
                errors.append(
                    f"Version heading '{sec.raw_heading}' is missing a release date ('YYYY-MM-DD')."
                )
            if not sec.entries:
                errors.append(f"Version section '{sec.raw_heading}' has no bullet entries.")

        for entry in sec.entries:
            non_empty_lines = [ln for ln in entry.splitlines() if ln.strip()]
            if len(non_empty_lines) > 3:
                errors.append(
                    f"Entry in '{sec.raw_heading}' exceeds 3 lines (keep entries to 1–2 lines): {non_empty_lines[0][:80]}"
                )

    return errors


def find_previous_tag(repo: Path, target_ref: str, target_tag: str) -> str | None:
    """Find the most recent ancestor `v*` tag strictly earlier than `target_tag`."""
    proc = run_git(repo, ["tag", "--merged", target_ref, "v*"], check=False)
    if proc.returncode != 0:
        return None
    tags = [t.strip() for t in proc.stdout.splitlines() if t.strip() and t.strip() != target_tag]
    if not tags:
        return None

    target_sv = parse_semver(target_tag)
    target_is_pre = is_prerelease_tag(target_tag)

    candidates: list[tuple[tuple[int, int, int, int, tuple[tuple[int, int | str], ...]], str]] = []
    for tag in tags:
        sv = parse_semver(tag)
        if sv is None:
            continue
        if not target_is_pre and sv[3] == 0:
            # Skip prereleases when computing changes for a stable release
            continue
        if target_sv is not None and sv >= target_sv:
            continue
        candidates.append((sv, tag))

    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1]


def _read_changelog_at_ref(
    repo: Path,
    ref: str,
    changelog_rel: str,
    *,
    prefer_worktree: bool,
) -> str | None:
    if prefer_worktree and ref == "HEAD":
        disk_path = repo / changelog_rel
        if disk_path.is_file():
            return disk_path.read_text(encoding="utf-8")
    proc = run_git(repo, ["show", f"{ref}:{changelog_rel}"], check=False)
    if proc.returncode == 0:
        return proc.stdout
    return None


def _is_ancestor(repo: Path, maybe_ancestor: str, descendent: str) -> bool:
    proc = run_git(repo, ["merge-base", "--is-ancestor", maybe_ancestor, descendent], check=False)
    return proc.returncode == 0


def collect_git_entries(repo: Path, base_ref: str | None, head_ref: str) -> tuple[str, ...]:
    """Generate 1-line changelog bullets for APK/PBW-relevant first-parent commits in `base_ref..head_ref`."""
    rev_range = f"{base_ref}..{head_ref}" if base_ref else head_ref
    sep_rec = "\x1e"
    sep_fld = "\x1f"
    proc = run_git(
        repo,
        ["log", "--first-parent", f"--format=%H{sep_fld}%P{sep_fld}%s{sep_fld}%b{sep_rec}", rev_range],
        check=False,
    )
    if proc.returncode != 0 or not proc.stdout.strip():
        return ()

    entries: list[str] = []
    seen: set[str] = set()
    for raw_rec in proc.stdout.split(sep_rec):
        rec = raw_rec.strip("\r\n")
        if not rec.strip():
            continue
        parts = rec.split(sep_fld, 3)
        if len(parts) < 4:
            continue
        sha, parents_str, subject, body = parts
        sha = sha.strip()
        parents = parents_str.strip().split()
        if parents:
            diff_proc = run_git(repo, ["diff", "--name-only", parents[0], sha])
            files = [f for f in diff_proc.stdout.splitlines() if f.strip()]
        else:
            tree_proc = run_git(repo, ["ls-tree", "-r", "--name-only", sha])
            files = [f for f in tree_proc.stdout.splitlines() if f.strip()]

        entry = format_commit_entry(subject, body, files)
        if not entry:
            continue
        key = _normalize_entry_key(entry)
        if key not in seen:
            seen.add(key)
            entries.append(entry)

    return tuple(entries)


def resolve_release_changelog(
    *,
    repo: Path,
    tag: str,
    ref: str = "HEAD",
    changelog_rel: str = DEFAULT_CHANGELOG_PATH,
    prefer_worktree: bool = True,
) -> ResolvedChangelog:
    """Resolve changelog entries for `tag` from `CHANGELOG.md` with git-history fallback."""
    prev_tag = find_previous_tag(repo, ref, tag)
    text = _read_changelog_at_ref(repo, ref, changelog_rel, prefer_worktree=prefer_worktree)
    norm_target = normalize_tag_label(tag)

    if text:
        sections = parse_changelog(text)
        # 1. Explicit version section matching `tag`
        for sec in sections:
            if normalize_tag_label(sec.label) == norm_target and sec.entries:
                return ResolvedChangelog(
                    tag=tag,
                    previous_tag=prev_tag,
                    entries=sec.entries,
                    source="changelog-version",
                )

        # 2. Curated `## [Unreleased]` entries added since `prev_tag`
        unreleased = next(
            (sec for sec in sections if normalize_tag_label(sec.label) == "unreleased"),
            None,
        )
        curated: list[str] = list(unreleased.entries) if unreleased else []
        if curated and prev_tag:
            prev_text = _read_changelog_at_ref(repo, prev_tag, changelog_rel, prefer_worktree=False)
            if prev_text:
                prev_sections = parse_changelog(prev_text)
                prev_unrel = next(
                    (sec for sec in prev_sections if normalize_tag_label(sec.label) == "unreleased"),
                    None,
                )
                if prev_unrel and prev_unrel.entries:
                    prev_keys = {_normalize_entry_key(e) for e in prev_unrel.entries}
                    curated = [e for e in curated if _normalize_entry_key(e) not in prev_keys]

        if curated:
            # Append any APK/PBW commits merged after the last commit that updated CHANGELOG.md
            last_cl_proc = run_git(
                repo,
                ["log", "-n", "1", "--format=%H", ref, "--", changelog_rel],
                check=False,
            )
            last_cl_sha = last_cl_proc.stdout.strip() if last_cl_proc.returncode == 0 else ""
            extra_entries: tuple[str, ...] = ()
            if last_cl_sha:
                # If worktree CHANGELOG.md has uncommitted edits, skip git append
                worktree_dirty = False
                if prefer_worktree and ref == "HEAD":
                    dirty_proc = run_git(repo, ["status", "--porcelain", "--", changelog_rel], check=False)
                    worktree_dirty = bool(dirty_proc.stdout.strip())
                if not worktree_dirty:
                    if prev_tag and _is_ancestor(repo, last_cl_sha, prev_tag):
                        extra_entries = collect_git_entries(repo, prev_tag, ref)
                    else:
                        extra_entries = collect_git_entries(repo, last_cl_sha, ref)

            merged: list[str] = []
            seen: set[str] = set()
            for entry in (*extra_entries, *curated):
                key = _normalize_entry_key(entry)
                if key not in seen:
                    seen.add(key)
                    merged.append(entry)
            return ResolvedChangelog(
                tag=tag,
                previous_tag=prev_tag,
                entries=tuple(merged),
                source="changelog-unreleased",
            )

    # 3. Fallback: generate directly from APK/PBW-relevant first-parent commits
    git_entries = collect_git_entries(repo, prev_tag, ref)
    return ResolvedChangelog(
        tag=tag,
        previous_tag=prev_tag,
        entries=git_entries,
        source="git-history",
    )


def cut_changelog_text(
    text: str,
    *,
    tag: str,
    release_date: str,
    extra_entries: Sequence[str] = (),
) -> str:
    """Move `## [Unreleased]` entries (plus optional extras) into a new `## [<tag>] - <date>` section."""
    sections = parse_changelog(text)
    if not sections or normalize_tag_label(sections[0].label) != "unreleased":
        raise ChangelogError("CHANGELOG.md must begin with an '## [Unreleased]' section before cutting a release.")

    norm_target = normalize_tag_label(tag)
    for sec in sections:
        if normalize_tag_label(sec.label) == norm_target:
            raise ChangelogError(f"CHANGELOG.md already contains a section for '{tag}'.")

    unreleased = sections[0]
    combined: list[str] = []
    seen: set[str] = set()
    for entry in (*extra_entries, *unreleased.entries):
        key = _normalize_entry_key(entry)
        if key not in seen:
            seen.add(key)
            combined.append(entry)

    if not combined:
        raise ChangelogError(
            f"No changelog entries available under '## [Unreleased]' or recent APK/PBW commits for '{tag}'."
        )

    lines = text.splitlines()
    before = lines[: unreleased.start_line]
    after = lines[unreleased.end_line :]

    new_block = [
        "## [Unreleased]",
        "",
        f"## [{tag}] - {release_date}",
        "",
        *combined,
        "",
    ]
    result_lines = [*before, *new_block, *after]
    return "\n".join(result_lines).rstrip() + "\n"


def render_release_notes(
    resolved: ResolvedChangelog,
    *,
    github_repo: str = DEFAULT_GITHUB_REPO,
    intro: str = "",
) -> str:
    """Render GitHub/Gitea release notes markdown combining changelog bullets and download info."""
    intro_clean = intro.strip()
    if intro_clean and CHANGELOG_HEADING_IN_INTRO_RE.search(intro_clean):
        return intro_clean + "\n"

    parts: list[str] = []
    if intro_clean:
        parts.append(intro_clean)

    parts.append("## What's Changed")
    if resolved.entries:
        parts.append("\n".join(resolved.entries))
    elif resolved.previous_tag:
        parts.append(
            f"- Maintenance release (no user-facing Android APK or Pebble watchapp changes since `{resolved.previous_tag}`)."
        )
    else:
        parts.append("- Initial release of the PebblePinManager Android app and Pebble companion watchapp.")

    tag = resolved.tag
    downloads = "\n".join([
        "## Downloads",
        "",
        f"- **`pebblepinmanager-android-{tag}.apk`** — Standard Android release build (without Developer Mode)",
        f"- **`pebblepinmanager-android-{tag}-dev.apk`** — Optional Android release build with Developer Mode (raw notification inspector & regex explorer)",
        f"- **`pebblepinmanager-watchapp-{tag}.pbw`** — Companion Pebble watchapp for Pebble Time (`basalt`) and Pebble Time 2 (`emery`)",
        "- **`SHA256SUMS.txt`** — SHA-256 checksums for all release files",
        "",
        f"See [INSTALL.md](https://github.com/{github_repo}/blob/main/INSTALL.md) for installation instructions "
        f"and [CHANGELOG.md](https://github.com/{github_repo}/blob/main/CHANGELOG.md) for full release history.",
    ])
    parts.append(downloads)

    if resolved.previous_tag:
        parts.append(
            f"**Full Changelog**: https://github.com/{github_repo}/compare/{resolved.previous_tag}...{tag}"
        )

    return "\n\n".join(parts) + "\n"


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Manage CHANGELOG.md and render release notes.")
    sub = parser.add_subparsers(dest="command", required=True)

    p_check = sub.add_parser("check", help="Validate CHANGELOG.md formatting and structure.")
    p_check.add_argument("--repo", default=".", help="Path to git repository root")
    p_check.add_argument("--changelog", default=DEFAULT_CHANGELOG_PATH, help="Relative path to CHANGELOG.md")

    p_collect = sub.add_parser("collect", help="Print resolved APK/PBW changelog entries for a tag.")
    p_collect.add_argument("--tag", required=True, help="Target release tag (e.g. v0.0.3)")
    p_collect.add_argument("--repo", default=".", help="Path to git repository root")
    p_collect.add_argument("--ref", default="HEAD", help="Git ref to inspect (default: HEAD)")
    p_collect.add_argument("--changelog", default=DEFAULT_CHANGELOG_PATH, help="Relative path to CHANGELOG.md")

    p_render = sub.add_parser("render-release-notes", help="Render markdown release notes for GitHub/Gitea Releases.")
    p_render.add_argument("--tag", required=True, help="Target release tag (e.g. v0.0.3)")
    p_render.add_argument("--repo", default=".", help="Path to git repository root")
    p_render.add_argument("--ref", default="HEAD", help="Git ref to inspect (default: HEAD)")
    p_render.add_argument("--changelog", default=DEFAULT_CHANGELOG_PATH, help="Relative path to CHANGELOG.md")
    p_render.add_argument("--github-repo", default=DEFAULT_GITHUB_REPO, help="GitHub owner/repo for links")
    p_render.add_argument("--intro", default="", help="Optional custom intro markdown")
    p_render.add_argument("--intro-file", default="", help="Optional file containing custom intro markdown")
    p_render.add_argument("--output", default="", help="Write rendered markdown to file instead of stdout")

    p_cut = sub.add_parser("cut", help="Promote ## [Unreleased] entries into a new versioned section.")
    p_cut.add_argument("tag", help="Release tag to cut (e.g. v0.0.3)")
    p_cut.add_argument(
        "--date",
        default=dt.date.today().isoformat(),
        help="Release date YYYY-MM-DD (default: today)",
    )
    p_cut.add_argument("--repo", default=".", help="Path to git repository root")
    p_cut.add_argument("--changelog", default=DEFAULT_CHANGELOG_PATH, help="Relative path to CHANGELOG.md")
    p_cut.add_argument("--write", action="store_true", help="Write updated CHANGELOG.md in place (default: preview to stdout)")

    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    repo = Path(args.repo).resolve()

    try:
        if args.command == "check":
            cl_path = repo / args.changelog
            if not cl_path.is_file():
                raise ChangelogError(f"Changelog file not found: {cl_path}")
            errors = validate_changelog(cl_path.read_text(encoding="utf-8"))
            if errors:
                for err in errors:
                    print(f"ERROR: {err}", file=sys.stderr)
                return 1
            print(f"Changelog OK ({args.changelog})")
            return 0

        if args.command == "collect":
            resolved = resolve_release_changelog(
                repo=repo,
                tag=args.tag,
                ref=args.ref,
                changelog_rel=args.changelog,
            )
            if resolved.entries:
                print("\n".join(resolved.entries))
            return 0

        if args.command == "render-release-notes":
            intro = args.intro
            if args.intro_file:
                intro_path = Path(args.intro_file)
                if intro_path.is_file():
                    intro = intro_path.read_text(encoding="utf-8")
            resolved = resolve_release_changelog(
                repo=repo,
                tag=args.tag,
                ref=args.ref,
                changelog_rel=args.changelog,
            )
            rendered = render_release_notes(
                resolved,
                github_repo=args.github_repo,
                intro=intro,
            )
            if args.output:
                out_path = Path(args.output)
                out_path.parent.mkdir(parents=True, exist_ok=True)
                out_path.write_text(rendered, encoding="utf-8")
            else:
                sys.stdout.write(rendered)
            return 0

        if args.command == "cut":
            cl_path = repo / args.changelog
            if not cl_path.is_file():
                raise ChangelogError(f"Changelog file not found: {cl_path}")
            text = cl_path.read_text(encoding="utf-8")
            resolved = resolve_release_changelog(
                repo=repo,
                tag=args.tag,
                ref="HEAD",
                changelog_rel=args.changelog,
            )
            updated = cut_changelog_text(
                text,
                tag=args.tag,
                release_date=args.date,
                extra_entries=resolved.entries,
            )
            errors = validate_changelog(updated)
            if errors:
                raise ChangelogError("Generated CHANGELOG.md failed validation: " + "; ".join(errors))
            if args.write:
                cl_path.write_text(updated, encoding="utf-8")
                print(f"Updated {args.changelog} with section [{args.tag}] - {args.date}")
            else:
                sys.stdout.write(updated)
            return 0
    except ChangelogError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
