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

export function voiceFor(lang: LangCode, id: string): VoiceClip | undefined {
  return VOICE_MANIFEST[lang]?.[id];
}

function framesFor(clip: VoiceClip | undefined): number {
  return clip ? Math.ceil(clip.seconds * VIDEO.fps) : 0;
}

/** Frame the intro narration starts on. */
export const INTRO_VOICE_FROM = 30;
/** Frame the outro narration starts on. */
export const OUTRO_VOICE_FROM = 20;

/** Intro/outro lengths stretch to cover their narration. */
export function introFrames(lang: LangCode): number {
  return Math.max(120, INTRO_VOICE_FROM + framesFor(voiceFor(lang, 'intro')) + VOICE_TAIL);
}
export function outroFrames(lang: LangCode): number {
  return Math.max(210, OUTRO_VOICE_FROM + framesFor(voiceFor(lang, 'outro')) + 30);
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
    const voice =
      beat.kind === 'meera' || beat.kind === 'wa' ? voiceFor(lang, `${scene.id}-${index}`) : undefined;
    const naturalHold = defaultHold(beat, voice);
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
