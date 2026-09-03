-- V73 — Meera for Creators, Phase A (T-MEERA-CREATOR-PHASE-A, SPEC.md 1.2).
--
-- One row per creator: rate floors Meera must never quote under, brand/category filters, the
-- automation level the creator has granted Meera, language/tone, working hours, and the DPDP
-- consent timestamp (A6) that gates the first Meera turn. Rows are created lazily on first
-- GET /creator/agent-preferences (CreatorAgentPreferencesService.getOrCreatePreferences), not
-- backfilled here — every existing creator simply has no row until they first touch Meera.

CREATE TABLE creator_agent_preferences (
    id                      VARCHAR(26)     PRIMARY KEY,
    creator_id              VARCHAR(26)     NOT NULL UNIQUE,
    reel_floor              DECIMAL(12, 2)  NULL,
    story_set_floor         DECIMAL(12, 2)  NULL,
    post_floor              DECIMAL(12, 2)  NULL,
    excluded_categories     TEXT            NULL,
    blocked_brands          TEXT            NULL,
    approval_level          INTEGER         NOT NULL DEFAULT 0,
    creator_language        VARCHAR(10)     NOT NULL DEFAULT 'hi-IN',
    brand_tone              VARCHAR(16)     NOT NULL DEFAULT 'FRIENDLY',
    working_hours_start     INTEGER         NULL,
    working_hours_end       INTEGER         NULL,
    working_days            TEXT            NULL,
    weekly_sponsored_limit  INTEGER         NULL,
    represented             BOOLEAN         NOT NULL DEFAULT false,
    agency_name             VARCHAR(200)    NULL,
    consent_accepted_at     TIMESTAMP       NULL,
    created_at              TIMESTAMP       NOT NULL,
    updated_at              TIMESTAMP       NOT NULL,

    CONSTRAINT fk_creator_agent_prefs_creator FOREIGN KEY (creator_id)
        REFERENCES creator_profiles (id) ON DELETE CASCADE
);

CREATE INDEX idx_creator_agent_prefs_creator ON creator_agent_preferences (creator_id);
