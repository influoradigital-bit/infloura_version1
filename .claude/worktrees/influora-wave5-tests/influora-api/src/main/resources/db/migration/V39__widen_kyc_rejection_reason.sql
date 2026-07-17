-- Kabir cycle 5 (wiki/admin-progress/SECURITY-NOTES.md) — DTO/column length mismatch fix.
-- `VerifyKycRequest.reason` (AdminBrandDtos.java) was always @Size(max = 2000) — the 2000 cap is
-- the deliberate business limit (KYC rejection reasons plausibly need more room than the 500-char
-- suspend/reinstate reasons), but `workspaces.kyc_rejection_reason` was mistakenly created at
-- VARCHAR(1000) in V36. A reject reason 1001-2000 chars long passed bean validation, then threw a
-- DB truncation error on save inside the same @Transactional method as the audit-log write,
-- rolling back both (no KYC state change, no audit row, raw 500 instead of a clean 400).
-- Widening the column to match the DTO's existing validation, rather than truncating admin input
-- by tightening the DTO down to 1000.

ALTER TABLE workspaces
  MODIFY COLUMN kyc_rejection_reason VARCHAR(2000) NULL;
