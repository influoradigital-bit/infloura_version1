-- T-MEERA-CREATOR-PHASE-B / B0-09 (SPEC.md 2.1) -- the six Phase-B columns on
-- creator_agent_preferences. Appended to the existing V73 table rather than a new table: all six
-- are 1:1 with the creator's single preferences row and are read on the same hot path
-- (MeeraContextService.assembleCreatorContext), so a join would buy nothing.
--
-- rate_card_shareable / rate_card_json (B6, opt-in rate card): the JSON holds creator-TYPED
-- strings, e.g. {"reel":"5000","story_set":"2500","post":"3000"}, not parsed numbers -- the creator
-- authored them and they are echoed back verbatim on the public media kit. TEXT, consistent with
-- every other JSON blob on this table (excluded_categories, blocked_brands, working_days).
-- NOTE the info barrier (SPEC.md 0.3): a rate card is only ever exposed when rate_card_shareable
-- is 1. The three *_floor columns remain never-brand-visible regardless of this flag.
--
-- negotiation_holdout / holdout_until (B6, 20 percent holdout): assigned ONCE at row creation in
-- CreatorAgentPreferencesService.createWithComputedDefaults (SPEC.md 2.9), deterministically from
-- Math.floorMod(profileId.hashCode(), 5) == 0. String.hashCode() is specified by the JLS, so the
-- assignment is stable across JVMs and restarts and needs no stored seed. DEFAULT 0 is the correct
-- value for the Phase-A rows this migration backfills: the holdout is a measurement cohort for
-- creators onboarded from Phase B on, and retro-assigning existing creators would contaminate it.
-- holdout_until is DATE, not TIMESTAMP -- the holdout expires on a calendar day (creation + 90
-- days), never at an instant, and the entity field is the first java.time.LocalDate on this class.
--
-- approved_draft_count (B5, level-0 exit): a counter, not a derived COUNT over meera_drafts.
-- The level-up rule is "10 approved drafts ever"; a COUNT over a table whose rows can be discarded
-- would let the count go DOWN, which is not what "ever" means.
--
-- level_up_prompted_at: null means never prompted. Prevents re-prompting a creator who has
-- already declined the level-1 offer once.
--
-- No CHAR(n) anywhere (SPEC.md 0.5): ddl-auto=validate rejects CHAR against a @Column(length=N)
-- String with "wrong column type ... found [char]" -- the exact boot failure
-- V20260718150000__char_to_varchar_remaining.sql was written to repair.
-- TINYINT(1) is what MySQL already stores for this table's existing `represented BOOLEAN` column,
-- so the boolean entity fields validate against it identically.
ALTER TABLE creator_agent_preferences
    ADD COLUMN rate_card_shareable TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN rate_card_json TEXT NULL,
    ADD COLUMN negotiation_holdout TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN holdout_until DATE NULL,
    ADD COLUMN approved_draft_count INT NOT NULL DEFAULT 0,
    ADD COLUMN level_up_prompted_at TIMESTAMP NULL;
