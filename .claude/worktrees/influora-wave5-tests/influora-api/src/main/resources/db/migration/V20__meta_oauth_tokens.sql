-- Week 1-2 backend spec (VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §1) — encrypted Meta
-- (Instagram/Facebook) OAuth tokens for creators. [SEC: Kabir sign-off gate] encrypted_access_token
-- is AES-256-GCM ciphertext produced by MetaTokenStorage; this table must NEVER receive a
-- plaintext token via any other code path.
CREATE TABLE meta_oauth_tokens (
  id                       VARCHAR(26) PRIMARY KEY,
  workspace_id             VARCHAR(26) NOT NULL,                 -- FK workspaces(id)
  creator_profile_id       VARCHAR(26) NOT NULL,                 -- FK creator_profiles(id)
  encrypted_access_token   TEXT NOT NULL,                        -- AES-256-GCM ciphertext, base64 (IV prepended)
  expires_at               TIMESTAMP NOT NULL,
  granted_scopes           JSON NULL,                             -- ['instagram_basic','instagram_manage_insights',...]
  revoked                  BOOLEAN NOT NULL DEFAULT FALSE,
  last_refreshed_at        TIMESTAMP NULL,
  created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_meta_oauth_workspace_creator (workspace_id, creator_profile_id),
  INDEX idx_meta_oauth_expires (expires_at),
  INDEX idx_meta_oauth_creator (creator_profile_id),
  CONSTRAINT fk_meta_oauth_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_meta_oauth_creator   FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
