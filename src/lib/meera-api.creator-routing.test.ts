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

  it('speak resolves to null for a creator turn without ever hitting the brand-gated route', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const result = await meeraApi.speak('hello', undefined, 'creator');

    expect(result).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('transcribe resolves to null for a creator turn without ever hitting the brand-gated route', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const result = await meeraApi.transcribe(new Blob(['x']), 'creator');

    expect(result).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
