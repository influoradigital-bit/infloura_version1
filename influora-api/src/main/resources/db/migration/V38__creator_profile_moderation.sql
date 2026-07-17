-- Admin Panel Phase 1 (P1) — AdminCreatorController support (src/admin/TASK_ASSIGNMENTS.md,
-- Vikram cycle 5). Owner: Vikram (Backend).
--
-- `creator_profiles` (V6__creators_collaborations.sql) is the existing entity that backs the
-- admin panel's `CreatorDetail`/`Creator` frontend types (src/admin/types/admin.types.ts) —
-- confirmed via grep before adding a duplicate table: `creator_profiles` already carries
-- `display_name`/`bio`/`city`/`is_verified`/`total_followers`/`engagement_rate`, and
-- `platform_stats` already carries the per-platform Instagram handle/followers/verified flag.
-- What's missing for AdminCreatorController's 5 endpoints (creator detail, review-application,
-- force-reauth, suspend, reinstate) is (1) a suspension flag + audit trail — same gap
-- `workspaces` had before V36, `creator_profiles` has no `is_suspended` column today either —
-- and (2) an application-review audit trail (`CreatorDetail.applicationStatus`/
-- `applicationReviewedBy`/`applicationReviewedAt`/`applicationRejectionReason`).
--
-- Mirrors V36__workspace_suspension_kyc_audit.sql's shape/column-naming exactly, same
-- suspended_by/reinstated_by/reviewed_by -> admin_users(id) FK convention.

ALTER TABLE creator_profiles
  ADD COLUMN is_suspended                BOOLEAN NOT NULL DEFAULT FALSE AFTER total_followers,
  ADD COLUMN suspended_reason            VARCHAR(500) NULL AFTER is_suspended,
  ADD COLUMN suspended_at                TIMESTAMP NULL AFTER suspended_reason,
  ADD COLUMN suspended_by                VARCHAR(26) NULL AFTER suspended_at,
  ADD COLUMN reinstated_at               TIMESTAMP NULL AFTER suspended_by,
  ADD COLUMN reinstated_by               VARCHAR(26) NULL AFTER reinstated_at,
  -- Default APPROVED, not PENDING: this codebase's creator signup flow (User.newCreator /
  -- CreatorProfile.newForUser) has no pre-approval gate today — creators are discoverable as
  -- soon as they set is_discoverable=true, with no admin review step in between. Defaulting
  -- every existing/new row to APPROVED preserves that live behavior unchanged; this migration
  -- only exposes an admin-manageable review column, it does not invent a moderation gate on the
  -- live signup path. PENDING is left available for a future product decision to require
  -- pre-approval, at which point CreatorProfile.newForUser's default would change too.
  ADD COLUMN application_status          ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'APPROVED' AFTER reinstated_by,
  ADD COLUMN application_reviewed_by     VARCHAR(26) NULL AFTER application_status,
  ADD COLUMN application_reviewed_at     TIMESTAMP NULL AFTER application_reviewed_by,
  ADD COLUMN application_rejection_reason VARCHAR(1000) NULL AFTER application_reviewed_at,
  ADD INDEX idx_creator_profiles_is_suspended (is_suspended),
  ADD INDEX idx_creator_profiles_application_status (application_status),
  ADD CONSTRAINT fk_creator_profiles_suspended_by     FOREIGN KEY (suspended_by)            REFERENCES admin_users(id),
  ADD CONSTRAINT fk_creator_profiles_reinstated_by    FOREIGN KEY (reinstated_by)           REFERENCES admin_users(id),
  ADD CONSTRAINT fk_creator_profiles_application_reviewed_by FOREIGN KEY (application_reviewed_by) REFERENCES admin_users(id);

-- Note: like V36, this is a FK from a pre-admin-panel table (V6) into a V34+ table
-- (`admin_users`) — safe because V38 necessarily runs after V34/V35/V36/V37 in Flyway's
-- strictly-increasing version order, so `admin_users` always exists first.
