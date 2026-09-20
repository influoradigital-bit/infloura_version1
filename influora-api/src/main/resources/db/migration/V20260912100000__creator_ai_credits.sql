-- T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.1.
--
-- Per-creator credit balance row, keyed on users.id (R1: the creator Meera path keys on the
-- creator's own user id, not creator_profiles.id -- see CREDITS-SPEC §1 R1). Two buckets:
-- monthly_remaining resets to monthly_allotment on the 1st (NOT cumulative); purchased_balance
-- never expires and holds packs, the signup grant, and admin grants (R2).
--
-- AMENDMENT A1 (Priya, 2026-09-20): monthly_remaining, monthly_allotment and purchased_balance
-- are INT and store TENTHS of a credit -- 40.0 credits is stored as 400, 2.5 credits as 25
-- (CREDITS-SPEC §0 rule 11). A new creator's row is monthly_remaining = 400, monthly_allotment =
-- 400, purchased_balance = 300 (the 30.0-credit signup grant). daily_actions_used is NOT tenths
-- -- it counts actions, mirroring the brand R7 abuse guard.
--
-- AMENDMENT A4 (Priya, 2026-09-20): free_searches_used / free_search_week_start are the weekly
-- free-search counter (CREDITS-SPEC §4A.3), same used-count-plus-period shape as
-- daily_actions_used / daily_actions_date two lines above (itself the brand shape from
-- V16__daily_action_cap.sql). free_search_week_start is NULL on a fresh row and that NULL is
-- load-bearing for CreatorAiCreditRepository#tryClaimFreeSearch's atomic claim query -- see that
-- method's javadoc.
--
-- users.id is VARCHAR(26) (V2__core_auth.sql L4, verified in this worktree), matching the FK here.
CREATE TABLE creator_ai_credits (
  creator_user_id         VARCHAR(26) NOT NULL PRIMARY KEY,
  monthly_remaining       INT NOT NULL DEFAULT 0,
  monthly_allotment       INT NOT NULL DEFAULT 0,
  purchased_balance       INT NOT NULL DEFAULT 0,
  cycle_start             DATE NOT NULL,
  last_reset              DATE NOT NULL,
  signup_grant_at         DATETIME(6) NULL,
  daily_actions_used      INT NOT NULL DEFAULT 0,
  daily_actions_date      DATE NULL,
  created_at              DATETIME(6) NOT NULL,
  updated_at              DATETIME(6) NOT NULL,
  free_searches_used      INT NOT NULL DEFAULT 0,
  free_search_week_start  DATE NULL,
  CONSTRAINT fk_creator_ai_credits_user FOREIGN KEY (creator_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
