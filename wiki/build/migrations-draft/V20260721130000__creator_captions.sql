-- DRAFT — reviewable artifact only, NOT applied. Creator AI Co-pilot Tier-1
-- (wiki/build/creator-copilot-priya-review-r1.md pre-condition 5 / build spec §4).
--
-- Persists captions fetched via the existing InstagramMetricsFetcher so the nightly
-- CreatorThemeTaggingJob (be-services-plan §2.6) tags from cache instead of re-hitting the Graph
-- API live. Table name is creator_captions (SQL name Vikram's entity — CreatorCaptionCache,
-- be-services-plan §2.2 — is built against, per his explicit note that the spec's architecture
-- diagram's "creator_caption_cache" label is the entity's descriptive class name only, not the
-- table name). caption_text/tagged_themes are attacker-influenced (creator-authored captions) —
-- P0 security note (build spec §5): any consumer of caption_text MUST wrap_untrusted() it before
-- any model call; this migration only stores it, does not gate that control.
CREATE TABLE creator_captions (
    id                 VARCHAR(26)  NOT NULL,
    creator_profile_id VARCHAR(26)  NOT NULL,
    ig_media_id        VARCHAR(64)  NOT NULL,
    caption_text       TEXT         NULL,
    tagged_themes      JSON         NULL,
    tag_status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',   -- CaptionTagStatus: PENDING|TAGGED|FAILED
    posted_at          TIMESTAMP    NULL,
    tagged_at          TIMESTAMP    NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_creator_captions_media (creator_profile_id, ig_media_id),
    INDEX idx_creator_captions_status (tag_status),
    CONSTRAINT fk_creator_captions_profile FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
