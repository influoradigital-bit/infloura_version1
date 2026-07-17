-- P4: 500 actions/day hard cap on "unlimited while live" credits (20-ROHAN-COST-REVIEW.md section 5).
-- Adds daily action tracking to brand_ai_credits for abuse/runaway-loop prevention.
-- This is a safety net, NOT the primary billing mechanism; resets at midnight UTC.
ALTER TABLE brand_ai_credits
  ADD COLUMN daily_actions_used    INT NOT NULL DEFAULT 0,
  ADD COLUMN daily_actions_date    DATE NULL;
