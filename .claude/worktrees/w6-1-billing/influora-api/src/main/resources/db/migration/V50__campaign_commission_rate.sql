-- P2-13: per-campaign affiliate commission rate override.
-- NULL = "not set" -> falls back to platform default (AffiliateEarningsService.DEFAULT_COMMISSION_RATE, 10%).
-- Never NOT NULL DEFAULT 0 -- a silent 0.0000 would look like "brand configured zero commission"
-- instead of "unset". See wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md.
ALTER TABLE campaigns ADD COLUMN commission_rate DECIMAL(5,4) NULL;
