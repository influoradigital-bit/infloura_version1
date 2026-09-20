-- T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.4.
--
-- Mirrors wallet_topups (WalletTopUp entity) column for column where the concept matches: a
-- PENDING row is created alongside a Razorpay order, and only a signature-verified webhook flips
-- it to CREDITED (R6 -- credits are not money and never enter wallet_transactions).
--
-- AMENDMENT A1 (Priya, 2026-09-20): the `credits` snapshot is TENTHS (§0 rule 11), copied
-- verbatim from creator_credit_packs.credits at order time -- a Starter order stores 500.
-- amount_paise is unchanged.
--
-- Snapshots (pack_code, credits, amount_paise) so a later catalogue price change never changes
-- what an old order credits.
CREATE TABLE creator_credit_orders (
  id                   VARCHAR(26) NOT NULL PRIMARY KEY,
  creator_user_id      VARCHAR(26) NOT NULL,
  pack_id              VARCHAR(26) NOT NULL,
  pack_code            VARCHAR(20) NOT NULL,      -- snapshot
  credits              INT NOT NULL,              -- snapshot, TENTHS
  amount_paise         INT NOT NULL,              -- snapshot
  currency             VARCHAR(3) NOT NULL DEFAULT 'INR',
  status               VARCHAR(12) NOT NULL,      -- PENDING | CREDITED
  razorpay_order_id    VARCHAR(64) NULL,
  razorpay_payment_id  VARCHAR(64) NULL,
  idempotency_key      VARCHAR(64) NOT NULL,
  credited_at          DATETIME(6) NULL,
  created_at           DATETIME(6) NOT NULL,
  updated_at           DATETIME(6) NOT NULL,
  CONSTRAINT fk_creator_credit_orders_user FOREIGN KEY (creator_user_id) REFERENCES users(id),
  CONSTRAINT fk_creator_credit_orders_pack FOREIGN KEY (pack_id) REFERENCES creator_credit_packs(id),
  UNIQUE KEY uk_creator_credit_orders_idem (creator_user_id, idempotency_key),
  UNIQUE KEY uk_creator_credit_orders_rzp (razorpay_order_id),
  KEY idx_creator_credit_orders_user_time (creator_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
