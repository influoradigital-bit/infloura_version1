-- F-0225 (status-blind-duplicate-guard) — `applied_at` on collaborations.
--
-- WHY THIS COLUMN EXISTS
-- ----------------------
-- Withdrawing an application sets the collaboration to CANCELLED; the row survives, and the
-- UNIQUE(campaign_id, creator_id) constraint means a re-application can never insert a second
-- one. The fix revives the CANCELLED row in place instead of 409-ing.
--
-- That creates a date problem. `created_at` is the moment the ROW was created and is mapped
-- straight to the creator-facing "Applied" date (CreatorApplicationMapper), which also drives
-- the sort on "My applications". A revived row would therefore tell a creator who withdrew in
-- June and re-applied in August that they applied in June, and sort it to the bottom of their
-- list — true about the row, false about the application.
--
-- Rather than making `created_at` mutable (which would silently change what that column means
-- for every other reader), the display date gets its own column. `created_at` keeps meaning
-- "row created"; `applied_at` means "the current application/invitation started".
--
-- DIALECT: MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect; docker-compose -> mysql:8.0).
-- The first version of this file used `ALTER COLUMN ... SET NOT NULL` and
-- `CREATE INDEX IF NOT EXISTS`, which are Postgres/MariaDB grammar and abort MySQL with
-- ERROR 1064 — Flyway would have failed and the app would not have booted. Every other
-- nullability change in this directory uses `MODIFY COLUMN` (V20260715180000,
-- V20260718130000, V39__widen_kyc_rejection_reason.sql, and others); this follows them.
-- The mvn gates could not have caught it: the F-0225 suites are Mockito/POJO tests that never
-- open a connection.
ALTER TABLE collaborations
    ADD COLUMN applied_at TIMESTAMP NULL;

-- BACKFILL: existing rows have applied exactly once, so applied_at = created_at is correct for
-- all of them, not merely a safe default.
--
-- `updated_at = updated_at` is NOT redundant and must not be removed. V6 declares that column
-- `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`, so MySQL rewrites
-- it on every row an UPDATE touches unless the statement assigns it explicitly. Without this
-- clause the backfill would stamp the deploy time onto every collaboration in the table —
-- irreversibly, since the true values are overwritten in place. `updated_at` is not cosmetic:
-- AdminCreatorService (~:693) reports it as the completion date of a COMPLETED collaboration,
-- under a comment promising it is "never fabricated", and DealService (~:1363) uses it as the
-- last-activity fallback for deals with no messages. Assigning a column its own value suppresses
-- the ON UPDATE clause in MySQL while leaving the stored value untouched.
UPDATE collaborations
SET applied_at = created_at,
    updated_at = updated_at
WHERE applied_at IS NULL;

-- DEFAULT CURRENT_TIMESTAMP matches how `created_at` is declared for this table (V6) and keeps
-- the column safe for any writer that does not set it explicitly. Deliberately no
-- ON UPDATE CURRENT_TIMESTAMP: applied_at moves only when Collaboration#revive moves it, never
-- on every save.
ALTER TABLE collaborations
    MODIFY COLUMN applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- The list query filters by (creator_id, source) and sorts on applied_at.
CREATE INDEX idx_collaborations_creator_applied_at
    ON collaborations (creator_id, applied_at DESC);
