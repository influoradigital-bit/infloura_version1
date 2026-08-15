/**
 * useMeeraStream - Real SSE streaming client for Meera AI
 * ----------------------------------------------------------------------------
 * P10: Connects directly to Python SSE endpoint (not through Spring).
 *
 * Transport (fixed 2026-07-17): the Python edge is `@router.post("/chat")` —
 * POST ONLY (influora-ai/app/routes/chat.py). The original EventSource client
 * could never connect: EventSource is hard-wired to GET, so every live turn
 * failed straight into the non-streaming fallback. This client speaks the
 * actual contract instead:
 *   - `fetch()` POST to {streamUrl} with `Accept: text/event-stream`
 *   - `Authorization: Bearer {streamToken}` — chat.py's `_bearer_from` reads
 *     the header first; the scoped stream token (aud=meera-stream, <=60s,
 *     workspace+conversation bound) authenticates the stream
 *   - JSON body from the caller (workspace_id / conversation_id / turn_id /
 *     onbehalf_jwt / conversation) — chat.py requires workspace_id and binds
 *     the token's conversation_id against the body's
 *   - the response body is parsed as an SSE stream via ReadableStream
 *
 * Protocol (04 section 4):
 *   - Events: token, thinking, tool_start, tool_result, prompt_meta, done, error
 *   - Heartbeat: `: ping` comment every ~15s (keep-alive)
 *
 * Recovery (02 section 4.5):
 *   - On stream error, DO NOT re-POST (would double-spend credits)
 *   - Instead, fall back to GET /meera/sessions/{id}/messages?after={messageId}
 *   - fetch() never auto-reconnects (unlike EventSource), which is exactly
 *     right here: the stream token is single-use, a reconnect would 401
 *
 * Cancellation:
 *   - AbortController cancels on unmount, new send, or user stop
 *   - Python cancels in-flight provider calls on client disconnect
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  MeeraTokenEvent,
  MeeraThinkingEvent,
  MeeraToolStartEvent,
  MeeraToolResultEvent,
  MeeraDoneEvent,
  MeeraErrorEvent,
} from '@/lib/meera-api';

export type StreamStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'error';

export interface MeeraStreamHandlers {
  onToken?: (event: MeeraTokenEvent) => void;
  onThinking?: (event: MeeraThinkingEvent) => void;
  onToolStart?: (event: MeeraToolStartEvent) => void;
  onToolResult?: (event: MeeraToolResultEvent) => void;
  onPromptMeta?: (event: { prompt_version: string }) => void;
  onDone?: (event: MeeraDoneEvent) => void;
  onError?: (event: MeeraErrorEvent) => void;
  /** Called when heartbeat silence exceeds threshold (connection likely dead) */
  onHeartbeatTimeout?: () => void;
}

/** Heartbeat silence threshold in ms - if no data for this long, treat as stale */
const HEARTBEAT_TIMEOUT_MS = 30000;

/**
 * Parse SSE event data safely. Returns null on malformed JSON.
 */
function parseEventData<T>(data: string): T | null {
  try {
    return JSON.parse(data) as T;
  } catch {
    console.warn('[useMeeraStream] Malformed event data:', data);
    return null;
  }
}

/**
 * One parsed SSE frame: the `event:` name (default "message") and the joined
 * `data:` payload. Comment-only frames (heartbeats) yield a null.
 */
interface SseFrame {
  event: string;
  data: string;
}

/**
 * Parse a single raw SSE frame (the text between two blank lines) per the
 * SSE spec: `:` lines are comments, multiple `data:` lines join with `\n`,
 * one optional leading space after the field colon is stripped.
 */
function parseSseFrame(rawFrame: string): SseFrame | null {
  let event = 'message';
  const dataLines: string[] = [];

  for (const rawLine of rawFrame.split(/\r?\n/)) {
    if (rawLine === '' || rawLine.startsWith(':')) continue; // comment (heartbeat) / blank
    const colon = rawLine.indexOf(':');
    const field = colon === -1 ? rawLine : rawLine.slice(0, colon);
    let value = colon === -1 ? '' : rawLine.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') event = value;
    else if (field === 'data') dataLines.push(value);
    // id/retry are legal SSE fields but unused by the Meera protocol
  }

  if (dataLines.length === 0) return null; // heartbeat / comment-only frame
  return { event, data: dataLines.join('\n') };
}

export interface UseMeeraStreamResult {
  /** Current stream status */
  status: StreamStatus;
  /**
   * POST to the stream URL and consume the SSE response. `body` is the JSON
   * request body chat.py expects (workspace_id, conversation_id, turn_id,
   * onbehalf_jwt, conversation) — the stream token itself travels in the
   * Authorization header.
   */
  open: (
    streamUrl: string,
    streamToken: string,
    handlers: MeeraStreamHandlers,
    body?: Record<string, unknown>,
  ) => void;
  /** Close the current stream (cancels in-flight request) */
  close: () => void;
  /** Last error event received, if any */
  lastError: MeeraErrorEvent | null;
}

export function useMeeraStream(): UseMeeraStreamResult {
  const [status, setStatus] = useState<StreamStatus>('idle');
  const [lastError, setLastError] = useState<MeeraErrorEvent | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const heartbeatTimerRef = useRef<number | null>(null);
  const handlersRef = useRef<MeeraStreamHandlers | null>(null);

  /**
   * Reset the heartbeat timer. Called on every frame/comment received.
   */
  const resetHeartbeat = useCallback(() => {
    if (heartbeatTimerRef.current !== null) {
      window.clearTimeout(heartbeatTimerRef.current);
    }
    heartbeatTimerRef.current = window.setTimeout(() => {
      // Heartbeat timeout - connection is likely dead
      console.warn('[useMeeraStream] Heartbeat timeout - no data for 30s');
      handlersRef.current?.onHeartbeatTimeout?.();
      // Don't auto-close here - let the caller decide recovery strategy
    }, HEARTBEAT_TIMEOUT_MS);
  }, []);

  /**
   * Abort the in-flight request and clean up.
   */
  const close = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    if (heartbeatTimerRef.current !== null) {
      window.clearTimeout(heartbeatTimerRef.current);
      heartbeatTimerRef.current = null;
    }
    handlersRef.current = null;
  }, []);

  /**
   * Dispatch one parsed frame to the caller's handlers. Returns true when the
   * stream is finished (done event) and reading should stop.
   */
  const dispatchFrame = useCallback(
    (frame: SseFrame, handlers: MeeraStreamHandlers): boolean => {
      switch (frame.event) {
        case 'token': {
          const data = parseEventData<MeeraTokenEvent>(frame.data);
          if (data) handlers.onToken?.(data);
          return false;
        }
        case 'thinking': {
          const data = parseEventData<MeeraThinkingEvent>(frame.data);
          if (data) handlers.onThinking?.(data);
          return false;
        }
        case 'tool_start': {
          const data = parseEventData<MeeraToolStartEvent>(frame.data);
          if (data) handlers.onToolStart?.(data);
          return false;
        }
        case 'tool_result': {
          const data = parseEventData<MeeraToolResultEvent>(frame.data);
          if (data) handlers.onToolResult?.(data);
          return false;
        }
        case 'prompt_meta': {
          const data = parseEventData<{ prompt_version: string }>(frame.data);
          if (data) handlers.onPromptMeta?.(data);
          return false;
        }
        case 'done': {
          const data = parseEventData<MeeraDoneEvent>(frame.data);
          if (data) {
            setStatus('done');
            handlers.onDone?.(data);
          }
          return true;
        }
        case 'error': {
          // Protocol-level error event with data payload (has fallback:text)
          const data = parseEventData<MeeraErrorEvent>(frame.data);
          if (data) {
            setLastError(data);
            setStatus('error');
            handlers.onError?.(data);
          }
          return true;
        }
        default:
          // Routine frame, not an anomaly — dev-only so prod consoles stay clean for
          // E2E triage and frame payloads never reach end-user consoles (F-0211).
          if (import.meta.env.DEV) console.warn('[useMeeraStream] Generic message:', frame.data);
          return false;
      }
    },
    [],
  );

  /**
   * POST to the Python stream endpoint and consume the SSE response body.
   */
  const open = useCallback(
    (
      streamUrl: string,
      streamToken: string,
      handlers: MeeraStreamHandlers,
      body: Record<string, unknown> = {},
    ) => {
      // Close any existing connection first
      close();

      handlersRef.current = handlers;
      setStatus('connecting');
      setLastError(null);

      const controller = new AbortController();
      abortRef.current = controller;

      // Start heartbeat monitoring
      resetHeartbeat();

      const fail = (error: MeeraErrorEvent) => {
        if (controller.signal.aborted) return;
        setStatus('error');
        setLastError(error);
        handlers.onError?.(error);
        close();
      };

      (async () => {
        let response: Response;
        try {
          response = await fetch(streamUrl, {
            method: 'POST',
            headers: {
              Authorization: `Bearer ${streamToken}`,
              'Content-Type': 'application/json',
              Accept: 'text/event-stream',
            },
            body: JSON.stringify(body),
            signal: controller.signal,
          });
        } catch (err) {
          if (controller.signal.aborted) return;
          console.error('[useMeeraStream] Connection error:', err);
          fail({
            code: 'CONNECTION_ERROR',
            fallback: 'text',
            message: 'Stream connection failed',
          });
          return;
        }

        if (!response.ok) {
          // Auth/validation failures come back as JSON, not SSE
          let code = 'CONNECTION_ERROR';
          let message = `Stream rejected (HTTP ${response.status})`;
          try {
            const payload = (await response.json()) as {
              error?: { code?: string; message?: string };
            };
            if (payload?.error?.code) code = payload.error.code;
            if (payload?.error?.message) message = payload.error.message;
          } catch {
            // non-JSON error body — keep the HTTP-status message
          }
          fail({ code, fallback: 'text', message });
          return;
        }

        if (!response.body) {
          fail({
            code: 'CONNECTION_ERROR',
            fallback: 'text',
            message: 'Stream response has no body',
          });
          return;
        }

        setStatus('streaming');
        resetHeartbeat();

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let finished = false;

        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;

            resetHeartbeat();
            buffer += decoder.decode(value, { stream: true });

            // Frames are separated by a blank line (\n\n or \r\n\r\n)
            for (;;) {
              const sep = buffer.search(/\r?\n\r?\n/);
              if (sep === -1) break;
              const rawFrame = buffer.slice(0, sep);
              buffer = buffer.slice(sep).replace(/^\r?\n\r?\n/, '');

              const frame = parseSseFrame(rawFrame);
              if (!frame) continue; // heartbeat comment
              finished = dispatchFrame(frame, handlers);
              if (finished) break;
            }
            if (finished) break;
          }
        } catch (err) {
          if (!controller.signal.aborted) {
            console.error('[useMeeraStream] Stream read error:', err);
            fail({
              code: 'CONNECTION_ERROR',
              fallback: 'text',
              message: 'Stream interrupted',
            });
          }
          return;
        }

        if (!finished && !controller.signal.aborted) {
          // Server closed the connection without a done/error event
          fail({
            code: 'STREAM_INCOMPLETE',
            fallback: 'text',
            message: 'Stream ended before completion',
          });
          return;
        }

        close();
      })();
    },
    [close, dispatchFrame, resetHeartbeat],
  );

  // Abort any in-flight stream on unmount to prevent connection leaks
  // and callbacks firing on unmounted components
  useEffect(() => () => close(), [close]);

  return {
    status,
    open,
    close,
    lastError,
  };
}

export default useMeeraStream;
