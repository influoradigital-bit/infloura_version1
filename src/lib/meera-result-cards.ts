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
 *   - a beat line's `Stress:`/`Pause:` markers, entirely — both are optional together (see
 *     `ScriptBeat.stress`/`.pause`), so a beat with just `Shot`/`Say`/`On screen` still parses
 *   - one trailing short question line after `Why this works:` (kept as `followUp`)
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
 */

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
}

export interface ParsedMeeraScript {
  idea: string;
  plan: string;
  action: string;
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

export function parseMeeraScript(text: string): ParsedMeeraScript | undefined {
  if (typeof text !== 'string') return undefined;
  const lines = splitLines(text);
  // Minimum shape: Idea, Plan, Action, Script, 3 beats, Caption, Before you shoot, Why this
  // works = 10 lines. Success looks like and the trailing follow-up question are both optional.
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
    successLooksLike,
    beats,
    caption: captionKV.value,
    beforeYouShoot,
    whyThisWorks: whyKV.value,
    followUp,
  };
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
