CREATE TABLE brand_profiles (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id)
  website_url       VARCHAR(500),
  analysis_status   ENUM('PENDING','ANALYZING','READY','FAILED') NOT NULL DEFAULT 'PENDING',
  scraped_at        TIMESTAMP NULL,
  product_catalog   JSON,                                    -- [{name, price, url, image_key}]
  brand_aesthetic   JSON,                                    -- {accent_color, target_demo}
  tone_profile      JSON,                                    -- {formality, energy, emoji_ok, cultural_context}
  niche_tags        JSON,                                    -- ['beauty','skincare']
  competitor_urls   JSON,
  analysis_error    VARCHAR(500) NULL,                       -- for the "paste a link" fallback (PRD §9)
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_brandprofile_workspace (workspace_id),
  INDEX idx_brandprofile_status (analysis_status),
  CONSTRAINT fk_brandprofile_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
