-- Lean deliverable rows per collaboration slot (Priya CREATOR_EXEC_PLAN §1.3 — no separate version/file tables).
-- Files for the current draft live in files_json; version_number increments on each upload.

CREATE TABLE deliverables (
  id                  VARCHAR(26) PRIMARY KEY,
  collaboration_id    VARCHAR(26) NOT NULL,
  creator_profile_id  VARCHAR(26) NOT NULL,
  milestone_id        VARCHAR(26) NULL,
  slot_index          INT NOT NULL DEFAULT 1,
  type                VARCHAR(50) NOT NULL DEFAULT 'INSTAGRAM_REEL',
  title               VARCHAR(200) NOT NULL,
  description         TEXT NULL,
  status              ENUM(
                        'PENDING','DRAFT','SUBMITTED','REVISION_REQUESTED','RESUBMITTED',
                        'APPROVED','REJECTED','POSTED','METRICS_REPORTED','VERIFIED'
                      ) NOT NULL DEFAULT 'PENDING',
  deadline            DATE NULL,
  version_number      INT NOT NULL DEFAULT 0,
  revision_count      INT NOT NULL DEFAULT 0,
  files_json          JSON NULL,
  caption             TEXT NULL,
  hashtags_json       JSON NULL,
  creator_notes       TEXT NULL,
  review_notes        TEXT NULL,
  post_url            VARCHAR(500) NULL,
  post_id             VARCHAR(100) NULL,
  posted_at           TIMESTAMP NULL,
  submitted_at        TIMESTAMP NULL,
  approved_at         TIMESTAMP NULL,
  reviewed_at         TIMESTAMP NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_deliverable_collab_slot (collaboration_id, slot_index),
  INDEX idx_deliverable_collab (collaboration_id),
  INDEX idx_deliverable_creator (creator_profile_id),
  INDEX idx_deliverable_status (status),
  CONSTRAINT fk_deliverable_collab     FOREIGN KEY (collaboration_id)   REFERENCES collaborations(id),
  CONSTRAINT fk_deliverable_creator    FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id),
  CONSTRAINT fk_deliverable_milestone  FOREIGN KEY (milestone_id)       REFERENCES payment_milestones(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
