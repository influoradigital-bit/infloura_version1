-- Domain B (07-NOTIFICATION-SYSTEM-SPEC.md): email outbox + preferences tables.

CREATE TABLE email_outbox (
  id              VARCHAR(26) PRIMARY KEY,           -- ULID
  user_id         VARCHAR(26) NOT NULL,
  to_email        VARCHAR(255) NOT NULL,
  template_key    VARCHAR(64) NOT NULL,              -- e.g., 'creator.proposal_received'
  template_data   JSON NOT NULL,                     -- merge fields for MSG91 template
  status          ENUM('PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
  retry_count     TINYINT NOT NULL DEFAULT 0,
  next_retry_at   TIMESTAMP(3) NULL,
  sent_at         TIMESTAMP(3) NULL,
  error_message   VARCHAR(512) NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  idempotency_key VARCHAR(128) NULL,                 -- event_type:entity_id:user_id
  INDEX idx_email_outbox_pending (status, next_retry_at),
  INDEX idx_email_outbox_user (user_id),
  UNIQUE KEY uq_email_outbox_idempotency (idempotency_key),
  CONSTRAINT fk_email_outbox_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_preferences (
  id              VARCHAR(26) PRIMARY KEY,           -- ULID
  user_id         VARCHAR(26) NOT NULL,
  event_type      VARCHAR(64) NOT NULL,              -- e.g., 'campaign.created' or '*' for all
  unsubscribed    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_email_pref_user_event (user_id, event_type),
  CONSTRAINT fk_email_pref_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
