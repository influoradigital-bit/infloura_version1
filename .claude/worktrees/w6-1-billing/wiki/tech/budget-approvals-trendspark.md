# Trend-Spark AI — Budget Approvals (v1)

> **Owner:** Rohan (CFO) — this is the POLICY + math document, per `trendspark/02-API-KEYS-REQUIRED.md`
> billing checklist and `wiki/architecture/trendspark-priya-schema-lock.md` §5.
> **Date:** 2026-07-13 · **Task:** INDEX.md Task 2 (unblocked by Priya's Task 1 lock)
> **Scope:** v1 sources only. Phase 2 paid sources (X API, SerpAPI paid, Meta official) are
> out of scope here — those need a fresh Swapnil-approved proposal when triggered.

---

## 1. Free-tier confirmation table

One "pull" = the n8n 6 AM job (§0 of the schema-lock doc: n8n writes `trends` once/day).

| # | Source | Free tier? | Specific free limit | Usage for ONE 6 AM pull | Headroom |
|---|--------|-----------|---------------------|--------------------------|----------|
| 1 | **Google Trends** (pytrends, default) | Yes | No official quota — unofficial lib, unbounded but rate-limit/breakage risk, not a $ risk | 1 script run, handful of keyword queries | N/A (risk is reliability, not spend — see §3) |
| 1b | **Google Trends fallback** (SerpAPI, only if pytrends breaks) | Yes | 100 searches/**month** | ~1 search/day if promoted to primary = ~30/month | 70/100 (70%) spare — **tightest quota of all v1 sources** |
| 2 | **NewsAPI** | Yes | 100 requests/**day** | ~5–10 category requests | ~90+/100 (90%+) spare |
| 3 | **TMDb** | Yes | No published hard daily cap (soft rate-limit ~40 req/10s) | ~5–10 requests | Effectively unbounded for this volume |
| 4 | **YouTube Data API v3** | Yes | 10,000 units/**day** | `videos.list` (chart=mostPopular) ≈ 1 unit/call; even padded to a `search.list` call (100 units) + a few list calls ≈ ~150 units | ~9,850/10,000 (98.5%) spare |
| 5 | **Festival calendar** | Yes | N/A — static JSON, no API, no quota | 1 file read | Unbounded |
| 6 | **Snapsby catalog DB** | Yes | N/A — our own MySQL, existing infra credentials | Normal query load | Unbounded (governed by DB capacity, not a billing item) |
| 7 | **Anthropic** (nudge phrasing) | **No — the one paid item** | Pay-per-token | See §2 | Capped below |

**Confirmation: 6 of 7 v1 sources are genuinely $0, free-tier, with wide headroom for a single daily pull.** Anthropic is the only metered cost in v1, and it's small and cappable.

---

## 2. The one paid item — Anthropic nudge phrasing

**Model:** Haiku-class, `claude-haiku-4-5-20251001` (locked by Priya, §4 of schema-lock doc — not Sonnet/Opus).
**Call pattern:** exactly one AI call per nudge, phrasing only, after all rules/logic already ran in Java (§4).

### ₹ math (assumptions stated so Swapnil/Ash can correct the inputs)

| Input | Value |
|---|---|
| Price — Haiku 4.5 input | $1.00 / MTok |
| Price — Haiku 4.5 output | $5.00 / MTok |
| Tokens in per nudge (trend text, brand context, catalog ids, guardrail instructions) | ~600 tokens |
| Tokens out per nudge (short structured JSON message) | ~200 tokens |
| USD→INR (approx, adjust to live rate) | ₹87 / $1 |

```
Cost/nudge = (600 × $1.00 + 200 × $5.00) / 1,000,000
           = ($600 + $1,000) / 1,000,000
           = $0.0016 / nudge
           ≈ ₹0.14 / nudge
```

### Volume assumption for v1

MVP workspace count is small; nudges only fire when the content-gap check trips (§3 of
schema-lock doc — "stay silent" is the default, not every workspace nudges every day).
Sane v1 planning volume: **up to 5,000 nudges/month** (headroom built in for growth beyond
actual expected MVP traffic).

```
Monthly cost @ 5,000 nudges = 5,000 × ₹0.14  ≈ ₹700/month  (~$8)
```

### Proposed cap

| | |
|---|---|
| **Proposed monthly spend cap** | **₹1,500/month** (~$17) — ~2x the 5,000-nudge estimate, covers volume spikes/retries without being loose |
| **Cap implies ceiling volume** | ~10,700 nudges/month at ₹0.14 each before hitting the cap |
| **Alert threshold** | **80% of cap = ₹1,200/month** → notify Swapnil |
| **Escalation if breached** | At 80%: Rohan flags Swapnil same day via `SHARED_CONTEXT.md` alert + this doc updated. At 100%: recommend Ash/Vikram throttle nudge frequency or Swapnil approves a higher cap — **no silent overspend**. |

This is a planning cap, not a hard kill-switch — see §4 for what still needs to happen for
it to be a *real*, enforced cap.

---

## 3. Alert policy — free sources

**Closest to its limit: Google Trends fallback path (SerpAPI, 100 searches/month).**
It has the smallest absolute quota of any v1 source. Even though it's not the default (pytrends
is primary, no formal quota), if pytrends breaks and Dev promotes SerpAPI to primary, 70% of
its monthly quota is consumed just by normal daily use, leaving very little room for retries.

**Trigger for a Swapnil notification (any of):**
1. SerpAPI usage crosses **70/100 searches in a calendar month** (70%) — flag now, before it
   becomes a hard blocker.
2. **Any** free source returns a 429 / rate-limit error on the 6 AM pull — this should never
   happen at v1 volume, so a single occurrence means something is wrong upstream (retry storm,
   duplicate cron trigger, etc.) and gets escalated immediately regardless of %.
3. NewsAPI or YouTube daily usage crosses **80% of its daily cap** (80/100 requests or
   8,000/10,000 units) — unlikely at v1 volume, but same 80%-of-limit rule as the Anthropic cap
   for consistency.

Dev owns the n8n workflow and is the one who'd see request counts/errors first; Rohan's role is
to log the alert in `wiki/processes/cost-log.json` / `SHARED_CONTEXT.md` and escalate to Swapnil.

---

## 4. What this document is — and isn't

This document sets the **policy and the math**: the cap number (₹1,500/month), the alert
threshold (₹1,200 / 80%), and the free-tier confirmation. It does **not** itself configure a
live, enforced billing cap — Rohan has no access to configure spend limits on the Anthropic
console or any other billing dashboard.

**Action required (not done by this doc):** whoever owns the Anthropic account / billing
console (per `02-API-KEYS-REQUIRED.md`, the existing Claude account used by the agents) must
manually set the actual usage limit / alert in the Anthropic Console to match the numbers
above. Until that's done, this cap is a **policy commitment**, not a technical guardrail.
Flagging this to Swapnil as the one open item from this task.

---

**Rohan sign-off:** v1 confirmed ₹0-fixed across 6 of 7 sources; Anthropic is the sole metered
cost, with a proposed ₹1,500/month cap and ₹1,200 (80%) alert threshold. Live console cap still
needs to be configured by the account owner (§4). — Rohan · 2026-07-13
