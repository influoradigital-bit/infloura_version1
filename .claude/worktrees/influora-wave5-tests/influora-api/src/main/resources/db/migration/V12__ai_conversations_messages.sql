CREATE TABLE ai_conversations (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (tenant key — Guardrail 4)
  started_by        VARCHAR(26) NOT NULL,                    -- FK users(id)
  status            ENUM('ACTIVE','CAMPAIGN_CREATED','DORMANT','PAUSED_CREDITS') NOT NULL DEFAULT 'ACTIVE',
  title             VARCHAR(200) NULL,                       -- derived from first intent
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_message_at   TIMESTAMP NULL,
  UNIQUE KEY uq_conv_active (workspace_id, status),          -- at most one ACTIVE per workspace (partial semantics enforced in service)
  INDEX idx_conv_workspace (workspace_id),
  CONSTRAINT fk_conv_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_conv_user      FOREIGN KEY (started_by)   REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_messages (
  id                VARCHAR(26) PRIMARY KEY,
  conversation_id   VARCHAR(26) NOT NULL,                    -- FK ai_conversations(id)
  role              ENUM('USER','ASSISTANT','SYSTEM','TOOL') NOT NULL,
  content           TEXT,
  metadata          JSON,                                    -- {prompt_version, tool_use[], actions[], token_usage}
  credits_charged   INT NOT NULL DEFAULT 0,                  -- 1 per exchange, 10 for analysis (PRD §7)
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_msg_conversation (conversation_id, created_at),
  CONSTRAINT fk_msg_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
