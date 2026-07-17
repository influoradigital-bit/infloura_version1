-- Creator self-service profile + public portfolio handle (Task #2 Profile CRUD)

ALTER TABLE creator_profiles
  ADD COLUMN username VARCHAR(80) NULL AFTER display_name,
  ADD COLUMN portfolio_settings_json JSON NULL AFTER content_styles,
  ADD COLUMN username_changed_at TIMESTAMP NULL AFTER portfolio_settings_json,
  ADD UNIQUE KEY uq_creator_username (username);
