-- T-FESTIVALBOX-0905 — public enquiry capture for the Festival Box landing page (/festival-box).
--
-- One row per submission from the public page's enquiry form. The page carries a Brand/Creator
-- toggle, so a row is either a brand asking to sponsor a slot (tier + product + budget context)
-- or a creator applying to the roster (handle + followers). `type` decides which half of the
-- row is meaningful; the other half stays NULL rather than being coerced to ''/0, so admin can
-- tell "creator did not give a follower count" apart from "creator has 0 followers"
-- (F-0259/F-0260 discipline, same as external_creators).
--
-- NOT a lead-scoring table and NOT an account: an enquiry is pre-signup: the submitter has no
-- User row, no workspace, and no JWT. `email` is therefore NOT unique — the same brand may
-- enquire for two editions, and forcing a unique key would make the second submission a silent
-- 409 on a public marketing form.
--
-- source_ip_hash is a salted hash, never the raw address: it exists only to let
-- FestivalEnquiryService throttle a flood from one origin, which does not require the ability
-- to re-identify the origin later.
--
-- DIALECT: MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect; docker-compose -> mysql:8.0).
-- VARCHAR(26) ids (ULID) + ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci,
-- matching the newest V2026* CREATE TABLE migrations.
CREATE TABLE festival_enquiries (
    id                  VARCHAR(26)   NOT NULL,
    type                VARCHAR(16)   NOT NULL,                    -- BRAND | CREATOR
    status              VARCHAR(16)   NOT NULL DEFAULT 'NEW',      -- NEW | CONTACTED | QUALIFIED | WON | LOST
    edition             VARCHAR(64)   NOT NULL,                    -- e.g. 'MUMBAI_FESTIVE_2026' — which edition was on the page

    -- Always present (both sides of the toggle ask for these three).
    name                VARCHAR(120)  NOT NULL,
    email               VARCHAR(255)  NOT NULL,
    phone               VARCHAR(32)   NULL,

    -- BRAND half — NULL on a CREATOR row.
    company             VARCHAR(160)  NULL,
    website             VARCHAR(500)  NULL,
    tier                VARCHAR(16)   NULL,                        -- GIFTING | FEATURED | TITLE | UNDECIDED
    product_category    VARCHAR(120)  NULL,

    -- CREATOR half — NULL on a BRAND row.
    instagram_handle    VARCHAR(80)   NULL,                        -- lower-cased, no leading '@' — enforced app-side
    followers           BIGINT        NULL,
    city                VARCHAR(80)   NULL,

    message             VARCHAR(2000) NULL,

    -- Admin-side workflow columns. handled_by is an admin_users.id, deliberately not a FK:
    -- an admin row being deleted must never cascade away the enquiry history.
    admin_notes         VARCHAR(2000) NULL,
    handled_by          VARCHAR(26)   NULL,
    handled_at          TIMESTAMP     NULL,

    -- Provenance. utm_* let marketing tell which campaign produced the enquiry.
    utm_source          VARCHAR(120)  NULL,
    utm_medium          VARCHAR(120)  NULL,
    utm_campaign        VARCHAR(120)  NULL,
    source_ip_hash      VARCHAR(64)   NULL,
    user_agent          VARCHAR(300)  NULL,

    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_festival_enquiries_status (status),
    INDEX idx_festival_enquiries_type_status (type, status),
    INDEX idx_festival_enquiries_created (created_at),
    -- Throttle lookup: "how many rows from this origin since T" is the only query that reads
    -- source_ip_hash, and it always pairs it with created_at.
    INDEX idx_festival_enquiries_ip_created (source_ip_hash, created_at),
    INDEX idx_festival_enquiries_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
