/**
 * shot-zones: the live camera guide's geometry (spec v2 Phase 5a + 5b).
 *
 * Run: npx vitest run src/lib/shoot-check/shot-zones.test.ts
 */
import { describe, expect, it } from 'vitest';

import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { targetForShotSize, type ShotCard } from './beat-to-shot';
import { THRESHOLDS, framingVerdict, type ShotTarget } from './metrics';
import { INTERIM_SAFE_ZONES, type SafeZones } from './safe-zones';
import {
  CARD_PROP_Y,
  CARD_TOP_TEXT,
  EDGE_MARKER_Y,
  GOLDEN_LINES,
  PLANE,
  PROP_RAIL_GAP,
  PROP_ZONE_WIDTH,
  TEXT_AREA_GAP,
  THIRDS_LINES,
  chipText,
  clipVisible,
  textArea,
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

  it('no card: the size chip first (never the angle text), then where, light, sit_or_walk, at most 3', () => {
    const full = shot('0-3s · Medium shot', 'medium', {
      angle: 'Eye level',
      where: 'Bedroom, by the window and the long cupboard',
      light: 'Window on your left',
      sit_or_walk: 'Sit',
      line: 'never a chip',
      action: 'never a chip',
    });
    const chips = setupChips(full, 'Medium shot');
    // Decision 3: the chip that said "angle" is the shot size, labelled as such.
    expect(chips.map((c) => c.kind)).toEqual(['size', 'where', 'light']);
    expect(chips[0]).toEqual({ kind: 'size', text: 'Medium shot', full: 'Medium shot' });
    expect(chips.map((c) => c.text)).not.toContain('Eye level');
    expect(chips[1]).toEqual({ kind: 'where', text: 'Bedroom, by the…', full: 'Bedroom, by the window and the long cupboard' });
    const sparse = setupChips(shot('0-3s · x', 'medium', { where: '  ', light: 'Soft lamp' }), 'Medium shot');
    expect(sparse.map((c) => c.text)).toEqual(['Medium shot', 'Soft lamp']);
  });

  it('with a card: size, height, light, place, each only when the card knows it; `?` adds no chip', () => {
    const words = { height: 'Eye level', light: 'Window, your left' };
    const known = setupChips(
      cardShot({ place: 'Bedroom desk by the long window seat' }, { where: 'the Set-up line', sit_or_walk: 'Sit' }),
      'Medium close-up',
      words
    );
    expect(known.map((c) => c.kind)).toEqual(['size', 'height', 'light', 'where']);
    expect(known.map((c) => c.text)).toEqual(['Medium close-up', 'Eye level', 'Window, your left', 'Bedroom desk by…']);

    // Unknown card fields get no chip, and nothing is filled in from the context instead.
    const unknown = setupChips(
      cardShot({ place: '?', height: '?', light: '?' }, { where: 'the Set-up line', light: 'lamp', sit_or_walk: 'Sit' }),
      'Medium close-up',
      { height: null, light: null }
    );
    expect(unknown.map((c) => c.kind)).toEqual(['size']);
  });
});

// ---------------------------------------------------------------------------
// Shot card (spec v2 Phase 6, decision 3)
// ---------------------------------------------------------------------------

const FULL_CARD: ShotCard = {
  size: 'MCU',
  height: 'eye',
  distance: '0.8-1 m',
  place: 'Bedroom desk',
  light: 'window-left',
  stand: 'left',
  headroom: 'small',
  eyes: 'lens',
  background: 'plain wall',
  space: 'right',
  text: 'top',
  prop: 'right-hand',
  move: 'still',
};

function cardShot(card: Partial<ShotCard>, context?: ShootCheckShot['context'], target: ShotTarget = 'medium'): ShootCheckShot {
  return { index: 0, label: '0-3s · Medium shot - hold the serum', seconds: 3, target, context, card: { ...FULL_CARD, ...card } };
}

describe('shot size from the card', () => {
  it('the card size comes before the shot words; `?` falls back to the words', () => {
    expect(shotSizeFor(cardShot({ size: 'OVERHEAD' }, { line: 'Medium shot - hold it' }))).toBe('OVERHEAD');
    expect(shotSizeFor(cardShot({ size: '?' }, { line: 'Close-up - hold it' }))).toBe('CU');
  });
});

describe('prop zone from the card (side x hand/table/floor)', () => {
  const railLimit = ZONES.rail.x * PLANE.width - PROP_RAIL_GAP;
  const captionY = ZONES.captionLine * PLANE.height;

  it('every one of the 9 positions gives a zone on its side, left of the rail', () => {
    for (const side of ['left', 'centre', 'right'] as const) {
      for (const surface of ['hand', 'table', 'floor'] as const) {
        const zone = propZone({ shot: cardShot({ prop: `${side}-${surface}` }), size: 'MS', grid: 'thirds', mirrored: true, zones: ZONES })!;
        expect(zone, `${side}-${surface}`).not.toBeNull();
        expect(zone.side).toBe(side);
        expect(zone.place).toBe(surface);
        expect(zone.fromCard).toBe(true);
        expect(zone.rect.x + zone.rect.width).toBeLessThanOrEqual(railLimit);
        expect(zone.rect.height).toBeGreaterThan(0);
        if (side === 'centre') {
          expect(zone.snappedTo).toBeNull();
          expect(zone.rect.x + zone.rect.width / 2).toBeCloseTo(PLANE.width / 2);
        } else {
          expect(zone.snappedTo).toBe(side === 'left' ? 360 : 720);
          expect(zone.rect.width).toBe(PROP_ZONE_WIDTH);
        }
      }
    }
  });

  it('hand and table end above the caption line; floor sits low (v1 3 x 3 table)', () => {
    const at = (prop: ShotCard['prop'], size: 'MS' | 'MLS') =>
      propZone({ shot: cardShot({ prop }), size, grid: 'thirds', mirrored: true, zones: ZONES })!;
    for (const size of ['MS', 'MLS'] as const) {
      expect(at('left-hand', size).rect.y + at('left-hand', size).rect.height).toBeLessThanOrEqual(captionY);
      expect(at('left-table', size).rect.y + at('left-table', size).rect.height).toBeLessThanOrEqual(captionY);
    }
    // Medium floor: 0.86-0.98 of the frame, not clipped (props may sit under covered areas).
    const floor = at('left-floor', 'MS');
    expect(CARD_PROP_Y.medium.floor).toEqual([0.86, 0.98]);
    expect(floor.rect.y).toBeCloseTo(0.86 * PLANE.height);
    expect(floor.rect.y + floor.rect.height).toBeCloseTo(0.98 * PLANE.height);
    // Wide: the hand zone is higher than the table zone, the table higher than the floor.
    expect(at('left-hand', 'MLS').rect.y).toBeLessThan(at('left-table', 'MLS').rect.y);
    expect(at('left-table', 'MLS').rect.y).toBeLessThan(at('left-floor', 'MLS').rect.y);
  });

  it('a close-up\'s table or floor prop is an edge marker at the bottom edge', () => {
    const zone = propZone({ shot: cardShot({ prop: 'right-table' }), size: 'CU', grid: 'thirds', mirrored: true, zones: ZONES })!;
    expect(zone.edgeMarker).toBe(true);
    expect(zone.rect.y).toBeCloseTo(EDGE_MARKER_Y[0] * PLANE.height);
    const inHand = propZone({ shot: cardShot({ prop: 'right-hand' }), size: 'CU', grid: 'thirds', mirrored: true, zones: ZONES })!;
    expect(inHand.edgeMarker).toBe(false);
  });

  it('the creator\'s right is on screen right in the mirrored preview; the rear camera flips it', () => {
    const front = propZone({ shot: cardShot({ prop: 'right-table' }), size: 'MS', grid: 'golden', mirrored: true, zones: ZONES })!;
    expect(front.screenSide).toBe('right');
    expect(front.snappedTo).toBe(GOLDEN_LINES.vertical[1]);
    const rear = propZone({ shot: cardShot({ prop: 'right-table' }), size: 'MS', grid: 'golden', mirrored: false, zones: ZONES })!;
    expect(rear.side).toBe('right');
    expect(rear.screenSide).toBe('left');
    expect(rear.snappedTo).toBe(GOLDEN_LINES.vertical[0]);
  });

  it('the card overrides v1\'s default side (a medium shot\'s default is your right)', () => {
    const zone = propZone({ shot: cardShot({ prop: 'left-hand' }, { prop: 'serum' }), size: 'MS', grid: 'thirds', mirrored: true, zones: ZONES })!;
    expect(zone.side).toBe('left');
    expect(zone.prop).toBe('serum');
  });

  it('`none` and `?` draw nothing, even when the context names a prop', () => {
    for (const prop of ['none', '?'] as const) {
      expect(propZone({ shot: cardShot({ prop }, { prop: 'serum bottle' }), size: 'MS', grid: 'thirds', mirrored: true, zones: ZONES })).toBeNull();
    }
  });
});

describe('text area from the card', () => {
  const captionY = ZONES.captionLine * PLANE.height;
  const railLimit = ZONES.rail.x * PLANE.width - TEXT_AREA_GAP;

  it('no card: the size table\'s hook-text area (unchanged Phase 5a behaviour)', () => {
    const noCard = shot('0-3s · Medium shot', 'medium');
    const area = textArea({ shot: noCard, bands: shotBands('MS'), zones: ZONES, mirrored: true })!;
    expect(area).toEqual({ rect: hookTextArea(shotBands('MS'), ZONES), kind: 'top', screenSide: null, fromCard: false });
    expect(textArea({ shot: noCard, bands: shotBands('CU'), zones: ZONES, mirrored: true })).toBeNull();
  });

  it('top: the size\'s hook band, or one line under the top covered area for a size without one', () => {
    const ms = textArea({ shot: cardShot({ text: 'top' }), bands: shotBands('MS'), zones: ZONES, mirrored: true })!;
    expect(ms.kind).toBe('top');
    expect(ms.rect.y + ms.rect.height).toBeCloseTo(480);
    const cu = textArea({ shot: cardShot({ text: 'top' }), bands: shotBands('CU'), zones: ZONES, mirrored: true })!;
    expect(cu.rect.y).toBeGreaterThanOrEqual(ZONES.top * PLANE.height);
    expect(cu.rect.y + cu.rect.height).toBeCloseTo(CARD_TOP_TEXT.to);
  });

  it('lower_middle: ends above the caption line and left of the rail', () => {
    const area = textArea({ shot: cardShot({ text: 'lower_middle' }), bands: shotBands('MS'), zones: ZONES, mirrored: true })!;
    expect(area.kind).toBe('lower_middle');
    expect(area.rect.y + area.rect.height).toBeLessThanOrEqual(captionY);
    expect(area.rect.x + area.rect.width).toBeLessThanOrEqual(railLimit);
    expect(area.rect.height).toBeGreaterThan(0);
  });

  it('opposite_face: on the other screen side from the face, level with the eye line; flips with the rear camera', () => {
    // Standing on your own left: in the mirrored preview your face is on screen left, text on the right.
    const front = textArea({ shot: cardShot({ text: 'opposite_face', stand: 'left' }), bands: shotBands('MCU'), zones: ZONES, mirrored: true })!;
    expect(front.screenSide).toBe('right');
    expect(front.rect.x).toBeGreaterThan(PLANE.width / 2);
    expect(front.rect.x + front.rect.width).toBeLessThanOrEqual(railLimit);
    expect(front.rect.y).toBeLessThanOrEqual(640);
    expect(front.rect.y + front.rect.height).toBeGreaterThanOrEqual(733);
    const rear = textArea({ shot: cardShot({ text: 'opposite_face', stand: 'left' }), bands: shotBands('MCU'), zones: ZONES, mirrored: false })!;
    expect(rear.screenSide).toBe('left');
    expect(rear.rect.x + rear.rect.width).toBeLessThan(PLANE.width / 2);
  });

  it('draws nothing for `none`, `?`, an unknown or centre stand, or a size with no eye line', () => {
    const none = (card: Partial<ShotCard>, size: 'MS' | 'FS' = 'MS') =>
      textArea({ shot: cardShot(card), bands: shotBands(size), zones: ZONES, mirrored: true });
    expect(none({ text: 'none' })).toBeNull();
    expect(none({ text: '?' })).toBeNull();
    expect(none({ text: 'opposite_face', stand: '?' })).toBeNull();
    expect(none({ text: 'opposite_face', stand: 'centre' })).toBeNull();
    expect(none({ text: 'opposite_face', stand: 'left' }, 'FS')).toBeNull();
  });

  it('hookTextArea follows the card when it is given the shot, and is unchanged without it', () => {
    const card = cardShot({ text: 'lower_middle' });
    expect(hookTextArea(shotBands('MS'), ZONES, card, true)).toEqual(
      textArea({ shot: card, bands: shotBands('MS'), zones: ZONES, mirrored: true })!.rect
    );
    expect(hookTextArea(shotBands('MS'), ZONES, cardShot({ text: '?' }), true)).toBeNull();
    expect(hookTextArea(shotBands('MS'), ZONES)).toEqual(hookTextArea(shotBands('MS'), ZONES, null));
  });
});

describe('clipVisible (the Say and On-screen lines)', () => {
  it('keeps short text, cuts long text at a space, and never splits a Devanagari syllable', () => {
    expect(clipVisible('Is your saffron even real?', 120)).toBe('Is your saffron even real?');
    const hindi = 'क्या आपका केसर सच में असली है? '.repeat(8).trim();
    const cut = clipVisible(hindi, 40);
    expect(cut.endsWith('…')).toBe(true);
    expect(visibleChars(cut).length).toBeLessThanOrEqual(40);
    const kept = cut.slice(0, -1);
    expect(hindi.startsWith(kept)).toBe(true);
    // The next character after the cut starts a new syllable, never a dangling vowel sign/virama.
    expect(/^\p{M}/u.test(hindi.slice(kept.length).trimStart())).toBe(false);
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
