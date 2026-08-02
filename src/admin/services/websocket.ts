/**
 * INFLUORA ADMIN PANEL — Real-time WebSocket client
 * Owner: Priya (CTO)
 * Reference: docs/ADMIN-PANEL-SPEC.md, src/admin/TASK_ASSIGNMENTS.md (P1 — WebSocket config)
 *
 * Pushes live updates into the admin panel: CEO-pulse KPI ticks, new support
 * tickets (Vikram's AdminSupportController), new content-moderation flags, and
 * new items entering the approval queue. Components subscribe via the
 * `useAdminSocket` hook (src/admin/hooks/useAdminSocket.ts) — they should not
 * touch this client directly except through `getAdminSocket()`.
 *
 * DESIGN NOTES
 * - Uses the native browser `WebSocket`. Deliberately NO socket.io / STOMP /
 *   SockJS dependency: a grep of the app source found zero existing realtime
 *   infra, and adding a client lib would need dependency sign-off (see
 *   wiki/tech/approved-deps.md). Native WS keeps the bundle lean and matches
 *   the SSE-style edge already used by src/lib/meera-api.ts.
 * - The browser WebSocket API cannot set request headers, so the bearer token
 *   is passed as a `token` query param (same `admin_token` used by
 *   api-contracts.ts). The server MUST validate it on handshake and MAY reject
 *   with a 4401 close code; this client treats 4401/4403 as terminal (no
 *   reconnect) so an expired session doesn't hammer the endpoint.
 * - Reconnect uses exponential backoff with full jitter, capped both in delay
 *   and in attempt count (see `DEFAULTS.maxReconnectAttempts`), and is
 *   skipped on intentional close (code 1000) and auth-failure close codes.
 * - A lightweight app-level ping/pong heartbeat detects half-open sockets that
 *   never fire `onclose` (common behind proxies/load balancers).
 *
 * BACKEND STATUS (2026-07-15, Priya/B1 phase 1): there is currently NO
 * backend WS endpoint — no `@EnableWebSocket`/STOMP/SockJS in influora-api.
 * `connect()` is gated behind `VITE_ADMIN_WS_ENABLED` (default OFF/unset) so
 * this client does not attempt to open a socket at all until a backend
 * endpoint exists (Phase 2, Vikram). The admin pulse panel does not depend on
 * this client — it reads live data via REST polling (`usePulseData`).
 *
 * SECURITY: this is a UX transport only. Every payload it delivers is
 * advisory — the server remains the source of truth and every admin action is
 * still re-authorized through the REST controllers in api-contracts.ts.
 */

import type {
  CeoPulseData,
  SupportTicket,
  ContentFlag,
  ApprovalWorkflow,
} from '../types/admin.types';

// ============================================
// EVENT / MESSAGE CONTRACT
// ============================================

/**
 * Server -> client event types. String literals are namespaced `domain.event`
 * so they read clearly in logs and match the topics the server publishes.
 */
export enum AdminSocketEvent {
  /** Live CEO-pulse KPI snapshot; replaces the dashboard's polled figures. */
  DASHBOARD_PULSE = 'dashboard.pulse.updated',
  /** A brand/creator opened a new support ticket. */
  SUPPORT_TICKET_CREATED = 'support.ticket.created',
  /** An existing ticket changed (status / assignment / new reply). */
  SUPPORT_TICKET_UPDATED = 'support.ticket.updated',
  /** New content flag raised (AI/user/admin) needing moderation. */
  MODERATION_FLAG_CREATED = 'moderation.flag.created',
  /** A new item entered an approval queue (KYC / application / dispute…). */
  APPROVAL_QUEUED = 'approval.queued',
  /** Server acknowledges a successful (re)subscribe. Not domain data. */
  SUBSCRIBED = 'system.subscribed',
}

/**
 * Maps each event to its payload type. Keeps `on(...)` handlers fully typed —
 * subscribing to SUPPORT_TICKET_CREATED yields a `SupportTicket`, etc.
 */
export interface AdminSocketEventMap {
  [AdminSocketEvent.DASHBOARD_PULSE]: CeoPulseData;
  [AdminSocketEvent.SUPPORT_TICKET_CREATED]: SupportTicket;
  [AdminSocketEvent.SUPPORT_TICKET_UPDATED]: SupportTicket;
  [AdminSocketEvent.MODERATION_FLAG_CREATED]: ContentFlag;
  [AdminSocketEvent.APPROVAL_QUEUED]: ApprovalWorkflow;
  [AdminSocketEvent.SUBSCRIBED]: { topics: AdminSocketEvent[] };
}

/** Envelope every server message arrives in. */
export interface AdminSocketMessage<E extends AdminSocketEvent = AdminSocketEvent> {
  /** Discriminant — which event this is. */
  type: E;
  /** Strongly-typed body for that event. */
  payload: AdminSocketEventMap[E];
  /** Server-assigned message id (dedupe / audit correlation). */
  id: string;
  /** ISO-8601 server emit time. */
  timestamp: string;
}

/** Client -> server control frames. */
type OutboundFrame =
  | { action: 'subscribe'; topics: AdminSocketEvent[] }
  | { action: 'unsubscribe'; topics: AdminSocketEvent[] }
  | { action: 'ping'; ts: number };

/** Connection lifecycle, surfaced to the hook for status UI. */
export enum AdminSocketStatus {
  IDLE = 'IDLE',
  CONNECTING = 'CONNECTING',
  OPEN = 'OPEN',
  RECONNECTING = 'RECONNECTING',
  CLOSED = 'CLOSED',
}

type EventHandler<E extends AdminSocketEvent> = (payload: AdminSocketEventMap[E]) => void;
type StatusHandler = (status: AdminSocketStatus) => void;

// ============================================
// CONFIG
// ============================================

export interface AdminSocketConfig {
  /** Fully-resolved ws(s):// URL. Defaults to `resolveAdminSocketUrl()`. */
  url?: string;
  /** Reads the bearer token to authenticate the handshake. */
  getToken?: () => string | null;
  /** First backoff delay, ms. */
  baseReconnectDelayMs?: number;
  /** Backoff ceiling, ms. */
  maxReconnectDelayMs?: number;
  /** Give up after this many consecutive failures (0 = never). */
  maxReconnectAttempts?: number;
  /** App-level ping interval, ms. 0 disables the heartbeat. */
  heartbeatIntervalMs?: number;
  /** How long to wait for a pong before treating the socket as dead, ms. */
  heartbeatTimeoutMs?: number;
}

const DEFAULTS = {
  baseReconnectDelayMs: 1_000,
  maxReconnectDelayMs: 30_000,
  // Capped, not infinite: no backend WS endpoint exists yet (B1 phase 1), so
  // an enabled-but-unreachable client must not retry-storm forever.
  maxReconnectAttempts: 5,
  heartbeatIntervalMs: 25_000,
  heartbeatTimeoutMs: 10_000,
} as const;

/** Close codes the client uses / recognises. 4xxx range is app-defined. */
const CLOSE = {
  NORMAL: 1000,
  AUTH_FAILED: 4401,
  FORBIDDEN: 4403,
} as const;

const isDev = (import.meta as any).env?.DEV === true;

function log(...args: unknown[]): void {
  if (isDev) console.info('[admin-ws]', ...args);
}

/**
 * Feature flag: no backend WS endpoint exists yet (see BACKEND STATUS note
 * above), so the client defaults to OFF and must be explicitly opted in via
 * `VITE_ADMIN_WS_ENABLED=true`. This is intentionally strict equality — unset,
 * empty, or anything other than the literal string `"true"` stays disabled.
 */
export function isAdminWsEnabled(): boolean {
  const env = (import.meta as any).env ?? {};
  return env.VITE_ADMIN_WS_ENABLED === 'true';
}

/**
 * Derives the admin WebSocket URL. Priority:
 *  1. `VITE_ADMIN_WS_URL` explicit override.
 *  2. Swap the scheme of `VITE_API_BASE_URL` (http->ws, https->wss) and append
 *     `/admin/ws`. Matches the backend context-path `/api/v1` + `/admin/**`
 *     mount used by api-contracts.ts, resolving to `/api/v1/admin/ws`.
 *  3. Local dev fallback.
 */
export function resolveAdminSocketUrl(): string {
  const env = (import.meta as any).env ?? {};
  const explicit: string | undefined = env.VITE_ADMIN_WS_URL;
  if (explicit) return explicit;

  const apiBase: string = env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';
  try {
    const u = new URL(apiBase);
    u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
    // Ensure exactly one `/admin/ws` suffix on the context-path.
    u.pathname = `${u.pathname.replace(/\/+$/, '')}/admin/ws`;
    return u.toString();
  } catch {
    return 'ws://localhost:8080/api/v1/admin/ws';
  }
}

// ============================================
// CLIENT
// ============================================

/**
 * Reconnecting, typed admin WebSocket client. Prefer the module singleton via
 * `getAdminSocket()` so every hook shares one connection; construct directly
 * only in tests.
 */
export class AdminSocketClient {
  private ws: WebSocket | null = null;
  private status: AdminSocketStatus = AdminSocketStatus.IDLE;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private pongTimer: ReturnType<typeof setTimeout> | null = null;
  /** Set when the caller explicitly disconnected — suppresses reconnect. */
  private intentionalClose = false;

  private readonly cfg: Required<Omit<AdminSocketConfig, 'url' | 'getToken'>> & {
    url: string;
    getToken: () => string | null;
  };

  /** Per-event handler sets. */
  private readonly handlers = new Map<AdminSocketEvent, Set<EventHandler<AdminSocketEvent>>>();
  /** Status-change listeners. */
  private readonly statusHandlers = new Set<StatusHandler>();
  /** Topics we want the server to (re)subscribe us to after every (re)connect. */
  private readonly subscriptions = new Set<AdminSocketEvent>();

  constructor(config: AdminSocketConfig = {}) {
    this.cfg = {
      url: config.url ?? resolveAdminSocketUrl(),
      getToken: config.getToken ?? (() => localStorage.getItem('admin_token')),
      baseReconnectDelayMs: config.baseReconnectDelayMs ?? DEFAULTS.baseReconnectDelayMs,
      maxReconnectDelayMs: config.maxReconnectDelayMs ?? DEFAULTS.maxReconnectDelayMs,
      maxReconnectAttempts: config.maxReconnectAttempts ?? DEFAULTS.maxReconnectAttempts,
      heartbeatIntervalMs: config.heartbeatIntervalMs ?? DEFAULTS.heartbeatIntervalMs,
      heartbeatTimeoutMs: config.heartbeatTimeoutMs ?? DEFAULTS.heartbeatTimeoutMs,
    };
  }

  // ---- public API ---------------------------------------------------------

  getStatus(): AdminSocketStatus {
    return this.status;
  }

  /**
   * Open the connection. No-op if already connecting/open, and a deliberate
   * no-op (never throws, never logs an error) while
   * `VITE_ADMIN_WS_ENABLED` is not `"true"` — there is no backend endpoint
   * to reach yet, so callers should rely on REST polling instead.
   */
  connect(): void {
    if (this.status === AdminSocketStatus.CONNECTING || this.status === AdminSocketStatus.OPEN) {
      return;
    }

    if (!isAdminWsEnabled()) {
      log('VITE_ADMIN_WS_ENABLED is not "true" — skipping connect, using REST fallback');
      this.setStatus(AdminSocketStatus.CLOSED);
      return;
    }

    this.intentionalClose = false;

    const token = this.cfg.getToken();
    if (!token) {
      log('no admin_token — refusing to connect');
      this.setStatus(AdminSocketStatus.CLOSED);
      return;
    }

    const url = `${this.cfg.url}${this.cfg.url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`;
    this.setStatus(
      this.reconnectAttempts > 0 ? AdminSocketStatus.RECONNECTING : AdminSocketStatus.CONNECTING,
    );

    try {
      this.ws = new WebSocket(url);
    } catch (err) {
      log('construct failed', err);
      this.scheduleReconnect();
      return;
    }

    this.ws.onopen = this.onOpen;
    this.ws.onmessage = this.onMessage;
    this.ws.onclose = this.onClose;
    this.ws.onerror = this.onError;
  }

  /** Close intentionally and stop all timers. No reconnect will follow. */
  disconnect(code: number = CLOSE.NORMAL): void {
    this.intentionalClose = true;
    this.clearReconnect();
    this.stopHeartbeat();
    if (this.ws) {
      try {
        this.ws.close(code, 'client disconnect');
      } catch {
        /* ignore */
      }
    }
    this.ws = null;
    this.setStatus(AdminSocketStatus.CLOSED);
  }

  /**
   * Subscribe a handler to one event type. Registers interest with the server
   * (sending a subscribe frame immediately if connected) and returns an
   * unsubscribe function.
   */
  on<E extends AdminSocketEvent>(event: E, handler: EventHandler<E>): () => void {
    let set = this.handlers.get(event);
    if (!set) {
      set = new Set();
      this.handlers.set(event, set);
    }
    set.add(handler as EventHandler<AdminSocketEvent>);

    const isNewTopic = !this.subscriptions.has(event);
    this.subscriptions.add(event);
    if (isNewTopic && this.isOpen()) {
      this.send({ action: 'subscribe', topics: [event] });
    }

    return () => this.off(event, handler);
  }

  /** Remove a handler. Unsubscribes the topic server-side once it's unused. */
  off<E extends AdminSocketEvent>(event: E, handler: EventHandler<E>): void {
    const set = this.handlers.get(event);
    if (!set) return;
    set.delete(handler as EventHandler<AdminSocketEvent>);
    if (set.size === 0) {
      this.handlers.delete(event);
      this.subscriptions.delete(event);
      if (this.isOpen()) this.send({ action: 'unsubscribe', topics: [event] });
    }
  }

  /** Listen for connection-status changes (for banners/indicators). */
  onStatusChange(handler: StatusHandler): () => void {
    this.statusHandlers.add(handler);
    return () => this.statusHandlers.delete(handler);
  }

  // ---- socket event handlers (arrow fns to bind `this`) -------------------

  private onOpen = (): void => {
    log('open', this.cfg.url);
    this.reconnectAttempts = 0;
    this.setStatus(AdminSocketStatus.OPEN);
    // Re-assert all desired subscriptions on every (re)connect.
    if (this.subscriptions.size > 0) {
      this.send({ action: 'subscribe', topics: [...this.subscriptions] });
    }
    this.startHeartbeat();
  };

  private onMessage = (ev: MessageEvent): void => {
    let msg: AdminSocketMessage;
    try {
      msg = JSON.parse(String(ev.data)) as AdminSocketMessage;
    } catch {
      log('unparseable frame', ev.data);
      return;
    }

    // App-level pong resets the heartbeat watchdog.
    if ((msg as unknown as { type?: string }).type === 'system.pong') {
      this.clearPongTimer();
      return;
    }

    if (!msg || typeof msg.type !== 'string') {
      log('frame missing type', msg);
      return;
    }

    const set = this.handlers.get(msg.type);
    if (!set || set.size === 0) return;
    set.forEach((h) => {
      try {
        h(msg.payload);
      } catch (err) {
        log('handler threw', msg.type, err);
      }
    });
  };

  private onError = (ev: Event): void => {
    log('error', ev);
    // Browsers fire `error` then `close`; reconnect is driven from onClose.
  };

  private onClose = (ev: CloseEvent): void => {
    log('close', ev.code, ev.reason);
    this.stopHeartbeat();
    this.ws = null;

    if (this.intentionalClose || ev.code === CLOSE.NORMAL) {
      this.setStatus(AdminSocketStatus.CLOSED);
      return;
    }

    if (ev.code === CLOSE.AUTH_FAILED || ev.code === CLOSE.FORBIDDEN) {
      // Expired / rejected session: don't retry — the token won't heal itself.
      log('auth-failure close — not reconnecting');
      this.setStatus(AdminSocketStatus.CLOSED);
      return;
    }

    this.scheduleReconnect();
  };

  // ---- reconnect ----------------------------------------------------------

  private scheduleReconnect(): void {
    if (this.intentionalClose) return;

    const max = this.cfg.maxReconnectAttempts;
    if (max > 0 && this.reconnectAttempts >= max) {
      log('max reconnect attempts reached — giving up');
      this.setStatus(AdminSocketStatus.CLOSED);
      return;
    }

    this.reconnectAttempts += 1;
    // Exponential backoff with full jitter.
    const exp = Math.min(
      this.cfg.maxReconnectDelayMs,
      this.cfg.baseReconnectDelayMs * 2 ** (this.reconnectAttempts - 1),
    );
    const delay = Math.random() * exp;
    log(`reconnect #${this.reconnectAttempts} in ${Math.round(delay)}ms`);

    this.setStatus(AdminSocketStatus.RECONNECTING);
    this.clearReconnect();
    this.reconnectTimer = setTimeout(() => this.connect(), delay);
  }

  private clearReconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  // ---- heartbeat ----------------------------------------------------------

  private startHeartbeat(): void {
    if (this.cfg.heartbeatIntervalMs <= 0) return;
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (!this.isOpen()) return;
      this.send({ action: 'ping', ts: Date.now() });
      // If no pong lands in time, force a reconnect on a presumed-dead socket.
      this.clearPongTimer();
      this.pongTimer = setTimeout(() => {
        log('heartbeat timeout — forcing reconnect');
        try {
          this.ws?.close(4000, 'heartbeat timeout');
        } catch {
          /* onclose will drive the reconnect */
        }
      }, this.cfg.heartbeatTimeoutMs);
    }, this.cfg.heartbeatIntervalMs);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.clearPongTimer();
  }

  private clearPongTimer(): void {
    if (this.pongTimer) {
      clearTimeout(this.pongTimer);
      this.pongTimer = null;
    }
  }

  // ---- helpers ------------------------------------------------------------

  private isOpen(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  private send(frame: OutboundFrame): void {
    if (!this.isOpen()) return;
    try {
      this.ws!.send(JSON.stringify(frame));
    } catch (err) {
      log('send failed', err);
    }
  }

  private setStatus(next: AdminSocketStatus): void {
    if (this.status === next) return;
    this.status = next;
    this.statusHandlers.forEach((h) => {
      try {
        h(next);
      } catch (err) {
        log('status handler threw', err);
      }
    });
  }
}

// ============================================
// SINGLETON
// ============================================

let singleton: AdminSocketClient | null = null;

/**
 * Returns the shared admin socket, constructing it on first use so every
 * `useAdminSocket` consumer multiplexes over one connection.
 */
export function getAdminSocket(): AdminSocketClient {
  if (!singleton) singleton = new AdminSocketClient();
  return singleton;
}
