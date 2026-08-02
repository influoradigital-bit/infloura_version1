/**
 * INFLUORA ADMIN PANEL — Real-time socket subscription hook
 * Owner: Priya (CTO)
 * Reference: src/admin/services/websocket.ts, docs/ADMIN-PANEL-SPEC.md
 *
 * Thin React binding over the shared `AdminSocketClient` singleton. Components
 * declare which event they care about and get a stable, typed callback plus the
 * live connection status — the hook owns connect/subscribe/cleanup so a screen
 * never leaks a listener or opens a second socket.
 *
 * Typical uses:
 *   - PulseDashboard subscribes to DASHBOARD_PULSE to live-refresh KPI tiles.
 *   - TicketList subscribes to SUPPORT_TICKET_CREATED to prepend new tickets.
 *   - FlagQueue subscribes to MODERATION_FLAG_CREATED for the moderation badge.
 *   - Approval widgets subscribe to APPROVAL_QUEUED for the pending count.
 */

import { useEffect, useRef, useState } from 'react';
import {
  AdminSocketEvent,
  AdminSocketStatus,
  getAdminSocket,
  type AdminSocketEventMap,
} from '../services/websocket';

export interface UseAdminSocketOptions {
  /**
   * When false, the hook neither connects nor subscribes (e.g. before auth
   * resolves, or on a screen the user lacks permission for). Defaults to true.
   */
  enabled?: boolean;
}

export interface UseAdminSocketResult {
  /** Live connection status, for banners/indicators. */
  status: AdminSocketStatus;
  /** True only while the socket is fully OPEN. */
  isConnected: boolean;
}

/**
 * Subscribe to a single admin socket event.
 *
 * The `handler` is captured in a ref so callers can pass an inline arrow
 * function without re-subscribing on every render — the socket subscription is
 * keyed only on `event` and `enabled`.
 *
 * @example
 * useAdminSocket(AdminSocketEvent.SUPPORT_TICKET_CREATED, (ticket) => {
 *   setTickets((prev) => [ticket, ...prev]);
 * });
 */
export function useAdminSocket<E extends AdminSocketEvent>(
  event: E,
  handler: (payload: AdminSocketEventMap[E]) => void,
  options: UseAdminSocketOptions = {},
): UseAdminSocketResult {
  const { enabled = true } = options;

  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  const [status, setStatus] = useState<AdminSocketStatus>(
    () => getAdminSocket().getStatus(),
  );

  useEffect(() => {
    if (!enabled) return;

    const socket = getAdminSocket();

    const offStatus = socket.onStatusChange(setStatus);
    setStatus(socket.getStatus());

    // Stable indirection so the subscription doesn't churn when `handler`
    // identity changes between renders.
    const off = socket.on(event, (payload) => handlerRef.current(payload));

    socket.connect();

    return () => {
      off();
      offStatus();
    };
  }, [event, enabled]);

  return {
    status,
    isConnected: status === AdminSocketStatus.OPEN,
  };
}
