/**
 * The chat history a Meera turn sends to the AI service.
 *
 * EV-044 (influora-ai app/routes/chat.py) REFUSES a request whose history is over
 * AI_MAX_HISTORY_TURNS (default 40) or AI_MAX_HISTORY_CHARS (default 60,000) with
 * AI_HISTORY_TOO_LARGE, "Start a new conversation". That is the right guard against a client that
 * sends an unbounded history, but neither chat has a "new conversation" control and both resume the
 * same conversation (creator: up to 100 stored messages), so without this every brand or creator
 * would be locked out of Meera after about 20 exchanges.
 *
 * So the client sends only the most recent turns, inside limits comfortably below the server's:
 * the newest turns first, stopping at MAX_TURNS or MAX_CHARS, and never starting on an assistant
 * turn (the model expects the replay to open with the user). The whole transcript stays on screen;
 * Meera works from the recent part, and the latest message is always included.
 */
export const MEERA_HISTORY_MAX_TURNS = 30;
export const MEERA_HISTORY_MAX_CHARS = 48_000;

/**
 * Creator Meera sends a shorter window (2026-09-22, Swapnil, cost fix 2): the last 20 messages
 * (10 back-and-forths), at most 16,000 characters. History is the one part of the prompt that is
 * never cached, so at the 30-message / 48,000-character window a long creator chat cost ~Rs 4.8 a
 * message; this caps the history share at roughly Rs 1.2. Brand chat keeps the defaults above.
 */
export const CREATOR_HISTORY_MAX_TURNS = 20;
export const CREATOR_HISTORY_MAX_CHARS = 16_000;

export interface HistoryTurn {
  role: 'user' | 'assistant';
  content: string;
}

export function recentHistory(
  turns: HistoryTurn[],
  maxTurns: number = MEERA_HISTORY_MAX_TURNS,
  maxChars: number = MEERA_HISTORY_MAX_CHARS,
): HistoryTurn[] {
  const kept: HistoryTurn[] = [];
  let chars = 0;
  for (let i = turns.length - 1; i >= 0; i--) {
    const turn = turns[i];
    const size = turn.content.length;
    // The newest turn (the message being sent) is always kept, even if it alone is large.
    if (kept.length > 0 && (kept.length >= maxTurns || chars + size > maxChars)) break;
    kept.push(turn);
    chars += size;
  }
  kept.reverse();
  while (kept.length > 1 && kept[0].role !== 'user') kept.shift();
  return kept;
}
