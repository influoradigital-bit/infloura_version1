/**
 * `parseMeeraScript` (meera-result-cards.ts) is hand-written against the "Full script format"
 * section of `influora-ai/app/prompt/creator_persona.py` — the labels it matches (Idea, Plan,
 * Action, Success looks like, Script, Caption, Before you shoot, Why this works, and the Shot/
 * Say/On screen markers inside a beat) are copied from that section, not derived from it at
 * runtime. Nothing stops the two from drifting apart the next time someone edits the persona's
 * wording, so this test reads the persona file directly (same idea as
 * `meera-api.creator-tools-in-sync.test.ts`'s guard on the creator tool list) and fails loudly if
 * any label this parser depends on stops appearing in that section.
 *
 * This only catches a label disappearing or being renamed — it does not (and cannot) verify the
 * parser's line SHAPE still matches the persona's prose. That is what the hand-written fixtures in
 * `meera-result-cards.test.ts` are for.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { parseMeeraScript, SHOT_CARD_KEYS } from './meera-result-cards';

// Path relative to THIS test file, not `process.cwd()` — vitest can be invoked from a few
// different working directories (see `meera-api.creator-tools-in-sync.test.ts`'s `process.cwd()`
// approach, which assumes the repo root; this one does not need that assumption).
const TEST_DIR = dirname(fileURLToPath(import.meta.url));
const PERSONA_PATH = join(TEST_DIR, '..', '..', 'influora-ai', 'app', 'prompt', 'creator_persona.py');

const SECTION_START = 'Full script format (only when asked):';
// The next heading after the rich section. Keeps the slice scoped to the script format only, not
// spilling into the Profile review format's own, differently-shaped labels.
const SECTION_END = 'Profile review format (only when asked to review their profile):';

/** The persona file's "Full script format" section, and nothing else — so a label match below
 *  can only come from the section this parser actually implements, not some unrelated part of
 *  the file (e.g. the Profile review format's own, differently-shaped labels). */
function fullScriptFormatSection(): string {
  const source = readFileSync(PERSONA_PATH, 'utf8');
  const start = source.indexOf(SECTION_START);
  const end = source.indexOf(SECTION_END);
  expect(start, `"${SECTION_START}" heading not found in creator_persona.py`).toBeGreaterThanOrEqual(0);
  expect(end, `"${SECTION_END}" heading not found in creator_persona.py`).toBeGreaterThan(start);
  return source.slice(start, end);
}

/** Every label `parseMeeraScript` matches against, in `meera-result-cards.ts`. */
const REQUIRED_LABELS = [
  'Idea:',
  // Optional in the PARSER (owner decision B, 2026-09-26: right after Idea; older replies without
  // it still parse), but the label must exist in the persona so a model writes it.
  'Made for:',
  'Plan:',
  'Action:',
  // Optional in the PARSER (added to the persona 2026-09-25, right after Action; older replies
  // without it still parse), but the label must exist in the persona so a model writes it.
  'Set-up:',
  // In the persona's layout; the PARSER tolerates it missing (see meera-result-cards.ts), but the
  // label must still exist in the persona so a model that writes it is parsed.
  'Success looks like:',
  'Script:',
  'Caption:',
  'Before you shoot:',
  'Why this works:',
  'Shot:',
  'Say:',
  // Optional in the PARSER (see ScriptBeat.stress/.pause in meera-result-cards.ts), but the
  // labels must still exist in the persona so a model that writes them is parsed, same as
  // "Success looks like:" above.
  'Stress:',
  'Pause:',
  'On screen:',
  // Optional in the PARSER (spec v2 Phase 6: replies without the block still parse), but the
  // label must exist in the persona so a model that writes the block is read.
  'Shot cards:',
];

describe('parseMeeraScript labels stay in sync with creator_persona.py', () => {
  it("names every label the parser depends on inside the persona's Full script format section", () => {
    const section = fullScriptFormatSection();
    for (const label of REQUIRED_LABELS) {
      expect(section.includes(label), `expected "${label}" inside the Full script format section`).toBe(true);
    }
  });
});

describe("the persona's Shot cards line is the one the parser reads", () => {
  it('its S1 example line has the 13 keys in the parser order and parses into a card', () => {
    const section = fullScriptFormatSection();
    const example = /^\s*(S1: size=.*)$/m.exec(section)?.[1]?.trim();
    expect(example, "the persona's S1 example line moved or changed shape").toBeTruthy();
    const keys = example!
      .slice('S1:'.length)
      .split(';')
      .map((pair) => pair.split('=')[0].trim());
    expect(keys).toEqual([...SHOT_CARD_KEYS]);
    const script = [
      'Idea: a short title',
      'Plan: for whom; the feeling; the goal; 20 seconds',
      'Action: speak to camera',
      'Script:',
      '0-3s. Shot: Close-up - speak. Say: "one". On screen: ONE',
      '3-6s. Shot: Close-up - speak. Say: "two". On screen: TWO',
      '6-9s. Shot: Close-up - speak. Say: "three". On screen: THREE',
      'Shot cards:',
      example!,
      'Caption: a caption',
      'Before you shoot: 1) one 2) two 3) three',
      'Why this works: a reason',
    ].join('\n');
    const card = parseMeeraScript(script)?.beats[0].card;
    expect(card).toBeDefined();
    expect(Object.keys(card!)).toEqual([...SHOT_CARD_KEYS]);
  });
});
