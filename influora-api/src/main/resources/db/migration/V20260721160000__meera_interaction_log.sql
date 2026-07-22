-- Meera flywheel logging (Phase 2 item 2.3 — wiki/ai-review/meera-label-to-moat-build-plan.md
-- §2.3, wiki/build/phase2-backend-design.md §3). One append-only table behind the
-- "options presented -> tapped -> draft -> funded" funnel + revision-reason capture. A DIFFERENT
-- table from creator_nudge_log (V20260721140000) -- that one is the Creator Co-pilot's own
-- flywheel, not reusable here.
--
-- event_type is a VARCHAR discriminator (not a DB ENUM), matching portfolio_events'/
-- creator_nudge_log's own convention -- a new event kind is an app-layer change, never a schema
-- migration. DRAFT_ABANDONED is deliberately NOT in the v1 enum (see
-- MeeraInteractionEventType's javadoc) -- CampaignIntent.abandon() has zero call sites today.
--
-- Deliberately NO FK on campaign_id or workspace_id (same reasoning portfolio_events/
-- creator_nudge_log-adjacent event-log tables in this codebase already use): (1) campaign_id is
-- legitimately NULL for pre-campaign events (OPTIONS_PRESENTED during initial chat, before any
-- campaign exists) -- a FK would need to be nullable anyway; (2) a blind FK risks the same
-- MySQL-8 collation-mismatch failure (SQL 3780) V20260721140000__creator_nudge_log.sql's own
-- comment documents for trend_id -> trends.id -- this table's writers span both AI-turn and
-- browser-tap contexts, application-level integrity via the writing service is the right trade
-- here, not a schema-level guarantee.
--
-- prompt_version is nullable (a deliberate departure from the original design sketch's NOT NULL):
-- OPTIONS_PRESENTED (Python-originated) and OPTION_TAPPED (browser-originated, if the frontend has
-- one to pass) can stamp a real influora-ai PROMPT_VERSION value; DRAFT_CREATED/DRAFT_FUNDED/
-- REVISION_REQUESTED are pure Java business-state transitions with no live AI-turn prompt-version
-- context available server-side today -- forcing a fabricated sentinel string into a NOT NULL
-- column would be worse data than an honest NULL.
--
-- Retention: 180 days (Priya's B7 ruling, phase2-priya-review.md) -- Rohan (CFO) signs the
-- storage-cost line; the purge JOB itself is a fast-follow, not a v1 blocker (early traffic will
-- not overflow this table in 180 days). No TTL enforced by this migration.

CREATE TABLE meera_interaction_log (
    id                 VARCHAR(26)  NOT NULL,
    workspace_id       VARCHAR(26)  NOT NULL,
    session_id         VARCHAR(64)  NULL,        -- opaque, Python-assembled session id (assembler.py cache_key_for) -- not a Spring FK
    event_type         VARCHAR(32)  NOT NULL,     -- OPTIONS_PRESENTED | OPTION_TAPPED | DRAFT_CREATED | DRAFT_FUNDED | REVISION_REQUESTED
    tool_name          VARCHAR(32)  NULL,         -- e.g. create_campaign, confirm_launch, present_options -- null when not tool-triggered
    recommended_flag   BOOLEAN      NULL,         -- for OPTION_TAPPED: was the tapped option Meera's recommended one?
    campaign_id        VARCHAR(26)  NULL,         -- nullable: OPTIONS_PRESENTED/REVISION_REQUESTED may precede a real campaign row
    revision_reason    TEXT         NULL,         -- redacted via SensitiveTextRedactor before insert -- never raw
    prompt_version     VARCHAR(32)  NULL,         -- see migration comment above -- NULL for pure Java business-state events
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Hot-path index for the eventual "flywheel funnel" read (options presented -> tapped -> draft ->
-- funded), not yet queried in this pass (write-only v1 per the build design).
CREATE INDEX idx_meera_interaction_workspace_time ON meera_interaction_log (workspace_id, created_at);
CREATE INDEX idx_meera_interaction_campaign ON meera_interaction_log (campaign_id);
