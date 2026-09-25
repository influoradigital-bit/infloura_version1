-- Goal memory (Meera intelligence v1, 2026-09-25). Creator-declared, saved ONLY by a chip the
-- creator taps (PUT /creator/agent-preferences/content-goal), never by Meera. NULL = not told.
-- Codes are app-layer enums (VARCHAR, not DB ENUM), matching creator_challenges.status; the
-- service rejects an unknown code with a 400 before anything is written.
--
-- Its own route, not the full-replace PUT /creator/agent-preferences, so saving the rest of the
-- settings page never wipes these four columns (same reasoning as V76 phone_model).
ALTER TABLE creator_agent_preferences
    ADD COLUMN content_goal      VARCHAR(20) NULL,   -- GROW_FOLLOWERS | BRAND_DEALS | SELL_PRODUCT
    ADD COLUMN weekly_time_band  VARCHAR(12) NULL,   -- UNDER_2H | H2_TO_5 | OVER_5H
    ADD COLUMN equipment         TEXT        NULL,   -- JSON array of codes: PHONE_ONLY, TRIPOD, EXTERNAL_MIC, RING_LIGHT, GIMBAL
    ADD COLUMN content_dislikes  TEXT        NULL;   -- JSON array of codes: NO_FACE, NO_VOICE, NO_DANCING, NO_TRENDING_AUDIO, NO_OUTDOOR
