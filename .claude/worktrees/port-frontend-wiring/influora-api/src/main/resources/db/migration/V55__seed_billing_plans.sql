-- Seed Free + Pro plan rows into V54's `plans` table. Task 18 subscription-billing functional
-- core, Phase 1 (Vikram). Next free Flyway slot after V54__subscription_billing.sql.
--
-- Fixed ULID primary keys (not app-generated) so this migration is deterministic and idempotent
-- across environments — PlanService.getFreePlan()/getProPlan() resolve by `code`, never by id, so
-- the exact id value is only an internal FK target (subscriptions.plan_id).
--
-- price_inr is INR cents per V54's column comment (0 for Free, 499900 = ₹4,999.00 for Pro).
-- razorpay_plan_id is NULL for both — Free never gets one; Pro's is created one-time via the
-- Razorpay API in Phase 2 (Task 19) and backfilled with an UPDATE, not by this migration.
--
-- Numbers match the tier matrix in wiki/tech/SUBSCRIPTION-BILLING-PLAN.md §2 (Rohan's proposal),
-- still pending Swapnil's §6 sign-off on the price/fee/cap values themselves — the row *shape* is
-- stable regardless of whether those numbers change before launch (per §0.5 routing note: safe to
-- seed now, safe to UPDATE later without a schema change).

INSERT INTO plans (
  id, code, name, price_inr, billing_cycle, razorpay_plan_id, fee_bps,
  ai_monthly_allotment, seat_limit, tracked_creator_limit, creator_analytics_monthly_limit,
  export_enabled, campaign_templates_enabled, active, created_at, updated_at
) VALUES (
  '01KXEH2EK5A74SGTT2Q5SA0VCG', 'FREE', 'Free', 0, 'MONTHLY', NULL, 1000,
  100, 1, 5, 1,
  FALSE, FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO plans (
  id, code, name, price_inr, billing_cycle, razorpay_plan_id, fee_bps,
  ai_monthly_allotment, seat_limit, tracked_creator_limit, creator_analytics_monthly_limit,
  export_enabled, campaign_templates_enabled, active, created_at, updated_at
) VALUES (
  '01KXEH2EK73916TTXYV5ARZF2X', 'PRO', 'Pro', 499900, 'MONTHLY', NULL, 700,
  400, 5, NULL, NULL,
  TRUE, TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
