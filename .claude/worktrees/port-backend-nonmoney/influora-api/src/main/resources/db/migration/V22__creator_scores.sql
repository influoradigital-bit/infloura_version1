-- Phase 3 Scoring Algorithms (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2.4 / §4) — computed creator
-- scores (fake-follower detection, quality, brand safety, rate estimation), one row per creator per
-- daily ScoreCalculationJob run.
--
-- [CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same ruling that
-- applied to V21 applies here too: the spec's §2.4 schema targets TimescaleDB/PostgreSQL
-- (TIMESTAMPTZ, JSONB, create_hypertable()). This is an ordinary MySQL InnoDB table, NOT a
-- hypertable. `time` uses DATETIME(6) storing UTC (matches V21's convention). JSON columns use
-- plain MySQL `JSON`, not JSONB. The composite (creator_profile_id, time) index below is what a
-- hypertable indexes on anyway, minus time-chunking that is irrelevant at our current volume. No
-- CREATE EXTENSION, no create_hypertable() — Postgres-only, not used.
--
-- [SCOPE CUT — see ScoreCalculationJob.java javadoc] `BrandSafetyScoreService` requires cross-repo
-- integration with influora-ai (a separate Python service) and is deliberately NOT built in this
-- pass — it is scoped as its own follow-up task. The 3 columns below it would populate
-- (brand_safety_score, garm_flags, content_sentiment) are included in this migration per the spec's
-- full column list, but are left NULLable with no NOT NULL constraint and are NOT written by
-- ScoreCalculationJob today. Whoever builds BrandSafetyScoreService next should start populating
-- exactly these 3 columns — no other schema change should be needed for that follow-up.
CREATE TABLE creator_scores (
  id                          VARCHAR(26) PRIMARY KEY,                 -- ULID
  time                        DATETIME(6) NOT NULL,                    -- UTC; when this score snapshot was computed
  creator_profile_id          VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id)

  -- Fake Follower Detection (FakeFollowerDetectionService)
  fake_follower_score         DECIMAL(5,2) NULL,                       -- 0-100, higher = more suspicious
  fake_follower_reasons       JSON NULL,                                -- ["sudden_spike", "low_engagement_ratio", ...]

  -- Quality Score (composite) (QualityScoreService)
  quality_score                DECIMAL(5,2) NULL,                       -- 0-100, higher = better
  engagement_consistency       DECIMAL(5,2) NULL,                       -- std dev of engagement over time
  posting_frequency            DECIMAL(5,2) NULL,                       -- posts per week, normalized
  audience_match_score         DECIMAL(5,2) NULL,                       -- how well audience matches typical brand targets

  -- Brand Safety Score (BrandSafetyScoreService — NOT YET BUILT, see scope-cut note above)
  brand_safety_score           DECIMAL(5,2) NULL,                       -- 0-100, higher = safer
  garm_flags                   JSON NULL,                                -- ["adult_content", "controversial_topics", ...]
  content_sentiment            DECIMAL(5,2) NULL,                       -- -1 to +1, average sentiment

  -- Rate Estimation (RateEstimationService)
  estimated_rate_min           DECIMAL(12,2) NULL,
  estimated_rate_max           DECIMAL(12,2) NULL,
  rate_currency                 VARCHAR(3) NOT NULL DEFAULT 'INR',
  rate_confidence               DECIMAL(5,2) NULL,                       -- 0-100

  -- Meta
  algorithm_version             VARCHAR(20) NOT NULL,                    -- e.g., "v1.0.0"
  computed_at                   DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now(), no DB-side NOW() default (matches DATETIME(6) precision)
  created_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_creator_scores_creator_time (creator_profile_id, time),
  INDEX idx_creator_scores_quality (quality_score DESC, time DESC),
  CONSTRAINT fk_creator_scores_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
