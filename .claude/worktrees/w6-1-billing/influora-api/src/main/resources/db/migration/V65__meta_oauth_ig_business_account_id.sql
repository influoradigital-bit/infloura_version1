-- H-9 fix (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md): the OAuth callback never resolved
-- Instagram's real numeric Business Account id, so every downstream Graph API call was passing our
-- internal ULID (creator_profile_id) as the account id and 400ing. This column persists the id
-- resolved from GET /me/accounts?fields=instagram_business_account at connect time (see
-- MetaOAuthController.callback / FacebookPageClient.resolveConnectedInstagram).
--
-- Nullable: existing rows (connected before this fix shipped) will not have it until the creator
-- reconnects; consumers (MetricsPollingJob/DeliverableVerificationService/AudienceDemographicsJob)
-- must treat NULL the same as "no valid token" and skip rather than call Graph with a null id.
ALTER TABLE meta_oauth_tokens
  ADD COLUMN ig_business_account_id VARCHAR(64) NULL AFTER creator_profile_id;
