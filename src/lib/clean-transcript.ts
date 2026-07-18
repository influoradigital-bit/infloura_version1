/**
 * MOCK, scoped to the browser-fallback STT path only.
 *
 * The primary voice-input path (`useVoiceInput` recording audio and calling
 * `meeraApi.transcribe`) now gets `cleaned_text` back from the Sarvam
 * backend already cleaned up server-side — this function is never applied
 * to that result. It still runs on the ONE remaining client-side path:
 * `webkitSpeechRecognition`'s raw transcript, used when Sarvam recording is
 * unavailable or the transcribe call soft-fails. Replacing this with a real
 * cleanup call (or retiring it once the browser-fallback path itself goes
 * away) is still open.
 *
 * Deterministic, light-touch transcript tidy for voice input. This is
 * explicitly NOT a grammar/Hinglish rewriter — that needs an LLM and lands
 * once the backend exists. Today this only:
 *   - trims leading/trailing whitespace
 *   - collapses runs of internal whitespace to a single space
 *   - capitalizes the first letter of the sentence
 *
 * MEANING-PRESERVATION IS NON-NEGOTIABLE (Priya's voice handoff §3 / spec
 * §5A.C acceptance #2). This function must NEVER alter:
 *   - numbers ("10", "15,000")
 *   - ₹ amounts ("₹17,250")
 *   - @handles ("@brandname")
 *   - city/brand names (any capitalized proper noun / word as typed)
 * When in doubt, pass the text through unchanged rather than risk rewriting
 * something a brand actually said. The real safety net is the edit-first
 * composer UI, not this function — this is just whitespace/case tidy.
 *
 * `cleanTranscript` is a single swappable async seam: `useVoiceInput` awaits
 * it, so replacing the body with a real backend LLM call later is a
 * one-function swap with zero UI changes.
 */
export async function cleanTranscript(raw: string): Promise<string> {
  if (!raw) return raw

  // Collapse whitespace only — never touch digits, ₹, @handles, or casing
  // of individual words (proper nouns like city/brand names stay exactly
  // as spoken/transcribed).
  const collapsed = raw.trim().replace(/\s+/g, ' ')
  if (!collapsed) return collapsed

  // Sentence-case: capitalize only the very first character of the whole
  // string. This cannot alter a leading number, ₹ amount, or @handle since
  // toUpperCase() on a digit/symbol is a no-op.
  const capitalized = collapsed.charAt(0).toUpperCase() + collapsed.slice(1)

  return capitalized
}
