-- CR-119 — make `creator_metrics.data_source` fail CLOSED at the DB layer, matching the Java rule.
--
-- WHY
-- ---
-- `data_source` stopped being a descriptive label and became a trust input: PlatformStat.verified
-- is now derived from it (CreatorMetric#isPlatformVerified), and that flag is rendered to brands
-- as "Verified" next to a follower count they are about to spend money against.
--
-- CreatorMetric.Builder was changed to default a missing data_source to 'CREATOR_REPORTED' so that
-- "the caller forgot to say where this came from" can never resolve to "a platform confirmed it".
-- V21 left the column DEFAULT 'META_API', so the two layers disagreed: any writer that bypasses
-- the builder (a raw jdbcTemplate insert, a backfill, a future importer) would still silently mint
-- a platform-verified claim by omission. No such writer exists today — this closes the trap before
-- one is added, rather than after.
--
-- The V21 inline comment also listed the value vocabulary as "META_API, MANUAL, ESTIMATED", which
-- never included CREATOR_REPORTED; the real vocabulary is restated below.
--
-- Existing rows are deliberately NOT rewritten: every current row was written by MetricsPollingJob
-- or PortfolioService#syncPlatforms, both of which set 'META_API' explicitly, so they are correctly
-- labelled already. Changing a stored provenance value retroactively would be inventing history.
--
-- SCOPE: creator_metrics ONLY.
-- media_metrics carries an identically-named column and was initially included here "for
-- consistency" — that was wrong. MediaMetric.Builder still defaults to 'META_API' in Java, so
-- changing only the DB side would have created exactly the Java-vs-DB divergence this migration
-- exists to remove, just inverted. media_metrics.data_source also feeds no verified-flag
-- consumer, so there is no correctness pressure to touch it. Left alone deliberately; if it ever
-- gains a trust consumer, change the Java default and the column together in one migration.

ALTER TABLE creator_metrics
    MODIFY COLUMN data_source VARCHAR(20) NOT NULL DEFAULT 'CREATOR_REPORTED'
    COMMENT 'Provenance of this snapshot. META_API = fetched from a real platform API (may claim verified); CREATOR_REPORTED = self-declared, the fail-closed default.';
