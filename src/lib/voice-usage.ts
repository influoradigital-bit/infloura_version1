/**
 * Credits metering seam for voice actions (Priya's voice handoff §7).
 * No backend cost meter exists yet — this is a fire-and-forget no-op today.
 * Rohan hooks in the real cost model later (STT + grammar-cleanup pass +
 * TTS all cost credits, same as any other Meera action). Do NOT build
 * billing logic here — this is intentionally a stub.
 *
 * Text-only usage never calls this, so the existing ₹17-31/brand-month
 * baseline is unaffected (acceptance #5).
 */
export function logVoiceUsage(kind: 'stt' | 'tts'): void {
  if (import.meta.env?.DEV) {
    // eslint-disable-next-line no-console
    console.debug(`[voice-usage] ${kind} action logged (stub — no billing wired yet)`)
  }
}
