-- T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.3.
--
-- Catalogue lives in the DB by precedent (`plans` is seeded by
-- V55__seed_billing_plans.sql; there is no yml plan config). Prices are Tejas §3 (₹149 / ₹249 /
-- ₹649, GST-inclusive, what the creator pays -- price_paise). Ids are real ULIDs generated at
-- build time (Crockford base32, 26 chars), not the spec's illustrative placeholders.
--
-- AMENDMENT A1 (Priya, 2026-09-20): the seeded `credits` values are TENTHS (§0 rule 11) -- 500 /
-- 1000 / 3000, which the creator sees as 50 / 100 / 300. price_paise is unchanged: it was already
-- the smallest money unit and has nothing to do with the credit unit. Getting this row wrong
-- ships a pack that grants a tenth of what it sold, and ddl-auto=validate does not check seed
-- values -- CreatorCreditPackSeedTest asserts the three amounts directly against this table's
-- seeded rows.
CREATE TABLE creator_credit_packs (
  id            VARCHAR(26) NOT NULL PRIMARY KEY,
  code          VARCHAR(20) NOT NULL,
  name          VARCHAR(60) NOT NULL,
  credits       INT NOT NULL,          -- TENTHS (§0 rule 11): 500 = 50.0 credits, etc.
  price_paise   INT NOT NULL,          -- GST-inclusive, what the creator pays
  sort_order    INT NOT NULL DEFAULT 0,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    DATETIME(6) NOT NULL,
  updated_at    DATETIME(6) NOT NULL,
  UNIQUE KEY uk_creator_credit_packs_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO creator_credit_packs (id, code, name, credits, price_paise, sort_order, active, created_at, updated_at) VALUES
('01M2Z6C91ZM0FKX6NT642Z3YZ8', 'STARTER',  'Starter',   500, 14900, 1, TRUE, NOW(6), NOW(6)),
('01M2Z6C920PMRY1Q6VSJTYDJPG', 'STANDARD', 'Standard', 1000, 24900, 2, TRUE, NOW(6), NOW(6)),
('01M2Z6C920BTBJBCVV6FYDYV11', 'POWER',    'Power',    3000, 64900, 3, TRUE, NOW(6), NOW(6));
