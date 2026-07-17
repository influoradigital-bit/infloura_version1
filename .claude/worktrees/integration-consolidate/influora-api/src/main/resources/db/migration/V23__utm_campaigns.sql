-- Phase 4 UTM/Coupon Tracking (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §2.5) — UTM-tagged tracking
-- links generated per creator per campaign, click/visitor/conversion counters updated by
-- CampaignLinkService / ConversionTrackingService.
--
-- [CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline applied to V21/V22 applies here: the spec's §2.5 schema targets
-- PostgreSQL (TIMESTAMPTZ). This is an ordinary MySQL InnoDB table. created_at/updated_at/
-- expires_at use DATETIME(6) storing UTC (matches the V21/V22 convention — DATETIME(6) for
-- microsecond precision, app sets values via Instant.now(), no DB-side NOW()/CURRENT_TIMESTAMP
-- default so behavior matches the entity's Instant-based builder exactly).
--
-- [SCOPE CUT — see Phase 4 task] Coupons (V24 coupon_codes/coupon_redemptions), Shopify/WooCommerce
-- webhooks, and affiliate campaigns are deliberately deferred to later slices — not built here.
--
-- Foreign keys: campaign_id -> campaigns(id) (V4), collaboration_id -> collaborations(id) (V6).
-- creator_profile_id -> creator_profiles(id) (V6) intentionally has NO FK-enforced workspace_id
-- column on this table (same ADR reasoning as creator_metrics/creator_scores: creator profiles are
-- creator-owned, not workspace-owned) — workspace authorization for who may create/read a given
-- row is enforced in CampaignLinkService via campaigns.workspace_id, not at the schema level.
CREATE TABLE utm_campaigns (
  id                      VARCHAR(26) PRIMARY KEY,                 -- ULID
  campaign_id             VARCHAR(26) NOT NULL,                    -- FK campaigns(id)
  collaboration_id        VARCHAR(26) NOT NULL,                    -- FK collaborations(id)
  creator_profile_id      VARCHAR(26) NOT NULL,                    -- FK creator_profiles(id)

  -- UTM parameters
  base_url                VARCHAR(1000) NOT NULL,
  utm_source              VARCHAR(100) NOT NULL,                   -- 'instagram', 'youtube', etc.
  utm_medium              VARCHAR(100) NOT NULL,                   -- 'influencer', 'creator_post'
  utm_campaign            VARCHAR(100) NOT NULL,                   -- campaign slug
  utm_content             VARCHAR(100) NULL,                       -- specific post/story identifier
  utm_term                VARCHAR(100) NULL,                       -- optional: keywords

  -- Generated link
  full_tracking_url       VARCHAR(2000) NOT NULL,
  short_url               VARCHAR(200) NULL,                       -- optional: bit.ly or similar

  -- Stats (updated by CampaignLinkService / ConversionTrackingService)
  click_count             BIGINT NOT NULL DEFAULT 0,
  unique_visitors         BIGINT NOT NULL DEFAULT 0,
  conversion_count        BIGINT NOT NULL DEFAULT 0,
  revenue_attributed      DECIMAL(14,2) NOT NULL DEFAULT 0.00,

  created_at              DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  updated_at              DATETIME(6) NOT NULL,                    -- UTC; app sets via Instant.now()
  expires_at              DATETIME(6) NULL,                        -- UTC; optional auto-expire

  UNIQUE KEY uq_utm_campaign_creator (campaign_id, creator_profile_id),
  INDEX idx_utm_campaign (campaign_id),
  INDEX idx_utm_creator (creator_profile_id),
  INDEX idx_utm_collab (collaboration_id),
  CONSTRAINT fk_utm_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
  CONSTRAINT fk_utm_collaboration FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_utm_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
