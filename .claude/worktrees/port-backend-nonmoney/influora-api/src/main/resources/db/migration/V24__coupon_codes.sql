-- Phase 4 UTM/Coupon Tracking (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2.6, CORRECTED by §10 "Unique
-- Coupons Per Creator (CRITICAL FIX)") -- coupon-code storage for per-creator conversion tracking.
--
-- [SPEC CONFLICT RESOLVED] §2.6's original coupon_codes design allowed a nullable
-- collaboration_id/creator_profile_id (campaign-wide codes) with a single global UNIQUE(code). §10
-- explicitly supersedes that design ("same coupon code for multiple creators -> broken
-- attribution") and requires every creator to get a mandatory, unique coupon code per campaign.
-- This migration builds §10's corrected shape -- creator_id NOT NULL, a direct workspace_id column,
-- UNIQUE(workspace_id, code), and UNIQUE(campaign_id, creator_id). The nullable/campaign-wide
-- version from §2.6 was NOT built.
--
-- [CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline as V21/V22/V23: TIMESTAMPTZ -> DATETIME(6) UTC (app sets via
-- Instant.now(), no DB-side NOW()/CURRENT_TIMESTAMP default), JSONB -> JSON, no CREATE
-- EXTENSION/hypertable syntax. §2.6's Postgres-only partial index
-- (`idx_coupon_creator ... WHERE creator_profile_id IS NOT NULL`) is dropped -- MySQL doesn't
-- support partial indexes, and moot anyway since creator_id is now NOT NULL under §10's shape (a
-- plain index covers it).
--
-- Foreign keys: campaign_id -> campaigns(id) (V4), creator_id -> creator_profiles(id) (V6).
-- workspace_id has no FK constraint (same convention as campaigns.workspace_id itself -- workspaces
-- aren't modeled as their own FK-enforced table elsewhere in this schema either); it is populated
-- from campaign.getWorkspaceId() at write time and exists purely so coupon_codes can be
-- authorization-scoped directly, without a join through campaigns, unlike utm_campaigns.
CREATE TABLE coupon_codes (
  id                      VARCHAR(26) PRIMARY KEY,                 -- ULID
  workspace_id            VARCHAR(26) NOT NULL,                    -- owning brand workspace (direct, no join needed)
  campaign_id             VARCHAR(26) NOT NULL,                    -- FK campaigns(id)
  creator_id              VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id) -- KEY CHANGE per §10, was nullable

  code                    VARCHAR(50) NOT NULL,                    -- e.g. "PRIYA_SUMMER25"
  discount_type           VARCHAR(20) NOT NULL,                    -- 'percentage' or 'fixed'
  discount_value          DECIMAL(10,2) NOT NULL,                  -- 15 for 15%, or 500 for INR 500

  usage_limit             INT NULL,                                -- NULL = unlimited
  usage_count             INT NOT NULL DEFAULT 0,

  expires_at              DATETIME(6) NULL,                        -- UTC; optional
  created_at              DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()

  UNIQUE KEY uq_coupon_workspace_code (workspace_id, code),         -- code unique per workspace
  UNIQUE KEY uq_coupon_campaign_creator (campaign_id, creator_id),  -- one code per creator per campaign
  INDEX idx_coupon_campaign (campaign_id),
  INDEX idx_coupon_creator (creator_id),
  INDEX idx_coupon_workspace (workspace_id),
  CONSTRAINT fk_coupon_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
  CONSTRAINT fk_coupon_creator FOREIGN KEY (creator_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Coupon redemptions (for idempotency and audit) -- carried over from §2.6 unchanged in shape
-- (§10's addendum does not redefine this table), FK updated to point at the corrected coupon_codes
-- table above. idempotency_key is [SEC: Kabir]-flagged in the spec -- load-bearing for preventing
-- double-counted redemptions; kept as a NOT NULL UNIQUE column exactly as specified.
CREATE TABLE coupon_redemptions (
  id                      VARCHAR(26) PRIMARY KEY,                 -- ULID
  coupon_id               VARCHAR(26) NOT NULL,                    -- FK coupon_codes(id)
  order_id                VARCHAR(100) NOT NULL,                   -- external order ID from brand's system
  order_amount            DECIMAL(12,2) NOT NULL,
  discount_applied        DECIMAL(12,2) NOT NULL,
  customer_id             VARCHAR(100) NULL,                       -- optional: for per-user limit tracking
  redeemed_at             DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  idempotency_key         VARCHAR(100) NOT NULL UNIQUE,            -- [SEC: Kabir] prevent double-counting

  metadata                JSON NULL,                                -- additional brand-provided data

  INDEX idx_redemption_coupon (coupon_id),
  INDEX idx_redemption_order (order_id),
  INDEX idx_redemption_time (redeemed_at DESC),
  CONSTRAINT fk_redemption_coupon FOREIGN KEY (coupon_id) REFERENCES coupon_codes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
