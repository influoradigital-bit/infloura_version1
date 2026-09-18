-- T-S3-F0879-0917 REPAIR ROUND [vikram, 2026-09-18] -- F-0881/F-0883/F-0884/F-0885, per Swapnil's
-- ruling (.proof-os/tasks/T-S3-F0879-0917/RULING-upgrade-grant.md).
--
-- Adds the two billing-PERIOD markers (never the calendar month -- brand_ai_credits.cycle_start /
-- last_reset already own that) that AICreditService needs to make the two money-path repairs in
-- this round safe against webhook retries/redeliveries and duplicate scheduler triggers, without
-- touching Subscription.java or SubscriptionService.java (out of scope for this lane):
--
--   credit_grant_period_end -- the subscription's current_period_end (Subscription.java:46-49)
--     that AICreditService#applyPlanAllotment's last allotment-INCREASE grant was applied for.
--     BrandAiCreditRepository#grantAllotmentIncrease's atomic UPDATE guards on this column so a
--     repeat PAST_DUE->ACTIVE flap inside the SAME billing period (which re-syncs the SAME plan
--     allotment on every reactivation) grants the full new allowance at most once per period,
--     closing F-0883 ("granted=300 three times" probe) under the ruling's "SET to the full new
--     allotment" rule (F-0881), which would otherwise make repeat grants worse, not better.
--
--   last_reset_period_end -- the current_period_end AICreditService#resetForNewCycle's last
--     reset was applied for. Closes F-0884: SubscriptionService#applyRenewalSafetyNet calls
--     resetForNewCycle directly on a per-subscription renewal boundary that does not align to the
--     calendar month, so resetForNewCycleIfDue's separate same-UTC-month guard cannot see that
--     call at all. A missed renewal webhook already means the safety net's own mid-month reset
--     and AICreditResetJob's next 1st-of-month run (a different calendar month) could otherwise
--     both refill the same still-current billing period. This column lets resetForNewCycle guard
--     on the billing period itself, independent of the calendar-month guard.
--
-- Both are nullable TIMESTAMPs (mirrors unlimited_until/first_campaign_at above): NULL for any
-- workspace with no resolvable Subscription row (e.g. a Free workspace that has never touched
-- billing), which both call sites treat as "guard disabled" -- deliberately fails OPEN (grants/
-- resets proceed) rather than silently withholding a workspace's allowance when the billing
-- period can't be determined. Nullable ADD COLUMN with no backfill needed: every existing row's
-- guard starts disabled (NULL) and is populated the next time either write path actually runs for
-- that workspace, which is safe -- at worst the very next call is treated as "not yet granted/
-- reset for this period" once, which is the correct behavior for a column that has never been set.
--
-- Next free Flyway slot after V20260918140000__convert_agency_workspaces_to_brand.sql (the highest migration on disk at commit time).

ALTER TABLE brand_ai_credits
  ADD COLUMN credit_grant_period_end TIMESTAMP NULL,
  ADD COLUMN last_reset_period_end   TIMESTAMP NULL;
