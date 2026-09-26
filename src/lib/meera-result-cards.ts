/**
 * Parses Meera's two fixed-shape replies (a full reel script, a profile review) into structured
 * data a card can render.
 *
 * `parseMeeraReview` is unchanged Phase-C work (still documented by PHASE-C-SPEC.md §1). Its
 * REVIEW contract never changed, so it is not repeated here.
 *
 * `parseMeeraScript` was rewritten for the "Full script format" in
 * `influora-ai/app/prompt/creator_persona.py` (search that file for that exact heading) — the
 * richer shape the product owner chose to replace Phase C's short `SCRIPT`/`Title`/`Length`/
 * `Hook`/beats/`CTA`/`Why` layout. That file is the source of truth for the contract below; if
 * this comment and that file ever disagree, the file wins (see
 * `meera-api.creator-tools-in-sync.test.ts` for the same principle applied to tool names, and
 * this file's own `*.sync.test.ts` for the equivalent guard on script labels).
 *
 * Pure functions, no React, no new dependency. Both parsers are deliberately STRICT: anything
 * that does not match its contract returns `undefined`/`null` (see each function's own doc for
 * which), and the caller (the chat) falls back to the plain message bubble it already renders
 * today. A half-parsed card must never render — that rule is enforced entirely by these functions
 * refusing early rather than returning a partially-filled object.
 *
 * `parseMeeraScript` — tolerant (does not change meaning, so does not cause a refusal):
 *   - leading/trailing blank lines
 *   - `\r\n` line endings
 *   - one space either side of the `:` in `Key: value`
 *   - every label in upper/lower/mixed case, and extra internal whitespace in a multi-word label
 *   - Hindi (or any) text in the values — the labels themselves (Idea, Plan, Action, Success
 *     looks like, Script, Caption, Before you shoot, Why this works, and the Shot/Say/Stress/
 *     Pause/On screen markers inside a beat) stay in English per the persona file, so only the
 *     labels are matched case/whitespace-insensitively; the surrounding prose is read as opaque
 *     text either way
 *   - a whole beat line optionally wrapped in one pair of straight (`"`) or curly (`“…”`) quotes —
 *     the persona file's own example line is shown quoted, but nothing says the model must
 *     reproduce those outer quotes literally, so both are accepted
 *   - the `Say: "…"` text using straight or curly double quotes
 *   - a missing `Success looks like:` line — see the parser's own comment at that line for why
 *     this is the one required-looking line that does not fail the whole card
 *   - a missing `Set-up:` line (added to the persona 2026-09-25, right after `Action:`) — older
 *     replies written before it existed still parse; the label is also read as `Setup:` or
 *     `Set up:`, the same meaning
 *   - a beat line's `Stress:`/`Pause:` markers, entirely — both are optional together (see
 *     `ScriptBeat.stress`/`.pause`), so a beat with just `Shot`/`Say`/`On screen` still parses
 *   - one trailing short question line after `Why this works:` (kept as `followUp`)
 *   - a missing `Shot cards:` block (spec v2 Phase 6) — replies written before it existed, or
 *     without it, parse exactly as before, with no `ScriptBeat.card`. When present it sits after
 *     the last beat and before `Caption:`: the line `Shot cards:`, then `S<n>: ` + 13 `key=value`
 *     pairs joined by `; ` (size, height, distance, place, light, stand, headroom, eyes,
 *     background, space, text, prop, move — this order). Inside the block nothing refuses the
 *     card: a malformed or unrecognised line, an `S<n>` for a beat that does not exist, or a beat
 *     named twice only means that beat has no card; fewer lines than beats is fine; an unknown
 *     enum value or an over-long free text becomes `'?'` for that one field (see `ShotCard`).
 *     `influora-ai/app/recommendations/script_card.py` is this parser's Python twin and must read
 *     the block identically (shared fixture: `influora-ai/tests/fixtures/meera_scripts.json`,
 *     `shot_card_cases`, pinned by `meera-result-cards.shot-cards.test.ts` and the pytest parity
 *     test), or SCRIPT_CARD recommendations silently stop being recorded (spec risk R6).
 *
 * `parseMeeraScript` — strict (returns `undefined`):
 *   - `Idea`, `Plan`, `Action`, `Script`, `Caption`, `Before you shoot` or `Why this works` is
 *     missing, out of order, or its value is empty
 *   - the `Script:` line itself carries a value (the beats belong on their own lines after it)
 *   - fewer than 3 beat lines, a beat line that does not match the
 *     `<start>-<end>s. Shot: … . Say: "…". [Stress: … . Pause: … .] On screen: ….` shape, or one
 *     with an empty Shot/Say/On-screen part, or only one of Stress/Pause present
 *   - the beats do not start at 0s, are out of order, overlap, or leave a gap
 *   - `Before you shoot:`'s value is not exactly three `1) … 2) … 3) …` items on that one line
 *   - anything appears before `Idea:`, or after the last recognised line (the optional follow-up
 *     question, when present)
 *   - the `Shot cards:` line itself carries a value, or the block appears anywhere but straight
 *     after the last beat (`S<n>:` lines without the `Shot cards:` line are not a block either)
 */
import { stripMeeraMarkdown } from './meera-text';

// ---------------------------------------------------------------------------
// Shared line handling
// ---------------------------------------------------------------------------

/**
 * One `<start>-<end>s. Shot: … . Say: "…". On screen: ….` line from a rich-format script — see
 * `BEAT_RE` below for the exact shape this is read from.
 */
export interface ScriptBeat {
  from: number;
  to: number;
  /** Camera angle and action, e.g. `Close-up on your face - hold up the saffron box`. */
  shot: string;
  /** The exact line to say on camera. */
  say: string;
  /** The one word or short phrase in `say` that carries the new/payoff information — the
   *  persona's `Stress:` marker. Optional: some beat lines omit the marker entirely (not just an
   *  empty value — see `BEAT_RE`), and a card with no Stress/Pause markers must still parse. */
  stress?: string;
  /** The natural break in `say` ("after \"<word>\", or none") — the persona's `Pause:` marker.
   *  Always present together with `stress` (one regex group covers both); never a length in
   *  seconds, just the persona's own text for where the pause falls. */
  pause?: string;
  /** The on-screen text overlay. */
  onScreen: string;
  /** This beat's shot card, from the optional `Shot cards:` block. Absent (not `undefined`-valued)
   *  when the reply has no block, or no well-formed `S<n>:` line for this beat. */
  card?: ShotCard;
}

export interface ParsedMeeraScript {
  idea: string;
  plan: string;
  action: string;
  /** Where the creator sits or stands and where the light falls (their left/right), where the
   *  phone goes (height, distance, lens), the phone settings, and how they move between spots.
   *  Optional: replies written before the persona added this line do not have it. */
  setup?: string;
  /** Optional — see the parser's own comment at this line for why. */
  successLooksLike?: string;
  beats: ScriptBeat[];
  caption: string;
  /** Always exactly 3 items — the persona's "numbered 1) 2) 3) on one line". */
  beforeYouShoot: [string, string, string];
  whyThisWorks: string;
  /** The optional short question the persona ends the script with (e.g. which language for the
   *  voice-over). Undefined when the reply ends at `Why this works:`. */
  followUp?: string;
}

export interface ParsedMeeraReview {
  working: string;
  notWorking: string;
  nextSteps: string[];
}

/**
 * Normalizes `\r\n` to `\n`, splits into lines, and trims fully-blank lines only off the start
 * and end — never in the middle, where a blank line is a genuine formatting break the parser
 * should refuse on (via the sequential key checks below), not silently swallow.
 */
function splitLines(raw: string): string[] {
  // `stripMeeraMarkdown` also normalises CRLF, drops `---` divider lines and unwraps `**bold**`,
  // so "**Caption:** ..." parses like "Caption: ..." (the model sometimes adds markdown).
  const normalized = stripMeeraMarkdown(raw);
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

/** Any of the double-quote characters the persona's Say text (or a whole beat line) may be
 *  wrapped in — straight ASCII, or the curly pair a phone keyboard/typical LLM output favours. */
const OPEN_QUOTE = '[“"]';
const CLOSE_QUOTE = '[”"]';
const QUOTE_CHARS = new Set(['"', '“', '”']);

/**
 * `0-3s. Shot: <camera angle> - <action>. Say: "<exact line>". Stress: <phrase>. Pause: after
 * "<word>", or none. On screen: <text>.` — the exact layout `creator_persona.py`'s "Full script
 * format" prescribes for a beat line. Non-greedy up to the first `.` after `Shot:` (the persona's
 * own shot text has no reason to contain a period); the Say text is read up to the first closing
 * quote immediately followed by `.`; `Stress:`/`Pause:` are ONE optional group (the persona always
 * emits them together, right after Say and before On screen) so a beat line written against an
 * earlier persona revision without them still parses; the on-screen text is whatever is left, with
 * one optional trailing `.` stripped (the format's own closing punctuation, not part of the
 * overlay text).
 */
const BEAT_RE = new RegExp(
  `^(\\d+)-(\\d+)s\\.\\s*Shot:\\s*(.+?)\\.\\s*Say:\\s*${OPEN_QUOTE}(.+?)${CLOSE_QUOTE}\\.\\s*` +
    `(?:Stress:\\s*(.+?)\\.\\s*Pause:\\s*(.+?)\\.\\s*)?` +
    `On screen:\\s*(.+?)\\.?\\s*$`,
  'i',
);

/** Strips ONE matching pair of wrapping quotes off a beat line, if present — the persona's own
 *  example shows the whole line quoted, but nothing requires the model to reproduce that
 *  literally, so both a quoted and an unquoted line are accepted. */
function stripWrappingQuotes(line: string): string {
  if (line.length >= 2 && QUOTE_CHARS.has(line[0]) && QUOTE_CHARS.has(line[line.length - 1])) {
    return line.slice(1, -1);
  }
  return line;
}

/** `Before you shoot:`'s value is three items numbered `1) … 2) … 3) …` on ONE line (per the
 *  persona file), not three separate lines — this splits that one value back into 3 items.
 *  Non-greedy segments bounded by the literal next marker, so this only breaks if an item's own
 *  text happens to contain the literal substring `2)` or `3)` — accepted as a known edge case,
 *  same tolerance-vs-strictness trade-off the rest of this file makes throughout. */
function parseBeforeYouShoot(value: string): [string, string, string] | undefined {
  const match = /^1\)\s*(.+?)\s*2\)\s*(.+?)\s*3\)\s*(.+)$/.exec(value.trim());
  if (!match) return undefined;
  const [, a, b, c] = match;
  if (!a.trim() || !b.trim() || !c.trim()) return undefined;
  return [a.trim(), b.trim(), c.trim()];
}

// ---------------------------------------------------------------------------
// Shot cards (spec v2 Phase 6): the optional block between the last beat and `Caption:`
// ---------------------------------------------------------------------------

/** A field Meera does not know. The card shows it as "Not set yet"; Meera asks the matching
 *  coach question next time instead of guessing. */
export const SHOT_CARD_UNKNOWN = '?';
export type ShotCardUnknown = typeof SHOT_CARD_UNKNOWN;

export const SHOT_SIZES = ['ECU', 'CU', 'MCU', 'MS', 'MLS', 'FS', 'LS', 'OVERHEAD'] as const;
export const CAMERA_HEIGHTS = ['eye', 'chest', 'above', 'below', 'overhead'] as const;
export const LIGHT_KINDS = ['window', 'sun', 'shade', 'lamp', 'ring_light', 'tube_light', 'mixed'] as const;
/** The creator's OWN side, as they face the phone (spec 2.4). */
export const LIGHT_SIDES = ['left', 'right', 'front', 'behind'] as const;
export const STAND_POSITIONS = ['left', 'centre', 'right'] as const;
export const HEADROOMS = ['cropped', 'small', 'medium'] as const;
export const EYE_LINES = ['lens', 'product', 'off_lens'] as const;
export const NEGATIVE_SPACES = ['left', 'right', 'top', 'none'] as const;
export const TEXT_POSITIONS = ['top', 'opposite_face', 'lower_middle', 'none'] as const;
export const PROP_SIDES = ['left', 'centre', 'right'] as const;
export const PROP_SURFACES = ['hand', 'table', 'floor'] as const;
export const MOVEMENTS = ['still', 'sit', 'stand', 'walk', 'pan', 'push'] as const;

export type ShotSize = (typeof SHOT_SIZES)[number];
export type CameraHeight = (typeof CAMERA_HEIGHTS)[number];
export type LightKind = (typeof LIGHT_KINDS)[number];
export type LightSide = (typeof LIGHT_SIDES)[number];
export type StandPosition = (typeof STAND_POSITIONS)[number];
export type Headroom = (typeof HEADROOMS)[number];
export type EyeLine = (typeof EYE_LINES)[number];
export type NegativeSpace = (typeof NEGATIVE_SPACES)[number];
export type TextPosition = (typeof TEXT_POSITIONS)[number];
export type PropSide = (typeof PROP_SIDES)[number];
export type PropSurface = (typeof PROP_SURFACES)[number];
export type Movement = (typeof MOVEMENTS)[number];
/** `window`, or `window-left` (kind, then the creator's own side). */
export type ShotLight = LightKind | `${LightKind}-${LightSide}`;
/** `none`, or `right-hand` / `centre-table` / `left-floor` (the creator's own side, then the
 *  surface). This exact value is what the camera sends as `MeeraShotContext.prop_position`. */
export type ShotProp = 'none' | `${PropSide}-${PropSurface}`;

/** Free-text fields are capped (Unicode code points, the same count as Python's `len`); a longer
 *  value is treated as unknown, never cut. */
export const SHOT_CARD_TEXT_MAX = { distance: 20, place: 40, background: 40 } as const;

/**
 * One beat's shot card: the 13 `key=value` pairs of an `S<n>:` line, each either a valid value or
 * `'?'` (unknown). Values are kept exactly as the wire contract spells them (enum values English
 * even in a Hindi reply; free text in the creator's language). Creator facts (place, light,
 * height, distance, eyes, prop, move) come only from the creator's answers; the prompt, not this
 * parser, owns that rule.
 */
export interface ShotCard {
  size: ShotSize | ShotCardUnknown;
  height: CameraHeight | ShotCardUnknown;
  /** Free text, at most 20 characters. */
  distance: string;
  /** Free text, at most 40 characters. */
  place: string;
  light: ShotLight | ShotCardUnknown;
  stand: StandPosition | ShotCardUnknown;
  headroom: Headroom | ShotCardUnknown;
  eyes: EyeLine | ShotCardUnknown;
  /** Free text, at most 40 characters. */
  background: string;
  space: NegativeSpace | ShotCardUnknown;
  text: TextPosition | ShotCardUnknown;
  prop: ShotProp | ShotCardUnknown;
  move: Movement | ShotCardUnknown;
}

/** The 13 keys, in the one order an `S<n>:` line must use. */
export const SHOT_CARD_KEYS = [
  'size',
  'height',
  'distance',
  'place',
  'light',
  'stand',
  'headroom',
  'eyes',
  'background',
  'space',
  'text',
  'prop',
  'move',
] as const satisfies ReadonlyArray<keyof ShotCard>;

/** Lower-cases A-Z only. Enum values are ASCII, and folding only ASCII keeps this identical to
 *  the Python twin (full Unicode case mapping differs between the two languages at the edges). */
function asciiLower(value: string): string {
  return value.replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32));
}

function asciiUpper(value: string): string {
  return value.replace(/[a-z]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 32));
}

function enumValue<T extends string>(value: string, allowed: readonly T[], fold: (v: string) => string): T | ShotCardUnknown {
  const folded = fold(value);
  return (allowed as readonly string[]).includes(folded) ? (folded as T) : SHOT_CARD_UNKNOWN;
}

function freeText(value: string, max: number): string {
  if (!value || value === SHOT_CARD_UNKNOWN) return SHOT_CARD_UNKNOWN;
  // Code points, not UTF-16 units: an emoji counts once, as it does in Python.
  return Array.from(value).length <= max ? value : SHOT_CARD_UNKNOWN;
}

function lightValue(value: string): ShotLight | ShotCardUnknown {
  const folded = asciiLower(value);
  const dash = folded.indexOf('-');
  const kind = dash === -1 ? folded : folded.slice(0, dash);
  if (!(LIGHT_KINDS as readonly string[]).includes(kind)) return SHOT_CARD_UNKNOWN;
  if (dash === -1) return folded as ShotLight;
  return (LIGHT_SIDES as readonly string[]).includes(folded.slice(dash + 1)) ? (folded as ShotLight) : SHOT_CARD_UNKNOWN;
}

function propValue(value: string): ShotProp | ShotCardUnknown {
  const folded = asciiLower(value);
  if (folded === 'none') return 'none';
  const dash = folded.indexOf('-');
  if (dash === -1) return SHOT_CARD_UNKNOWN;
  const side = folded.slice(0, dash);
  const surface = folded.slice(dash + 1);
  return (PROP_SIDES as readonly string[]).includes(side) && (PROP_SURFACES as readonly string[]).includes(surface)
    ? (folded as ShotProp)
    : SHOT_CARD_UNKNOWN;
}

/** `light` split into its kind and the creator's own side; `null` when unknown. */
export function shotLightParts(light: ShotCard['light']): { kind: LightKind; side?: LightSide } | null {
  if (light === SHOT_CARD_UNKNOWN) return null;
  const dash = light.indexOf('-');
  if (dash === -1) return { kind: light as LightKind };
  return { kind: light.slice(0, dash) as LightKind, side: light.slice(dash + 1) as LightSide };
}

/** `prop` split into the creator's own side and the surface; `null` for `none` or unknown (no
 *  prop zone is drawn for either). */
export function shotPropParts(prop: ShotCard['prop']): { side: PropSide; surface: PropSurface } | null {
  if (prop === SHOT_CARD_UNKNOWN || prop === 'none') return null;
  const dash = prop.indexOf('-');
  return { side: prop.slice(0, dash) as PropSide, surface: prop.slice(dash + 1) as PropSurface };
}

/** `S<n>:` at the start of a block line (ASCII digits only, as JS `\d` is). */
const SHOT_LINE_RE = /^S\s*(\d+)\s*:/i;

/**
 * The part of an `S<n>:` line after the colon -> a card, or `null` when the line is malformed:
 * not exactly 13 `key=value` pairs, or a key missing, misspelt or out of order. One trailing `.`
 * or `;` is tolerated (the spec's own example line ends with a full stop). A bad VALUE never makes
 * the line malformed: it becomes `'?'` for that one field.
 */
function parseShotCardBody(body: string): ShotCard | null {
  const text = body.trim().replace(/[.;]\s*$/, '');
  const pairs = text.split(';');
  if (pairs.length !== SHOT_CARD_KEYS.length) return null;
  const raw: Record<string, string> = {};
  for (let k = 0; k < SHOT_CARD_KEYS.length; k++) {
    const pair = pairs[k];
    const eq = pair.indexOf('=');
    if (eq === -1) return null;
    if (asciiLower(pair.slice(0, eq).trim()) !== SHOT_CARD_KEYS[k]) return null;
    raw[SHOT_CARD_KEYS[k]] = pair.slice(eq + 1).trim();
  }
  return {
    size: enumValue(raw.size, SHOT_SIZES, asciiUpper),
    height: enumValue(raw.height, CAMERA_HEIGHTS, asciiLower),
    distance: freeText(raw.distance, SHOT_CARD_TEXT_MAX.distance),
    place: freeText(raw.place, SHOT_CARD_TEXT_MAX.place),
    light: lightValue(raw.light),
    stand: enumValue(raw.stand, STAND_POSITIONS, asciiLower),
    headroom: enumValue(raw.headroom, HEADROOMS, asciiLower),
    eyes: enumValue(raw.eyes, EYE_LINES, asciiLower),
    background: freeText(raw.background, SHOT_CARD_TEXT_MAX.background),
    space: enumValue(raw.space, NEGATIVE_SPACES, asciiLower),
    text: enumValue(raw.text, TEXT_POSITIONS, asciiLower),
    prop: propValue(raw.prop),
    move: enumValue(raw.move, MOVEMENTS, asciiLower),
  };
}

/**
 * The block's lines (everything after `Shot cards:` up to, not including, the `Caption:` line) ->
 * one card or `null` per beat. A line that is not a well-formed `S<n>:` line for a beat that exists
 * is dropped; a beat named twice gets no card (never guess which one was meant); a beat with no
 * line gets no card.
 */
function shotCardsForBeats(blockLines: string[], beatCount: number): Array<ShotCard | null> {
  const cards: Array<ShotCard | null> = new Array(beatCount).fill(null);
  const seen = new Set<number>();
  const doubled = new Set<number>();
  for (const line of blockLines) {
    const trimmed = line.trim();
    const match = SHOT_LINE_RE.exec(trimmed);
    if (!match) continue;
    const n = parseInt(match[1], 10);
    if (n < 1 || n > beatCount) continue;
    if (seen.has(n)) doubled.add(n);
    seen.add(n);
    cards[n - 1] = parseShotCardBody(trimmed.slice(match[0].length));
  }
  for (const n of doubled) cards[n - 1] = null;
  return cards;
}

export function parseMeeraScript(text: string): ParsedMeeraScript | undefined {
  if (typeof text !== 'string') return undefined;
  const lines = splitLines(text);
  // Minimum shape: Idea, Plan, Action, Script, 3 beats, Caption, Before you shoot, Why this
  // works = 10 lines. Set-up, Success looks like and the trailing follow-up question are optional.
  if (lines.length < 10) return undefined;

  let i = 0;

  const ideaKV = splitKeyValue(lines[i]);
  i++;
  if (!ideaKV || !keyIs(ideaKV, 'idea') || !ideaKV.value) return undefined;

  const planKV = splitKeyValue(lines[i]);
  i++;
  if (!planKV || !keyIs(planKV, 'plan') || !planKV.value) return undefined;

  const actionKV = splitKeyValue(lines[i]);
  i++;
  if (!actionKV || !keyIs(actionKV, 'action') || !actionKV.value) return undefined;

  // Set-up: optional, and only in this one position (after Action, before Success looks like).
  // Replies written before the persona added it have no such line and still parse. Present but
  // empty is refused, same as every other label here.
  let setup: string | undefined;
  const maybeSetupKV = i < lines.length ? splitKeyValue(lines[i]) : null;
  if (maybeSetupKV && (keyIs(maybeSetupKV, 'set-up') || keyIs(maybeSetupKV, 'setup') || keyIs(maybeSetupKV, 'set up'))) {
    if (!maybeSetupKV.value) return undefined;
    setup = maybeSetupKV.value;
    i++;
  }

  // Success looks like: the ONE required-looking line this parser still accepts a card without.
  // Every other label renders as its own line on the card (Idea as the title, Action and the
  // beats and Caption and Before-you-shoot and Why-this-works all get their own row), so dropping
  // any of those would visibly break the card. This line only restates the goal from "Script
  // length by goal" — the same goal the Plan line already carries in short form ("...the goal;
  // ...") — so a model that folds it into Plan instead still produces a complete, renderable
  // card. Tolerating it here means one fewer way a good script gets refused down to a plain
  // bubble over a line that duplicates information the card already has elsewhere.
  let successLooksLike: string | undefined;
  const maybeSuccessKV = i < lines.length ? splitKeyValue(lines[i]) : null;
  if (maybeSuccessKV && keyIs(maybeSuccessKV, 'success looks like')) {
    if (!maybeSuccessKV.value) return undefined;
    successLooksLike = maybeSuccessKV.value;
    i++;
  }

  if (i >= lines.length) return undefined;
  const scriptKV = splitKeyValue(lines[i]);
  i++;
  if (!scriptKV || !keyIs(scriptKV, 'script')) return undefined;
  // The beats belong on their OWN lines after this one (persona: "Script: then one line per
  // beat"); a value trailing the colon on this line does not match that shape.
  if (scriptKV.value) return undefined;

  const beats: ScriptBeat[] = [];
  while (i < lines.length) {
    const match = BEAT_RE.exec(stripWrappingQuotes(lines[i].trim()));
    if (!match) break;
    const from = parseInt(match[1], 10);
    const to = parseInt(match[2], 10);
    const shot = match[3].trim();
    const say = match[4].trim();
    // match[5]/match[6] (Stress/Pause) are one optional group — either both matched (non-empty
    // after trim, per the persona's own layout) or neither did.
    const stress = match[5]?.trim();
    const pause = match[6]?.trim();
    const onScreen = match[7].trim();
    if (!shot || !say || !onScreen) return undefined;
    beats.push({ from, to, shot, say, stress: stress || undefined, pause: pause || undefined, onScreen });
    i++;
  }
  // No upper bound on beat count: unlike Phase C's fixed 15/30/45/60s lengths (which capped beats
  // at 6), the persona picks the length itself from a range per goal/structure, so this file only
  // enforces the floor of 3 the task requires.
  if (beats.length < 3) return undefined;
  if (beats[0].from !== 0) return undefined;
  for (let b = 0; b < beats.length; b++) {
    if (beats[b].to <= beats[b].from) return undefined;
    if (b > 0 && beats[b].from !== beats[b - 1].to) return undefined;
  }

  // Shot cards: optional, and only in this one position (after the last beat, before Caption).
  // The block runs up to the `Caption:` line; what is inside it can only cost a beat its card,
  // never the whole script (see `shotCardsForBeats`). Present with a value on its own line is
  // refused, like `Script:`.
  const maybeCardsKV = i < lines.length ? splitKeyValue(lines[i]) : null;
  if (maybeCardsKV && keyIs(maybeCardsKV, 'shot cards')) {
    if (maybeCardsKV.value) return undefined;
    i++;
    const blockStart = i;
    while (i < lines.length) {
      const kv = splitKeyValue(lines[i]);
      if (kv && keyIs(kv, 'caption')) break;
      i++;
    }
    const cards = shotCardsForBeats(lines.slice(blockStart, i), beats.length);
    cards.forEach((card, index) => {
      if (card) beats[index].card = card;
    });
  }

  if (i >= lines.length) return undefined;
  const captionKV = splitKeyValue(lines[i]);
  i++;
  if (!captionKV || !keyIs(captionKV, 'caption') || !captionKV.value) return undefined;

  if (i >= lines.length) return undefined;
  const beforeKV = splitKeyValue(lines[i]);
  i++;
  if (!beforeKV || !keyIs(beforeKV, 'before you shoot') || !beforeKV.value) return undefined;
  const beforeYouShoot = parseBeforeYouShoot(beforeKV.value);
  if (!beforeYouShoot) return undefined;

  if (i >= lines.length) return undefined;
  const whyKV = splitKeyValue(lines[i]);
  i++;
  if (!whyKV || !keyIs(whyKV, 'why this works') || !whyKV.value) return undefined;

  // One optional trailing line — the short question the persona ends every script with. Kept
  // verbatim, whatever its content, rather than matched against a shape: the persona's own
  // example ("the language of the voice-over") is illustrative, not a fixed template.
  let followUp: string | undefined;
  if (i < lines.length) {
    followUp = lines[i].trim();
    i++;
    if (!followUp) return undefined;
  }

  // Nothing left over — a line after the follow-up question (or after `Why this works:` when
  // there is no follow-up) is unexplained content the contract does not allow.
  if (i !== lines.length) return undefined;

  return {
    idea: ideaKV.value,
    plan: planKV.value,
    action: actionKV.value,
    setup,
    successLooksLike,
    beats,
    caption: captionKV.value,
    beforeYouShoot,
    whyThisWorks: whyKV.value,
    followUp,
  };
}

/**
 * The text the script card's Copy writes: the reply exactly as sent, minus the machine-readable
 * `Shot cards:` block (its header line and every line up to `Caption:`), which the card never
 * shows as text and which keeps English keys even in a Hindi reply. Every other character, line
 * endings included, is kept byte for byte. A reply that does not parse as a script, or has no
 * block, comes back unchanged.
 */
export function scriptCopyText(text: string): string {
  if (!parseMeeraScript(text)) return text;
  // Lines at even indexes, their line breaks at odd ones (the parser's own breaks: CRLF or LF).
  const parts = text.split(/(\r\n|\n)/);
  const keyAt = (k: number) => splitKeyValue(stripMeeraMarkdown(parts[k]));
  let header = -1;
  for (let k = 0; k < parts.length; k += 2) {
    const kv = keyAt(k);
    if (kv && keyIs(kv, 'shot cards') && !kv.value) {
      header = k;
      break;
    }
  }
  if (header < 0) return text;
  for (let k = header + 2; k < parts.length; k += 2) {
    const kv = keyAt(k);
    if (kv && keyIs(kv, 'caption')) return parts.slice(0, header).join('') + parts.slice(k).join('');
  }
  return text;
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
