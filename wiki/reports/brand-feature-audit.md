# Brand Surface — Feature Alignment Audit (wired vs. broken)

Date: 2026-07-22 · Method: `model-scheduler-audit` · Auditors: Priya (full-surface, 5 area-tracers) + Ash (AI features), independently.
Branch audited: `feat/creator-ai-copilot` (working tree; Phase-2 Meera changeset uncommitted).
Scope: **BRAND surface only**, start to end. Creator-only features excluded.

Every verdict below was traced against real code (UI call → route → service → persistence/AI → consumed), not from comments or trackers — this repo over-claims in both directions.

## Two axes (read both)

- **Code-alignment: 86% (31/36 features wired).** Is the feature genuinely connected end-to-end in code?
- **Live-proven: effectively 0% verifiable from source.** Every AI/payment feature is gated on **unprovisioned keys** (Anthropic/Gemini/Sarvam/Razorpay/RazorpayX). This is the plan's Phase-0 precondition — nothing has been model-/payment-verified live. "Code-complete" ≠ "works live."

State counts: **ALIGNED 21 · BUILT-NOT-LIVE 9 · PARTIAL 2 · BROKEN 4.**

---

## 🔴 BROKEN — real defects (4)

| # | Feature | Break (file:line) | Impact |
|---|---|---|---|
| 1 | **Meera outcome digest** (Phase 2 "moat core") | `chat.py:126-134` `_fetch_brand_context` builds `brand_fields` with 7 keys and **omits `outcome_digest`**; only `run_eval.py:369` ever wires it. `assembler.py:301` `brand.get("outcome_digest")` is always `None`. | The just-built moat payload is generated + serialized + renderer-ready but **never reaches Meera's live prompt**. Offline eval green, production dead (landmine #6). **Triple-confirmed** (Ash + 2 Priya tracers). One-line fix. |
| 2 | **Contract brand-signing** | FE sends `{name, agreedAt}` with no `role` (`api.ts:1466`); brand branch requires `body.role()` non-blank (`ContractController.java:87-90`, DTO `MoneyDtos.java:199`). Every brand sign → 400 `INVALID_SIGNER_ROLE`. | Brand can **never** record its signature → contract never both-signed → `ContractReadyForEscrowEvent` never fires via UI → the contract→escrow flow is blocked. Creator sign unaffected. |
| 3 | **Deliverable-level brand-safety review** | No code path scores submitted deliverable content. `BrandDeliverableService` has zero reference to `BrandSafetyScoreService`/`BrandSafetyAiClient`; GARM only scores creators' published IG posts in the batch job. | Brand reviews deliverables with **no safety signal on the actual submitted content**. Feature doesn't exist (vs. claimed). |
| 4 | **Brand content-performance (per-post media)** | `api.ts:2616` `GET /analytics/creators/:id/media` — **no such route** on `AnalyticsController` (only `/metrics`, `/scores`, `/demographics`). Only `/creator/analytics/me/media` exists (self-scope). | Dead FE call → 404 → generic error. Hook doc (`useContentPerformance.ts:9-12`) stale (claims `NOT_IMPLEMENTED`; actually makes a live call). |

## 🟠 PARTIAL (2)

| # | Feature | Gap | Note |
|---|---|---|---|
| 5 | **GARM grade shown to brand** | Badge mounted only on creator self-view; `brand-creator-analytics.tsx:32-36` omits it on a **stale "service isn't built" premise** — but the service IS built and the brand read path (`api.analytics.getCreatorScores` carrying `garmFlags`) exists. | Brand never sees a creator's safety grade. Fix = mount the existing badge. `BrandSafetyBadge.tsx:74-91` "backend gap" comment also stale. |
| 6 | **Brand dashboard pipeline stage colors** | FE `STAGE_COLOR` keys `Outreach/Review/Settled` vs backend `Negotiating/Contracted/In Progress/Completed` (`DashboardService.java:40-43`) → some stages fall to gray. | Cosmetic only; data flows correctly. |

## 🟡 BUILT-NOT-LIVE — code-complete, gated on an unprovisioned key (9)

| Feature | Gate |
|---|---|
| Inline Razorpay wallet top-up | Razorpay live keys (`RazorpayProperties.isConfigured`) |
| Escrow fund PENDING→FUNDED | Razorpay webhook secret (fail-closed) |
| Payout to creator bank/UPI (net-vs-gross, PENDING row, double-pay + orphaned-debit sweeper — all fixes verified) | RazorpayX keys + payout account |
| Meera chat send + SSE streaming | `ANTHROPIC_API_KEY` |
| Voice Sarvam STT/TTS + language parity | `SARVAM_API_KEY` |
| GARM brand-safety scoring (compute→persist chain aligned) | `ANTHROPIC_API_KEY` + `enabled=true` + a `ScoreCalculationJob` run (no backfill job) |
| TrendSpark LLM recovery tagger | `ANTHROPIC_API_KEY` + `TREND_TAG_INGEST_SECRET` (n8n-only, no browser FE by design) |
| `analyze_site` intake classify | Gemini key (placeholder) |
| Subscription live checkout | Razorpay keys (webhook-verified; local row only on verified webhook) |

## 🟢 ALIGNED — real end-to-end, testable now (21)

Wallet read/balance/summary · INSUFFICIENT_FUNDS 402 shortfall path · Escrow release (net + platform-fee split) · Platform-fee computation · Deals list/get/create/accept/reject/counter (dual-role) · Deal-room chat + SSE · Deliverable submission → brand review → escrow release · Server-sourced Meera brand-context assembly (PII allow-list enforced) · Meera 6-tool dispatch round-trip · Meera credit gating (per-brand + global spend) · TrendSpark nudge (deterministic fallback) · Structured product-fact / `price_source` extraction · Subscription status / plan-gating / webhook · Brand invoicing PDF · Campaign analytics (creator-reported) · Deliverable-metric submit → aggregation (PLATFORM_VERIFIED not overwritable) · Brand dashboard actions + pipeline · Brand-facing creator metrics/scores/demographics (authz pre-read) · **`get_campaign_performance` tool (Phase 2, IDOR-closed)** · **`StagePerformance` card (Phase 2)** · **Flywheel logging `OPTIONS_PRESENTED` (Phase 2, write-only by design)**

---

## Cross-cutting notes

- **Auditor agreement:** no verdict disagreements between Priya's tracers and Ash → **no Swapnil escalation needed**. The one flagged item (outcome digest) converged to BROKEN on both sides.
- **Phase 2 reality check:** the moat we just built/secured/Kabir-passed is 3/4 wired — `get_campaign_performance`, `StagePerformance`, flywheel logging are all ALIGNED; only the **outcome digest** (the highest-leverage piece) is dead behind a one-line `chat.py` omission. Kabir verified security invariants, not live wiring — hence the gap slipped every prior gate (CI diff-check only diffs field names; the wiring test feeds the assembler a dict directly).
- **Stale over-claiming comments found (false direction — "not built" when built):** `BrandSafetyBadge.tsx:74-91`, `brand-creator-analytics.tsx:32-36`, `BillingController` "NOT_YET_IMPLEMENTED", `useContentPerformance.ts:9-12`.
- **Non-blocking dangles:** manual `releasePayout` API wrapper has no UI caller; `PayoutLedger.tsx` renders mock only; flywheel log is write-only (learns nothing back yet — by design for v1).

## Fastest path to raise the number (4 real fixes, all small)

1. Outcome digest — add `"outcome_digest": context_data.get("outcome_digest")` at `chat.py:126`. (S)
2. Contract brand-sign — send `role` from the FE, or default it server-side for the brand branch. (S)
3. Content-performance media — add the brand `/{creatorId}/media` route, or make the FE honestly surface not-implemented. (S–M)
4. GARM brand display — mount the existing `BrandSafetyBadge` on `brand-creator-analytics.tsx`. (S)

Deliverable-level brand-safety review (#3) is the only genuinely new build.
