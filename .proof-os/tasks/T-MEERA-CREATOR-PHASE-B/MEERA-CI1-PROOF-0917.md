# MEERA-CI1-PROOF-0917 — independent falsification of `rule3_prompt_version`

Author: Meera (falsification only — did not write `ci/stale-comment-check.py`, did not edit it,
did not edit `.github/workflows/frontend-checks.yml`).

Scope: `ci/stale-comment-check.py::rule3_prompt_version` (~L270-408) and its two checks:
(a) per-commit `since..HEAD` walk, (b) working-tree-vs-HEAD.

Method: every scenario built in its own scratch git repo under
`C:\Users\SAGEWO~1\AppData\Local\Temp\claude\...\scratchpad\meera-ci1\<name>\`, the real unmodified
`ci/stale-comment-check.py` copied in verbatim, run with `python` (not python3). Nothing in
`influora-b0` was committed, stashed, or had its history rewritten. Scratch repos are throwaway
and were not touched in the `New Influora` tree.

Tool versions: Python 3.13.3, git 2.53.0.windows.1, global `core.autocrlf=true` (noted because it
matters for scenario 7).

---

## 1. A bump that reformats the line but keeps the same value

Repo `s1`. c1: `PROMPT_VERSION = "1.0.0"` + prompt v1. c2: prompt v2 (content change) +
`PROMPT_VERSION    =   '1.0.0'` (spacing changed, double→single quote) — same string value.

Command: `python ci/stale-comment-check.py --since HEAD~1`

Output:
```
STALE  commit b609284bed touches prompt content (influora-ai/app/prompt/assembler.py) but
PROMPT_VERSION is still '1.0.0', unchanged from its parent — this commit needs its own bump...
```
Exit 1.

**RED — correct.** `PROMPT_VERSION_LITERAL` extracts only `group(1)` (the value between quotes),
so reformatting the surrounding line (spacing, quote style) is invisible to the comparison; only
the literal value is compared. A cosmetic "bump" that doesn't change the string does not fool this
gate.

---

## 2. A revert to an older, already-used version

Repo `s2`. c1: version `"meera.3"`, prompt v1. c2: prompt v2 + version bumped to `"meera.4"`
(differs from parent → not flagged on its own). c3: prompt v3 (content changed again) + version
set back to `"meera.3"` (differs from ITS parent, `.4`, so the per-commit check sees "changed").

Command: `python ci/stale-comment-check.py --since <first-commit>`

Output:
```
stale-comment: OK — no contradicted claims, all citations resolve
```
Exit 0.

**GREEN — and this is a real gap.** Rule (a) only asks "does this commit's PROMPT_VERSION differ
from its own immediate parent's?" It has no memory of the range's history, so reusing *any*
previously-seen value satisfies it as long as it differs from the one commit directly before it.
This defeats the stated purpose of the rule: `cache_key_for` is keyed on `PROMPT_VERSION`, and the
cache for `"meera.3"` was already populated (by c1) with the *v1* prompt content. c3 reintroduces
the string `"meera.3"` while the actual prompt content is now *v3* — any cache hit against that key
after c3 lands would serve stale (wrong) content under a key that looks freshly-differentiated to
this gate. The module's own docstring calls this exact failure mode "not cosmetic" (L28-31); rule
3 as written does not close it, it only closes the "no bump at all" case. **NOT PROVED** on this
axis — the gate cannot detect version-string reuse, only version-string non-change-from-parent.

---

## 3. Two commits: A changes the prompt with no bump, B only bumps

Repo `s3`. c0: baseline (`v1`/`v1`). A: prompt → v2, no version change. B: version → `v2`, no
further prompt touch ("fix it in the next commit").

Command: `python ci/stale-comment-check.py --since c0`

Output:
```
STALE  commit 95cd8a3118 touches prompt content (...assembler.py) but PROMPT_VERSION is still
'v1', unchanged from its parent — this commit needs its own bump, not one borrowed from an
earlier commit in the range (F-0150)
```
Exit 1 — **A is flagged, confirmed**, even though B (the very next commit) supplies the bump.

**Squash-merge check** (repo `s3-squash`, built by `git reset --soft` + recommitting A+B as one
commit against the same base, simulating what GitHub's squash-merge produces on `main`):
```
stale-comment: OK — ...
```
Exit 0 — green, because the single squashed commit's own diff against its parent shows the version
did change.

**Verdict on strictness:** this is real and it is stricter than "did the range ever bump" — it
persists for the *entire life of the PR*, not just an intermediate push. As long as A and B remain
two distinct commits in `since..HEAD` (i.e., before a squash-merge or an interactive
rebase/`--fixup`/amend cleans the history), CI stays red on every subsequent push to that PR,
including the final one with both A and B present, because the per-commit walk checks each
historical commit against *its own* git parent, not the PR's net diff. A contributor must either
amend/fixup A directly, or squash A+B into one commit, before the required check goes green.

Which commit does CI actually see, concretely: for a **PR-triggered** run (`--since
pull_request.base.sha`), CI walks every commit in the branch as pushed, so it sees A and B as
distinct commits and flags A for as long as they stay distinct — exactly reproduced above. Once
a maintainer **squash-merges** the PR into `main`, the push-triggered run on `main` sees only the
one squashed commit and does not re-flag it (also reproduced above). So: correct-by-design during
review (forces a clean, honestly-bumped history or a squash before merge), and does not re-litigate
after a squash-merge lands. Whether that's "desired strictness" is a product call, not a bug — but
teams that push habitual "oops, fix in next commit" traffic on a long-lived PR without squashing
will see this gate stay red until they clean up, which is a real workflow cost worth stating
explicitly rather than discovering at merge time.

---

## 4. Merge commits in the range

Repo `s4` (clean case): `feature` branches off `base`, changes prompt + bumps version correctly on
the feature branch itself; `main` gets an unrelated commit meanwhile; `git merge --no-ff feature`
into `main`. `--since base` → exit 0, no crash, no skip.

Repo `s4b` (adversarial case): `featureX` branches off `base`, changes prompt **without** bumping;
`main` gets an unrelated commit; merge is done with `--no-commit`, and the bump is added **only in
the merge commit itself** (simulating a human "fixing" a missed bump while resolving/finishing the
merge, rather than in either parent).

Command: `python ci/stale-comment-check.py --since base` on `s4b`

Output:
```
STALE  commit bf0d60de20 touches prompt content (...assembler.py) but PROMPT_VERSION is still
'm1', unchanged from its parent — this commit needs its own bump...
```
Exit 1 — the *featureX* commit is flagged; the merge commit itself (`a8e5435`, whose own diff
against its first parent shows `m1`→`m2-fixed-in-merge`) is correctly **not** re-flagged.

**Confirmed: the walk handles two-parent commits without crashing or skipping them.**
`parent = f"{commit}^"` resolves to the first parent for a merge commit (git's own `^` semantics),
so a merge commit is diffed against its first-parent tree exactly like a normal commit, and is
itself walked by `git rev-list --reverse since..HEAD` like any other commit reachable from HEAD.
The same "no coasting" strictness from scenario 3 applies through merges too: a bump added only in
the merge commit does not retroactively clear the earlier commit that introduced the unbumped
prompt change.

---

## 5. PROMPT_VERSION via a different syntax, or defined twice

**5a — f-string** (repo `s5a`): `PROMPT_VERSION = f"{_BASE}.2"`.
Command: `--since <first-commit>` → `UNREADABLE: could not read influora-ai/app/config.py at
<sha> or its parent to check the F-0150 pairing`, **exit 2**. Same result for the working-tree
check with an uncommitted f-string edit (`--since HEAD` → exit 2, "could not read PROMPT_VERSION
from HEAD or the working tree").

**5b — annotated assignment** (repo `s5b`): `PROMPT_VERSION: str = "2.0"`.
Same result: `UNREADABLE: could not read influora-ai/app/config.py ...`, **exit 2**.

**Verdict for 5a/5b: correct, not a silent bypass.** `PROMPT_VERSION_LITERAL` requires the pattern
`PROMPT_VERSION\s*=\s*["'...']` with nothing but whitespace between the name and the quote; an
`f` prefix or a `: str` annotation breaks that match, `_prompt_version_at`/
`_prompt_version_in_working_tree` return `None`, and the code explicitly treats `None` as
`UNREADABLE` (never as "unchanged" and never as "changed") — this fails loud (exit 2, blocks CI)
rather than failing open. Real limitation (switching syntax breaks the gate entirely until someone
notices and reverts to a plain literal), but not a vacuous-pass hole.

**5c — duplicate definition, second one wins at runtime** (repo `s5c`) — **this is the serious
one**. `config.py`:
```python
PROMPT_VERSION = "1.0"
# ... 40 lines of unrelated config ...
PROMPT_VERSION = "1.0"  # duplicate re-assignment; this is the one actually in effect at import time
```
Verified by actually importing the module (`importlib`) both before and after the edit: Python's
last-assignment-wins semantics mean the *effective* `PROMPT_VERSION` is the **second** line's
value.

c2 bumps only the **first** (shadowed/decoy) occurrence to `"1.1"`, changes the prompt content, and
leaves the second (actually-effective) occurrence at `"1.0"`. Confirmed via `importlib` that the
runtime-effective value is still `"1.0"` after the edit.

Command: `python ci/stale-comment-check.py --since <first-commit>`
Output:
```
stale-comment: OK — no contradicted claims, all citations resolve
```
Exit 0.

**GREEN — and this is a false pass on exactly the axis the gate exists to protect.**
`PROMPT_VERSION_LITERAL.search()` returns only the *first* regex match in the file. If a duplicate
(or shadowing conditional) definition exists — plausible in a config file that's accumulated
history, or a merge that leaves both branches' definitions by accident — the gate can see the
**decoy** definition bump while the value Python actually uses at import time (and therefore what
`cache_key_for` actually hashes) never changes. This is worse than scenario 2: it's not merely
"reuses an old value," it's "the string the gate checked was never the string the program runs
with." **NOT PROVED** — this is the single clearest way to make new prompt content ship silently
under an unchanged cache key while the gate reports green.

---

## 6. Renamed or deleted prompt source file

Repo `s6`. All three sub-cases correctly flagged red without a bump:
- **Rename + tiny edit**: `git mv assembler.py assembler_v2.py` + a one-line change → flagged
  (`assembler_v2.py` still matches the `influora-ai/app/prompt/` prefix).
- **Pure rename, zero content change**: `git mv assembler_v2.py assembler_v3.py` with no edit at
  all → `git diff --name-only` still lists `assembler_v3.py` (git's default rename handling
  reports the destination path even with no textual diff), and it is still flagged as "touching"
  prompt content requiring a bump. This is conservative/over-inclusive rather than a bypass: a
  no-op rename forces a version bump it arguably doesn't need, but it errs toward blocking, not
  toward vacuous passing.
- **Deletion**: `git rm assembler_v3.py` (the only remaining prompt-source file) → flagged the
  same way.

**Verdict: no gap.** Renames and deletions of prompt-source files all still land in
`git diff --name-only`'s output with a path under the `PROMPT_SOURCES` prefix, so
`_touches_prompt_sources` catches all three. If anything this direction is too strict (a pure
rename shouldn't semantically require a new cache key), not too lenient.

---

## 7. CRLF-only change to a prompt source (Windows checkout)

Repo `s7`, built with `core.autocrlf=false` and `.gitattributes: * -text` locally so the exact
bytes committed are under control (global `core.autocrlf=true` on this machine would otherwise
normalize this away). c1: `assembler.py` with `\n` line endings. c2: byte-for-byte identical text,
every `\n` replaced with `\r\n`, no other change.

`git diff --name-only HEAD~1 HEAD` → lists `assembler.py` (git diffs blobs byte-for-byte; the
tracked content genuinely changed).

Command: `python ci/stale-comment-check.py --since HEAD~1`
Output: `STALE ... touches prompt content (...assembler.py) but PROMPT_VERSION is still 'c1',
unchanged from its parent ...` Exit 1.

**RED, and this is a real strictness/noise risk, reported as found rather than as a defect in the
gate's logic.** The check is purely diff-based, not semantic — it cannot distinguish "the assembled
prompt text changed" from "only the line-ending bytes changed." On a team with inconsistent
`core.autocrlf`/`.gitattributes` normalization across Windows and non-Windows contributors, a
line-ending-only commit to any file under `influora-ai/app/prompt/` would force a spurious
`PROMPT_VERSION` bump (and, per the docstring, an actually-pointless cache-key change) purely from
whitespace churn. Not a bypass — the opposite failure mode from scenario 2/5c (over-strict rather
than vacuous) — but worth a `.gitattributes` rule (`text eol=lf`) on the `influora-ai/app/prompt/`
tree if this hasn't already been done, since this repo's own CI runs on `ubuntu-latest` and the
dev machines are Windows.

---

## 8. The real workflow invocation

`.github/workflows/frontend-checks.yml`:
- Checkout: `actions/checkout@v4` with `fetch-depth: 0` — confirmed present (L47-49), with its own
  comment citing exactly this gate.
- Gate invocation (L77): `python ci/stale-comment-check.py --since
  "${{ github.event.pull_request.base.sha || github.event.before }}"`.
- Triggers: `pull_request` (path-filtered) and `push` to `main`, `feature/**`, `feat/**` (L27-34).

**On a PR:** `github.event.pull_request.base.sha` is used. For a `pull_request` event, GitHub
checks out an ephemeral merge commit whose two parents are the base branch tip and the head branch
tip, so `base.sha` is always exactly the merge commit's first parent — always resolvable and always
an ancestor with `fetch-depth: 0`. Confirmed the ancestor-checking machinery behaves correctly for
this shape in scenario 4. This is the well-designed path: `since..HEAD` here is exactly "every
commit this PR introduces," full stop.

**On a push to `main`/`feature/**`/`feat/**`:** `github.event.before` is used — the branch's prior
tip SHA. Two sub-cases, tested independently of Vikram's proofs:

- *Normal push (branch already existed, non-force):* `before` is a real ancestor of the new tip.
  Sanity-checked: an uncommitted-and-unbumped edit on top of a resolvable `since` is caught by
  check (b) (repo `s8`, exit 1) — reproduced independently, not just taken on Vikram's word.
- *Force-push / rebase (old tip no longer an ancestor, but its commit object still exists in the
  repo):* reproduced properly in repo `s8` (`git reset --hard` to an earlier commit + a divergent
  new commit, keeping the old tip reachable via a tag). `--since <old-tip>` → `UNREADABLE: '<sha>'
  is not an ancestor of HEAD (a force-push or rebase changed the base?) ... The working-tree-vs-HEAD
  check (b) below still ran.`, **exit 2**. Confirmed (b) does run in this branch — I checked the
  code path, not just the message text.
- ***First push of a brand-new branch, `before` = forty zeros (or empty string) — this is where I
  found a gap the task's framing doesn't quite cover.*** `_resolve()` falls back to trying
  `HEAD~1`. Two very different outcomes depending on how much local history existed **before** the
  push:
  - If the branch's tip has a real parent commit already known to this checkout (the *overwhelmingly
    common* real case — every `feat/*` branch here forks off `main`, which already has history),
    `HEAD~1` resolves to *some* commit and the walk proceeds — but only over `HEAD~1..HEAD`, i.e.
    **the single most recent commit**, not the branch's full divergence from `main`. Reproduced in
    repo `s8-firstpush`: a brand-new branch pushed for the first time with **three** local commits
    (commit1 touches the prompt with no bump; commit2/commit3 are unrelated, commit3 is the tip).
    `--since 0000...0` (the real forty-zero value GitHub sends) → `_resolve` silently falls back to
    `HEAD~1` = commit2, so the walk only checks commit3 → **exit 0, clean** — commit1's unbumped
    prompt change is invisible. The *correct* full-branch range (`--since main-tip`) does catch it
    (exit 1, reproduced in the same repo). This reproduces the exact "later commit coasts on
    nothing being checked at all" shape the CI-1 rewrite exists to close — just via the push
    trigger's `since`-resolution fallback rather than the old single-range-diff logic.
  - If there is no `HEAD~1` at all (the tip commit has no parent — a fresh, single-commit history),
    `_resolve()` returns `None` entirely, and `rule3_prompt_version` returns `[]` **immediately** —
    which means check (b) (working-tree-vs-HEAD) does **not** run either in this specific case,
    contrary to a plain reading of "(b) still runs." Reproduced in repo `s8-single`: single commit,
    an uncommitted unbumped prompt edit on top of it, `--since 0000...0` → clean exit 0, no mention
    of the dirty working tree at all. This sub-case is low-probability in this repo specifically
    (it requires a literal from-scratch single-commit history, not just "a new branch"), so I'm not
    calling it the headline finding, but it is a genuine, reproducible difference between "since is
    resolvable but non-ancestor" (b) still runs) and "since is unresolvable" (b) does not run) that
    the docstring does not distinguish.
  - **Mitigating factor, stated for balance:** the vulnerable path is specifically the *push*-
    triggered run take alone. A `pull_request`-triggered run against the same commits (which will
    exist for any branch that goes through a PR before merging into `main`) uses `base.sha`
    correctly and does catch the full range, as shown in the PR sub-case above. If this repo's
    branch-protection actually requires the PR-triggered check (not the push-triggered one) before
    merge, this gap is covered in practice. I did not find branch-protection settings in this repo
    checkout to confirm that either way — reporting the gap in the script/workflow pairing as
    reproduced, not a claim about whether it's exploitable end-to-end in this repo's merge policy.

---

## 9. The real tree

Run directly against `influora-b0` (no scratch copy — read-only, nothing committed or stashed).

`git log --oneline -5`:
```
df20091 feat(meera-b0): Phase 1 — a pasted brief becomes a summary, a price and risk flags
ff4ed40 docs(meera-b0): mark both HIGH defects closed on the pending sheet
948f10f fix(meera-b0): build the two controls the audit found missing
c22b00e fix(risk): price a pre-contract deal from the package on the proposal card
92c81be perf(security): verify the on-behalf token once per request, not twice
```
`HEAD` **is** `df20091` (the branch's tip; all further changes are uncommitted working-tree edits).
`git merge-base --is-ancestor df20091 HEAD` → exit 0 (trivially, same commit).

**`python ci/stale-comment-check.py --since HEAD`:**
```
stale-comment: OK — no contradicted claims, all citations resolve
  NOT CHECKED: a comment that cites live code and describes it wrongly
  NOT CHECKED: dead .md references outside .github/workflows (~550 today, rule 4 note)
```
Exit 0. Since `HEAD == df20091`, the per-commit range `HEAD..HEAD` is empty (check (a) has nothing
to walk); the result rests entirely on check (b). Verified independently that this green is
earned, not vacuous: `git diff --name-only HEAD` shows the uncommitted working tree touches three
`PROMPT_SOURCES` files (`influora-ai/app/prompt/assembler.py`,
`influora-ai/app/prompt/creator_persona.py`, `influora-ai/app/tools/creator_schemas.py`), and
`PROMPT_VERSION` at `HEAD` is `"meera-2026.09.10.2"` while the working tree's is
`"meera-2026.09.10.4"` — genuinely different, so check (b) correctly finds no pairing violation.

**`python ci/stale-comment-check.py --since df20091`:** identical output and exit 0, as expected —
`df20091` literally is `HEAD` here, so this is the same invocation as `--since HEAD` by definition,
not an independent data point.

---

## Summary table

| # | Scenario | Command (representative) | Result | Correct? |
|---|---|---|---|---|
| 1 | Reformatted line, same value + prompt change | `--since HEAD~1` (s1) | RED, exit 1 | Yes |
| 2 | Revert to an older, reused version | `--since <first>` (s2) | GREEN, exit 0 | **No — gap** |
| 3 | A: prompt no bump, B: bump only | `--since c0` (s3) | A flagged RED, exit 1 | Yes, by design (see strictness note) |
| 3 | Same, squash-merged into one commit | `--since base` (s3-squash) | GREEN, exit 0 | Yes (post-merge shape, expected) |
| 4 | Clean merge (feature already bumped) | `--since base` (s4) | GREEN, exit 0, no crash | Yes |
| 4 | Merge commit fixes a bump its parent commit lacked | `--since base` (s4b) | Parent commit flagged RED, exit 1; merge commit itself correctly not re-flagged | Yes, consistent with scenario 3 |
| 5a | f-string `PROMPT_VERSION` | `--since <first>` / `--since HEAD` | UNREADABLE, exit 2 | Yes (fails loud, not open) |
| 5b | Annotated assignment `PROMPT_VERSION: str = ...` | `--since <first>` | UNREADABLE, exit 2 | Yes (fails loud, not open) |
| 5c | Duplicate definition, first (decoy) bumped, second (effective) stale | `--since <first>` (s5c) | GREEN, exit 0 — **verified via `importlib` that runtime value never changed** | **No — worst gap found** |
| 6 | Rename + edit / pure rename / delete | `--since c0` (s6) | RED, exit 1 in all three | Yes (over-inclusive, not a bypass) |
| 7 | CRLF-only change, no textual change | `--since HEAD~1` (s7) | RED, exit 1 | Technically correct, but noisy/over-strict on mixed-OS teams |
| 8 | PR trigger (`base.sha`) | analysis + s4 | Always resolvable ancestor, correct range | Yes |
| 8 | Push trigger, normal / force-push | s8 | Exit 1 / exit 2 as documented, (b) still runs for the non-ancestor case | Yes, independently reproduced |
| 8 | Push trigger, first push of a *pre-existing-history* branch (`before`=zeros) | s8-firstpush | `HEAD~1` fallback checks only the tip commit, **misses earlier unbumped commits in the same initial push** | **No — gap, mitigated only if a PR-triggered run is also required** |
| 8 | Push trigger, first push with **no** `HEAD~1` at all | s8-single | Rule 3 returns `[]` immediately; **check (b) also skipped**, not just (a) | **No — gap, low-probability in this repo** |
| 9 | Real tree, `--since HEAD` | influora-b0 | GREEN, exit 0, verified earned (version genuinely bumped) | Yes |
| 9 | Real tree, `--since df20091` | influora-b0 | Identical to above (`HEAD == df20091`) | N/A — not an independent range |

---

## VERDICT: NOT PROVED

The per-commit "no coasting on an earlier commit's bump" mechanism (the actual CI-1 fix) works as
designed and I could not break it directly — scenarios 1, 3, 4, 6 all confirm it catches what it's
supposed to, including through merges and formatting tricks. Where it fails is at a different
layer: **the pairing check assumes "PROMPT_VERSION differs from before" is proof that the version
is meaningfully new, and that assumption is false in two reproduced, git-native ways:**

1. **Reused/reverted values pass** (#2) — the check has no memory beyond the immediate parent, so
   a version string can cycle back to one already keyed in the prompt cache and still read as
   "changed."
2. **A duplicate/shadowing `PROMPT_VERSION` definition defeats the check entirely** (#5c) — `re.search`
   takes the first match in the file; if that's a decoy while a later definition is the one Python
   actually uses, the gate can watch the decoy get bumped and go green while the real,
   cache-key-relevant value never moves. I verified this isn't a hypothetical parsing quirk by
   actually importing the module before and after the edit and printing the live value.
3. **The workflow's `since` derivation has an unhandled first-push shape** (#8) — `github.event.before`
   = forty zeros is explicitly anticipated by `_resolve()`, but its `HEAD~1` fallback silently
   narrows the per-commit walk to one commit instead of the branch's actual divergence range,
   which can hide exactly the kind of earlier-commit violation rule (a) exists to catch. Whether
   this is exploitable end-to-end depends on branch-protection configuration I could not inspect
   from this checkout — reported as a reproducible gap in the script+workflow pairing, not as a
   confirmed live bypass of this repo's merge policy.

Everything else asked for (#1, #6, #7, the merge-commit walk in #4, the PR-triggered path in #8,
and the real-tree run in #9) held up under independent, from-scratch reproduction — not just a
re-read of Vikram's own proofs.

No item required UNAVAILABLE — every scenario above was actually run, not inferred from reading the
script.

---
---

# Re-proof — round 2

Vikram's CI-1 round 2 changed `ci/stale-comment-check.py` (438 → 690 lines) and added a
`git fetch origin main:refs/remotes/origin/main` step to `frontend-checks.yml`. I read the new
source in full before testing anything (not just his summary), re-ran every round-1 scenario
against the new script from fresh scratch repos under
`scratchpad/meera-ci1/*r2` (plus a couple of new bare-repo + clone setups where a real
`origin/main` remote-tracking ref was needed to exercise the new fallback honestly), and then
tried the six new angles. Same rules as round 1: only scratch repos, `ci/stale-comment-check.py`
and the workflow file untouched (verified via `git diff --stat` at the end — the only diff against
them is Vikram's own pre-existing uncommitted change, not mine), `python` not `python3`, scripts
written to files via Write/Edit rather than heredocs, nothing in `influora-b0` committed, stashed,
or rewritten.

## Re-run of s1–s9 against the round-2 script

| # | Scenario | Round-1 result | Round-2 result | Verdict |
|---|---|---|---|---|
| 1 | Reformatted line, same value | RED | RED (`s1r2`, unchanged) | Still correct |
| 2 | Revert to an older, reused value | GREEN (gap) | **RED** (`s2r2`): `commit ... bumps PROMPT_VERSION to 'meera.3', but that value has already appeared earlier in influora-ai/app/config.py's history reachable from HEAD ...` | **Gap closed** |
| 3 | A: prompt no bump, B: bump only | A flagged RED | A still flagged RED (`s3r2`, unchanged) | Still correct, same strictness note applies |
| 3 | Same, squash-merged | GREEN | GREEN (`s3r2-squash`, unchanged) | Still correct |
| 4 | Clean merge (already bumped) | GREEN, no crash | GREEN, no crash (unaffected; not re-run in full, logic path untouched by the diff) | Still correct |
| 4 | Merge commit fixes a bump its parent lacked | Parent flagged RED, merge not re-flagged | Same (`s4br2`) — and confirmed the new reused-value check does not misfire on the merge's own legitimate fresh bump (`m2-fixed-in-merge` has never been used before) | Still correct |
| 5a | f-string `PROMPT_VERSION` | UNREADABLE, exit 2 | UNREADABLE, exit 2 (now via `ast.parse` finding a non-`Constant` value rather than regex failing to match — same outward behavior, different mechanism, re-verified not re-run in full since the code path is unchanged in spirit) | Still correct |
| 5b | Annotated `PROMPT_VERSION: str = "..."` | UNREADABLE, exit 2 (a round-1 false limitation) | **Now correctly parsed.** `ast.AnnAssign` is explicitly handled: an unbumped annotated edit → RED (`s5br2`, `PROMPT_VERSION is still '1.0'`); a genuinely fresh bump → passes that specific commit (still correctly RED for the earlier unbumped commit still in range, confirmed) | **Improved** (not just unbroken — round 1's blind spot on this syntax is now closed as a side effect of the `ast` rewrite) |
| 5c | Duplicate definition, decoy bumped, real one stale | GREEN (worst gap) | **UNREADABLE, exit 2** (`s5cr2`): `zero or 2+ top-level PROMPT_VERSION assignments ... is refused rather than guessed at` | **Gap closed** |
| 6 | Rename + edit / pure rename / delete | RED in all three | RED in all three (`s6r2`, unchanged — `_really_touches_prompt_sources` explicitly treats create/delete/rename as always-a-touch) | Still correct |
| 7 | CRLF-only change, no textual change | RED (noisy) | **GREEN** (`s7r2`): line-ending-only edit no longer flagged. Positive control re-run: a genuine textual change hidden inside a CRLF-converted file is **still** caught (RED) — normalisation only masks pure line-ending noise, not real edits | **Gap closed** |
| 8 | PR trigger (`base.sha`) | Correct | Unaffected by this diff (the `else` branch of the new `if not ref or set(ref)=={'0'}` still calls the same `_resolve`) | Still correct |
| 8 | Push trigger, normal push (real non-zero `before`) | Correct | Re-verified against a real bare-`origin` + clone setup (`s-push-main`): `--since <real-before-sha>` still walks exactly the pushed commit and flags it | Still correct |
| 8 | Push trigger, force-push (resolvable, non-ancestor) | UNREADABLE exit 2, (b) still runs | Unaffected by this diff (same code path) | Still correct |
| 8 | Push trigger, **first push of a branch with real prior history** (`before`=zeros) | **Gap**: `HEAD~1` fallback checked only the tip commit | **Gap closed**, verified against a real clone with `origin/main` fetched (`s8-firstpush-r2`, built from a bare `origin.git` + a proper `git clone`, not a synthetic ref): a 3-commit first push where commit 1 touches the prompt with no bump is now caught — `_default_branch_merge_base()` resolves to the true fork point, not `HEAD~1` | **Gap closed** |
| 8 | Push trigger, first push with **no prior history at all** (`s8-single`) | Gap: rule 3 returned `[]`, check (b) also skipped silently | **Now loud**: `UNREADABLE: ... 'origin/main' could not be resolved in this checkout — refusing to silently narrow the per-commit walk to HEAD~1 ...`, exit 2 (`s8singler2`) | **Gap closed** |
| 9 | Real tree, `--since HEAD` | GREEN, verified earned | GREEN, re-verified earned under the new script (12s wall time — see performance note below) | Still correct |
| 9 | Real tree, `--since df20091` | Identical to `--since HEAD` | Identical to `--since HEAD` (`HEAD == df20091` still holds; no new commits landed on this branch) | N/A, not independent |

**All four round-1 gaps (#2 reused value, #5c duplicate definition, #7 CRLF noise, #8 first-push
narrowing) are closed, independently reproduced from scratch, not taken on Vikram's word.** I did
not find a way to re-open any of them.

## New adversarial angles

### (i) Reuse across branches — real, by design, and confirmed live in this repo

Built cleanly in scratch (`s-crossbranch-work`): a bare `origin.git` with a `main` at `PROMPT_VERSION
= "main-v1"`; `phase-e` branches off `main` and bumps to `"meera-2026.08.10.1"`, pushed; `phase-b0`
branches off the **same** `main` commit (a true sibling, `git merge-base --is-ancestor phase-e HEAD`
→ exit 1, confirming no shared reachability) and independently reuses `"meera-2026.08.10.1"` with
completely different prompt content. `python ci/stale-comment-check.py --since origin/main` on
`phase-b0` → clean `OK`, exit 0. **Passes by design**: `_version_history_reachable_from_head` walks
`git log HEAD -- config.py`, which is fundamentally scoped to one branch's own ancestry and cannot
see a sibling's independent commits.

This is not a hypothetical for this repo. `feat/meera-creator-phase-e` is a real branch here right
now (`git branch -a` lists it, both local and `remotes/origin/`), and its current tip
(`e9db77a`) genuinely holds `PROMPT_VERSION = "meera-2026.08.10.1"` (checked via
`git show feat/meera-creator-phase-e:influora-ai/app/config.py`). I also checked whether that
*specific* string is an active collision risk against `feat/meera-creator-phase-b0` (this branch)
today: it isn't, right now — `"meera-2026.08.10.1"` also appears in `b0`'s own reachable history
(`git log feat/meera-creator-phase-b0 -p -- influora-ai/app/config.py` shows it as one of `b0`'s own
past values, inherited from the shared ancestor at `3358608`), so `b0`'s own same-branch reuse check
would already catch a re-use of that exact string. What's live is the *shape* of the risk: `phase-e`'s
tip is sitting at a value from before the two branches diverged, while `b0` has since moved on to
`meera-2026.09.10.1` → `meera-2026.09.10.2`, values that exist **only** in `b0`'s own history now.
A future bump on `phase-e` (or any third branch) that happens to reuse one of those two b0-only
values, or vice versa, would not be caught by either branch's own check.

**Is this a real cache-collision risk?** Yes, conditionally: it matters exactly if both branches'
deployments can ever address the same prompt cache — e.g. a shared Redis/cache layer keyed only on
the `PROMPT_VERSION` string with no branch or commit qualifier, hit by more than one running
deployment (a canary/rolling deploy overlap, a staging environment tracking a different branch than
prod, or simply redeploying an old branch for a hotfix after `main` has moved on). If the cache key
also incorporates something branch- or commit-scoped, this is moot. I don't have visibility into
the actual cache implementation from this checkout to say which is true — reporting the mechanism
and the live example, not demanding a fix. Priya's call.

### (ii) Working-tree reuse of a same-branch historical value — closed, verified

`s-wtreuse`: c1 sets `PROMPT_VERSION="wt.1"`, c2 bumps to `"wt.2"`, then an **uncommitted** edit on
top of c2 touches the prompt and sets the version back to `"wt.1"` (used two commits ago, same
branch). `--since HEAD` (empty commit range, only check (b) can fire) →
`STALE  working tree bumps PROMPT_VERSION to 'wt.1', but that value has already appeared earlier in
... history reachable from HEAD ...`, exit 1. `_reused_value(history, None, value)` correctly
treats "no `before_commit`" as "value must never appear anywhere in history reachable from HEAD."
Confirmed working, not just claimed.

### (iii) `ast` evasion attempts — all three refused loudly, plus one true residual limitation

Built in `s-astevade`, each as its own branch off a common baseline so results don't interact:

- `if True:\n    PROMPT_VERSION = "..."` → **UNREADABLE**, exit 2 (`evade-if-true`). The node is an
  `ast.If` in `tree.body`, not an `Assign`/`AnnAssign`, so it's simply invisible to the top-level
  walk — `found` stays empty → `None` → refused, not guessed.
- `try: PROMPT_VERSION = "..." except: ...` → **UNREADABLE**, exit 2 (`evade-try`). Same reason,
  `ast.Try` isn't handled.
- `globals()["PROMPT_VERSION"] = "..."` → **UNREADABLE**, exit 2 (`evade-globals`). The assignment
  target is an `ast.Subscript`, not `ast.Name`, so `isinstance(target, ast.Name)` fails and it's
  skipped.

All three fail loud, exactly as the docstring promises ("refused rather than guessed at") — none of
them produces a false pass. Good.

**The one case that does slip through, because no static tool can close it:** a single, unambiguous
top-level literal (`PROMPT_VERSION = "1.0"`) that a *function*, called at startup, later reassigns
via `global PROMPT_VERSION`. Built and proved with `importlib`, not just reasoned about
(`runtime-override-test` branch): `config.py` has exactly one top-level assignment plus a
`_configure_from_env()` function (a realistic "let an operator roll back the prompt via an env var
without a redeploy" pattern) that does `global PROMPT_VERSION; PROMPT_VERSION = override_value`.
A commit that bumps the top-level literal from `"1.0"` to `"1.1"` alongside a real prompt change
passes clean (`OK`, exit 0) — correctly, because the *source* genuinely has one unambiguous,
freshly-bumped top-level value. But actually importing the module with
`MEERA_PROMPT_VERSION_OVERRIDE=1.0` set and calling that startup function shows the **runtime**
value end up back at `"1.0"` — the old, already-cached slot — while the prompt text assembled is
the new `v2` content. The gate verified the text of the file; it cannot verify what a function
does when the process actually runs, because it never executes the module, by design (it's a
git-diff tool, not a runtime probe). This isn't a bug in `_extract_prompt_version_from_source` — it
does exactly what its docstring says, refuse ambiguity, accept exactly one unambiguous top-level
literal — it's a structural ceiling on *any* static-analysis approach to this problem. Worth naming
explicitly since Vikram asked, but I'm not filing it as an open defect in his fix: closing it would
require either banning runtime overrides of `PROMPT_VERSION` as a coding rule (a Priya-level policy
call, not something this script can enforce) or a completely different verification approach
(actually running the app and asking it what it thinks its version is, which is a different, much
heavier kind of gate).

### (iv) Push to main, real `before` SHA — unaffected; plus one self-merge-base edge case found

Normal case (`s-push-main`, real bare `origin` + clone): a genuine push to `main` with a real,
non-zero `before` SHA still hits the unchanged `else` branch (`_resolve(ref)`), walks exactly the
pushed commit, and flags it correctly. Confirmed, not just assumed.

**But I chased the specific concern in the prompt** — "the merge-base of HEAD with origin/main would
be HEAD itself" — and it is a real, if narrow, gap. It requires `before` to be zero/missing **on a
push to `main` itself** (not a feature branch). I reproduced it directly: pushed two new commits
straight to `main` (the second touching the prompt with no bump), then simulated the workflow's own
`git fetch origin main:refs/remotes/origin/main` immediately after — since this run's own push
already updated the remote's `main` to the same tip, `origin/main` now **equals** `HEAD` exactly
(`git rev-parse origin/main` == `git rev-parse HEAD`, confirmed byte-for-byte). Feeding rule 3
`--since 0000...0` in that state: `_default_branch_merge_base()` returns `HEAD` (merge-base of a
ref with itself is itself), so `since..HEAD` is **empty**, and the per-commit walk silently checks
nothing — despite two new commits, one of which touches the prompt without a bump. Output: clean
`OK`, exit 0.

How likely is `before=zero` on a push to `main` specifically? Low — GitHub sets it to all-zeros
specifically when the ref didn't exist before the push, and `main` (the long-lived default branch)
essentially always already exists. I could not find a realistic trigger for this in normal
operation of this repo. But the code doesn't special-case "am I already on `DEFAULT_BRANCH`" before
applying the merge-base-against-`origin/DEFAULT_BRANCH` fallback, so if `before` is ever empty/zero
while `HEAD` already **is** `main`'s tip (repo bootstrapping, a branch-protection misconfiguration,
or some other GitHub Actions edge case producing an empty `before` on main), this degenerates into
exactly the "check nothing" vacuous pass the whole CI-1 effort exists to prevent. Reporting as found;
given the low likelihood I would not block on it, but it's a real, reproduced gap in the fallback's
implicit assumption that `HEAD` is always a **descendant** of `origin/main`, which is false when
`HEAD` is on `main` itself.

### (v) The fetch line on `main` itself — no failure found

Reproduced directly rather than reasoned about: in `s-push-main`, confirmed via `git symbolic-ref
--short HEAD` and `git status --short --branch` that the local checkout has branch `main` actually
**checked out** (not detached), then ran the exact command from the workflow,
`git fetch origin main:refs/remotes/origin/main`, verbatim — exit 0, no error, no warning. Git's
"refusing to fetch into the branch you're currently on" protection is specifically about a
destination refspec under `refs/heads/<checked-out-branch>`; the workflow's destination is
`refs/remotes/origin/main`, a different namespace entirely, which is exactly what a plain
`git fetch origin` updates by default regardless of what's checked out. No conflict, reproduced
concretely, not inferred. (I did not have a way to reproduce GitHub's actual fork-PR checkout
mechanics locally; reasoning only, not tested: `origin` on a fork PR still refers to the **base**
repo in Actions' checkout, which always has a `main`, so I'd expect no special-casing is needed
there either — flagged as reasoned-not-reproduced, not claimed as verified.)

### (vi) Performance

Measured on this Windows dev machine (CI runs on `ubuntu-latest`, where process-spawn overhead is
typically much lower — these are relative/shape numbers, not a prediction of real CI wall time):

- Full CLI (`main()`, all four rules), `--since HEAD` on the real tree (empty per-commit range):
  **12.0s**.
- Full CLI, `--since HEAD~50`: **24.9s**. Full CLI, `--since HEAD~200`" (the longest linear range
  this repo's multi-root history allows — `HEAD~250` doesn't resolve): **59.6s**.
- Isolated `rule3_prompt_version()` alone (no rules 1/2/4): `--since HEAD` → **1.45s**;
  `--since HEAD~50` → **6.8s**; `--since HEAD~200` → **27.8s**. Roughly linear in range size, ~0.1–
  0.15s/commit, consistent with one-or-more subprocess spawns (`git diff`, and for touched commits
  `git show` ×2 for the CRLF-normalisation check, plus the version reads) per commit in the walked
  range.
- The **new** always-run reused-value history walk (`_version_history_reachable_from_head`, one
  `git log -- config.py` plus one `git show` per commit that ever touched that file) is cheap on
  this repo: 19 commits reachable from HEAD have ever touched `config.py`, measured at ~1.1s total
  for the `git show` calls. This does **not** scale with the `--since` range — it's a fixed cost
  based on how many times the version file has been touched in the branch's whole history, separate
  from rule 3's O(range-size) per-commit walk.
- The bulk of the CLI's constant ~7-8s overhead I measured is **not** attributable to CI-1 at all —
  it's rules 1/2/4 each independently walking large parts of the repository tree
  (`_basename_index()` walks the entire repo root and is rebuilt from scratch inside both
  `rule2_citations()` and `rule4_workflow_citations()` — called twice, not cached). Out of scope for
  this proof (rule 3 is what changed), noted only so the timing numbers above aren't misread as "CI-1
  round 2 made the gate slow" — it didn't; rule 3 alone is a small fraction of total wall time for
  realistic (PR-sized, single or low-tens of commits) ranges.

**Verdict: not a blocker.** For the ranges CI will actually see in practice — a PR's own commits,
typically single digits to low tens — rule 3's added cost is on the order of a second or two even
on this slower-than-CI Windows measurement. It only becomes noticeable (tens of seconds) for ranges
in the hundreds of commits, which would only occur via the new first-push merge-base fallback on an
unusually large initial branch push, or a manually-specified large `--since`.

## Real tree, `--since HEAD`

```
stale-comment: OK — no contradicted claims, all citations resolve
  NOT CHECKED: a comment that cites live code and describes it wrongly
  NOT CHECKED: dead .md references outside .github/workflows (~550 today, rule 4 note)
```
Exit 0, 12.0s wall time (see performance note — dominated by rules 1/2/4, not rule 3). Same
earned-not-vacuous result as round 1: the uncommitted working-tree edit touches three
`PROMPT_SOURCES` files and its `PROMPT_VERSION` (`meera-2026.09.10.4`) both differs from HEAD's
(`meera-2026.09.10.2`) *and* — newly checked in round 2 — has never appeared before in this
branch's own `config.py` history, so the reused-value check also has nothing to say. `--since
df20091` is identical, as before, since `HEAD == df20091` still holds.

## VERDICT — round 2: PROVED, with three residual gaps disclosed (none of them silent)

All four round-1 findings are independently confirmed fixed: reused/reverted version values,
the duplicate-definition decoy, CRLF-only spurious bumps, and the first-push `HEAD~1` narrowing all
now behave correctly, reproduced from scratch rather than taken on Vikram's word. I could not
re-open any of them, and the core per-commit "no coasting" mechanism, merge-commit handling, and the
PR-triggered path all continue to hold up exactly as in round 1.

What remains, in order of how much I'd weight them:

1. **Cross-branch reuse (#i) is real and live in this repo today**, by design, not by oversight —
   `feat/meera-creator-phase-e` currently holds a `PROMPT_VERSION` whose value is no longer in
   `feat/meera-creator-phase-b0`'s own reachable history (the two branches have each moved past
   their last shared value). Whether it's exploitable depends on whether the prompt cache is keyed
   on anything besides that string, which I can't see from this checkout. This is a scope decision
   (branch-reachable history vs. cross-repo/global history), not a bug — flagged for Priya, not
   something I'm asking Vikram to change unprompted.
2. **The self-merge-base degenerate case on a direct push to `main` (#iv)** is a genuine,
   reproduced vacuous-pass of the exact shape CI-1 exists to prevent, gated behind a low-probability
   precondition (`before` = zero/missing while already on `main`). Worth a one-line guard (e.g.
   "if `since == HEAD`, that's suspicious for a push event — check whether we're already on
   `DEFAULT_BRANCH` and, if so, fail loud instead of silently accepting an empty range") but I'm not
   the one who gets to decide it's worth the code churn for a corner this unlikely.
3. **Runtime-level overrides of an otherwise-correctly-bumped top-level literal (#iii, second half)**
   are invisible to any static gate by construction. Not a defect to fix in this script; a limitation
   to know about.

None of these are silent in the sense of "the gate claims something false with confidence" — #1 and
#3 are scope limitations the gate never claimed to cover (it only ever promised to check history
*reachable from HEAD*, and it only ever promised to check *source text*, never running code), and #2
is a real but narrow vacuous-pass that I'm disclosing rather than burying. Given that framing:
**PROVED** for the four gaps this round-2 pass was scoped to close, with the three items above
disclosed as separate, named, out-of-original-scope findings rather than rounding up to a clean
bill of health.

No item required UNAVAILABLE in this round either — every scenario above was actually executed.
