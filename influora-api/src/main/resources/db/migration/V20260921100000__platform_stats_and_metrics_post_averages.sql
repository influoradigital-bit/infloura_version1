-- Per-post averages surfaced to brands on PlatformStatResponse.
--
-- creator_metrics already carried avg_reach_per_post and avg_impressions_per_post (V21), computed
-- and stored by MetricsPollingJob on every poll but never read by anything creator- or brand-facing.
-- This migration (a) adds the two missing per-post averages to creator_metrics, and (b) rolls all
-- four, plus a sync timestamp, up into platform_stats so the portfolio read path can serve them
-- without a second query.
--
-- NULLABILITY IS THE POINT (F-0589). Every column below is NULL with NO DEFAULT. A creator who has
-- not connected Meta - which is very nearly all of them today - has no media insights at all, and an
-- absent average must arrive at the UI as null so the block can be omitted. A `DEFAULT 0` here would
-- render "0 avg reach" as though it were a measured fact. Contrast platform_stats.source in
-- V20260919100000, which correctly took NOT NULL DEFAULT 'CREATOR_REPORTED' because it is a
-- fail-closed enum; a count has no fail-closed value.
--
-- There is deliberately NO backfill UPDATE. Existing rows stay null until the next poll writes real
-- values. Backfilling from the newest creator_metrics row would stamp a last_synced_at that is not
-- that row's provenance - the same class of over-claim as EV-008/CR-119.

-- avg_views_per_post is sourced from creator_metrics.avg_impressions_per_post: Meta's unified `views`
-- count lands in the impressions column (MediaMetricMapper), and `views` is what the UI says.
-- BIGINT/DATETIME(6) mirror creator_metrics exactly (V21) so precision survives the copy;
-- DATETIME(6) rather than TIMESTAMP also avoids MySQL's implicit ON UPDATE behaviour.
ALTER TABLE platform_stats ADD COLUMN avg_reach_per_post     BIGINT      NULL;
ALTER TABLE platform_stats ADD COLUMN avg_views_per_post     BIGINT      NULL;
ALTER TABLE platform_stats ADD COLUMN avg_likes_per_post     BIGINT      NULL;
ALTER TABLE platform_stats ADD COLUMN avg_comments_per_post  BIGINT      NULL;

-- Deliberately NOT sourced from platform_stats.updated_at (V6), which is bumped by ANY row write -
-- including PortfolioService.declarePlatform (a creator typing a handle) and the F-0965 backfill
-- UPDATEs. Rendering that as "Synced 2m ago" would claim a Meta sync that never happened. This column
-- is written only from creator_metrics.fetched_at, and only for a META_API snapshot; a
-- creator-declared row leaves it null.
ALTER TABLE platform_stats ADD COLUMN last_synced_at         DATETIME(6) NULL;

-- creator_metrics already has avg_reach_per_post and avg_impressions_per_post (V21:26-27); likes and
-- comments were never aggregated. MediaMetric has carried both per post since V21 - only the
-- creator-level roll-up was missing.
ALTER TABLE creator_metrics ADD COLUMN avg_likes_per_post     BIGINT NULL;
ALTER TABLE creator_metrics ADD COLUMN avg_comments_per_post  BIGINT NULL;
