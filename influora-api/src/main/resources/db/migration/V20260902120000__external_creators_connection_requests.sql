-- T-CREATORCONNECT-0902 — Meta-sourced creators -> "Connect this creator" -> admin invite ->
-- brand notified on join (.proof-os/tasks/T-CREATORCONNECT-0902/TASKS.md).
--
-- external_creators: one row per Instagram handle Influora knows about but that is not (yet) a
-- real CreatorProfile. Sourced from the Meta Marketplace client (flagged, dark today), Business
-- Discovery lookups, or an admin bulk import. `ig_username` is the only field guaranteed present
-- on every row (Business Discovery/admin-import rows may never get enriched); everything else is
-- nullable and MUST stay null rather than be coerced to 0/"" when Meta has not returned it yet
-- (F-0259/F-0260 discipline — see ExternalCreatorResponse).
--
-- creator_connection_requests: a brand's ask to be introduced to one external creator. One row
-- per (workspace, external_creator) — the unique key below means a brand can only ever have ONE
-- live request per creator; ExternalCreatorService reuses that single row across DECLINED ->
-- re-request rather than inserting a second one, since the schema does not allow it.
--
-- DIALECT: MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect; docker-compose -> mysql:8.0).
-- VARCHAR(26) ids (ULID) + ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci,
-- matching the newest V2026* CREATE TABLE migrations (e.g. V20260721130000__creator_captions.sql,
-- V20260724120000__shipments.sql) rather than the older bare-VARCHAR/no-charset style.
CREATE TABLE external_creators (
    id                    VARCHAR(26)  NOT NULL,
    source                VARCHAR(32)  NOT NULL,   -- META_MARKETPLACE | BUSINESS_DISCOVERY | ADMIN_IMPORT
    ig_account_id         VARCHAR(64)  NULL,
    ig_username           VARCHAR(80)  NOT NULL,   -- lower-cased, no leading '@' — enforced app-side
    display_name          VARCHAR(100) NULL,
    bio                   TEXT         NULL,
    profile_picture_url   VARCHAR(500) NULL,
    followers             BIGINT       NULL,
    media_count           BIGINT       NULL,
    engagement_rate       DECIMAL(5, 2) NULL,
    country               VARCHAR(64)  NULL,
    categories            JSON         NULL,
    email                 VARCHAR(255) NULL,       -- admin-supplied only; Business Discovery never returns it
    status                VARCHAR(32)  NOT NULL DEFAULT 'UNVERIFIED', -- UNVERIFIED | INVITED | JOINED
    linked_creator_profile_id VARCHAR(26) NULL,
    invited_at            TIMESTAMP    NULL,
    joined_at             TIMESTAMP    NULL,
    last_synced_at        TIMESTAMP    NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_external_creators_username (ig_username),
    UNIQUE KEY uk_external_creators_ig_account (ig_account_id),
    INDEX idx_external_creators_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE creator_connection_requests (
    id                    VARCHAR(26)   NOT NULL,
    workspace_id          VARCHAR(26)   NOT NULL,
    requested_by_user_id  VARCHAR(26)   NOT NULL,
    external_creator_id   VARCHAR(26)   NOT NULL,
    message               VARCHAR(1000) NULL,
    status                VARCHAR(32)   NOT NULL DEFAULT 'PENDING', -- PENDING | CONTACTED | JOINED | DECLINED
    admin_notes           VARCHAR(1000) NULL,
    handled_by            VARCHAR(26)   NULL,
    handled_at            TIMESTAMP     NULL,
    joined_notified_at    TIMESTAMP     NULL,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ccr_workspace_creator (workspace_id, external_creator_id),
    KEY ix_ccr_status (status),
    CONSTRAINT fk_ccr_external_creator FOREIGN KEY (external_creator_id) REFERENCES external_creators (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
