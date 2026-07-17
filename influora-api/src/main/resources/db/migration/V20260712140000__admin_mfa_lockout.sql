-- Kabir P1-2 Gap 2 (HIGH) — MFA-specific lockout columns for distributed TOTP brute-force defense.
-- Separate from failed_login_attempts/locked_until (password failures) so an attacker with a valid
-- password but no valid MFA code cannot rotate IPs to attempt many TOTP codes before account locks.
-- Tighter threshold (3 attempts/1 hour) than password lockout (5 attempts/15 min).
--
-- NULLABLE mfa_locked_until, DEFAULT 0 failed_mfa_attempts: every existing admin_users row has zero
-- prior failed MFA attempts and is not MFA-locked — the only correct backfill value.
ALTER TABLE admin_users
  ADD COLUMN failed_mfa_attempts INT NOT NULL DEFAULT 0 AFTER failed_login_attempts,
  ADD COLUMN mfa_locked_until     TIMESTAMP NULL AFTER failed_mfa_attempts;
