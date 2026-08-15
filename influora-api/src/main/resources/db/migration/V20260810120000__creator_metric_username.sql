-- CR-116 — InstagramUserResponse.username() was already fetched by MetricsPollingJob but
-- discarded: no column existed anywhere to hold it, so PlatformStat.handle stayed null from
-- every automated path. creator_metrics is the raw per-poll snapshot the value is fetched
-- alongside (see CreatorMetric's append-only javadoc), so it belongs here first and rolls up
-- into platform_stats.handle via PlatformStatsAggregationJob same as followers/engagement.
ALTER TABLE creator_metrics
    ADD COLUMN username VARCHAR(200) NULL AFTER platform;
