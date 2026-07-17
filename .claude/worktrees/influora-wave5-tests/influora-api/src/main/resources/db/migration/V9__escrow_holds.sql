CREATE TABLE escrow_holds (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) — brand paying
  collaboration_id  VARCHAR(26) NULL,                        -- FK collaborations(id) (null for campaign-level pool)
  campaign_id       VARCHAR(26) NULL,                        -- FK campaigns(id) — funds a whole campaign pool
  milestone_id      VARCHAR(26) NULL,                        -- FK payment_milestones(id) (V10, FK added there)
  amount            DECIMAL(14,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  status            ENUM('PENDING','FUNDED','RELEASED','REFUNDED','FROZEN') NOT NULL DEFAULT 'PENDING',
  hold_txn_id       VARCHAR(26) NULL,                        -- FK wallet_transactions(id) — the DEBIT leg
  release_txn_id    VARCHAR(26) NULL,                        -- FK wallet_transactions(id) — set on release
  idempotency_key   VARCHAR(64) NOT NULL,
  funded_at         TIMESTAMP NULL,
  released_at       TIMESTAMP NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_escrow_idem (idempotency_key),
  INDEX idx_escrow_workspace (workspace_id),
  INDEX idx_escrow_campaign (campaign_id),
  INDEX idx_escrow_status (status),
  CONSTRAINT fk_escrow_workspace  FOREIGN KEY (workspace_id)     REFERENCES workspaces(id),
  CONSTRAINT fk_escrow_collab     FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_escrow_campaign   FOREIGN KEY (campaign_id)      REFERENCES campaigns(id),
  CONSTRAINT fk_escrow_holdtxn    FOREIGN KEY (hold_txn_id)      REFERENCES wallet_transactions(id),
  CONSTRAINT fk_escrow_releasetxn FOREIGN KEY (release_txn_id)   REFERENCES wallet_transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
