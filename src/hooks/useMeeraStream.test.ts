/**
 * useMeeraStream — POST SSE transport contract tests (2026-07-17 seam fix).
 *
 * The Python edge is `@router.post("/chat")` (POST only). These tests pin the
 * transport the hook must speak: fetch POST with the stream token in the
 * Authorization header and the caller's JSON body, response parsed as an SSE
 * stream (event:/data: frames, `: ping` heartbeat comments, frames split
 * across network chunks), terminal `done`/`error` events, and JSON error
 * bodies on non-2xx. The old EventSource client (hard-wired to GET) could
 * never connect at all — a regression here would silently kill live
 * streaming again, so keep these green.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useMeeraStream, type MeeraStreamHandlers } from './useMeeraStream';

/** Builds a Response whose body streams the given chunks in order. */
function sseResponse(chunks: string[], init: ResponseInit = {}): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
    ...init,
  });
}

function frame(event: string, data: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('useMeeraStream (POST SSE client)', () => {
  it('POSTs to the stream URL with Authorization bearer token and JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([frame('done', { finish_reason: 'stop' })]));
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useMeeraStream());
    act(() => {
      result.current.open(
        'http://localhost:8000/chat',
        'stream-token-123',
        {},
        { workspace_id: 'ws_1', conversation_id: 'conv_1', turn_id: 'msg_1' },
      );
    });

    await waitFor(() => expect(result.current.status).toBe('done'));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:8000/chat');
    expect(init.method).toBe('POST'); // the whole point of the seam fix — never GET
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer stream-token-123');
    expect((init.headers as Record<string, string>).Accept).toBe('text/event-stream');
    expect(JSON.parse(init.body as string)).toEqual({
      workspace_id: 'ws_1',
      conversation_id: 'conv_1',
      turn_id: 'msg_1',
    });
  });

  it('dispatches token/thinking/tool_result/done events in order, ignoring heartbeats', async () => {
    const chunks = [
      frame('prompt_meta', { prompt_version: 'v7' }),
      frame('thinking', { step: 'Reading your brand profile', done: false }),
      ': ping\n\n', // heartbeat comment frame — must be silently skipped
      frame('token', { text: 'Hello' }),
      frame('token', { text: ' there' }),
      frame('tool_result', { name: 'show_creators', status: 'ok' }),
      frame('done', { finish_reason: 'stop' }),
    ];
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(chunks)));

    const seen: string[] = [];
    let text = '';
    const handlers: MeeraStreamHandlers = {
      onPromptMeta: (e) => seen.push(`meta:${e.prompt_version}`),
      onThinking: (e) => seen.push(`think:${e.step}`),
      onToken: (e) => {
        text += e.text;
        seen.push('token');
      },
      onToolResult: (e) => seen.push(`tool:${e.name}:${e.status}`),
      onDone: (e) => seen.push(`done:${e.finish_reason}`),
    };

    const { result } = renderHook(() => useMeeraStream());
    act(() => result.current.open('http://x/chat', 't', handlers));

    await waitFor(() => expect(result.current.status).toBe('done'));
    expect(seen).toEqual([
      'meta:v7',
      'think:Reading your brand profile',
      'token',
      'token',
      'tool:show_creators:ok',
      'done:stop',
    ]);
    expect(text).toBe('Hello there');
  });

  it('reassembles SSE frames split across network chunks', async () => {
    const whole = frame('token', { text: 'split-safe' }) + frame('done', { finish_reason: 'stop' });
    // Cut mid-"data:" line to prove buffering works
    const cut = whole.indexOf('split');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([whole.slice(0, cut), whole.slice(cut)])));

    let text = '';
    const { result } = renderHook(() => useMeeraStream());
    act(() =>
      result.current.open('http://x/chat', 't', {
        onToken: (e) => {
          text += e.text;
        },
      }),
    );

    await waitFor(() => expect(result.current.status).toBe('done'));
    expect(text).toBe('split-safe');
  });

  it('surfaces protocol error events (fallback:text) and stops reading', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(sseResponse([frame('error', { code: 'tool_loop_cap', fallback: 'text' })])),
    );

    const onError = vi.fn();
    const { result } = renderHook(() => useMeeraStream());
    act(() => result.current.open('http://x/chat', 't', { onError }));

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'tool_loop_cap', fallback: 'text' }));
    expect(result.current.lastError?.code).toBe('tool_loop_cap');
  });

  it('maps a non-2xx JSON body (chat.py auth/validation shape) onto onError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: { code: 'invalid_token', message: 'stream token expired' } }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );

    const onError = vi.fn();
    const { result } = renderHook(() => useMeeraStream());
    act(() => result.current.open('http://x/chat', 't', { onError }));

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'invalid_token', message: 'stream token expired' }),
    );
  });

  it('treats a server close without done/error as STREAM_INCOMPLETE', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([frame('token', { text: 'partial…' })])));

    const onError = vi.fn();
    const { result } = renderHook(() => useMeeraStream());
    act(() => result.current.open('http://x/chat', 't', { onError }));

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'STREAM_INCOMPLETE' }));
  });

  it('close() aborts the in-flight request without firing onError', async () => {
    // A stream that never ends on its own — only abort can finish it
    const neverEnding = new ReadableStream<Uint8Array>({ start() {} });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(neverEnding, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }),
      ),
    );

    const onError = vi.fn();
    const { result } = renderHook(() => useMeeraStream());
    act(() => result.current.open('http://x/chat', 't', { onError }));
    await waitFor(() => expect(result.current.status).toBe('streaming'));

    act(() => result.current.close());
    // Give any wrongly-scheduled error callbacks a tick to fire
    await new Promise((r) => setTimeout(r, 50));
    expect(onError).not.toHaveBeenCalled();
  });
});
