#!/usr/bin/env python3
"""gates/F-0663-test-pins-subject.py — origin: F-0663 (test-pins-wrong-subject).

THE CLASS. Four Wave-3 regression tests were green while their findings stayed broken,
because each pinned something other than the defect:

  F-0434  asserted the outgoing PATCH body carried `collabs`; PortfolioPatchRequest has no
          such field, so Jackson dropped it and nothing ever persisted.
  F-0440  pinned src/pages/brand-chat.tsx — an already-correct sibling with no diff — while
          the real handler is src/components/brand/deals/deal-room-dashboard.tsx. The
          ledger's `where` for F-0440 named a DIRECTORY (src/components/brand/deal-room)
          containing no accept handler at all. That wrong path is the documented cause.
  F-0640  rendered CreatorDealContractTab in isolation with a `milestones` prop its only
          production caller never passed. An inverse probe that ADDED the missing wiring
          returned an IDENTICAL pass — the test could not detect the defect in either
          state. Its `where` named a file that does not exist on disk.
  F-0341  asserted on an internal record instead of the response DTO the controller
          actually serialises.

A green test that cannot fail against the defect is worse than no test: it retires the
finding from attention.

WHAT THIS GATE PROVES, AND NOTHING MORE. It is a SUBJECT-RESOLVABILITY check, not a
test-quality check. No static check can read a test and decide whether it would have failed
against a defect that is now fixed — that needs the test run against the old code, which is
falsification, an agent's act, not a gate's. What IS mechanically decidable is the
precondition all four violated: is the subject identifiable, and does the test filed
against it go anywhere near it?

  CHECK A (fails) — ledger subject resolvability.
      An OPEN row whose `where` names path(s) into this repo must resolve at least one of
      them to a file that exists. Such a row pointing only at phantoms is a trap primed for
      the next fix agent: it cannot be opened, so the agent picks the nearest plausible
      file, which is exactly how F-0440 and F-0640 produced green tests with no fix. Cheap,
      total, and it would have prevented two of the four. CLOSED rows are reported, not
      failed — a fix that renames or deletes its own subject leaves a dead `where`
      legitimately. A multi-path `where` where some paths resolve is advisory: usable.

  CHECK B (fails) — a test FILED under an F-id must reach that row's file.
      "Filed under" is deliberately narrow: the id is in the test's FILENAME, or is the
      first id named in the file's FIRST comment block, and only when that block precedes
      the first declaration. Every other mention is a cross-reference, not a claim —
      ShopifyWebhookIdempotencyTest is filed under F-0619 and merely cites F-0521, and
      failing it for that would be a lie. "Reaches" is deliberately generous: a direct
      import, a module that itself imports the subject, or a NARROWLY-shared module (≤5
      in-repo importers) the subject also imports, so a test of an extracted helper counts;
      for a row naming several paths, reaching any one of them passes. Mentioning the
      subject in a COMMENT does not count: brand-chat-accept-recovery.test names
      deal-room-dashboard.tsx in prose and still imports only brand-chat.

  CHECK C (fails) — a subject claimed by a test must not be a directory.
      A directory `where` is right for a scope-level finding (F-0651: "all 11 test classes
      in this package are Mockito-only"), so on its own it is only ADVISORY. Once a test is
      filed under that id the subject must be a file, or there is nothing to check the test
      against. That is the F-0440 moment exactly.

  CHECK D (advisory) — test-only props on a React subject.
      Props a claiming .test.tsx passes to the subject component that NO production render
      of it passes: the F-0640 signature. Advisory because a spread, a wrapper, a route
      element or React.createElement defeats the scan, and a false red here would teach
      people to ignore the gate.

FALSIFIED 2026-09-05, four ways, all against real artifacts rather than a fixture:
  1. HISTORICAL LEDGER. `git show HEAD:.proof-os/ledger/failures.jsonl` is the ledger as it
     stood before today's path corrections. Run against it (--ledger), the gate flags
     F-0640 "where names a file that does not exist" (CHECK A) and F-0440 "where is a
     DIRECTORY while a test is already filed under the id" (CHECK C) — the two documented
     root causes, caught for the right reason, exit 1.
  2. GREEN PATH. Repointing every row the gate names at the file its filed test actually
     imports takes it to exit 0. So each finding is attributable to a pointer, and the pass
     path is reachable — except F-0280, where NO `where` edit can help: that test resolves
     zero in-repo imports, so no product file exists for the ledger to point at.
  3. CHECK D, BOTH STATES. On a copy of src/ with `milestones={liveContract?.milestones}`
     removed from creator-chat.tsx — the exact pre-fix production state of F-0640 — CHECK D
     reports `<CreatorDealContractTab milestones>` as passed only by the test; with the line
     restored it reports nothing. That two-state discrimination is precisely what F-0640's
     own regression test lacked (its inverse probe returned an identical pass either way).
  4. EXIT CODES. Absent ledger, empty ledger and unparseable ledger each exit 2 (never 0);
     an unknown flag exits 64; blind spots print on all four.

Exit: 0 proved · 1 a subject is unresolvable or unreached · 2 nothing could be read · 64 usage

Usage: python .proof-os/gates/F-0663-test-pins-subject.py [--ledger PATH]
       --ledger falsifies the gate against a historical ledger, e.g.
         git show HEAD:.proof-os/ledger/failures.jsonl > /tmp/old.jsonl
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_LEDGER = ROOT / ".proof-os" / "ledger" / "failures.jsonl"

BLIND = [
    "whether a test that DOES reach its subject exercises the defect — only running it "
    "against the old code proves that, and that is falsification, not a check",
    "the F-0434 shape (asserting a request field the backend DTO never accepts): that is "
    "FE/BE contract drift, owned by gates/dto-drift.py, not implemented here",
    "the F-0341 shape (asserting on an internal record instead of the serialised response "
    "DTO) — no assertion-target analysis exists in this gate",
    "a test filed under an F-id only in prose that is NOT the file's first F-id mention: "
    "counted as a cross-reference and reported, never failed, because a citation and a "
    "claim are indistinguishable to a parser",
    "cross-language pairs (a .tsx test filed under a .java subject, or the reverse): "
    "reported, never failed — an import graph cannot span the two",
    "Java subject-touch is a class-name match in code, which cannot tell a real exercise "
    "from an unused import; and test-only-prop analysis is React-only",
    "which of several paths in a multi-path `where` is the real site: reaching ANY of them "
    "passes, so a test pinning the harmless one of two named files still reads as reached",
    "rows whose `where` is prose or points outside this repo (skills tree, VPS paths): "
    "counted and skipped, never failed",
    "whether a `where` that DOES resolve is the RIGHT file — a path can exist and still be "
    "the wrong subject; only a human reading the symptom can say",
    "a raw apostrophe in JSX text can truncate CHECK D's tag scan (comments are lexed out, "
    "JSX text is not), which loses props rather than inventing them",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# --------------------------------------------------------------------------- args
argv = sys.argv[1:]
ledger_path = DEFAULT_LEDGER
i = 0
while i < len(argv):
    if argv[i] == "--ledger" and i + 1 < len(argv):
        ledger_path = Path(argv[i + 1])
        i += 2
    else:
        die(64, "usage: F-0663-test-pins-subject.py [--ledger PATH]",
            ["everything: the gate never ran"])

# --------------------------------------------------------------------------- ledger
if not ledger_path.is_file():
    die(2, "· ledger not readable at %s — nothing to check" % ledger_path,
        ["everything: there was no ledger to read"])

rows, malformed = [], 0
try:
    for line in ledger_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            malformed += 1
except OSError as exc:
    die(2, "· cannot read ledger (%s)" % exc.__class__.__name__,
        ["everything: the ledger could not be opened"])

if not rows:
    die(2, "· ledger parsed to zero rows (%d malformed line(s)) — 'no rows, therefore every "
           "subject is valid' is the vacuous pass this gate exists to prevent" % malformed,
        ["everything: no ledger rows were parsed"])

try:
    TOP_LEVEL = set(os.listdir(ROOT))
except OSError as exc:
    die(2, "· cannot list repo root (%s)" % exc.__class__.__name__,
        ["everything: the repo root could not be read"])

# --------------------------------------------------------------------------- lexing
def strip_comments_js(src: str) -> str:
    """Blank out // and /* */ WITHOUT letting an apostrophe inside a comment open a string.
    Measured: without this, `// priority over the deal's dealValue` swallowed the rest of a
    JSX tag and reported four correctly-passed props as test-only."""
    out, i, n, state = [], 0, len(src), None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state is None:
            if c == "/" and nxt == "/":
                state, i = "line", i + 2
                out.append("  ")
                continue
            if c == "/" and nxt == "*":
                state, i = "block", i + 2
                out.append("  ")
                continue
            if c in "\"'`":
                state = c
            out.append(c)
            i += 1
            continue
        if state == "line":
            if c == "\n":
                state = None
                out.append(c)
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block":
            if c == "*" and nxt == "/":
                state, i = None, i + 2
                out.append("  ")
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if c == "\\" and i + 1 < n:          # keep escaped chars verbatim
            out.append(src[i])
            out.append(src[i + 1])
            i += 2
            continue
        if c == state:
            state = None
        out.append(c)
        i += 1
    return "".join(out)


JAVA_COMMENTS = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)

# --------------------------------------------------------------------------- where
def is_repo_path(tok: str) -> bool:
    """Conservative: a token is a repo path only when its first segment is a real top-level
    entry of THIS repo. Everything else is prose or out-of-tree and is skipped, not failed."""
    return "/" in tok and tok.split("/")[0] in TOP_LEVEL


def where_paths(where: str) -> list:
    """EVERY repo path a `where` names, in order, stripped of :LINE / :LINE,LINE / :method()
    annotations. `where` is prose- AND multi-path-tolerant by convention:
      'src/pages/brand-chat.tsx:1014-1028,1931-1935' -> [src/pages/brand-chat.tsx]
      'src/pages/creator-login.tsx:64,src/pages/creator-onboarding.tsx:231'
                                                    -> [both]  (measured: taking only the
         first turned F-0275/F-0286/F-0457 into false reds — their test pins the 2nd path)
      'arjun/SKILL.md pipeline stages 3-4'           -> []      (out of tree, skipped)"""
    out = []
    for tok in re.split(r"[,\s]+", (where or "").strip()):
        # F-0178's where is 'public/og-image-placeholder.txt (index.html:21 references
        # public/og-image.png)' — without stripping the paren the 2nd path never resolves.
        # Trailing punctuation only. Stripping it from the FRONT too ate the dot in
        # `.proof-os/gates/...`, which then failed is_repo_path and silently SKIPPED three
        # open rows — a false green, the worst outcome available to this gate.
        tok = tok.split(":")[0].lstrip("(\"'`").rstrip(")]}>\"'`,;.")
        if tok and is_repo_path(tok) and tok not in out:
            out.append(tok)
    return out


_basename_index = None
SKIP_DIRS = {"node_modules", ".git", "target", "dist", "build", ".next", "coverage",
             ".runtime"}   # .proof-os/.runtime holds vendored copies of the plugin itself


def basename_index() -> dict:
    global _basename_index
    if _basename_index is None:
        idx: dict = {}
        for dirpath, dirnames, filenames in os.walk(ROOT):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for fn in filenames:
                idx.setdefault(fn, []).append(
                    Path(dirpath, fn).relative_to(ROOT).as_posix())
        _basename_index = idx
    return _basename_index


# --------------------------------------------------------------------------- test index
TEST_ROOTS = [ROOT / "src", ROOT / "influora-api" / "src" / "test",
              ROOT / ".proof-os" / "gates"]
TEST_NAME = re.compile(r"\.(test|spec)\.[jt]sx?$|Test\.java$|Tests\.java$|IT\.java$")
FID = re.compile(r"F[-_ ]?0(\d{3})\b|\bf0(\d{3})\b")


def fids_in(text: str) -> list:
    """F-ids in order of first appearance."""
    seen, out = set(), []
    for m in FID.finditer(text):
        fid = "F-0" + (m.group(1) or m.group(2))
        if fid not in seen:
            seen.add(fid)
            out.append(fid)
    return out


tests, unreadable_tests = [], []
for troot in TEST_ROOTS:
    if not troot.is_dir():
        continue
    for p in sorted(troot.rglob("*")):
        if not p.is_file() or not TEST_NAME.search(p.name):
            continue
        if SKIP_DIRS & set(p.parts) or "_archive" in p.parts:
            continue
        try:
            tests.append((p.relative_to(ROOT).as_posix(), p,
                          p.read_text(encoding="utf-8", errors="replace")))
        except OSError as exc:
            unreadable_tests.append("%s (%s)" % (p, exc.__class__.__name__))

if not tests:
    die(2, "· no test files found under %s — 'no tests, therefore no test pins the wrong "
           "subject' is exactly the vacuous pass this gate exists to prevent"
           % ", ".join(str(t) for t in TEST_ROOTS),
        ["everything after the ledger read: no tests were discovered"])

# A test is FILED under the id in its FILENAME, or under the FIRST id named in its HEADER:
# the file's FIRST comment block, and only when that block precedes the first declaration.
# Every other mention is a citation. Measured, on this repo:
#   · "first id anywhere in the file" made creator-disputes.test.tsx a claimant of F-0341 on
#     one parenthetical at line 120, and FirstRunChecklist.test.tsx on an it() title — both
#     false reds, both gone under this rule;
#   · "every comment before the first declaration" is also too wide: a page test whose first
#     describe() sits at line 200 swept up mid-file doc blocks (creator-discovery.test.tsx
#     became a claimant of F-0217 that way).
# It keeps the real ones: brand-chat-accept-recovery.test.tsx's line-2 "F-0440 regression
# guard", and Java class javadoc, which sits after `package`/imports but before `class`.
FIRST_DECL = re.compile(
    r"^\s*(?:describe|it|test)\s*\(|^\s*@Test\b|^\s*(?:public\s+|final\s+|abstract\s+)*class\s+\w",
    re.M)
COMMENT = re.compile(r"/\*.*?\*/|(?:^[ \t]*//[^\n]*\n?)+", re.S | re.M)


def header_fids(text: str) -> list:
    decl = FIRST_DECL.search(text)
    limit = decl.start() if decl else len(text)
    first = COMMENT.search(text)
    if not first or first.start() >= limit:
        return []
    return fids_in(first.group(0))


claims, citations = {}, {}
for rel, p, text in tests:
    head = header_fids(text)
    filed = set(fids_in(p.name)) | (set(head[:1]) if head else set())
    for fid in filed:
        claims.setdefault(fid, []).append((rel, p, text))
    for fid in set(fids_in(text)) - filed:
        citations.setdefault(fid, []).append(rel)

# --------------------------------------------------------------------------- CHECK A / C
missing_open, missing_closed, dirs_advisory, dirs_with_test = [], [], [], []
abbrev_ok, abbrev_bad, abbrev_closed, partial = [], [], [], []
skipped_nonpath = 0
resolved = {}            # F-id -> [subject file(s)]
# F-id -> is this row still actionable? CHECK A already exempts CLOSED rows (a fix that
# renamed its own subject is not a live defect). CHECK B did not, which is why the first
# live run reported 23 findings of which 15 sat on closed rows — noise that would have
# made this gate unpromotable and, worse, trained people to ignore it.
actionable_by_id = {}
ABBREV = re.compile(r"\.\.\.|\*")

for r in rows:
    fid, where = r.get("id", "?"), r.get("where", "")
    paths = where_paths(where)
    if not paths:
        skipped_nonpath += 1
        continue
    actionable = (r.get("status") or "").strip().lower() != "closed"
    actionable_by_id[fid] = actionable
    has_test = fid in claims
    files, dirs, gone = [], [], []

    for tok in paths:
        if ABBREV.search(tok):
            hits = basename_index().get(tok.split("/")[-1], [])
            if len(hits) == 1:
                abbrev_ok.append((fid, tok, hits[0]))
                files.append(hits[0])
            elif (ROOT / tok.split("*")[0].split("...")[0]).is_dir():
                # 'src/hooks/*.ts' is a scope expression, not a broken path — same status as
                # a directory `where`: fine until a test is filed under the id.
                dirs.append(tok)
            elif actionable or has_test:
                abbrev_bad.append((fid, tok, "%d file(s) share that basename" % len(hits)))
            else:
                abbrev_closed.append((fid, tok, len(hits)))
            continue
        full = ROOT / tok
        if full.is_file():
            files.append(tok)
        elif full.is_dir():
            dirs.append(tok)
        else:
            gone.append(tok)

    if files:
        resolved[fid] = files
        if gone:
            partial.append((fid, files, gone))
        continue
    if dirs:
        # A directory `where` is right for a scope-level finding (F-0651) — until a test is
        # filed under the id, at which point there is no file to check that test against.
        (dirs_with_test if has_test else dirs_advisory).append(
            (fid, ", ".join(dirs),
             [t[0] for t in claims[fid]] if has_test else r.get("status", "")))
        continue
    if gone:
        near = sorted(basename_index().get(gone[0].split("/")[-1], []))[:3]
        entry = (fid, ", ".join(gone), r.get("status", ""), near)
        (missing_open if actionable else missing_closed).append(entry)

# --------------------------------------------------------------------------- imports
IMPORT_PATS = [
    re.compile(r"""(?:^|\W)from\s+['"]([^'"]+)['"]"""),
    re.compile(r"""\bimport\s*\(\s*['"]([^'"]+)['"]"""),
    re.compile(r"""\brequire\s*\(\s*['"]([^'"]+)['"]"""),
    re.compile(r"""\bvi\.mock\s*\(\s*['"]([^'"]+)['"]"""),
]
JAVA_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)", re.M)


def specifiers(text: str) -> list:
    out = []
    for pat in IMPORT_PATS:
        out += [m.group(1) for m in pat.finditer(text)]
    return out


def resolve_spec(spec: str, from_file: Path):
    if spec.startswith("@/"):
        base = ROOT / "src" / spec[2:]
    elif spec.startswith("."):
        base = (from_file.parent / spec).resolve()
    else:
        return None
    cands = [base] + [base.with_suffix(s) for s in (".tsx", ".ts", ".jsx", ".js")] + \
            [base / "index.tsx", base / "index.ts"]
    for cand in cands:
        try:
            if cand.is_file():
                return cand.relative_to(ROOT).as_posix()
        except (OSError, ValueError):
            continue
    return None


_imports_cache = {}


def imports_of(rel: str) -> set:
    if rel not in _imports_cache:
        p = ROOT / rel
        try:
            text = strip_comments_js(p.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            _imports_cache[rel] = set()
            return _imports_cache[rel]
        _imports_cache[rel] = {t for t in (resolve_spec(s, p) for s in specifiers(text)) if t}
    return _imports_cache[rel]


_fanin = None


def fanin() -> dict:
    """How many in-repo modules import each module. A module imported by everything (@/lib/api,
    ui primitives) proves nothing about a test reaching a specific subject; a module imported
    by one or two files does."""
    global _fanin
    if _fanin is None:
        counts: dict = {}
        src = ROOT / "src"
        if src.is_dir():
            for p in src.rglob("*.ts*"):
                if SKIP_DIRS & set(p.parts):
                    continue
                for t in imports_of(p.relative_to(ROOT).as_posix()):
                    counts[t] = counts.get(t, 0) + 1
        _fanin = counts
    return _fanin


NARROW = 5

# --------------------------------------------------------------------------- CHECK B
def reaches(rel: str, text: str, subject: str) -> bool:
    if subject.endswith(".java"):
        return bool(re.search(r"\b%s\b" % re.escape(Path(subject).stem),
                              JAVA_COMMENTS.sub(" ", text)))
    direct = imports_of(rel)
    if subject in direct or any(subject in imports_of(d) for d in direct):
        return True
    # A test of an EXTRACTED helper reaches the subject that uses it — but only when the
    # shared module is specific. @/lib/api is imported by half the tree and proves nothing.
    return any(fanin().get(d, 0) <= NARROW for d in direct & imports_of(subject))


unreached, unreached_closed, cross_lang = [], [], []
for fid, subjects in sorted(resolved.items()):
    # Same exemption CHECK A applies. A CLOSED row's test not reaching the row's recorded
    # `where` is usually the fix having moved or renamed its own subject, not a test aimed
    # at the wrong thing — reported below, never failed.
    row_actionable = actionable_by_id.get(fid, True)
    for rel, p, text in claims.get(fid, []):
        test_java = rel.endswith(".java")
        same = [s for s in subjects if s.endswith(".java") == test_java]
        if not same:
            cross_lang.append((fid, rel, ", ".join(subjects)))
            continue
        if any(reaches(rel, text, s) for s in same):
            continue
        why = ("the class name(s) %s appear nowhere in the test's code (comments stripped)"
               % ", ".join(Path(s).stem for s in same) if test_java else
               "resolves %d in-repo import(s); none is a subject, imports one, or is a "
               "narrowly-shared module a subject also imports" % len(imports_of(rel)))
        (unreached if row_actionable else unreached_closed).append((fid, rel, ", ".join(same), why))

# --------------------------------------------------------------------------- CHECK D
_prod_tsx = None


def prod_tsx():
    global _prod_tsx
    if _prod_tsx is None:
        out = []
        src = ROOT / "src"
        if src.is_dir():
            for p in src.rglob("*.tsx"):
                if TEST_NAME.search(p.name) or "__tests__" in p.parts or SKIP_DIRS & set(p.parts):
                    continue
                try:
                    out.append((p.relative_to(ROOT).as_posix(),
                                strip_comments_js(p.read_text(encoding="utf-8",
                                                              errors="replace"))))
                except OSError:
                    continue
        _prod_tsx = out
    return _prod_tsx


def open_tags(text: str, comp: str):
    for m in re.finditer(r"<%s(?=[\s/>])" % re.escape(comp), text):
        i, depth, quote, buf = m.end(), 0, "", []
        while i < len(text):
            c = text[i]
            if quote:
                if c == quote and text[i - 1] != "\\":
                    quote = ""
            elif c in "\"'`":
                quote = c
            elif c in "{([":
                depth += 1
            elif c in "})]":
                depth -= 1
            elif c == ">" and depth <= 0:
                break
            buf.append(c)
            i += 1
        yield "".join(buf)


def tag_props(tag: str) -> set:
    props, depth, quote, ident, i = set(), 0, "", "", 0
    while i < len(tag):
        c = tag[i]
        if quote:
            if c == quote and tag[i - 1] != "\\":
                quote = ""
        elif c in "\"'`":
            quote = c
        elif c in "{([":
            depth += 1
        elif c in "})]":
            depth -= 1
        elif depth == 0:
            if c.isalnum() or c in "_$":
                ident += c
                i += 1
                continue
            if c == "=" and ident:
                props.add(ident)
            ident = ""
        i += 1
    return props


EXPORTED = re.compile(r"export\s+(?:default\s+)?(?:function|const|class)\s+([A-Z]\w+)")
test_only_props, no_prod_render = [], []
for fid, subjects in sorted(resolved.items()):
  for subject in subjects:
    if fid not in claims or not subject.endswith(".tsx"):
        continue
    try:
        subj_text = strip_comments_js((ROOT / subject).read_text(encoding="utf-8",
                                                                errors="replace"))
    except OSError:
        continue
    for rel, p, text in claims[fid]:
        if not rel.endswith(".tsx"):
            continue
        ttext = strip_comments_js(text)
        for comp in sorted(set(EXPORTED.findall(subj_text))):
            ttags = list(open_tags(ttext, comp))
            if not ttags:
                continue
            tprops = set().union(*(tag_props(t) for t in ttags))
            pprops, sites, spread = set(), [], False
            for prel, ptext in prod_tsx():
                # A production render counts only where THIS component can be in scope: the
                # subject file itself, or a file importing it. Measured: two different
                # components are both named YoureInStep (brand-onboarding, creator-onboarding)
                # and without this the other one's props read as "test-only".
                if prel != subject and subject not in imports_of(prel):
                    continue
                for t in open_tags(ptext, comp):
                    sites.append(prel)
                    spread = spread or "{..." in t.replace(" ", "")
                    pprops |= tag_props(t)
            if not sites:
                no_prod_render.append((fid, rel, comp))
            elif not spread and (tprops - pprops):
                test_only_props.append((fid, rel, comp, sorted(tprops - pprops),
                                        sorted(set(sites))[:2]))

# --------------------------------------------------------------------------- report
print("* F-0663 subject-resolvability  ledger=%s" % ledger_path)
print("  %d ledger row(s) · %d test file(s) · %d finding(s) filed under a test · "
      "%d row(s) skipped (prose or out-of-tree `where`)"
      % (len(rows), len(tests), len(claims), skipped_nonpath))
if malformed:
    print("  %d malformed ledger line(s) (reported, not failed)" % malformed)
if unreadable_tests:
    print("  unreadable test file(s) (reported, not failed): " + ", ".join(unreadable_tests))

if abbrev_ok:
    print("  ADVISORY  %d abbreviated `where` (…/ or *) that still resolve uniquely by "
          "basename:" % len(abbrev_ok))
    for fid, tok, hit in abbrev_ok:
        print("      %-8s %-56s -> %s" % (fid, tok, hit))
if abbrev_closed:
    print("  ADVISORY  %d CLOSED row(s) with an abbreviated `where` matching 0 or >1 files:"
          % len(abbrev_closed))
    for fid, tok, n in abbrev_closed:
        print("      %-8s %-56s %d candidate(s)" % (fid, tok, n))
if dirs_advisory:
    print("  ADVISORY  %d `where` naming a DIRECTORY with no test filed under it — right "
          "for a scope-level finding (F-0651), a trap the moment someone writes a test:"
          % len(dirs_advisory))
    for fid, tok, st in dirs_advisory:
        print("      %-8s %-8s %s" % (fid, st, tok))
if partial:
    print("  ADVISORY  %d multi-path `where` where some paths resolve and some do not — the "
          "row is still usable, so this is not failed:" % len(partial))
    for fid, files, gone in partial:
        print("      %-8s stale: %s" % (fid, ", ".join(gone)))
if missing_closed:
    print("  ADVISORY  %d CLOSED row(s) whose `where` no longer exists — expected when the "
          "fix renamed or deleted its own subject:" % len(missing_closed))
    for fid, tok, st, near in missing_closed:
        print("      %-8s %-58s nearest: %s"
              % (fid, tok, ", ".join(near) if near else "no file of that name"))
if cross_lang:
    print("  ADVISORY  %d test(s) filed under a finding in the OTHER language — an import "
          "graph cannot span FE and BE, so this gate cannot judge them:" % len(cross_lang))
    for fid, rel, subject in cross_lang:
        print("      %-8s %s  ->  %s" % (fid, rel, subject))
if citations:
    n = sum(len(v) for v in citations.values())
    print("  ADVISORY  %d cross-reference mention(s) of an F-id in a test filed under a "
          "different id — cited, not claimed, so not checked" % n)
if no_prod_render:
    print("  ADVISORY  subject component rendered by its test but by NO production .tsx "
          "(route element, lazy import, or dead — props cannot be compared):")
    for fid, rel, comp in no_prod_render:
        print("      %-8s <%s> in %s" % (fid, comp, rel))
if test_only_props:
    print("  ADVISORY  props passed ONLY by the test — the F-0640 signature: the test may "
          "be proving a path production never takes:")
    for fid, rel, comp, extra, sites in test_only_props:
        print("      %-8s <%s %s> in %s" % (fid, comp, " ".join(extra), rel))
        print("               production renders at %s" % ", ".join(sites))

findings = 0
if missing_open:
    findings += len(missing_open)
    print("  BROKEN    %d actionable row(s) whose `where` names a file that does not exist:"
          % len(missing_open))
    for fid, tok, st, near in missing_open:
        print("      %-8s %-8s %s" % (fid, st, tok))
        print("               nearest same-name file(s): %s"
              % (", ".join(near) if near else
                 "NONE — that basename exists nowhere in the repo"))
if abbrev_bad:
    findings += len(abbrev_bad)
    print("  BROKEN    %d actionable row(s) whose abbreviated `where` resolves to 0 or >1 "
          "files:" % len(abbrev_bad))
    for fid, tok, why in abbrev_bad:
        print("      %-8s %-56s %s" % (fid, tok, why))
if dirs_with_test:
    findings += len(dirs_with_test)
    print("  BROKEN    %d row(s) whose `where` is a DIRECTORY while a test is already filed "
          "under the id — the test has no subject to be checked against:" % len(dirs_with_test))
    for fid, tok, tfiles in dirs_with_test:
        print("      %-8s %s   filed test(s): %s" % (fid, tok, ", ".join(tfiles)))
if unreached:
    findings += len(unreached)
    print("  BROKEN    %d test(s) filed under an F-id that never reach the row's subject:"
          % len(unreached))
    for fid, rel, subject, why in unreached:
        print("      %-8s %s" % (fid, rel))
        print("               subject: %s" % subject)
        print("               %s" % why)
if unreached_closed:
    print("  advisory  %d test(s) under a CLOSED row that do not reach its recorded `where` — "
          "reported, not failed: on a closed row this usually means the fix moved or renamed its "
          "own subject, or the real defect turned out to live elsewhere and the `where` was never "
          "corrected. Worth reading, but not a live wrong-subject test:" % len(unreached_closed))
    for fid, rel, subject, why in unreached_closed:
        print("      %-8s %s   (recorded subject: %s)" % (fid, rel, subject))

if findings:
    print("VERDICT: broken — %d subject(s) cannot be resolved, or are not reached by the "
          "test filed against them. Each is a fix or a test aimed at something other than "
          "the defect (F-0663)." % findings)
    emit()
    sys.exit(1)

print("VERDICT: proved — every actionable ledger subject resolves to a real file, no "
      "claimed subject is a directory, and every test filed under an F-id reaches that "
      "row's file.")
emit()
sys.exit(0)
