import { LOCALES } from './locales';
import type { Beat, Card, LangCode, Scene } from './script';
import { PHONE, SCREEN, VIDEO } from './theme';
import { VOICE_MANIFEST } from './voice-manifest';

export interface VoiceClip {
  /** Path under `public/`, for `staticFile()`. */
  file: string;
  seconds: number;
}

/** A beat with its absolute start frame (within the scene) and hold length. */
export interface TimedBeat {
  beat: Beat;
  start: number;
  hold: number;
  /** Estimated rendered height in px, used for the fake auto-scroll. */
  height: number;
  /** Frames over which a typewriter bubble reveals its text. */
  revealFrames: number;
  /** Meera's spoken line for this beat, when one was generated. */
  voice?: VoiceClip;
}

export interface TimedScene {
  lang: LangCode;
  scene: Scene;
  beats: TimedBeat[];
  /** Total frames of the scene including the tail. */
  duration: number;
}

/** Characters per line in a 34px bubble that is at most 560px wide. */
const CHARS_PER_LINE = 30;
const LINE_HEIGHT = 46;
const BUBBLE_CHROME = 44;
const GAP = 22;

/** Typewriter speed when a bubble has no voice clip, characters per frame. */
export const TYPE_RATE = 1.5;

/** Frames a voiced bubble stays after its clip ends. */
const VOICE_TAIL = 14;

export const TRANSITION_FRAMES = 15;

/**
 * U-4 (2026-09-17) — these ids' RECORDED audio still says what the on-screen copy no longer does
 * (RULINGS-U-0917.md; the U-4 pass on `script.ts`/`script.en.ts`/`script.mr.ts`). Regenerating
 * them costs a real Sarvam TTS call per language and is Swapnil's call, not made here — so until
 * that happens, each id below is forced silent (no `<Audio>` renders at all; every call site
 * already guards on `voiceFor(...)` being falsy — see `ChatScene.tsx`, `IntroScene.tsx`,
 * `OutroScene.tsx`) rather than playing the stale recording. The `.wav` files stay on disk;
 * nothing deletes them, and nothing looks them up while its id is listed here.
 *
 * One id per STALE BEAT, not per language: the same beat's text changed in hi, en AND mr, so the
 * id is blocked for every `lang` uniformly. 4 ids × 3 languages = 12 stale `.wav` files.
 *   - 'intro'   — INTRO.line/.say (all 3 locales): "I read, and I remember" replaced the old
 *                 "you decide / I write" line.
 *   - 'outro'   — OUTRO.bullets[1]/.say (all 3 locales): "Your floor, your decision" replaced
 *                 "you approve, only then it goes".
 *   - 'paste-5' — the 'paste' scene's closing `meera` beat (all 3 locales): "Here is my read on
 *                 it..." replaced "I am drafting the reply now...".
 *   - 'money-0' — the 'money' scene's first `wa` beat (all 3 locales), cut in Nisha's full
 *                 read-through (NISHA-U4-RECHECK-0917.md §5): dropped the "...and I will send the
 *                 72-hour reach to the brand" clause, a first-person send promise with no card
 *                 later in the scene backing it up. The snapshot card's own "the brand sees this
 *                 too" line already carries the honest visibility fact.
 *
 * Remove an id from this set only after its .wav has been regenerated for hi, en AND mr —
 * removing it for one language and not the others would silence it correctly in some but leave
 * the stale recording playing in the rest, since `VOICE_MANIFEST` is keyed by language already.
 */
const STALE_VOICE_IDS: ReadonlySet<string> = new Set(['intro', 'outro', 'paste-5', 'money-0']);

export function voiceFor(lang: LangCode, id: string): VoiceClip | undefined {
  if (STALE_VOICE_IDS.has(id)) return undefined;
  return VOICE_MANIFEST[lang]?.[id];
}

function framesFor(clip: VoiceClip | undefined): number {
  return clip ? Math.ceil(clip.seconds * VIDEO.fps) : 0;
}

/**
 * Kavya's MEDIUM (KAVYA-FE-RECHECK-0917.md, F6 recheck round 2) — silencing a beat that used to
 * carry a voice clip can leave its on-screen text up for less time than a reader needs, because
 * `defaultHold`'s typewriter-based `typed` value scales with CHARACTER count (how long the type
 * effect takes), not reading speed. A rough, commonly-used reading rate — 250ms per word plus a
 * 1-second baseline — catches that. Applied only where a beat/screen's voice was suppressed for
 * being STALE (see `STALE_VOICE_IDS`), never to a beat that simply never had a recording; the
 * ordinary `typed`/fixed-minimum values already cover that other case and are unaffected.
 */
function readableFloorFrames(text: string): number {
  const words = text.trim().split(/\s+/).filter(Boolean).length;
  return Math.ceil((1 + words * 0.25) * VIDEO.fps);
}

/** Frame the intro narration starts on. */
export const INTRO_VOICE_FROM = 30;
/** Frame the outro narration starts on. */
export const OUTRO_VOICE_FROM = 20;

/**
 * The intro/outro screens show several fading-in text pieces (eyebrow, title, sub, line/bullets,
 * cta), so their reading floor is computed from ALL of them combined, per language — not a single
 * shared guess. Before U-4's silencing this branch was dead in practice: every language's real
 * narration clip (6.6s-19.3s) always exceeded the old fixed 120/210-frame minimums, so those
 * numbers only ever really applied once 'intro'/'outro' were forced silent.
 */
function introReadableFloor(lang: LangCode): number {
  const copy = LOCALES[lang].intro;
  return readableFloorFrames([copy.eyebrow, copy.title, copy.sub, copy.line].join(' '));
}
function outroReadableFloor(lang: LangCode): number {
  const copy = LOCALES[lang].outro;
  return readableFloorFrames([copy.title, copy.sub, ...copy.bullets, copy.cta].join(' '));
}

/** Intro/outro lengths stretch to cover their narration, and never drop below a readable floor
 *  once silenced (see `introReadableFloor`/`outroReadableFloor` above). */
export function introFrames(lang: LangCode): number {
  return Math.max(
    120,
    introReadableFloor(lang),
    INTRO_VOICE_FROM + framesFor(voiceFor(lang, 'intro')) + VOICE_TAIL,
  );
}
export function outroFrames(lang: LangCode): number {
  return Math.max(
    210,
    outroReadableFloor(lang),
    OUTRO_VOICE_FROM + framesFor(voiceFor(lang, 'outro')) + 30,
  );
}

function bubbleHeight(text: string): number {
  const lines = Math.max(1, Math.ceil(text.length / CHARS_PER_LINE));
  return BUBBLE_CHROME + lines * LINE_HEIGHT + GAP;
}

function cardHeight(card: Card): number {
  switch (card.type) {
    case 'prefs':
      return 96 + card.rows.length * 58 + GAP;
    case 'flags':
      return 110 + card.flags.length * 96 + GAP;
    case 'quote':
      return 250 + card.lines.length * 52 + GAP;
    case 'draft':
      return 330 + (card.subject ? 46 : 0) + Math.ceil(card.body.length / 34) * 40 + GAP;
    case 'timeline':
      return 110 + card.steps.length * 84 + (card.footer ? 60 : 0) + GAP;
    case 'snapshot':
      return 250 + GAP;
    case 'health':
      return 330 + GAP;
    case 'note':
      return 120 + card.items.length * 58 + GAP;
    case 'brands':
      return 120 + card.brands.length * 96 + 56 + GAP;
    case 'hook':
      return 270 + GAP;
    default:
      return 300;
  }
}

function defaultHold(beat: Beat, voice: VoiceClip | undefined): number {
  switch (beat.kind) {
    case 'meera':
    case 'wa': {
      const typed = Math.min(150, Math.max(70, 40 + Math.ceil(beat.text.length / TYPE_RATE)));
      return voice ? Math.max(typed, 8 + framesFor(voice) + VOICE_TAIL) : typed;
    }
    case 'creator':
      return Math.min(90, 45 + Math.ceil(beat.text.length / 12));
    case 'typing':
      return 30;
    case 'card':
      return 90;
    case 'tap':
      return 44;
    case 'system':
      return 36;
    default:
      return 60;
  }
}

export function beatHeight(beat: Beat): number {
  switch (beat.kind) {
    case 'meera':
    case 'wa':
    case 'creator':
      return bubbleHeight(beat.text);
    case 'typing':
      return 84 + GAP;
    case 'card':
      return cardHeight(beat.card);
    case 'tap':
      return 104 + GAP;
    case 'system':
      return 56 + GAP;
    default:
      return 100;
  }
}

export function timeScene(scene: Scene, lang: LangCode): TimedScene {
  let cursor = 12;
  const beats: TimedBeat[] = scene.beats.map((beat, index) => {
    const id = `${scene.id}-${index}`;
    const voice = beat.kind === 'meera' || beat.kind === 'wa' ? voiceFor(lang, id) : undefined;
    let naturalHold = defaultHold(beat, voice);
    // Kavya's MEDIUM (KAVYA-FE-RECHECK-0917.md) — a beat silenced for being STALE must still show
    // its text long enough to read, not just long enough to type out. Only applies when this
    // SPECIFIC id was force-silenced (`STALE_VOICE_IDS`); a beat that simply never had a
    // recording keeps the ordinary `typed` value from `defaultHold` unchanged.
    if (!voice && (beat.kind === 'meera' || beat.kind === 'wa') && STALE_VOICE_IDS.has(id)) {
      naturalHold = Math.max(naturalHold, readableFloorFrames(beat.text));
    }
    // An explicit hold in the script is a minimum; a voice clip can only lengthen it.
    const hold = beat.hold == null ? naturalHold : Math.max(beat.hold, voice ? naturalHold : 0);
    const textLength = 'text' in beat ? beat.text.length : 0;
    const revealFrames = voice ? Math.max(1, framesFor(voice) - 6) : Math.ceil(textLength / TYPE_RATE);
    const timed: TimedBeat = { beat, start: cursor, hold, height: beatHeight(beat), revealFrames, voice };
    cursor += hold;
    return timed;
  });
  return { lang, scene, beats, duration: cursor + (scene.tail ?? 24) };
}

/** Height of the scrollable chat viewport inside the phone screen. */
export const CHAT_VIEWPORT = SCREEN.height - PHONE.header - 40;

/**
 * The content height contributed by a beat at a given frame. A typing
 * indicator only occupies space while it is on screen.
 */
export function heightAt(timed: TimedBeat, frame: number): number {
  if (frame < timed.start) return 0;
  if (timed.beat.kind === 'typing' && frame >= timed.start + timed.hold) return 0;
  return timed.height;
}

export function totalDuration(lang: LangCode, scenes: TimedScene[]): number {
  const scenesTotal = scenes.reduce((acc, s) => acc + s.duration, 0);
  const cuts = scenes.length + 1; // intro→scene1 … sceneN→outro
  return introFrames(lang) + scenesTotal + outroFrames(lang) - cuts * TRANSITION_FRAMES;
}
