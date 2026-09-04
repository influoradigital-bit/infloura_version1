/**
 * meera-api.ts — role-based URL routing (T-MEERA-CREATOR-PHASE-A, gate fix round 1 item 1)
 *
 * Priya's audit (SHARED_CONTEXT.md Q1): `role` used to pick only the auth token, not the URL —
 * every call hit the brand-gated `/meera/...` path regardless of role, so a CREATOR session
 * 403'd before ever reaching `CreatorMeeraController` (`/creator/meera/...`). This pins the fix:
 * `role: 'creator'` must route through `/creator/meera/...`, `role: 'brand'` (and the default)
 * must keep hitting `/meera/...` exactly as before, so no pre-existing brand call site regresses.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => true };
});

import { meeraApi } from '@/lib/meera-api';

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify({ success: true, data: body }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('meeraApi role -> URL routing', () => {
  it('startSession routes a creator turn to /creator/meera/sessions', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ conversationId: 'c1', status: 'ACTIVE' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.startSession('creator');

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(new URL(url).pathname.endsWith('/creator/meera/sessions')).toBe(true);
  });

  it('startSession keeps a brand (default) turn on /meera/sessions', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ conversationId: 'c1', status: 'ACTIVE' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.startSession();

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(new URL(url).pathname.endsWith('/meera/sessions')).toBe(true);
    expect(url).not.toContain('/creator/meera');
  });

  it('sendTurn routes a creator turn to /creator/meera/sessions/{id}/messages', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ messageId: 'm1', assistantMessageId: 'm2', streamToken: 't', streamUrl: 'u' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.sendTurn('conv1', 'hello', 'creator');

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/creator/meera/sessions/conv1/messages');
  });

  it('getHistory routes a creator turn to /creator/meera/sessions/{id}/messages', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.getHistory('conv1', 'creator');

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/creator/meera/sessions/conv1/messages');
  });

  it('getMessagesAfter routes a creator turn to /creator/meera/sessions/{id}/messages', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.getMessagesAfter('conv1', 'm1', 'creator');

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/creator/meera/sessions/conv1/messages');
    expect(url).toContain('after=m1');
  });

  // Gate review fix round 2 (Priya's frontend gate, item 2): CreatorMeeraController now exposes
  // POST /creator/meera/voice/{speak,transcribe} (Vikram), so speak()/transcribe() no longer
  // short-circuit to null for role 'creator' — they route through basePath(role) exactly like
  // every other call in this file. Full route-correctness coverage (both roles, both methods)
  // lives in meera-api.voice-routes.test.ts; these two just keep this file's "role -> URL"
  // narrative complete now that voice is no longer the one exception to it.
  it('speak now routes a creator turn to /creator/meera/voice/speak instead of resolving null', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Blob(['audio'], { type: 'audio/wav' }), {
        status: 200,
        headers: { 'Content-Type': 'audio/wav' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await meeraApi.speak('hello', undefined, 'creator');

    expect(result).not.toBeNull();
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/creator/meera/voice/speak');
  });

  it('transcribe now routes a creator turn to /creator/meera/voice/transcribe instead of resolving null', async () => {
    // transcribe() parses a flat JSON body ({ raw_transcript, cleaned_text, ... }), NOT the
    // { success, data } envelope `jsonResponse()` above builds for the other endpoints in this
    // file — see meera-api.ts's transcribe() doc comment.
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ raw_transcript: 'hi', cleaned_text: 'hi' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await meeraApi.transcribe(new Blob(['x']), 'creator');

    expect(result).not.toBeNull();
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/creator/meera/voice/transcribe');
  });
});
