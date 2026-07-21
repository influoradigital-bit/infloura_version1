-- DRAFT — reviewable artifact only, NOT applied. Creator AI Co-pilot Tier-1
-- (wiki/build/creator-copilot-priya-review-r1.md pre-condition 5 / build spec §4).
--
-- Mirrors brand_profiles.theme_tags (V51__trendspark.sql:21-22) exactly: same JSON NULL shape,
-- same "additive, no backfill, populated by a batch job" story. Populated by the nightly
-- CreatorThemeTaggingJob (be-services-plan §2.6), read via
-- CreatorProfile.getThemeTagsJson()/setThemeTagsJson() (be-services-plan §2.1) — a plain JSON
-- column, NOT routed through the user-facing applySelfEdit partial-update path, exactly like
-- BrandProfile.setThemeTagsJson is separate from applyAnalysisResult.
--
-- Verified against the tree before drafting: creator_profiles (V6__creators_collaborations.sql)
-- has no theme_tags column today, so this is a clean additive ALTER with no naming collision.
ALTER TABLE creator_profiles
    ADD COLUMN theme_tags JSON NULL;
