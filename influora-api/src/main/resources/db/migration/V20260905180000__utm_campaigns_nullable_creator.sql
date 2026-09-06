-- Festival Box Phase 7 (T-FESTIVALBOX-0905) -- utm_campaigns.creator_profile_id AND
-- collaboration_id both become NULLABLE so a UTM tracking link can represent two new click
-- shapes, alongside the existing per-creator-per-collaboration link CampaignLinkService already
-- builds:
--
--   1. A per-creator Festival Box link (a roster creator's own reel -> the page): HAS a creator,
--      but Festival Box creators are drawn from a roster, not a Collaboration row -- there is
--      nothing to put in collaboration_id for this case. (creator_profile_id NOT NULL,
--      collaboration_id NULL.)
--   2. A page-level "Shop button" link (the brand's own Shop button on the Festival Box page):
--      has neither a creator nor a collaboration to attribute the click to. (creator_profile_id
--      NULL, collaboration_id NULL.) {@code UtmCampaign#isPageLevel()} is the single source of
--      truth for THIS case specifically -- it is keyed on creator_profile_id alone (see step 2
--      below for why), independent of collaboration_id.
--
-- [RELATIONSHIP TO THIS TABLE'S OWN NOT NULL CONSTRAINTS -- READ THIS BEFORE TOUCHING THESE
-- COLUMNS AGAIN] V23 gave both columns NOT NULL because every UTM link at the time was built by
-- CampaignLinkService#createTrackingLink, which resolves a real Collaboration AND CreatorProfile
-- and cross-checks them against each other (collaboration-campaign mismatch / creator-
-- collaboration mismatch guards) before ever building the row -- there was no link shape that
-- legitimately had neither. THIS MIGRATION DOES NOT WEAKEN THAT CROSS-CHECK: createTrackingLink's
-- resolution and mismatch guards are completely unchanged for the case where both ids ARE
-- supplied. What changes is that a NEW, separate creation path
-- (CampaignLinkService#createPageLevelTrackingLink) is now allowed to skip that resolution
-- entirely, for the one case (no creator, no collaboration) where there is nothing to resolve.
--
-- Same idiom as V20260905160000 (coupon_codes.creator_id nullable, T-FESTIVALBOX-0905 phase 4):
-- a NULL-creator UtmCampaign row can never be confused for an ordinary per-creator link --
-- isPageLevel() is the one explicit branch every consumer checks, never "creator unknown/TBD".
-- The schema additionally enforces "at most ONE page-level link per campaign" itself (step 3
-- below), for the identical MySQL-NULLs-are-distinct-in-a-UNIQUE-key reason V20260905160000's
-- header explains in full: without it, nothing would stop unlimited page-level rows being
-- inserted for one campaign.
--
-- [VERIFICATION NOTE] Docker's daemon is down in this environment -- every statement below has
-- been read/reasoned against the MySQL 8.0 Reference Manual (Online DDL "Changing the NULL/NOT
-- NULL attribute" and "Adding a generated column" support tables; the generated-column rule that
-- an expression may reference other columns of the SAME row, just not other rows/subqueries/non-
-- deterministic functions) exactly as V20260905160000 was, but has NOT been executed against a
-- real MySQL server. It has not been run against H2 either -- H2's MySQL compatibility mode does
-- not reliably reproduce MySQL 8's STORED generated-column + composite-UNIQUE-with-NULL semantics
-- this migration depends on, so a green H2 test here would not actually prove this constraint
-- works; no Testcontainers-MySQL integration test proves it for real in this pass either, for the
-- same Docker-down reason V20260905160000 documented.

-- 1) creator_profile_id and collaboration_id both become nullable. Two nullability-only MODIFY
-- COLUMN statements -- neither changes a column's name, type, or length, so neither requires (and
-- does not silently drop) fk_utm_creator / fk_utm_collaboration or any of the existing indexes on
-- these columns (uq_utm_campaign_creator, idx_utm_creator, idx_utm_collab) -- those are separate
-- catalog objects keyed off the column, not attributes of the column definition itself, and
-- MySQL's ALTER TABLE does not require restating them for a plain nullability flip. A NULL value
-- on either FK column is standard SQL FK semantics (MySQL only supports MATCH SIMPLE): the FK is
-- simply not evaluated for a NULL value, so inserting a NULL creator_profile_id/collaboration_id
-- never needs a matching creator_profiles/collaborations row.
ALTER TABLE utm_campaigns
  MODIFY COLUMN creator_profile_id VARCHAR(26) NULL;              -- was NOT NULL (V23); fk_utm_creator + uq_utm_campaign_creator + idx_utm_creator untouched
ALTER TABLE utm_campaigns
  MODIFY COLUMN collaboration_id VARCHAR(26) NULL;                -- was NOT NULL (V23); fk_utm_collaboration + idx_utm_collab untouched

-- 2) A STORED generated column that evaluates to a constant marker (1) exactly when
-- creator_profile_id IS NULL, and to NULL for every other row. Deliberately keyed on
-- creator_profile_id ALONE, not collaboration_id: a per-creator Festival Box link (case 1 above)
-- has a real creator_profile_id but a NULL collaboration_id, and must NOT be treated as
-- page-level or it would wrongly collide with (or block) the campaign's one real page-level link
-- under the constraint in step 3. isPageLevel() on the entity mirrors this exact same rule.
ALTER TABLE utm_campaigns
  ADD COLUMN page_level_marker TINYINT
    GENERATED ALWAYS AS (CASE WHEN creator_profile_id IS NULL THEN 1 ELSE NULL END) STORED;  -- 1 for a page-level link, NULL for every creator-attributed link (roster or collaboration-backed alike)

-- 3) The actual schema-level enforcement for landmine 2 (see task brief): MySQL treats every NULL
-- in a UNIQUE key as distinct from every other NULL, so UNIQUE(campaign_id, page_level_marker)
-- places NO limit on how many creator-attributed rows (page_level_marker = NULL) one campaign can
-- have -- uq_utm_campaign_creator above already governs the ones that carry a non-null
-- creator_profile_id (MySQL never compares a NULL against anything in a UNIQUE key, so a NULL
-- creator_profile_id row is invisible to that constraint, same as it always was for any nullable
-- unique column) -- but this new key DOES limit page-level rows (page_level_marker = 1) to at
-- most ONE per campaign, enforced by the schema itself, not only an application-level existence
-- check. A concurrent double-insert of two page-level links for the same campaign hits this
-- constraint (surfaced by CampaignLinkService as PAGE_LINK_EXISTS, 409), not a race window in a
-- service-layer SELECT-then-INSERT.
ALTER TABLE utm_campaigns
  ADD UNIQUE KEY uq_utm_campaign_page_level (campaign_id, page_level_marker);
