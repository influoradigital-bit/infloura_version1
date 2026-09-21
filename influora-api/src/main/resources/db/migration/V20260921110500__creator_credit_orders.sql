-- T-CREATOR-CREDITS-V2 B1 — a Razorpay checkout order for a creator credit pack. Mirrors
-- wallet_topups: a PENDING row is created alongside a Razorpay order, and ONLY confirmPaid (a
-- signature-verified webhook, the client-driven /verify endpoint after a gateway fetch, or the
-- reconciliation job — never the client's order-creation response) can move it to CREDITED.
--
-- razorpay_payment_id UNIQUE (K-10): the same captured payment can never credit two orders, even
-- if the webhook, /verify and the reconciliation job all race for the same order.
--
-- idempotency_key + UNIQUE(creator_user_id, idempotency_key): a double-tap "Buy" replays the SAME
-- PENDING order rather than minting a second Razorpay order (K-26: capped at 64 chars).
--
-- Invoice columns (K-25/C18, Rohan's shape): a statutory invoice number + the GST breakup are
-- stored on the order at confirmPaid time, best-effort, never blocking the credit.
CREATE TABLE creator_credit_orders (
  id                       VARCHAR(26) NOT NULL PRIMARY KEY,
  creator_user_id          VARCHAR(26) NOT NULL,
  pack_id                  VARCHAR(26) NOT NULL,
  pack_code                VARCHAR(32) NOT NULL,
  credits                  INT NOT NULL,
  amount_paise             INT NOT NULL,
  currency                 VARCHAR(3) NOT NULL DEFAULT 'INR',
  status                   VARCHAR(12) NOT NULL,
  razorpay_order_id        VARCHAR(64) NULL,
  razorpay_payment_id      VARCHAR(64) NULL,
  idempotency_key          VARCHAR(64) NOT NULL,
  grant_id                 VARCHAR(26) NULL,
  paid_at                  DATETIME(6) NULL,
  credited_at              DATETIME(6) NULL,
  invoice_number           VARCHAR(32) NULL,
  taxable_paise            INT NULL,
  cgst_paise                INT NULL,
  sgst_paise                INT NULL,
  igst_paise                INT NULL,
  hsn_sac_code              VARCHAR(10) NULL,
  place_of_supply_state     VARCHAR(2) NULL,
  created_at                DATETIME(6) NOT NULL,
  updated_at                DATETIME(6) NOT NULL,
  CONSTRAINT fk_cco_account FOREIGN KEY (creator_user_id) REFERENCES creator_credit_accounts(creator_user_id),
  CONSTRAINT fk_cco_pack FOREIGN KEY (pack_id) REFERENCES creator_credit_packs(id),
  CONSTRAINT fk_cco_grant FOREIGN KEY (grant_id) REFERENCES creator_credit_grants(id),
  UNIQUE KEY uk_cco_idem (creator_user_id, idempotency_key),
  UNIQUE KEY uk_cco_rzp_order (razorpay_order_id),
  UNIQUE KEY uk_cco_rzp_payment (razorpay_payment_id),
  KEY idx_cco_user_time (creator_user_id, created_at),
  KEY idx_cco_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- CREATOR_CREDITS HSN/SAC placeholder (Rohan build-flag #4 — never hardcode a tax code), same
-- discipline and same PENDING-CA-CONFIRMATION labelling as V20260715160000's SUBSCRIPTION row.
-- SAC 998599 (support services n.e.c. / marketplace-adjacent) is a placeholder for a prepaid
-- creator-credit top-up; CA sign-off is Q6 in SPEC.md and gates the flag flip, not the build.
INSERT INTO hsn_sac_codes (id, code_type, code, description, applies_to, created_at)
VALUES ('01J0D14HSC000000000000CC01', 'SAC', '998599', 'Creator AI credits top-up — PENDING CA CONFIRMATION', 'CREATOR_CREDITS', NOW());
