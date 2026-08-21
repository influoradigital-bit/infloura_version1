# ADR: BrandSafetyScoreService — persist captions during polling (not live-fetch at scoring)

> **Decision by:** Priya (CTO) — final authority on technical architecture
> **Date:** 2026-07-06
> **Status:** LOCKED (decision made; implementation deferred — see sequencing)

---

## Context

`BrandSafetyScoreService` (spec §4.3) needs post caption/text content to send to
`influora-ai` for GARM-framework NLP analysis. But Phase 2's schema stores **zero
text content** — `media_metrics` and `creator_metrics` hold only numeric engagement
fields. Two candidate sources for caption text:

- **Option A** — persist caption text in our DB during metrics polling, then have the
  scoring job read it from our DB.
- **Option B** — fetch captions live from Meta at scoring time, inside the daily job.

## Verified facts (grounded, not assumed)

- `InstagramInsightsClient.MEDIA_FIELDS` **already requests `caption`**
  (`id,caption,media_type,media_url,permalink,timestamp,like_count,comments_count`) —
  caption text already arrives in the `getMedia` response today; it is simply discarded.
- `MediaMetric` has **no caption field**, and `MetricsPollingJob`'s per-post
  `media_metrics` polling is **entirely stubbed** (documented TODO) — only creator-level
  profile metrics are persisted today. The `media_metrics` storage layer exists but is
  unused.

## Decision: Option A (persist during polling). LOCKED.

Rejected Option B: fetching live at scoring time would couple the daily
`ScoreCalculationJob` — currently a clean, resilient DB-reader — to a live external
Meta call per creator, re-fetching content already pulled during metrics polling, and
adding rate-limit exposure + token-expiry failure modes to the scoring path. That
contradicts the deliberate poll→store→read architecture of the entire analytics layer.

Option A: the caption is already in the fetched payload (no extra API cost); scoring
stays decoupled and runs GARM/NLP against a stable DB snapshot. Consistent with how
every other score is computed (read persisted metrics, never call Meta at score time).

## Privacy / retention constraint (binding)

Captions are already-public Instagram content, so storing them for brand-safety
analysis is defensible — but: store caption text on `media_metrics` (or a dedicated
column) **only**, apply a retention limit consistent with the metrics data, never expose
raw caption text through any brand-facing DTO (only the derived `brand_safety_score` /
`garm_flags` may surface), and keep it out of logs (same redaction discipline as
`AuditLogService`).

## Prerequisite chain (this is an epic, not a column add)

When BrandSafetyScoreService is scheduled, the honest order is:
1. Implement the stubbed `media_metrics` per-post polling in `MetricsPollingJob`
   (map `InstagramInsightsResponse` → `MediaMetric`, handle per-media-type metric
   availability — the reason it was originally deferred).
2. Add a caption text column (migration V23+, MySQL per the Phase-2 datastore ADR).
3. Build the `influora-ai` `/internal/brand-safety` endpoint + GARM/NLP prompt
   (cross-repo Python + LLM prompt design — the genuinely novel part).
4. Build `BrandSafetyAiClient` (Java, `integration/ai/`) + `BrandSafetyScoreService`.
5. Wire into `ScoreCalculationJob` (populate the currently-nullable
   `brand_safety_score`/`garm_flags`/`content_sentiment` columns on `creator_scores`).

## Sequencing decision

**Deferred behind Phase 4 (UTM/Coupons).** Phase 4 is 0%, higher business value
(conversion attribution — the money-proving demo), and well-understood pure-Java work
matching the team's proven execution pattern. BrandSafetyScoreService is the
highest-uncertainty remaining item and its absence is already handled gracefully (the
shipped dashboard shows "not yet available" for these fields). Build the certain,
high-value work first; give the cross-repo AI epic a dedicated focused pass afterward.
