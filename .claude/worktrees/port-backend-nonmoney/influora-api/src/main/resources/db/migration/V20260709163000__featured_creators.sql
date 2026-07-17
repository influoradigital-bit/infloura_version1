-- Spec 04: featured creator rows for discovery home sections (MySQL-native, no ES).
CREATE TABLE featured_creators (
  id                  VARCHAR(26) PRIMARY KEY,
  creator_profile_id  VARCHAR(26) NOT NULL,
  featured_category   VARCHAR(64) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  featured_from       TIMESTAMP NULL,
  featured_until      TIMESTAMP NULL,
  is_active           BOOLEAN NOT NULL DEFAULT TRUE,
  featured_by_user_id VARCHAR(26) NULL,
  featured_reason     VARCHAR(500) NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_featured_category_active (featured_category, is_active),
  CONSTRAINT fk_featured_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
