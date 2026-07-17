CREATE TABLE contracts (
  id                VARCHAR(26) PRIMARY KEY,
  collaboration_id  VARCHAR(26) NOT NULL,                    -- FK collaborations(id)
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (denormalized for scoping)
  version           INT NOT NULL DEFAULT 1,
  status            ENUM('DRAFT','PENDING_SIGNATURES','ACTIVE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'DRAFT',
  total_amount      DECIMAL(12,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  pdf_r2_key        VARCHAR(500) NULL,                       -- R2 object key (file bytes never in DB)
  terms             JSON NULL,                                -- exclusivity, usage rights, deliverable summary
  brand_signed_at   TIMESTAMP NULL,
  creator_signed_at TIMESTAMP NULL,
  effective_date    DATE NULL,
  expiration_date   DATE NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_contract_collab (collaboration_id),
  INDEX idx_contract_workspace (workspace_id),
  INDEX idx_contract_status (status),
  CONSTRAINT fk_contract_collab    FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_contract_workspace FOREIGN KEY (workspace_id)     REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_milestones (
  id                VARCHAR(26) PRIMARY KEY,
  contract_id       VARCHAR(26) NOT NULL,                    -- FK contracts(id)
  collaboration_id  VARCHAR(26) NOT NULL,                    -- FK collaborations(id) (denormalized)
  sequence_no       INT NOT NULL,                            -- 1,2,3 ordering
  description       VARCHAR(300),
  amount            DECIMAL(12,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  due_date          DATE NULL,
  status            ENUM('PENDING','FUNDED','RELEASED','REFUNDED','FROZEN') NOT NULL DEFAULT 'PENDING',
  escrow_hold_id    VARCHAR(26) NULL,                        -- FK escrow_holds(id)
  released_txn_id   VARCHAR(26) NULL,                        -- FK wallet_transactions(id)
  idempotency_key   VARCHAR(64) NULL,                        -- set when a release is executed
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_milestone_seq (contract_id, sequence_no),
  UNIQUE KEY uq_milestone_release_idem (idempotency_key),
  INDEX idx_milestone_contract (contract_id),
  INDEX idx_milestone_status (status),
  CONSTRAINT fk_milestone_contract FOREIGN KEY (contract_id)      REFERENCES contracts(id),
  CONSTRAINT fk_milestone_collab   FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_milestone_escrow   FOREIGN KEY (escrow_hold_id)   REFERENCES escrow_holds(id),
  CONSTRAINT fk_milestone_txn      FOREIGN KEY (released_txn_id)  REFERENCES wallet_transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Back-fill the deferred FK from V9 now that payment_milestones exists.
ALTER TABLE escrow_holds
  ADD CONSTRAINT fk_escrow_milestone FOREIGN KEY (milestone_id) REFERENCES payment_milestones(id);
