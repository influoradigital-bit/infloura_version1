-- Meera intelligence v1, slice 2 (spec 8.2, 2026-09-25): recommendation -> outcome record.
--
-- One row per thing Meera (or the challenge) told a creator to post: a plan_my_week line, a
-- challenge day, or a script card. Written by the server only -- the challenge rows inside
-- CreatorChallengeService.start, the plan/script rows from the assistant write-back's
-- metadata.recommendations (parsed by influora-ai with deterministic parsers, never a model tool).
-- The outcome columns are filled lazily on read by CreatorRecommendationOutcomeService, from the
-- creator's own media_metrics readings, and are FROZEN once status is SETTLED, MISSED or NO_OUTCOME.
--
-- Statuses (CreatorRecommendationStatus): OPEN -> MATCHED -> SETTLED, OPEN -> MISSED, and
-- MATCHED -> NO_OUTCOME when the matched post never settles by match_until + 48 h settling
-- period + a 7-day margin (account switched, post deleted, post dropped out of the 25-post poll).
-- An OPEN row whose matching window lies before the evaluator's 180-day scan floor also becomes
-- NO_OUTCOME rather than MISSED: its posts were not scanned, so "missed" would be a guess.
-- NO_OUTCOME is 10 characters and fits status VARCHAR(12).
--
-- version is the JPA @Version (optimistic lock): every outcome write is UPDATE ... WHERE
-- version = ?, so two concurrent evaluations cannot both write a row, and a frozen row is never
-- rewritten by a slower evaluation that read it before it froze. BIGINT NOT NULL DEFAULT 0 <->
-- Long, exactly.
--
-- outcome_ig_account_id is the Instagram account whose posts decided the row (set with MATCHED,
-- MISSED or NO_OUTCOME; a MATCHED row keeps the account it was matched on). For a creator who
-- has ever connected more than one account, followed_recommendations counts only the rows decided
-- on the account connected now (Kabir L-3), the same account rule the profile uses. VARCHAR(64),
-- like the other Instagram account id columns.
--
-- Types match the JPA entity exactly (INT <-> Integer, never TINYINT; BIGINT <-> Long;
-- BOOLEAN <-> Boolean; DATE <-> LocalDate; TIME <-> LocalTime; DATETIME(6) <-> Instant):
-- ddl-auto=validate on real MySQL rejects a mismatch that H2 and Mockito let through.
--
-- uk_creator_rec_source makes a replayed write-back (or a replayed challenge start) a no-op.
-- uk_creator_rec_media makes "one post fills at most one recommendation" a database guarantee;
-- MySQL allows any number of NULLs under UNIQUE, so unmatched rows never collide (the same
-- reasoning as creator_challenges.active_key).
--
-- window_label is VARCHAR(24), not the spec's VARCHAR(12): plan_my_week's labels are
-- CreatorPostRules.windowLabel values such as "weekday afternoon" (17 chars); 12 would truncate
-- them. Challenge rows carry the daypart only ("evening").
--
-- DPDP (Kabir M-1, L-2):
--  * Conversation delete: CreatorAgentConversationService.deleteConversation deletes the
--    conversation's plan/script rows explicitly, and fk_creator_rec_conversation (ON DELETE
--    CASCADE to meera_creator_conversations.conversation_id, the creator's own conversation index,
--    VARCHAR(26) utf8mb4_unicode_ci like this column) makes that complete even for a row inserted
--    by a write-back that raced the delete: once the conversation row is gone such a late insert
--    fails the FK instead of leaving an orphan, and the writer's after-commit guard swallows it.
--    conversation_id is NULL for challenge rows, which the FK allows.
--  * Account delete: DELETE /me/account is a SOFT delete of users (the row stays, so nothing
--    cascades from it) and never deletes creator_profiles, so fk_creator_rec_profile's cascade
--    does NOT cover account deletion. AccountController deletes every row of the creator
--    explicitly (CreatorRecommendationWriter.deleteAllForCreator). The profile cascade only
--    matters if a creator_profiles row is ever hard-deleted.
--  * Export: a conversation's rows are included in its DPDP export
--    (GET /creator/agent-preferences/conversations/{id}/export). Challenge rows have no
--    conversation and no export surface (there is no account-level data export).
CREATE TABLE creator_recommendations (
    id                      VARCHAR(26)  NOT NULL,
    creator_user_id         VARCHAR(26)  NOT NULL,
    creator_profile_id      VARCHAR(26)  NOT NULL,
    source                  VARCHAR(16)  NOT NULL,   -- PLAN_MY_WEEK | CHALLENGE | SCRIPT_CARD
    source_ref              VARCHAR(64)  NOT NULL,   -- turn messageId:line_index | challengeId:dayIndex
    conversation_id         VARCHAR(26)  NULL,       -- for the DPDP delete with the conversation
    recommended_for         DATE         NULL,       -- IST day; NULL for a script
    match_until             DATE         NOT NULL,   -- first IST date that no longer matches (exclusive)
    post_type               VARCHAR(12)  NOT NULL,   -- REEL | CAROUSEL | POST (ChallengeDayType names)
    window_label            VARCHAR(24)  NULL,
    window_from             TIME         NULL,
    window_to               TIME         NULL,
    structure_name          VARCHAR(80)  NULL,
    hook_template           VARCHAR(80)  NULL,
    topic                   VARCHAR(160) NULL,       -- neutralised + SensitiveTextRedactor
    festival                VARCHAR(80)  NULL,
    prompt_version          VARCHAR(32)  NULL,
    knowledge_version       VARCHAR(32)  NULL,
    status                  VARCHAR(12)  NOT NULL,   -- OPEN | MATCHED | SETTLED | MISSED | NO_OUTCOME
    matched_media_id        VARCHAR(50)  NULL,
    outcome_ig_account_id   VARCHAR(64)  NULL,       -- the account whose posts decided the row
    matched_type            BOOLEAN      NULL,
    matched_window          BOOLEAN      NULL,
    reach                   BIGINT       NULL,
    engagement              BIGINT       NULL,
    baseline_median_reach   BIGINT       NULL,
    baseline_sample_size    INT          NULL,
    reach_vs_baseline_pct   INT          NULL,
    settled_at              DATETIME(6)  NULL,
    created_at              DATETIME(6)  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,   -- JPA @Version (optimistic lock)
    PRIMARY KEY (id),
    UNIQUE KEY uk_creator_rec_source (creator_profile_id, source, source_ref),
    UNIQUE KEY uk_creator_rec_media (creator_profile_id, matched_media_id),
    INDEX idx_creator_rec_open (creator_profile_id, status, recommended_for),
    INDEX idx_creator_rec_conversation (conversation_id),
    CONSTRAINT fk_creator_rec_profile FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    CONSTRAINT fk_creator_rec_conversation FOREIGN KEY (conversation_id) REFERENCES meera_creator_conversations(conversation_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
