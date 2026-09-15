# Ruling: engagement-rate formula — reach-based

> **Decision by:** Swapnil Maruti (CEO) — final authority on business direction
> **Advised by:** Priya (CTO)
> **Date:** 2026-09-15
> **Status:** LOCKED
> **Unblocks:** Task 2.1 (`wiki/processes/task-creator-profile-and-copilot.md`), therefore Phase 2

---

## The question

`CreatorMetric.avgEngagementRate` and `platform_stats.engagement_rate` are permanently null
today — nothing computes them (F-0793). Two candidate formulas:

- **Follower-based:** `(likes + comments + …) / followers`
- **Reach-based:** `(total_interactions) / reach`, over the trailing N posts

Once a number is published, brands will compare it against other platforms and other creators.
We cannot quietly change the formula later without the number moving for no visible reason.

## Decision: reach-based. LOCKED.

`engagement_rate = total_interactions / avg_reach_per_post`, computed over the trailing lookback
window `MetricsPollingJob`/`AnalyticsService` already use (`CONTENT_PERFORMANCE_LOOKBACK`,
`influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java:56`).

**Why:**

1. **Harder to game.** A follower-based rate rewards a large, partly-dormant or partly-fake
   follower count with a *lower* denominator relative to actual delivered interactions — buying
   followers understates nothing and can even help the number. A reach-based rate is anchored to
   posts Instagram actually delivered, which fake followers do not inflate.
2. **The input is already collected.** `CreatorMetric.avgReachPerPost` exists as a field
   (`influora-api/src/main/java/com/influora/domain/entity/CreatorMetric.java:95`) — we already
   pull `reach` per post via `InstagramInsightsClient` at Advanced Access. No new Meta scope, no
   new API cost.
3. **Matches how Instagram's own Insights present engagement** — per-delivered-audience, not
   per-follower-count — so our number will not read as an outlier next to what a creator already
   sees on their own account.

## What this does NOT decide

- The exact lookback window (posts vs. days) — implementation detail for Task 2.1, not a business
  call.
- Whether `reach` of 0 or null falls back to follower-based for that single post, or is simply
  excluded from the average. Vikram's implementation call; the honest-null discipline
  (`wiki/tech/profile-data-model.md` §5.4) applies either way — never coerce to a fabricated 0.

## Consequence

Task 2.1 is unblocked. `PlatformStatsAggregationJob`'s existing null-until-computed comment
(`influora-api/src/main/java/com/influora/job/PlatformStatsAggregationJob.java:47-51`) stops being
permanently true the moment this formula lands.
