-- T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.2.
--
-- The creator credits money record. The signed credit-amount column is named `delta`, NOT
-- `amount` -- CREDITS-SPEC's own §0/amendment notes flag this explicitly because PLAN.md §3 step 1
-- calls it "ledger amounts" and an engineer building from memory would reach for `amount`.
--
-- AMENDMENT A1 (Priya, 2026-09-20): delta, monthly_after and purchased_after are TENTHS (§0 rule
-- 11). A chat debit row is delta = -10, a search debit -25, the signup grant +300.
--
-- AMENDMENT A3 (Priya, 2026-09-20): a FREE_SEARCH row writes delta = 0 and every NOT NULL column
-- still needs a value: bucket = MONTHLY (it spends the free weekly allowance, the free tier;
-- CreditBucket has only MONTHLY/PURCHASED), monthly_after/purchased_after are the UNCHANGED
-- balances, and reference_id is the search's own fresh ULID (never '') so the unique key below
-- can never collide between two free searches. Getting bucket wrong on a FREE_SEARCH row is a
-- runtime NOT NULL failure on the first free search, not a compile or test failure -- see
-- CreatorCreditLedgerEntryTest.
--
-- Why the unique key (creator_user_id, reason, bucket, reference_id): SIGNUP_GRANT with
-- reference_id = '' can exist once per creator; MONTHLY_RESET with reference_id = '2026-10-01'
-- once per month; TURN_DEBIT with the turn id once per bucket. This is the structural
-- double-grant guard, same idea as affiliate_earnings.UNIQUE(redemption_id). bucket is in the key
-- because one turn can debit both buckets (two rows, monthly then purchased).
CREATE TABLE creator_credit_ledger (
  id                 VARCHAR(26) NOT NULL PRIMARY KEY,
  creator_user_id    VARCHAR(26) NOT NULL,
  delta              INT NOT NULL,                       -- signed; negative = debit. TENTHS.
  bucket             VARCHAR(12) NOT NULL,                -- MONTHLY | PURCHASED
  reason             VARCHAR(32) NOT NULL,                -- see CreditLedgerReason
  reference_id       VARCHAR(64) NOT NULL DEFAULT '',     -- turnId / briefId / orderId / cycle date; '' when none
  monthly_after      INT NOT NULL,                        -- TENTHS
  purchased_after    INT NOT NULL,                        -- TENTHS
  actor_id           VARCHAR(26) NOT NULL DEFAULT '',      -- admin user id for ADMIN_GRANT, else ''
  note               VARCHAR(255) NOT NULL DEFAULT '',
  created_at         DATETIME(6) NOT NULL,
  CONSTRAINT fk_creator_credit_ledger_user FOREIGN KEY (creator_user_id) REFERENCES users(id),
  UNIQUE KEY uk_creator_credit_ledger_ref (creator_user_id, reason, bucket, reference_id),
  KEY idx_creator_credit_ledger_user_time (creator_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
