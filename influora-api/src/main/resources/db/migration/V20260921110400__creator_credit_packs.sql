-- T-CREATOR-CREDITS-V2 B1 — the creator credit catalogue. One paid pack for v1 (owner ruling R2):
-- 60 credits for Rs 249, GST-inclusive. Whole credits only (no tenths — deviates deliberately from
-- feat/creator-credits-search's TENTHS convention, which this branch does not reuse; SPEC.md §4).
CREATE TABLE creator_credit_packs (
  id            VARCHAR(26) NOT NULL PRIMARY KEY,
  code          VARCHAR(32) NOT NULL,
  credits       INT NOT NULL,
  price_paise   INT NOT NULL,
  gst_inclusive BOOLEAN NOT NULL DEFAULT TRUE,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order    INT NOT NULL DEFAULT 0,
  created_at    DATETIME(6) NOT NULL,
  updated_at    DATETIME(6) NOT NULL,
  UNIQUE KEY uk_ccp_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Exactly one active pack (A35): 60 credits, 24900 paise (Rs 249.00) = 21102 taxable + 3798 GST.
INSERT INTO creator_credit_packs (id, code, credits, price_paise, gst_inclusive, active, sort_order, created_at, updated_at)
VALUES ('J9TRDBKH8AZXX3QRQFPVQ9QKKX', 'PACK_60', 60, 24900, TRUE, TRUE, 1, NOW(6), NOW(6));
