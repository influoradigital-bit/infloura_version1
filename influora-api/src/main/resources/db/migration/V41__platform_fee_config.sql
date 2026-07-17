-- Creator-side platform fee config (§1.1 CEO ruling: 15% default at escrow release).
-- Singleton row — admin-editable without redeploy (V1 scope: global default only).
--
-- RENAMED V40 -> V41 (Vikram): this file and V40__reviews.sql were both independently authored
-- as "V40__..." by a concurrent session, a genuine Flyway version collision (two migrations
-- can't share a version) that would have failed at boot. Neither had been applied to any
-- persistent DB yet (both created moments before the rename), so renaming was safe. Content
-- below is unchanged from the original V40__platform_fee_config.sql. See
-- V42__platform_fee_config_brand_fee_razorpay.sql for the CEO-directive fields (brand fee,
-- Razorpay-absorption flag) added on top of this table.
CREATE TABLE platform_fee_config (
  id                VARCHAR(26) PRIMARY KEY,
  default_fee_bps   INT NOT NULL,
  min_fee_bps       INT NOT NULL DEFAULT 0,
  max_fee_bps       INT NOT NULL DEFAULT 3000,
  allow_high_fee    BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by        VARCHAR(26) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO platform_fee_config (id, default_fee_bps, min_fee_bps, max_fee_bps, allow_high_fee, updated_at)
VALUES ('default', 1500, 0, 3000, FALSE, CURRENT_TIMESTAMP);
