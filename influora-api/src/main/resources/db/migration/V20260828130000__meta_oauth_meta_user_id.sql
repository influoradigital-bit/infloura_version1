-- F-0390 C5 (Kabir Track E, Meta deauthorize/data-deletion callback): a Meta deauthorize
-- signed_request carries only { user_id, algorithm, issued_at } -- the FB app-scoped user id for
-- THIS app, not an Instagram Business Account id and not a Page id. For INSTAGRAM_LOGIN tokens
-- that id was already resolvable via ig_business_account_id (the Instagram user id IS the id
-- Meta's Instagram-Login code exchange returns as user_id, stored there since V65/T-IGLOGIN-0820).
-- For FACEBOOK_LOGIN tokens there was no column carrying the FB user's own id at all -- the
-- deauthorize callback's user_id could never be matched to a row for that path.
--
-- Nullable, same convention as ig_business_account_id (V65): every row that exists before this
-- migration ships was connected without this being fetched/persisted, so it backfills to NULL
-- rather than a guess -- MetaTokenStorage#findByMetaUserIdAndAuthPathAndRevokedFalse must treat a
-- NULL-column row the same as "no match" (never throw), same fail-safe discipline every other
-- nullable lookup column on this table already follows.
ALTER TABLE meta_oauth_tokens
  ADD COLUMN meta_user_id VARCHAR(64) NULL AFTER ig_business_account_id,
  ADD INDEX idx_meta_oauth_meta_user_id (meta_user_id);
