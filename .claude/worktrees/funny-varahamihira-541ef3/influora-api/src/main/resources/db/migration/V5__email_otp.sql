CREATE TABLE email_otp_challenges (
  id          VARCHAR(26) PRIMARY KEY,
  email       VARCHAR(255) NOT NULL,
  otp_hash    VARCHAR(64) NOT NULL,
  expires_at  TIMESTAMP NOT NULL,
  verified    BOOLEAN NOT NULL DEFAULT FALSE,
  attempts    INT NOT NULL DEFAULT 0,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_email_otp_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
