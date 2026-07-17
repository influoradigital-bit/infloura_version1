-- Domain E / Phase 4 support: append-only audit trail + generic cross-tool idempotency store.
--
-- audit_log is written by AuditLogService for every money-affecting tool call (RequestPaymentExecutor,
-- ConfirmLaunchExecutor), every rejected/forbidden tool-call (ToolCallValidator), and every internal
-- auth rejection (InternalServiceTokenFilter / OnBehalfAuthResolver). No UPDATE/DELETE grant is issued
-- to the application user for this table in any live environment — inserts only, by application
-- convention (MySQL role/grant management itself is an ops-side task, not expressible in Flyway DDL
-- without knowing the live DB username; enforced procedurally until then).
CREATE TABLE audit_log (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NULL,                        -- FK workspaces(id); NULL for pre-auth rejections
  actor_type        VARCHAR(32) NOT NULL,                    -- MEERA_AI | HUMAN | SYSTEM | SERVICE
  actor_id          VARCHAR(64) NULL,                        -- userId, service-token subject, or null
  event_type        VARCHAR(64) NOT NULL,                    -- e.g. TOOL_CALL_REJECTED, PAYMENT_REQUESTED
  tool_name         VARCHAR(32) NULL,                        -- MeeraToolName when applicable
  tool_tier         VARCHAR(16) NULL,                        -- R | D | C | FORBIDDEN
  outcome           VARCHAR(32) NOT NULL,                    -- ALLOWED | REJECTED | FAILED
  reason_code       VARCHAR(64) NULL,                        -- e.g. AMOUNT_MISMATCH, TENANT_MISMATCH
  idempotency_key   VARCHAR(64) NULL,
  server_amount     DECIMAL(12,2) NULL,                      -- amount Spring RE-DERIVED, never AI-supplied
  before_balance    DECIMAL(14,2) NULL,
  after_balance     DECIMAL(14,2) NULL,
  request_digest    VARCHAR(128) NULL,                       -- sha256 of the request args, tamper-evidence
  detail_json       JSON NULL,                                -- structured detail; no PII/secrets ever
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_audit_workspace (workspace_id),
  INDEX idx_audit_event_type (event_type),
  INDEX idx_audit_tool_name (tool_name),
  INDEX idx_audit_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Generic idempotency store for IdempotencyService.executeOnce(key, supplier). meera_tool_calls
-- already carries its own UNIQUE(idempotency_key) for the 5 Meera tools (V14); this table is the
-- shared, tool-agnostic version used by any future write path that needs the same insert-first
-- dedupe guarantee under concurrency (UNIQUE constraint is the arbiter, not application logic).
CREATE TABLE idempotency_keys (
  idempotency_key   VARCHAR(128) PRIMARY KEY,
  workspace_id      VARCHAR(26) NULL,
  scope             VARCHAR(64) NOT NULL,                    -- logical namespace, e.g. 'meera.tool_call'
  status            ENUM('IN_PROGRESS','COMPLETED','FAILED') NOT NULL DEFAULT 'IN_PROGRESS',
  result_digest     VARCHAR(128) NULL,                       -- sha256 of the stored result, for replay checks
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at      TIMESTAMP NULL,
  INDEX idx_idem_workspace (workspace_id),
  INDEX idx_idem_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
