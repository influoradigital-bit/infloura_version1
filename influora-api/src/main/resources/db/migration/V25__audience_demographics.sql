-- Wave B task B4 (wiki/tech/REMAINING_WORK_PLAN.md) -- audience demographics per creator, fetched
-- weekly by AudienceDemographicsJob from Meta's
-- GET /{ig-user-id}/insights?metric=audience_city,audience_country,audience_gender_age,audience_locale
-- endpoint (see InstagramInsightsClient#getAudienceDemographics /
-- integration.meta.dto.AudienceDemographicsResponse -- already existed as a transient response
-- shape with no persisted table until this migration).
--
-- [CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline as V21/V22/V23/V24: ordinary MySQL InnoDB table, DATETIME(6) UTC for
-- time-series columns (app sets via Instant.now(), no DB-side NOW() default), JSON (not JSONB),
-- no CREATE EXTENSION/hypertable syntax.
--
-- [PATTERN CHOICE -- matches CreatorScore, not an upsert-latest table] Meta's audience_* insights
-- are a periodic ("lifetime" period, recomputed at fetch time) snapshot, exactly like
-- CreatorScore's daily ScoreCalculationJob output -- not a running/mutable counter like
-- coupon_codes.usage_count. Rows here are therefore IMMUTABLE snapshots, one row per
-- AudienceDemographicsJob run per creator: no update/mutator method on the entity, no UNIQUE
-- constraint forcing one row per creator. The "current" value for the brand-facing endpoint is
-- simply the most recent row by (creator_profile_id, time DESC), same finder shape as
-- CreatorScoreRepository#findFirstByCreatorProfileIdOrderByTimeDesc. This also means a broken/
-- botched fetch never destroys the prior snapshot -- the old row just remains the "latest" until a
-- good fetch supersedes it.
--
-- [JSON BUCKET SHAPE] Meta's response groups each demographic dimension (audience_city,
-- audience_country, audience_gender_age, audience_locale) into its own named breakdown, each with a
-- single value map (e.g. {"US": 1200, "GB": 300, ...} for audience_country, or
-- {"F.25-34": 400, "M.18-24": 250, ...} for audience_gender_age). Rather than a rigid relational
-- shape (which would need per-bucket-value rows and doesn't match how the data is consumed --
-- always "give me the whole breakdown for this dimension"), each dimension is stored as its own
-- JSON column holding the raw {bucket_key: count} map, matching this codebase's existing
-- convention of storing variable-shape breakdowns as JSON (e.g. CreatorScore.fake_follower_reasons,
-- CreatorScore.garm_flags, MetaOAuthToken.granted_scopes -- see JsonLists helper). age_gender is
-- kept as a single combined map (Meta's own audience_gender_age metric is already combined,
-- e.g. "F.25-34") rather than splitting it into separate age/gender columns that Meta doesn't
-- actually provide split.
CREATE TABLE audience_demographics (
  id                          VARCHAR(26) PRIMARY KEY,                 -- ULID
  time                        DATETIME(6) NOT NULL,                    -- UTC; when this snapshot was fetched
  creator_profile_id          VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id)
  platform                    VARCHAR(20) NOT NULL,                    -- INSTAGRAM (only platform fetched today)

  age_gender_breakdown        JSON NULL,                                -- {"F.25-34": 400, "M.18-24": 250, ...} (audience_gender_age)
  country_breakdown           JSON NULL,                                -- {"US": 1200, "GB": 300, ...} (audience_country)
  city_breakdown              JSON NULL,                                -- {"New York, NY": 210, ...} (audience_city)
  locale_breakdown             JSON NULL,                                -- {"en_US": 900, ...} (audience_locale)

  data_source                 VARCHAR(20) NOT NULL DEFAULT 'META_API', -- matches creator_metrics/media_metrics convention
  fetched_at                  DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  INDEX idx_audience_demographics_creator_time (creator_profile_id, time),
  CONSTRAINT fk_audience_demographics_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
