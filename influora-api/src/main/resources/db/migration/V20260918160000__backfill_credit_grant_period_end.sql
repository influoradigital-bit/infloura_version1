-- T-CREDITCLOCK-0918 [vikram, 2026-09-18] -- wiki/decisions/2026-09-18-ai-credit-clock.md §4
-- "Backfill". Next free Flyway slot after V20260918150000__brand_ai_credit_grant_period.sql (the
-- highest migration on disk at commit time).
--
-- credit_grant_period_end is now the single "billing period last filled" marker that
-- AICreditService#refillForBillingPeriod's atomic UPDATE guards on (see
-- BrandAiCreditRepository#refillForBillingPeriod). Every workspace already on the BILLING_PERIOD
-- clock (a paid, non-comp Subscription row that is ACTIVE or PAST_DUE -- see
-- SubscriptionService#creditClockFor) whose marker is still NULL has never been through this
-- build's refill path yet. Without this backfill, that row's guard hits its own IS-NULL branch and
-- receives one EXTRA refill the next time its subscription.charged webhook (or the renewal safety
-- net) fires -- exactly the double-refill this whole ruling exists to close (F-0896), just shifted
-- from "twice on day 1" to "once extra on the next webhook".
--
-- Sets the marker to the subscription's OWN current_period_end, not today's date -- that is what
-- the guard compares against on the row's NEXT billing-period event, so backfilling it to the
-- CURRENT period means that event (if it carries the SAME period, e.g. a redelivered `activated`)
-- correctly no-ops, and a genuinely NEW period (the next `charged`) correctly refills.
--
-- Idempotent and additive only: `credit_grant_period_end IS NULL` scopes this to rows that have
-- never had the marker set (a second run of this migration, or a workspace this build's own code
-- has already refilled, matches zero rows). No existing credits_remaining/monthly_allotment value
-- is touched.
UPDATE brand_ai_credits bac
INNER JOIN subscriptions s ON s.workspace_id = bac.workspace_id
INNER JOIN plans p ON p.id = s.plan_id
SET bac.credit_grant_period_end = s.current_period_end
WHERE bac.credit_grant_period_end IS NULL
  AND s.is_comp = FALSE
  AND p.code = 'PRO'
  AND s.status IN ('ACTIVE', 'PAST_DUE');
