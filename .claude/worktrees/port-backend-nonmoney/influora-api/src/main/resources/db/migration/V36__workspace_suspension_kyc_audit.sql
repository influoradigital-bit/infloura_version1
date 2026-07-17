-- Admin Panel Phase 1 (P1) — AdminBrandController support (src/admin/TASK_ASSIGNMENTS.md,
-- Vikram cycle 4). Owner: Vikram (Backend).
--
-- `workspaces` (V2__core_auth.sql, type=BRAND) is the existing entity that backs the admin
-- panel's `BrandDetail`/`Brand` frontend types (src/admin/types/admin.types.ts) — confirmed via
-- grep before adding a duplicate table: `workspaces` already carries `name`/`industry`/
-- `company_size`/`verification_status`/`gstin`/`pan`, which is exactly the shape
-- `BrandProfile.tsx` renders. What's missing for AdminBrandController's 4 endpoints (brand
-- detail, KYC verify/reject, suspend, reinstate) is (1) a suspension flag + audit trail columns
-- (no `is_active`/`is_suspended` column exists on `workspaces` today — the existing
-- `users.status` SUSPENDED enum value is a different table entirely, brand-level suspension has
-- no home), (2) a billing address free-text column (`billing_email` already exists but
-- `BrandDetail.billingAddress` has nothing to read from), and (3) a KYC review audit trail
-- (`BrandDetail.kycReviewedBy`/`kycReviewedAt`/`kycRejectionReason` — `verification_status`
-- alone records the outcome but not who decided it, when, or why a rejection happened).

ALTER TABLE workspaces
  ADD COLUMN billing_address      VARCHAR(500) NULL AFTER billing_email,
  ADD COLUMN is_suspended         BOOLEAN NOT NULL DEFAULT FALSE AFTER kyc_pan_doc_url,
  ADD COLUMN suspended_reason     VARCHAR(500) NULL AFTER is_suspended,
  ADD COLUMN suspended_at         TIMESTAMP NULL AFTER suspended_reason,
  ADD COLUMN suspended_by         VARCHAR(26) NULL AFTER suspended_at,
  ADD COLUMN reinstated_at        TIMESTAMP NULL AFTER suspended_by,
  ADD COLUMN reinstated_by        VARCHAR(26) NULL AFTER reinstated_at,
  ADD COLUMN kyc_reviewed_by      VARCHAR(26) NULL AFTER reinstated_by,
  ADD COLUMN kyc_reviewed_at      TIMESTAMP NULL AFTER kyc_reviewed_by,
  ADD COLUMN kyc_rejection_reason VARCHAR(1000) NULL AFTER kyc_reviewed_at,
  ADD INDEX idx_workspaces_is_suspended (is_suspended),
  ADD CONSTRAINT fk_workspaces_suspended_by     FOREIGN KEY (suspended_by)    REFERENCES admin_users(id),
  ADD CONSTRAINT fk_workspaces_reinstated_by    FOREIGN KEY (reinstated_by)   REFERENCES admin_users(id),
  ADD CONSTRAINT fk_workspaces_kyc_reviewed_by  FOREIGN KEY (kyc_reviewed_by) REFERENCES admin_users(id);

-- Note: `suspended_by`/`reinstated_by`/`kyc_reviewed_by` reference `admin_users(id)` (V34), not
-- `users(id)` — every mutation these columns record is an ADMIN action, never a brand/creator
-- self-action. This is the first FK from `workspaces` (a V2, pre-admin-panel table) into
-- `admin_users` (a V34+ table) — safe because V36 necessarily runs after V34/V35 in Flyway's
-- strictly-increasing version order, so `admin_users` always exists first.
