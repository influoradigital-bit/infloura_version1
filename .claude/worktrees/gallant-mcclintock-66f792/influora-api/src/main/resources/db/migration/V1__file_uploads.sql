-- Media metadata (bytes live on Cloudflare R2). Full schema from BACKEND-API-SPEC §2 added in later migrations.

CREATE TABLE IF NOT EXISTS file_uploads (
  id            VARCHAR(26) PRIMARY KEY,
  owner_id      VARCHAR(26) NOT NULL,
  owner_type    ENUM('USER','WORKSPACE') NOT NULL,
  purpose       VARCHAR(32) NOT NULL,
  r2_bucket     VARCHAR(128) NOT NULL,
  r2_key        VARCHAR(512) NOT NULL,
  mime_type     VARCHAR(128) NOT NULL,
  size_bytes    BIGINT NOT NULL,
  etag          VARCHAR(128),
  public_url    VARCHAR(1024),
  thumbnail_key VARCHAR(512),
  status        ENUM('PENDING','READY','FAILED','DELETED') DEFAULT 'PENDING',
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_owner (owner_id, owner_type),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
