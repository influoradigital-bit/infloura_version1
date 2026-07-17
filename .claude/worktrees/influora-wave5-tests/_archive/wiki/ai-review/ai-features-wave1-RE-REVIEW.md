# AI Re-Review (settled tree) — Meera AI-features Wave 1

> **Reviewer:** Ash · **Date:** 2026-07-11 · Follows `ai-features-wave1-ai-review.md`
> **Method:** independent — read the settled tree AND ran the full pytest suite myself. Did not
> trust the implementing agents' "all passed" summaries. That is the point of a gate.
> **All four background agents (Priya, Vikram/Arjun, Kabir, Meera) have completed.**

## Settled state — the P0 injection fixes are real and correct
- `app/prompt/untrusted.py` present; `neutralize_angle_brackets` = entity-escape, immune to case/split.
- `assembler.py` `_wrap_untrusted` delegates to it; Block B neutralizes every `classify_site` field.
- `app/tools/loop.py` tier gate present (`allow_commit_tools`, fail-closed before HTTP forward).
- **F1 and F2 (the ship-blocker P0s) are CLOSED.** Kabir K1.1–K1.5 pass on the fixed tree; they
  demonstrably failed on `main` first. Verified by my own run.

## NEW finding — R1 [P1, blocks green CI]: V1.3 regressed 2 existing tests
Running the **full** suite (not just the 5 new K1 tests) — `pytest tests/eval/test_prompt_injection.py`:
```
2 failed, 36 passed
FAILED  test_injected_instruction_cannot_forge_a_funded_confirm_launch_without_spring_check
FAILED  test_amount_shaped_field_from_model_is_forwarded_as_hint_only_never_authoritative
  IndexError: fake_spring.calls[0]  -> list is EMPTY
  WARNING loop.py:185 rejected commit-tier tool request_payment — allow_commit_tools=False
```
**Root cause:** V1.3's gate defaults `allow_commit_tools=False` and there is **no enable-path** — not
in production (`routes/chat.py:115` builds `ToolLoopContext` without it; `VerifiedToken` has no
commit-capability claim) and not in these two tests. Both tests exercise the *legitimate authorized*
commit-forward contract (`request_payment`/`confirm_launch` forward to Spring; `display_amount_hint`
survives as advisory-only; no authoritative amount is synthesized). The gate now blocks the forward,
so `fake_spring.calls` is empty. Kabir's summary reported only the 5 K1 tests green and missed this.

**Impact:** the money-proposal flow is blocked in Python (my earlier F3 note, now proven by failing
tests), and **Meera's `ai-tests.yml` CI job is RED** on these 2 failures. Wave 1 is not clean-green.

**Fix (Vikram — Ash writes no code):**
1. Define a commit-capability signal on `VerifiedToken` (e.g. a `scope`/claim like `chat:commit`), and
   in `routes/chat.py` set `allow_commit_tools=<claim present>` — capability from the verified token
   only, never request body (K1.5 already guards the body path).
2. Update the 2 regressed tests to construct `ToolLoopContext(..., allow_commit_tools=True)` — they
   test the authorized path, so they must grant the capability. Their assertions stay unchanged.
3. Needs Priya to name the claim/scope (contract decision). Small, but it's a contract, not a guess.

## Open items carried from first review (unchanged)
- **T4 fix proposal corrected** — `wiki/ai-review/T4-schema-fix-correction.md`. `STANDARD` stays
  Java-only (Priya); land the schema-check report-only this wave or it blocks the injection PRs.
- **V1.1 not actually done** — `BrandSafetyScoreService` exists but has no job/callers; scores stay
  NULL. Safe (Priya rule 5), but S3 wire-up is incomplete. Backlog / Wave 1.5.
- **F5 cache lever** still unmeasured; **F6** `length` finish_reason still unhandled.

## Verdict: SHIP WITH FIXES — all Wave-1 code blockers cleared; one sign-off remains

| | |
|---|---|
| **P0 injection (F1, F2)** | ✅ FIXED, independently verified. Ship-blockers cleared. |
| **R1 — V1.3 enable-path** | ✅ FIXED. `chat:commit` scope → `allow_commit_tools` (token-only). I re-ran the full injection suite myself: **38 passed, 0 failed**. 2 regressions gone. |
| **`chat:commit` grant mechanism** | ⚠️ SIGN-OFF (not code): Priya + Kabir confirm `chat:commit` scope vs a boolean claim before Spring is wired to mint it. Fail-closed default is safe meanwhile. |
| **T4 schema-check** | ⚠️ Land report-only; my `STANDARD` correction overrides Meera's proposal. Wave 2. |
| **V1.1 / F5 / F6** | Backlog, non-blocking. |
| **2 pre-existing broken test files** | `tests/routes/test_brand_safety.py`, `tests/security/test_service_token_jwks_e_task.py` — untracked at session start, unrelated (missing imports `ClaudeToolResult`, `_assert_dev_jwks_source_is_dev_only`). Backlog; not from this batch. |

**Final: the Wave-1 AI ship-blockers (injection P0s + tier parity) are CLEARED and green.** Remaining
items are a security-contract sign-off (`chat:commit`) and Wave-2 backlog — no open code blocker. Priya signs.
