-- DPF-6: platform-verified analytics (fixes defect #2b — analytics were 100% self-typed).
-- Adds a source flag to deliverable_metrics so a row can be either:
--   CREATOR_REPORTED  -- unchanged legacy behavior, self-declared by the creator
--   PLATFORM_VERIFIED -- fetched from the real Instagram/YouTube post via the platform API
-- Existing rows default to CREATOR_REPORTED (they were all self-reported before this migration —
-- never silently reclassified as verified). platform_media_id records which platform media object
-- the verified numbers came from (Instagram media id today; YouTube video id once that client
-- exists) for traceability/idempotency; verified_at is null until a verification actually lands.
ALTER TABLE deliverable_metrics
  ADD COLUMN source             VARCHAR(20) NOT NULL DEFAULT 'CREATOR_REPORTED' AFTER engagements,
  ADD COLUMN platform_media_id  VARCHAR(100) NULL AFTER source,
  ADD COLUMN verified_at        TIMESTAMP NULL AFTER platform_media_id;
