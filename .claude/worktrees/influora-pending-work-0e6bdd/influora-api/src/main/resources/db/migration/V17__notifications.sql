-- Domain B (07-NOTIFICATION-SYSTEM-SPEC.md): in-app notifications table.
CREATE TABLE notifications (
  id              VARCHAR(26) PRIMARY KEY,           -- ULID
  user_id         VARCHAR(26) NOT NULL,
  workspace_id    VARCHAR(26) NULL,                  -- nullable for user-level notifications
  event_type      VARCHAR(64) NOT NULL,              -- e.g., 'campaign.created'
  title           VARCHAR(255) NOT NULL,
  body            TEXT NULL,
  link            VARCHAR(512) NULL,                 -- deep link into app
  is_read         BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_notification_user_unread (user_id, is_read, created_at DESC),
  INDEX idx_notification_workspace (workspace_id),
  CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
