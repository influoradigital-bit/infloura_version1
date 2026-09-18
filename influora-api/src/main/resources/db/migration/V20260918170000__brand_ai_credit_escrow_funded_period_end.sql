-- T-CREDITCLOCK-0918 repair round [vikram, 2026-09-18] -- wiki/decisions/2026-09-18-ai-credit-clock.md
-- and Swapnil's same-day follow-up ruling that a funded launch (AICreditService
-- #applyEscrowFundedReset) refills AT MOST ONCE PER BILLING PERIOD, "like upgrades".
-- Next free Flyway slot after V20260918160000__backfill_credit_grant_period_end.sql (the highest
-- migration on disk at commit time).
--
-- A SEPARATE marker from credit_grant_period_end on purpose: the clock decision doc's scenario 9
-- ("a funded launch on Pro -> 450 without touching credit_grant_period_end") still holds, and this
-- new guard must not suppress (or be suppressed by) the billing-refill primitive's own
-- once-per-period grant on credit_grant_period_end -- see
-- BrandAiCreditRepository#applyEscrowFundedResetOncePerPeriod.
--
-- NULL for every existing row: a workspace that has already funded a launch under the OLD
-- unconditional behavior has no prior "period it was funded through" to backfill -- its next
-- funded launch (in whatever period is current then) is correctly treated as the first one this
-- guard has ever seen for that workspace, which matches "at most once per period from here
-- forward", not a retroactive claim about periods before this column existed.
ALTER TABLE brand_ai_credits
    ADD COLUMN escrow_funded_period_end TIMESTAMP NULL;
