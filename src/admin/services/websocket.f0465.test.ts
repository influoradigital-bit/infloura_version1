/**
 * F-0465 (token-in-url), P0 — regression test.
 *
 * The admin WS bearer token must never travel in the connection URL (query
 * string): proxy access logs, server access logs, and browser history would
 * all capture it. `AdminSocketClient.connect()` must instead pass it as a
 * `WebSocket` subprotocol (`new WebSocket(url, [token])`), which the browser
 * sends as the `Sec-WebSocket-Protocol` handshake header, not the URL.
 *
 * No backend WS endpoint exists yet (see websocket.ts BACKEND STATUS note),
 * so this only pins the client-side contract — what URL/protocols the
 * browser `WebSocket` constructor is invoked with.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AdminSocketClient, AdminSocketStatus } from './websocket';

/** Minimal fake WebSocket that records how it was constructed and never opens. */
class RecordingWebSocket {
  static instances: RecordingWebSocket[] = [];
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  readyState = RecordingWebSocket.CONNECTING;
  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: unknown) => void) | null = null;
  onclose: ((ev: unknown) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;

  constructor(
    public readonly url: string,
    public readonly protocols?: string | string[],
  ) {
    RecordingWebSocket.instances.push(this);
  }

  close(): void {
    this.readyState = RecordingWebSocket.CLOSED;
  }
  send(): void {}
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  RecordingWebSocket.instances = [];
});

describe('AdminSocketClient.connect() — F-0465 token transport', () => {
  it('never puts the bearer token in the connection URL', () => {
    vi.stubGlobal('WebSocket', RecordingWebSocket as unknown as typeof WebSocket);
    vi.stubEnv('VITE_ADMIN_WS_ENABLED', 'true');

    const client = new AdminSocketClient({
      url: 'ws://localhost:8080/api/v1/admin/ws',
      getToken: () => 'super-secret-admin-token',
    });

    client.connect();

    expect(RecordingWebSocket.instances).toHaveLength(1);
    const socket = RecordingWebSocket.instances[0];

    // The whole point of F-0465: the raw token string must not appear in the URL.
    expect(socket.url).toBe('ws://localhost:8080/api/v1/admin/ws');
    expect(socket.url).not.toContain('super-secret-admin-token');
    expect(socket.url).not.toContain('token=');
  });

  it('carries the token as a WS subprotocol instead', () => {
    vi.stubGlobal('WebSocket', RecordingWebSocket as unknown as typeof WebSocket);
    vi.stubEnv('VITE_ADMIN_WS_ENABLED', 'true');

    const client = new AdminSocketClient({
      url: 'ws://localhost:8080/api/v1/admin/ws',
      getToken: () => 'super-secret-admin-token',
    });

    client.connect();

    const socket = RecordingWebSocket.instances[0];
    expect(socket.protocols).toEqual(['super-secret-admin-token']);
  });

  it('does not construct a socket at all when the token is missing', () => {
    vi.stubGlobal('WebSocket', RecordingWebSocket as unknown as typeof WebSocket);
    vi.stubEnv('VITE_ADMIN_WS_ENABLED', 'true');

    const client = new AdminSocketClient({
      url: 'ws://localhost:8080/api/v1/admin/ws',
      getToken: () => null,
    });

    client.connect();

    expect(RecordingWebSocket.instances).toHaveLength(0);
    expect(client.getStatus()).toBe(AdminSocketStatus.CLOSED);
  });
});
