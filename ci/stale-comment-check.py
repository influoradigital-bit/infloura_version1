#!/usr/bin/env python3
"""
stale-comment gate — proof-os class `stale-comment` (F-0075, F-0095, F-0150).

Comments in this repo have been wrong in both directions, and each time the cost was a person
believing them: a "Phase-2 stub" note nearly caused a real fix to be skipped, three comments
described a pre-fix world as current fact, and a prompt-version history entry claimed a bump that
never happened.

"Is this comment true?" is not decidable in general, so this does NOT try. It checks three
specific claims that ARE mechanically falsifiable, one per ledger record:

  Rule 1 (F-0075)  A client method whose live branch calls the backend must not also describe
                   itself as unimplemented. That contradiction is the exact shape of F-0075.
  Rule 2 (F-0095)  Every `path/file.ext:NN` citation must resolve — the file exists and has that
                   many lines. Catches the subset of comment rot where the cited code moved or
                   was deleted. It does NOT catch a comment that cites live code and describes it
                   wrongly, which was F-0095's actual form; see LIMITS below.
  Rule 3 (F-0150)  If the Meera prompt text or tool schemas changed, PROMPT_VERSION must have
                   changed in the same range. Not cosmetic: PROMPT_VERSION is a component of
                   `cache_key_for`, so a missed bump keeps serving a stale cached persona —
                   persona.py's own module docstring states the rule this enforces.

LIMITS — read before trusting a green run:
  A comment that points at live code and simply describes it incorrectly passes every rule here.
  That is most comment rot, and it is why this gate is a floor, not a guarantee.

Usage:
  python ci/stale-comment-check.py                 # rules 1-2 (working tree)
  python ci/stale-comment-check.py --since <ref>   # adds rule 3 over <ref>..working tree
Exit: 0 clean · 1 a stale claim found · 2 sources unreadable
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = ("node_modules", ".venv", ".claude", "dist", "graphify-out", "_archive", "target", "__pycache__")

# Text that asserts a feature does not exist yet.
UNSHIPPED = re.compile(r"NOT_YET_IMPLEMENTED|not yet implemented|ships in Phase\s*\d|is a stub\b", re.I)
# `foo/bar.ts:123` or `Bar.java:45`, optionally a range.
CITATION = re.compile(r"([A-Za-z0-9_\-./]+\.(?:ts|tsx|java|py))\s*:\s*(\d+)(?:\s*-\s*\d+)?")
# Files whose content is baked into the Meera system prompt / tool schemas.
PROMPT_SOURCES = ("influora-ai/app/prompt/", "influora-ai/app/tools/schemas.py")
VERSION_FILE = "influora-ai/app/config.py"


def _walk(root: Path, *suffixes: str):
    for p in root.rglob("*"):
        if p.is_file() and p.suffix in suffixes and not any(k in str(p) for k in SKIP):
            yield p


def _strip_comments(text: str) -> str:
    """Blank out // and /* */ comments, preserving line count and column offsets.

    Rule 1 deliberately inspects CODE only. A comment saying "this used to throw
    NOT_YET_IMPLEMENTED" is a history note — the most useful kind of comment in this repo, and
    flagging it would train people to delete exactly the context that prevents a regression.
    The falsifiable claim is the executable one: a method that both calls the backend and
    rejects as unimplemented.
    """
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j == -1 else j
            out.append(" " * (j - i)); i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append("".join(c if c == "\n" else " " for c in text[i:j])); i = j
        else:
            out.append(text[i]); i += 1
    return "".join(out)


def rule1_unshipped_claims() -> list[str]:
    """A live branch that calls the backend, paired with an unshipped rejection in the same method."""
    out: list[str] = []
    for path in _walk(ROOT / "src", ".ts", ".tsx"):
        lines = _strip_comments(path.read_text(encoding="utf-8", errors="replace")).splitlines()
        for i, line in enumerate(lines):
            if not UNSHIPPED.search(line):
                continue
            # Look back a few lines for a live call in the same ternary/method body.
            window = "\n".join(lines[max(0, i - 6) : i + 1])
            if "http.request" in window or "http.downloadBlob" in window:
                rel = path.relative_to(ROOT).as_posix()
                out.append(
                    f"{rel}:{i + 1} rejects as unshipped, but the live branch just above it calls "
                    f"the backend — one of the two is a lie (F-0075)\n"
                    f"      {line.strip()[:120]}"
                )
    return out


def rule2_citations() -> list[str]:
    index: dict[str, list[Path]] = {}
    for p in ROOT.rglob("*"):
        if p.is_file() and not any(k in str(p) for k in SKIP):
            index.setdefault(p.name, []).append(p)

    out: list[str] = []
    for path in list(_walk(ROOT / "src", ".ts", ".tsx")) + list(
        _walk(ROOT / "influora-api" / "src" / "main" / "java", ".java")
    ) + list(_walk(ROOT / "influora-ai" / "app", ".py")):
        text = path.read_text(encoding="utf-8", errors="replace")
        rel = path.relative_to(ROOT).as_posix()
        for m in CITATION.finditer(text):
            ref, line_no = m.group(1), int(m.group(2))
            cands = index.get(ref.split("/")[-1])
            if not cands:
                out.append(f"{rel} cites {m.group(0)} — no such file exists anywhere (F-0095)")
                continue
            longest = max(
                len(c.read_text(encoding="utf-8", errors="replace").splitlines()) for c in cands
            )
            if line_no > longest:
                out.append(
                    f"{rel} cites {m.group(0)} but that file has only {longest} lines (F-0095)"
                )
    return out


def _resolve(ref: str) -> str | None:
    """A ref git can actually diff against, or None.

    CI hands us `github.event.before`, which is forty zeros on a branch's first push, and an
    empty string when neither a PR base nor a before-sha exists. Failing the build there would
    be a false alarm about the runner's event payload, not about the code — and the fastest way
    to get a gate deleted is for it to cry wolf on an unrelated condition.
    """
    for candidate in (ref, "HEAD~1"):
        if not candidate or set(candidate) == {"0"}:
            continue
        ok = subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", f"{candidate}^{{commit}}"],
            cwd=str(ROOT), capture_output=True, text=True,
        )
        if ok.returncode == 0:
            return candidate
    return None


def rule3_prompt_version(ref: str) -> list[str]:
    since = _resolve(ref)
    if since is None:
        print(
            f"NOTICE: rule 3 skipped — '{ref}' is not a commit this checkout can diff against "
            f"(first push of a branch?). Prompt/PROMPT_VERSION pairing was NOT verified."
        )
        return []
    try:
        changed = subprocess.run(
            ["git", "diff", "--name-only", since],
            cwd=str(ROOT), capture_output=True, text=True, check=True,
        ).stdout.split()
    except (OSError, subprocess.CalledProcessError) as exc:
        return [f"UNREADABLE: git diff against {since} failed ({exc.__class__.__name__})"]

    touched = [f for f in changed if any(f.startswith(s) for s in PROMPT_SOURCES)]
    if not touched:
        return []
    if VERSION_FILE not in changed:
        return [
            f"prompt content changed ({', '.join(touched[:3])}"
            f"{'...' if len(touched) > 3 else ''}) but {VERSION_FILE} is untouched — "
            f"PROMPT_VERSION feeds cache_key_for, so sessions keep serving the old persona (F-0150)"
        ]
    diff = subprocess.run(
        ["git", "diff", since, "--", VERSION_FILE],
        cwd=str(ROOT), capture_output=True, text=True,
    ).stdout
    if not re.search(r"^\+\s*PROMPT_VERSION\s*=", diff, re.M):
        return [
            f"prompt content changed but PROMPT_VERSION itself was not reassigned in "
            f"{VERSION_FILE} — editing the history comment without the version is F-0150 exactly"
        ]
    return []


def main() -> int:
    findings = rule1_unshipped_claims() + rule2_citations()
    if "--since" in sys.argv:
        findings += rule3_prompt_version(sys.argv[sys.argv.index("--since") + 1])

    if any(f.startswith("UNREADABLE") for f in findings):
        for f in findings:
            print(f, file=sys.stderr)
        return 2
    for f in findings:
        print(f"STALE  {f}")
    if findings:
        return 1
    print("stale-comment: OK — no contradicted claims, all citations resolve")
    print("  NOT CHECKED: a comment that cites live code and describes it wrongly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
