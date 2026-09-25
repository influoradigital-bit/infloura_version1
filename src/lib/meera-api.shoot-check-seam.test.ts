/**
 * The Shoot Check photo-check body, from influora-ai's REAL parser to the app's parser.
 *
 * WHY THIS EXISTS. The panel and parser tests feed hand-built bodies, and the Python tests check
 * the body the Python side writes. Neither side reads the other's output, so a key renamed on one
 * side (or a field the other side never sends) would pass both suites and break the app. The
 * fixture below is not hand-built: it is the JSON body `parse_frame_check_reply` returns for four
 * realistic model replies (bedroom window-behind in English on an OPPO A78; a kitchen under a tube
 * light in Hinglish with no saved phone; a park in the sun in English on an OPPO Find X8 Ultra; a
 * photo too dark to judge). The Java proxy passes these bytes through unchanged.
 *
 * influora-ai/tests/prompt/test_frame_check_app_fixture.py builds these bodies and fails when the
 * fixture no longer matches; after an intended change, rewrite it from influora-ai/ with
 * `python tests/prompt/test_frame_check_app_fixture.py --write`.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { parseShootCheckFrameBody, type MeeraShootCheckStep } from './meera-api';

type RawPart = { label: string; value: string; needs_pro: boolean; needs_ois?: boolean };
type RawStep = { kind: string; text: string; note: string; label?: string; parts?: RawPart[] };
type RawBody = Record<string, unknown> & { steps: RawStep[]; lang: string; retake: boolean };

const BODIES = JSON.parse(
  readFileSync(join(process.cwd(), 'src', 'lib', '__fixtures__', 'shoot-check-frame-bodies.json'), 'utf-8')
) as Record<string, RawBody>;

const PRO_PREFIX = { en: 'If your camera app has a Pro video mode: ', hi: 'Agar aapke camera app mein Pro video mode hai: ' };
const OIS_PREFIX = {
  en: 'If your phone has optical stabilisation (OIS): ',
  hi: 'Agar aapke phone mein OIS (optical stabilisation) hai: ',
};

/** A settings step's text rebuilt from its parsed parts alone: the plain parts as "Label: value.",
 *  then each conditional group as "{prefix}label value; label value.". If `parts` and `text` were
 *  ever built separately (a part dropped, added or moved in one of them), this stops matching. */
function rebuildSettingsText(step: MeeraShootCheckStep, lang: 'en' | 'hi'): string {
  const parts = step.parts ?? [];
  const plain = parts.filter((p) => !p.needsPro && p.needsOis !== true).map((p) => `${p.label}: ${p.value}.`);
  const group = (prefix: string, items: typeof parts) =>
    items.length ? [`${prefix}${items.map((p) => `${p.label} ${p.value}`).join('; ')}.`] : [];
  return [
    ...plain,
    ...group(PRO_PREFIX[lang], parts.filter((p) => p.needsPro)),
    ...group(OIS_PREFIX[lang], parts.filter((p) => p.needsOis === true)),
  ].join(' ');
}

describe('the Shoot Check frame body from influora-ai parses into what the panel reads', () => {
  it('has the four realistic cases', () => {
    expect(Object.keys(BODIES).sort()).toEqual([
      'bedroom_window_behind_en_a78',
      'kitchen_tube_light_hi_no_phone',
      'park_sun_en_find_x8_ultra',
      'too_dark_en',
    ]);
  });

  for (const [name, body] of Object.entries(BODIES)) {
    it(`${name}: lang, retake, every step label and every settings part arrive`, () => {
      const result = parseShootCheckFrameBody(body);
      expect(result.lang).toBe(body.lang);
      expect(result.retake).toBe(body.retake);
      // No step is dropped by the app's parser (the server already sorts and caps them).
      expect(result.steps).toHaveLength(body.steps.length);
      body.steps.forEach((raw, i) => {
        const step = result.steps[i];
        expect(step.kind).toBe(raw.kind);
        expect(step.text).toBe(raw.text);
        expect(raw.label, `${name} step ${i} has a label from the server`).toBeTruthy();
        expect(step.label).toBe(raw.label);
        if (raw.parts) {
          expect(step.parts).toEqual(
            raw.parts.map((p) => ({
              label: p.label,
              value: p.value,
              needsPro: p.needs_pro,
              ...(p.needs_ois ? { needsOis: true } : {}),
            }))
          );
        } else {
          expect('parts' in step).toBe(false);
        }
      });
    });
  }

  it('every settings step carries parts, and its parts rebuild its text exactly', () => {
    let settingsSteps = 0;
    for (const body of Object.values(BODIES)) {
      const result = parseShootCheckFrameBody(body);
      for (const step of result.steps.filter((s) => s.kind === 'settings')) {
        settingsSteps += 1;
        expect(step.parts?.length).toBeGreaterThan(0);
        expect(rebuildSettingsText(step, result.lang ?? 'en').toLowerCase()).toBe(step.text.toLowerCase());
      }
    }
    expect(settingsSteps).toBe(3);
  });

  it('the bedroom case is the English window-behind reply the demo copies', () => {
    const r = parseShootCheckFrameBody(BODIES.bedroom_window_behind_en_a78);
    expect(r.lang).toBe('en');
    expect(r.retake).toBe(false);
    expect(r.steps.map((s) => [s.kind, s.label])).toEqual([
      ['move_you', 'Window behind you'],
      ['move_phone', 'Eye-level phone'],
      ['settings', 'Talking head by a window'],
    ]);
    expect(r.steps[2].parts?.map((p) => `${p.label}=${p.value}`)).toEqual([
      'Lens=1x Main',
      'Distance=0.8-1m',
      'Framing=Chest up',
      'EV=+0.5',
      'Stabilization=Tripod (stabilization off)',
    ]);
    expect(r.ask?.id).toBe('can_move');
  });

  it('the Hinglish kitchen reply (no saved phone) says hi, and keeps its Pro and OIS parts conditional', () => {
    const r = parseShootCheckFrameBody(BODIES.kitchen_tube_light_hi_no_phone);
    expect(r.lang).toBe('hi');
    const parts = r.steps.find((s) => s.kind === 'settings')?.parts ?? [];
    expect(parts.filter((p) => p.needsPro).map((p) => p.label)).toEqual(['FPS', 'Shutter', 'ISO', 'White balance']);
    expect(parts.filter((p) => p.needsOis === true).map((p) => p.label)).toEqual(['Phone steady']);
    // Labels are in the reply language, and no step caption is the raw knowledge-row name.
    expect(r.steps.map((s) => s.label)).not.toContain('Kitchen/corridor mixed lights');
  });

  it('the Find X8 Ultra has a Pro video mode, so none of its settings are conditional', () => {
    const r = parseShootCheckFrameBody(BODIES.park_sun_en_find_x8_ultra);
    const parts = r.steps.find((s) => s.kind === 'settings')?.parts ?? [];
    expect(parts.length).toBeGreaterThan(0);
    expect(parts.some((p) => p.needsPro || p.needsOis === true)).toBe(false);
  });

  it('a photo too dark to judge is a retake with its one line and nothing else', () => {
    const r = parseShootCheckFrameBody(BODIES.too_dark_en);
    expect(r.retake).toBe(true);
    expect(r.whatISee).toMatch(/too dark/i);
    expect(r.steps).toEqual([]);
    expect(r.ok).toEqual([]);
    expect(r.cantTell).toEqual([]);
    expect(r.ask).toBeNull();
  });
});
