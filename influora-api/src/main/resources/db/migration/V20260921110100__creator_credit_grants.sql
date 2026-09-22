-- T-CREATOR-CREDITS-V2 B1 — one row per "lot" of credit (design-priya C1 / Kabir K-19: every
-- purchase carries its own 90-day expiry, and one balance column cannot hold two expiry dates).
--
-- bucket: FREE_SIGNUP (the once-ever 40, never expires) | FREE_MONTHLY (the 15/month, expires the
-- 1st of the following IST month, never carried over) | PAID (a Rs 249 pack, expires paidAt+90d) |
-- ADMIN (a manual grant, no expiry unless set).
--
-- source_ref (K-30): the caller-supplied idempotency key for THIS grant — 'signup' for the once
-- welcome grant, 'm:YYYY-MM' for a monthly grant, 'order:<ULID>' for a purchase. Deliberately
-- VARCHAR(64) NOT NULL with NO DEFAULT: an empty-string source_ref would let two distinct grants of
-- the same bucket silently collide (or silently NOT collide when they should), so a caller that
-- forgets to pass one fails loudly (a DB NOT NULL violation) instead of writing '' and defeating
-- uk_ccg_source below.
--
-- credits_remaining is spent DOWN in place (never re-derived from the ledger) under the parent
-- account's lock; the CHECK is defense-in-depth against a debit that outran the guard.
CREATE TABLE creator_credit_grants (
  id                   VARCHAR(26) NOT NULL PRIMARY KEY,
  creator_user_id      VARCHAR(26) NOT NULL,
  bucket               VARCHAR(20) NOT NULL,
  credits_granted      INT NOT NULL,
  credits_remaining    INT NOT NULL,
  granted_at           DATETIME(6) NOT NULL,
  expires_at           DATETIME(6) NULL,
  source_ref           VARCHAR(64) NOT NULL,
  CONSTRAINT fk_ccg_account FOREIGN KEY (creator_user_id) REFERENCES creator_credit_accounts(creator_user_id),
  CONSTRAINT ck_ccg_remaining_bounds CHECK (credits_remaining >= 0 AND credits_remaining <= credits_granted),
  UNIQUE KEY uk_ccg_source (creator_user_id, bucket, source_ref),
  KEY idx_ccg_spend (creator_user_id, credits_remaining, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
