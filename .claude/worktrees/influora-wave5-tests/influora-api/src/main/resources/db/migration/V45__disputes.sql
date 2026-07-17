-- Task #34 (V5) — collaboration-scoped disputes (CEO §1.3 interim policy).
-- One active dispute per collaboration enforced at the service layer (OPEN / UNDER_REVIEW).
-- Unreleased escrow freezes on open via EscrowService (FUNDED -> FROZEN); no auto-refund.

CREATE TABLE disputes (
  id                    VARCHAR(26) PRIMARY KEY,
  collaboration_id      VARCHAR(26) NOT NULL,
  opened_by_type        ENUM('CREATOR','BRAND') NOT NULL,
  opened_by_user_id     VARCHAR(26) NOT NULL,
  reason                TEXT NOT NULL,
  status                ENUM(
                          'OPEN',
                          'UNDER_REVIEW',
                          'RESOLVED_BRAND',
                          'RESOLVED_CREATOR',
                          'RESOLVED_SPLIT'
                        ) NOT NULL DEFAULT 'OPEN',
  resolved_by_admin_id  VARCHAR(26) NULL,
  resolution_notes      TEXT NULL,
  resolved_at           TIMESTAMP NULL,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_dispute_collaboration (collaboration_id),
  INDEX idx_dispute_status (status),
  CONSTRAINT fk_dispute_collaboration FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_dispute_opened_by FOREIGN KEY (opened_by_user_id) REFERENCES users(id),
  CONSTRAINT fk_dispute_resolved_by FOREIGN KEY (resolved_by_admin_id) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
