import { LANG_ORDER, LOCALES } from './locales';
import type { LangCode } from './script';
import { timeScene, totalDuration, type TimedScene } from './timing';

/** Every scene with its computed beat timings, per language. */
export const TIMED_SCENES: Record<LangCode, TimedScene[]> = Object.fromEntries(
  LANG_ORDER.map((lang) => [lang, LOCALES[lang].scenes.map((scene) => timeScene(scene, lang))]),
) as Record<LangCode, TimedScene[]>;

/** Total length of each language's composition in frames, transitions accounted for. */
export const MEERA_DEMO_DURATION: Record<LangCode, number> = Object.fromEntries(
  LANG_ORDER.map((lang) => [lang, totalDuration(lang, TIMED_SCENES[lang])]),
) as Record<LangCode, number>;

export const DEFAULT_LANG: LangCode = 'hi';
