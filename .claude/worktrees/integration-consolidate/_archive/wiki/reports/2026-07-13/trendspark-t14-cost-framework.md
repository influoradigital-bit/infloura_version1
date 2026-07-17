# Trend-Spark AI — Task 14: Post-Launch ₹/Nudge Cost Report FRAMEWORK

> **Owner:** Rohan (CFO) · **Date:** 2026-07-13 · **Tracker:** `trendspark/INDEX.md` Task 14
> **Depends on:** `wiki/tech/budget-approvals-trendspark.md` (T2 policy/cap), `wiki/architecture/trendspark-priya-schema-lock.md` §1d (`nudge_log` schema), `influora-ai/app/costs/pricing.py` (Haiku pricing, T8/Ash).

---

## 0. What this is — and isn't

This is the **framework**: exact SQL, the unit-economics model, and the alert plan, built so
Rohan can run one query and one script the moment real traffic exists. **It is not a live cost
report** — there is no `nudge_log` traffic yet (Trend-Spark just finished sign-off at Task 13,
2026-07-13). No live ₹ figure is stated anywhere below as if it were real. See STATUS at the
bottom.

**Known data gap found while building this (flagging, not fixing — not in Rohan's authority):**
`nudge_log` (schema-lock §1d) has `message_source` (AI|FALLBACK) but **no `cost_usd`/token
columns**, and per `influora-ai/app/costs/spend_tracker.py` line 47-48 the in-process spend
counter resets daily and is explicitly *not* the rollup source — the only cost signal is the
structured `ai_spend` log line emitted in `influora-ai/app/routes/trendspark.py` (lines 269-288:
`route=trendspark_nudge, model, cost_usd, spend_today_usd, workspace_id, request_id`). That means
**real ₹/nudge is a two-source reconciliation (MySQL `nudge_log` count/rate query + log-pipeline
aggregation of `ai_spend` lines), not a single SQL join** — there's no shared key between a
`nudge_log` row and its `ai_spend` log line today (no `request_id` column on `nudge_log`). §1 and
§2 below account for this; §1 gives the volume/flywheel side from MySQL, §2 gives the projected
₹ side from `pricing.py`, and real ₹/nudge = §2's per-unit price × §1's actual AI-sourced volume,
cross-checked against summed `ai_spend.cost_usd` once logs are queryable. If tighter precision is
wanted later, the fix is a one-line Flyway add (`nudge_log.request_id` + optional `cost_usd`) —
Vikram/Priya call, not proposed here.

---

## 1. The SQL — `nudge_log`, once live

All queries run against MySQL (`influora-api`, Flyway `V51__trendspark.sql`). Swap the date
range (`@from`/`@to`) for whatever window is being reported (first live week = 7 days).

```sql
SET @from = '2026-08-01 00:00:00';   -- placeholder — first live week, once traffic exists
SET @to   = '2026-08-08 00:00:00';

-- 1a. Nudges per day
SELECT
  DATE(shown_at)              AS day,
  COUNT(*)                    AS nudges_shown
FROM nudge_log
WHERE shown_at >= @from AND shown_at < @to
GROUP BY DATE(shown_at)
ORDER BY day;

-- 1b. % AI vs FALLBACK (the guardrail health signal — high FALLBACK% means the
--     model/prompt/validation is misbehaving, not just a cost question)
SELECT
  message_source,
  COUNT(*)                                                    AS nudges,
  ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)          AS pct_of_total
FROM nudge_log
WHERE shown_at >= @from AND shown_at < @to
GROUP BY message_source;

-- 1c. The flywheel: click-through and purchase rate, overall
SELECT
  COUNT(*)                                                      AS nudges_shown,
  SUM(clicked_at IS NOT NULL)                                   AS nudges_clicked,
  SUM(purchased_at IS NOT NULL)                                 AS nudges_purchased,
  ROUND(100.0 * SUM(clicked_at IS NOT NULL) / COUNT(*), 2)      AS click_through_pct,
  ROUND(100.0 * SUM(purchased_at IS NOT NULL) / COUNT(*), 2)    AS purchase_pct,
  ROUND(100.0 * SUM(purchased_at IS NOT NULL)
        / NULLIF(SUM(clicked_at IS NOT NULL), 0), 2)            AS click_to_purchase_pct
FROM nudge_log
WHERE shown_at >= @from AND shown_at < @to;

-- 1d. Flywheel broken out by mode (SNAPSBY nudges are the only ones that can
--     purchase against snapsby_catalog_video; OWN_CONTENT never mentions the
--     marketplace per schema-lock §3 — expect purchased_at to be near-zero there,
--     and treat a nonzero value as a bug, not a win)
SELECT
  mode,
  COUNT(*)                                                      AS nudges_shown,
  SUM(clicked_at IS NOT NULL)                                   AS clicked,
  SUM(purchased_at IS NOT NULL)                                 AS purchased,
  ROUND(100.0 * SUM(clicked_at IS NOT NULL) / COUNT(*), 2)      AS click_through_pct,
  ROUND(100.0 * SUM(purchased_at IS NOT NULL) / COUNT(*), 2)    AS purchase_pct
FROM nudge_log
WHERE shown_at >= @from AND shown_at < @to
GROUP BY mode;

-- 1e. AI-sourced nudge volume — the number to multiply by ₹/nudge (§2). FALLBACK
--     nudges cost ₹0 in Anthropic spend (deterministic template, no provider call
--     per trendspark.py's _fallback_response() path) — do NOT include them in cost.
SELECT COUNT(*) AS ai_sourced_nudges
FROM nudge_log
WHERE shown_at >= @from AND shown_at < @to
  AND message_source = 'AI';
```

**Combining with the token/cost log (Ash's `ai_spend` structured log line, §0):**
```
real_total_spend_usd  = SUM(cost_usd) from ai_spend log lines WHERE route='trendspark_nudge'
                         AND timestamp BETWEEN @from AND @to
real_₹_per_nudge       = (real_total_spend_usd × USD_INR_rate) / ai_sourced_nudges   -- from 1e
real_₹_per_purchase    = (real_total_spend_usd × USD_INR_rate) / nudges_purchased    -- from 1c
```
`real_total_spend_usd` is a log-pipeline aggregation (grep/CloudWatch Insights/whatever log sink
is wired up — not decided in this doc), summing the `cost_usd` field Ash's route already emits.
This is the only place a *real*, non-estimated ₹/nudge can come from.

---

## 2. Unit-economics model (projected, from `pricing.py` — not fabricated, not live)

Reusing the exact numbers already coded, not invented for this report:

| Input | Value | Source |
|---|---|---|
| Model | `claude-haiku-4-5-20251001` | `influora-ai/app/config.py:55` (`TRENDSPARK_MODEL`) |
| Price — input | $1.00 / MTok | `influora-ai/app/costs/pricing.py:43` |
| Price — output | $5.00 / MTok | `influora-ai/app/costs/pricing.py:43` |
| Output cap | 256 tokens (`TRENDSPARK_MAX_TOKENS`) | `influora-ai/app/config.py:212-214` |
| Tokens in per nudge (estimate) | ~600 | `wiki/tech/budget-approvals-trendspark.md` §2 |
| Tokens out per nudge (estimate) | ~200 (within the 256 cap) | same |
| USD→INR | ₹87 / $1 (approx, adjust to live rate) | same |

```
Cost/nudge = (600 × $1.00 + 200 × $5.00) / 1,000,000
           = ($600 + $1,000) / 1,000,000
           = $0.0016 / nudge
           ≈ ₹0.14 / nudge          (matches budget-approvals-trendspark.md §2 — same math, reused not redone)
```

This is a **projection from an assumed token count**, not a measured one. §1's real query is
what replaces it with a measured number after the first live week.

### Projected monthly spend — 3 volume scenarios vs the ₹1,500/mo cap

| Scenario | Nudges/mo | Projected spend | % of ₹1,500 cap | Cap status |
|---|---|---|---|---|
| Low | 1,000 | 1,000 × ₹0.14 = **₹140** | 9.3% | Well under |
| Mid (v1 planning volume) | 5,000 | 5,000 × ₹0.14 = **₹700** | 46.7% | Under, healthy |
| High | 20,000 | 20,000 × ₹0.14 = **₹2,800** | **186.7%** | **Breaches cap** |

### Where the cap binds

```
Breakeven volume = ₹1,500 / ₹0.14  ≈  10,714 nudges/month
```

The ₹1,500/mo cap binds at **~10,700 AI-sourced nudges/month** — consistent with the ceiling
volume already stated in `wiki/tech/budget-approvals-trendspark.md` §2 ("~10,700 nudges/month
... before hitting the cap"). Below that volume the cap has headroom; above it, spend exceeds
policy and needs either a higher approved cap or nudge-frequency throttling (schema-lock §2's
`THRESHOLD`/gap-check knobs, a Vikram/Ash change, not a Rohan one).

**Separate, coarser guardrail already live in code (not Trend-Spark-specific):**
`influora-ai/app/costs/gate.py` enforces `AI_DAILY_SPEND_CEILING_USD` (default $15.00/day,
`config.py:218-220`) as a hard kill-switch (`check_spend_gate()`, called at the top of
`trendspark_nudge`, lines 231-238) — **but this ceiling is shared across every AI route** (chat,
analyze_site, brand_safety, trendspark), not scoped to Trend-Spark alone. $15/day ≈ ₹1,305/day ≈
~₹39,150/month *if fully consumed by Trend-Spark alone* — far looser than the ₹1,500/mo
Trend-Spark-specific policy cap. Do not mistake the global kill-switch being green for the
Trend-Spark ₹1,500/mo policy cap being respected — they are two different, non-equivalent limits.
The ₹1,500/mo cap still has **no dedicated enforcement**, per budget-approvals-trendspark.md §4 —
that open item is unchanged by this report.

---

## 3. Alert / threshold plan

| Trigger | Threshold | Action |
|---|---|---|
| **Trend-Spark monthly Anthropic spend** (from §1's real-spend reconciliation) | **80% of ₹1,500 = ₹1,200/mo** | Rohan flags Swapnil same day via `SHARED_CONTEXT.md` + updates `wiki/processes/cost-log.json` (existing policy, `budget-approvals-trendspark.md` §2 — not new) |
| Trend-Spark monthly spend | **100% of ₹1,500** | Recommend Ash/Vikram throttle nudge frequency (raise gap-check `THRESHOLD`, schema-lock §2) or Swapnil approves a higher cap — no silent overspend |
| **Measured real ₹/nudge** (§1) vs **projected ₹0.14** (§2) | Real exceeds projection by **>50% for 2 consecutive reporting weeks** | Investigate: prompt/context bloat, output near/at the 256-token cap on most calls, or a pricing-table staleness bug — re-run §2's math with real token counts, don't just re-raise the cap |
| **Global daily kill-switch** (`AI_DAILY_SPEND_CEILING_USD`, gate.py) | Approaching $15/day *combined across all AI routes* | Not Trend-Spark-specific — if this trips, Trend-Spark nudges silently fall back to templates (fail-closed, `trendspark.py` lines 231-238) with zero error shown to the user; Rohan should distinguish "Trend-Spark cap hit" from "global kill-switch hit" in any alert, since the fix differs (raise Trend-Spark cap vs raise/adjust the shared daily ceiling) |
| **Free-tier source approaching limit** (reconsider paid upgrade) | SerpAPI fallback crosses **70/100 searches/month**, or NewsAPI/YouTube cross **80% of daily cap** | Per `budget-approvals-trendspark.md` §3 (unchanged policy) — this is the trigger to propose a paid-tier source, separate from the Anthropic nudge-cost trigger above |

---

## 4. STATUS

**Framework ready. Real ₹/nudge report PENDING first live week (no `nudge_log` traffic yet) —
cannot and will not report a fabricated live number.** All figures above are either (a) reused,
already-coded prices from `pricing.py`/`config.py`, or (b) projections built from the existing
₹0.14/nudge estimate in `budget-approvals-trendspark.md` §2, clearly labeled as projected. §1's
SQL runs the moment `nudge_log` has rows; §1's cost reconciliation runs the moment `ai_spend` log
lines are queryable in whatever log pipeline the team wires up (not decided here — flagging as an
open item, not solving it, since log-pipeline choice isn't Rohan's authority).

---
**Rohan sign-off:** Framework built and reconciled against real code (`pricing.py`, `config.py`,
`trendspark.py`, `gate.py`, `spend_tracker.py`) and the locked schema — no invented numbers, no
live ₹ figure claimed. — Rohan · 2026-07-13
