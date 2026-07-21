-- Creator AI Co-pilot Tier-1 (be-services-plan.md §0/§3) — the IG OAuth ownership flip.
--
-- Verified live-system bug this closes: MetaOAuthController.callback (already creator-only,
-- requireCreator + resolves CreatorProfile by principal.getUserId()) calls
-- tokenStorage.storeToken(creatorProfile.getId(), principal.getWorkspaceId(), ...) — but a
-- CREATOR-type AuthPrincipal has no workspaceId (see security/AnalyticsUsageCapInterceptor.java's
-- own comment), so that call always passed workspace_id = NULL against this column's prior
-- `NOT NULL` constraint. Every creator who ever completed /meta/oauth/callback got a
-- DataIntegrityViolationException on the insert — this path could never have succeeded.
--
-- The existing UNIQUE KEY uq_meta_oauth_workspace_creator (workspace_id, creator_profile_id)
-- (V20__meta_oauth_tokens.sql) is left untouched: MySQL unique keys treat multiple NULLs as
-- non-conflicting, which is exactly the semantics wanted here (many creators, each with one
-- workspace_id=NULL row, must never collide with each other). [SEC: Kabir F-1] that same
-- "multiple NULLs never conflict" behavior also means this ALTER alone does not stop two
-- concurrent first-time creator connects from both inserting a non-revoked NULL-workspace row —
-- MetaTokenStorage.storeCreatorToken closes that at the application layer (revoke-before-insert),
-- not with a second DB constraint here.
ALTER TABLE meta_oauth_tokens
    MODIFY COLUMN workspace_id VARCHAR(26) NULL;
