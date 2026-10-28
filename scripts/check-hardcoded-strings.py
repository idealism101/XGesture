#!/usr/bin/env python3
"""Fail when newly added Kotlin lines hardcode Chinese UI text instead of a string resource.

Why this exists
---------------
`scripts/check-translations.py` compares *resource names* between `values/` and each
`values-<locale>/`. That is the wrong shape for the failure we kept shipping: a Compose screen
whose text was never extracted into a resource has no name at all, so the completeness check has
nothing to compare and reports OK. `app/build.gradle.kts` disables `MissingTranslation` /
`ExtraTranslation`, and lint's `HardcodedText` only inspects XML layout attributes — never
`Text("…")` or `SettingSwitchRow(title = "…")` in Compose. The global drag & drop flow shipped
as 100 % Chinese literals in an English-default app (`values/strings.xml` is the English source of
truth) and every CI job stayed green.

This script closes the other half of that hole, on the *added* side only: it reads `git diff` and
fails when a changed line introduces a string literal containing CJK characters. It deliberately
does not police the existing literals — OTP keyword tables, notification matchers, tokenizer
fixtures and ~1 200 old strings — because rewriting those is a separate project and gating on
them would turn every touching PR red.

Usage
-----
    python scripts/check-hardcoded-strings.py --diff origin/main   # added lines only (CI)
    python scripts/check-hardcoded-strings.py --all                # whole tree, exit 1 on any hit
    python scripts/check-hardcoded-strings.py app/src/main/…/Foo.kt # check those files in full

Exemptions, only when the Chinese is genuinely data rather than UI text:

    val keyword = "验证码" // i18n-ignore        on one line
    // i18n-ignore-file                          anywhere in the file's first five lines

`Log.*` calls are always exempt: developer logs never reach a locale.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# Han, CJK punctuation and full-width forms. Deliberately not Kana/Hangul: those only occur in
# `values-<locale>/` resources, never in a Kotlin literal that this check should police.
CJK_RE = re.compile(r"[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff01-\uff5e]")

BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
LITERAL_RE = re.compile(r'"(?:[^"\\\n]|\\.)*"')
LOG_CALL_RE = re.compile(r"\bLog\.[dviwe]\s*\(")

IGNORE_LINE = "i18n-ignore"
IGNORE_FILE = "i18n-ignore-file"

# Source sets whose Chinese is test data, not UI.
SKIPPED_SEGMENTS = ("/src/test/", "/src/androidTest/")

# Directories that never contain hand-written sources.
SKIPPED_DIRS = {".git", ".gradle", "build", ".tmp", "node_modules"}

HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def repo_root() -> Path:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, check=True,
            encoding="utf-8", errors="replace",
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return Path.cwd()
    return Path(out)


def strip_block_comments(text: str) -> str:
    """Blank out `/* … */` while keeping the line numbering intact.

    Replacing a comment with the newlines it contained (instead of removing it) is what lets the
    diff mode index the cleaned file by the line numbers git reported.
    """
    return BLOCK_COMMENT_RE.sub(lambda m: "\n" * m.group(0).count("\n"), text)


def strip_line_comment(line: str) -> str:
    """Drop a trailing `// …`, ignoring the `//` inside a string literal (`"https://…"`)."""
    in_string = False
    escaped = False
    for index, char in enumerate(line):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
        elif char == "/" and line[index + 1:index + 2] == "/":
            return line[:index]
    return line


def clean_lines(path: Path) -> list[str]:
    """Comment-free source lines; index 0 holds line 1."""
    text = path.read_text(encoding="utf-8", errors="replace")
    return [strip_line_comment(line) for line in strip_block_comments(text).splitlines()]


def file_ignored(path: Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="replace")
    return IGNORE_FILE in "\n".join(text.splitlines()[:5])


def collect_hits(path: Path, line_numbers: list[int] | None = None) -> list[tuple[int, str, str]]:
    """Return (line number, literal, raw line) for every CJK literal in the requested lines."""
    if file_ignored(path):
        return []
    try:
        lines = clean_lines(path)
    except OSError as exc:
        print(f"WARNING: cannot read {path}: {exc}", file=sys.stderr)
        return []

    wanted = range(len(lines)) if line_numbers is None else [n - 1 for n in line_numbers]
    hits: list[tuple[int, str, str]] = []
    for index in wanted:
        if index < 0 or index >= len(lines):
            continue
        line = lines[index]
        if IGNORE_LINE in line or LOG_CALL_RE.search(line):
            continue
        for match in LITERAL_RE.finditer(line):
            if CJK_RE.search(match.group(0)):
                hits.append((index + 1, match.group(0), line.strip()))
    return hits


def git(*args: str) -> str:
    # Force UTF-8: this repo's commit messages and source are Chinese, and on a Windows console
    # subprocess would otherwise decode git's output as cp936, raise inside the reader thread and
    # hand back nothing — i.e. the guard silently passes. CI runs on Linux where the default is
    # already UTF-8; the pinning keeps both platforms honest.
    return subprocess.run(
        ["git", "--no-pager", *args], capture_output=True, text=True, check=True,
        encoding="utf-8", errors="replace",
    ).stdout


def rev_exists(rev: str) -> bool:
    if not rev:
        return False
    try:
        git("rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}")
    except subprocess.CalledProcessError:
        return False
    return True


def added_lines(base: str) -> dict[str, list[int]]:
    """Map of repo-relative .kt path -> new line numbers that the diff added."""
    diff = git("diff", "--no-color", "-U0", "--diff-filter=AM", base, "HEAD", "--", "*.kt")
    added: dict[str, list[int]] = {}
    path: str | None = None
    new_line = 0
    for raw in diff.splitlines():
        if raw.startswith("+++ "):
            target = raw[4:].strip()
            path = None if target == "/dev/null" else target.removeprefix("b/")
            continue
        if raw.startswith("@@"):
            match = HUNK_RE.match(raw)
            new_line = int(match.group(1)) if match else 0
            continue
        if path is None or not new_line:
            continue
        if raw.startswith("+"):
            added.setdefault(path, []).append(new_line)
            new_line += 1
        elif raw.startswith(" "):
            new_line += 1
    return added


def iter_sources(root: Path):
    for module in ("app", "feature", "core"):
        base = root / module
        if not base.is_dir():
            continue
        for path in base.rglob("*.kt"):
            if any(part in SKIPPED_DIRS for part in path.parts):
                continue
            if any(segment in path.as_posix() + "/" for segment in SKIPPED_SEGMENTS):
                continue
            yield path


def report(root: Path, per_file: dict[Path, list[tuple[int, str, str]]]) -> int:
    total = sum(len(hits) for hits in per_file.values())
    for path, hits in sorted(per_file.items()):
        rel = path.relative_to(root).as_posix()
        for number, literal, raw in hits:
            print(f"{rel}:{number}: hardcoded CJK literal {literal}")
            print(f"    {raw}")
    if not total:
        return 0
    print(
        f"\nFAILED: {total} hardcoded Chinese literal(s) in {len(per_file)} file(s).\n"
        "Move the text to res/values/strings*.xml (English is the default locale) and read it "
        "with stringResource(...) in Compose or context.getString(...) elsewhere.\n"
        "If the Chinese is data rather than UI, mark the line with `// i18n-ignore`.",
        file=sys.stderr,
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Flag Chinese string literals in Kotlin sources that never became resources.",
    )
    parser.add_argument("files", nargs="*", help="Kotlin files to check in full")
    parser.add_argument("--diff", metavar="BASE", help="only lines added since BASE (default in CI)")
    parser.add_argument("--all", action="store_true", help="scan every non-test Kotlin source")
    parser.add_argument("--repo-root", "-r", help="repository root (default: git top level)")
    args = parser.parse_args()

    root = Path(args.repo_root).resolve() if args.repo_root else repo_root()

    if args.diff:
        if not rev_exists(args.diff):
            print(
                f"NOTE: base revision {args.diff!r} is not available; skipping the "
                "hardcoded-string guard (shallow clone or a brand-new branch)."
            )
            return 0
        added = added_lines(args.diff)
        per_file: dict[Path, list[tuple[int, str, str]]] = {}
        for rel, numbers in added.items():
            path = root / rel
            if not path.is_file():
                continue  # deleted or renamed away by a later commit
            hits = collect_hits(path, numbers)
            if hits:
                per_file[path] = hits
        return report(root, per_file)

    if args.all:
        per_file = {}
        for path in iter_sources(root):
            hits = collect_hits(path)
            if hits:
                per_file[path] = hits
        return report(root, per_file)

    if not args.files:
        parser.error("pass files, --all, or --diff BASE")
    per_file = {}
    for name in args.files:
        path = Path(name)
        if not path.is_absolute():
            path = root / path
        hits = collect_hits(path)
        if hits:
            per_file[path] = hits
    return report(root, per_file)


if __name__ == "__main__":
    sys.exit(main())
