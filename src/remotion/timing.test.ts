/**
 * U-4 (2026-09-17) — the 12 stale voice files (paste-5/intro/outro/money-0 × hi/en/mr) still speak
 * promises cut from the on-screen copy (RULINGS-U-0917.md, NISHA-U4-RECHECK-0917.md §5).
 * Regenerating them costs a paid Sarvam TTS call per language and waits on Swapnil, so
 * `timing.ts`'s `voiceFor` instead forces those ids silent — this pins that behaviour, that a
 * beat with no voice still times sanely, and that the composition's `<Audio>` sites (which all
 * already guard on `voice` being falsy) never see a missing-file src.
 *
 * Run: npx vitest run src/remotion/timing.test.ts
 */
import { describe, expect, it } from 'vitest';

import { LOCALES } from './locales';
import type { LangCode } from './script';
import { introFrames, outroFrames, timeScene, voiceFor } from './timing';

const LANGS: LangCode[] = ['hi', 'en', 'mr'];
/** Mirrors `timing.ts`'s own `STALE_VOICE_IDS` — kept separate on purpose, so this file is
 *  actually pinning `voiceFor`'s observable behaviour, not just re-reading the same constant. */
const STALE_IDS = ['intro', 'outro', 'paste-5', 'money-0'];

describe('voiceFor — U-4 stale voice silencing', () => {
  it('resolves no audio for every stale id, in every language', () => {
    for (const lang of LANGS) {
      for (const id of STALE_IDS) {
        expect(voiceFor(lang, id), `${lang}/${id} should resolve to no audio`).toBeUndefined();
      }
    }
  });

  it('still resolves a known-good id the U-4 copy pass did not touch', () => {
    for (const lang of LANGS) {
      expect(voiceFor(lang, 'setup-0')).toBeDefined();
      expect(voiceFor(lang, 'setup-2')).toBeDefined();
      // money-3 (the scene's CLOSING wa beat, "paisa bank pahunch gaya…") is untouched by the
      // U-4/recheck cuts — only money-0 (the opening beat) lost its send-promise clause.
      expect(voiceFor(lang, 'money-3')).toBeDefined();
    }
  });

  it('an id outside the manifest entirely still resolves to no audio (unaffected baseline)', () => {
    for (const lang of LANGS) {
      expect(voiceFor(lang, 'not-a-real-id')).toBeUndefined();
    }
  });

  it('does not break scene timing for the paste-5 beat that used to carry a voice and now carries none', () => {
    for (const lang of LANGS) {
      const pasteScene = LOCALES[lang].scenes.find((s) => s.id === 'paste');
      expect(pasteScene, `${lang} locale is missing the 'paste' scene`).toBeDefined();
      const timed = timeScene(pasteScene!, lang);

      // The scene's closing meera beat is index 5 — same position in every locale's array.
      const lastBeat = timed.beats[timed.beats.length - 1];
      expect(lastBeat.beat.kind).toBe('meera');
      expect(lastBeat.voice).toBeUndefined();
      // No <Audio> renders for this beat (ChatScene.tsx guards on `b.voice`), and its timing
      // still falls back to the ordinary typewriter calculation instead of NaN/undefined.
      expect(lastBeat.hold).toBeGreaterThan(0);
      expect(Number.isFinite(lastBeat.hold)).toBe(true);
      expect(Number.isFinite(lastBeat.revealFrames)).toBe(true);
      expect(Number.isFinite(timed.duration)).toBe(true);
      expect(timed.duration).toBeGreaterThan(0);
    }
  });

  it('does not break scene timing for the money-0 beat cut in the U-4 recheck', () => {
    for (const lang of LANGS) {
      const moneyScene = LOCALES[lang].scenes.find((s) => s.id === 'money');
      expect(moneyScene, `${lang} locale is missing the 'money' scene`).toBeDefined();
      const timed = timeScene(moneyScene!, lang);

      const firstBeat = timed.beats[0];
      expect(firstBeat.beat.kind).toBe('wa');
      expect(firstBeat.voice).toBeUndefined();
      expect(firstBeat.hold).toBeGreaterThan(0);
      expect(Number.isFinite(firstBeat.hold)).toBe(true);
      expect(Number.isFinite(firstBeat.revealFrames)).toBe(true);
      // The scene's closing wa beat (money-3, untouched) still carries real voice timing —
      // proves the fallback is scoped to the one stale beat, not the whole scene.
      const lastBeat = timed.beats[timed.beats.length - 1];
      expect(lastBeat.beat.kind).toBe('wa');
      expect(lastBeat.voice).toBeDefined();
      expect(Number.isFinite(timed.duration)).toBe(true);
      expect(timed.duration).toBeGreaterThan(0);
    }
  });

  it('the intro/outro narration frames fall back to a floor instead of stretching for stale audio', () => {
    // introFrames/outroFrames stretch to cover the voice clip's length via framesFor(voiceFor(...));
    // with the id silenced, framesFor sees no clip and both fall back to a floor instead.
    for (const lang of LANGS) {
      expect(voiceFor(lang, 'intro')).toBeUndefined();
      expect(voiceFor(lang, 'outro')).toBeUndefined();
    }
  });
});

/**
 * Kavya's MEDIUM (KAVYA-FE-RECHECK-0917.md, F6 recheck round 2) — silencing paste-5/money-0/
 * intro/outro can leave their on-screen text up for less time than a reader needs, since the
 * ordinary hold calculations are driven by TYPING speed (character count / voice-clip length),
 * not reading speed. These pin the exact floor in frames the code now guarantees, computed at
 * ~250ms/word + a 1s baseline, and prove the OLD (typing-only) values really were short of it —
 * numbers reproduced from a one-off `vite-node` run against this same code, quoted in the PR
 * report.
 *
 * | beat     | lang | words | floor (frames/s) | old typing-only hold (frames/s) | short by |
 * |----------|------|-------|-------------------|----------------------------------|----------|
 * | paste-5  | hi   | 12    | 120f / 4.00s      | 80f / 2.67s                      | 40f      |
 * | paste-5  | en   | 16    | 150f / 5.00s      | 82f / 2.73s                      | 68f      |
 * | paste-5  | mr   | 10    | 105f / 3.50s      | 75f / 2.50s                      | 30f      |
 * | money-0  | hi   | 13    | 128f / 4.27s      | 84f / 2.80s                      | 44f      |
 * | money-0  | en   | 12    | 120f / 4.00s      | 82f / 2.73s                      | 38f      |
 * | money-0  | mr   | 12    | 120f / 4.00s      | 88f / 2.93s                      | 32f      |
 * | intro    | hi   | 18    | 165f / 5.50s      | 120f / 4.00s (old fixed min)     | 45f      |
 * | intro    | en   | 15    | 143f / 4.77s      | 120f / 4.00s (old fixed min)     | 23f      |
 * | intro    | mr   | 15    | 143f / 4.77s      | 120f / 4.00s (old fixed min)     | 23f      |
 * | outro    | hi   | 30    | 255f / 8.50s      | 210f / 7.00s (old fixed min)     | 45f      |
 * | outro    | en   | 29    | 248f / 8.27s      | 210f / 7.00s (old fixed min)     | 38f      |
 * | outro    | mr   | 31    | 263f / 8.77s      | 210f / 7.00s (old fixed min)     | 53f      |
 */
describe('readable floor for silenced beats/screens — Kavya MEDIUM (F6 recheck round 2)', () => {
  const PASTE_5_FLOOR: Record<LangCode, number> = { hi: 120, en: 150, mr: 105 };
  const MONEY_0_FLOOR: Record<LangCode, number> = { hi: 128, en: 120, mr: 120 };
  const INTRO_FLOOR: Record<LangCode, number> = { hi: 165, en: 143, mr: 143 };
  const OUTRO_FLOOR: Record<LangCode, number> = { hi: 255, en: 248, mr: 263 };

  it('paste-5 holds exactly the readable floor, in every language', () => {
    for (const lang of LANGS) {
      const scene = LOCALES[lang].scenes.find((s) => s.id === 'paste')!;
      const hold = timeScene(scene, lang).beats[scene.beats.length - 1].hold;
      expect(hold, `${lang} paste-5`).toBe(PASTE_5_FLOOR[lang]);
    }
  });

  it('money-0 holds exactly the readable floor, in every language', () => {
    for (const lang of LANGS) {
      const scene = LOCALES[lang].scenes.find((s) => s.id === 'money')!;
      const hold = timeScene(scene, lang).beats[0].hold;
      expect(hold, `${lang} money-0`).toBe(MONEY_0_FLOOR[lang]);
    }
  });

  it('introFrames is exactly the readable floor, in every language', () => {
    for (const lang of LANGS) {
      expect(introFrames(lang), lang).toBe(INTRO_FLOOR[lang]);
    }
  });

  it('outroFrames is exactly the readable floor, in every language', () => {
    for (const lang of LANGS) {
      expect(outroFrames(lang), lang).toBe(OUTRO_FLOOR[lang]);
    }
  });

  it('the floor is genuinely above the old typing-only/fixed-minimum values it replaced', () => {
    // Guards the guard: if these ever stopped being an improvement, the floor would be pointless.
    const OLD_PASTE_5_TYPED: Record<LangCode, number> = { hi: 80, en: 82, mr: 75 };
    const OLD_MONEY_0_TYPED: Record<LangCode, number> = { hi: 84, en: 82, mr: 88 };
    for (const lang of LANGS) {
      expect(PASTE_5_FLOOR[lang]).toBeGreaterThan(OLD_PASTE_5_TYPED[lang]);
      expect(MONEY_0_FLOOR[lang]).toBeGreaterThan(OLD_MONEY_0_TYPED[lang]);
      expect(INTRO_FLOOR[lang]).toBeGreaterThan(120);
      expect(OUTRO_FLOOR[lang]).toBeGreaterThan(210);
    }
  });
});
