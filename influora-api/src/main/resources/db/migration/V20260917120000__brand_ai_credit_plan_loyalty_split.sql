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
-- whether first_campaign_at was ever set AND the stored monthly_allotment could actually have
-- included the 50-point bonus (see REPAIR ROUND note below), plan_allotment is back-derived as the
-- exact remainder (no flooring), and the final monthly_allotment rewrite is exactly
-- plan_allotment + loyalty_bonus -- i.e. algebraically identical to the value already stored, so
-- no row's current allowance can go up. It does NOT retroactively repair a historical case where
-- an old Pro brand's monthly_allotment was already clobbered down to 150 by the pre-fix
-- applyEscrowFundedReset (that brand's plan_allotment backfills to 100, not 400) -- fixing stale
-- historical data is a separate reconciliation concern, not something a migration that must never
-- raise anyone's current allowance can safely do.
--
-- REPAIR ROUND [vikram, 2026-09-17] -- kabir (LOW, live MySQL 8.0.40 probe) proved the original
-- unconditional "loyalty_bonus = 50 iff first_campaign_at IS NOT NULL" backfill RAISES
-- monthly_allotment whenever the stored value is < 50 (30->50, 0->50, 49->50) or negative
-- (-5->0), because GREATEST(monthly_allotment - loyalty_bonus, 0) floors plan_allotment up to 0
-- while loyalty_bonus is still forced to 50, so plan_allotment + loyalty_bonus = 50 > the
-- original row. The app cannot currently produce such a row (seeded plans are 100/400, builder
-- default is 100, no admin path writes plans.ai_monthly_allotment below 50) but the migration's
-- own invariant must hold unconditionally, not just for reachable app states. Fix: only credit the
-- loyalty bonus when the stored value could actually contain it (>= 50); otherwise the whole value
-- is plan_allotment and loyalty_bonus is 0. plan_allotment is then the exact, unfloored remainder
-- -- a negative monthly_allotment backfills to a negative plan_allotment (still algebraically
-- reconstructing the original, non-raising, out-of-range value) rather than being silently
-- clamped up to 0.
--   Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §6 F-3, §7 SM-0.2
-- Next free Flyway slot after V20260912120000__backfill_free_subscriptions.sql.

ALTER TABLE brand_ai_credits
  ADD COLUMN plan_allotment INT NOT NULL DEFAULT 100 AFTER monthly_allotment,
  ADD COLUMN loyalty_bonus  INT NOT NULL DEFAULT 0    AFTER plan_allotment;

-- loyalty_bonus: 50 iff the workspace ever recorded a first funded campaign AND the stored
-- monthly_allotment is large enough to actually contain that bonus (>= 50). Below that threshold
-- crediting the bonus would force plan_allotment up to 0 and raise monthly_allotment above its
-- current value (see REPAIR ROUND note above), which this migration must never do.
UPDATE brand_ai_credits
SET loyalty_bonus = CASE
  WHEN first_campaign_at IS NOT NULL AND monthly_allotment >= 50 THEN 50
  ELSE 0
END;

-- plan_allotment: the exact remainder of the existing monthly_allotment after removing the
-- loyalty component just computed -- deliberately NOT floored at 0. loyalty_bonus is only ever 50
-- when monthly_allotment >= 50 (see above), so this is never negative on any row the app can
-- actually produce; on a corrupt/out-of-range row (loyalty_bonus = 0 here) it reconstructs
-- monthly_allotment unchanged rather than clamping it up.
UPDATE brand_ai_credits
SET plan_allotment = monthly_allotment - loyalty_bonus;

-- monthly_allotment: rewritten as plan_allotment + loyalty_bonus so the stored column matches
-- what the application layer will compute from this point on. Algebraically a no-op versus the
-- pre-migration value in every case -- never raises, and never lowers, any row's current
-- allowance.
UPDATE brand_ai_credits
SET monthly_allotment = plan_allotment + loyalty_bonus;
