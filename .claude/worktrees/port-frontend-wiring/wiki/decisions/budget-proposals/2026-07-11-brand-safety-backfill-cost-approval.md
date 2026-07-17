# Budget Proposal — Brand-Safety Score Backfill (P0-A gate)

> **Author:** Rohan (CFO) · **Date:** 2026-07-11
> **Why this exists:** Priya's Wave-1 gate (`wiki/architecture/priya-wave1-gate.md`, P0-A) blocks any
> production run of the brand-safety backfill until I give a cost ceiling + kill switch, and Swapnil
> approves a 20-creator dry-run in writing. This is that approval package.
> **Pricing verified live** against `platform.claude.com/docs/en/about-claude/pricing` (2026-07-11) —
> not from memory. All $ are USD; ₹ at ₹83/$ per our standing convention (`PLATFORM_COST_STRUCTURE.md`).

---

## 0. A finding that has to come before the cost table

**Influora has no tracked production-AI runtime budget at all, in either direction.** I checked every
file in `wiki/processes/`. `cost-log.json` and `subscriptions.md` both track Sage Digital's own
$133/mo dev-tooling spend (Claude Max flat plan, Cursor, Canva — running *our* agent team). Neither
covers what Influora's Anthropic API key actually bills once Meera chat, `classify_site` (Gemini),
and this brand-safety batch classifier are serving real brands. **This backfill would be the first
metered production AI spend with no budget line to charge it against.**

I'm treating this proposal as opening that line, not just approving one backfill. Recommend: once
this ships, I add a `wiki/processes/INFLUORA-AI-RUNTIME-COSTS.md` (mirrors `PLATFORM_COST_STRUCTURE.md`'s
structure, for Anthropic/Google API spend instead of Razorpay) and start logging real
`input_tokens`/`output_tokens`/`cache_read_input_tokens` per call — Ash's F5 finding already asks for
this instrumentation on the chat path; I'm asking for it here too.

---

## 1. Unit cost model (verified rates, real config, no cache)

| Input | Value | Source |
|---|---|---|
| Model | `claude-sonnet-4-5-20250929` | `influora-ai/app/config.py:51` |
| Base input | $3 / MTok | platform.claude.com/pricing, verified live today |
| Output | $15 / MTok | same |
| Cache | **not used** — `build_system_block()` sets no `cache_control` (`brand_safety.py:81-85`, deliberate per its docstring) | `app/prompt/brand_safety.py` |
| Chunk size | ≤25 **content items** per call (not creators) | `brand_safety_max_items_per_call`, default 25, `app/config.py:176-178` |
| Caption cap | 2,200 chars/item | `brand_safety_max_caption_chars`, `app/config.py:179-181` |
| Output cap | 4,096 tokens/call | `brand_safety_max_tokens`, `app/config.py:185-187` |
| GARM system prompt | ~2,640 chars ≈ **~660 tokens** | measured from `_GARM_SYSTEM_PROMPT`, `brand_safety.py:29-78` |
| Forced tool-use overhead | ~588 tokens (Sonnet 4.5, `tool_choice: tool`) | Anthropic pricing docs, tool-use table |

### Per-call cost, 25 items/call

| Scenario | Input tokens | Output tokens | Cost/call | Cost/item |
|---|---|---|---|---|
| **Realistic** (avg caption ~60 tok, terse JSON result ~120 tok/item) | 660 + 588 + 25×(60+40 wrap) = 3,748 | 25×120 = 3,000 | **$0.056** | **$0.0022** |
| **Worst-case** (every caption at the 2,200-char cap, output hits the 4,096-token ceiling) | 660 + 588 + 25×(550+40) = 15,998 | 4,096 (capped) | **$0.109** | **$0.0044** |

I'm carrying **$0.003–$0.005/content-item** as the planning range. Output dominates cost, so the real
lever if this gets expensive later is trimming `overall_rationale` length, not caption truncation.

### Scaled to creator count (assumption: ~15 recent content items scored per creator — **not yet verified, see §3**)

| Creator base | Content items (@15/creator) | Cost (realistic → worst-case) |
|---|---|---|
| 20 (the mandated dry-run) | 300 | **$0.90 – $1.35** |
| 100 | 1,500 | $4.50 – $6.75 |
| 1,000 | 15,000 | $45 – $68 |
| 10,000 | 150,000 | $450 – $675 |

**The 20-creator dry-run costs about a dollar.** There's no financial reason to delay it — the reason
to gate it (Priya's P0-A) is to get *real* per-creator token counts before committing to the full run,
not because the dry-run itself is risky money.

---

## 2. Recommended ceiling + kill switch (for Vikram to implement, Swapnil to approve)

1. **Dry-run cap:** hard-stop at **$5** (5× my worst-case estimate for 20 creators — cheap headroom for surprises like unexpectedly long captions).
2. **Full-backfill ceiling (pending real dry-run numbers):** propose **$100 one-time** as the approval ceiling for the current creator base — this covers the worst-case table above through ~10,000 creators. If the real dry-run number scales worse than assumed, I recompute before the full run, not after.
3. **Kill switch (Vikram, code-level, not just a number I track):**
   - Running per-job token/cost counter; job aborts if cumulative spend crosses the approved ceiling mid-run, not just at the end.
   - Daily cap on the *ongoing nightly* path (post-backfill, new-creator scoring via `ScoreCalculationJob`) — recommend **$10/day** to start; that's ~2,000 new-creator-equivalents/day at the realistic rate, far above expected signup volume.
   - Provider `ok=False` must cost nothing extra and must NOT retry in a loop that burns spend on a broken key/outage — confirm `BrandSafetyScoreService` doesn't retry-storm (Priya P0-B already requires NULL-not-zero on failure; add "and don't retry unboundedly" to that same check, tag Vikram).
4. **Log every call's `input_tokens`/`output_tokens`** to whatever the job's structured log is (Meera owns this per her T2 spec) so I can true up this estimate against real spend within the first day, not the first month.

---

## 3. What I need from Vikram/Meera before the FULL run (not the dry-run)

- Real distinct creator count and real avg content-items-scored-per-creator — one query, replaces my
  "~15 items" assumption with a fact. This is the single biggest lever on the final number.
- Confirm the dry-run's actual logged token counts once it runs — I'll republish this table with real
  numbers instead of estimates within the same day.

---

## 4. Ask of Swapnil

1. **Approve the 20-creator dry-run**, ceiling **$5**. (Priya's gate requires this in writing before Vikram runs it — this file is my sign-off; I need yours alongside it.)
2. **Approve $100 as the provisional full-backfill ceiling**, understanding I will recompute and re-flag before the full run if real dry-run numbers say otherwise.
3. **Approve opening a tracked "Influora AI runtime costs" budget line** separate from the $133/mo Sage Digital tooling budget — this backfill is the first metered production AI spend and it won't be the last (Meera chat + `classify_site` already run in production with no cost ceiling tracked anywhere either).

Once you confirm, I'll log the dry-run in `wiki/processes/cost-log.json` under a new
`influora_production_ai` section (kept separate from the existing dev-tooling budget) and alert at
70%/85% of whatever ceiling you set, same GREEN/YELLOW/RED convention as today.

`FROM Rohan → TO Swapnil | Brand-safety backfill cost approval | wiki/decisions/budget-proposals/2026-07-11-brand-safety-backfill-cost-approval.md | STATUS: awaiting Swapnil sign-off on dry-run ($5) + provisional ceiling ($100) + new runtime-cost budget line | NEXT: Vikram runs dry-run on approval, Rohan republishes with real numbers`
