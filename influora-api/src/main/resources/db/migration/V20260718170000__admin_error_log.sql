-- Admin error-log console (errorApi in src/admin/services/api-contracts.ts, lines 654-671).
-- Backing store for handled *server* errors. Populated best-effort by GlobalExceptionHandler's
-- catch-all 500 handler (com.influora.common.GlobalExceptionHandler.handleGeneric) inside its own
-- REQUIRES_NEW transaction, so a logging failure can never break the request being handled and is
-- never entangled in the failed request's rolled-back transaction.

CREATE TABLE error_log (
  id              VARCHAR(26) PRIMARY KEY,             -- ULID
  severity        ENUM('ERROR','WARN','CRITICAL') NOT NULL DEFAULT 'ERROR',
  exception_class VARCHAR(255) NULL,                   -- e.g. java.lang.NullPointerException
  message         TEXT NULL,                           -- exception message, truncated in service
  endpoint        VARCHAR(512) NULL,                   -- request URI, e.g. /api/v1/admin/brands
  http_method     VARCHAR(16) NULL,                    -- GET/POST/...
  status_code     INT NULL,                            -- HTTP status the handler returned (500)
  user_id         VARCHAR(26) NULL,                    -- best-effort principal id; NO FK on purpose
  stack_trace     TEXT NULL,                           -- truncated stack summary
  resolved        BOOLEAN NOT NULL DEFAULT FALSE,
  resolved_by     VARCHAR(26) NULL,                    -- admin_users.id who resolved; NO FK on purpose
  resolved_at     TIMESTAMP(3) NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_error_log_created (created_at),
  INDEX idx_error_log_resolved (resolved),
  INDEX idx_error_log_endpoint (endpoint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Deliberately NO foreign keys on user_id / resolved_by: this table is a best-effort observability
-- sink written from an exception path. A dangling/unknown id (or a null) must never cause the
-- INSERT to fail and swallow the error record we are trying to capture.
