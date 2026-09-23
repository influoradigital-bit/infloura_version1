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

import { meeraApi } from './meera-api';

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
    expect(result).toEqual({ kind: 'ok', result: { fixes: ['Step right'], settings: [], ok: [] } });
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
