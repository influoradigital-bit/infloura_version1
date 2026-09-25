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

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => true };
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
      result: { fixes: ['Step right'], settings: [], ok: [], whatISee: null, steps: [], cantTell: [], ask: null },
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
        steps: [
          { kind: 'move_you', text: 'Turn about 30-45 degrees towards the window', note: 'Soft natural window light' },
          { kind: 'move_phone', text: 'Raise the phone to eye height', note: 'Eye level' },
          { kind: 'settings', text: 'Lock focus and exposure on your face', note: 'Talking Head (Window light)' },
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
