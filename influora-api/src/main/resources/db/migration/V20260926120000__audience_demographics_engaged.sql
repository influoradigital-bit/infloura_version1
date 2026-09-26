-- Engaged audience (Swapnil 2026-09-26): who ENGAGED with the creator's content this month, next
-- to who follows her. From GET /{ig-user-id}/insights?metric=engaged_audience_demographics
--     &period=lifetime&metric_type=total_value&timeframe=this_month&breakdown=age,gender|country|city
-- (InstagramInsightsClient#getEngagedAudienceDemographics), fetched by AudienceDemographicsJob in
-- the same weekly run and the same connect-triggered fetch as the follower breakdowns, and stored
-- on the same immutable snapshot row.
--
-- Meta: "Not returned if the IG User has less than 100 engagements during the timeframe." That is
-- a normal state, not an error, so each row records what happened in engaged_status:
--   AVAILABLE        the three engaged_* maps hold Meta's counts (any of them may still be NULL
--                    when Meta returned that one dimension empty)
--   BELOW_THRESHOLD  Meta returned nothing: fewer than 100 engagements this month
--   FETCH_FAILED     the engaged call failed (rate limit, expired token, API error); the follower
--                    breakdowns on the row are still good
--   NULL             a row written before this migration: never asked
-- The status is an app-layer code (VARCHAR, not DB ENUM), matching creator_challenges.status.
--
-- Creator-only: read by the creator's own GET /creator/analytics/me/demographics and by Meera's
-- get_my_audience / "Your audience" line. The brand-facing demographics route never maps these.
--
-- Types match the entity exactly (JSON <-> String with SqlTypes.JSON, VARCHAR(20) <-> String,
-- DATETIME(6) <-> Instant). All nullable, no default, so existing rows need no backfill.
ALTER TABLE audience_demographics
    ADD COLUMN engaged_age_gender_breakdown JSON        NULL,   -- {"18-24_female": 120, ...}, same keys as age_gender_breakdown
    ADD COLUMN engaged_country_breakdown    JSON        NULL,   -- {"IN": 300, ...}
    ADD COLUMN engaged_city_breakdown       JSON        NULL,   -- {"Mumbai, Maharashtra": 80, ...}
    ADD COLUMN engaged_status               VARCHAR(20) NULL,   -- AVAILABLE | BELOW_THRESHOLD | FETCH_FAILED
    ADD COLUMN engaged_fetched_at           DATETIME(6) NULL;   -- UTC; set only when engaged_status = AVAILABLE
