-- V74 — Meera for Creators, Phase A (T-MEERA-CREATOR-PHASE-A, SPEC.md 1.4).
--
-- DPDP compliance index over `ai_conversations` for CREATOR-audience Meera turns: list/export/
-- delete need a creator-scoped, fast-sortable view without adding a nullable creator_id to the
-- shared (BRAND + CREATOR) ai_conversations table. One row per creator conversation, updated
-- alongside the underlying ai_conversations/ai_messages writes; conversation_id is UNIQUE because
-- it is a 1:1 index over one ai_conversations row, never a fan-out.

CREATE TABLE meera_creator_conversations (
    id                  VARCHAR(26)     PRIMARY KEY,
    creator_id          VARCHAR(26)     NOT NULL,
    conversation_id     VARCHAR(26)     NOT NULL UNIQUE,
    started_at          TIMESTAMP       NOT NULL,
    last_message_at     TIMESTAMP       NOT NULL,
    message_count       INTEGER         NOT NULL DEFAULT 0,

    CONSTRAINT fk_meera_creator_conv_creator FOREIGN KEY (creator_id)
        REFERENCES creator_profiles (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_meera_creator_conv_creator ON meera_creator_conversations (creator_id);
CREATE INDEX idx_meera_creator_conv_last_msg ON meera_creator_conversations (creator_id, last_message_at DESC);
