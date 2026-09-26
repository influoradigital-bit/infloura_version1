/**
 * shot-zones: the live camera guide's geometry (spec v2 Phase 5a + 5b).
 *
 * Run: npx vitest run src/lib/shoot-check/shot-zones.test.ts
 */
import { describe, expect, it } from 'vitest';

import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { targetForShotSize } from './beat-to-shot';
import { THRESHOLDS, framingVerdict, type ShotTarget } from './metrics';
import { INTERIM_SAFE_ZONES, type SafeZones } from './safe-zones';
import {
  GOLDEN_LINES,
  PLANE,
  PROP_RAIL_GAP,
  PROP_ZONE_WIDTH,
  THIRDS_LINES,
  chipText,
  gridGeometry,
  hookTextArea,
  planeRectToBox,
  planeXToBox,
  planeYToBox,
  propZone,
  reelFrame,
  setupChips,
  shotBands,
  shotSizeFor,
  visibleChars,
} from './shot-zones';

const ZONES: SafeZones = INTERIM_SAFE_ZONES;

function shot(label: string, target: ShotTarget, context?: ShootCheckShot['context']): ShootCheckShot {
  return { index: 0, label, seconds: 3, target, ...(context ? { context } : {}) };
}

describe('shot bands (the Phase 5a zone table)', () => {
  it('CU, MCU and MS use the approved eye line 640-733, with no head-top band', () => {
    for (const size of ['CU', 'MCU', 'MS'] as const) {
      const bands = shotBands(size);
      expect(bands.eyeLine).toEqual({ from: 640, to: 733 });
      expect(bands.headTop).toBeNull();
      expect(bands.overhead).toBeNull();
    }
  });

  it('hook-text areas: MCU y 269-400 (1 line), MS y 269-480 (2 lines), none for CU', () => {
    expect(shotBands('MCU').hookText).toEqual({ from: 269, to: 400 });
    expect(shotBands('MS').hookText).toEqual({ from: 269, to: 480 });
    expect(shotBands('CU').hookText).toBeNull();
    expect(shotBands('MLS').hookText).toBeNull();
    expect(shotBands('FS').hookText).toBeNull();
  });

  it('lower crops: MCU chest 1050-1300, MS waist 1450-1700, MLS knees 1600-1830, FS feet 1680-1840, CU none', () => {
    expect(shotBands('MCU').lowerCrop).toEqual({ part: 'chest', band: { from: 1050, to: 1300 } });
    expect(shotBands('MS').lowerCrop).toEqual({ part: 'waist', band: { from: 1450, to: 1700 } });
    expect(shotBands('MLS').lowerCrop).toEqual({ part: 'knees', band: { from: 1600, to: 1830 } });
    expect(shotBands('FS').lowerCrop).toEqual({ part: 'feet', band: { from: 1680, to: 1840 } });
    expect(shotBands('CU').lowerCrop).toBeNull();
  });

  it('MLS and FS keep the research values: inner band the overlap, outer the union', () => {
    const mls = shotBands('MLS');
    expect(mls.eyeLine).toEqual({ from: 430, to: 620 });
    expect(mls.headTop).toEqual({ inner: { from: 130, to: 200 }, outer: { from: 100, to: 260 } });
    const fs = shotBands('FS');
    expect(fs.eyeLine).toBeNull();
    expect(fs.headTop).toEqual({ inner: { from: 120, to: 200 }, outer: { from: 100, to: 250 } });
  });

  it('LS is drawn as FS, ECU as CU, OVERHEAD has a surface and two hands and no face bands', () => {
    expect(shotBands('LS').drawnAs).toBe('FS');
    expect(shotBands('LS').lowerCrop).toEqual(shotBands('FS').lowerCrop);
    expect(shotBands('ECU').drawnAs).toBe('CU');
    const overhead = shotBands('OVERHEAD');
    expect(overhead.eyeLine).toBeNull();
    expect(overhead.headTop).toBeNull();
    expect(overhead.hookText).toBeNull();
    expect(overhead.overhead?.hands).toHaveLength(2);
    for (const r of [overhead.overhead!.surface, ...overhead.overhead!.hands]) {
      expect(r.x).toBeGreaterThanOrEqual(0);
      expect(r.y).toBeGreaterThanOrEqual(0);
      expect(r.x + r.width).toBeLessThanOrEqual(PLANE.width);
      expect(r.y + r.height).toBeLessThanOrEqual(PLANE.height);
    }
  });

  it('the hook-text area spans the safe area and never starts inside the covered top bar', () => {
    const area = hookTextArea(shotBands('MS'), ZONES)!;
    expect(area.x).toBeCloseTo(ZONES.side * PLANE.width);
    expect(area.x + area.width).toBeCloseTo((1 - ZONES.side) * PLANE.width);
    expect(area.y).toBeGreaterThanOrEqual(ZONES.top * PLANE.height);
    // A measured, taller top bar pushes it down with it.
    const tall = hookTextArea(shotBands('MS'), { ...ZONES, top: 0.2 })!;
    expect(tall.y).toBeCloseTo(0.2 * PLANE.height);
    expect(hookTextArea(shotBands('CU'), ZONES)).toBeNull();
  });
});

describe('shotSizeFor (the size the camera guide draws)', () => {
  it('reads the shot text, then the target, then MS', () => {
    expect(shotSizeFor(shot('0-3s · Medium close-up, talking', 'closeup'))).toBe('MCU');
    expect(shotSizeFor(shot('0-3s · Talking to camera', 'closeup'))).toBe('CU');
    expect(shotSizeFor(shot('x', 'medium', { line: 'Full body walk-in' }))).toBe('FS');
    expect(shotSizeFor(shot('0-3s · Something else', 'wide'))).toBe('FS');
    expect(shotSizeFor(shot('0-3s · Pour the water', 'hands-overhead'))).toBe('OVERHEAD');
    expect(shotSizeFor(null)).toBe('MS');
  });

  it('prefers context.line over the label', () => {
    expect(shotSizeFor(shot('0-3s · Close-up', 'closeup', { line: 'Overhead on your hands' }))).toBe('OVERHEAD');
  });
});

describe('the 9:16 Reel frame inside the preview box', () => {
  it('a 390 x 600 portrait box: full height, centred sideways', () => {
    const frame = reelFrame({ width: 390, height: 600 });
    expect(frame.height).toBe(600);
    expect(frame.width).toBeCloseTo(337.5);
    expect(frame.x).toBeCloseTo(26.25);
    expect(frame.y).toBe(0);
    expect(frame.width / frame.height).toBeCloseTo(9 / 16);
  });

  it('a 600 x 390 landscape box: full height, centred sideways', () => {
    const frame = reelFrame({ width: 600, height: 390 });
    expect(frame.height).toBe(390);
    expect(frame.width).toBeCloseTo(219.375);
    expect(frame.x).toBeCloseTo(190.3125);
    expect(frame.y).toBe(0);
  });

  it('a box narrower than 9:16: full width, centred vertically', () => {
    const frame = reelFrame({ width: 300, height: 800 });
    expect(frame.width).toBe(300);
    expect(frame.height).toBeCloseTo(533.333, 2);
    expect(frame.y).toBeCloseTo((800 - 533.333) / 2, 2);
  });

  it('maps plane coordinates into the box', () => {
    const frame = reelFrame({ width: 390, height: 600 });
    expect(planeXToBox(frame, 0)).toBeCloseTo(26.25);
    expect(planeXToBox(frame, 1080)).toBeCloseTo(363.75);
    expect(planeYToBox(frame, 1920)).toBeCloseTo(600);
    expect(planeRectToBox(frame, { x: 540, y: 960, width: 108, height: 192 })).toEqual({
      x: 26.25 + 540 * 0.3125,
      y: 300,
      width: 108 * 0.3125,
      height: 60,
    });
  });
});

describe('camera grid', () => {
  it('Rule of thirds: x 360/720, y 640/1280, no dots', () => {
    expect(gridGeometry('thirds')).toEqual({ vertical: [360, 720], horizontal: [640, 1280], points: [] });
  });

  it('Golden grid: x 412/668, y 733/1187 and exactly 4 dots at the crossings', () => {
    const golden = gridGeometry('golden');
    expect(golden.vertical).toEqual([412, 668]);
    expect(golden.horizontal).toEqual([733, 1187]);
    expect(golden.points).toHaveLength(4);
    expect(golden.points).toEqual(
      expect.arrayContaining([
        { x: 412, y: 733 },
        { x: 668, y: 733 },
        { x: 412, y: 1187 },
        { x: 668, y: 1187 },
      ])
    );
  });

  it('Off: no lines and no dots', () => {
    expect(gridGeometry('off')).toEqual({ vertical: [], horizontal: [], points: [] });
  });
});

describe('prop zone', () => {
  const medium = shot('0-3s · Medium shot - hold the serum', 'medium', { prop: 'serum bottle' });
  const railLimit = ZONES.rail.x * PLANE.width - PROP_RAIL_GAP;
  const captionY = ZONES.captionLine * PLANE.height;

  it('sits on the grid line of the creator\'s side, left of the rail and above the caption line', () => {
    for (const [grid, line] of [
      ['thirds', THIRDS_LINES.vertical[1]],
      ['golden', GOLDEN_LINES.vertical[1]],
      ['off', THIRDS_LINES.vertical[1]],
    ] as const) {
      const zone = propZone({ shot: medium, size: 'MS', grid, mirrored: true, zones: ZONES })!;
      expect(zone.snappedTo).toBe(line);
      expect(zone.rect.x + zone.rect.width / 2).toBeCloseTo(line);
      expect(zone.rect.width).toBe(PROP_ZONE_WIDTH);
      expect(zone.rect.x + zone.rect.width).toBeLessThanOrEqual(railLimit);
      expect(zone.rect.y + zone.rect.height).toBeLessThanOrEqual(captionY);
      expect(zone.rect.height).toBeGreaterThan(0);
    }
  });

  it('mirror on (front camera): "your right" is drawn on the screen\'s right; rear camera flips it', () => {
    const front = propZone({ shot: medium, size: 'MS', grid: 'thirds', mirrored: true, zones: ZONES })!;
    expect(front.side).toBe('right');
    expect(front.screenSide).toBe('right');
    expect(front.rect.x).toBeGreaterThan(PLANE.width / 2 - PROP_ZONE_WIDTH / 2);
    const rear = propZone({ shot: medium, size: 'MS', grid: 'thirds', mirrored: false, zones: ZONES })!;
    expect(rear.side).toBe('right');
    expect(rear.screenSide).toBe('left');
    expect(rear.snappedTo).toBe(360);
  });

  it('a wide shot\'s table prop is on the creator\'s left, and ends above the caption line', () => {
    const wide = shot('0-3s · Wide shot of the counter', 'wide', { prop: 'jar' });
    const zone = propZone({ shot: wide, size: 'LS', grid: 'thirds', mirrored: true, zones: ZONES })!;
    expect(zone.side).toBe('left');
    expect(zone.place).toBe('table');
    expect(zone.snappedTo).toBe(360);
    expect(zone.rect.y + zone.rect.height).toBeLessThanOrEqual(captionY);
    expect(zone.rect.height).toBeGreaterThan(0);
  });

  it('follows the config: a wider rail clips it, a higher caption line lifts it', () => {
    const zones: SafeZones = { ...ZONES, captionLine: 0.6, rail: { ...ZONES.rail, x: 0.7 } };
    const zone = propZone({ shot: medium, size: 'MS', grid: 'thirds', mirrored: true, zones })!;
    expect(zone.rect.x + zone.rect.width).toBeCloseTo(0.7 * PLANE.width - PROP_RAIL_GAP);
    expect(zone.rect.y + zone.rect.height).toBeCloseTo(0.6 * PLANE.height);
  });

  it('a close-up\'s in-hand prop is centred, not snapped', () => {
    const close = shot('0-3s · Close-up - hold up the box', 'closeup', { prop: 'box' });
    const zone = propZone({ shot: close, size: 'CU', grid: 'golden', mirrored: true, zones: ZONES })!;
    expect(zone.side).toBe('centre');
    expect(zone.snappedTo).toBeNull();
    expect(zone.rect.x + zone.rect.width / 2).toBeCloseTo(PLANE.width / 2);
  });

  it('no prop, no zone: we never invent one', () => {
    expect(propZone({ shot: shot('0-3s · Medium shot', 'medium'), size: 'MS', grid: 'thirds', mirrored: true, zones: ZONES })).toBeNull();
    expect(
      propZone({ shot: shot('0-3s · Medium shot', 'medium', { prop: '   ' }), size: 'MS', grid: 'thirds', mirrored: true, zones: ZONES })
    ).toBeNull();
    expect(propZone({ shot: null, size: 'MS', grid: 'thirds', mirrored: true, zones: ZONES })).toBeNull();
  });
});

describe('set-up chips (20 visible characters, never splitting Devanagari)', () => {
  it('keeps text of 20 characters or fewer as it is', () => {
    expect(chipText('Bedroom desk')).toBe('Bedroom desk');
    expect(chipText('x'.repeat(20))).toBe('x'.repeat(20));
  });

  it('cuts long English text at the last space before character 19, then adds "…"', () => {
    expect(chipText('Bedroom, by the window and the long cupboard')).toBe('Bedroom, by the…');
  });

  it('hard-cuts at 19 when there is no space', () => {
    expect(chipText('y'.repeat(30))).toBe(`${'y'.repeat(19)}…`);
  });

  it('never splits a Devanagari syllable from its matra', () => {
    // 36 visible characters but 62 code points: a code-point count would cut it mid-syllable.
    const hindi = 'खिड़कीकेपासवालीमेज़परबैठकरबोलें'.repeat(2);
    const cut = chipText(hindi);
    expect(cut.endsWith('…')).toBe(true);
    const kept = cut.slice(0, -1);
    // Counted with the test's own segmenter, not the module's, so a code-point count cannot agree
    // with itself: 19 syllables kept, never 19 code points (which would be only 10 syllables).
    const syllables = Array.from(new Intl.Segmenter('hi', { granularity: 'grapheme' }).segment(hindi), (s) => s.segment);
    expect(kept).toBe(syllables.slice(0, 19).join(''));
    expect(visibleChars(kept)).toHaveLength(19);
    // The next character after the cut starts a new syllable, never a dangling vowel sign/virama.
    expect(/^\p{M}/u.test(hindi.slice(kept.length))).toBe(false);
    expect(visibleChars(cut).length).toBeLessThanOrEqual(20);
  });

  it('a Devanagari phrase with spaces is cut at a space and stays within 20 visible characters', () => {
    const cut = chipText('रसोई में खिड़की के पास वाली बड़ी मेज़ पर');
    expect(cut.endsWith('…')).toBe(true);
    expect(visibleChars(cut).length).toBeLessThanOrEqual(20);
    expect(cut.slice(0, -1).endsWith(' ')).toBe(false);
  });

  it('takes angle, where, light, sit_or_walk in order, at most 3, with the size name for a missing angle', () => {
    const full = shot('0-3s · Medium shot', 'medium', {
      angle: 'Eye level',
      where: 'Bedroom, by the window and the long cupboard',
      light: 'Window on your left',
      sit_or_walk: 'Sit',
      line: 'never a chip',
      action: 'never a chip',
    });
    const chips = setupChips(full, 'Medium shot');
    expect(chips.map((c) => c.kind)).toEqual(['angle', 'where', 'light']);
    expect(chips[1]).toEqual({ kind: 'where', text: 'Bedroom, by the…', full: 'Bedroom, by the window and the long cupboard' });
    const noAngle = setupChips(shot('0-3s · x', 'medium', { where: '  ', light: 'Soft lamp' }), 'Medium shot');
    expect(noAngle.map((c) => c.text)).toEqual(['Medium shot', 'Soft lamp']);
  });
});

describe('checker compatibility (F9): the guide against framingVerdict', () => {
  // A synthetic face box whose estimated eye line (top + 0.4 x height, the Phase 4 rule) sits in the
  // middle of the size's eye band, centred sideways, 0.75 as wide as tall, on the 1080 x 1920 frame.
  const frame = { width: PLANE.width, height: PLANE.height };
  function faceOnEyeBand(size: 'CU' | 'MCU' | 'MS' | 'MLS', heightShare: number) {
    const eye = shotBands(size).eyeLine!;
    const height = heightShare * PLANE.height;
    const width = 0.75 * height;
    const eyeY = (eye.from + eye.to) / 2;
    return { x: (PLANE.width - width) / 2, y: eyeY - 0.4 * height, width, height };
  }
  // Sized inside each target's range, read from THRESHOLDS (not copied), so a retune moves them.
  const HEIGHT_SHARE: Record<ShotTarget, number> = {
    closeup: THRESHOLDS.FACE_CLOSEUP_MIN_FRACTION + 0.07,
    medium: (THRESHOLDS.FACE_MEDIUM_MIN_FRACTION + THRESHOLDS.FACE_MEDIUM_MAX_FRACTION) / 2,
    wide: THRESHOLDS.FACE_WIDE_MAX_FRACTION * 0.9,
    'hands-overhead': 0,
  };

  it('MLS: a face centred in the eye band, sized for its target, passes framingVerdict', () => {
    const target = targetForShotSize('MLS');
    expect(target).toBe('wide');
    expect(framingVerdict(faceOnEyeBand('MLS', HEIGHT_SHARE[target]), frame, target)).toEqual({ status: 'ok', advice: 'ok' });
  });

  it('CU, MCU, MS: the size check passes; the known headroom disagreement is pinned', () => {
    // Only the SIZE is pinned for the talking sizes: the approved eye line (640-733) leaves room for
    // hook text above the head, which framingVerdict's HEADROOM_MAX (0.22) calls "too much" for a
    // medium face. The live match is OFF (no match state exists in FramingGuide), so nothing shows
    // this on screen. If this test starts failing because the medium verdict is no longer
    // "lift-phone", HEADROOM_MAX was retuned: update it. Do NOT turn a live match on without that retune.
    const expected: Record<'CU' | 'MCU' | 'MS', string> = { CU: 'ok', MCU: 'lift-phone', MS: 'lift-phone' };
    for (const size of ['CU', 'MCU', 'MS'] as const) {
      const target = targetForShotSize(size);
      const verdict = framingVerdict(faceOnEyeBand(size, HEIGHT_SHARE[target]), frame, target);
      expect(verdict.advice).not.toBe('come-closer');
      expect(verdict.advice).not.toBe('step-back');
      expect(verdict.advice).toBe(expected[size]);
    }
  });
});
