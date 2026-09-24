-- Swapnil ruling 2026-09-23, data half completed 2026-09-24: Meera speaks English to every
-- creator unless they ask otherwise.
--
-- V20260923090000 moved the COLUMN DEFAULT to 'en-IN' and left existing rows alone, because a
-- stored tag could be a real choice or just the old 'hi-IN' default and the migration could not
-- tell them apart. The owner's call (2026-09-24) is to move them: every creator starts in English
-- and Meera follows them into Hindi from the message they write in Hindi, so a creator who really
-- wants Hindi gets it back on their very next message, and no one is stuck in a language they
-- never picked.
--
-- Only 'hi-IN' moves. Any OTHER tag (ta-IN, mr-IN, ...) was never a default on this column, so it
-- can only have been an explicit choice, and those are left exactly as they are.
UPDATE creator_agent_preferences
SET creator_language = 'en-IN'
WHERE creator_language = 'hi-IN';
