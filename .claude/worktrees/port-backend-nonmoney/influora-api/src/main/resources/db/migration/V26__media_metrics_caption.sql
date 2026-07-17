-- Wave C task C1 (wiki/tech/REMAINING_WORK_PLAN.md) -- adds a nullable caption text column to
-- media_metrics so BrandSafetyScoreService (C3) has post text to send to influora-ai's GARM/NLP
-- pipeline (C2).
--
-- [CTO RULING -- wiki/decisions/2026-07-06-brand-safety-caption-storage.md, LOCKED] Option A:
-- persist the caption during polling (MetricsPollingJob already fetches it in the
-- InstagramInsightsClient.getMedia response -- see MEDIA_FIELDS -- at zero extra API cost) rather
-- than live-fetching from Meta at scoring time. Captions are already-public Instagram content, so
-- storing them for brand-safety analysis is defensible, but per the ADR's binding privacy/retention
-- constraint: this column is for the internal BrandSafety pipeline ONLY, must never be exposed
-- through any brand-facing DTO (only the derived brand_safety_score/garm_flags may surface -- see
-- CreatorScore/AnalyticsDtos), and must be kept out of logs (same redaction discipline as
-- AuditLogService -- never log PII/raw content, only shapes/ids/counts). Retention follows the same
-- lifecycle as the rest of media_metrics -- no separate retention job introduced by this migration.
--
-- [CTO RULING -- wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same MySQL
-- translation discipline as V21/V25 -- ordinary ALTER TABLE on the existing InnoDB table, no
-- Postgres/TimescaleDB syntax.
--
-- [CHARSET] Captions are free-form user text and routinely contain emoji/non-Latin unicode -- TEXT
-- under utf8mb4 (matching the table's DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci set in
-- V21) is mandatory; utf8mb3/latin1 would silently corrupt or reject 4-byte characters.
ALTER TABLE media_metrics
  ADD COLUMN caption TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
    COMMENT 'Internal BrandSafety pipeline input only (Wave C). Never surface raw caption text via any brand-facing DTO/response -- only derived brand_safety_score/garm_flags may surface. Keep out of logs.'
    AFTER media_type;
