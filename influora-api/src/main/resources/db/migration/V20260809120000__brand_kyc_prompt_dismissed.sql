-- OB-1 (BrandF.md §105/§91) — the brand KYC prompt (`brand-kyc-prompt.tsx`) currently remembers
-- "skip for now" in localStorage only (`influora_brand_kyc_prompt_dismissed`), so a brand that
-- dismisses it on one device/browser is re-prompted on every other device, in a private window,
-- or after clearing storage. This column gives that dismissal a server-side home so the prompt can
-- stay hidden across devices once a user has explicitly skipped it.
--
-- Scoped to the USER row (not `workspaces`), matching `onboarding_completed`'s existing precedent
-- on this same table -- the KYC prompt is a per-person "don't nag me again" UX preference, not a
-- workspace-level verification fact (that fact already exists as `workspaces.verification_status`
-- and is read separately; see OnboardingService#getBrandOnboardingStatus).
--
-- Additive + NOT NULL with a DEFAULT FALSE, matching `email_verified`/`phone_verified`/
-- `onboarding_completed`'s existing boolean convention on this table (all NOT NULL, no nullable
-- tri-state). Every existing user row backfills to FALSE = "not dismissed yet", i.e. current
-- behavior (always show the prompt until KYC is done) is preserved for everyone until they
-- explicitly skip it going forward.

ALTER TABLE users
  ADD COLUMN kyc_prompt_dismissed BOOLEAN NOT NULL DEFAULT FALSE
  COMMENT 'OB-1: brand explicitly dismissed the KYC prompt (POST /onboarding/brand/kyc-prompt-dismissed) -- server-side so it stays hidden across devices, independent of workspaces.verification_status';
