-- Phase 2 Data Pipeline (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2) — creator/media time-series
-- metrics fetched from the Meta Graph API.
--
-- [CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED]
-- The spec's §2.2 schema targets TimescaleDB/PostgreSQL (TIMESTAMPTZ, create_hypertable(),
-- continuous aggregates). Priya's ADR rejects that for now: these are ordinary MySQL InnoDB
-- tables, NOT hypertables. `time` uses DATETIME(6) storing UTC (matches the existing
-- serverTimezone=UTC JDBC convention — see V19/V20's plain TIMESTAMP columns; DATETIME(6) is used
-- here instead of TIMESTAMP specifically for microsecond precision on high-frequency metric rows,
-- per the ADR's explicit instruction). The composite (creator_profile_id, time) and
-- (platform, time) indexes below are what a hypertable indexes on anyway, minus time-chunking that
-- is irrelevant at our current volume. No CREATE EXTENSION, no create_hypertable() — Postgres-only,
-- not used. Revisit per the ADR's trigger conditions (>5M rows in a metrics table, or a
-- continuous-aggregate/retention-compression requirement at launch scale).

-- Creator-level metrics (one row per poll per creator/platform).
CREATE TABLE creator_metrics (
  id                          VARCHAR(26) PRIMARY KEY,                 -- ULID
  time                        DATETIME(6) NOT NULL,                    -- UTC; when the metric snapshot applies
  creator_profile_id          VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id)
  platform                    VARCHAR(20) NOT NULL,                    -- INSTAGRAM, FACEBOOK, YOUTUBE
  followers                   BIGINT NOT NULL,
  following                   BIGINT NULL,
  media_count                 INT NULL,
  avg_engagement_rate         DECIMAL(8,4) NULL,                       -- percentage: 4.5200 = 4.52%
  avg_reach_per_post          BIGINT NULL,
  avg_impressions_per_post    BIGINT NULL,
  profile_views               BIGINT NULL,
  website_clicks               BIGINT NULL,
  data_source                 VARCHAR(20) NOT NULL DEFAULT 'META_API', -- META_API, MANUAL, ESTIMATED
  fetched_at                  DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now(), no DB-side NOW() default (matches DATETIME(6) precision)
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_creator_metrics_creator_time (creator_profile_id, time),
  INDEX idx_creator_metrics_platform_time (platform, time),
  CONSTRAINT fk_creator_metrics_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Media-level metrics (per-post analytics, one row per poll per media item).
CREATE TABLE media_metrics (
  id                          VARCHAR(26) PRIMARY KEY,                 -- ULID
  time                        DATETIME(6) NOT NULL,                    -- UTC; when the metric snapshot applies
  media_id                    VARCHAR(50) NOT NULL,                    -- Meta's media id (external, not FK)
  creator_profile_id          VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id)
  platform                    VARCHAR(20) NOT NULL,                    -- INSTAGRAM, FACEBOOK, YOUTUBE
  media_type                  VARCHAR(20) NOT NULL,                    -- IMAGE, VIDEO, CAROUSEL_ALBUM, REELS, STORY
  permalink                   VARCHAR(500) NULL,
  impressions                 BIGINT NULL,
  reach                       BIGINT NULL,
  engagement                  BIGINT NULL,                             -- likes + comments + saves + shares
  likes                       BIGINT NULL,
  comments                    BIGINT NULL,
  saves                       BIGINT NULL,
  shares                      BIGINT NULL,
  video_views                 BIGINT NULL,                             -- NULL for non-video
  avg_watch_time_seconds      DECIMAL(10,2) NULL,
  posted_at                   DATETIME(6) NULL,                        -- UTC; original post timestamp from Meta
  data_source                 VARCHAR(20) NOT NULL DEFAULT 'META_API',
  fetched_at                  DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_media_metrics_creator_time (creator_profile_id, time),
  INDEX idx_media_metrics_platform_time (platform, time),
  INDEX idx_media_metrics_media (media_id),
  CONSTRAINT fk_media_metrics_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
