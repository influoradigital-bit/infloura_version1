/**
 * The spoken-cue rule: say something only when the advice CHANGED, and never more than once every
 * few seconds. Both halves matter independently — without the first, a creator hears the same
 * sentence on a loop; without the second, a reading flickering across a threshold becomes a
 * stutter of overlapping utterances.
 */

import { describe, expect, it } from 'vitest'

import { initialSpeechThrottleState, shouldSpeak } from './speech-throttle'

const WINDOW = 3000

describe('shouldSpeak', () => {
  it('speaks the first cue immediately', () => {
    expect(shouldSpeak('lift-phone', 0, initialSpeechThrottleState(), WINDOW)).toBe(true)
  })

  it('never repeats the same advice, however long has passed', () => {
    const spoken = { lastKey: 'lift-phone', lastAt: 1_000 }
    expect(shouldSpeak('lift-phone', 1_000 + WINDOW, spoken, WINDOW)).toBe(false)
    expect(shouldSpeak('lift-phone', 1_000 + WINDOW * 100, spoken, WINDOW)).toBe(false)
  })

  it('holds a changed cue back until the window has passed, then lets it through', () => {
    const spoken = { lastKey: 'lift-phone', lastAt: 1_000 }
    expect(shouldSpeak('come-closer', 1_000 + WINDOW - 1, spoken, WINDOW)).toBe(false)
    expect(shouldSpeak('come-closer', 1_000 + WINDOW, spoken, WINDOW)).toBe(true)
  })

  it('defers rather than drops — the same cue still speaks on a later tick', () => {
    const spoken = { lastKey: 'add-light', lastAt: 5_000 }
    // Three sampling ticks inside the window are all silent...
    for (const now of [5_250, 5_500, 5_750]) {
      expect(shouldSpeak('level-it', now, spoken, WINDOW)).toBe(false)
    }
    // ...and the cue is still spoken once the window clears, not lost.
    expect(shouldSpeak('level-it', 8_000, spoken, WINDOW)).toBe(true)
  })

  it('treats ok as just another key, so returning to ok is announced once', () => {
    const spoken = { lastKey: 'come-closer', lastAt: 0 }
    expect(shouldSpeak('ok', WINDOW, spoken, WINDOW)).toBe(true)
    expect(shouldSpeak('ok', WINDOW * 2, { lastKey: 'ok', lastAt: WINDOW }, WINDOW)).toBe(false)
  })
})
