/**
 * useMeeraStream - Real SSE streaming client for Meera AI
 * ----------------------------------------------------------------------------
 * P10: Connects directly to Python SSE endpoint (not through Spring).
 *
 * Protocol (04 section 4):
 *   - Browser opens EventSource to {streamUrl}?token={streamToken}
 *   - Python validates the scoped stream token before emitting any bytes
 *   - Events: token, thinking, tool_start, tool_result, prompt_meta, done, error
 *   - Heartbeat: `: ping` comment every ~15s (keep-alive)
 *
 * Recovery (02 section 4.5):
 *   - On stream error, DO NOT re-POST (would double-spend credits)
 *   - Instead, fall back to GET /meera/sessions/{id}/messages?after={messageId}
 *
 * Cancellation:
 *   - Close EventSource on unmount, new send, or user stop
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
  MeeraSSEEventType,
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

export interface UseMeeraStreamResult {
  /** Current stream status */
  status: StreamStatus;
  /** Open an SSE connection to the stream URL with the given token */
  open: (streamUrl: string, streamToken: string, handlers: MeeraStreamHandlers) => void;
  /** Close the current stream (cancels in-flight request) */
  close: () => void;
  /** Last error event received, if any */
  lastError: MeeraErrorEvent | null;
}

export function useMeeraStream(): UseMeeraStreamResult {
  const [status, setStatus] = useState<StreamStatus>('idle');
  const [lastError, setLastError] = useState<MeeraErrorEvent | null>(null);

  const eventSourceRef = useRef<EventSource | null>(null);
  const heartbeatTimerRef = useRef<number | null>(null);
  const handlersRef = useRef<MeeraStreamHandlers | null>(null);

  /**
   * Reset the heartbeat timer. Called on every message/comment received.
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
   * Close and cleanup the EventSource connection.
   */
  const close = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    if (heartbeatTimerRef.current !== null) {
      window.clearTimeout(heartbeatTimerRef.current);
      heartbeatTimerRef.current = null;
    }
    handlersRef.current = null;
  }, []);

  /**
   * Open an SSE connection to the Python stream endpoint.
   */
  const open = useCallback(
    (streamUrl: string, streamToken: string, handlers: MeeraStreamHandlers) => {
      // Close any existing connection first
      close();

      handlersRef.current = handlers;
      setStatus('connecting');
      setLastError(null);

      // Build the full URL with the stream token as query param
      // The token is scoped (aud=meera-stream, <=60s, workspace+conversation bound)
      const url = `${streamUrl}?token=${encodeURIComponent(streamToken)}`;

      const es = new EventSource(url);
      eventSourceRef.current = es;

      // Start heartbeat monitoring
      resetHeartbeat();

      es.onopen = () => {
        setStatus('streaming');
        resetHeartbeat();
      };

      // Generic message handler for SSE comments (heartbeat `: ping`)
      es.onmessage = (event) => {
        resetHeartbeat();
        // Default message type - we use named event handlers below
        // This catches any unlabeled messages
        console.debug('[useMeeraStream] Generic message:', event.data);
      };

      // Named event handlers for each event type (04 section 4)
      es.addEventListener('token', (event: MessageEvent) => {
        resetHeartbeat();
        const data = parseEventData<MeeraTokenEvent>(event.data);
        if (data) handlers.onToken?.(data);
      });

      es.addEventListener('thinking', (event: MessageEvent) => {
        resetHeartbeat();
        const data = parseEventData<MeeraThinkingEvent>(event.data);
        if (data) handlers.onThinking?.(data);
      });

      es.addEventListener('tool_start', (event: MessageEvent) => {
        resetHeartbeat();
        const data = parseEventData<MeeraToolStartEvent>(event.data);
        if (data) handlers.onToolStart?.(data);
      });

      es.addEventListener('tool_result', (event: MessageEvent) => {
        resetHeartbeat();
        const data = parseEventData<MeeraToolResultEvent>(event.data);
        if (data) handlers.onToolResult?.(data);
      });

      es.addEventListener('prompt_meta', (event: MessageEvent) => {
        resetHeartbeat();
        const data = parseEventData<{ prompt_version: string }>(event.data);
        if (data) handlers.onPromptMeta?.(data);
      });

      es.addEventListener('done', (event: MessageEvent) => {
        resetHeartbeat();
        const data = parseEventData<MeeraDoneEvent>(event.data);
        if (data) {
          setStatus('done');
          handlers.onDone?.(data);
          close();
        }
      });

      es.addEventListener('error', (event: MessageEvent) => {
        resetHeartbeat();
        // SSE error event with data payload (not the onerror connection error)
        const data = parseEventData<MeeraErrorEvent>(event.data);
        if (data) {
          setLastError(data);
          setStatus('error');
          handlers.onError?.(data);
          // Don't auto-close - error events have fallback:text
        }
      });

      // Connection error handler
      es.onerror = (event) => {
        console.error('[useMeeraStream] Connection error:', event);
        // EventSource will auto-reconnect, but we don't want that
        // (stream token is single-use, reconnect would fail)
        setStatus('error');
        setLastError({
          code: 'CONNECTION_ERROR',
          fallback: 'text',
          message: 'Stream connection failed',
        });
        close();
      };
    },
    [close, resetHeartbeat]
  );

  // Cleanup EventSource on unmount to prevent connection leaks
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
