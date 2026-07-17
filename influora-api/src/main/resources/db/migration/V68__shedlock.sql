-- H-13 (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md, CTO-approved): distributed scheduler
-- locking table for net.javacrumbs.shedlock's JdbcTemplateLockProvider — exact shape required by
-- that library (do not rename columns). One row per lock name; a lock is "held" while
-- lock_until is in the future.
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL,
  lock_until TIMESTAMP(3) NOT NULL,
  locked_at  TIMESTAMP(3) NOT NULL,
  locked_by  VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
