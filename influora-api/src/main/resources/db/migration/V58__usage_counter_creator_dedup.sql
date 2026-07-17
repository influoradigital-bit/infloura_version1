-- Task 22 Flag #1 fix (Rohan CFO ruling, SHARED_CONTEXT.md 2026-07-14 "Flag #1 economics ruling
-- -- HOLD for per-creator dedup fix"): per-creator-lookup dedup for the CREATOR_ANALYTICS_VIEW
-- usage cap. Previously each of AnalyticsController's 4 brand-facing sub-endpoint calls
-- (metrics/scores/demographics/media) for the SAME creatorId counted as 4 separate "views"
-- against Free's 1/month allowance -- SUBSCRIPTION-BILLING-PLAN.md SS1.4/SS2 says "1
-- creator-analytics deep-dive/month", read as ONE creator lookup, not one API call.
--
-- This table lets UsageCounterService dedupe: one row per (workspace, metric, period, creatorId)
-- recorded the FIRST time a given creator is looked at in a billing period.
-- usage_counters.used only increments when a genuinely NEW creator is recorded for that period --
-- not on every sub-endpoint call for a creator already seen this period. Additive only -- no
-- change to the existing usage_counters table/rows/columns. Next free Flyway slot after V57
-- (highest existing before this pass). Owner: Vikram (Backend).

CREATE TABLE usage_counter_details (
  id CHAR(26) PRIMARY KEY,
  workspace_id VARCHAR(26) NOT NULL,
  metric VARCHAR(50) NOT NULL COMMENT 'Same enum as usage_counters.metric -- CREATOR_ANALYTICS_VIEW today, additional metrics may reuse this dedup mechanism later',
  period_start DATE NOT NULL COMMENT 'Billing cycle start date this dedup row applies to -- mirrors usage_counters.period_start',
  dedup_key VARCHAR(50) NOT NULL COMMENT 'The distinct entity being deduped within (workspace, metric, period) -- creatorId (ULID) for CREATOR_ANALYTICS_VIEW',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
  UNIQUE KEY uk_workspace_metric_period_dedup (workspace_id, metric, period_start, dedup_key) COMMENT 'Enforces exactly-once recording per distinct entity per period -- the concurrency-safety mechanism: two racing requests for the same creator can only insert one winning row, the loser gets a duplicate-key violation and is treated as already-recorded, no double increment of usage_counters.used',
  INDEX idx_workspace_metric_period (workspace_id, metric, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-(workspace,metric,period) distinct-entity dedup rows backing per-creator-lookup counting for usage_counters -- Task 22 Flag #1 fix';
