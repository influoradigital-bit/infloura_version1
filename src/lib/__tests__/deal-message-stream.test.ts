/**
 * CR-31 — the deal-message SSE transport reconnects, and a clean close is a disconnect.
 *
 * The defect these pin down: `messages.stream` replaced `EventSource` with a raw fetch (it
 * had to — `EventSource` cannot send an `Authorization` header) and never reimplemented the
 * reconnect `EventSource` gave for free. The read loop then treated `done` as a normal
 * return, so when the server closed the stream cleanly — a proxy idle-timeout, an API
 * restart — the function returned having called NOTHING. Not `onError`, not a log. The deal
 * room went permanently deaf with no trace, which silently undid CR-08's whole purpose.
 *
 * The first test is the tripwire for that exact path. It fails if `done` ever goes back to
 * being a quiet `return`.
 *
 * Run: npx vitest run src/lib/__tests__/deal-message-stream.test.ts
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { messages as MessagesApi } from '@/lib/api';

const MESSAGE_JSON = JSON.stringify({
  id: 'm1',
  dealId: 'd1',
  kind: 'text',
  senderId: 'u1',
  senderType: 'brand',
  content: 'hello',
  createdAt: '2026-07-28T00:00:00.000Z',
  readBy: [],
});

const ONE_FRAME = `event: deal-message\ndata: ${MESSAGE_JSON}\n\n`;

/**
 * A stream that delivers `body` and then closes CLEANLY — i.e. `reader.read()` resolves
 * `{done: true}` with no error. This is the case the original code mistook for success.
 */
function cleanlyClosingSseResponse(body: string): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      if (body) controller.enqueue(new TextEncoder().encode(body));
      controller.close();
    },
  });
  return new Response(stream, { status: 200 });
}

/** Lets pending fetch/read microtasks settle without moving the backoff clock. */
async function settle() {
  for (let i = 0; i < 8; i++) await vi.advanceTimersByTimeAsync(0);
}

describe('deal message stream reconnect (CR-31)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let messages: typeof MessagesApi;

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    vi.useFakeTimers();
    // Backoff jitter is `ceiling/2 + random * ceiling/2`. Pinning random to 0 makes the
    // first retry land at exactly 500ms so the assertions can be about behaviour, not luck.
    vi.spyOn(Math, 'random').mockReturnValue(0);
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    ({ messages } = await import('@/lib/api'));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('treats a clean server close as a disconnect: reconnects, and tells the caller to refetch', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(cleanlyClosingSseResponse(ONE_FRAME)));
    const onMessage = vi.fn();
    const onReconnect = vi.fn();
    const onStatusChange = vi.fn();

    const handle = messages.stream('creator', 'd1', { onMessage, onReconnect, onStatusChange });

    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onMessage).toHaveBeenCalledTimes(1);
    // The connection ended. Before CR-31 nothing at all happened here and the room was done.
    expect(onStatusChange).toHaveBeenCalledWith('reconnecting');
    // Not yet — this was the FIRST connection, so there is no gap to close.
    expect(onReconnect).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(500);
    await settle();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    // The transport has no Last-Event-ID replay, so reconnecting alone would resume future
    // frames and keep the hole. This callback is what makes the caller re-read.
    expect(onReconnect).toHaveBeenCalledTimes(1);

    handle.close();
  });

  it('backs off exponentially instead of hot-looping a server that keeps closing', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(cleanlyClosingSseResponse('')));
    const handle = messages.stream('creator', 'd1', { onMessage: vi.fn() });

    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Attempt 1 -> ceiling 1000, floor 500.
    await vi.advanceTimersByTimeAsync(499);
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // Attempt 2 -> ceiling 2000, floor 1000. Each connection here closes immediately, so it
    // never reaches STREAM_STABLE_MS and the ladder must NOT reset.
    await vi.advanceTimersByTimeAsync(999);
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(3);

    handle.close();
  });

  it('gives up for good on a 403 — retrying a verdict would hammer the API forever', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 403 }));
    const onStatusChange = vi.fn();

    const handle = messages.stream('creator', 'd1', { onMessage: vi.fn(), onStatusChange });

    await settle();
    expect(onStatusChange).toHaveBeenCalledWith('closed');
    expect(onStatusChange).not.toHaveBeenCalledWith('reconnecting');

    await vi.advanceTimersByTimeAsync(120_000);
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    handle.close();
  });

  it('retries a 502, which is a blip rather than a verdict', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 502 }));
    const handle = messages.stream('creator', 'd1', { onMessage: vi.fn() });

    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(500);
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(2);

    handle.close();
  });

  it('close() cancels a scheduled reconnect, so a switched-away deal stops retrying', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(cleanlyClosingSseResponse('')));
    const handle = messages.stream('creator', 'd1', { onMessage: vi.fn() });

    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    handle.close();

    await vi.advanceTimersByTimeAsync(120_000);
    await settle();
    // Still 1. A leaked retry timer would keep a closed room's stream reopening in the
    // background for as long as the tab lives.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
