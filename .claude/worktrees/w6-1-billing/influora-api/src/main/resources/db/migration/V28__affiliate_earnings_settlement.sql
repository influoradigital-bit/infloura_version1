-- Wave D task D4 (wiki/tech/REMAINING_WORK_PLAN.md) -- affiliate revenue-share: per-creator
-- commission accrual on a qualifying coupon redemption, plus monthly settlement-batch tracking so
-- accrued PENDING earnings can be paid out in bulk without double-paying.
--
-- [CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline as V21-V26: DATETIME(6) UTC (app sets via Instant.now(), no DB-side
-- NOW()/CURRENT_TIMESTAMP default), DECIMAL for money, no partial indexes / hypertables.
--
-- Money-moving [SEC: Kabir load-bearing review required, Rohan cost review required] -- mirrors the
-- ALREADY-FIXED PayoutService/IdempotencyService pattern (E2 HIGH-1/CRITICAL findings): every
-- money-mutating write path here goes through the shared idempotency_keys table (V15) via
-- IdempotencyService.executeOnce, validated BEFORE the key is reserved, never inside it.
--
-- Two tables:
--   1. affiliate_earnings      -- one row per qualifying conversion (coupon redemption) a creator
--                                  earns commission on. NOT NULL UNIQUE(redemption_id) is the
--                                  structural guard against double-crediting the same redemption
--                                  twice (backstops IdempotencyService's own dedup, same
--                                  belt-and-suspenders discipline as coupon_redemptions.idempotency_key
--                                  backstopping RedemptionService).
--   2. affiliate_settlement_batches -- one row per monthly settlement run. affiliate_earnings rows
--                                  reference the batch that settled them (settlement_batch_id,
--                                  nullable until settled) rather than each earning carrying its own
--                                  ad-hoc "paid" boolean -- this makes "which batch paid this
--                                  earning" a real, auditable join, and lets a batch's own status
--                                  (IN_PROGRESS/COMPLETED/FAILED) be the single source of truth for
--                                  whether it is safe to retry.
--
-- Idempotency key columns: affiliate_earnings.idempotency_key is derived deterministically from
-- redemption_id (see AffiliateEarningsService) and is NOT NULL UNIQUE -- a structural,
-- database-enforced backstop, exactly like coupon_redemptions.idempotency_key. The settlement job's
-- own idempotency (one settlement attempt per creator per period) is arbitrated by the shared
-- idempotency_keys table (V15), NOT a new column here -- see AffiliateSettlementJob javadoc for why
-- the derived key is (creatorId, periodYearMonth), not (batchId, creatorId): a retried/duplicate
-- job run for the same period must collide with the PRIOR run's key, not mint a fresh one.
CREATE TABLE affiliate_settlement_batches (
  id                      VARCHAR(26) PRIMARY KEY,                 -- ULID
  period_year_month       VARCHAR(7) NOT NULL,                     -- e.g. '2026-07', the settlement period this batch covers
  status                  VARCHAR(20) NOT NULL,                    -- IN_PROGRESS | COMPLETED | FAILED
  total_creators          INT NOT NULL DEFAULT 0,
  total_amount            DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  started_at              DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  completed_at            DATETIME(6) NULL,                        -- UTC; set when status moves to COMPLETED/FAILED

  INDEX idx_settlement_batch_period (period_year_month),
  INDEX idx_settlement_batch_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE affiliate_earnings (
  id                      VARCHAR(26) PRIMARY KEY,                 -- ULID
  workspace_id            VARCHAR(26) NOT NULL,                    -- owning brand workspace (direct, no join needed -- mirrors coupon_codes.workspace_id)
  campaign_id             VARCHAR(26) NOT NULL,                    -- FK campaigns(id)
  creator_id              VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id) -- who earns this commission
  redemption_id           VARCHAR(26) NOT NULL,                    -- FK coupon_redemptions(id) -- the qualifying conversion this commission is derived from

  commission_amount       DECIMAL(12,2) NOT NULL,                  -- the creator's earned commission for this one conversion
  currency                VARCHAR(3) NOT NULL DEFAULT 'INR',

  status                  VARCHAR(20) NOT NULL,                    -- PENDING | SETTLED | FAILED
  settlement_batch_id     VARCHAR(26) NULL,                        -- FK affiliate_settlement_batches(id); set only once settled (or attempted)

  idempotency_key         VARCHAR(100) NOT NULL,                   -- [SEC: Kabir] derived from redemption_id -- see AffiliateEarningsService
  created_at              DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  settled_at              DATETIME(6) NULL,                        -- UTC; set when status moves to SETTLED

  UNIQUE KEY uq_affiliate_earning_redemption (redemption_id),      -- [SEC: Kabir] structural guard: exactly one earning per redemption, ever
  UNIQUE KEY uq_affiliate_earning_idempotency_key (idempotency_key),
  INDEX idx_affiliate_earning_creator (creator_id),
  INDEX idx_affiliate_earning_workspace (workspace_id),
  INDEX idx_affiliate_earning_status (status),
  INDEX idx_affiliate_earning_batch (settlement_batch_id),
  INDEX idx_affiliate_earning_creator_status (creator_id, status),  -- backs the settlement job's per-creator PENDING sweep
  CONSTRAINT fk_affiliate_earning_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
  CONSTRAINT fk_affiliate_earning_creator FOREIGN KEY (creator_id) REFERENCES creator_profiles(id),
  CONSTRAINT fk_affiliate_earning_redemption FOREIGN KEY (redemption_id) REFERENCES coupon_redemptions(id),
  CONSTRAINT fk_affiliate_earning_batch FOREIGN KEY (settlement_batch_id) REFERENCES affiliate_settlement_batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
