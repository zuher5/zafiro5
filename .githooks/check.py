#!/usr/bin/env python3
"""Pre-commit code smell check.

Standard library only. By default checks added lines in the staged diff
(incremental); --all checks the whole worktree (full).

All policies are constants at the top of the file; changing policy does not
require changing logic. See .githooks/README.md.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# --------------------------------------------------------------------------
# Policy: change behavior here
# --------------------------------------------------------------------------

# Rule severity: "block" rejects the commit, "warn" only reports.
SEVERITY = {
    "monitor-lock": "block",
    "legacy-comment": "warn",
    "inline-fqn": "warn",
    "log-api": "block",
    "log-tag": "warn",
    "uncancellable-run-catching": "block",
    "bare-catch": "warn",
}

# Path prefixes that are never checked (shared by incremental and full runs).
EXCLUDE_PREFIXES = (
    "libs/",
)

# Keywords for narrative comments. A comment describing what the code used to
# be, or what it no longer is, is always invalid: comments only describe the
# current state. Add new keywords here.
NARRATIVE = re.compile(
    r"不再|改为|改成|此前|原来|原本|原先|原为|曾经|旧版|解决|修复"
    r"|\bno longer\b|\bused to\b|\bformerly\b|\bpreviously\b|\bsolve\b|\bfixed\b"
    r"|\brenamed from\b|\bwas renamed\b",
    re.IGNORECASE,
)

TARGET_SUFFIX = ".kt"

# Log TAGs must start with this prefix (all 59 existing tag constants comply).
LOG_TAG_PREFIX = "niki914_zafiro_"

# Constant names treated as log TAGs (compared lowercase). TAG_PREFIX and the
# like are not included.
TAGISH_NAMES = ("tag", "log_tag", "logtag", "logging_tag")

# --------------------------------------------------------------------------
# Rule implementations
# --------------------------------------------------------------------------

IGNORE_DIRECTIVE = re.compile(r"githooks:ignore(-file)?\s+([\w,\-]+)")
IMPORT = re.compile(r"^\s*import\s+([\w.]+)")
MONITOR_LOCK = re.compile(r"\bsynchronized\s*\(|@Synchronized\b")
FQN = re.compile(
    r"(?<![\w.])(?:com|org|android|androidx|java|kotlin|javax)\.(?:[a-z]\w*\.)+([A-Z]\w*)"
)
STRING_LITERAL = re.compile(
    r'""".*?"""|"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\''
)
ANDROID_LOG_IMPORT = re.compile(r"^\s*import\s+android\.util\.Log\b")
ANDROID_LOG_CALL = re.compile(r"(?<![\w.])Log\.[dviwe]\s*\(")
PRINT_CALL = re.compile(r"(?<![\w.])(?:println|print)\s*\(|System\.out\b")
TAG_DECL = re.compile(r'(?i)(?:val|var)\s+(\w+)\s*(?::\s*[\w<>?.]+\s*)?=\s*"([^"]*)"')
LOGGER_LITERAL_TAG = re.compile(r'Logger\.[dviwe]\s*\(\s*"([^"]*)"')
UNCANCELLABLE_RUN_CATCHING = re.compile(
    r"\b(?:runCatching|mapCatching)\s*(?:[\(\{]|::)|^\s*import\s+[\w.]*\b(?:runCatching|mapCatching)\b"
)
BARE_CATCH = re.compile(
    r"\bcatch\s*\(\s*(?:\w+|_)\s*:\s*(?:[\w.]+\.)?(?:Throwable|Exception)\s*\)"
)


@dataclass(frozen=True)
class LineContext:
    imported: frozenset[str]
    android_log: bool


def is_comment(line: str) -> bool:
    return line.strip().startswith(("//", "*", "/*"))


def strip_strings(line: str) -> str:
    return STRING_LITERAL.sub('""', line)


def raw_lines(lines: list[str]) -> set[int]:
    """Line numbers inside triple-quoted strings (usually inline Python / Markdown)."""
    inside = False
    raw: set[int] = set()
    for number, line in enumerate(lines, 1):
        quotes = line.count('"""')
        if inside or quotes % 2 == 1:
            raw.add(number)
        if quotes % 2 == 1:
            inside = not inside
    return raw


def hit_monitor_lock(line: str, ctx: LineContext) -> bool:
    return not is_comment(line) and MONITOR_LOCK.search(line) is not None


def hit_legacy_comment(line: str, ctx: LineContext) -> bool:
    return is_comment(line) and NARRATIVE.search(line) is not None


def hit_inline_fqn(line: str, ctx: LineContext) -> bool:
    if is_comment(line) or line.strip().startswith(("import ", "package ")):
        return False
    # An inline FQN is deliberate disambiguation when the class is already imported.
    return any(
        m.group(1) not in ctx.imported for m in FQN.finditer(strip_strings(line))
    )


def hit_log_api(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    if ctx.android_log and (ANDROID_LOG_IMPORT.match(line) or ANDROID_LOG_CALL.search(line)):
        return True
    return PRINT_CALL.search(strip_strings(line)) is not None


def hit_log_tag(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    decl = TAG_DECL.search(line)
    if decl and decl.group(1).lower() in TAGISH_NAMES:
        return not decl.group(2).startswith(LOG_TAG_PREFIX)
    literal = LOGGER_LITERAL_TAG.search(line)
    if literal:
        return not literal.group(1).startswith(LOG_TAG_PREFIX)
    return False


def hit_uncancellable_run_catching(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    return UNCANCELLABLE_RUN_CATCHING.search(strip_strings(line)) is not None


def hit_bare_catch(line: str, ctx: LineContext) -> bool:
    if is_comment(line):
        return False
    return BARE_CATCH.search(strip_strings(line)) is not None


RULES = (
    (
        "monitor-lock",
        hit_monitor_lock,
        "uses the synchronized monitor lock",
        "use Mutex / coroutines; if truly required add // githooks:ignore monitor-lock on this or the previous line",
    ),
    (
        "legacy-comment",
        hit_legacy_comment,
        "comment describes a state that has been replaced",
        "describe only current behavior; or add // githooks:ignore legacy-comment on this or the previous line",
    ),
    (
        "inline-fqn",
        hit_inline_fqn,
        "fully qualified name written inline instead of imported",
        "add the import; if disambiguation is intended add // githooks:ignore inline-fqn",
    ),
    (
        "log-api",
        hit_log_api,
        "uses android.util.Log / println, bypassing the business Logger",
        "use com.niki914.logging.Logger; or add // githooks:ignore log-api",
    ),
    (
        "log-tag",
        hit_log_tag,
        f"log TAG does not start with {LOG_TAG_PREFIX}",
        f"rename to {LOG_TAG_PREFIX}<ClassName>; or add // githooks:ignore log-tag",
    ),
    (
        "uncancellable-run-catching",
        hit_uncancellable_run_catching,
        "用了未处理协程取消的标准库 runCatching / mapCatching",
        "改用 runCatchingCancellable / mapCatchingCancellable；确实需要生吞取消时加 // githooks:ignore uncancellable-run-catching",
    ),
    (
        "bare-catch",
        hit_bare_catch,
        "捕获了泛化异常 (Throwable/Exception)",
        "建议改用 runCatchingCancellable / tryOrNull，或捕获具体异常类型；确实需要时加 // githooks:ignore bare-catch",
    ),
)

RULE_NAMES = tuple(name for name, *_ in RULES)


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Finding:
    path: str
    lineno: int
    rule: str
    message: str
    hint: str


def excluded(path: str) -> bool:
    return path.startswith(EXCLUDE_PREFIXES)


def split_rules(text: str) -> set[str]:
    return {t for t in re.split(r"[,\s]+", text) if t}


def file_context(lines: list[str]) -> LineContext:
    imported = frozenset(
        m.group(1).rsplit(".", 1)[-1] for m in map(IMPORT.match, lines) if m
    )
    return LineContext(imported, any(ANDROID_LOG_IMPORT.match(l) for l in lines))


def file_scope_rules(lines: list[str]) -> set[str]:
    """File-level opt-out: `githooks:ignore-file <rule>` anywhere in the file."""
    allowed: set[str] = set()
    for line in lines:
        m = IGNORE_DIRECTIVE.search(line)
        if m and m.group(1):
            allowed |= split_rules(m.group(2))
    return allowed


def line_scope_rules(lines: list[str], lineno: int) -> set[str]:
    """Line-level opt-out: on the offending line itself or the line just above."""
    allowed: set[str] = set()
    for probe in (lines[lineno - 1], lines[lineno - 2] if lineno >= 2 else ""):
        m = IGNORE_DIRECTIVE.search(probe)
        if m and not m.group(1):
            allowed |= split_rules(m.group(2))
    return allowed


def findings_in_line(
    path: str, lineno: int, text: str, ctx: LineContext, allowed: set[str],
    rules: tuple[str, ...],
) -> tuple[list[Finding], int]:
    hits: list[Finding] = []
    ignored = 0
    for name, hit, message, hint in RULES:
        if name not in rules or not hit(text, ctx):
            continue
        if name in allowed or "all" in allowed:
            ignored += 1
            continue
        hits.append(Finding(path, lineno, name, message, hint))
    return hits, ignored


def collect_findings(
    files: dict[str, str],
    candidates: dict[str, list[int]],
    rules: tuple[str, ...] = RULE_NAMES,
) -> tuple[list[Finding], int]:
    """files: path -> full file content; candidates: path -> line numbers (1-based)."""
    findings: list[Finding] = []
    ignored = 0
    for path, linenos in candidates.items():
        source = files.get(path)
        if source is None:
            continue
        lines = source.splitlines()
        ctx = file_context(lines)
        raw = raw_lines(lines)
        file_scope = file_scope_rules(lines)
        for lineno in linenos:
            if lineno < 1 or lineno > len(lines) or lineno in raw:
                continue
            hits, skipped = findings_in_line(
                path,
                lineno,
                lines[lineno - 1],
                ctx,
                file_scope | line_scope_rules(lines, lineno),
                rules,
            )
            findings.extend(hits)
            ignored += skipped
    return findings, ignored


# --------------------------------------------------------------------------
# Candidate lines
# --------------------------------------------------------------------------


def parse_diff(diff_text: str) -> dict[str, list[int]]:
    candidates: dict[str, list[int]] = defaultdict(list)
    path = None
    lineno = 0
    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
        elif line.startswith("@@"):
            m = re.search(r"\+(\d+)", line)
            lineno = int(m.group(1)) if m else 0
        elif line.startswith("+") and not line.startswith("+++"):
            if path and path.endswith(TARGET_SUFFIX) and not excluded(path):
                candidates[path].append(lineno)
            lineno += 1
        elif line.startswith(" "):
            lineno += 1
    return dict(candidates)


def git(root: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", root, *args], text=True, encoding="utf-8", errors="replace", capture_output=True, check=False
    )


def staged_candidates(root: str) -> dict[str, list[int]]:
    result = git(
        root, "-c", "core.quotePath=false", "diff", "--cached", "-U0",
        "--diff-filter=ACMR", "--", f"*{TARGET_SUFFIX}",
    )
    return parse_diff(result.stdout)


def staged_contents(root: str, paths: list[str]) -> dict[str, str]:
    files: dict[str, str] = {}
    for path in paths:
        result = git(root, "show", f":{path}")
        if result.returncode == 0:
            files[path] = result.stdout
    return files


def all_candidates(root: str, scope: list[str]) -> tuple[dict[str, str], dict[str, list[int]]]:
    result = git(root, "ls-files", "-z", f"*{TARGET_SUFFIX}")
    files: dict[str, str] = {}
    candidates: dict[str, list[int]] = {}
    for path in result.stdout.split("\0"):
        if excluded(path) or (scope and not path.startswith(tuple(scope))):
            continue
        try:
            source = Path(root, path).read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        files[path] = source
        candidates[path] = list(range(1, len(source.splitlines()) + 1))
    return files, candidates


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------


def print_findings(findings: list[Finding], ignored: int, scope_note: str) -> int:
    blocking = sum(1 for f in findings if SEVERITY[f.rule] == "block")
    warnings = len(findings) - blocking

    grouped: dict[str, list[Finding]] = defaultdict(list)
    for f in findings:
        grouped[f.path].append(f)

    for path in sorted(grouped):
        for f in sorted(grouped[path], key=lambda x: x.lineno):
            print(f"{f.path}:{f.lineno}: {f.rule}: {f.message}")
            print(f"    → {f.hint}")

    if findings or ignored:
        parts = []
        if blocking:
            parts.append(f"{blocking} blocking")
        if warnings:
            parts.append(f"{warnings} warning(s)")
        parts.append(f"{ignored} ignored")
        print()
        print(f"githooks: {', '.join(parts)}{scope_note}")

    if blocking:
        print("githooks: commit blocked. Fix and re-run git add; use the opt-outs in .githooks/README.md only when truly necessary.")
    return 1 if blocking else 0


def print_stat(findings: list[Finding], files_scanned: int) -> int:
    per_module: dict[str, Counter] = defaultdict(Counter)
    for f in findings:
        per_module[f.path.split("/")[0]][f.rule] += 1

    width = max((len(m) for m in per_module), default=6)
    print(
        f"{'module':<{width}}  {'lock':>5} {'comment':>7} {'fqn':>5} {'log':>5} {'tag':>5} {'rc':>5} {'catch':>6} {'total':>6}"
    )
    for module in sorted(per_module, key=lambda m: -sum(per_module[m].values())):
        c = per_module[module]
        print(
            f"{module:<{width}}  {c['monitor-lock']:>5} {c['legacy-comment']:>7}"
            f" {c['inline-fqn']:>5} {c['log-api']:>5} {c['log-tag']:>5}"
            f" {c['uncancellable-run-catching']:>5} {c['bare-catch']:>6} {sum(c.values()):>6}"
        )
    total = sum(sum(c.values()) for c in per_module.values())
    print()
    print(f"githooks: {files_scanned} file(s), {total} violation(s)")
    return 0


def print_rules() -> int:
    for name, _, message, hint in RULES:
        print(f"{name} ({SEVERITY[name]}): {message}")
        print(f"    → {hint}")
    return 0


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="check.py",
        description="Code smell check: by default only added staged lines (incremental).",
    )
    parser.add_argument(
        "--all", action="store_true",
        help="Check the whole worktree (full); optional path args limit the modules.",
    )
    parser.add_argument(
        "--staged", action="store_true", help="Check added staged lines (default)."
    )
    parser.add_argument(
        "--rule", action="append", choices=RULE_NAMES, dest="rules",
        help="Check only the given rule; repeatable.",
    )
    parser.add_argument("--stat", action="store_true", help="Only print per-module statistics.")
    parser.add_argument("--list-rules", action="store_true", help="List rules.")
    parser.add_argument("scope", nargs="*", help="Path prefixes to limit when using --all.")
    args = parser.parse_args(argv)

    if args.list_rules:
        return print_rules()

    rules = tuple(args.rules or RULE_NAMES)
    root = git(str(Path.cwd()), "rev-parse", "--show-toplevel").stdout.strip()
    if not root:
        print("githooks: not inside a git repository", file=sys.stderr)
        return 0

    if args.all:
        files, candidates = all_candidates(root, args.scope)
        scanned = len(files)
        note = "" if not EXCLUDE_PREFIXES else " (excluded " + ", ".join(EXCLUDE_PREFIXES) + ")"
    else:
        candidates = staged_candidates(root)
        files = staged_contents(root, list(candidates))
        scanned = len(files)
        note = ""

    findings, ignored = collect_findings(files, candidates, rules)
    if args.stat:
        return print_stat(findings, scanned)
    return print_findings(findings, ignored, note)


if __name__ == "__main__":
    sys.exit(main())
