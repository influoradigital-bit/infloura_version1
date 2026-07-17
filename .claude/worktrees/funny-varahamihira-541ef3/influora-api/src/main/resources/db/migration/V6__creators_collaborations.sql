CREATE TABLE creator_profiles (
  id                VARCHAR(26) PRIMARY KEY,
  user_id           VARCHAR(26) NOT NULL,
  display_name      VARCHAR(100) NOT NULL,
  bio               TEXT,
  avatar_url        VARCHAR(500),
  cover_image_url   VARCHAR(500),
  city              VARCHAR(100),
  categories        JSON,
  languages         JSON,
  content_styles    JSON,
  rate_min          DECIMAL(12,2),
  rate_max          DECIMAL(12,2),
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  is_verified       BOOLEAN NOT NULL DEFAULT FALSE,
  is_discoverable   BOOLEAN NOT NULL DEFAULT TRUE,
  engagement_rate   DECIMAL(5,2) DEFAULT 0.00,
  total_followers   BIGINT NOT NULL DEFAULT 0,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_creator_user (user_id),
  INDEX idx_creator_city (city),
  INDEX idx_creator_discoverable (is_discoverable)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE platform_stats (
  id                  VARCHAR(26) PRIMARY KEY,
  creator_profile_id  VARCHAR(26) NOT NULL,
  platform            VARCHAR(32) NOT NULL,
  handle              VARCHAR(200),
  followers           BIGINT NOT NULL DEFAULT 0,
  engagement_rate     DECIMAL(5,2) DEFAULT 0.00,
  is_verified         BOOLEAN NOT NULL DEFAULT FALSE,
  profile_url         VARCHAR(500),
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_platform_creator (creator_profile_id),
  CONSTRAINT fk_platform_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE saved_creators (
  id                  VARCHAR(26) PRIMARY KEY,
  workspace_id        VARCHAR(26) NOT NULL,
  creator_profile_id  VARCHAR(26) NOT NULL,
  saved               BOOLEAN NOT NULL DEFAULT TRUE,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_workspace_creator (workspace_id, creator_profile_id),
  INDEX idx_saved_workspace (workspace_id),
  CONSTRAINT fk_saved_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_saved_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE collaborations (
  id              VARCHAR(26) PRIMARY KEY,
  campaign_id     VARCHAR(26) NOT NULL,
  creator_id      VARCHAR(26) NOT NULL,
  status          ENUM('INVITED','APPLIED','SHORTLISTED','IN_NEGOTIATION','TERMS_AGREED',
                  'CONTRACT_PENDING','CONTRACTED','IN_PROGRESS','REVIEW_PENDING',
                  'REVISION_REQUESTED','COMPLETED','CANCELLED','DISPUTED') NOT NULL DEFAULT 'INVITED',
  source          ENUM('INVITATION','APPLICATION') NOT NULL,
  agreed_rate     DECIMAL(12,2),
  currency        VARCHAR(3) DEFAULT 'INR',
  notes           TEXT,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_campaign_creator (campaign_id, creator_id),
  INDEX idx_collab_creator (creator_id),
  INDEX idx_collab_status (status),
  CONSTRAINT fk_collab_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
  CONSTRAINT fk_collab_creator_user FOREIGN KEY (creator_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
