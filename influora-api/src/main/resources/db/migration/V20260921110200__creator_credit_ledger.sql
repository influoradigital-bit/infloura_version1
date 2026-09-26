-- T-CREATOR-CREDITS-V2 B1 — the money record. One row per grant TOUCHED by a single charge/
-- release/grant event (a 3-credit brief spanning two grants writes TWO rows, same debit
-- reference_id, different grant_id — design-priya A2/Kabir K-19).
--
-- reference_id (K-30): NOT NULL, NO DEFAULT on purpose — see creator_credit_grants.source_ref for
-- the same reasoning. Formats: turn:<ULID> / tts:<ULID> / brief:<ULID> / m:YYYY-MM / signup /
-- order:<ULID> (SPEC.md 4 reference table), always server-minted, at most 64 chars.
--
-- uk_ccl_ref (creator_user_id, reason, reference_id, grant_id) is the structural exactly-once
-- guard: a retried charge for the SAME turn/brief/order against the SAME grant can never double
-- post, because the DB rejects the second insert — this is what makes CreatorCreditService#charge
-- step 6 ("already-charged?" check) a genuine belt-and-suspenders rather than the only guard.
CREATE TABLE creator_credit_ledger (
  id                 VARCHAR(26) NOT NULL PRIMARY KEY,
  creator_user_id    VARCHAR(26) NOT NULL,
  grant_id           VARCHAR(26) NOT NULL,
  reason             VARCHAR(20) NOT NULL,
  reference_id       VARCHAR(64) NOT NULL,
  delta              INT NOT NULL,
  balance_after      INT NOT NULL,
  ist_date           DATE NOT NULL,
  created_at         DATETIME(6) NOT NULL,
  CONSTRAINT fk_ccl_account FOREIGN KEY (creator_user_id) REFERENCES creator_credit_accounts(creator_user_id),
  CONSTRAINT fk_ccl_grant FOREIGN KEY (grant_id) REFERENCES creator_credit_grants(id),
  UNIQUE KEY uk_ccl_ref (creator_user_id, reason, reference_id, grant_id),
  KEY idx_ccl_user_ref (creator_user_id, reference_id),
  KEY idx_ccl_user_time (creator_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
