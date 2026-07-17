-- Core auth & brand workspace tables (BACKEND-API-SPEC §2, §4)

CREATE TABLE users (
  id                  VARCHAR(26) PRIMARY KEY,
  email               VARCHAR(255) UNIQUE,
  phone_number        VARCHAR(20) UNIQUE,
  password_hash       VARCHAR(255),
  user_type           ENUM('BRAND','CREATOR','ADMIN') NOT NULL,
  status              ENUM('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DEACTIVATED') NOT NULL DEFAULT 'PENDING_VERIFICATION',
  email_verified      BOOLEAN NOT NULL DEFAULT FALSE,
  phone_verified      BOOLEAN NOT NULL DEFAULT FALSE,
  onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
  display_name        VARCHAR(100),
  first_name          VARCHAR(50),
  last_name           VARCHAR(50),
  avatar_url          VARCHAR(500),
  timezone            VARCHAR(50) NOT NULL DEFAULT 'Asia/Kolkata',
  last_login_at       TIMESTAMP NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workspaces (
  id                  VARCHAR(26) PRIMARY KEY,
  name                VARCHAR(200) NOT NULL,
  slug                VARCHAR(100) NOT NULL UNIQUE,
  type                ENUM('BRAND','AGENCY') NOT NULL DEFAULT 'BRAND',
  logo_url            VARCHAR(500),
  website_url         VARCHAR(500),
  industry            VARCHAR(100),
  company_size        VARCHAR(50),
  description         TEXT,
  verification_status ENUM('UNVERIFIED','PENDING','VERIFIED','REJECTED') NOT NULL DEFAULT 'UNVERIFIED',
  billing_email       VARCHAR(255),
  gstin               VARCHAR(20),
  pan                 VARCHAR(20),
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workspace_members (
  id            VARCHAR(26) PRIMARY KEY,
  workspace_id  VARCHAR(26) NOT NULL,
  user_id       VARCHAR(26) NOT NULL,
  role          ENUM('OWNER','ADMIN','MANAGER','MEMBER','VIEWER') NOT NULL,
  is_active     BOOLEAN NOT NULL DEFAULT TRUE,
  joined_at     TIMESTAMP NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_workspace_user (workspace_id, user_id),
  CONSTRAINT fk_wm_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_wm_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE wallets (
  id              VARCHAR(26) PRIMARY KEY,
  owner_id        VARCHAR(26) NOT NULL,
  owner_type      ENUM('USER','WORKSPACE') NOT NULL,
  balance         DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  escrow_balance  DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  currency        VARCHAR(3) NOT NULL DEFAULT 'INR',
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_owner (owner_id, owner_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refresh_tokens (
  id          VARCHAR(26) PRIMARY KEY,
  user_id     VARCHAR(26) NOT NULL,
  token_hash  VARCHAR(64) NOT NULL,
  expires_at  TIMESTAMP NOT NULL,
  revoked     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_token_hash (token_hash),
  INDEX idx_refresh_user (user_id),
  CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE password_reset_tokens (
  id          VARCHAR(26) PRIMARY KEY,
  user_id     VARCHAR(26) NOT NULL,
  token_hash  VARCHAR(64) NOT NULL,
  expires_at  TIMESTAMP NOT NULL,
  used        BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_prt_user (user_id),
  CONSTRAINT fk_prt_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
