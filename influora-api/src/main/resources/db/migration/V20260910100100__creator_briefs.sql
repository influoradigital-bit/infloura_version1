-- T-MEERA-CREATOR-PHASE-B / B0-09 (SPEC.md 2.2) -- one row per brief a creator brings to Meera,
-- whether pasted from a DM/email (source = PASTED) or lifted from an on-platform campaign
-- (source = PLATFORM). This is the anchor row for the whole Paste-and-Read arc: the raw text goes
-- in on paste and every later analysis result is written back onto the SAME row.
--
-- WHY raw_text IS PERSISTED FIRST AND SEPARATELY from extracted_json: brief extraction is an AI
-- call and AI calls fail. BriefFallbackExtractor (B0-41) exists precisely so a paste survives an
-- outage, and it can only re-run over text that was already stored. raw_text is NOT NULL and is
-- written before any model is consulted; extracted_json is NULL until an extraction succeeds.
-- extraction_source records WHICH path produced it (AI | FALLBACK) so a degraded analysis is
-- labelled rather than disguised as a real one (the same reasoning behind degraded_reason in
-- SPEC.md B0-52).
--
-- creator_profile_id IS A creator_profiles.id, NOT A users.id (SPEC.md 0.8). This table follows
-- CreatorAgentPreferences/CreatorMetric, not Collaboration.creatorId (which is a user id).
-- Callers resolve with creatorProfileRepository.findByUserId(userId) first. The FK below makes
-- that unambiguous and is the reason the column is not merely indexed.
--
-- collaboration_id IS NULLABLE AND HAS NO FK. A PASTED brief has no collaboration at all until
-- either the brief came from a platform campaign or a secure link is redeemed and a deal is
-- created (status SECURED). Adding an FK here would either force a NOT NULL the PASTED path
-- cannot satisfy, or cascade a collaboration delete into the creator's own brief history, which
-- is the creator's record and not the brand's to erase.
--
-- THE THREE _json COLUMNS ARE SNAPSHOTS, NOT LIVE VIEWS. extracted_json (BriefExtraction, 2.11),
-- risk_flags_json (List<RiskFlag>, 5.2) and quote_json (PackageQuote, 4.3) are frozen at the
-- moment of analysis. The rate model, the risk rules and the creator's own floors all move; a
-- brief the creator read last week must keep showing what she was actually shown, not what the
-- current code would compute today.
-- INFO BARRIER (SPEC.md 0.3, 3.8): this table is the CREATOR's own data and is never
-- brand-readable, so quote_json MAY contain her floor. PackageQuote carries floor_total and
-- floor_total_value (plus anchor, range_min/range_max, provenance) and the quote is stored whole
-- and deliberately: reopening a brief must show the creator the same numbers she was shown.
-- The strip is enforced at the ONE boundary where any of this becomes brand-visible -- Phase B1's
-- createSecureLink builds a SEPARATE package_json for the link and removes floor_total, anchor,
-- range_* and provenance from it. That strip is MANDATORY; nothing here has pre-cleaned the data.
--
-- status is the lifecycle NEW -> ANALYZED -> DRAFTED -> SECURED, with DISMISSED reachable from
-- any of them. VARCHAR(16) not a MySQL ENUM: the entity maps it @Enumerated(EnumType.STRING) and
-- adding a value to a Java enum must not require a migration (V6's ENUM columns are the older
-- pattern and are not repeated in any V2026* table).
--
-- The index is (creator_profile_id, created_at DESC): the only list query is
-- findByCreatorProfileIdOrderByCreatedAtDesc -- "my recent briefs", newest first, paged.
--
-- No CHAR(n) (SPEC.md 0.5) -- ddl-auto=validate rejects CHAR against @Column(length=N).
CREATE TABLE creator_briefs (
    id                      VARCHAR(26)  NOT NULL,
    creator_profile_id      VARCHAR(26)  NOT NULL,
    source                  VARCHAR(16)  NOT NULL,            -- PASTED | PLATFORM
    collaboration_id        VARCHAR(26)  NULL,                -- set when source = PLATFORM or after secure-link redemption
    raw_text                TEXT         NOT NULL,
    brand_name_guess        VARCHAR(200) NULL,
    extracted_json          TEXT         NULL,                -- BriefExtraction JSON (2.11)
    risk_flags_json         TEXT         NULL,                -- List<RiskFlag> JSON (5.2)
    quote_json              TEXT         NULL,                -- PackageQuote JSON (4.3)
    status                  VARCHAR(16)  NOT NULL,            -- NEW | ANALYZED | DRAFTED | SECURED | DISMISSED
    extraction_source       VARCHAR(16)  NULL,                -- AI | FALLBACK
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_creator_briefs_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_creator_briefs_creator_created (creator_profile_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
