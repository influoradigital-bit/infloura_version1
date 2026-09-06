-- F-0551 backend half — remember-me now controls refresh-token lifetime server-side. Each
-- refresh_tokens row records whether it was issued under a "remember me" login (long lifetime)
-- or not (short lifetime) at issuance time, so AuthService#refresh can preserve the original
-- choice across rotation instead of silently upgrading/downgrading it on every refresh.
--
-- DEFAULT TRUE is not a placeholder: every row that exists before this migration runs was issued
-- under the single fixed 30-day lifetime that was the only behavior in effect before this change
-- (AuthCookieService.maxAgeSeconds / JwtProperties.refreshExpirySeconds), which is exactly what
-- "remembered" maps to going forward. Backfilling them as remembered=true is the historically
-- accurate value, not an invented default -- their real expires_at (already 30 days out) is
-- untouched by this migration.
ALTER TABLE refresh_tokens
  ADD COLUMN remembered BOOLEAN NOT NULL DEFAULT TRUE AFTER expires_at;
