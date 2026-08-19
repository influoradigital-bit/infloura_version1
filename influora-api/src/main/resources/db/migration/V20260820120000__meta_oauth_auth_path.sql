-- T-IGLOGIN-0820: Influora is adding Instagram Login (Business Login for Instagram) alongside the
-- existing Facebook Login for Business path, so a creator with an Instagram professional account
-- and NO linked Facebook Page can connect. Verified against Meta's Instagram Platform Overview
-- (updated 2026-03-09): the Facebook Page requirement belongs to the Facebook-Login configuration,
-- not to Instagram; the Instagram-Login configuration explicitly does not require a Page.
--
-- Tokens from the two paths are NOT interchangeable and this column is what tells them apart:
--   FACEBOOK_LOGIN  - Facebook User/Page token, host graph.facebook.com, ig id resolved via
--                     /me/accounts, refreshed via the Facebook long-lived exchange.
--   INSTAGRAM_LOGIN - Instagram User token, host graph.instagram.com, ig id arrives with the token
--                     exchange (no /me/accounts, no Page), refreshed via refresh_access_token.
--
-- NOT NULL with a FACEBOOK_LOGIN default: every row that exists today was created by the Facebook
-- path, so the backfill is exact rather than a guess. A nullable column would let a future insert
-- omit the discriminator and leave the host selection undefined.
ALTER TABLE meta_oauth_tokens
  ADD COLUMN auth_path VARCHAR(32) NOT NULL DEFAULT 'FACEBOOK_LOGIN' AFTER creator_profile_id;
