-- P1 security hardening (Kabir §8, HIGH — AdminAuthService.java:101-109): admin login had no
-- failed-attempt lockout at all, the highest-value credential surface with the weakest gate.
-- Mirrors the shape of every other "audit trail + threshold" column pair already in this schema
-- (creator_profiles.is_suspended/V38, workspaces suspension/V36) rather than inventing a new one.
--
-- NULLABLE locked_until, DEFAULT 0 failed_login_attempts: every existing admin_users row has zero
-- prior failed attempts and is not locked — the only correct backfill value, not a guess.
ALTER TABLE admin_users
  ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0 AFTER last_login_at,
  ADD COLUMN locked_until           TIMESTAMP NULL AFTER failed_login_attempts;
