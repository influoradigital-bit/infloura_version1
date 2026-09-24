-- A creator profile can connect a DIFFERENT Instagram account at any time, and nothing recorded
-- which account a metric row came from. Observed live 2026-09-24 on creator
-- 01M11V2C3B4AZW5T7QK0AK3RVW: 57 snapshots from @sage_digitalworld, 20 from @snapsby_ugc and 8
-- from @influora.io, all under one profile. Every read is "all rows for this profile", so Meera's
-- recent posts, her quality score, the 90-day posting pattern behind "best time to post", and the
-- brand-facing Content Performance panel were built from three different accounts at once, while
-- the follower count on top came from the newest one.
--
-- creator_metrics carries `username`, so its rows can at least be told apart after the fact.
-- media_metrics carries nothing but a post id: a post could not be attributed to an account at
-- all. This adds the account id to both, written on every poll from the token row's
-- ig_business_account_id (the same id MetricsPollingJob already throttles on).
--
-- Deliberately NULLable, and deliberately NOT backfilled here: existing rows cannot be attributed
-- retroactively, and readers treat a NULL as "unknown, still show it" so no creator loses their
-- history the moment this ships. Rows written from now on are tagged, and the one creator with
-- genuinely mixed data is cleaned up separately, with a backup, as its own change.
ALTER TABLE media_metrics   ADD COLUMN ig_account_id VARCHAR(50) NULL;
ALTER TABLE creator_metrics ADD COLUMN ig_account_id VARCHAR(50) NULL;

-- Reads are always creator-scoped first, then narrowed by account and ordered by time.
CREATE INDEX idx_media_metrics_profile_account_time
    ON media_metrics (creator_profile_id, ig_account_id, time);
CREATE INDEX idx_creator_metrics_profile_account_time
    ON creator_metrics (creator_profile_id, ig_account_id, time);
