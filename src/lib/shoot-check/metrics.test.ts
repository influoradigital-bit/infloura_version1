/**
 * Shoot Check Level 1 — the measurement maths.
 *
 * These are the only tests that can prove anything about the readings: jsdom has no camera, so
 * `useShootCheck` can only be tested for its refusal paths. Everything here runs on synthetic
 * pixel buffers, which is why `metrics.ts` is pure and takes raw `Uint8ClampedArray` rather than
 * reading a canvas itself.
 *
 * Thresholds are imported, never retyped, so a deliberate threshold change does not silently
 * break tests that were asserting yesterday's number — but every boundary below is asserted on
 * BOTH sides, so a change in behaviour still fails.
 *
 * What these tests do NOT prove: that a real phone camera produces buffers like these, that the
 * advice is useful to a creator, or that the thresholds are right. They are craft heuristics
 * (see `THRESHOLDS`) until calibrated against real creator results.
 */

import { describe, expect, it } from 'vitest'

import {
  THRESHOLDS,
  averageLuma,
  clutter,
  focusVerdict,
  framingVerdict,
  lightVerdict,
  micVerdict,
  sharpness,
  tiltVerdict,
  type Box,
  type ShotTarget,
  type Size,
} from './metrics'

const FRAME: Size = { width: 160, height: 90 }

/** A uniform RGBA buffer at one grey level. */
function flat(level: number, w = FRAME.width, h = FRAME.height): Uint8ClampedArray {
  const data = new Uint8ClampedArray(w * h * 4)
  for (let i = 0; i < w * h; i++) {
    data[i * 4] = level
    data[i * 4 + 1] = level
    data[i * 4 + 2] = level
    data[i * 4 + 3] = 255
  }
  return data
}

/** Hard black/white checkerboard: maximum high-frequency detail. */
function checkerboard(cell: number, w = FRAME.width, h = FRAME.height): Uint8ClampedArray {
  const data = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const on = (Math.floor(x / cell) + Math.floor(y / cell)) % 2 === 0
      const level = on ? 235 : 20
      const i = (y * w + x) * 4
      data[i] = level
      data[i + 1] = level
      data[i + 2] = level
      data[i + 3] = 255
    }
  }
  return data
}

/** Detail only inside `box`; everything outside it is flat. */
function detailInside(box: Box, level = 128, w = FRAME.width, h = FRAME.height): Uint8ClampedArray {
  const data = flat(level, w, h)
  for (let y = box.y; y < box.y + box.height; y++) {
    for (let x = box.x; x < box.x + box.width; x++) {
      const i = (y * w + x) * 4
      const on = (x + y) % 2 === 0
      const v = on ? 240 : 15
      data[i] = v
      data[i + 1] = v
      data[i + 2] = v
    }
  }
  return data
}

const SUBJECT: Box = { x: 50, y: 20, width: 60, height: 60 }

describe('averageLuma', () => {
  it('reads 0 for black, ~100 for white, and scales in between', () => {
    expect(averageLuma(flat(0))).toBe(0)
    expect(averageLuma(flat(255))).toBeCloseTo(100, 0)
    expect(averageLuma(flat(128))).toBeGreaterThan(45)
    expect(averageLuma(flat(128))).toBeLessThan(55)
  })

  it('is monotonic — a brighter frame never reads darker', () => {
    const readings = [0, 40, 90, 140, 200, 255].map((level) => averageLuma(flat(level)))
    const sorted = [...readings].sort((a, b) => a - b)
    expect(readings).toEqual(sorted)
  })
})

describe('lightVerdict', () => {
  it('calls a dark frame dark on both sides of the threshold', () => {
    expect(lightVerdict(THRESHOLDS.DARK_LUMA - 1).status).toBe('dark')
    expect(lightVerdict(THRESHOLDS.DARK_LUMA).status).not.toBe('dark')
  })

  it('separates dim from ok, and flags an overexposed frame', () => {
    expect(lightVerdict(THRESHOLDS.DIM_LUMA - 1).status).toBe('dim')
    expect(lightVerdict(THRESHOLDS.DIM_LUMA).status).toBe('ok')
    expect(lightVerdict(THRESHOLDS.BRIGHT_LUMA + 1).status).toBe('bright')
  })

  it('gives an ok frame no advice to act on', () => {
    expect(lightVerdict(60)).toEqual({ status: 'ok', advice: 'ok' })
  })
})

describe('sharpness', () => {
  it('scores a detailed frame far above a flat one', () => {
    const flatScore = sharpness(flat(128), FRAME.width, FRAME.height)
    const sharpScore = sharpness(checkerboard(2), FRAME.width, FRAME.height)
    expect(flatScore).toBeLessThan(THRESHOLDS.SHARP_MIN)
    expect(sharpScore).toBeGreaterThan(THRESHOLDS.SHARP_MIN)
  })

  it('scores a fine checkerboard above a coarse one (more edges per pixel)', () => {
    const fine = sharpness(checkerboard(2), FRAME.width, FRAME.height)
    const coarse = sharpness(checkerboard(16), FRAME.width, FRAME.height)
    expect(fine).toBeGreaterThan(coarse)
  })
})

describe('focusVerdict', () => {
  it('REFUSES to judge focus in the dark — grain reads as detail', () => {
    // A high sharpness score in a dark frame must not be reported as "in focus".
    expect(focusVerdict(10_000, THRESHOLDS.DARK_LUMA - 1)).toEqual({
      status: 'unknown',
      advice: 'unknown',
    })
    // And a low score in the dark is not reported as blurry either.
    expect(focusVerdict(0, THRESHOLDS.DARK_LUMA - 1).status).toBe('unknown')
  })

  it('judges focus once there is enough light', () => {
    expect(focusVerdict(THRESHOLDS.SHARP_MIN - 1, 60).status).toBe('blurry')
    expect(focusVerdict(THRESHOLDS.SHARP_MIN, 60).status).toBe('ok')
  })

  it('judges at the exact darkness boundary rather than one step below it', () => {
    expect(focusVerdict(500, THRESHOLDS.DARK_LUMA).status).not.toBe('unknown')
  })
})

describe('clutter', () => {
  it('reads a plain background as calm and a patterned one as busy', () => {
    const plain = clutter(flat(128), FRAME.width, FRAME.height, SUBJECT)
    const busy = clutter(checkerboard(2), FRAME.width, FRAME.height, SUBJECT)
    expect(plain).toBeLessThan(THRESHOLDS.CLUTTER_BUSY)
    expect(busy).toBeGreaterThan(THRESHOLDS.CLUTTER_BUSY)
  })

  it('ignores detail INSIDE the subject box — a textured shirt is not clutter', () => {
    const subjectOnly = detailInside(SUBJECT)
    expect(clutter(subjectOnly, FRAME.width, FRAME.height, SUBJECT)).toBeLessThan(
      THRESHOLDS.CLUTTER_BUSY,
    )
    // The same buffer, with no subject box declared, counts that detail against the background.
    expect(clutter(subjectOnly, FRAME.width, FRAME.height)).toBeGreaterThan(
      clutter(subjectOnly, FRAME.width, FRAME.height, SUBJECT),
    )
  })
})

describe('framingVerdict', () => {
  const face = (over: Partial<Box> = {}): Box => ({ x: 55, y: 12, width: 50, height: 25, ...over })

  it('an overhead hands shot expects NO face, and never talks about an eye line', () => {
    expect(framingVerdict(null, FRAME, 'hands-overhead')).toEqual({ status: 'ok', advice: 'ok' })
    const withFace = framingVerdict(face(), FRAME, 'hands-overhead')
    expect(withFace.advice).toBe('point-at-hands')
    expect(['lift-phone', 'lower-phone', 'come-closer', 'step-back']).not.toContain(withFace.advice)
  })

  it('reports unknown rather than guessing when no face is detected', () => {
    for (const target of ['closeup', 'medium', 'wide'] as ShotTarget[]) {
      expect(framingVerdict(null, FRAME, target)).toEqual({ status: 'unknown', advice: 'no-face' })
    }
  })

  it('asks for distance per shot type, not one global target', () => {
    // 0.25 of frame height: under the close-up minimum (0.35), inside the medium band
    // (0.15-0.45), and over the wide-shot maximum (0.2). One face, three verdicts.
    const smallFace = face({ height: FRAME.height * 0.25, y: 12 })
    // The SAME face is too small for a close-up and fine for a medium shot.
    expect(framingVerdict(smallFace, FRAME, 'closeup').advice).toBe('come-closer')
    expect(framingVerdict(smallFace, FRAME, 'medium').advice).toBe('ok')
    // And too big for a wide shot.
    expect(framingVerdict(smallFace, FRAME, 'wide').advice).toBe('step-back')
  })

  it('reads headroom in the right direction', () => {
    const height = FRAME.height * 0.25
    const tooMuchHeadroom = face({ height, y: FRAME.height * (THRESHOLDS.HEADROOM_MAX + 0.05) })
    const tooLittle = face({ height, y: FRAME.height * (THRESHOLDS.HEADROOM_MIN - 0.02) })
    expect(framingVerdict(tooMuchHeadroom, FRAME, 'medium').advice).toBe('lift-phone')
    expect(framingVerdict(tooLittle, FRAME, 'medium').advice).toBe('lower-phone')
  })

  it('tells the creator which way to move, matching the side they are on', () => {
    const height = FRAME.height * 0.25
    const y = FRAME.height * 0.12
    const width = 30
    const farLeft = face({ height, y, width, x: 5 })
    const farRight = face({ height, y, width, x: FRAME.width - width - 5 })
    // A face on the LEFT of frame means the creator steps to their own right in the picture.
    expect(framingVerdict(farLeft, FRAME, 'medium').advice).toBe('move-right')
    expect(framingVerdict(farRight, FRAME, 'medium').advice).toBe('move-left')
  })

  it('is ok when size, headroom and centring all sit inside their bands', () => {
    const good = face({ height: FRAME.height * 0.25, y: FRAME.height * 0.12, width: 30, x: 65 })
    expect(framingVerdict(good, FRAME, 'medium')).toEqual({ status: 'ok', advice: 'ok' })
  })

  it('refuses to judge a zero-sized frame', () => {
    expect(framingVerdict(face(), { width: 0, height: 0 }, 'medium').status).toBe('unknown')
  })
})

describe('tiltVerdict', () => {
  it('holds its boundaries in both directions', () => {
    expect(tiltVerdict(THRESHOLDS.TILT_OK_DEG).status).toBe('ok')
    expect(tiltVerdict(THRESHOLDS.TILT_OK_DEG + 0.5).status).not.toBe('ok')
    expect(tiltVerdict(THRESHOLDS.TILT_WARN_DEG + 1).status).toBe('bad')
  })

  it('treats a leftward and rightward lean the same', () => {
    expect(tiltVerdict(-6)).toEqual(tiltVerdict(6))
  })
})

describe('micVerdict', () => {
  it('judges the GAP above the room, not raw loudness', () => {
    // Quiet voice in a silent room: same gap as a loud voice in a loud room.
    const quietRoom = micVerdict(-40, -40 - THRESHOLDS.MIC_GOOD_GAP_DB)
    const loudRoom = micVerdict(-10, -10 - THRESHOLDS.MIC_GOOD_GAP_DB)
    expect(quietRoom).toEqual(loudRoom)
    expect(quietRoom.status).toBe('ok')
  })

  it('tells a creator in a noisy room to fix the room, not to shout', () => {
    const noisy = micVerdict(-20, -20 - (THRESHOLDS.MIC_LOW_GAP_DB - 1))
    expect(noisy.status).toBe('poor-separation')
    expect(noisy.advice).toBe('reduce-background-noise')
  })

  it('asks for a step closer when the margin is thin but not hopeless', () => {
    const thin = micVerdict(-20, -20 - (THRESHOLDS.MIC_GOOD_GAP_DB - 1))
    expect(thin.status).toBe('weak-separation')
    expect(thin.advice).toBe('move-closer-to-mic')
  })
})
