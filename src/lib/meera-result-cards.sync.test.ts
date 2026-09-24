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

// Path relative to THIS test file, not `process.cwd()` — vitest can be invoked from a few
// different working directories (see `meera-api.creator-tools-in-sync.test.ts`'s `process.cwd()`
// approach, which assumes the repo root; this one does not need that assumption).
const TEST_DIR = dirname(fileURLToPath(import.meta.url));
const PERSONA_PATH = join(TEST_DIR, '..', '..', 'influora-ai', 'app', 'prompt', 'creator_persona.py');

const SECTION_START = 'Full script format (only when asked):';
// On this line the persona still carries the old Phase C SCRIPT/REVIEW block right after the rich
// "Full script format" section (a concurrent change on another line is removing that block and
// replacing this heading with "Profile review format (only when asked to review their profile):"
// — when that lands here, update this constant to match). Until then, this is the actual next
// heading in creator_persona.py, and using it keeps the slice scoped to the rich section only, not
// spilling into Phase C's differently-shaped SCRIPT/REVIEW labels.
const SECTION_END = 'Two replies have a fixed shape (Phase C).';

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
  'Plan:',
  'Action:',
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
];

describe('parseMeeraScript labels stay in sync with creator_persona.py', () => {
  it("names every label the parser depends on inside the persona's Full script format section", () => {
    const section = fullScriptFormatSection();
    for (const label of REQUIRED_LABELS) {
      expect(section.includes(label), `expected "${label}" inside the Full script format section`).toBe(true);
    }
  });
});
