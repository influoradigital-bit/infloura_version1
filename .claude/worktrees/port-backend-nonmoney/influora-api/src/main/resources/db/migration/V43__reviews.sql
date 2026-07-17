-- Task #29 (V4) — collaboration-scoped creator↔brand reviews (CEO §1.2).
-- One review per collaboration per reviewer_type; gated on COMPLETED at the service layer.

CREATE TABLE reviews (
  id                  VARCHAR(26) PRIMARY KEY,
  collaboration_id    VARCHAR(26) NOT NULL,
  reviewer_type       ENUM('CREATOR','BRAND') NOT NULL,
  reviewer_user_id    VARCHAR(26) NOT NULL,
  stars               TINYINT NOT NULL,
  review_text         TEXT,
  hidden              BOOLEAN NOT NULL DEFAULT FALSE,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_review_collab_reviewer (collaboration_id, reviewer_type),
  INDEX idx_review_collaboration (collaboration_id),
  CONSTRAINT fk_review_collaboration FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_review_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES users(id),
  CONSTRAINT chk_review_stars CHECK (stars >= 1 AND stars <= 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Reuse admin content_flags moderation queue for flagged reviews (CEO §1.2).
ALTER TABLE content_flags
  MODIFY content_type ENUM('DELIVERABLE','PROFILE','MESSAGE','REVIEW') NOT NULL;
