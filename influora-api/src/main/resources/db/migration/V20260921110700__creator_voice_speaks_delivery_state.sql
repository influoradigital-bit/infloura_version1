-- T-CREATOR-CREDITS-V2 K-15/round-2 review finding #3 — the "was this turn's paid voice reply
-- already delivered" decision moves from a process-local ConcurrentHashMap (lost on restart, not
-- shared across instances, and read/written OUTSIDE any lock) onto this row, decided under the
-- SAME creator_credit_accounts row lock CreatorCreditService#release already takes.
ALTER TABLE creator_voice_speaks
  ADD COLUMN delivered BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN refunded  BOOLEAN NOT NULL DEFAULT FALSE;
