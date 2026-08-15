# F-21 — deferred: 25 of 85 golden eval cases cannot be executed from this repo

**Status:** DEFERRED, not fixed. **Owner:** Vikram (backend). **Review date:** 2026-08-22.
**Raised by:** AI-DEEP-AUDIT-2026-08-08.html, finding F-21.

**Deferral ACCEPTED by Swapnil Maruti (CEO), 2026-08-08** (session local; the
two `.proof-os/journal.jsonl` entries are stamped 2026-08-09T07:50:57Z and
08:38:53Z UTC — same acceptance, recorded once and then again alongside the
amended criterion below. Priya flagged the date mismatch; this is the
reconciliation, not a second decision.)

Priya (CTO) declined to sign this herself and was right to: accepting 29-of-30
against an approved `done_when` is a change to an acceptance criterion, which
sits above the CTO. She reviewed the engineering three times in a fresh context,
ran the three oracles herself, and mutation-tested every finding; she has no
engineering objection to the reasoning below. This acceptance is the missing
authority, not a waiver of anything she found.

**Amended acceptance criterion (Swapnil Maruti, CEO, 2026-08-08).**

The original `done_when` read *"All 30 findings F-01…F-30 are fixed, each with a
regression test, and pytest / ruff / mypy are green; then Priya signs off."* On
the strength of the acceptance below it is amended to:

> **29 of 30 findings (F-01…F-20, F-22…F-30) are fixed, each pinned by a
> regression test verified to fail when its fix is reverted; F-21 is DEFERRED
> per this document, accepted 2026-08-08 (journalled 2026-08-09T07:50:57Z / 08:38:53Z UTC); and `python3 -m pytest -q`,
> `ruff check app/` and `mypy app/ --ignore-missing-imports` are green.**

Priya declined to sign a 30/30 statement against a 29/30 reality, and declined
to redefine the criterion herself so that her own signature became possible.
Both refusals were correct — that is what the gate is for. This amendment is the
authority she was missing, made explicitly and in writing rather than by quietly
loosening the wording.

What is being accepted, explicitly:

1. 29 of the 30 audit findings are fixed, and each is pinned by a regression
   test that fails when its fix is reverted.

   *Correction, 2026-08-08, after this acceptance was first recorded:* when
   Swapnil accepted, that sentence was **not yet true of F-06, F-07 and F-09**.
   Priya's final review mutation-tested them and found the ROUTE half pinned and
   the PROVIDER half unpinned — flipping `billed=True` at `sarvam.py`'s
   empty-transcript return restored "free unlimited STT" in full with the whole
   suite still green, and the same for F-07's ~40% under-bill and F-09's
   `_validate_kid` call site. Those three gaps are now closed
   (`tests/providers/test_f06_f07_provider_level.py`, plus two call-site tests in
   `tests/security/test_f09_f10_auth_hardening.py`) and each revert has been
   confirmed to go red. The claim above is accurate as of this correction; it was
   not when the acceptance was first written, and this note exists so nobody
   later mistakes the original wording for something that had been verified.

   *Second correction, 2026-08-09 (round 7, ledger F-0086):* the round-6 sweep
   was reported as "10/10 CAUGHT, missed: 0". A clean re-run of the FULL round-6
   batch — 12 reverts, not the 10 that had actually been run — found **four**
   fix-halves GREEN with all 640 tests still passing, and a subsequent full
   83-revert sweep across every finding found **nine** in total: F-01c (Gemini
   cache rates), F-03c (`record_spend`'s own `max()`), F-05f (release of an
   unsettled reservation), F-06g (multi-chunk TTS failure billing), F-11f
   (sockaddr coercion), F-13b (tuples/sets), F-13g (bytes), F-21r/F-21r2 (the
   NOT RUN branch and the "no cases were scored" failure), F-24b (CLI exit 1 on a
   scored-but-failing dataset) and F-28/F-28b (unit-suffixed magnitudes). Two
   causes: a mutation result was reported under a label naming a wider batch than
   was run, and a SIGTERM-killed harness run left `app/routes/chat.py` reverted so
   two unrelated tests failed on every subsequent row, painting four GREEN
   verdicts as RED. All thirteen halves are now pinned
   (`tests/costs/test_round7_mutation_gaps.py`,
   `tests/security/test_f11_f13_pin_gaps.py`,
   `tests/evals/test_f24_f28_pin_gaps.py` — 36 tests) and the full 83-revert
   sweep now returns 83 RED / 0 GREEN. The sentence in item 1 is accurate as of
   this correction; it was not when the first correction was written.

   *Third correction, 2026-08-09 (round 7 sign-off review, ledger F-0087):*
   Priya, reviewing in a fresh context, wrote her own 30 mutations rather than
   re-running the sweep, and found the one that mattered. The `$15/day` ceiling
   is a **global, cross-tenant** control, but every F-05 concurrency test fired
   its 200 callers from a single `workspace_id`. Narrowing `held_global`
   (`app/costs/spend_tracker.py:443`) to that caller's own workspace therefore
   left 663 of 663 tests green while reproducing the audit's headline number to
   the cent: 200 of 200 admitted, $14.00 of in-flight spend authorized against
   $0.10 of headroom. Thirteen pins and a 96-revert sweep had not varied the
   workspace id, so the clause that makes the control global had no behavioural
   signature anywhere in the suite. Closed by two tests in
   `tests/costs/test_f01_f07_money_path.py`; Priya's own revert now goes red.
   Her advisories 1, 3 and 5 (F-23's recursive key walk, F-13's decimal guard,
   F-13's unscrubbed dict KEYS) are closed and mutation-verified; advisory 4 (the
   `_RANGE_HINT_RE` guard) is resolved as documented, deliberate redundancy with
   the behaviour pinned instead of the line.
2. F-21's *reporting* is fixed — nothing is skipped, the harness exits non-zero,
   and the size of the blind spot is printed on every CI run.
3. F-21's *coverage* is open. 25 of 85 cases do not execute, and 100% of
   provenance, PII and IDOR coverage is unmeasured end-to-end.
4. `python3 -m evals.run_eval --offline all` **exits 1 from now until the
   fixtures land.** Any CI job wired to it is red, deliberately. Do not "fix"
   that job by removing the dataset or relaxing the check — that recreates the
   exit-0 this finding exists to remove.

## What was actually fixed

The *reporting*. Before: `outcome_recommendation` (15 cases) and
`campaign_performance` (10 cases) had committed golden cases and no recorded
fixtures, pytest **skipped** them, and the anti-silent-skip test `continue`d
past them — so 25/85 cases and 100% of provenance, PII and IDOR coverage
vanished behind a green CI run at exit 0.

Now:

- `run_eval.py --offline all` prints `RESULT: NOT RUN — 0 of N case(s) executed`
  for both datasets and exits non-zero. It can never read as PASS.
- Nothing is skipped in `tests/evals/test_eval_harness_offline.py`. Every
  dataset is asserted: either all its cases scored, or it is a declared
  unrecordable dataset **and** it reported zero scored cases with a
  non-passing verdict.
- `test_unmeasured_coverage_is_named_not_hidden` prints the exact size of the
  blind spot on every CI run:
  `EVAL COVERAGE: 60/85 golden cases measured offline; 25 NOT measured`.

## What was NOT fixed

The coverage itself. 25 cases still never execute. The audit's stated
consequence — "100% of provenance, PII and IDOR coverage never run" — is
unchanged. The F-23 IDOR fix and the F-27/F-28/F-29 provenance fixes are
exercised only by direct unit tests, never end-to-end through the harness.

## Why it cannot be closed here

Both fixture sets have to be produced somewhere this repo is not:

| Dataset | Fixture is | Needs |
|---|---|---|
| `outcome_recommendation` (15) | recorded Meera prose responses | `--live --record` in an environment holding `ANTHROPIC_API_KEY` |
| `campaign_performance` (10) | recorded `GetCampaignPerformanceResult` payloads | the Spring integration test that dumps the Java executor's output per case |

Authoring either by hand from the `expected` block would make the eval
**circular** — the fixture would be derived from the answer it is scored
against, and both datasets would go green while measuring nothing. That is a
worse failure than the one being fixed, and it is the same class as F-22
(an eval that reports PASS without running the code it claims to measure).

## Consequence of accepting this deferral

`python3 -m evals.run_eval --offline all` exits non-zero until the fixtures
land. If that command gates a CI job, that job is **red from now until F-21 is
closed**. That is the intended, honest state: the alternative is the exit-0
this finding exists to remove.

## Definition of done

1. `evals/fixtures/outcome_recommendation/` holds 15 fixtures recorded via
   `--live --record`.
2. `evals/fixtures/campaign_performance/` holds 10 fixtures dumped from the
   Spring integration test.
3. `python3 -m evals.run_eval --offline all` exits 0.
4. `test_unmeasured_coverage_is_named_not_hidden` prints `85/85`.
5. `FIXTURES_NOT_RECORDABLE_HERE` in `tests/evals/test_eval_harness_offline.py`
   is emptied — the allow-list must not outlive the gap it describes.
6. This file is deleted. A deferral document that outlives its deferral is just
   a stale excuse.
