-- P0 #3: Creator-reported campaign analytics (Q9, brand-audit backend build task).
-- One row per payment milestone (our closest existing "deliverable" unit — see
-- payment_milestones.description / due_date). All numbers are self-declared by the creator;
-- every read path surfaces source = CREATOR_REPORTED so the UI never implies platform-verified
-- data. Verified platform-API integration (Instagram/YouTube) is an explicit separate, later
-- effort — do NOT infer or backfill values here.
CREATE TABLE deliverable_metrics (
  id                       VARCHAR(26) PRIMARY KEY,
  milestone_id             VARCHAR(26) NOT NULL,                 -- FK payment_milestones(id), 1:1 per deliverable
  collaboration_id         VARCHAR(26) NOT NULL,                 -- FK collaborations(id) (denormalized for campaign aggregation)
  reach                    BIGINT NULL,
  impressions              BIGINT NULL,
  engagements              BIGINT NULL,
  link                     VARCHAR(1000) NULL,
  proof_screenshot_r2_key  VARCHAR(500) NULL,
  reported_by_creator_id   VARCHAR(26) NOT NULL,                 -- FK users(id)
  reported_at              TIMESTAMP NOT NULL,
  created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_deliverable_metric_milestone (milestone_id),
  INDEX idx_deliverable_metric_collab (collaboration_id),
  CONSTRAINT fk_deliverable_metric_milestone FOREIGN KEY (milestone_id)     REFERENCES payment_milestones(id),
  CONSTRAINT fk_deliverable_metric_collab    FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_deliverable_metric_creator   FOREIGN KEY (reported_by_creator_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
