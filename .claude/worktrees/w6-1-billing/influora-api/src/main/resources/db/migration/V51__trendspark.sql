-- Trend-Spark AI (T4, Vikram) — schema per wiki/architecture/trendspark-priya-schema-lock.md §1
-- Latest prior migration is V50; this is V51 (never renumber).

CREATE TABLE trends (
    id                VARCHAR(26)  NOT NULL,
    trend_text        VARCHAR(500) NOT NULL,
    source            JSON         NOT NULL,
    region            VARCHAR(8)   NOT NULL DEFAULT 'IN',
    detected_date     DATE         NOT NULL,
    peak_window_days  INT          NOT NULL,
    expires_at        DATETIME(6)  NOT NULL,
    themes            JSON         NOT NULL,
    campaign_type     VARCHAR(16)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_trends_expires (expires_at),
    INDEX idx_trends_campaign (campaign_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE brand_profiles
    ADD COLUMN theme_tags     JSON        NULL,
    ADD COLUMN last_posted_at DATETIME(6) NULL;

CREATE TABLE snapsby_catalog_video (
    id            VARCHAR(26)  NOT NULL,
    title         VARCHAR(300) NOT NULL,
    niche         VARCHAR(64)  NOT NULL,
    themes        JSON         NOT NULL,
    language      VARCHAR(16)  NOT NULL,
    price_inr     INT          NOT NULL,
    preview_url   VARCHAR(500) NULL,
    active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_catalog_niche (niche)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE nudge_log (
    id                 VARCHAR(26)  NOT NULL,
    workspace_id       VARCHAR(26)  NOT NULL,
    trend_id           VARCHAR(26)  NOT NULL,
    campaign_type      VARCHAR(16)  NOT NULL,
    match_score        INT          NOT NULL,
    mode               VARCHAR(16)  NOT NULL,
    video_ids          JSON         NULL,
    message            TEXT         NOT NULL,
    message_source     VARCHAR(16)  NOT NULL,
    shown_at           DATETIME(6)  NOT NULL,
    clicked_at         DATETIME(6)  NULL,
    purchased_at       DATETIME(6)  NULL,
    purchased_video_id VARCHAR(26)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_nudge_workspace (workspace_id),
    INDEX idx_nudge_trend (trend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed MVP catalog rows so catalog-match returns something real (fitness/apparel/skincare).
INSERT INTO snapsby_catalog_video
    (id, title, niche, themes, language, price_inr, preview_url, active, created_at)
VALUES
    ('01J0TSKV0000000000000001', 'High-Energy Gym Reel Template', 'fitness',
     JSON_ARRAY('strength', 'energy', 'discipline', 'power'), 'en', 1499,
     'https://snapsby.example.com/preview/gym-reel-01', 1, UTC_TIMESTAMP(6)),
    ('01J0TSKV0000000000000002', 'Festive Ethnic Wear Showcase', 'apparel',
     JSON_ARRAY('festive', 'tradition', 'elegance', 'celebration'), 'hi', 1999,
     'https://snapsby.example.com/preview/ethnic-showcase-01', 1, UTC_TIMESTAMP(6)),
    ('01J0TSKV0000000000000003', 'Glow-Up Skincare Routine Clip', 'skincare',
     JSON_ARRAY('beauty', 'glow', 'self-care', 'radiance'), 'en', 1299,
     'https://snapsby.example.com/preview/skincare-glow-01', 1, UTC_TIMESTAMP(6)),
    ('01J0TSKV0000000000000004', 'Victory Sports Hype Cut', 'sports',
     JSON_ARRAY('pride', 'victory', 'energy', 'strength'), 'en', 1799,
     'https://snapsby.example.com/preview/sports-hype-01', 1, UTC_TIMESTAMP(6));
