/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §9.2, F3/A49) — `sendTurn` sends `voiceReply`, and the
 * Idempotency-Key contract: a caller that mints ONE key per user message and passes it on every
 * call for that message gets the SAME header value every time; `sendTurn`'s own default (key
 * omitted) still mints a fresh one per call, exactly as it always has, so no existing call site
 * changes behaviour.
 *
 * Run: npx vitest run src/lib/meera-api.creator-credits.test.ts
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return { ...actual, isApiLive: () => true };
});

import { meeraApi } from './meera-api';

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify({ success: true, data: body }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function turnResponse(creditsRemaining: number) {
  return {
    messageId: 'msg_1',
    streamToken: 'tok',
    streamUrl: 'https://ai.example/stream',
    creditsRemaining,
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('meeraApi.sendTurn (A49 — voiceReply + Idempotency-Key reuse)', () => {
  it('sends voiceReply:true in the body when the caller opts into a voice reply', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(turnResponse(58)));
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.sendTurn('conv_1', 'hello', 'creator', { voiceReply: true });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string);
    expect(body).toMatchObject({ content: 'hello', voiceReply: true });
  });

  it('mints a fresh Idempotency-Key per call when none is supplied (unchanged default behaviour)', async () => {
    // `mockImplementation` (not `mockResolvedValue`) — a `Response` body can only be read once,
    // and this test calls `sendTurn` twice against the same mock, so each call needs its OWN
    // `Response` instance rather than replaying one whose `.json()` was already consumed.
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(turnResponse(58)));
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.sendTurn('conv_1', 'hello', 'creator');
    await meeraApi.sendTurn('conv_1', 'hello again', 'creator');

    const key1 = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    const key2 = (fetchMock.mock.calls[1][1] as RequestInit).headers as Record<string, string>;
    expect(key1['Idempotency-Key']).toBeTruthy();
    expect(key2['Idempotency-Key']).toBeTruthy();
    expect(key1['Idempotency-Key']).not.toBe(key2['Idempotency-Key']);
  });

  it('reuses the identical Idempotency-Key across a retry of the same user message', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(turnResponse(57)));
    vi.stubGlobal('fetch', fetchMock);

    const idempotencyKey = 'fixed-key-for-one-message';
    await meeraApi.sendTurn('conv_1', 'hello', 'creator', { idempotencyKey });
    // A caller retrying the SAME logical turn passes the SAME key back.
    await meeraApi.sendTurn('conv_1', 'hello', 'creator', { idempotencyKey });

    const headers1 = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    const headers2 = (fetchMock.mock.calls[1][1] as RequestInit).headers as Record<string, string>;
    expect(headers1['Idempotency-Key']).toBe(idempotencyKey);
    expect(headers2['Idempotency-Key']).toBe(idempotencyKey);
  });

  it('a 409 on retry surfaces as an ApiError instead of silently resending with a new key', async () => {
    const conflict = new Response(JSON.stringify({ success: false, error: { code: 'CONFLICT', message: 'already processed' } }), {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    });
    const fetchMock = vi.fn().mockResolvedValue(conflict);
    vi.stubGlobal('fetch', fetchMock);

    const idempotencyKey = 'retry-key';
    await expect(meeraApi.sendTurn('conv_1', 'hello', 'creator', { idempotencyKey })).rejects.toMatchObject({
      code: 'CONFLICT',
    });
    // The caller's own recovery path (not this method) decides whether to resend — this method
    // itself never auto-retries with a fresh key on a 409.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('meeraApi.speak (turnId, appended after role)', () => {
  it('sends turnId in the body when supplied as the 4th argument', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Blob(['audio'], { type: 'audio/wav' }), { status: 200, headers: { 'Content-Type': 'audio/wav' } }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.speak('hello', undefined, 'creator', 'turn_123');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string);
    expect(body).toEqual({ text: 'hello', turnId: 'turn_123' });
  });

  it('a pre-existing 3-argument call (no turnId) is unaffected', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Blob(['audio'], { type: 'audio/wav' }), { status: 200, headers: { 'Content-Type': 'audio/wav' } }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.speak('hello', undefined, 'creator');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ text: 'hello' });
  });
});
