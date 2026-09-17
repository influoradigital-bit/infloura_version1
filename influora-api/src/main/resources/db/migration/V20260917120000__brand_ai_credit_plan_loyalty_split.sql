-- F-3 / F-0836 [vikram, 2026-09-17] -- brand_ai_credits.monthly_allotment was written directly by
-- TWO owners: the plan sync (AICreditService#applyPlanAllotment, e.g. Pro = 400) and the loyalty
-- bonus (AICreditService#applyEscrowFundedReset, which overwrote the whole column with a flat 150
-- on first funded campaign). Whichever wrote last clobbered the other -- a Pro brand's first
-- funded campaign silently dropped them from 400 to 150. Per Swapnil's ruling (SM-0.2), the
-- loyalty bonus STACKS on the plan allotment instead: Free + funded = 150, Pro + funded = 450.
--
-- This migration splits the column into plan_allotment (plan sync only) and loyalty_bonus (earned
-- once, sticky); monthly_allotment stays as a real mapped column (BrandAiCreditRepository#refundCredits
-- pins a JPQL clamp against c.monthlyAllotment -- see BrandAiCreditRepositoryQueryTest -- so it
-- cannot become @Transient) but is now kept in sync by the application layer
-- (BrandAiCredit#recomputeMonthlyAllotment) instead of being an independently-writable value.
--
-- Backfill is deliberately conservative and invariant-preserving: loyalty_bonus is inferred from
-- whether first_campaign_at was ever set (the only signal the old single-column write-up left
-- behind), plan_allotment is back-derived as the remainder, and the final monthly_allotment
-- rewrite is exactly plan_allotment + loyalty_bonus -- i.e. algebraically identical to the value
-- already stored, so no row's current allowance can go up. It does NOT retroactively repair a
-- historical case where an old Pro brand's monthly_allotment was already clobbered down to 150 by
-- the pre-fix applyEscrowFundedReset (that brand's plan_allotment backfills to 100, not 400) --
-- fixing stale historical data is a separate reconciliation concern, not something a migration
-- that must never raise anyone's current allowance can safely do.
--
-- Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, §7 SM-0.2
-- Next free Flyway slot after V20260912120000__backfill_free_subscriptions.sql.

ALTER TABLE brand_ai_credits
  ADD COLUMN plan_allotment INT NOT NULL DEFAULT 100 AFTER monthly_allotment,
  ADD COLUMN loyalty_bonus  INT NOT NULL DEFAULT 0    AFTER plan_allotment;

-- loyalty_bonus: 50 iff the workspace ever recorded a first funded campaign, else 0. Matches the
-- only condition the old code gated the loyalty write on (BrandAiCredit.firstCampaignAt != null).
UPDATE brand_ai_credits
SET loyalty_bonus = CASE WHEN first_campaign_at IS NOT NULL THEN 50 ELSE 0 END;

-- plan_allotment: back-derived as the remainder of the existing monthly_allotment after removing
-- the loyalty component just computed. GREATEST(..., 0) guards the theoretical case of corrupt
-- pre-existing data where monthly_allotment < loyalty_bonus, so this can never go negative.
UPDATE brand_ai_credits
SET plan_allotment = GREATEST(monthly_allotment - loyalty_bonus, 0);

-- monthly_allotment: rewritten as plan_allotment + loyalty_bonus so the stored column matches
-- what the application layer will compute from this point on. Algebraically a no-op versus the
-- pre-migration value except in the GREATEST(...) floor edge case above, where it can only ever
-- decrease -- never raises any row's current allowance.
UPDATE brand_ai_credits
SET monthly_allotment = plan_allotment + loyalty_bonus;
