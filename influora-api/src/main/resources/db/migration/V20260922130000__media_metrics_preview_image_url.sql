-- Post thumbnails for the Content Performance panel (2026-09-22).
--
-- preview_image_url is the post's cover image as a signed Instagram/Facebook CDN link. It is
-- hotlinked, never copied: the CDN answers cross-origin with no cookies. The link is SIGNED and
-- EXPIRES (~4 days, the oe= parameter), so MetricsPollingJob writes the fresh link on every poll's
-- new row and readers take it from the latest snapshot per media_id only.
--
-- 2048, not permalink's 500: a real link measured 521 characters, which would fail every insert
-- with "Data too long". Nullable with no default: rows polled before this column existed, posts
-- with no image, and URLs that fail MediaMetricMapper's host allow-list all stay NULL.

ALTER TABLE media_metrics ADD COLUMN preview_image_url VARCHAR(2048) NULL;
