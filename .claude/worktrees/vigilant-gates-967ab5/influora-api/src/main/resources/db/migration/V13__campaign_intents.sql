CREATE TABLE campaign_intents (
  id                VARCHAR(26) PRIMARY KEY,
  conversation_id   VARCHAR(26) NOT NULL,                    -- FK ai_conversations(id)
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (scoping)
  campaign_type     ENUM('HYPE','DIRECT','REVIEW','STANDARD') NOT NULL,
  product_name      VARCHAR(255),
  product_url       VARCHAR(500),
  product_price     DECIMAL(12,2) NULL,                      -- basis for server-side budget re-derivation
  proposed_budget   DECIMAL(12,2) NULL,                      -- AI proposal — NOT authoritative
  creator_count     INT,
  location_filter   JSON,                                    -- ['Mumbai']
  status            ENUM('DRAFTING','READY','CONFIRMED','ABANDONED') NOT NULL DEFAULT 'DRAFTING',
  confirmed         BOOLEAN NOT NULL DEFAULT FALSE,
  campaign_id       VARCHAR(26) NULL,                        -- FK campaigns(id) — set on confirm_launch
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_intent_conversation (conversation_id),
  INDEX idx_intent_workspace (workspace_id),
  CONSTRAINT fk_intent_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id),
  CONSTRAINT fk_intent_workspace    FOREIGN KEY (workspace_id)    REFERENCES workspaces(id),
  CONSTRAINT fk_intent_campaign     FOREIGN KEY (campaign_id)     REFERENCES campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
