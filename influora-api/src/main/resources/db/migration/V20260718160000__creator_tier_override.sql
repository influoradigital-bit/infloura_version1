-- Admin Panel — AdminCreatorController tier-adjust support (creatorApi.adjustTier ->
-- PUT /admin/creators/{id}/tier, src/admin/services/api-contracts.ts:256-260). Owner: Vikram (Backend).
--
-- `creator_profiles` (V6__creators_collaborations.sql, moderation columns in V38) has NO persisted
-- tier concept today — the admin surface derives tier from total_followers at read time
-- (AdminCreatorService.deriveTier). creatorApi.adjustTier needs an explicit admin-chosen override
-- that wins over the derived value. This adds a nullable override column (NULL = "use the derived
-- tier", which preserves the current behavior for every existing/new row — no default tier is
-- fabricated) plus admin-attribution columns, mirroring V36/V38's
-- suspended_by/reinstated_by/reviewed_by -> admin_users(id) FK convention exactly.
--
-- Additive + nullable only: no existing data is rewritten. Like V36/V38 this is a FK from a
-- pre-admin-panel table (V6) into a V34+ table (admin_users) — safe because this migration runs
-- after V34 in Flyway's strictly-increasing version order, so admin_users always exists first.

-- tier_override is VARCHAR(20), NOT a MySQL ENUM: Hibernate runs ddl-auto=validate and the entity
-- maps this as @Enumerated(STRING) @Column(length = 20) -> VARCHAR(20). Matches the recent
-- convention (identity_kyc_status/tax_registration_status VARCHAR(20), and the V20260718150000
-- char->varchar cleanup). The allowed value set (NANO/MICRO/MID/MACRO) is enforced at the app
-- layer via CreatorTier.valueOf in AdminCreatorService.adjustTier, not a DB CHECK.
ALTER TABLE creator_profiles
  ADD COLUMN tier_override     VARCHAR(20) NULL AFTER application_rejection_reason,
  ADD COLUMN tier_adjusted_by  VARCHAR(26) NULL AFTER tier_override,
  ADD COLUMN tier_adjusted_at  TIMESTAMP   NULL AFTER tier_adjusted_by,
  ADD INDEX idx_creator_profiles_tier_override (tier_override),
  ADD CONSTRAINT fk_creator_profiles_tier_adjusted_by FOREIGN KEY (tier_adjusted_by) REFERENCES admin_users(id);
