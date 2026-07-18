-- Priya task: "Hype" campaigns (brand-new-hype-campaign.tsx, POST /campaigns with
-- campaignType: 'HYPE') were persisting only the generic campaign fields and silently dropping
-- the `hype` block (sourceReelUrl, audioTrack, hashtag, formatLanes, perReelRate, currency,
-- slotCap, slotsFilled, liveUntil) -- a Hype campaign round-tripped as a plain campaign with no
-- way to recover its defining config.
--
-- campaign_type itself already exists (V30__campaigns_campaign_type.sql) -- this migration only
-- adds storage for the HYPE-specific config block, and only ever gets populated for
-- campaign_type = 'HYPE' rows (see CampaignService.create/update); every other type leaves it NULL.
--
-- Storage choice: a single JSON column, matching the existing JSON-column precedent already used
-- on this table (platforms/content_types/objectives/requirements/hashtags/target_audience) and on
-- BrandProfile (niche_tags/product_catalog) -- NOT flat scalar columns, because the hype block
-- mixes scalars with a formatLanes list and is exclusive to one campaign type, so normalizing it
-- into columns would just add dead columns to every STANDARD/DIRECT/REVIEW row.
--
-- Additive + nullable, no default, no backfill: every existing campaign is non-HYPE today, so
-- hype_config simply stays NULL for all of them.
ALTER TABLE campaigns
  ADD COLUMN hype_config JSON NULL AFTER campaign_type;
