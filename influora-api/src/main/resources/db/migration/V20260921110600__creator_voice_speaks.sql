-- T-CREATOR-CREDITS-V2 B1 — caps server TTS calls per paid voice turn (design-priya/Kabir C5): a
-- voice turn's tts:<turnId> debit buys at most `voice-speaks-per-turn` (default 3) real Sarvam
-- calls for that turn's text — a creator re-tapping "play" does not re-charge, but also cannot
-- call Sarvam an unbounded number of times off one paid debit.
CREATE TABLE creator_voice_speaks (
  creator_user_id  VARCHAR(26) NOT NULL,
  turn_id          VARCHAR(26) NOT NULL,
  speak_count      INT NOT NULL DEFAULT 0,
  updated_at       DATETIME(6) NOT NULL,
  PRIMARY KEY (creator_user_id, turn_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
