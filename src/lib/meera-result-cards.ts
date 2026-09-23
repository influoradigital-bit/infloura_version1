/**
 * T-MEERA-CREATOR-PHASE-C (PHASE-C-SPEC.md §1) — parses Meera's two fixed-shape replies (a reel
 * script, a profile review) into structured data a card can render.
 *
 * Pure functions, no React, no new dependency. Both parsers are deliberately STRICT: anything
 * that does not match the contract in PHASE-C-SPEC.md returns `null`, and the caller (the chat)
 * falls back to the plain message bubble it already renders today. A half-parsed card must never
 * render — that rule is enforced entirely by these functions returning `null` early rather than
 * returning a partially-filled object.
 *
 * Tolerant (does not change meaning, so does not cause a `null`):
 *   - leading/trailing blank lines
 *   - `\r\n` line endings
 *   - one space either side of the `:` in `Key: value`
 *   - the first line (`SCRIPT`/`REVIEW`) and every key in upper/lower/mixed case
 *   - Hindi (or any) text in the values
 *   - a missing `Why` line on a script (the only optional key either contract has)
 *
 * Strict (returns `null`):
 *   - the first line is not `SCRIPT` / `REVIEW`
 *   - a required key is missing, out of order, or its value is empty
 *   - a timed line's key does not match `<start>-<end>s`
 *   - the beats do not start at 0, are out of order, overlap, leave a gap, or do not end exactly
 *     at the script's stated `Length`
 *   - fewer than 3 or more than 6 beats
 *   - `Length` is not one of `15s|30s|45s|60s`
 *   - the review does not have exactly 3 `Next N` lines
 *   - anything appears before the first line, or after the last recognised line
 */

// ---------------------------------------------------------------------------
// Shared line handling
// ---------------------------------------------------------------------------

/** One `<start>-<end>s: <text>` line from a script. */
export interface ScriptBeat {
  from: number;
  to: number;
  text: string;
}

export interface ParsedMeeraScript {
  title: string;
  length: number;
  hook: string;
  beats: ScriptBeat[];
  cta: string;
  why?: string;
}

export interface ParsedMeeraReview {
  working: string;
  notWorking: string;
  nextSteps: string[];
}

const SCRIPT_LENGTHS = [15, 30, 45, 60];

/**
 * Normalizes `\r\n` to `\n`, splits into lines, and trims fully-blank lines only off the start
 * and end — never in the middle, where a blank line is a genuine formatting break the parser
 * should refuse on (via the sequential key checks below), not silently swallow.
 */
function splitLines(raw: string): string[] {
  const normalized = raw.replace(/\r\n/g, '\n');
  const rawLines = normalized.split('\n');
  let start = 0;
  let end = rawLines.length;
  while (start < end && rawLines[start].trim() === '') start++;
  while (end > start && rawLines[end - 1].trim() === '') end--;
  return rawLines.slice(start, end);
}

interface KeyValue {
  key: string;
  value: string;
}

/** Splits on the FIRST `:` only, so a value that itself contains a colon is never truncated. */
function splitKeyValue(line: string): KeyValue | null {
  const idx = line.indexOf(':');
  if (idx === -1) return null;
  const key = line.slice(0, idx).trim();
  const value = line.slice(idx + 1).trim();
  if (!key) return null;
  return { key, value };
}

/** Case- and whitespace-insensitive key match ("Not working" === "not   working"). */
function keyIs(kv: KeyValue, expected: string): boolean {
  return kv.key.toLowerCase().replace(/\s+/g, ' ') === expected;
}

// ---------------------------------------------------------------------------
// parseMeeraScript
// ---------------------------------------------------------------------------

export function parseMeeraScript(text: string): ParsedMeeraScript | null {
  if (typeof text !== 'string') return null;
  const lines = splitLines(text);
  // Minimum shape: SCRIPT, Title, Length, Hook, 3 beats, CTA = 8 lines. Why is optional.
  if (lines.length < 8) return null;

  let i = 0;
  if (lines[i].trim().toUpperCase() !== 'SCRIPT') return null;
  i++;

  const titleKV = splitKeyValue(lines[i]);
  i++;
  if (!titleKV || !keyIs(titleKV, 'title') || !titleKV.value) return null;

  const lengthKV = splitKeyValue(lines[i]);
  i++;
  if (!lengthKV || !keyIs(lengthKV, 'length')) return null;
  const lengthMatch = /^(\d+)s$/i.exec(lengthKV.value.trim());
  if (!lengthMatch) return null;
  const length = parseInt(lengthMatch[1], 10);
  if (!SCRIPT_LENGTHS.includes(length)) return null;

  const hookKV = splitKeyValue(lines[i]);
  i++;
  if (!hookKV || !keyIs(hookKV, 'hook') || !hookKV.value) return null;

  const beats: ScriptBeat[] = [];
  while (i < lines.length) {
    const kv = splitKeyValue(lines[i]);
    if (!kv) break;
    const beatMatch = /^(\d+)-(\d+)s$/i.exec(kv.key.trim());
    if (!beatMatch) break;
    if (!kv.value) return null;
    beats.push({ from: parseInt(beatMatch[1], 10), to: parseInt(beatMatch[2], 10), text: kv.value });
    i++;
  }
  if (beats.length < 3 || beats.length > 6) return null;
  if (beats[0].from !== 0) return null;
  for (let b = 0; b < beats.length; b++) {
    if (beats[b].to <= beats[b].from) return null;
    if (b > 0 && beats[b].from !== beats[b - 1].to) return null;
  }
  if (beats[beats.length - 1].to !== length) return null;

  if (i >= lines.length) return null;
  const ctaKV = splitKeyValue(lines[i]);
  i++;
  if (!ctaKV || !keyIs(ctaKV, 'cta') || !ctaKV.value) return null;

  let why: string | undefined;
  if (i < lines.length) {
    const whyKV = splitKeyValue(lines[i]);
    i++;
    if (!whyKV || !keyIs(whyKV, 'why') || !whyKV.value) return null;
    why = whyKV.value;
  }

  // Nothing left over — a line after `Why` (or after `CTA` when `Why` is absent) is unexplained
  // content the contract does not allow.
  if (i !== lines.length) return null;

  return { title: titleKV.value, length, hook: hookKV.value, beats, cta: ctaKV.value, why };
}

// ---------------------------------------------------------------------------
// parseMeeraReview
// ---------------------------------------------------------------------------

export function parseMeeraReview(text: string): ParsedMeeraReview | null {
  if (typeof text !== 'string') return null;
  const lines = splitLines(text);
  // Fixed shape, no optional keys: REVIEW, Working, Not working, Next 1, Next 2, Next 3.
  if (lines.length !== 6) return null;

  let i = 0;
  if (lines[i].trim().toUpperCase() !== 'REVIEW') return null;
  i++;

  const workingKV = splitKeyValue(lines[i]);
  i++;
  if (!workingKV || !keyIs(workingKV, 'working') || !workingKV.value) return null;

  const notWorkingKV = splitKeyValue(lines[i]);
  i++;
  if (!notWorkingKV || !keyIs(notWorkingKV, 'not working') || !notWorkingKV.value) return null;

  const nextSteps: string[] = [];
  for (let n = 1; n <= 3; n++) {
    const kv = splitKeyValue(lines[i]);
    i++;
    if (!kv || !keyIs(kv, `next ${n}`) || !kv.value) return null;
    nextSteps.push(kv.value);
  }

  if (i !== lines.length) return null;
  if (nextSteps.length < 3) return null;

  return { working: workingKV.value, notWorking: notWorkingKV.value, nextSteps };
}
