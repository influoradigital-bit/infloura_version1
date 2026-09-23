-- Swapnil ruling 2026-09-23: Meera speaks ENGLISH by default; she switches to Hindi when the
-- creator writes to her in Hindi or asks for it (persona rule in influora-ai/app/prompt/
-- creator_persona.py). V73 created this column with DEFAULT 'hi-IN', which made Hindi the
-- language of every creator who never opened the setting.
--
-- Only the column DEFAULT changes, so this applies to creators created from now on. Existing
-- rows are deliberately left alone: a row may hold a language the creator actually chose, and
-- this migration cannot tell that apart from the old default. Flipping the untouched ones is a
-- separate, deliberate data fix (owner's call), not a schema migration.
ALTER TABLE creator_agent_preferences
    ALTER COLUMN creator_language SET DEFAULT 'en-IN';
