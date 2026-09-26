-- V75 -- content_topics: the creator-facing "today's topics" catalogue behind Meera's
-- get_todays_topics tool (T-CONTENT-TOPICS).
--
-- Rows are typed in BY HAND over a SQL client -- there is deliberately no admin write UI for this
-- table, only a read-only check (GET /admin/content-topics/preview). Because nothing screens a
-- hand-typed row on the way in, nothing here enforces safety either: ContentTopicService screens
-- every title/angle through TrendHeadlineScreener on the way OUT, at READ time, and drops
-- anything unsafe rather than trusting whoever ran the INSERT (F-0786: the drop is logged by id
-- and rejection category only, never the text). See ContentTopicService's class javadoc and
-- wiki/decisions/2026-09-18-trend-headline-screening.md's fail-closed rule, applied here at read
-- time instead of ingest time because this table has no ingest job to apply it at.
--
-- category is a free-text creator category, matched case-insensitively against
-- creator_profiles.categories_json, OR the literal 'ALL', which every creator matches regardless
-- of her own categories (and is also what a creator with NO stored categories falls back to).
--
-- angles is plain text, ONE ANGLE PER LINE -- deliberately not JSON, so a row can be typed
-- straight into a text column over a SQL client without escaping a JSON array by hand.
--
-- status starts at 'DRAFT' so a row typed in mid-edit is never accidentally servable; only
-- 'APPROVED' rows within [live_from, live_until] (inclusive both ends) are ever read back by
-- ContentTopicRepository#findServable.
CREATE TABLE content_topics (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  category      VARCHAR(80)  NOT NULL,
  title         VARCHAR(200) NOT NULL,
  angles        TEXT         NOT NULL,
  live_from     DATE         NOT NULL,
  live_until    DATE         NOT NULL,
  region        VARCHAR(40)  NOT NULL DEFAULT 'India',
  source_note   VARCHAR(255) NULL,
  sensitivity   VARCHAR(255) NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
  created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  INDEX idx_content_topics_servable (status, live_from, live_until),
  INDEX idx_content_topics_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
