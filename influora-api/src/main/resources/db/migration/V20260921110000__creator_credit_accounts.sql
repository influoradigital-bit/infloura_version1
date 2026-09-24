-- T-CREATOR-CREDITS-V2 (SPEC.md 2026-09-21, Priya) B1 — the lock anchor row.
--
-- Every credit mutation (charge/release/grant/monthly-roll/welcome) takes a
-- SELECT ... FOR UPDATE on this row (CreatorCreditAccountRepository#findByIdForUpdate) before it
-- touches any grant or ledger row for that creator — the same "one lock anchor per tenant"
-- discipline WalletTopUp/EscrowHold already use. Distinct from the old feat/creator-credits-search
-- branch's `creator_ai_credits` (a balance-holding row) — this table holds NO balance; it holds
-- only lock/roll bookkeeping. The balance lives in creator_credit_grants (one row per lot, so each
-- purchase can carry its own 90-day expiry — see design-priya C1/Kabir K-19).
--
-- welcome_granted_at: set once, permanently, the first time the 40-credit signup grant lands
-- (grantWelcome). monthly_period ('YYYY-MM', IST) and daily_date/daily_used: lazily materialised
-- roll-forward markers, touched only under this row's own lock (CreatorCreditService#charge steps
-- 3-4). No FK-cascade concern: users.id is VARCHAR(26) (V2__core_auth.sql), matching here.
CREATE TABLE creator_credit_accounts (
  creator_user_id      VARCHAR(26) NOT NULL PRIMARY KEY,
  welcome_granted_at    DATETIME(6) NULL,
  monthly_period        VARCHAR(7) NULL,
  daily_date            DATE NULL,
  daily_used            INT NOT NULL DEFAULT 0,
  created_at            DATETIME(6) NOT NULL,
  updated_at            DATETIME(6) NOT NULL,
  CONSTRAINT fk_creator_credit_accounts_user FOREIGN KEY (creator_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
