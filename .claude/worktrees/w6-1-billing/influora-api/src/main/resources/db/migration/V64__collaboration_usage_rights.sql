-- A7-U1: usage-rights terms submitted on deal proposal were accepted by the DTO
-- but never persisted anywhere. Add a column on collaborations (the "Deal" entity)
-- to store the raw string value. Minimum viable fix — not a structured rights model.
ALTER TABLE collaborations
  ADD COLUMN usage_rights TEXT NULL AFTER notes;
