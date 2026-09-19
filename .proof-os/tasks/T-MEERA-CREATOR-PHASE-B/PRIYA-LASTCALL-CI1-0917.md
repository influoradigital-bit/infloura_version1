# Priya last call: CI-1, rule 3 of the stale-comment gate (PROMPT_VERSION pairing)

**From:** Priya (CTO)
**To:** Arjun. Builder: vikram. Falsifier: meera. QA: kavya
**Date:** 2026-09-17, written 19:55
**Tree:** `influora-b0`, branch `feat/meera-creator-phase-b0`, uncommitted on `df20091`
**How this was checked:**
- Read only on this tree: `ci/stale-comment-check.py` (sha256 `aa5e184e…`, 19:02:27) and `.github/workflows/frontend-checks.yml` (`5c437869…`), both unchanged by me; `MEERA-CI1-PROOF-0917.md`, both rounds; `influora-ai/app/prompt/assembler.py` `cache_key_for` (L922-934) and its consumers.
- Every gate run was on scratch repos under `scratchpad/ci1/`, using copies of the working-tree gate.
- No Maven. Nothing committed, stashed or pushed.

## Verdict: **FAIL**

Meera's round-2 proof stands for the gaps she tested. All four round-1 findings are closed, and I did not repeat those runs.

CI-1 still fails, on **two new findings, both reproduced**. One is a vacuous pass of the same kind the gate exists to close. The other makes the gate reject the Wave U commit it is supposed to protect.

| # | Finding | Severity | Blocks |
|---|---|---|---|
| **F1** | A force-push to a `feat/**` branch narrows the per-commit walk to the tip commit, and the run passes silently | HIGH | Wave U commit |
| **F2** | The Wave U commit's own first push is **red**, on four older b0 commits that changed prompts without a bump | HIGH | Wave U commit |

The fix is one more small round for vikram (**CI-1 round 3**, below). It also absorbs Meera's residuals 1 and 2.

---

## F1: force-push, where the old tip is absent from the CI checkout

**The mechanism.**
- On a force-push, `github.event.before` is the **old** tip SHA. It is non-empty and non-zero, so rule 3 takes the `else` branch and calls `_resolve(ref)` (`stale-comment-check.py` L296-306).
- The old tip is no longer reachable from any ref, so a fresh CI clone does not have it.
- `_resolve` then falls back to **`HEAD~1`**. That is the same narrowing that finding (f) removed for first pushes, still live on this path.
- Meera's force-push scenario kept the old tip reachable through a tag, so it could not show this.

**Reproduced** (`scratchpad/ci1/forcepush.sh`, repo `s-forcepush`):
- A bare `origin`. `feat/x` is pushed with a clean bumped commit.
- It is then rewritten and force-pushed: commit c1' changes `persona.py` **without** a bump, and c2' is an unrelated tip.
- CI-style clone with `git clone --no-local`, which transfers only reachable objects, as a real fetch does.

```
old tip present in CI clone? no
--- gate --since <old tip> (what a force-push run passes):
stale-comment: OK — no contradicted claims, all citations resolve
exit=0
--- gate --since origin/main (the true divergence range), control:
STALE  commit d4553cf434 touches prompt content (influora-ai/app/prompt/persona.py) but PROMPT_VERSION is still 'v1', unchanged from its parent ...
exit=1
```

Rebasing and force-pushing `feat/*` branches is routine here, and the workflow runs on `feat/**` pushes (L27-34). This is not a corner case.

## F2: the gate is red on the Wave U commit itself

**The situation.** `feat/meera-creator-phase-b0` has never been pushed: `git ls-remote --heads origin` lists only `main`, `feat/meera-creator-phase-e` and `fix/f0390-…`.
- The Wave U push is therefore a **first push**. `before` is forty zeros, and rule 3 falls back to `merge-base(HEAD, origin/main)` = `8f1153de`.
- That range is **44 commits.** A PR run against `base.sha` = `8f1153de` walks the same 44.

**Reproduced** (`scratchpad/ci1/waveu_firstpush.sh`, repo `s-waveu`):
- A `--no-local` clone of this repository.
- `refs/remotes/origin/main` set to the real `origin/main`, `8f1153de`.
- One commit on top of `df20091` carrying the **working-tree** gate, `config.py` (`.4`), `app/prompt/**`, `schemas.py` and `creator_schemas.py`.
- Rule 3 called directly.

```
--- rule 3 only, --since forty zeros (first push):
findings: 4
 - commit 1792c37b6b touches prompt content (assembler.py, creator_persona.py, persona.py...) but PROMPT_VERSION is still 'meera-2026.08.10.1', unchanged from its parent
 - commit 8c7b18b1cf touches prompt content (assembler.py) but PROMPT_VERSION is still 'meera-2026.08.10.1', unchanged from its parent
 - commit a33f07eb20 touches prompt content (assembler.py) but PROMPT_VERSION is still 'meera-2026.09.10.2', unchanged from its parent
 - commit df2009179a touches prompt content (brief_extract.py, schemas.py) but PROMPT_VERSION is still 'meera-2026.09.10.2', unchanged from its parent
--- rule 3 only, --since the pre-Wave-U tip (a later normal push):
findings: 0
```

- **The four commits really are historic F-0150 misses.** The old whole-range rule passed them. They were committed 2026-09-03 to 09-15, before CI-1 existed. `config.py`'s own history comment already admits the Wave 2 rewrite under an unchanged version. None was deployed.
- **The Wave U delta itself is clean** (0 findings). That delta is the same thing the coordinator's real-tree `--since HEAD` checks, which is why that run says OK.
- **But `--since HEAD` is not what CI will run.** The first push, the PR, and (once F1 is fixed) any force-push all walk the whole branch and go red.
- **Why this blocks.** The only ways through are:
  - rewriting 44 reviewed commits, which is a force-push, which hits F1;
  - squashing the entire phase into one commit;
  - disabling the gate.

  A gate that forces a choice between rewriting history and turning it off will get turned off.

---

## Rulings on Meera's three residuals

### Residual 1, reuse of a version across branches: **(b), limited to `origin` branch heads. Required in round 3. Owner vikram.**
- **What `PROMPT_VERSION` does at runtime today.** Nothing reads `AssembledPrompt.cache_key`; grep finds only its assignment at `assembler.py` L987 and its tests. The live consumers are **attribution**: `prompt_meta` on the stream (`chat.py` L605) and the persisted assistant-message metadata (`chat.py` L900) that feeds Meera's interaction and outcome logs. So a version reused across branches doesn't serve a stale cache today. It **silently merges two different prompts into one row of every per-prompt metric**, which is what the B0 gate numbers are read by. That is enough to prevent. And the first person who wires `cache_key` into a real cache inherits the worse harm.
- **Why not (a).** The date-based scheme guarantees collisions: two parallel branches both reach for `.N+1` next. A naming convention is a request, and a request is what the original F-0150 miss was.
- **(b) as I scope it:**
  - The reuse check walks the version file's history across `HEAD` plus every `refs/remotes/origin/*` head. It stays path-limited, so the cost is commits that touched `config.py`, not the size of the branch.
  - The workflow fetches all branch heads explicitly (`git fetch origin '+refs/heads/*:refs/remotes/origin/*'`) instead of relying on checkout defaults.
  - If no `origin/*` head resolves, rule 3 fails loud rather than silently checking `HEAD` only.
  - Local-only branches remain invisible. That is acceptable, because a branch must be pushed to deploy.
- **Accepted risk.** A value on a stale remote experiment branch blocks nobody from shipping; you just pick another value.

### Residual 2, zero or missing `before` on `main` where merge-base = HEAD: **fix now, in round 3. Owner vikram.**
- It is the exact vacuous-pass shape, and F1's fix touches the same lines, so it costs nothing to close together.
- **The rule, one guard covering both:** if `since` came from **any** fallback (zero, missing, **or a non-empty `before` that does not resolve**) and the resolved base is `HEAD`, fail loud. Exception: the event is a `pull_request` with a real `base.sha`.
- `_resolve`'s `HEAD~1` fallback is **removed**. A non-empty `before` that doesn't resolve takes the same `merge-base(HEAD, origin/main)` path as a zero `before`, and fails loud if that doesn't resolve either. This closes F1.

### Residual 3, a runtime override of `PROMPT_VERSION`: **(b), as a pytest. Not a Wave U blocker. Owner vikram, due before any env-driven prompt rollback is built.**
- Today there is no override: `grep "global PROMPT_VERSION\|PROMPT_VERSION\s*="` over `influora-ai/app` finds only `config.py` L69.
- **The test.** An `ast` scan of every `influora-ai/app/**/*.py` fails on:
  - any `global PROMPT_VERSION`
  - any assignment, augmented assignment or `setattr`/`__dict__`/`globals()` write to `PROMPT_VERSION` outside `config.py`'s single top-level line
  - any read of an environment variable whose name contains `PROMPT_VERSION`
- **Show it red three ways:** Meera's `_configure_from_env` shape, a `setattr(config, "PROMPT_VERSION", …)`, and a second module assigning it.
- **Why a test at all.** A static gate cannot see runtime behaviour. The honest fix is to forbid the pattern where the code lives, rather than pretend the gate covers it.

---

## F2 ruling: an explicit, reviewed exemption list, not an epoch or a date

- **Build** `.proof-os/gates/f0150-prompt-version-exempt.txt`: one full 40-character SHA per line, plus a reason.
  - The per-commit walk and the reuse check **skip exactly those commits** and print a NOTICE naming each one.
  - A listed SHA not present in the range is ignored.
  - The list starts with exactly these four:
    - `1792c37b6b29d04bb3b30c40a94bdfd9b931116c`: T-CREATORCONNECT-0902, before CI-1
    - `8c7b18b1cf5a6afc651d0aca220291b8f9660972`: Phase A gate fixes, before CI-1
    - `a33f07eb20b592a1e38471eba0abeb40eba30f1e`: B0 Wave 3, before CI-1
    - `df2009179a4445fbec3786ff15e269b776840cd2`: B0 Phase 1, before CI-1
- **Why a list and not "skip commits before the CI-1 commit".**
  - An epoch keyed on dates or on "does this commit's own tree carry the new rule" gives a later commit a way to exempt itself.
  - A list can only grow through a visible diff, and adding a line to it needs **priya or kavya** in review.
  - A rebase changes the SHAs and turns the build red again, which forces a deliberate re-list. That is the right direction to fail.
- **Squash-merging Wave U is not required,** and it would not help the branch's own first push or its PR run.

---

## CI-1 round 3: what to build (vikram), what to prove (meera), bar for my re-check

1. **F1 + residual 2:** `_resolve`'s `HEAD~1` fallback removed. Every fallback goes through `merge-base(HEAD, origin/main)`, and fails loud if that is unresolvable or equals `HEAD` on a non-PR event. **Meera proves red:**
   - my `forcepush.sh` scenario, with the old tip absent through `--no-local`
   - her `s-push-main` merge-base = HEAD scenario

   **And green:** a normal non-force push with a real `before`, and a PR with `base.sha`.
2. **F2:** the exemption file with the four SHAs, read by rule 3. **Meera proves:**
   - my `waveu_firstpush.sh` sim goes **green** with the list in place
   - a **fifth** unbumped commit added to that sim, not on the list, turns it red again
   - removing one listed SHA turns it red again
   - a garbage line in the file fails loud, never silently skipping everything
3. **Residual 1:** the reuse check covers `origin/*` heads; the workflow fetches all heads. **Meera proves red:** her `s-crossbranch-work` sibling-reuse scenario, with the sibling pushed to origin. **And loud:** no `origin/*` refs at all.
4. **Residual 3** is ticketed, not part of round 3.
5. **Timing,** measured the way Meera did. Rule 3 alone on the 44-commit Wave U range must stay in single-digit seconds on the Windows box.
6. **The real-tree answer, re-run on the round-3 sim, is what closes CI-1.** Not `--since HEAD`.

---

## The real tree, and whether `.4` is fine to commit

- **`--since HEAD` on this tree is OK, and the OK is earned but narrow.** The working tree touches `assembler.py`, `creator_persona.py` and `creator_schemas.py`, and bumps `.2` to `.4`. My sim reproduces that delta as a single commit on `df20091` and gets **0 findings** when rule 3 runs over just that commit. It proves the Wave U delta is paired; it says nothing about the push CI will run (F2).
- **Skipping `.3` in committed history is fine.** A version string needs to be new, not consecutive. `.3` was only ever an uncommitted working-tree value, and its history comment in `config.py` explains why `.4` exists.
- **`.4` is fine to commit under my residual-1 ruling.**
  - `git log --all -G 'meera-2026\.09\.10\.[34]' -- influora-ai/app/config.py` returns **nothing**, so neither `.3` nor `.4` exists in the committed history of any branch in this repository: local `main`, `feat/meera-creator-phase-e`, `fix/f0390-…`, the three `claude/*` branches, or their `origin/*` copies.
  - Every ref tip other than b0 sits at `meera-2026.08.10.1`.
  - **The one thing I could not check by rule:** uncommitted edits in the `New Influora` working tree (phase-e). Before the Wave U commit, **arjun** confirms with that lane that nobody has `meera-2026.09.10.4` in a working tree. If they do, one of the two changes to a fresh value; under round 3 it would be caught at push anyway.

## Owners, in one place

| Item | Build | Prove | Last call |
|---|---|---|---|
| F1 + residual 2 (fallback hardening) | vikram | meera | priya |
| F2 (exemption list, four SHAs) | vikram | meera | priya (and kavya on any future list addition) |
| Residual 1 (`origin/*` reuse scope, fetch all heads) | vikram | meera | priya |
| Residual 3 (no runtime `PROMPT_VERSION` override, pytest) | vikram, ticket | kavya | priya |
| Confirm no other lane holds `.4` uncommitted | arjun | — | — |

**The Wave U commit waits for CI-1 round 3.** Nothing else in this verdict holds it.
