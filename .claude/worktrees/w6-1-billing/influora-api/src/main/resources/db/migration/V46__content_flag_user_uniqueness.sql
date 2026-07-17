-- V-GA-5 / M-K6-C2-5: one flag per user per content (closes duplicate-flag residual after review-flag rate limit).
ALTER TABLE content_flags
  ADD COLUMN flagged_by_user_id VARCHAR(26) NULL AFTER flagged_by;

CREATE UNIQUE INDEX uq_content_flag_user ON content_flags (content_id, flagged_by_user_id);
