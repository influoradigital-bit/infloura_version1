-- T-CREATOR-CREDITS-V2 B1 — permanent, once-ever claim rows for the welcome 40 (Kabir K-12,
-- design-priya C3/C4 conflict resolution: a claim must survive account deletion and disconnect,
-- so it carries NO FK to users or to meta_oauth_tokens).
--
-- claim_key is one of:
--   user:<usersId>                — one per creator, forever (reconnect never re-arms the grant)
--   ig:<igBusinessAccountId>      — one per Instagram Business Account, forever (anti-farming: a
--                                    second user connecting the SAME IG account earns nothing)
--   meta:<metaUserId>             — only inserted when MetaOAuthToken actually exposes a Meta user
--                                    id (FACEBOOK_LOGIN rows only — see MetaOAuthToken#getMetaUserId)
--
-- grantWelcome inserts all applicable keys in ONE transaction (via a REQUIRES_NEW helper) before
-- ever granting credit; a UNIQUE-constraint violation on ANY of them aborts the grant with no
-- credit given, so two concurrent connects racing on the same IG id under different user ids can
-- produce at most one grant (Kabir K-12, CreatorCreditConcurrencyIntegrationTest#welcomeClaimRace).
CREATE TABLE creator_credit_welcome_claims (
  claim_key        VARCHAR(80) NOT NULL PRIMARY KEY,
  creator_user_id  VARCHAR(26) NOT NULL,
  claimed_at       DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ccwc_creator ON creator_credit_welcome_claims (creator_user_id);
