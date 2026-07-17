-- B5 — Campaign Templates. wiki/tech/CAMPAIGN-TEMPLATES-WORKFLOW.md §1.
-- Timestamp-named convention (safe alongside sequential V63 without a collision — see
-- V202607131...-prefixed migrations already in this directory for precedent).
--
-- Mirrors campaigns' writable field set (no dates/status) plus template metadata. SYSTEM rows
-- have workspace_id = NULL and are readable by every brand tier; CUSTOM rows are workspace-scoped
-- and Pro-gated on save (Plan.campaignTemplatesEnabled, enforced at the app layer via
-- @RequiresPlan, not here).

CREATE TABLE campaign_templates (
  id                  VARCHAR(26) PRIMARY KEY,
  name                VARCHAR(200) NOT NULL,
  description         TEXT,
  category            ENUM('AWARENESS','SALES','UGC','AFFILIATE','CUSTOM') NOT NULL,
  scope               ENUM('SYSTEM','CUSTOM') NOT NULL,
  workspace_id        VARCHAR(26) NULL,
  created_by          VARCHAR(26) NULL,
  campaign_type       ENUM('HYPE','DIRECT','REVIEW','STANDARD') NULL,
  budget_min          DECIMAL(12,2),
  budget_max          DECIMAL(12,2),
  platforms           JSON,
  content_types       JSON,
  objectives          JSON,
  requirements        JSON,
  hashtags            JSON,
  target_audience     JSON,
  brand_guidelines    TEXT,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_campaign_templates_workspace (workspace_id),
  INDEX idx_campaign_templates_scope (scope),
  CONSTRAINT fk_campaign_templates_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed the 4 SYSTEM presets (Tejas content, kept simple per the build brief — refined later).
-- Fixed ULIDs so this migration is deterministic/idempotent across environments.

INSERT INTO campaign_templates (
  id, name, description, category, scope, workspace_id, created_by, campaign_type,
  budget_min, budget_max, platforms, content_types, objectives, requirements, hashtags,
  target_audience, brand_guidelines, created_at, updated_at
) VALUES (
  '01JZ0AWARENESS0000000000TP',
  'Brand Awareness',
  'Reach-oriented preset — maximize impressions and new-audience reach with Reels and Stories.',
  'AWARENESS', 'SYSTEM', NULL, NULL, 'HYPE',
  10000.00, 50000.00,
  JSON_ARRAY('instagram', 'youtube'),
  JSON_ARRAY('reel', 'story'),
  JSON_ARRAY('reach', 'impressions'),
  JSON_ARRAY('Tag brand handle', 'Use campaign hashtag'),
  JSON_ARRAY('#brandpartner'),
  JSON_OBJECT('ageRange', '18-34', 'interests', JSON_ARRAY('lifestyle')),
  'Keep tone authentic and on-brand; no competitor mentions.',
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO campaign_templates (
  id, name, description, category, scope, workspace_id, created_by, campaign_type,
  budget_min, budget_max, platforms, content_types, objectives, requirements, hashtags,
  target_audience, brand_guidelines, created_at, updated_at
) VALUES (
  '01JZ0SALES00000000000000TP',
  'Sales & Conversions',
  'Conversion-oriented preset — drive clicks and sales via Reels and link-in-bio placements.',
  'SALES', 'SYSTEM', NULL, NULL, 'DIRECT',
  15000.00, 75000.00,
  JSON_ARRAY('instagram', 'tiktok'),
  JSON_ARRAY('reel', 'link_in_bio'),
  JSON_ARRAY('conversions', 'clicks'),
  JSON_ARRAY('Include tracked link/coupon code', 'Disclose partnership'),
  JSON_ARRAY('#ad', '#shopnow'),
  JSON_OBJECT('ageRange', '18-45', 'interests', JSON_ARRAY('shopping')),
  'CTA must be clear and link/coupon code must be visible in caption.',
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO campaign_templates (
  id, name, description, category, scope, workspace_id, created_by, campaign_type,
  budget_min, budget_max, platforms, content_types, objectives, requirements, hashtags,
  target_audience, brand_guidelines, created_at, updated_at
) VALUES (
  '01JZ0UGC0000000000000000TP',
  'UGC Content Pack',
  'Content-generation preset — raw video/photo assets for brand reuse, lower budget band.',
  'UGC', 'SYSTEM', NULL, NULL, 'STANDARD',
  5000.00, 20000.00,
  JSON_ARRAY('instagram'),
  JSON_ARRAY('raw_video', 'raw_photo'),
  JSON_ARRAY('asset_generation'),
  JSON_ARRAY('Deliver raw unedited files', 'Grant usage rights per contract terms'),
  JSON_ARRAY('#ugc'),
  JSON_OBJECT('ageRange', '18-40', 'interests', JSON_ARRAY('general')),
  'Usage-rights note: brand may reuse delivered assets in paid ads per contract usage-rights terms.',
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO campaign_templates (
  id, name, description, category, scope, workspace_id, created_by, campaign_type,
  budget_min, budget_max, platforms, content_types, objectives, requirements, hashtags,
  target_audience, brand_guidelines, created_at, updated_at
) VALUES (
  '01JZ0AFFILIATE0000000000TP',
  'Affiliate / Revenue Share',
  'Performance preset — revenue-share creators post reviews/demos with a commission structure.',
  'AFFILIATE', 'SYSTEM', NULL, NULL, 'REVIEW',
  0.00, 30000.00,
  JSON_ARRAY('instagram', 'youtube', 'tiktok'),
  JSON_ARRAY('review', 'demo'),
  JSON_ARRAY('revenue_share'),
  JSON_ARRAY('Use unique coupon code', 'Report commissionable sales'),
  JSON_ARRAY('#affiliate', '#partner'),
  JSON_OBJECT('ageRange', '18-50', 'interests', JSON_ARRAY('general')),
  'Commission rate and coupon code to be confirmed per-creator in the deal terms.',
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
