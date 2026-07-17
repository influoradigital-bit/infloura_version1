CREATE TABLE brand_ai_credits (
  workspace_id      VARCHAR(26) PRIMARY KEY,                 -- FK workspaces(id) — 1:1, PK is the FK
  credits_remaining INT NOT NULL DEFAULT 100,
  monthly_allotment INT NOT NULL DEFAULT 100,                -- bumps to 150 after first funded campaign (loyalty)
  cycle_start       DATE NOT NULL,
  unlimited_until   TIMESTAMP NULL,                          -- = campaign_end + 3d when funded (escrow event)
  last_reset        DATE NOT NULL,
  first_campaign_at TIMESTAMP NULL,                          -- set once; gates the 150 loyalty bump
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_credits_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Idempotency + audit ledger for /internal/meera/* executors.
CREATE TABLE meera_tool_calls (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (tenant isolation)
  conversation_id   VARCHAR(26) NULL,                        -- FK ai_conversations(id)
  tool_name         ENUM('create_campaign','request_payment','confirm_launch',
                    'show_creators','calculate_budget') NOT NULL,
  idempotency_key   VARCHAR(64) NOT NULL,                    -- supplied by Python per tool_use id
  status            ENUM('RECEIVED','EXECUTED','REJECTED','FAILED') NOT NULL DEFAULT 'RECEIVED',
  request_digest    VARCHAR(128) NULL,                       -- sha256 of request args (replay-mismatch detect)
  result_ref_type   ENUM('CAMPAIGN','ESCROW_HOLD','MILESTONE','INTENT','NONE') NOT NULL DEFAULT 'NONE',
  result_ref_id     VARCHAR(26) NULL,                        -- the real row Spring produced
  server_amount     DECIMAL(12,2) NULL,                      -- amount Spring RE-DERIVED (never from AI)
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_tool_idem (idempotency_key),
  INDEX idx_tool_workspace (workspace_id),
  INDEX idx_tool_conversation (conversation_id),
  CONSTRAINT fk_tool_workspace    FOREIGN KEY (workspace_id)    REFERENCES workspaces(id),
  CONSTRAINT fk_tool_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
