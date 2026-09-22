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
  Rule 4 (F-0341)  Every doc a .github/workflows file cites must exist. Workflow comments were
                   the one commented surface no gate read, and three citations had rotted there
                   unnoticed — two naming files that only ever existed inside the nested worktree
                   copies commit 8900bbc committed by accident, one naming a file with no history
                   at all. Scoped to workflows ON PURPOSE: the same doc-existence rule applied
                   repo-wide currently reports ~550 dead .md references across src/, Java and
                   Python, which is a real backlog but a separate decision, not this gate's job
                   to spring on a push. Rules 1-2 are unchanged and still skip workflow files.

  Rule 3 (F-0150)  If the Meera prompt text or tool schemas changed, PROMPT_VERSION must have
                   changed with it. Not cosmetic: PROMPT_VERSION is a component of
                   `cache_key_for`, so a missed bump keeps serving a stale cached persona —
                   persona.py's own module docstring states the rule this enforces.

                   CI-1 (vikram, RULINGS-U-0917.md "Vikram's order: ... -> CI-1"): the previous
                   version of this rule ran ONE `git diff --name-only <since>` over the whole
                   `<since>..working-tree` range and asked only "did PROMPT_VERSION change
                   SOMEWHERE in here" -- so a branch that bumped the version once, then touched
                   prompt content AGAIN with no further bump, passed vacuously (found by vikram
                   while landing K-3's PROMPT_VERSION bump; confirmed as a real defect against a
                   scratch repo, not this tree's history). Rule 3 now makes two checks instead of
                   one:
                     (a) per commit in `<since>..HEAD`: any commit that touches a PROMPT_SOURCES
                         file must have its OWN PROMPT_VERSION differ from its immediate parent's.
                         A later commit cannot coast on an earlier commit's bump.
                     (b) working tree vs HEAD: if any PROMPT_SOURCES file differs from HEAD (an
                         uncommitted edit), the working tree's PROMPT_VERSION must differ from
                         HEAD's. This is what actually matters at CI time, since the checkout IS
                         the working tree being tested.
                   Both need real commit history, not a single-commit shallow clone -- see the
                   fetch-depth note against frontend-checks.yml's checkout step (CI-1 part (c)).

                   CI-1 round 2 (vikram, independently falsified by Meera,
                   MEERA-CI1-PROOF-0917.md): four further gaps, each reproduced in a scratch repo
                   before the fix:
                     (d) reused version (s2): the per-commit check only asked "differs from its
                         immediate parent," so a commit reverting PROMPT_VERSION to an OLDER,
                         already-used value passed as a fresh bump. Now a value must never have
                         appeared before anywhere in VERSION_FILE's history reachable from HEAD
                         (a path-limited `git log`, not a whole-repo walk) -- see
                         `_version_history_reachable_from_head` / `_reused_value`. On a shallow
                         clone this can only see back to the shallow boundary; CI's fetch-depth: 0
                         avoids that, a shallow local run prints a NOTICE.
                     (e) duplicate definition (s5c): `re.search` took the FIRST `PROMPT_VERSION =`
                         match while Python's last-assignment-wins semantics mean the EFFECTIVE
                         value at import time is the LAST -- a decoy could be bumped while the real
                         one never moved, verified via `importlib`. Now parsed with `ast`,
                         requiring exactly one top-level assignment; zero or 2+ is UNREADABLE, never
                         a guess -- see `_extract_prompt_version_from_source`.
                     (f) first push of a new branch (s8-firstpush): `github.event.before` is forty
                         zeros, and the old fallback to HEAD~1 silently narrowed the per-commit walk
                         to the single tip commit for almost every branch in this repo (they all
                         fork off `main`, which already has history), hiding an earlier commit's
                         unbumped change in the same initial push. Now uses
                         `git merge-base HEAD origin/main` -- the branch's real point of
                         divergence -- and fails loud if that cannot be resolved, rather than
                         guessing with HEAD~1. Needs `origin/main` fetched in the checkout.
                     (g) CRLF-only noise (s7): `git diff --name-only` flags a file whose tracked
                         bytes changed even when only the line-ending style did, forcing a spurious
                         bump on mixed-OS teams. "Touches prompt content" now compares
                         line-ending-normalised file content at both revisions, not just the
                         name-only diff list -- see `_really_touches_prompt_sources`.

                   CI-1 round 3 (priya, PRIYA-LASTCALL-CI1-0917.md): two new findings from Priya's
                   own re-check, plus the residual-1 cross-branch ruling, all reproduced in scratch
                   repos first:
                     (h) F1 -- force-push, old tip absent (`forcepush.sh`, s-forcepush): a
                         force-push makes `github.event.before` a SHA no longer reachable in a
                         fresh CI clone. `_resolve` still fell back to `HEAD~1` for this
                         non-empty-but-unresolvable shape -- the same narrowing (f) removed for the
                         all-zero case, still live on this path. `_resolve`'s `HEAD~1` fallback is
                         now REMOVED ENTIRELY: missing, all-zero, AND a non-empty ref that simply
                         doesn't resolve are all one shape, all handled by
                         `_default_branch_merge_base` -- see `_resolve`'s docstring.
                     (i) F2 -- the gate was red on its own protected commit (`waveu_firstpush.sh`,
                         s-waveu): the Wave U branch's first push walks 44 commits back to
                         `origin/main`, and four of them are real, historic, pre-CI-1 F-0150 misses
                         (committed 2026-09-03..09-15, before this rule existed, never deployed).
                         An explicit, reviewed exemption list --
                         `.proof-os/gates/f0150-prompt-version-exempt.txt`, one 40-char SHA + a
                         one-line reason per line -- now lets the per-commit walk and the
                         reused-value check skip exactly those commits, printing a NOTICE for each.
                         A rebase changes the SHAs and turns the build red again on purpose --
                         see `_load_exemptions`.
                     (j) residual 2 -- a fallback-resolved base equal to HEAD itself
                         (`since..HEAD` empty) is the same vacuous-pass shape as (f)/(h), reachable
                         on a direct push to `origin/main` when `before` is zero/missing there too.
                         Now fails loud unless the run is a `pull_request` event (which never
                         legitimately reaches this fallback in the first place) -- see the
                         fallback-equals-HEAD guard in `rule3_prompt_version`. The event name comes
                         from `--event` or, failing that, `GITHUB_EVENT_NAME`.
                     (k) residual 1 -- cross-branch reuse (Meera round 2 finding (i),
                         `s-crossbranch-work`): the reused-value check only ever walked history
                         reachable from HEAD, so two sibling branches that fork from a shared
                         ancestor and each move past it independently can each reuse a value the
                         OTHER now holds, undetected. The check now also walks every
                         `refs/remotes/origin/*` head's own `VERSION_FILE` history -- see
                         `_origin_branch_heads` / `_origin_reused_values`. Fails loud if no
                         `origin/*` head resolves at all, rather than silently narrowing to HEAD.
                         Needs every branch head fetched (see the checkout note on
                         frontend-checks.yml).

LIMITS — read before trusting a green run:
  A comment that points at live code and simply describes it incorrectly passes every rule here.
  That is most comment rot, and it is why this gate is a floor, not a guarantee.

Usage:
  python ci/stale-comment-check.py                            # rules 1-2 (working tree)
  python ci/stale-comment-check.py --since <ref> [--event <name>]   # adds rule 3
    <name> is a GitHub Actions event_name ("pull_request", "push", ...). Falls back to the
    GITHUB_EVENT_NAME environment variable, then to "" (treated as non-pull_request) if neither
    is given.
Exit: 0 clean · 1 a stale claim found · 2 sources unreadable
"""

from __future__ import annotations

import ast
import os
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
# A bare doc reference: `wiki/a/b.md` or `SPEC.md`. Unlike CITATION these carry no line number,
# so only existence is checkable — which is exactly the rot that hid in workflow comments.
DOC_CITATION = re.compile(r"([A-Za-z0-9_\-./]+\.md)\b")
# An escape hatch for the most valuable comment in this repo: the one recording that a cited file
# is GONE. Rule 1's docstring already makes this argument for history notes; the same holds here,
# and without it the only way to document a dead doc is to stop naming it.
IGNORE_MARK = re.compile(r"stale-comment:\s*ignore", re.I)
WORKFLOWS = ROOT / ".github" / "workflows"
# Files whose content is baked into the Meera system prompt / tool schemas.
#
# Priya last-call round 3 (RULINGS-U-0917.md, "Kavya's CI note"): this list missed
# `influora-ai/app/tools/creator_schemas.py` -- the CREATOR-audience tool schemas, a sibling of
# `schemas.py` (the BRAND set) that is just as capable of changing what the model does. Wave U
# changed its `get_brief` description twice without rule 3 ever noticing; both times the
# PROMPT_VERSION bump was manual, not gate-enforced. Added here so the next tool-description edit
# (D-1's `draft_reply` touches this same file) cannot repeat that.
PROMPT_SOURCES = (
    "influora-ai/app/prompt/",
    "influora-ai/app/tools/schemas.py",
    "influora-ai/app/tools/creator_schemas.py",
)
VERSION_FILE = "influora-ai/app/config.py"
# CI-1 round 2 (Meera, MEERA-CI1-PROOF-0917.md #8, s8-firstpush): this repo's actual default
# branch, confirmed from the working tree's own git status. Used as the point of divergence when
# CI hands rule 3 an all-zero or missing 'before' SHA (a branch's first push), instead of the old
# HEAD~1 fallback, which only ever checked the single most recent commit.
DEFAULT_BRANCH = "main"
# CI-1 round 3, F2 (PRIYA-LASTCALL-CI1-0917.md): a reviewed, append-only list of commits the
# per-commit walk and the reused-value check must skip -- historic, pre-CI-1 F-0150 misses that
# would otherwise turn the Wave U branch's own first push red. See `_load_exemptions`.
EXEMPT_FILE = ROOT / ".proof-os" / "gates" / "f0150-prompt-version-exempt.txt"
EXEMPT_LINE = re.compile(r"^([0-9a-f]{40}):\s*(\S.*)$")


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


def _basename_index() -> dict[str, list[Path]]:
    index: dict[str, list[Path]] = {}
    for p in ROOT.rglob("*"):
        if p.is_file() and not any(k in str(p) for k in SKIP):
            index.setdefault(p.name, []).append(p)
    return index


def rule2_citations() -> list[str]:
    index = _basename_index()
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


def _exempt_lines(raw: list[str]) -> set[int]:
    """1-based line numbers exempted by `stale-comment: ignore`.

    A marker exempts every line of the contiguous `#`-comment run it sits in; on a non-comment
    line it exempts only itself.
    """
    exempt: set[int] = set()
    i, n = 0, len(raw)
    while i < n:
        if raw[i].lstrip().startswith("#"):
            j = i
            while j < n and raw[j].lstrip().startswith("#"):
                j += 1
            block = range(i, j)
            if any(IGNORE_MARK.search(raw[k]) for k in block):
                exempt.update(k + 1 for k in block)
            i = j
        else:
            if IGNORE_MARK.search(raw[i]):
                exempt.add(i + 1)
            i += 1
    return exempt


def rule4_workflow_citations() -> list[str]:
    """Docs and code cited from .github/workflows must exist (F-0341).

    Rules 1-2 walk src/, influora-api Java and influora-ai Python only, so nothing has ever read
    a citation inside a workflow file. Both kinds are checked here: `file.ext:NN` through the
    same CITATION grammar rules 2 uses, and bare `*.md` paths, which is the form workflow
    comments actually use and which carries no line number to verify beyond existence.

    `stale-comment: ignore` anywhere in a contiguous run of `#` comment lines exempts that whole
    run, so a note may state that a file is gone without the gate reading the statement as a
    fresh citation. Block scope rather than line scope because these notes are several lines
    long and the marker never lands on the same line as the name it is excusing.
    """
    if not WORKFLOWS.is_dir():
        return []
    index = _basename_index()
    out: list[str] = []
    for path in sorted(_walk(WORKFLOWS, ".yml", ".yaml")):
        rel = path.relative_to(ROOT).as_posix()
        raw = path.read_text(encoding="utf-8", errors="replace").splitlines()
        exempt = _exempt_lines(raw)
        for n, line in enumerate(raw, 1):
            if n in exempt:
                continue
            for m in DOC_CITATION.finditer(line):
                if not index.get(m.group(1).split("/")[-1]):
                    out.append(
                        f"{rel}:{n} cites {m.group(1)} — no such file exists anywhere (F-0341)"
                    )
            for m in CITATION.finditer(line):
                ref, line_no = m.group(1), int(m.group(2))
                cands = index.get(ref.split("/")[-1])
                if not cands:
                    out.append(f"{rel}:{n} cites {m.group(0)} — no such file exists anywhere (F-0341)")
                    continue
                longest = max(
                    len(c.read_text(encoding="utf-8", errors="replace").splitlines()) for c in cands
                )
                if line_no > longest:
                    out.append(
                        f"{rel}:{n} cites {m.group(0)} but that file has only {longest} lines (F-0341)"
                    )
    return out


def _commit_sha(ref: str) -> str | None:
    """The full commit SHA `ref` resolves to in this checkout, or None if it doesn't resolve at
    all (an empty string, a dangling ref, an object git has never heard of)."""
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def _resolve(ref: str) -> str | None:
    """`ref` itself, if it is non-empty, non-all-zero, and resolves to a real commit in this
    checkout -- or None for any of the three ways it can fail to.

    CI-1 round 3 (priya, PRIYA-LASTCALL-CI1-0917.md finding F1, `forcepush.sh`/s-forcepush): the
    old code here fell back to `HEAD~1` for a non-empty ref that simply didn't resolve -- exactly
    the shape a force-push produces, since `github.event.before` is then the OLD tip, which a
    fresh CI clone never fetched (only reachable objects transfer). That fallback silently
    narrowed the per-commit walk to the single most recent commit, hiding the actual force-pushed
    -in violation -- the same bug CI-1 round 2 already fixed for the all-zero/missing case (f).
    There is no longer a fallback HERE at all: missing, all-zero, and "doesn't resolve" are now
    ONE shape, and the caller (`rule3_prompt_version`) sends all three through
    `_default_branch_merge_base` -- the branch's real point of divergence -- and fails loud if
    that can't be resolved either, rather than guessing at a nearby commit either way.
    """
    if not ref or set(ref) == {"0"}:
        return None
    return ref if _commit_sha(ref) is not None else None


def _default_branch_merge_base() -> str | None:
    """`git merge-base HEAD origin/<DEFAULT_BRANCH>`, or None if that ref isn't resolvable in this
    checkout (e.g. `origin/main` was never fetched, or this really is a single-commit repo with no
    default branch history at all).

    CI-1 round 2: used when CI hands rule 3 an all-zero or missing 'before' SHA (a branch's first
    push) -- the branch's actual point of divergence from `main`, not an arbitrary single commit
    back. Requires the workflow's checkout to have fetched `origin/<DEFAULT_BRANCH>` (see the note
    on frontend-checks.yml's checkout step).
    """
    result = subprocess.run(
        ["git", "merge-base", "HEAD", f"origin/{DEFAULT_BRANCH}"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    sha = result.stdout.strip()
    return sha or None


def _is_shallow_repository() -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--is-shallow-repository"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    return result.returncode == 0 and result.stdout.strip() == "true"


def _diff_names(*args: str) -> list[str] | None:
    """`git diff --name-only <args>`, or None if git could not run the diff at all."""
    result = subprocess.run(
        ["git", "diff", "--name-only", *args],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout.split()


def _extract_prompt_version_from_source(source: str) -> str | None:
    """The PROMPT_VERSION value, parsed with `ast` rather than regex -- or None if that value is
    not unambiguous, which this treats as UNREADABLE rather than a guess.

    CI-1 round 2 (Meera, MEERA-CI1-PROOF-0917.md #5c): the old `re.search` took the FIRST
    `PROMPT_VERSION = "..."` match in the file, while Python's own last-assignment-wins semantics
    mean the value actually in effect at import time (and therefore what `cache_key_for` actually
    hashes) is the LAST one. A duplicate or shadowing definition let a decoy get bumped while the
    real, effective value never moved, and the old gate reported green -- verified via `importlib`
    that the runtime value never changed. This requires EXACTLY ONE top-level assignment:

      - zero definitions -> None (UNREADABLE)
      - exactly one plain or annotated string literal (`PROMPT_VERSION = "x"` or
        `PROMPT_VERSION: str = "x"`) -> that string, unambiguously
      - two or more definitions, even if both happen to hold the same string -> None (UNREADABLE);
        "exactly one" is the rule, not "at most one distinct value", because a duplicate that is
        merely coincidentally in sync today is exactly the shape that goes silently out of sync
        tomorrow
      - a non-literal value (an f-string, a name, a function call, string concatenation) -> None
        (UNREADABLE) -- kept failing loud here deliberately; this only handles the unambiguous
        annotated-assignment case properly, it does not try to evaluate arbitrary expressions

    Deliberately top-level only (`tree.body`, not `ast.walk`): a definition inside an `if`/`try`/
    function is exactly the "shadowing conditional" class of risk the docstring above describes,
    and is refused rather than guessed at.
    """
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return None
    found: list[str] = []
    for node in tree.body:
        if isinstance(node, ast.Assign):
            targets, value = node.targets, node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            targets, value = [node.target], node.value
        else:
            continue
        for target in targets:
            if isinstance(target, ast.Name) and target.id == "PROMPT_VERSION":
                if isinstance(value, ast.Constant) and isinstance(value.value, str):
                    found.append(value.value)
                else:
                    return None  # f-string, name, call, concatenation -- refuse to guess
    return found[0] if len(found) == 1 else None


def _prompt_version_at(rev: str) -> str | None:
    """The PROMPT_VERSION value as committed at `rev`, or None if unreadable (see
    `_extract_prompt_version_from_source`)."""
    result = subprocess.run(
        ["git", "show", f"{rev}:{VERSION_FILE}"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    return _extract_prompt_version_from_source(result.stdout)


def _prompt_version_in_working_tree() -> str | None:
    """The PROMPT_VERSION value as it stands on disk right now (committed or not)."""
    path = ROOT / VERSION_FILE
    if not path.is_file():
        return None
    return _extract_prompt_version_from_source(path.read_text(encoding="utf-8", errors="replace"))


def _version_history_reachable_from_head() -> list[tuple[str, str]] | None:
    """[(commit, value), ...], oldest first, for every commit touching VERSION_FILE reachable from
    HEAD -- a path-limited walk (`git log -- <file>`), not a walk of the whole repository's commit
    graph. None only if git could not run the log at all; a SHALLOW clone does not make this fail,
    it silently truncates the list at the shallow boundary (see `_is_shallow_repository`'s call
    site in `rule3_prompt_version` for how that's surfaced).
    """
    result = subprocess.run(
        ["git", "log", "--reverse", "--format=%H", "HEAD", "--", VERSION_FILE],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    history: list[tuple[str, str]] = []
    for commit in result.stdout.split():
        value = _prompt_version_at(commit)
        if value is not None:
            history.append((commit, value))
    return history


def _reused_value(history: list[tuple[str, str]], before_commit: str | None, value: str) -> bool:
    """True if `value` already appears in `history` strictly before `before_commit`'s own entry
    (or anywhere in `history` at all, if `before_commit` is None -- the working-tree case, which
    has no position of its own to compare against, only "has this value ever been committed").

    CI-1 round 2 (Meera #2, s2): the per-commit walk's own "differs from its immediate parent"
    check has no memory of the range's history, so a commit that reverts PROMPT_VERSION to an
    OLDER, already-used value differs from its parent and passed as a fresh bump -- while
    `cache_key_for` would key new (different) prompt content under an old cache slot. This check
    closes that: a value must never have been used before, not merely differ from the last commit.
    """
    for commit, v in history:
        if commit == before_commit:
            return False  # reached the commit's own entry without finding value earlier
        if v == value:
            return True
    return before_commit is None and any(v == value for _, v in history)


def _origin_branch_heads() -> list[str] | None:
    """Every `refs/remotes/origin/*` head, as `origin/<name>` refs usable with `git log` -- or
    None if git could not list them at all (this isn't even a checkout with a remote named
    `origin`).

    CI-1 round 3, residual 1 (PRIYA-LASTCALL-CI1-0917.md, Meera round 2 finding (i),
    `s-crossbranch-work`): the pattern is the BARE `refs/remotes/origin`, not
    `refs/remotes/origin/*` -- a trailing `/*` only matches ONE further path segment (fnmatch
    semantics, `*` does not cross `/`), so it silently missed every branch whose name itself
    contains a slash, which is this repo's own naming convention (`feat/meera-creator-phase-e`,
    `fix/f0390-...`, `claude/...`) -- caught only by actually running this against a real clone of
    this repo in round 3 proof, not by reasoning about the pattern. Uses `%(refname)` (the full
    path) rather than `%(refname:short)` and filters by exact match, because the symbolic
    `refs/remotes/origin/HEAD` alias's SHORT form collapses to the bare string `origin` (dropping
    `/HEAD` entirely) -- also only found by inspecting a real clone's output, and a string match on
    the long form is the only way to exclude exactly that one ref and nothing else. `origin/HEAD`
    just points at another entry already in this list, so walking it would re-walk that branch's
    history a second time under a different name, not add a new one.
    """
    result = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname)", "refs/remotes/origin"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    prefix = "refs/remotes/"
    return [
        full[len(prefix):]
        for full in result.stdout.split()
        if full and full != "refs/remotes/origin/HEAD"
    ]


def _version_values_exclusive_to_ref(ref: str) -> set[str]:
    """Every PROMPT_VERSION value committed to VERSION_FILE on a commit reachable from `ref` but
    NOT reachable from HEAD -- a path-limited `git log <ref> --not HEAD`, the same cost shape as
    `_version_history_reachable_from_head`, just rooted differently and with HEAD's own history
    subtracted out. An unreadable individual commit is skipped rather than failing the whole set
    (a foreign branch's own unrelated history quirks aren't this repo's to enforce).

    CI-1 round 3, residual 1 -- the `--not HEAD` half is NOT optional. `_origin_branch_heads` lists
    every `refs/remotes/origin/*` head, and the ordinary case after a branch's first push is that
    one of those heads IS (or is an ancestor of) HEAD's own branch. Without excluding HEAD's own
    reachable history, every commit's OWN freshly-introduced value would trivially appear in that
    head's history too (it's the same commit), so every fresh bump would falsely "reuse" itself --
    caught only by actually running this against a real clone of this repo (the sim's own
    `origin/feat/meera-creator-phase-b0` remote-tracking ref, identical to HEAD's own branch,
    produced exactly this false positive before this exclusion was added), not by reasoning about
    it. A true sibling branch's independently-committed values (the case this check exists to
    catch, e.g. `feat/meera-creator-phase-e`) are unaffected: none of its own commits are reachable
    from HEAD, so `--not HEAD` excludes nothing real from it.
    """
    result = subprocess.run(
        ["git", "log", "--format=%H", ref, "--not", "HEAD", "--", VERSION_FILE],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if result.returncode != 0:
        return set()
    values: set[str] = set()
    for commit in result.stdout.split():
        v = _prompt_version_at(commit)
        if v is not None:
            values.add(v)
    return values


def _origin_reused_values(origin_heads: list[str]) -> dict[str, str]:
    """{value: first origin/* head it was found on} across every head's own VERSION_FILE history
    minus HEAD's -- computed ONCE per rule-3 run, not once per commit.

    CI-1 round 3, residual 1: `_reused_value` only ever asked "has HEAD's own reachable history
    used this value before" -- a branch that never shares history with HEAD (a true sibling, both
    forked off the same ancestor and each moved on) is invisible to that walk. This closes the gap
    Meera reproduced live in this repo (`feat/meera-creator-phase-e` sitting on a value b0 has
    already moved past): a value must be new across every pushed branch, not just this one.

    Performance note (round-3 proof, timed against the real 44-commit Wave U range): an earlier
    version of this check re-walked every origin head's full history from scratch for EACH commit
    in the per-commit walk that introduced a fresh bump -- redundant work that showed up as
    inconsistent, occasionally double-digit-second timings on this repo's 7 origin heads. Building
    this map once, up front, makes the per-commit cost in the loop below a plain dict lookup.
    """
    values: dict[str, str] = {}
    for head in origin_heads:
        for v in _version_values_exclusive_to_ref(head):
            values.setdefault(v, head)
    return values


def _load_exemptions() -> tuple[dict[str, str], list[str]]:
    """({sha: reason}, [malformed-line errors]) from EXEMPT_FILE.

    CI-1 round 3, F2 (PRIYA-LASTCALL-CI1-0917.md): one 40-character lowercase-hex SHA, a colon,
    and a one-line reason per entry. Blank lines and `#`-comment lines are skipped for
    readability; anything else that doesn't match is a MALFORMED LINE -- collected as an error
    rather than silently dropped or silently treated as "the file is empty", so a typo can never
    silently exempt nothing (looks like it works) or, worse, silently exempt everything (a parser
    bug that matches too much). The caller turns any error here into a loud (exit 2) failure and
    does not apply the well-formed entries from a file that also contains a bad line, per Priya's
    ruling that this list only grows through a visible, correct diff.

    Deliberately does NOT check whether a listed SHA appears in the commit range being walked --
    that is normal and expected for the common case (a later, narrower push after Wave U lands,
    where none of these four historic SHAs are in range at all), and Priya's ruling is explicit
    that "a listed SHA not present in the range is ignored." The caller's per-commit loop already
    only ever looks up `commit in exempt`, which is a no-op for any listed SHA outside the walked
    range -- there is nothing further to check here.
    """
    if not EXEMPT_FILE.is_file():
        return {}, []
    exempt: dict[str, str] = {}
    errors: list[str] = []
    rel = EXEMPT_FILE.relative_to(ROOT).as_posix()
    for lineno, raw in enumerate(
        EXEMPT_FILE.read_text(encoding="utf-8", errors="replace").splitlines(), 1
    ):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = EXEMPT_LINE.match(line)
        if not m:
            errors.append(
                f"{rel}:{lineno} is not a valid F-0150 exemption line (expected "
                f"'<40-char-lowercase-hex-sha>: <reason>'), refusing to guess at its intent: "
                f"{raw!r}"
            )
            continue
        exempt[m.group(1)] = m.group(2)
    return exempt, errors


def _content_at(rev: str, path: str) -> str | None:
    """A file's content at a revision, or None if it does not exist there."""
    result = subprocess.run(
        ["git", "show", f"{rev}:{path}"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    return result.stdout if result.returncode == 0 else None


def _content_in_working_tree(path: str) -> str | None:
    p = ROOT / path
    if not p.is_file():
        return None
    return p.read_text(encoding="utf-8", errors="replace")


def _normalise_line_endings(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _touches_prompt_sources(files: list[str]) -> list[str]:
    """Cheap first-pass filter, by path prefix only. Not the final word on whether a file was
    REALLY touched for version-pairing purposes -- see `_really_touches_prompt_sources`."""
    return [f for f in files if any(f.startswith(s) for s in PROMPT_SOURCES)]


def _really_touches_prompt_sources(old_rev: str, new_rev: str | None, name_touched: list[str]) -> list[str]:
    """Filters `name_touched` (already limited to PROMPT_SOURCES paths by `_touches_prompt_sources`)
    down to files whose NORMALISED content actually differs between `old_rev` and `new_rev` (`None`
    for `new_rev` means the working tree).

    CI-1 round 2 (Meera #7, s7): `git diff --name-only` flags a file whose tracked BYTES changed
    even when the only difference is line-ending style (LF vs CRLF) -- real for git, meaningless
    for the Meera prompt/schema TEXT the version pairing exists to protect. On a team with mixed
    `core.autocrlf`/`.gitattributes` settings across Windows and Linux contributors, a line-ending-
    only commit forced a spurious PROMPT_VERSION bump. A file that was created, deleted or renamed
    (content missing on either side) is always treated as a real touch -- that is a structural
    change, not a line-ending nuance.
    """
    out: list[str] = []
    for f in name_touched:
        old_text = _content_at(old_rev, f)
        new_text = _content_in_working_tree(f) if new_rev is None else _content_at(new_rev, f)
        if old_text is None or new_text is None:
            out.append(f)
            continue
        if _normalise_line_endings(old_text) != _normalise_line_endings(new_text):
            out.append(f)
    return out


def rule3_prompt_version(ref: str, event_name: str = "") -> list[str]:
    """F-0150, CI-1: a fresh, never-before-used PROMPT_VERSION for every change to prompt content,
    not just one bump somewhere in the whole pushed range (see the CI-1 note in this module's
    docstring, and the CI-1 round 2 / round 3 notes on the helper functions above).

    `event_name` is the GitHub Actions `event_name` for this run ("pull_request", "push", ... or
    "" if unknown) -- CI-1 round 3, residual 2: it is only consulted once, to decide whether a
    fallback-resolved base equal to HEAD itself is the vacuous-pass shape this rule refuses, or a
    real (if very unusual) pull_request state. See the guard just below `_default_branch_merge_base`.
    """
    out: list[str] = []

    # CI-1 round 3 (F1, PRIYA-LASTCALL-CI1-0917.md, forcepush.sh/s-forcepush): `_resolve` no longer
    # has a `HEAD~1` fallback of its own (see its docstring) -- missing, all-zero, AND a non-empty
    # ref that simply doesn't resolve (a force-push's old tip, absent from a fresh CI clone) are
    # now ONE shape, all routed through the branch's real point of divergence from DEFAULT_BRANCH.
    since = _resolve(ref)
    used_fallback = since is None
    if used_fallback:
        since = _default_branch_merge_base()
        if since is None:
            out.append(
                f"UNREADABLE: '{ref or '(empty)'}' does not resolve to a commit in this checkout "
                f"(a first push, a force-push whose old tip was never fetched, or a bad --since), "
                f"and 'origin/{DEFAULT_BRANCH}' could not be resolved either — refusing to guess "
                f"with a nearby commit the way the old HEAD~1 fallback did (CI-1 round 3 removed "
                f"it entirely; PRIYA-LASTCALL-CI1-0917.md F1). Fetch origin/{DEFAULT_BRANCH} (see "
                f"the checkout note on frontend-checks.yml) or pass an explicit --since."
            )
            return out

        # CI-1 round 3, residual 2 (PRIYA-LASTCALL-CI1-0917.md; Meera round 2 finding (iv)): a
        # fallback-resolved base that turns out to equal HEAD itself makes `since..HEAD` EMPTY, so
        # the per-commit walk (a) below silently checks nothing — the exact vacuous pass this gate
        # exists to close. Concretely reachable on a direct push to `origin/main` when `before` is
        # zero/missing there too, once the checkout's own fetch step has already moved
        # `origin/main` to HEAD's own tip. A `pull_request` event is exempted: its `ref` is always
        # a real, non-empty `base.sha` that never legitimately reaches this fallback in the first
        # place, so if this guard ever fires on one, it isn't the vacuous-push shape it targets.
        head_sha = _commit_sha("HEAD")
        since_sha = _commit_sha(since)
        if (
            event_name != "pull_request"
            and head_sha is not None
            and since_sha is not None
            and head_sha == since_sha
        ):
            out.append(
                f"UNREADABLE: merge-base(HEAD, origin/{DEFAULT_BRANCH}) resolved to HEAD itself "
                f"on a '{event_name or '(unknown)'}' event — since..HEAD would be empty and the "
                f"per-commit walk would silently check nothing, reproducing the exact vacuous "
                f"pass this gate exists to close (MEERA-CI1-PROOF-0917.md round 2 #iv). If this "
                f"really is a pull_request, pass --event pull_request; otherwise pass the real "
                f"'before' SHA."
            )
            return out
    else:
        assert since is not None  # for type-checkers: the `used_fallback` branch above returns

    if _is_shallow_repository():
        print(
            "NOTICE: this checkout is a shallow clone. The reused-PROMPT_VERSION check below can "
            "only see VERSION_FILE's history back to the shallow boundary -- a value reused from "
            "before that boundary would not be caught. CI runs with fetch-depth: 0 (full history) "
            "specifically so this NOTICE should never print there; a local run may be shallower."
        )
    history = _version_history_reachable_from_head()
    if history is None:
        out.append(
            f"UNREADABLE: could not walk {VERSION_FILE}'s history reachable from HEAD "
            f"('git log -- {VERSION_FILE}' failed) — the reused-value check (F-0150) could not run"
        )

    # CI-1 round 3, residual 1 (PRIYA-LASTCALL-CI1-0917.md; Meera round 2 finding (i),
    # s-crossbranch-work): the reused-value check above is scoped to HEAD's own reachable history,
    # which cannot see a true sibling branch's independent commits. Fails loud rather than
    # silently narrowing to HEAD-only if no origin/* head resolves at all.
    origin_heads = _origin_branch_heads()
    if not origin_heads:
        out.append(
            "UNREADABLE: no 'refs/remotes/origin/*' branch heads resolved in this checkout — the "
            "cross-branch PROMPT_VERSION reuse check (residual 1, PRIYA-LASTCALL-CI1-0917.md) "
            "cannot run without them, and silently falling back to HEAD's own history only would "
            "hide the exact gap that check exists to close. Fetch every branch head explicitly "
            "(git fetch origin '+refs/heads/*:refs/remotes/origin/*'; see the checkout note on "
            "frontend-checks.yml)."
        )
        origin_heads = []
    origin_reused = _origin_reused_values(origin_heads) if origin_heads else {}

    # CI-1 round 3, F2 (PRIYA-LASTCALL-CI1-0917.md, waveu_firstpush.sh/s-waveu): commits this list
    # names are skipped entirely by both checks below, with a NOTICE printed for each. A malformed
    # line in the file is a loud failure, never a silent partial read — see `_load_exemptions`.
    exempt, exempt_errors = _load_exemptions()
    for err in exempt_errors:
        out.append(f"UNREADABLE: {err}")

    # (a) Per commit: walk since..HEAD and check each commit against its OWN parent, so a later
    # commit cannot coast on an earlier commit's bump. `git rev-list` on an empty range (since is
    # HEAD) yields no commits, which is correct: there is nothing new to check, and check (b)
    # below still runs regardless.
    #
    # If `since` is NOT an ancestor of HEAD (a force-push rewrote the base, or the two sides share
    # no history), `since..HEAD` is still well-formed git syntax, but it walks every commit
    # reachable from HEAD and not from `since` — which can include HEAD's own root commit. A root
    # commit has no parent to diff against, so a naive per-commit walk over that range fails with
    # a misleading "looks shallow" error for a completely different reason. Detected explicitly so
    # the message says what actually happened, and so rule 3 does not quietly report nothing for a
    # range it cannot walk.
    is_ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", since, "HEAD"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if is_ancestor.returncode == 1:
        out.append(
            f"UNREADABLE: '{since}' is not an ancestor of HEAD (a force-push or rebase changed "
            f"the base?) — the per-commit walk (a) cannot run over a non-linear range. The "
            f"working-tree-vs-HEAD check (b) below still ran."
        )
        commits: list[str] = []
    elif is_ancestor.returncode != 0:
        out.append(
            f"UNREADABLE: 'git merge-base --is-ancestor {since} HEAD' could not run — is the "
            f"checkout shallow? (needs fetch-depth: 0; see CI-1 note on frontend-checks.yml)"
        )
        commits = []
    else:
        rev_list = subprocess.run(
            ["git", "rev-list", "--reverse", f"{since}..HEAD"],
            cwd=str(ROOT), capture_output=True, text=True,
        )
        if rev_list.returncode != 0:
            out.append(f"UNREADABLE: git rev-list {since}..HEAD failed — is the checkout shallow?")
            commits = []
        else:
            commits = rev_list.stdout.split()
    for commit in commits:
        if commit in exempt:
            print(
                f"NOTICE: F-0150 exemption — commit {commit[:10]} skipped ({exempt[commit]}; "
                f"see {EXEMPT_FILE.relative_to(ROOT).as_posix()})"
            )
            continue
        parent = f"{commit}^"
        changed = _diff_names(parent, commit)
        if changed is None:
            # No parent (root commit) or a shallow checkout cut the history off here. Either way
            # this gate cannot see what changed, so it says so rather than staying silent.
            out.append(
                f"UNREADABLE: could not diff {commit[:10]} against its parent — the checkout is "
                f"likely shallow (needs fetch-depth: 0; see CI-1 note on frontend-checks.yml)"
            )
            continue
        name_touched = _touches_prompt_sources(changed)
        if not name_touched:
            continue
        touched = _really_touches_prompt_sources(parent, commit, name_touched)
        if not touched:
            continue  # CI-1 round 2: CRLF-only (or other normalisation-only) change; not a real touch
        before = _prompt_version_at(parent)
        after = _prompt_version_at(commit)
        if before is None or after is None:
            out.append(
                f"UNREADABLE: could not read {VERSION_FILE} at {commit[:10]} or its parent to "
                f"check the F-0150 pairing (zero or 2+ top-level PROMPT_VERSION assignments, or a "
                f"non-literal value, is refused rather than guessed at — see "
                f"_extract_prompt_version_from_source)"
            )
            continue
        if before == after:
            out.append(
                f"commit {commit[:10]} touches prompt content ({', '.join(touched[:3])}"
                f"{'...' if len(touched) > 3 else ''}) but PROMPT_VERSION is still '{after}', "
                f"unchanged from its parent — this commit needs its own bump, not one borrowed "
                f"from an earlier commit in the range (F-0150)"
            )
        elif history is not None and _reused_value(history, commit, after):
            out.append(
                f"commit {commit[:10]} bumps PROMPT_VERSION to '{after}', but that value has "
                f"already appeared earlier in {VERSION_FILE}'s history reachable from HEAD — "
                f"reusing an old cache key for new prompt content is the exact harm this rule "
                f"exists to prevent (F-0150); pick a value that has never been used"
            )
        else:
            origin_hit = origin_reused.get(after)
            if origin_hit:
                out.append(
                    f"commit {commit[:10]} bumps PROMPT_VERSION to '{after}', but that value "
                    f"already appears in {origin_hit}'s history of {VERSION_FILE} — reusing a "
                    f"value another branch already holds risks merging two different prompts "
                    f"under one cache key if they ever share a cache (F-0150 residual 1, "
                    f"PRIYA-LASTCALL-CI1-0917.md); pick a value that has never been used on HEAD "
                    f"or any origin/* branch"
                )

    # (b) Working tree vs HEAD: an uncommitted prompt-content edit needs its own bump too. This
    # is the check that matters most at CI time, because the checkout under test IS a working
    # tree (the PR's head commit, or a push's tip) being compared to what came before it.
    wt_changed = _diff_names("HEAD")
    if wt_changed is None:
        out.append("UNREADABLE: git diff --name-only HEAD failed")
    else:
        wt_name_touched = _touches_prompt_sources(wt_changed)
        wt_touched = _really_touches_prompt_sources("HEAD", None, wt_name_touched) if wt_name_touched else []
        if wt_touched:
            head_version = _prompt_version_at("HEAD")
            wt_version = _prompt_version_in_working_tree()
            if head_version is None or wt_version is None:
                out.append(
                    f"UNREADABLE: could not read PROMPT_VERSION from HEAD or the working tree "
                    f"to check the F-0150 pairing (zero or 2+ top-level PROMPT_VERSION "
                    f"assignments, or a non-literal value, is refused rather than guessed at)"
                )
            elif head_version == wt_version:
                out.append(
                    f"working tree touches prompt content ({', '.join(wt_touched[:3])}"
                    f"{'...' if len(wt_touched) > 3 else ''}) but PROMPT_VERSION is still HEAD's "
                    f"'{head_version}' — uncommitted prompt changes need their own bump too "
                    f"(F-0150)"
                )
            elif history is not None and _reused_value(history, None, wt_version):
                out.append(
                    f"working tree bumps PROMPT_VERSION to '{wt_version}', but that value has "
                    f"already appeared earlier in {VERSION_FILE}'s history reachable from HEAD — "
                    f"reusing an old cache key for new prompt content is the exact harm this rule "
                    f"exists to prevent (F-0150); pick a value that has never been used"
                )
            else:
                origin_hit = origin_reused.get(wt_version)
                if origin_hit:
                    out.append(
                        f"working tree bumps PROMPT_VERSION to '{wt_version}', but that value "
                        f"already appears in {origin_hit}'s history of {VERSION_FILE} — reusing a "
                        f"value another branch already holds risks merging two different prompts "
                        f"under one cache key if they ever share a cache (F-0150 residual 1, "
                        f"PRIYA-LASTCALL-CI1-0917.md); pick a value that has never been used on "
                        f"HEAD or any origin/* branch"
                    )

    return out


def _event_name_arg() -> str:
    """The GitHub Actions event_name for this run: `--event <name>` if given, else the
    GITHUB_EVENT_NAME environment variable Actions sets automatically, else "" (treated as
    non-pull_request, the strict default) -- CI-1 round 3, residual 2. An explicit CLI arg exists
    so a local or scratch-repo run can exercise the pull_request path without a real Actions
    environment."""
    if "--event" in sys.argv:
        return sys.argv[sys.argv.index("--event") + 1]
    return os.environ.get("GITHUB_EVENT_NAME", "")


def main() -> int:
    findings = rule1_unshipped_claims() + rule2_citations() + rule4_workflow_citations()
    if "--since" in sys.argv:
        ref = sys.argv[sys.argv.index("--since") + 1]
        findings += rule3_prompt_version(ref, _event_name_arg())

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
    print("  NOT CHECKED: dead .md references outside .github/workflows (~550 today, rule 4 note)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
