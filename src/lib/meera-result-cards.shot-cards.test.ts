/**
 * The optional `Shot cards:` block of a full script (spec v2 Phase 6) and the parity gate with the
 * Python twin (`influora-ai/app/recommendations/script_card.py`, spec risk R6).
 *
 * The shared fixture `influora-ai/tests/fixtures/meera_scripts.json` holds every script text both
 * parsers must agree on. This file runs ALL of it (`cases`, `edge_cases`, `shot_card_cases`)
 * through the real `parseMeeraScript` and asserts the recorded verdict and, for the shot-card
 * cases, every card field; `tests/recommendations/test_script_card_parity.py` asserts the same
 * recorded values against the Python port. Both sides pinned to one set of values = parity: if
 * either parser drifts, its own test goes red.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  parseMeeraScript,
  SHOT_CARD_KEYS,
  shotLightParts,
  shotPropParts,
  type ParsedMeeraScript,
  type ShotCard,
} from './meera-result-cards';

const TEST_DIR = dirname(fileURLToPath(import.meta.url));
const FIXTURE_PATH = join(TEST_DIR, '..', '..', 'influora-ai', 'tests', 'fixtures', 'meera_scripts.json');

interface FixtureCase {
  name: string;
  text: string;
  expected: 'card' | 'none';
}
interface ShotCardCase extends FixtureCase {
  cards: Array<ShotCard | null> | null;
}
interface Fixture {
  cases: FixtureCase[];
  edge_cases: FixtureCase[];
  shot_card_cases: ShotCardCase[];
}

const FIXTURE: Fixture = JSON.parse(readFileSync(FIXTURE_PATH, 'utf8'));
const SHOT_CASES = new Map(FIXTURE.shot_card_cases.map((c) => [c.name, c]));

function shotCase(name: string): ShotCardCase {
  const found = SHOT_CASES.get(name);
  if (!found) throw new Error(`fixture case ${name} is missing`);
  return found;
}

function cardsOf(parsed: ParsedMeeraScript | undefined): Array<ShotCard | null> | null {
  return parsed ? parsed.beats.map((beat) => beat.card ?? null) : null;
}

describe('parity with the Python port: the shared fixture through the real TS parser', () => {
  const all = [...FIXTURE.cases, ...FIXTURE.edge_cases, ...FIXTURE.shot_card_cases];

  it('reads a non-empty fixture with every section (a gate over nothing proves nothing)', () => {
    expect(FIXTURE.cases.length).toBeGreaterThanOrEqual(30);
    expect(FIXTURE.edge_cases.length).toBeGreaterThanOrEqual(10);
    expect(FIXTURE.shot_card_cases.length).toBeGreaterThanOrEqual(15);
    const cards = FIXTURE.shot_card_cases.flatMap((c) => c.cards ?? []).filter((c): c is ShotCard => c !== null);
    expect(cards.length).toBeGreaterThanOrEqual(30);
    expect(cards.some((card) => Object.values(card).includes('?'))).toBe(true);
    for (const card of cards) expect(Object.keys(card)).toEqual([...SHOT_CARD_KEYS]);
    for (const name of [
      'shot_cards_every_beat',
      'shot_cards_absent_still_a_card',
      'shot_cards_malformed_line_keeps_the_beat',
      'shot_cards_unknown_enum_values',
      'shot_cards_free_text_at_and_over_the_cap',
      'shot_cards_fewer_lines_than_beats',
      'shot_cards_hindi_reply',
    ]) {
      expect(SHOT_CASES.has(name), name).toBe(true);
    }
  });

  it.each(all.map((c) => [c.name, c] as const))('%s: the recorded verdict', (_name, c) => {
    expect(parseMeeraScript(c.text) === undefined ? 'none' : 'card').toBe(c.expected);
  });

  it.each(FIXTURE.shot_card_cases.map((c) => [c.name, c] as const))('%s: every card field', (_name, c) => {
    expect(cardsOf(parseMeeraScript(c.text))).toEqual(c.cards);
  });

  // Owner decision B (2026-09-26): a fixture case that records `made_for` (in any section of the
  // fixture, including one added for it) pins the parsed `madeFor` too — the value, or null when
  // the line is absent, empty or over 200 code points (ignored, not refused).
  const madeForCases = Object.values(FIXTURE as unknown as Record<string, unknown>)
    .filter((value): value is unknown[] => Array.isArray(value))
    .flat()
    .filter((c): c is FixtureCase & { made_for: string | null } => !!c && typeof c === 'object' && 'made_for' in c);

  it('every fixture case with a made_for is read to exactly that value by the TS parser', () => {
    // A gate over nothing proves nothing: at least one kept value and one ignored/absent line.
    expect(madeForCases.some((c) => c.made_for !== null), 'no fixture case with a kept made_for').toBe(true);
    expect(madeForCases.some((c) => c.made_for === null), 'no fixture case with made_for null').toBe(true);
    for (const c of madeForCases) {
      const parsed = parseMeeraScript(c.text);
      expect(parsed === undefined ? 'none' : 'card', c.name).toBe(c.expected);
      expect(parsed?.madeFor ?? null, c.name).toBe(c.made_for);
    }
  });
});

describe('parseMeeraScript — the Shot cards block', () => {
  it('parses a full block into one typed card per beat', () => {
    const parsed = parseMeeraScript(shotCase('shot_cards_every_beat').text);
    expect(parsed?.beats[0].card).toEqual({
      size: 'MCU',
      height: 'eye',
      distance: '0.8-1 m',
      place: 'Bedroom desk',
      light: 'window-left',
      stand: 'centre',
      headroom: 'small',
      eyes: 'lens',
      background: 'plain wall',
      space: 'right',
      text: 'top',
      prop: 'right-hand',
      move: 'still',
    });
    expect(parsed?.beats[1].card?.size).toBe('OVERHEAD');
    expect(parsed?.beats[1].card?.prop).toBe('centre-table');
    expect(parsed?.beats[2].card?.text).toBe('opposite_face');
  });

  it('a reply without the block still parses, exactly as before and with no card key', () => {
    const withBlock = parseMeeraScript(shotCase('shot_cards_every_beat').text)!;
    const without = parseMeeraScript(shotCase('shot_cards_absent_still_a_card').text)!;
    expect(without).toBeDefined();
    for (const beat of without.beats) expect('card' in beat).toBe(false);
    const stripped = { ...withBlock, beats: withBlock.beats.map(({ card: _card, ...rest }) => rest) };
    expect(stripped).toStrictEqual(without);
  });

  it('a malformed line keeps its beat, with no card, and the other beats keep theirs', () => {
    const parsed = parseMeeraScript(shotCase('shot_cards_malformed_line_keeps_the_beat').text)!;
    expect(parsed.beats).toHaveLength(3);
    expect(parsed.beats[1].say).toBe('Watch what happens in water');
    expect(parsed.beats[1].card).toBeUndefined();
    expect(parsed.beats[0].card?.size).toBe('MCU');
    expect(parsed.beats[2].card?.size).toBe('CU');
  });

  it('an unknown enum value or an over-long free text is "?" for that field only', () => {
    const enums = parseMeeraScript(shotCase('shot_cards_unknown_enum_values').text)!;
    const first = enums.beats[0].card!;
    expect([first.size, first.height, first.light, first.stand, first.prop, first.move]).toEqual(['?', '?', '?', '?', '?', '?']);
    expect(first.place).toBe('Bedroom desk');
    expect(enums.beats[1].card?.light).toBe('ring_light-front');
    const capped = parseMeeraScript(shotCase('shot_cards_free_text_at_and_over_the_cap').text)!;
    expect(capped.beats[0].card?.place).toBe('p'.repeat(40));
    expect(capped.beats[1].card?.place).toBe('?');
    expect(capped.beats[1].card?.distance).toBe('?');
    // 40 code points (50 UTF-16 units): counted like Python's len, so kept.
    expect(capped.beats[2].card?.place).toBe(`${'\u{1F3E0}'.repeat(10)}${'x'.repeat(30)}`);
  });

  it('fewer lines than beats: only the named beats get a card', () => {
    const parsed = parseMeeraScript(shotCase('shot_cards_fewer_lines_than_beats').text)!;
    expect(parsed.beats.map((b) => b.card !== undefined)).toEqual([true, false, true]);
  });

  it('a Hindi reply keeps English keys and enums, and free text in Hindi', () => {
    const parsed = parseMeeraScript(shotCase('shot_cards_hindi_reply').text)!;
    expect(parsed.beats[0].card?.place).toBe('बेडरूम की डेस्क');
    expect(parsed.beats[0].card?.size).toBe('MCU');
    expect(parsed.beats[2].card?.prop).toBe('?');
    expect(parsed.followUp).toBe('वॉइस-ओवर किस भाषा में चाहिए?');
  });

  it('a block in the wrong place, or a header with a value, is not a script card', () => {
    for (const name of [
      'shot_cards_header_with_value',
      'shot_cards_lines_without_header',
      'shot_cards_after_caption',
      'shot_cards_block_but_no_caption',
    ]) {
      expect(parseMeeraScript(shotCase(name).text), name).toBeUndefined();
    }
  });
});

describe('shot card value helpers', () => {
  it('splits light into kind and the creator’s own side', () => {
    expect(shotLightParts('window-left')).toEqual({ kind: 'window', side: 'left' });
    expect(shotLightParts('ring_light')).toEqual({ kind: 'ring_light' });
    expect(shotLightParts('?')).toBeNull();
  });

  it('splits prop into side and surface; none and unknown draw no prop zone', () => {
    expect(shotPropParts('right-hand')).toEqual({ side: 'right', surface: 'hand' });
    expect(shotPropParts('centre-table')).toEqual({ side: 'centre', surface: 'table' });
    expect(shotPropParts('none')).toBeNull();
    expect(shotPropParts('?')).toBeNull();
  });
});
