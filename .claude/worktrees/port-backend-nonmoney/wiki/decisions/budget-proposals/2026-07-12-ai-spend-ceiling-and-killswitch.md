# Budget Proposal — Production AI-Spend Ceiling + Kill-Switch (P2-17)

**Date:** 2026-07-12 · **Author:** Rohan (CFO) · **For:** Vikram (impl) · **Packet:** `wiki/reports/2026-07-12/tasks/P2-17-ai-spend-ceiling.md`
**Supersedes/extends:** `wiki/decisions/budget-proposals/2026-07-11-brand-safety-backfill-cost-approval.md` §2 (that proposal covered only the brand-safety backfill job and, per that file's §0, opened the question of a general runtime AI budget line — nothing from it was actually implemented; verified via grep, zero hits for kill-switch/ceiling code anywhere in `influora-ai`). This proposal covers **all three** production AI call sites: Meera chat (`routes/chat.py`), `classify_site`, and brand-safety scoring/backfill.

## 0. Ground truth check
No AI runtime spend is tracked or capped anywhere today. `influora-ai/app/config.py` has provider timeouts, retry policy, and a circuit breaker — none of it dollar-aware. `wiki/processes/cost-log.json` tracks Sage Digital's own $133/mo dev-tooling budget only, not Influora's production Anthropic/Google bill.

## 1. Unit costs (verified rates, no cache in use anywhere in this service)
| Model | Input | Output | Source |
|---|---|---|---|
| `claude-sonnet-4-5-20250929` (`CLAUDE_MODEL`, chat + brand-safety) | $3/MTok | $15/MTok | platform.claude.com pricing, verified 2026-07-11 |
| `gemini-2.5-flash-lite` (`GEMINI_MODEL`, classify_site) | $0.10/MTok | $0.40/MTok | Google AI pricing (flash-lite tier) |
| Sarvam STT/TTS | flat per-call estimate, ~$0.006/call | — | Sarvam pricing page |

## 2. Ceiling numbers (the actual thresholds Vikram implements)

| Ceiling | Value | Scope | Behavior at limit |
|---|---|---|---|
| **Global daily hard ceiling** | **$15.00 / UTC day** | All providers, all routes combined (chat + classify_site + brand-safety, sum of computed cost from `token_usage` on every completed call) | **Hard stop.** Any new provider-bound request after the ceiling is crossed returns `503 AI_SPEND_CEILING_REACHED` with zero provider call made. Resets at UTC midnight. |
| **Global kill-switch** | `AI_SPEND_KILL_SWITCH` env var, default `false` | All providers, all routes | When `true`: **every** provider-bound request short-circuits immediately (checked before the ceiling check, before any provider call) with `503 AI_KILL_SWITCH_ACTIVE`. Manual, instant, for outages/runaway-cost incidents — flip the env var and redeploy/restart, no code change needed. |
| **Per-workspace daily soft cap** | $3.00 / UTC day / workspace | Chat only (the only route with a reliable workspace_id on every call today) | **Not a hard block in this pass** — logged as a WARNING when crossed (`workspace_id`, `spend_today`) so Rohan can see which single workspace is driving the global ceiling before deciding whether to hard-block it later. Promoting this to a hard per-workspace block is an explicit follow-up once real per-workspace numbers exist (same reasoning as the 2026-07-11 proposal's "recompute before the full run" pattern). |
| **Monthly informational cap** | $300.00 / calendar month | All providers, all routes | **Not enforced in-process** (see §4 — accumulation is per-process, doesn't survive a restart or span multiple app instances, so a true 30-day rolling sum isn't reliable yet). Rohan tracks this manually in `wiki/processes/cost-log.json` under a new `influora_production_ai` section using the daily structured log lines Vikram adds (§3.4), same as the open item from 2026-07-11. |

Daily $15 sizing: covers the brand-safety $10/day nightly-scoring number from the 2026-07-11 proposal plus realistic Meera-chat + classify_site headroom at current (early) traffic. **Not a permanent number** — re-price once a full month of real logged spend exists (same "recompute, don't set-and-forget" discipline as the earlier proposal).

## 3. Implementation spec (Vikram)

1. **Pricing table** — new `influora-ai/app/costs/pricing.py`: a dict of `model_id -> (input_$_per_Mtok, output_$_per_Mtok)` per §1, plus a `estimate_cost_usd(model, usage) -> Decimal` helper. Use `Decimal`, not `float`, for money math (existing Java side already uses `BigDecimal` for the same reason).
2. **Spend counter** — new `influora-ai/app/costs/spend_tracker.py`: a module-level, thread/async-safe (use `asyncio.Lock` or equivalent) counter keyed by UTC date, holding `{global_total, per_workspace: dict[str, Decimal]}`. Reset when the stored date rolls to a new UTC day. **Explicitly scoped as per-process** for this pass — each running Python worker tracks its own spend independently. This is a known, documented limitation (not silently swept under the rug): if the service runs with >1 worker process, the *effective* real ceiling is `$15 × worker_count` until this is backed by a shared store. Flag this in the PR description. Phase 2 follow-up (out of scope for P2-17): back this with the existing Spring internal-API pattern (`SPRING_INTERNAL_BASE_URL`, HMAC-signed, same as other Python→Spring calls) so spend is a single cross-instance source of truth in Postgres — do not build a new datastore just for this.
3. **Enforcement point** — a single `check_spend_gate()` function called at the top of: `routes/chat.py` (`chat()`, before `run_tool_loop` starts), the `classify_site` route, and the brand-safety scoring path (`BrandSafetyScoreService`/`ScoreCalculationJob`'s call site). Order of checks: (a) kill-switch env var, (b) today's global total vs. $15 ceiling. On either trip, return the appropriate structured error (per §2) and make **zero** provider calls — mirrors the existing `service_token.py` pattern ("Any failure -> 401/403 ... no token spend") already used for auth failures.
4. **Recording** — after every successful provider call that returns usable `usage`/`token_usage` (chat.py already captures `final_usage` at line 134/175), compute cost via `estimate_cost_usd` and add to the tracker. Emit one structured log line per call: `{"event": "ai_spend", "route": ..., "model": ..., "cost_usd": ..., "spend_today_usd": ..., "workspace_id": ...}` — this is the raw material for Rohan's manual monthly rollup (§2 monthly cap) and for re-pricing the $15/day number later.
5. **Config** — add to `Settings` in `config.py` (using existing `_get_float`/`_get_bool` helpers, same pattern as every other setting in that file):
   - `ai_daily_spend_ceiling_usd: float` ← `AI_DAILY_SPEND_CEILING_USD`, default `15.0`
   - `ai_spend_kill_switch: bool` ← `AI_SPEND_KILL_SWITCH`, default `false`
   - `ai_workspace_daily_soft_cap_usd: float` ← `AI_WORKSPACE_DAILY_SOFT_CAP_USD`, default `3.0`
6. **Monitoring** — minimum bar: the structured log line in (4) is sufficient (Rohan tails/great-greps logs each morning, same as today's manual process). Nice-to-have if time allows: a lightweight internal-auth-only `GET /internal/ai-spend/status` returning today's global total + per-workspace breakdown, so this can be checked without shelling into logs — not a blocking acceptance criterion.

## 4. What this proposal deliberately does NOT do (documented, not hidden)
- No cross-instance/shared persistent ledger (Phase 2, flagged above).
- No automatic paging/alerting integration (Slack/email) — manual log-watching only, same tier as today's process.
- No hard per-workspace block yet, only a WARNING log — promote to hard block once real per-workspace distribution is known.
- Monthly $300 is informational only, not code-enforced, for the same reason (no durable cross-restart aggregation yet).

## 5. Acceptance criteria for Vikram
- [ ] Kill-switch env var checked first, blocks all 3 routes with zero provider calls when `true`.
- [ ] Daily $15 global ceiling enforced across chat + classify_site + brand-safety, resets at UTC midnight, blocks with `503 AI_SPEND_CEILING_REACHED` when crossed.
- [ ] Per-workspace $3/day soft cap logged (chat route) — WARNING only, not blocking.
- [ ] Structured `ai_spend` log line emitted per completed provider call with real cost.
- [ ] Config values wired through `Settings`/env vars per §3.5, defaults exactly as specified.
- [ ] Kavya QA · Meera real `pytest`/`mvn -o test` (whichever suite covers the touched files) green.

**Sign-off:** Rohan · 2026-07-12 · ceiling + kill-switch model approved for Vikram to implement. Swapnil should be looped in on the $15/day + $300/mo numbers since this is the first hard-coded production AI budget line the company has ever had (same disclosure norm as the 2026-07-11 proposal).

`FROM Rohan → TO Vikram | P2-13 + P2-17 specs ready | wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md, wiki/decisions/budget-proposals/2026-07-12-ai-spend-ceiling-and-killswitch.md | STATUS: signed off, unblocked (P0-1 done) | NEXT: Vikram implements both against current code, then Kavya QA -> Meera real mvn -o test/pytest`
