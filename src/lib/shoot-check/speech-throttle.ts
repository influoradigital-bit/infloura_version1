/**
 * Pure decision rule behind `useShootCheck`'s spoken cues.
 *
 * Kept separate from the hook (and from any DOM/`speechSynthesis` call) so the throttle itself —
 * "only speak when the advice actually changed, and never more than once every N ms" — can be
 * unit-tested without a camera, a microphone, or a real speech synthesizer. `useShootCheck` calls
 * this once per sample tick with whatever the current single most-urgent advice key is; a `true`
 * result is the hook's cue to actually call `speechSynthesis.speak(...)` and update the state
 * this function reads.
 */
export interface SpeechThrottleState {
  /** The advice key last actually spoken, or `null` before the first utterance. */
  lastKey: string | null;
  /** `Date.now()` at the moment that utterance was spoken, or `0` before the first one. */
  lastAt: number;
}

export function initialSpeechThrottleState(): SpeechThrottleState {
  return { lastKey: null, lastAt: 0 };
}

/**
 * `true` iff `key` differs from the last spoken key AND at least `throttleMs` has elapsed since
 * the last utterance. Both conditions matter independently: a changed key inside the throttle
 * window is deferred (not dropped — the next tick re-checks with the still-current key once the
 * window has passed), and an unchanged key never re-fires no matter how much time has passed.
 */
export function shouldSpeak(key: string, now: number, state: SpeechThrottleState, throttleMs: number): boolean {
  if (key === state.lastKey) return false;
  // Nothing has been spoken yet, so there is no window to respect: the FIRST cue always goes out.
  // Stated explicitly rather than relying on `now - 0 >= throttleMs` happening to be true, which
  // holds for `Date.now()` and silently fails for a monotonic clock (`performance.now()` starts
  // near 0) — that would mute the first three seconds, exactly when a creator is still positioning
  // themselves and most needs to hear something.
  if (state.lastKey === null) return true;
  if (now - state.lastAt < throttleMs) return false;
  return true;
}
