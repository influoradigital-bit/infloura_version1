/**
 * "Check my frame" must call a route the API server actually has.
 *
 * WHY THIS EXISTS. The first version posted to `/ai/shoot-check/frame` — influora-ai's own path.
 * The app never talks to influora-ai directly (that service accepts only a service token), so the
 * request landed on the API server, which had no such route, and every tap failed. The Python route
 * tests passed against the Python route; the panel tests passed against a mocked `checkFrame`.
 * Nothing compared the URL the app builds with the mapping the Java controller declares.
 *
 * This test does: it reads `CreatorMeeraController.java` for its `@RequestMapping` base and the
 * frame route's `@PostMapping`, and asserts the app's real fetch hits exactly that path.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Live by default; the demo-mode test flips this to read the offline sample.
const apiLive = vi.hoisted(() => ({ value: true }));
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => apiLive.value };
});

import {
  ANSWERS_MAX_CHARS,
  SHOT_CONTEXT_MAX_CHARS,
  meeraApi,
  serializeCoachAnswers,
  serializeShotContext,
} from './meera-api';

const CONTROLLER = join(
  process.cwd(),
  'influora-api',
  'src',
  'main',
  'java',
  'com',
  'influora',
  'web',
  'CreatorMeeraController.java',
);

/** `/creator/meera` + `/shoot-check/frame`, read from the Java source rather than retyped. */
function javaFrameRoute(): string {
  const source = readFileSync(CONTROLLER, 'utf8');
  const base = source.match(/@RequestMapping\("([^"]+)"\)/)?.[1];
  const frame = source.match(/@PostMapping\(\s*value\s*=\s*"([^"]*shoot-check[^"]*)"/)?.[1];
  if (!base || !frame) {
    throw new Error('CreatorMeeraController no longer declares the shoot-check frame route');
  }
  return `${base}${frame}`;
}

describe('checkFrame route', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ fixes: ['Step right'], settings: [], ok: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('the Java controller declares the route this test expects', () => {
    expect(javaFrameRoute()).toBe('/creator/meera/shoot-check/frame');
  });

  it('posts to the exact path the Java controller maps, not influora-ai’s internal one', async () => {
    await meeraApi.checkFrame(new Blob(['x'], { type: 'image/jpeg' }), 'static overhead', 'creator');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const url = String(fetchMock.mock.calls[0][0]);
    expect(new URL(url).pathname.endsWith(javaFrameRoute())).toBe(true);
    expect(url).not.toContain('/ai/shoot-check');
  });

  it('sends the image and shot label, and no client-chosen identity', async () => {
    await meeraApi.checkFrame(new Blob(['x'], { type: 'image/jpeg' }), 'static overhead', 'creator');

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const form = init.body as FormData;
    expect(init.method).toBe('POST');
    expect(form.get('image')).toBeInstanceOf(Blob);
    expect(form.get('shot_label')).toBe('static overhead');
    // The server resolves the creator from the auth token; a body field could only be spoofed.
    expect(form.get('workspace_id')).toBeNull();
  });

  it('parses the three lists from the proxy’s response', async () => {
    const result = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect(result).toEqual({
      kind: 'ok',
      result: {
        fixes: ['Step right'],
        settings: [],
        ok: [],
        whatISee: null,
        steps: [],
        cantTell: [],
        ask: null,
        lang: null,
        retake: false,
      },
    });
  });

  it('returns "unavailable" on the proxy’s failure codes, so the panel says it could not check', async () => {
    for (const status of [400, 413, 502]) {
      fetchMock.mockResolvedValueOnce(new Response('{"code":"X"}', { status }));
      expect(await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator')).toEqual({ kind: 'unavailable' });
    }
  });
});

/**
 * C4 — influora-ai's `shoot_check_frame` route (`influora-ai/app/routes/shoot_check.py`) answers
 * EVERY failure and gate-block path with HTTP 200 and `fallback: true`, `fixes` filled with
 * placeholder text from `fallback_response()` — never a real per-photo check. The Java proxy
 * passes that body through verbatim (`CreatorMeeraController#checkFrame` — `result.jsonBytes()`,
 * unmodified, still HTTP 200). These two bodies are copied from that route's own return
 * statements, not retyped: the oversize-upload fallback (a representative ordinary fallback) and
 * the creator monthly-cap fallback (`app/costs/spend_tracker.py`'s `CREATOR_CAP_MESSAGE` /
 * `CREATOR_CAP_CODE`), which is the one fallback body `checkFrame` must surface a message for.
 */
describe('checkFrame vs influora-ai’s fallback envelope', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('an ordinary fallback (e.g. the oversize-upload block) is never treated as a real result', async () => {
    // Copied verbatim from shoot_check.py's oversize-image branch:
    //   return {**fallback_response(), "fallback": True,
    //           "fixes": ["That photo is too large -- please use one under 1.5 MB and try again."]}
    fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          fixes: ['That photo is too large -- please use one under 1.5 MB and try again.'],
          settings: [],
          ok: [],
          fallback: true,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    const outcome = await meeraApi.checkFrame(new Blob(['x'], { type: 'image/jpeg' }), undefined, 'creator');
    expect(outcome).toEqual({ kind: 'unavailable' });
  });

  it('the creator monthly-cap fallback surfaces its own message instead of "unavailable" or a Fix', async () => {
    // Copied verbatim from shoot_check.py's `_creator_cap_gate` block:
    //   return {**fallback_response(), "fallback": True, "message": CREATOR_CAP_MESSAGE, "code": CREATOR_CAP_CODE}
    // fallback_response() == {"fixes": [FALLBACK_FIX], "settings": [], "ok": []}
    const CREATOR_CAP_MESSAGE =
      "You've reached your monthly Meera usage limit. It resets on the 1st of next month. "
      + "If you need more before then, message support and we'll sort it out.";
    fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          fixes: ["Couldn't check that photo just now -- please try uploading it again."],
          settings: [],
          ok: [],
          fallback: true,
          message: CREATOR_CAP_MESSAGE,
          code: 'CREATOR_MONTHLY_CAP_REACHED',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    const outcome = await meeraApi.checkFrame(new Blob(['x'], { type: 'image/jpeg' }), undefined, 'creator');
    expect(outcome).toEqual({ kind: 'capped', message: CREATOR_CAP_MESSAGE });
  });
});

/**
 * The coach response (2026-09-25): influora-ai returns `what_i_see`, `steps` (each naming the
 * knowledge entry it comes from), `cant_tell` and at most one coach-bank `ask`, plus the legacy
 * `fixes`/`settings`/`ok` lists for older clients. The request gains two optional text parts,
 * `shot_context` and `answers`, which the Java proxy rejects with 400 above 1000/600 characters.
 */
describe('checkFrame — coach response and request (2026-09-25)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  function respondWith(body: unknown): void {
    fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchMock);
  }

  function sentForm(): FormData {
    return (fetchMock.mock.calls[0][1] as RequestInit).body as FormData;
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const COACH_BODY = {
    what_i_see: 'You at a desk, window to your left, right side of your face in shadow',
    steps: [
      { kind: 'settings', text: 'Lock focus and exposure on your face', note: 'Talking Head (Window light)' },
      { kind: 'move_you', text: 'Turn about 30-45 degrees towards the window', note: 'Soft natural window light' },
      { kind: 'move_phone', text: 'Raise the phone to eye height', note: 'Eye level' },
    ],
    ok: ['Background is tidy'],
    cant_tell: ['Whether there is a lamp in the room'],
    ask: {
      id: 'other_light',
      question_en: 'Is there another light you can use?',
      question_hi: 'Koi aur light hai?',
      options: [
        { en: 'A lamp', hi: 'Lamp' },
        { en: 'Nothing else', hi: 'Aur kuch nahi' },
      ],
    },
    fixes: ['Turn about 30-45 degrees towards the window', 'Raise the phone to eye height'],
    settings: ['Lock focus and exposure on your face'],
  };

  it('parses what_i_see, the steps (sorted move_you, move_phone, move_light, settings), cant_tell and ask', async () => {
    respondWith(COACH_BODY);
    const outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect(outcome).toEqual({
      kind: 'ok',
      result: {
        whatISee: 'You at a desk, window to your left, right side of your face in shadow',
        // No `label` from this server: each step's label falls back to its note.
        steps: [
          {
            kind: 'move_you',
            text: 'Turn about 30-45 degrees towards the window',
            note: 'Soft natural window light',
            label: 'Soft natural window light',
          },
          { kind: 'move_phone', text: 'Raise the phone to eye height', note: 'Eye level', label: 'Eye level' },
          {
            kind: 'settings',
            text: 'Lock focus and exposure on your face',
            note: 'Talking Head (Window light)',
            label: 'Talking Head (Window light)',
          },
        ],
        cantTell: ['Whether there is a lamp in the room'],
        ask: {
          id: 'other_light',
          questionEn: 'Is there another light you can use?',
          questionHi: 'Koi aur light hai?',
          options: [
            { en: 'A lamp', hi: 'Lamp' },
            { en: 'Nothing else', hi: 'Aur kuch nahi' },
          ],
        },
        fixes: ['Turn about 30-45 degrees towards the window', 'Raise the phone to eye height'],
        settings: ['Lock focus and exposure on your face'],
        ok: ['Background is tidy'],
        lang: null,
        retake: false,
      },
    });
  });

  it('drops malformed steps, caps steps at 5 and cant_tell at 3, and reads a null ask', async () => {
    respondWith({
      what_i_see: '   ',
      steps: [
        { kind: 'jump', text: 'Unknown kind', note: 'x' },
        { kind: 'move_you', text: '', note: 'x' },
        'not an object',
        { kind: 'move_light', text: 'Put the lamp on your left', note: 42 },
        ...Array.from({ length: 6 }, (_, n) => ({ kind: 'move_you', text: 'Step ' + n, note: 'n' })),
      ],
      cant_tell: ['a', 'b', 'c', 'd'],
      ask: null,
      fixes: [],
      settings: [],
      ok: [],
    });
    const outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    if (outcome.kind !== 'ok') throw new Error('expected ok');
    expect(outcome.result.whatISee).toBeNull();
    // The move_light step (note not a string -> empty note) sorts after the move_you steps and
    // falls off the cap of 5.
    expect(outcome.result.steps).toHaveLength(5);
    expect(outcome.result.steps.every((s) => s.kind === 'move_you')).toBe(true);
    expect(outcome.result.cantTell).toEqual(['a', 'b', 'c']);
    expect(outcome.result.ask).toBeNull();
  });

  it('ignores an ask that is not a tappable bank question (fewer than 2 options, or no question)', async () => {
    respondWith({ ...COACH_BODY, ask: { id: 'room_size', question_en: 'Big room?', options: [{ en: 'Yes', hi: 'Haan' }] } });
    let outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect(outcome.kind === 'ok' && outcome.result.ask).toBeNull();

    respondWith({ ...COACH_BODY, ask: { id: 'other_light' } });
    outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect(outcome.kind === 'ok' && outcome.result.ask).toBeNull();
  });

  it('an older server with only the three lists still parses, with empty coach fields', async () => {
    respondWith({ fixes: ['Step right'], settings: ['Grid on'], ok: ['Good light'] });
    const outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect(outcome).toEqual({
      kind: 'ok',
      result: {
        fixes: ['Step right'],
        settings: ['Grid on'],
        ok: ['Good light'],
        whatISee: null,
        steps: [],
        cantTell: [],
        ask: null,
        lang: null,
        retake: false,
      },
    });
  });

  it('sends shot_context and answers as JSON text parts when given', async () => {
    respondWith(COACH_BODY);
    await meeraApi.checkFrame(new Blob(['x']), 'talking head', 'creator', {
      shotContext: { line: 'talking head at the desk', angle: 'eye level', where: 'bedroom desk', light: '' },
      answers: [
        { id: 'other_light', option: 0 },
        { id: 'room_size', option: 1 },
      ],
    });
    const form = sentForm();
    expect(form.get('shot_label')).toBe('talking head');
    expect(JSON.parse(String(form.get('shot_context')))).toEqual({
      line: 'talking head at the desk',
      angle: 'eye level',
      where: 'bedroom desk',
    });
    expect(JSON.parse(String(form.get('answers')))).toEqual([
      { id: 'other_light', option: 0 },
      { id: 'room_size', option: 1 },
    ]);
  });

  it('sends neither part when there is nothing in them', async () => {
    respondWith(COACH_BODY);
    await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', { shotContext: { line: '   ' }, answers: [] });
    const form = sentForm();
    expect(form.has('shot_context')).toBe(false);
    expect(form.has('answers')).toBe(false);
  });

  it('keeps shot_context within 1000 characters and answers within 3 items and 600 characters', () => {
    const long = 'x'.repeat(900);
    const context = serializeShotContext({ line: long, angle: long, action: long, where: long, prop: long });
    expect(context).not.toBeNull();
    expect(context!.length).toBeLessThanOrEqual(SHOT_CONTEXT_MAX_CHARS);
    // The planned line is the highest-priority key, so it survives the trim.
    expect(JSON.parse(context!).line).toBe('x'.repeat(300));

    const json = serializeCoachAnswers([
      { id: 'other_light', option: 0 },
      { id: 'can_move', option: 1 },
      { id: 'room_size', option: 0 },
      { id: 'window_side', option: 2 },
      { id: 'can_move', option: 0 },
      { id: 'bad', option: -1 },
      { id: '', option: 0 },
    ]);
    expect(json).not.toBeNull();
    expect(json!.length).toBeLessThanOrEqual(ANSWERS_MAX_CHARS);
    // Latest answer per id, the most recent three, malformed items dropped.
    expect(JSON.parse(json!)).toEqual([
      { id: 'room_size', option: 0 },
      { id: 'window_side', option: 2 },
      { id: 'can_move', option: 0 },
    ]);
    expect(serializeCoachAnswers([{ id: 'x'.repeat(700), option: 0 }])).toBeNull();
  });
});

/**
 * The coach layout fields (2026-09-25, contract in influora-ai `app/prompt/frame_check.py`):
 * top-level `lang` and `retake`, a creator-facing `label` on every step, and `parts` on a step from
 * a phone-settings entry. All additive: the Java proxy passes the JSON through unchanged, and an
 * older server that sends none of them must still parse.
 */
describe('checkFrame — lang, retake, step labels and settings parts', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  function respondWith(body: unknown): void {
    fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchMock);
  }

  async function parsed(body: unknown) {
    respondWith(body);
    const outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    if (outcome.kind !== 'ok') throw new Error('expected ok, got ' + outcome.kind);
    return outcome.result;
  }

  afterEach(() => {
    vi.unstubAllGlobals();
    apiLive.value = true;
  });

  const SETTINGS_STEP = {
    kind: 'settings',
    text: 'Lens: 1x Main. EV: +0.5. If your camera app has a Pro video mode: Shutter: 1/50.',
    note: 'Talking Head (Window light)',
    label: 'Talking head by a window',
    parts: [
      { label: 'Lens', value: '1x Main', needs_pro: false },
      { label: 'EV', value: '+0.5', needs_pro: false },
      { label: 'Shutter', value: '1/50', needs_pro: true },
    ],
  };

  it('reads lang, retake, each step label, and a settings step parts (needs_pro -> needsPro)', async () => {
    const result = await parsed({
      lang: 'hi',
      retake: false,
      what_i_see: 'Aap desk pe ho',
      steps: [SETTINGS_STEP, { kind: 'move_you', text: 'Window ki taraf ghoomo', note: 'Soft natural window light', label: 'Window ki soft light' }],
      ok: ['Background saaf hai'],
      cant_tell: [],
      ask: null,
      fixes: ['Window ki taraf ghoomo'],
      settings: [SETTINGS_STEP.text],
    });
    expect(result.lang).toBe('hi');
    expect(result.retake).toBe(false);
    expect(result.steps).toEqual([
      { kind: 'move_you', text: 'Window ki taraf ghoomo', note: 'Soft natural window light', label: 'Window ki soft light' },
      {
        kind: 'settings',
        text: SETTINGS_STEP.text,
        note: 'Talking Head (Window light)',
        label: 'Talking head by a window',
        parts: [
          { label: 'Lens', value: '1x Main', needsPro: false },
          { label: 'EV', value: '+0.5', needsPro: false },
          { label: 'Shutter', value: '1/50', needsPro: true },
        ],
      },
    ]);
    // A step from any other row has no `parts` key at all.
    expect('parts' in result.steps[0]).toBe(false);
  });

  it('keeps needs_ois as needsOis (only when exactly true, never on a Pro part)', async () => {
    const result = await parsed({
      what_i_see: 'Aap kitchen mein ho',
      steps: [
        {
          kind: 'settings',
          text: 'Lens: 1x Main.',
          note: 'Food close-up (restaurant or home)',
          label: 'Khaane ka close-up',
          parts: [
            { label: 'Lens', value: '1x Main', needs_pro: false, needs_ois: false },
            { label: 'FPS', value: '25', needs_pro: true, needs_ois: true },
            { label: 'Phone steady', value: 'OIS only', needs_pro: false, needs_ois: true },
            { label: 'EV', value: '-0.3', needs_pro: false, needs_ois: 'yes' },
          ],
        },
      ],
      ok: ['fine'],
    });
    expect(result.steps[0].parts).toEqual([
      { label: 'Lens', value: '1x Main', needsPro: false },
      { label: 'FPS', value: '25', needsPro: true },
      { label: 'Phone steady', value: 'OIS only', needsPro: false, needsOis: true },
      { label: 'EV', value: '-0.3', needsPro: false },
    ]);
    expect(result.steps[0].parts?.filter((p) => 'needsOis' in p).map((p) => p.label)).toEqual(['Phone steady']);
  });

  it('never throws on malformed parts, and drops the whole list when any item is bad so the text shows', async () => {
    const badItems: unknown[] = [
      null,
      'Lens: 1x Main',
      ['Lens', '1x Main'],
      { label: '', value: '1x Main', needs_pro: false },
      { label: 'EV', value: 0.5, needs_pro: false },
      { label: 'FPS' },
    ];
    for (const bad of badItems) {
      const result = await parsed({
        what_i_see: 'You at a desk',
        steps: [
          {
            kind: 'settings',
            text: 'Lens: 1x Main. ISO: 100-200.',
            note: 'n',
            label: 'L',
            parts: [{ label: 'Lens', value: '1x Main', needs_pro: false }, bad, { label: 'ISO', value: '100-200', needs_pro: true }],
          },
        ],
        ok: ['fine'],
      });
      // One dropped item would make the list shorter than the text, so the text is shown instead.
      expect(result.steps[0].text).toBe('Lens: 1x Main. ISO: 100-200.');
      expect('parts' in result.steps[0]).toBe(false);
    }
    const result = await parsed({
      what_i_see: 'You at a desk',
      steps: [
        {
          kind: 'settings',
          text: 'Lens: 1x Main.',
          note: 'n',
          label: 'L',
          parts: [
            { label: ' Lens ', value: ' 1x Main ', needs_pro: 'yes' },
            { label: 'ISO', value: '100-200', needs_pro: true },
          ],
        },
        { kind: 'settings', text: 'Grid on.', note: 'n2', label: 'L2', parts: [{ nope: 1 }, 7] },
        { kind: 'move_phone', text: 'Raise the phone', note: 'Eye-level', label: 42, parts: 'not a list' },
      ],
      ok: ['fine'],
    });
    expect(result.steps.find((s) => s.text === 'Lens: 1x Main.')?.parts).toEqual([
      // needs_pro counts only when it is exactly true.
      { label: 'Lens', value: '1x Main', needsPro: false },
      { label: 'ISO', value: '100-200', needsPro: true },
    ]);
    expect(result.steps.find((s) => s.text === 'Lens: 1x Main.')?.parts?.some((p) => 'needsOis' in p)).toBe(false);
    const allBad = result.steps.find((s) => s.text === 'Grid on.');
    expect(allBad && 'parts' in allBad).toBe(false);
    const phone = result.steps.find((s) => s.kind === 'move_phone');
    // A label that is not text falls back to the note; parts that are not a list are ignored.
    expect(phone?.label).toBe('Eye-level');
    expect(phone && 'parts' in phone).toBe(false);
  });

  it('keeps parts only on a settings step: well-formed parts on another kind never hide its text', async () => {
    const goodParts = [{ label: 'Lens', value: '1x Main', needs_pro: false }];
    const result = await parsed({
      what_i_see: 'You at a desk',
      steps: [
        { kind: 'move_you', text: 'Turn so the window is in front of you', note: 'n', label: 'Window behind you', parts: goodParts },
        { kind: 'move_phone', text: 'Raise the phone', note: 'n', label: 'Eye-level phone', parts: goodParts },
        { kind: 'move_light', text: 'Switch off the tube light', note: 'n', label: 'Mixed light colours', parts: goodParts },
        { kind: 'settings', text: 'Lens: 1x Main.', note: 'n', label: 'L', parts: goodParts },
      ],
      ok: ['fine'],
    });
    for (const step of result.steps) {
      if (step.kind === 'settings') expect(step.parts).toEqual([{ label: 'Lens', value: '1x Main', needsPro: false }]);
      else expect('parts' in step).toBe(false);
    }
  });

  it('reads any lang other than "en" or "hi" as not said (null)', async () => {
    for (const lang of ['fr', 'HI', 7, null, undefined]) {
      const result = await parsed({ lang, what_i_see: 'x', steps: [{ kind: 'move_you', text: 't', note: 'n' }] });
      expect(result.lang).toBeNull();
    }
    expect((await parsed({ lang: 'en', what_i_see: 'x', ok: ['a'] })).lang).toBe('en');
  });

  it('believes an explicit retake, and infers one from an older server that gives only what_i_see', async () => {
    const unusable = { what_i_see: "It's too dark to see you. Please retake the photo.", steps: [], ok: [], cant_tell: [], ask: null };
    expect((await parsed({ ...unusable, retake: true })).retake).toBe(true);
    // Older server: no retake field, only the one line.
    expect((await parsed(unusable)).retake).toBe(true);
    // A server that says false is believed even for that shape.
    expect((await parsed({ ...unusable, retake: false })).retake).toBe(false);
    // A normal older-server body with steps is not a retake; neither is one with no what_i_see.
    expect((await parsed({ ...unusable, steps: [{ kind: 'move_you', text: 't', note: 'n' }] })).retake).toBe(false);
    expect((await parsed({ fixes: ['Step right'], settings: [], ok: [] })).retake).toBe(false);
    // A retake that is not a boolean is treated as not sent.
    expect((await parsed({ ...unusable, steps: [{ kind: 'move_you', text: 't', note: 'n' }], retake: 'yes' })).retake).toBe(false);
  });

  it('demo mode returns a realistic sample in the new shape, and no question once one is answered', async () => {
    apiLive.value = false;
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const first = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    if (first.kind !== 'ok') throw new Error('expected ok');
    expect(fetchMock).not.toHaveBeenCalled();
    const r = first.result;
    expect(r.lang).toBe('en');
    expect(r.retake).toBe(false);
    expect(r.whatISee).toContain('bedroom');
    expect(r.steps.map((s) => [s.kind, s.label])).toEqual([
      ['move_you', 'Window behind you'],
      ['move_phone', 'Eye-level phone'],
      ['settings', 'Talking head by a window'],
    ]);
    expect(r.steps[1].text).toBe('Phone at eye-level: neutral point of view, reliable eye contact.');
    expect(r.steps[2].parts).toEqual([
      { label: 'Lens', value: '1x Main', needsPro: false },
      { label: 'Distance', value: '0.8-1m', needsPro: false },
      { label: 'Framing', value: 'Chest up', needsPro: false },
      { label: 'EV', value: '+0.5', needsPro: false },
      { label: 'Stabilization', value: 'Tripod (stabilization off)', needsPro: false },
    ]);
    expect(r.ok).toHaveLength(2);
    expect(r.cantTell).toHaveLength(2);
    expect(r.ask?.id).toBe('can_move');
    expect(r.ask?.options.map((o) => o.en)).toEqual(['Yes, I can move', 'No, fixed spot']);

    const answered = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', {
      answers: [{ id: 'can_move', option: 0 }],
    });
    expect(answered.kind === 'ok' && answered.result.ask).toBeNull();
  });
});
