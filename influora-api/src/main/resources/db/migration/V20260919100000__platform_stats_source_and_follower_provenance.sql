-- F-0965 (ruling 2026-09-19: option a, with a separate imported total).
--
-- Before this, every writer of creator_profiles.total_followers summed ALL platform_stats rows, so a
-- creator declaring "YouTube: 900,000" (PortfolioService.declarePlatform, unverified) inflated the
-- total brands filter and rank on. Now:
--   * platform_stats.source records where a platform's numbers came from:
--       META_API          - a Meta sync (is_verified = TRUE)
--       IMPORTED          - Meta Creator Marketplace / admin import, adopted by ExternalCreatorLinkService
--       CREATOR_REPORTED  - the creator's own declaration (the fail-closed default)
--   * creator_profiles.followers_source records what total_followers is made of:
--       VERIFIED (sum of META_API platforms) | IMPORTED (no verified platform; the imported one) | NONE
--   and total_followers / engagement_rate are recomputed by the same rule as FollowerTotals.java.

ALTER TABLE platform_stats ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'CREATOR_REPORTED';

UPDATE platform_stats SET source = 'META_API' WHERE is_verified = TRUE;

-- ExternalCreatorLinkService only ever wrote an unverified INSTAGRAM stat for a linked creator, and
-- only when no INSTAGRAM stat existed yet. An import writes NO creator_metrics row, while a creator's
-- own declaration (PortfolioService.declarePlatform) and a Meta sync always write one - so a linked
-- creator whose unverified INSTAGRAM stat has a creator_metrics row declared it themselves, and it
-- stays CREATOR_REPORTED.
UPDATE platform_stats
SET source = 'IMPORTED'
WHERE is_verified = FALSE
  AND platform = 'INSTAGRAM'
  AND creator_profile_id IN (
      SELECT linked_creator_profile_id FROM external_creators WHERE linked_creator_profile_id IS NOT NULL)
  AND NOT EXISTS (
      SELECT 1 FROM creator_metrics cm
      WHERE cm.creator_profile_id = platform_stats.creator_profile_id AND cm.platform = 'INSTAGRAM');

ALTER TABLE creator_profiles ADD COLUMN followers_source VARCHAR(20) NOT NULL DEFAULT 'NONE';

-- 1. Any Meta-synced platform: the total is the sum of those platforms only.
UPDATE creator_profiles
SET total_followers = (
        SELECT COALESCE(SUM(ps.followers), 0) FROM platform_stats ps
        WHERE ps.creator_profile_id = creator_profiles.id AND ps.source = 'META_API'),
    engagement_rate = (
        SELECT ps.engagement_rate FROM platform_stats ps
        WHERE ps.creator_profile_id = creator_profiles.id AND ps.source = 'META_API'
          AND ps.engagement_rate IS NOT NULL
        ORDER BY ps.followers DESC LIMIT 1),
    followers_source = 'VERIFIED'
WHERE EXISTS (
        SELECT 1 FROM platform_stats ps
        WHERE ps.creator_profile_id = creator_profiles.id AND ps.source = 'META_API');

-- 2. No verified platform but an imported one: the imported total, labelled as such.
UPDATE creator_profiles
SET total_followers = (
        SELECT COALESCE(SUM(ps.followers), 0) FROM platform_stats ps
        WHERE ps.creator_profile_id = creator_profiles.id AND ps.source = 'IMPORTED'),
    engagement_rate = (
        SELECT ps.engagement_rate FROM platform_stats ps
        WHERE ps.creator_profile_id = creator_profiles.id AND ps.source = 'IMPORTED'
          AND ps.engagement_rate IS NOT NULL
        ORDER BY ps.followers DESC LIMIT 1),
    followers_source = 'IMPORTED'
WHERE followers_source = 'NONE'
  AND EXISTS (
        SELECT 1 FROM platform_stats ps
        WHERE ps.creator_profile_id = creator_profiles.id AND ps.source = 'IMPORTED');

-- 3. Only creator-declared platforms: they no longer count toward the total.
UPDATE creator_profiles
SET total_followers = 0,
    engagement_rate = NULL
WHERE followers_source = 'NONE'
  AND EXISTS (SELECT 1 FROM platform_stats ps WHERE ps.creator_profile_id = creator_profiles.id);
