-- Account-level Instagram insights per creator (2026-09-24): how many accounts the creator reached,
-- views, interactions, accounts engaged and profile-link taps over the last 28 full days, from
-- GET /{ig-user-id}/insights?metric=reach,views,total_interactions,accounts_engaged,profile_links_taps
--     &period=day&metric_type=total_value&since=..&until=..
-- (InstagramInsightsClient#getAccountInsights, fetched daily by AccountInsightsJob and once right
-- after a creator connects).
--
-- Same shape discipline as V25 audience_demographics: IMMUTABLE snapshots, one row per fetch, the
-- "current" value is the newest row by (creator_profile_id, fetched_at DESC), so a failed fetch
-- never erases the last good numbers. Each metric is NULL when Meta did not return it -- never 0.
--
-- Types match the entity exactly (BIGINT <-> Long, DATE <-> LocalDate, DATETIME(6) <-> Instant):
-- ddl-auto=validate on real MySQL rejects a mismatch that H2 lets through.
CREATE TABLE creator_account_insights (
  id                  VARCHAR(26) PRIMARY KEY,                 -- ULID
  creator_profile_id  VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id)
  platform            VARCHAR(20) NOT NULL,                    -- INSTAGRAM
  period_start        DATE        NOT NULL,                    -- first day covered (IST)
  period_end          DATE        NOT NULL,                    -- last day covered, inclusive (IST)

  reach               BIGINT NULL,                             -- accounts reached in the period
  views               BIGINT NULL,
  total_interactions  BIGINT NULL,
  accounts_engaged    BIGINT NULL,
  profile_links_taps  BIGINT NULL,

  data_source         VARCHAR(20) NOT NULL DEFAULT 'META_API',
  fetched_at          DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

  INDEX idx_creator_account_insights_creator_fetched (creator_profile_id, fetched_at),
  CONSTRAINT fk_creator_account_insights_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
