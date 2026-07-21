-- Creator AI Co-pilot Tier-1 (wiki/build/creator-copilot-priya-review-r1.md Conflict-2 ruling
-- + R2 supersession).
--
-- R2 SUPERSEDES the R1 Conflict-2 column list (single `message` TEXT column). Priya ruled Option
-- (b) in R2: this table now carries `theme`/`headline`/`content_idea` as first-class columns
-- instead of mirroring NudgeLog's single-`message` shape. This closes the P0 flagged in Meera's
-- first verify pass (creator-copilot-meera-verify.md §1.1) — the frozen API-CONTRACT.md §2 DTO
-- (SuggestionDto: id, theme, headline, contentIdea, expiresAt) can now be produced directly from
-- a persisted row with no reconstruction gap. `message_source` (AI|FALLBACK, NudgeMessageSource
-- reused verbatim) and `prompt_version` (invariant #5, build spec §1) are unchanged from R1.
--
-- `expiresAt` is deliberately NOT a column: API-CONTRACT.md §2's own semantics ("end of the
-- creator's current UTC day... display-only for Tier-1") makes it a pure function of `shown_at`
-- computed at toDto()-time (end of shown_at's UTC calendar day) — persisting a redundant derived
-- timestamp would just be a second source of truth to keep in sync for a value that's never
-- itself queried or filtered on.
CREATE TABLE creator_nudge_log (
    id                 VARCHAR(26)  NOT NULL,
    creator_profile_id VARCHAR(26)  NOT NULL,
    trend_id           VARCHAR(26)  COLLATE utf8mb4_0900_ai_ci NOT NULL,   -- MUST match trends.id: V51 declared trends with CHARSET=utf8mb4 but no COLLATE, so MySQL 8 gave it the server-default utf8mb4_0900_ai_ci; this table's default is utf8mb4_unicode_ci, so an FK from trend_id (unicode_ci) to trends.id (0900_ai_ci) fails with SQL 3780. Per-column COLLATE aligns them. (creator_profile_id below stays table-default unicode_ci, which matches creator_profiles.id.)
    match_score        INT          NOT NULL,
    theme              VARCHAR(32)  NOT NULL,   -- closed vocab member (trendspark/theme-taxonomy.json); longest entry today is "togetherness"/"authenticity" (13 chars) — 32 gives headroom without inviting free text
    headline           VARCHAR(255) NOT NULL,   -- AI SuggestionCopy.headline (or templatedFallback's headline half) — same VARCHAR(255) convention as notifications.title (V17__notifications.sql:7)
    content_idea       TEXT         NOT NULL,   -- AI SuggestionCopy.contentIdea (or templatedFallback's body half) — free-form, TEXT like the `message` column it replaces
    message_source     VARCHAR(16)  NOT NULL,   -- NudgeMessageSource: AI|FALLBACK (reused verbatim, unchanged from R1)
    prompt_version     VARCHAR(32)  NOT NULL,   -- invariant #5 (build spec §1) — stamped every row, unchanged from R1
    shown_at           TIMESTAMP    NOT NULL,
    dismissed_at       TIMESTAMP    NULL,
    acted_at           TIMESTAMP    NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_creator_nudge_log_profile FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles (id),
    CONSTRAINT fk_creator_nudge_log_trend   FOREIGN KEY (trend_id) REFERENCES trends (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Hot-path index (build spec §4's idx_cnl_creator_day, kept from the original sketch — R1's draft
-- had dropped it as redundant with the unique key below; restored per R2 instruction to keep both):
-- backs CreatorNudgeService's idempotent-read-first query,
-- findByCreatorProfileIdAndShownAtAfter(creatorProfileId, startOfUtcDay()) (be-services-plan §1
-- step 2) — a range scan on shown_at within one creator's rows, not a table scan.
CREATE INDEX idx_cnl_creator_day ON creator_nudge_log (creator_profile_id, shown_at);

-- Per-creator/day cap DB backstop (be-services-plan §5, GREENLIGHT binding #3 — DB constraint
-- only, no distributed lock in Tier-1). Generated-column + unique-key trick, same pattern already
-- used in this repo (V62__creator_bank_account_primary.sql:36-41,
-- V20260715140000__platform_commission_invoice.sql). MySQL 8.0.13+ required — confirmed available
-- (docker-compose mysql:8.0 image).
ALTER TABLE creator_nudge_log
    ADD COLUMN shown_day DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED,
    ADD UNIQUE KEY uq_creator_nudge_day (creator_profile_id, shown_day);
