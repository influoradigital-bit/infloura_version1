-- Trend-Spark LLM Recovery Tagger (Dev, n8n wiring) — provenance column.
-- Priya sign-off 2026-07-16: additive, defaulted, backfills existing rows to
-- KEYWORD (every pre-existing trend was tagged by the deterministic keyword
-- tagger). MySQL 8 adds a column-with-default INSTANT, no table rewrite.
--
-- theme_source records HOW a trend's tags were produced:
--   KEYWORD      - deterministic n8n theme-tagger matched directly.
--   AI_RECOVERED - the keyword tagger found nothing and the LLM Recovery Tagger
--                  (POST /internal/trendspark/tag) rescued it onto the closed vocab.
-- Closed vocab; mirrors com.influora.domain.enums.TrendThemeSource.
--
-- NOTE (deferred, tracked): a DB-level UNIQUE natural key for idempotency
-- (region + detected_date + normalized trend_text) is intentionally NOT added
-- here — it needs a one-time dedup cleanup of any legacy rows first, and a naive
-- UNIQUE(trend_text) is wrong (trends recur yearly). Within-run dedup in the n8n
-- pipeline covers the reported multi-source duplication for now.

ALTER TABLE trends
    ADD COLUMN theme_source VARCHAR(16) NOT NULL DEFAULT 'KEYWORD';
