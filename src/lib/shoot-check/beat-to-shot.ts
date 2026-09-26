/**
 * A script card's beat as a photo-check shot (SPEC section 2a, with Ash's review item 10).
 *
 * "Check my set-up" on a script-card beat opens the in-chat camera on that beat; the shot built
 * here is what the check is told the creator is trying to film:
 *   - `label` "<from>-<to>s · <shot>", one line, at most 120 UTF-16 units (Java's own cut);
 *   - `context.line` is the beat's `shot` ONLY. The Say dialogue does nothing for framing, and
 *     every character it took pushed the keys that matter out of the 1000-character budget;
 *   - `context.angle` / `context.action` come from the "angle - action" split of `shot`;
 *   - `context.where` is the script's Set-up line when it has one;
 *   - `context.on_camera` is set when the beat itself settles it (the coach question bank's own
 *     option words: "Hands only", "Voice-over", "Face on camera"), so the check does not ask a
 *     question the script already answers. When the beat does not say, it is left out and the
 *     check may still ask;
 *   - `target` picks the framing guide: close -> closeup, overhead/top-down/hands ->
 *     hands-overhead, wide/full body -> wide, anything else -> medium. A beat with a shot card
 *     (spec v2 Phase 6) takes the card's `size` first (`targetForShotSize`);
 *   - `context.prop_position` is the card's `prop` ("right-hand", or "none"); a `?` is left out;
 *   - `say`, `onScreen` and the validated `card` ride on the shot for the camera sheet (decision 3,
 *     2026-09-26), never in `context`.
 */
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import type { MeeraShotContext } from '@/lib/meera-api';
import {
  CAMERA_HEIGHTS,
  EYE_LINES,
  HEADROOMS,
  LIGHT_KINDS,
  LIGHT_SIDES,
  MOVEMENTS,
  NEGATIVE_SPACES,
  SHOT_CARD_TEXT_MAX,
  SHOT_CARD_UNKNOWN,
  SHOT_SIZES,
  STAND_POSITIONS,
  TEXT_POSITIONS,
  type LightKind,
  type LightSide,
  type ParsedMeeraScript,
  type PropSide,
  type PropSurface,
  type ShotCard,
  type ShotProp,
} from '@/lib/meera-result-cards';
import type { ShotTarget } from '@/lib/shoot-check/metrics';

/** Java's cut for `shot_label` (CreatorMeeraController). */
export const SHOT_LABEL_MAX_CHARS = 120;

/** The coach bank's `on_camera` option words (influora-ai video_content_concepts.jsonl). */
export const ON_CAMERA_HANDS_ONLY = 'Hands only';
export const ON_CAMERA_VOICE_OVER = 'Voice-over';
export const ON_CAMERA_FACE = 'Face on camera';

function oneLine(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

/** At most `max` UTF-16 units — Java's `String.length()`, so Spring's own 120 cut never cuts it
 *  again — without leaving half a surrogate pair at the end. */
function clip(text: string, max: number): string {
  if (text.length <= max) return text;
  let out = text.slice(0, max);
  const last = out.charCodeAt(out.length - 1);
  if (last >= 0xd800 && last <= 0xdbff) out = out.slice(0, -1);
  return out.trimEnd();
}

export function targetForShot(shot: string): ShotTarget {
  if (/close/i.test(shot)) return 'closeup';
  if (/overhead|top.?down|hands/i.test(shot)) return 'hands-overhead';
  if (/wide|full body/i.test(shot)) return 'wide';
  return 'medium';
}

/**
 * The shot size the live camera guide draws (spec v2 Phase 5a). Finer than `ShotTarget`: the
 * guide's eye-line, hook-text and lower-crop bands differ between a medium close-up and a medium
 * shot, while the checker only knows four targets. LS is drawn as FS; OVERHEAD has no face bands.
 */
export type ShotSize = 'ECU' | 'CU' | 'MCU' | 'MS' | 'MLS' | 'FS' | 'LS' | 'OVERHEAD';

/** The spec's mapping, first match wins, in this order. `null` when no size word is in the text. */
const SHOT_SIZE_RULES: ReadonlyArray<readonly [RegExp, ShotSize]> = [
  [/extreme[\s-]*close/i, 'ECU'],
  [/medium[\s-]*close/i, 'MCU'],
  [/close/i, 'CU'],
  [/medium[\s-]*long|3\/4|knees/i, 'MLS'],
  [/medium|waist/i, 'MS'],
  [/full[\s-]*(?:body|shot)/i, 'FS'],
  [/wide|establishing|\blong\b/i, 'LS'],
  [/overhead|top.?down|hands/i, 'OVERHEAD'],
];

export function explicitShotSize(shot: string): ShotSize | null {
  for (const [re, size] of SHOT_SIZE_RULES) if (re.test(shot)) return size;
  return null;
}

/** Before Phase 6 this reads the beat's shot text; anything without a size word is MS. */
export function shotSizeForShot(shot: string): ShotSize {
  return explicitShotSize(shot) ?? 'MS';
}

/**
 * A size's `ShotTarget` for the existing checker (spec v2 Phase 5a, unchanged): ECU/CU -> closeup,
 * MCU/MS -> medium, MLS/FS/LS -> wide, OVERHEAD -> hands-overhead. `targetForShot` above is NOT
 * rewritten on top of this: it keeps its own word order ("medium close-up" stays a close-up target),
 * so a stored check's framing target never moves under an already-shipped card.
 */
export function targetForShotSize(size: ShotSize): ShotTarget {
  switch (size) {
    case 'ECU':
    case 'CU':
      return 'closeup';
    case 'MCU':
    case 'MS':
      return 'medium';
    case 'MLS':
    case 'FS':
    case 'LS':
      return 'wide';
    case 'OVERHEAD':
      return 'hands-overhead';
  }
}

// ---------------------------------------------------------------------------
// Shot card (spec v2 Phase 6 wire format, owner decisions 2026-09-26)
// ---------------------------------------------------------------------------

/** The parser's card type (`meera-result-cards.ts`), re-exported for the camera. */
export type { ShotCard };

/** A card field Meera does not know. Shown as "Not set yet"; the camera draws nothing for it. */
export const CARD_UNKNOWN = SHOT_CARD_UNKNOWN;

/** `prop_position` for a real spot: "<side>-<surface>", e.g. "right-hand" (the creator's own side). */
export type PropPosition = Exclude<ShotProp, 'none'>;

function cardString(value: unknown): string {
  return typeof value === 'string' ? oneLine(value) : '';
}

function cardEnum<T extends string>(value: unknown, allowed: readonly T[]): T | typeof CARD_UNKNOWN {
  const text = cardString(value);
  return (allowed as readonly string[]).includes(text) ? (text as T) : CARD_UNKNOWN;
}

function cardFreeText(value: unknown, max: number): string {
  const text = cardString(value);
  if (!text || text === CARD_UNKNOWN) return CARD_UNKNOWN;
  return Array.from(text).length <= max ? text : CARD_UNKNOWN;
}

/** "window" or "window-left" -> its parts; null for `?` or anything else. */
export function parseCardLight(value: string | undefined): { kind: LightKind; side: LightSide | null } | null {
  if (!value) return null;
  const [kind, side, extra] = value.split('-');
  if (extra !== undefined || !(LIGHT_KINDS as readonly string[]).includes(kind)) return null;
  if (side === undefined) return { kind: kind as LightKind, side: null };
  return (LIGHT_SIDES as readonly string[]).includes(side) ? { kind: kind as LightKind, side: side as LightSide } : null;
}

/** "right-hand" -> its parts; null for "none", `?` or anything else (no prop, nothing drawn). */
export function parseCardProp(value: string | undefined): { side: PropSide; surface: PropSurface } | null {
  if (!value) return null;
  const match = /^(left|centre|right)-(hand|table|floor)$/.exec(value);
  return match ? { side: match[1] as PropSide, surface: match[2] as PropSurface } : null;
}

/**
 * A beat's card as the camera may draw it: every field re-checked against the wire contract (an
 * unknown enum value or an over-long free text is `?`; a missing key, `null` or `undefined` is
 * `?`). The parser already validates; this is the camera's own guard, so a shot built anywhere
 * else (or an older parser) can never put an unchecked value on screen. `null` when there is no
 * card object at all (a beat without a card, or a reply without the block).
 */
export function normalizeShotCard(raw: unknown): ShotCard | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const card = raw as Record<string, unknown>;
  const light = cardString(card.light);
  const prop = cardString(card.prop);
  return {
    size: cardEnum(card.size, SHOT_SIZES),
    height: cardEnum(card.height, CAMERA_HEIGHTS),
    distance: cardFreeText(card.distance, SHOT_CARD_TEXT_MAX.distance),
    place: cardFreeText(card.place, SHOT_CARD_TEXT_MAX.place),
    light: parseCardLight(light) ? (light as ShotCard['light']) : CARD_UNKNOWN,
    stand: cardEnum(card.stand, STAND_POSITIONS),
    headroom: cardEnum(card.headroom, HEADROOMS),
    eyes: cardEnum(card.eyes, EYE_LINES),
    background: cardFreeText(card.background, SHOT_CARD_TEXT_MAX.background),
    space: cardEnum(card.space, NEGATIVE_SPACES),
    text: cardEnum(card.text, TEXT_POSITIONS),
    prop: prop === 'none' ? 'none' : parseCardProp(prop) ? (prop as PropPosition) : CARD_UNKNOWN,
    move: cardEnum(card.move, MOVEMENTS),
  };
}

/** A known card size, else null. */
export function cardShotSize(card: ShotCard | null | undefined): ShotSize | null {
  return card && card.size !== CARD_UNKNOWN ? card.size : null;
}

function onCameraFor(shot: string, target: ShotTarget): string | undefined {
  if (target === 'hands-overhead') return ON_CAMERA_HANDS_ONLY;
  if (/voice.?over/i.test(shot)) return ON_CAMERA_VOICE_OVER;
  // Only words that clearly put the face in frame. A bare "you" does not ("over the shoulder as you
  // type"), and a wrong answer here would silence the one question that would have caught it.
  if (/\b(?:face|faces|selfie|talking head|to camera|eye contact)\b/i.test(shot)) return ON_CAMERA_FACE;
  return undefined;
}

/** `shot` split on its first " - " (also an en or em dash): "angle - action". */
function splitAngleAction(shot: string): { angle?: string; action?: string } {
  const match = /^(.*?)\s+[-–—]\s+(.+)$/.exec(shot);
  if (!match) return {};
  const angle = match[1].trim();
  const action = match[2].trim();
  return { ...(angle ? { angle } : {}), ...(action ? { action } : {}) };
}

export function shotFromBeat(script: ParsedMeeraScript, beatIndex: number): ShootCheckShot {
  const beat = script.beats[beatIndex];
  if (!beat) throw new RangeError(`shotFromBeat: no beat ${beatIndex} in a script of ${script.beats.length}`);

  const shot = oneLine(beat.shot);
  // The beat's shot card (spec v2 Phase 6), when the reply had one for this beat. Read as unknown:
  // the parser's type is the wire contract, and every value is re-checked here before it is drawn.
  const card = normalizeShotCard((beat as { card?: unknown }).card);
  const cardSize = cardShotSize(card);
  // The card's size comes before the shot words; with no card (or `?`) the words decide as before.
  const target = cardSize ? targetForShotSize(cardSize) : targetForShot(shot);
  const context: MeeraShotContext = { line: shot, ...splitAngleAction(shot) };
  const where = script.setup ? oneLine(script.setup) : '';
  if (where) context.where = where;
  const onCamera = onCameraFor(shot, target);
  if (onCamera) context.on_camera = onCamera;
  // The card's prop value as the check's `prop_position`: a spot ("right-hand") or "none" (no
  // prop in this beat). `?` is left out: the check may still ask `prop_ready`.
  if (card && card.prop !== CARD_UNKNOWN) context.prop_position = card.prop;

  // The Say line and On-screen text ride on the shot for the camera sheet only; they never go into
  // `context` (the Say dialogue does nothing for framing and would eat the 1000-character budget).
  const say = oneLine(beat.say ?? '');
  const onScreen = oneLine(beat.onScreen ?? '');

  return {
    index: beatIndex,
    label: clip(`${beat.from}-${beat.to}s · ${shot}`, SHOT_LABEL_MAX_CHARS),
    seconds: Math.max(0, beat.to - beat.from),
    target,
    context,
    ...(say ? { say } : {}),
    ...(onScreen ? { onScreen } : {}),
    ...(card ? { card } : {}),
  };
}

/** "<from>-<to>s · " at the start of a label `shotFromBeat` built. */
const LABEL_TIMING_RE = /^(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)s\s*·\s*/;

/**
 * A shot rebuilt from a stored photo-check label, for "Check again" on a card that came back from
 * history (it keeps only its label, not the beat). The "<from>-<to>s · " prefix gives `seconds`;
 * the rest is the beat's shot line, which gives the framing target, `angle` / `action` and
 * `on_camera` exactly as `shotFromBeat` does — so a close-up or overhead beat gets its own framing
 * guide again instead of "medium", and the check does not re-ask what the shot line settles. The
 * script's Set-up line (`where`) is not part of a label, so it is left out.
 */
export function shotFromLabel(label: string): ShootCheckShot {
  const clean = oneLine(label);
  const timing = LABEL_TIMING_RE.exec(clean);
  const shot = timing ? clean.slice(timing[0].length).trim() : clean;
  const target = targetForShot(shot);
  const context: MeeraShotContext = shot ? { line: shot, ...splitAngleAction(shot) } : {};
  const onCamera = shot ? onCameraFor(shot, target) : undefined;
  if (onCamera) context.on_camera = onCamera;

  return {
    index: 0,
    label: clip(clean, SHOT_LABEL_MAX_CHARS),
    seconds: timing ? Math.max(0, Number(timing[2]) - Number(timing[1])) : 0,
    target,
    ...(Object.keys(context).length > 0 ? { context } : {}),
  };
}
