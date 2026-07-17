# AI Review: Spec-Compliance Audit (code vs. employee .md specs)

Reviewer: Ash · Date: 2026-07-11 · Method: read the code, matched each spec line to a call site.
Specs audited: `wiki/tech/employees/{00-architecture, vikram, meera, ananya, kabir, kavya, ash}.md`

**Bottom line up front:** the specs were written today; the code is essentially unchanged from
what I reviewed last week. Almost nothing is implemented yet. One item was partially built *before*
the spec (and is dead code). This is a "nothing has started" state, not a "it's wrong" state — but
do not let anyone report these as done.

---

## Compliance matrix

| Spec item | Required | Code reality | Status |
|---|---|---|---|
| **V1.2 / P0-1** shared neutralizer | Hoist `_neutralize_angle_brackets` to `app/prompt/untrusted.py`, use in `_wrap_untrusted` | `assembler.py:68` still `content.replace(f"</untrusted_{label}>", "")`. Neutralizer still lives only in `brand_safety.py`. | ❌ NOT DONE |
| **V1.2 / P0-2** validate Gemini output | `response_schema` on `classify_site`; neutralize + length-cap in `build_block_b` | `gemini.py:97` has `response_mime_type` only — no `response_schema`, no Pydantic validation. `build_block_b` still interpolates raw; only `_strip_forbidden_fields` (keys, not values). | ❌ NOT DONE |
| **V1.3 / S2** Python tier gate | `allow_commit_tools` on `ToolLoopContext`, fail closed; give `is_money_tool` a prod caller | `loop.py` has no `allow_commit_tools`, no tier check. `is_money_tool()` still called **only** by `test_prompt_injection.py:404`. | ❌ NOT DONE |
| **V1.1 / S3** brand-safety wire-up | `BrandSafetyScoreService` populates the 3 `creator_scores` columns via `ScoreCalculationJob` | Service class **exists** (`service/scoring/BrandSafetyScoreService.java`) — but `ScoreCalculationJob.java:37` says *"Deliberate scope cut — BrandSafetyScoreService is NOT wired in here."* **The job never calls it. Columns stay NULL.** | ⚠️ HALF — class built, not invoked |
| **V2 / S4** `CreatorFitService` | New join service + `CreatorFitProfile` DTO | Does not exist. | ❌ NOT DONE |
| **Meera T1** V48 migration | `creator_reliability_stats` | Migrations stop at V47. No V48. | ❌ NOT DONE |
| **V2.3** `show_creators` returns `fitProfile` | Enrich response, no new tool | `schemas.py` `show_creators` still `{niche, count, city}` only. | ❌ NOT DONE |
| **V2.4 / rule 6** `goal` drift | Resolve `awareness\|launch\|conversion\|review` vs `HYPE`; CI diff-check fails on drift | `schemas.py:82` goal enum unchanged. No `.github` diff-check job. Still one workflow (Lighthouse). | ❌ NOT DONE |
| **A1** campaign taxonomy in Block A | HYPE/DIRECT/REVIEW definitions in `persona.py` | No occurrence of HYPE/DIRECT/REVIEW in `persona.py`. | ❌ NOT DONE |
| **`STANDARD` question** | Priya/Swapnil answer before A1 | `schemas.py:102` still `enum: [HYPE, DIRECT, REVIEW]`; DB enum has 4. Unresolved. | ❌ OPEN |
| **A2** golden eval set | 10 brands, 2 refusals | Not present in `tests/eval/`. | ❌ NOT DONE |
| **Kabir K1** injection regressions | 5 new cases incl. both bypass payloads | `test_prompt_injection.py` unchanged; still passes while both bypasses work. | ❌ NOT DONE |
| **Meera T4 / CI** | shared-schema check, `mvn test`, `pytest`, flyway validate | Still one workflow: Lighthouse on `/brand/meera`. | ❌ NOT DONE |
| **DevOps hygiene** | `.gitattributes` `*.py text eol=lf` | None. Working tree already flipped to CRLF (see prior review). | ❌ NOT DONE |
| **PROMPT_VERSION** bump on prompt change | — | Still `meera-2026.07.05`. Consistent with "no prompt changed." | ✅ (nothing to bump) |

**Score: 0 of 14 fully done. 1 partial (built-but-dead). 1 open question.**

---

## The one that will mislead people — read this

`BrandSafetyScoreService.java` **exists and looks complete** — it maps the GARM response, picks the
worst-severity item, builds the `garm_flags` JSON. Someone glancing at the file list will tick S3
as done.

It is not done. `ScoreCalculationJob.java:37` explicitly does **not** wire it in:

> *"Deliberate scope cut — BrandSafetyScoreService is NOT wired in here… whoever builds it next
> should extend `scoreOne(CreatorProfile)` to also call it."*

So the class is built, unit-testable, and **never executed in production**. `creator_scores.
brand_safety_score` is still `NULL` for every creator. `AnalyticsService.java:152` confirms it reads
back null. The wire is one method call in `scoreOne()` — that call does not exist.

**This is exactly the failure pattern I keep flagging: a thing that looks finished and isn't.**
S3's remaining work is small (invoke the service in the job, chunk at ≤25, NULL on `ok=False`,
backfill). But "the service exists" must not be reported as "brand-safety scoring ships."

---

## Findings

**P0 — still open, unchanged from 2026-07-10 review.** P0-1 (`_wrap_untrusted` bypass), P0-2
(unescaped Gemini output in Block B system prompt), and the tier-parity gap. Nothing regressed;
nothing improved. The Wave 1 blockers are blockers still.

**P1 — S3 is a trap.** Built-but-unwired service will be miscounted as complete. Wire it or mark it
explicitly NOT DONE on the tracker.

**P2 — CRLF churn (from prior review) still uncommitted, still no `.gitattributes`.** If it lands
before the real Wave 1 diffs, every genuine fix is buried in line-ending noise.

---

## Verdict: **BLOCK** — code does not match the specs; Wave 1 has not started

To be clear about *why* this is fine and not alarming: I wrote these specs today. The team hasn't
picked them up. The correct read is "backlog is defined, execution pending" — not "someone shipped
and got it wrong."

But nobody gets to report Wave 1 as done. Concretely, in dependency order:

1. Meera: `.gitattributes` + re-normalize (unblocks clean diffs), then V48.
2. Vikram: P0-1, P0-2, tier gate (S1/S2), then wire `BrandSafetyScoreService` into `scoreOne()` (S3).
3. Kabir: the 5 regression cases — they must fail on `main` today, pass after Vikram.
4. Priya/Swapnil: answer the `STANDARD` question so I can write A1.

Re-review after each lands. Escalating the `STANDARD` open question and the S3 miscount to Priya.

Handoff: `FROM Ash → TO Arjun | spec-compliance audit | wiki/ai-review/spec-compliance-audit.md | BLOCK | NEXT: Wave 1 unstarted, route per 00-architecture order`
