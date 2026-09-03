-- V72 — Meera for Creators, Phase A (T-MEERA-CREATOR-PHASE-A, SPEC.md 1.1).
--
-- Structured deal terms on collaborations (usage rights / exclusivity / revisions) and end-brand
-- identity on campaigns. Both are additive: every new column is nullable or carries a safe
-- default, so every existing row keeps validating under ddl-auto=validate with no backfill.
--
-- end_brand_name / end_brand_category are "required for new campaigns" at the DTO/service layer
-- (CampaignDtos.CampaignWriteRequest, CampaignService.create) — NOT a NOT NULL column, because a
-- NOT NULL column has no legal value for the campaigns that already exist.

ALTER TABLE collaborations
    ADD COLUMN usage_months         INTEGER         NULL,
    ADD COLUMN usage_perpetual      BOOLEAN         NOT NULL DEFAULT false,
    ADD COLUMN usage_channels       VARCHAR(255)    NULL,
    ADD COLUMN exclusivity_days     INTEGER         NULL,
    ADD COLUMN exclusivity_scope    VARCHAR(32)     NOT NULL DEFAULT 'NONE',
    ADD COLUMN exclusivity_brands   TEXT            NULL,
    ADD COLUMN max_revisions        INTEGER         NOT NULL DEFAULT 2;

ALTER TABLE campaigns
    ADD COLUMN end_brand_name       VARCHAR(200)    NULL,
    ADD COLUMN end_brand_category   VARCHAR(100)    NULL;
