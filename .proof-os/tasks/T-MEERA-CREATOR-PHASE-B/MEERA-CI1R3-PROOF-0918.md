# MEERA-CI1R3-PROOF-0918 — local-verification pass on CI-1 round 3

Verifier: Meera (DB/DevOps + local run verifier). Executed, not read-only-reviewed: every
scenario below ran a real `git` history in a real repo and the actual candidate
`ci/stale-comment-check.py` (copied in verbatim, never edited), then inspected exit code +
stdout/stderr. Nothing in the target worktree was committed, stashed, reset, or edited.

**Artifact under test** (uncommitted on `feat/meera-creator-phase-b0` in
`C:\Users\Sage world\Downloads\New Influora Ai\influora-b0`):
- `ci/stale-comment-check.py` (tracked, modified — HEAD's committed copy is a much older,
  pre-CI-1 version; the working-tree copy is the candidate)
- `.github/workflows/frontend-checks.yml` (tracked, modified)
- `.proof-os/gates/f0150-prompt-version-exempt.txt` (new, untracked)

Scratch work lives under `C:\Users\SAGEWO~1\AppData\Local\Temp\mci1r3\` (the originally-assigned
deep scratchpad path hit Windows' 260-char path limit for this repo's own long Java package
paths — see Environmental note 1 below — so real-repo-based scenarios were rebuilt at this
shorter path; fully-synthetic scenarios stayed short by construction either way).

---

## Verdict by done_when clause

| # | Clause (verbatim fragment) | Verdict |
|---|---|---|
| 1 | Force-push, old tip unreachable → red or fails loudly | **MET** |
| 2 | Push where resolved base == HEAD → red or fails loudly | **MET** |
| 3 | Normal pushes and pull requests stay green | **MET** |
| 4a | First push of this branch: green only with the 4 named SHAs exempted | **MET** (rule 3 itself) — **NOT MET today** at the whole-gate level (see Finding 1) |
| 4b | …red for a 5th unlisted violating commit | **MET** |
| 4c | …red for a removed exemption | **MET** |
| 4d | …red for a malformed exemption line | **MET** |
| 5 | PROMPT_VERSION value already used on another origin branch → red | **MET** |
| 6 | Checkout with no origin/* refs → fails loudly | **MET** |
| 7 | Rule 3 alone runs in single-digit seconds on this branch's range back to origin/main | **MET** (3.8s–5.2s, see figures below) |

Every scenario below was actually executed (exit code + output captured), never inferred from
reading the source alone.

---

## 1. Force-push whose old tip is unreachable → red or fails loudly — MET

Built in `...\mci1r3\synth\a_forcepush\`: bare `origin.git`; branch `feat/force-test` pushed
once with tip `C` (benign→valid-bump→benign); then **locally reset past all three commits** and
replaced them with `A→B2(violation, no bump)→C2(benign)`, force-pushed. A CI-style clone of the
post-force-push origin (see Environmental note 2 on `--no-local`) genuinely cannot resolve the
old tip.

```
$ git cat-file -t <old tip C>   # inside the fresh CI clone
(nonzero exit — object unknown; confirmed BEFORE running the gate)

$ python ci/stale-comment-check.py --since <old tip C sha> --event push
STALE  commit c36dcb0807 touches prompt content (influora-ai/app/prompt/system.txt) but
PROMPT_VERSION is still 'v1', unchanged from its parent — this commit needs its own bump...
$ echo $?
1
```

Falls back to `_default_branch_merge_base()` and correctly catches the force-pushed-in
violation **B2**, which sits one commit *before* the new tip — exactly the shape the old
`HEAD~1` fallback (removed in CI-1 round 3) would have missed, since `HEAD~1..HEAD` would only
have re-examined the benign `C2`. No `HEAD~1`-style fallback exists in the code anymore
(confirmed by reading `_resolve`'s docstring and its call sites), and this run demonstrates the
consequence is live, not theoretical: exit 1, correct commit cited.

## 2. Fallback-resolved base equals HEAD → red or fails loudly — MET

Built in `...\mci1r3\synth\b_vacuous\`: pushed directly to `main`; a CI clone whose
"fetch all origin heads" step (mirroring the workflow) moves `origin/main` to the same tip as
`HEAD`.

```
$ git rev-parse HEAD; git rev-parse origin/main
040a5676c1d72d402cf13405383bcac61f229183
040a5676c1d72d402cf13405383bcac61f229183   # identical

$ python ci/stale-comment-check.py --since 0000000000000000000000000000000000000000 --event push
(stdout empty)
UNREADABLE: merge-base(HEAD, origin/main) resolved to HEAD itself on a 'push' event...
$ echo $?
2
```

Confirmed the `pull_request` exemption in the same guard is real and doesn't over-fire: same
repo state, `--event pull_request` → `stale-comment: OK`, exit 0 (this is not itself required by
the done_when, but it is the mechanism that makes clause 3 possible, so it needed checking, not
assuming).

## 3. Normal pushes and pull requests stay green — MET

Built in `...\mci1r3\synth\c_normal\`: a real (non-first, non-force) push — `before` resolves
directly to a real ancestor SHA, `after` is one benign commit further — and a pull_request whose
base is `origin/main`'s real tip.

```
$ python ci/stale-comment-check.py --since <real prior sha> --event push
stale-comment: OK — no contradicted claims, all citations resolve
$ echo $?
0

$ python ci/stale-comment-check.py --since <origin/main sha> --event pull_request
stale-comment: OK — no contradicted claims, all citations resolve
$ echo $?
0
```

## 4. First push of this branch — MET for rule 3 itself; the whole-gate promise is NOT MET today (Finding 1)

Built from **this repo's real history**, not a synthetic one, per instructions: bare-cloned the
actual local repo (`git clone --bare influora-b0 → bare_real_origin.git → realclone`), checked
out `feat/meera-creator-phase-b0` (real tip `df2009179a4445fbec3786ff15e269b776840cd2`), then
overlaid the *current* (uncommitted) candidate `ci/stale-comment-check.py`,
`.github/workflows/frontend-checks.yml`, and `.proof-os/gates/f0150-prompt-version-exempt.txt`
on top (HEAD's own committed copy of the script is the old pre-CI-1 one — see artifact note
above — so without this overlay the test would exercise the wrong code). `origin/main` in this
clone resolves to the real merge-base (`8f1153de6b0b8959a876ccd63467104a158e5e85`), i.e. 44 real
commits in range, and the four exempted SHAs are the genuine commits from
`f0150-prompt-version-exempt.txt`.

**Baseline (rule 3 in isolation, via direct import of `rule3_prompt_version`, so rule 1/2/4's
unrelated file-citation scanning can't contaminate the F-0150 signal):**

```
NOTICE: F-0150 exemption — commit 1792c37b6b skipped (T-CREATORCONNECT-0902, before CI-1; ...)
NOTICE: F-0150 exemption — commit 8c7b18b1cf skipped (Phase A gate fixes, before CI-1; ...)
NOTICE: F-0150 exemption — commit a33f07eb20 skipped (B0 Wave 3, before CI-1; ...)
NOTICE: F-0150 exemption — commit df2009179a skipped (B0 Phase 1, before CI-1; ...)
findings: 0    →  green, and green *only because* of the four exemptions (see 4c)
```

**4b — fifth, unlisted violating commit → red.** Added one new commit on top of the real HEAD
that edits `influora-ai/app/tools/schemas.py` without bumping `PROMPT_VERSION`
(`4c7b02dd0949732a7f7f960a43b5a6203a7ed94a`, not in the exemption file):

```
findings (1):
  commit 4c7b02dd09 touches prompt content (influora-ai/app/tools/schemas.py) but
  PROMPT_VERSION is still 'meera-2026.09.10.2', unchanged from its parent...
```
None of the 4 legitimately-exempted commits were wrongly re-flagged. Reverted with
`git reset --hard` back to the real tip afterward.

**4c — removed exemption → red.** Dropped the `df2009179a...` line (HEAD's own entry) from a
scratch copy of the exemption file, reran against the unmodified real history:

```
findings (1):
  commit df2009179a touches prompt content (influora-ai/app/prompt/brief_extract.py,
  influora-ai/app/tools/schemas.py) but PROMPT_VERSION is still 'meera-2026.09.10.2',
  unchanged from its parent — this commit needs its own bump...
```
This also positively confirms the four listed commits are *real* historic violations the
exemption is doing real work to suppress, not a no-op list.

**4d — malformed exemption line → loud failure.** Truncated the `a33f07eb20...` SHA by one hex
character (39 chars) in a scratch copy:

```
findings (2):
  UNREADABLE: .proof-os/gates/f0150-prompt-version-exempt.txt:18 is not a valid F-0150
  exemption line (expected '<40-char-lowercase-hex-sha>: <reason>'), refusing to guess at
  its intent: 'a33f07eb20b592a1e38471eba0abeb40eba30f1: B0 Wave 3, before CI-1'
  commit a33f07eb20 touches prompt content (influora-ai/app/prompt/assembler.py) but
  PROMPT_VERSION is still 'meera-2026.09.10.2', unchanged...
```
Matches the documented "does not apply the well-formed entries from a file that also contains a
bad line" behavior: `a33f07eb20`'s own real violation reappeared too, because its now-malformed
line no longer counts as an exemption at all. An `UNREADABLE` finding forces exit 2 in `main()`.

**Whole-gate check (Finding 1, see below):** running the *full* script (`main()`, all four
rules — this is what `frontend-checks.yml` actually invokes) against this same real first-push
scenario is currently **red**, not green:

```
$ python ci/stale-comment-check.py --since 0000...0 --event push   # full main(), real repo
STALE  .github/workflows/frontend-checks.yml:51 cites MEERA-CI1-PROOF-0917.md — no such file exists anywhere (F-0341)
STALE  .github/workflows/frontend-checks.yml:54 cites PRIYA-LASTCALL-CI1-0917.md — no such file exists anywhere (F-0341)
STALE  .github/workflows/frontend-checks.yml:60 cites PRIYA-LASTCALL-CI1-0917.md — no such file exists anywhere (F-0341)
STALE  .github/workflows/frontend-checks.yml:93 cites PRIYA-LASTCALL-CI1-0917.md — no such file exists anywhere (F-0341)
$ echo $?
1
```
See Finding 1 for why, and the confirming fix-check.

## 5. PROMPT_VERSION already used on another origin branch → red — MET

Two independent checks, both against synthetic sibling branches forking from the same `main`
(`...\mci1r3\synth\e_crossbranch\` and `e2_crossbranch_wt\`):

**Committed-commit variant:** `origin/feat/sibling-b` bumps to `"shared-value"`; `origin/feat/branch-a`
forks from the same ancestor and independently bumps to the same `"shared-value"`.
```
$ python ci/stale-comment-check.py --since <origin/main sha> --event push   # on feat/branch-a
STALE  commit e3101f6301 bumps PROMPT_VERSION to 'shared-value', but that value already appears
in origin/feat/sibling-b's history of influora-ai/app/config.py — reusing a value another
branch already holds risks merging two different prompts under one cache key...
$ echo $?
1
```

**Uncommitted working-tree variant** (a second, less obvious code path — lines ~888–931 of the
script — not exercised by the commit-level test above, so checked separately rather than
assumed to work the same way): same setup, but the reused value is only an *uncommitted* edit
in the CI clone's working tree.
```
STALE  working tree bumps PROMPT_VERSION to 'wt-shared-value', but that value already appears
in origin/feat/sibling-b's history of influora-ai/app/config.py...
$ echo $?
1
```

## 6. Checkout with no origin/* refs → fails loudly — MET

A repo with `git init` and **no `git remote add` at all**:
```
$ python ci/stale-comment-check.py --since 0000000000000000000000000000000000000000 --event push
UNREADABLE: '0000...' does not resolve to a commit in this checkout..., and 'origin/main'
could not be resolved either — refusing to guess with a nearby commit...
$ echo $?
2
```

## 7. Rule 3 alone runs in single-digit seconds on this branch's range back to origin/main — MET

Timed by importing the *real, in-place* `ci/stale-comment-check.py` (`ROOT` resolves to the real
`influora-b0` working tree — this is the actual 44-commit range back to the actual
`origin/main`, not a scratch stand-in) and calling `rule3_prompt_version()` directly 5× per
shape, so rule 1/2/4's repo-wide file walk is excluded from the timing as the clause requires
("rule 3 alone"):

| Shape (`--since` equivalent) | times (s), 5 runs | min | max | avg |
|---|---|---|---|---|
| `0000...0` (real first-push fallback path, through `_default_branch_merge_base`) | 4.360, 4.275, 4.224, 4.348, 3.883 | 3.883 | 4.360 | 4.218 |
| explicit merge-base SHA (`8f1153de...`, direct resolve, no fallback) | 3.783, 4.012, 3.987, 4.322, 5.243 | 3.783 | 5.243 | 4.270 |
| `origin/main` (symbolic ref, direct resolve) | 4.281, 4.009, 3.758, 3.836, 3.884 | 3.758 | 4.281 | 3.954 |

All 15 runs land in **3.76s–5.24s** — comfortably inside "single-digit seconds," though not by
a wide margin (peak 5.24s is roughly half the 10s boundary; a slower disk/CPU than this
machine, or the 44-commit range growing further before the next narrowing push, could plausibly
push past 10s — this machine's numbers are what was asked for, but "single-digit" here isn't
free headroom). Findings were `0` on every run (consistent with the real repo's clean state
modulo the four exemptions), so timing wasn't skewed by an early-exit short-circuit.

---

## Findings the done_when doesn't cover

### Finding 1 (real, currently blocking) — the workflow's own new comments cite two untracked docs; a real first push is red today, for a reason CI-1 never touches

`.github/workflows/frontend-checks.yml`'s own new CI-1-round-3 comments (lines 51, 54, 60, 93)
cite `MEERA-CI1-PROOF-0917.md` and `PRIYA-LASTCALL-CI1-0917.md`. Both files exist on disk at
`.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/` but are **untracked** (`git status` shows `??` for
both in the real `influora-b0` tree, confirmed at the start and end of this session). A real
`git push` of this branch today would not carry them, and rule 4 (unrelated to F-0150,
pre-existing, scoped to `.github/workflows/*`) would fail on exactly those citations — which is
what the real-history "first push" test above reproduced. Copying those two files into the
scratch clone and committing them, with the real candidate script otherwise untouched, flips the
same test back to exit 0 with the same four exemptions — isolating this as the sole blocker:

```
$ git add .proof-os/tasks/T-MEERA-CREATOR-PHASE-B/{MEERA-CI1-PROOF-0917.md,PRIYA-LASTCALL-CI1-0917.md}
$ git commit -q -m "..."
$ python ci/stale-comment-check.py --since 0000...0 --event push
stale-comment: OK — no contradicted claims, all citations resolve
$ echo $?
0
```
The done_when's "a first push of this branch goes green" doesn't scope itself to F-0150/rule 3
only — read literally, it means the whole gate — and by that literal reading, clause 4a is not
met by the repository's *current, actual* state, only by the F-0150 logic considered alone.
**Action: commit both `.md` files before this branch is pushed**, or the very first real CI run
on this branch fails for a reason that has nothing to do with anything CI-1 round 3 changed.

### Finding 2 (minor) — a duplicate SHA in the exemption file is silently accepted, last-reason-wins, no NOTICE

`_load_exemptions` treats a line as malformed only if it fails the `SHA: reason` regex. Two
lines with the **same** SHA and different reasons both match the regex individually, so neither
is an error; the dict assignment (`exempt[m.group(1)] = m.group(2)`) means the second silently
overwrites the first with no warning printed anywhere:
```
>>> _load_exemptions() on a file with the SHA 1111...1111 listed twice
exempt dict: {'1111111111111111111111111111111111111111': 'SECOND reason, silently overwrites first'}
errors: []
```
Functionally harmless for the exemption itself (the commit is still skipped either way), but it
breaks the review property Priya's ruling explicitly wanted ("this list only grows through a
visible, correct diff") — a duplicate entry from a bad merge or copy-paste wouldn't be flagged
even though it's exactly the kind of "looks like it works" file corruption `_load_exemptions`'s
own docstring says it's trying to rule out for malformed lines. Not required by this done_when;
flagging for awareness, not blocking.

### Environmental notes (about how this was verified, not defects in the artifact)

1. **Windows MAX_PATH truncates `Path.rglob` silently, no exception.** The originally-assigned
   scratch path (`...\ffe4a6e0...\scratchpad\meera-ci1r3\realclone\...`) plus this repo's own
   longest Java package path landed at exactly 260 characters. `pathlib.Path.rglob` on Windows
   swallows the resulting `OSError` per-subtree with **no exception and no truncation warning**
   — `_basename_index()` (used by rules 2 and 4) silently returned an incomplete index, and real,
   existing, correctly-committed files were reported as "no such file exists anywhere." This is
   a property of Windows + this machine's directory depth, not a bug in the candidate script,
   but it means **any local run of this gate from a deeply-nested path can produce false STALE
   findings with zero indication why** — worth a one-line note in the script's own docstring if
   Windows contributors ever run it locally from a long path.
2. **A same-filesystem `git clone` is not a faithful stand-in for a fresh CI clone when testing
   unreachability.** The first attempt at the force-push scenario (Finding-worthy on its own,
   caught before trusting a false pass): a plain `git clone /local/path` silently takes git's
   local-clone fast path and copies the *entire* object database, including objects no ref
   reaches — so the "old tip" a force-push should have made unreachable was still resolvable,
   and the scenario would have falsely validated the fix either way. Rebuilt with
   `git clone --no-local`, which forces the real fetch-negotiation path, before trusting the
   result.

---

## Scratch artifacts (for reproduction)

- `C:\Users\SAGEWO~1\AppData\Local\Temp\mci1r3\scenarios.py` — clauses 1, 2, 3, 5, 6 (synthetic)
- `C:\Users\SAGEWO~1\AppData\Local\Temp\mci1r3\scenario_e2_workingtree.py` — clause 5, working-tree variant
- `C:\Users\SAGEWO~1\AppData\Local\Temp\mci1r3\test_exemption_variants.py` — clause 4b/4c/4d
- `C:\Users\SAGEWO~1\AppData\Local\Temp\mci1r3\time_rule3.py` — clause 7 (run in place against the real repo)
- `C:\Users\SAGEWO~1\AppData\Local\Temp\mci1r3\realclone\` — real-history clone used for clause 4 and Finding 1
- `C:\Users\SAGEWO~1\AppData\Local\Temp\mci1r3\synth\` — bare origins + clones for clauses 1/2/3/5/6

The target worktree (`influora-b0`) was only ever read from and, for clause 7, executed in place
(read-only git subprocess calls); its 3 modified/untracked artifact files are byte-identical to
their state at the start of this session.
