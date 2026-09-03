-- T-ADMINMAIL-0903: admin custom email send. One row per CONFIRMED send (never per preview),
-- backing two of the five required abuse controls:
--
--   #4 audit trail  -- "who sent what to how many" must be answerable from the database after a
--                       restart, so this is a real table, not a log line.
--   #1 rate limit   -- influora.admin-custom-email.min-interval-minutes is enforced off this
--                       table's latest created_at (AdminCustomEmailService), not an in-memory
--                       counter a restart would clear, and applies across ALL admins (no
--                       admin_user_id filter on the lookback query).
--
-- subject/body_text/cta_* store the admin-AUTHORED template (tokens like {{first_name}}
-- unresolved) -- the source-of-truth record of what was sent, not any one recipient's
-- personalized copy. recipient_count/skipped_unsubscribed are the audience math at send time.

CREATE TABLE admin_email_campaigns (
  id                               VARCHAR(26) PRIMARY KEY,   -- ULID; also the API's campaignId
  admin_user_id                    VARCHAR(26) NOT NULL,
  subject                          VARCHAR(255) NOT NULL,
  body_text                        TEXT NOT NULL,
  cta_label                        VARCHAR(100) NULL,
  cta_url                          VARCHAR(2048) NULL,
  audience_user_type               VARCHAR(16) NOT NULL,      -- CREATOR | BRAND | ALL
  audience_only_verified           BOOLEAN NOT NULL DEFAULT FALSE,
  audience_registered_within_days  INT NULL,
  recipient_count                  INT NOT NULL,               -- audience size at send time (pre-unsubscribe)
  skipped_unsubscribed             INT NOT NULL,
  created_at                       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_admin_email_campaigns_created_at (created_at),
  CONSTRAINT fk_admin_email_campaigns_admin_user FOREIGN KEY (admin_user_id) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
