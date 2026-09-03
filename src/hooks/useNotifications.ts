/**
 * useNotifications - In-app notification state management
 * ----------------------------------------------------------------------------
 * P13: Notifications UI bound to GET /notifications endpoint.
 *
 * Backend not started (Domain B) - built against mock data with clean
 * adapter swap for when the real endpoint lands.
 *
 * Notification types (from 07-NOTIFICATION-SYSTEM-SPEC.md):
 *   - ai.site_analyzed, escrow.funded, creator.campaign_live, etc.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, isApiLive, notifications as notificationsApi, type Role } from '@/lib/api';
import { toast } from '@/hooks/use-toast';

// ---------------------------------------------------------------------------
// N-1 (BrandF.md §74): this hook used to call the API directly with a raw
// `fetch` + a locally-declared API_BASE_URL/authHeader, bypassing the shared
// HTTP client entirely — no 401 refresh, no credentials, a bare `new Error`
// on failure. All three calls now go through `api.ts`'s `notifications`
// client (`http.request` → `fetchWithAuthRetry`), which gets that for free.
// ---------------------------------------------------------------------------

// M-B (BrandF.md §78): bell data used to be fetched once on mount and never
// again for the life of the session. Poll on this interval as a floor, plus
// an open-triggered refresh wired in by the bell's own consumer (e.g.
// brand-layout.tsx calls `refresh()` when the popover opens).
const REFRESH_INTERVAL_MS = 60_000;

// F-0093: a tab whose refresh token has died keeps hitting GET /notifications every 60s
// forever, unauthenticated, for as long as it stays open. Consecutive auth failures now
// buy an exponentially growing number of skipped ticks (60s -> 2m -> 4m -> ... capped at
// this many), reset the moment one fetch succeeds. The cap keeps a tab that is merely
// waiting on a re-login from going quiet for the rest of the day.
const MAX_AUTH_BACKOFF_SKIPS = 15;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type NotificationType =
  | 'info'
  | 'success'
  | 'warning'
  | 'meera_nudge';

export interface Notification {
  id: string;
  type: NotificationType;
  title: string;
  body?: string;
  read: boolean;
  createdAt: string;
  /** Deep link to relevant page/stage */
  link?: string;
  /** For meera_nudge: the nudge can surface in chat */
  surfaceInChat?: boolean;
}

export interface UseNotificationsResult {
  notifications: Notification[];
  unreadCount: number;
  loading: boolean;
  error: string | null;
  /** Fetch notifications from server */
  refresh: () => Promise<void>;
  /** Mark a notification as read */
  markRead: (id: string) => Promise<void>;
  /** Mark all as read */
  markAllRead: () => Promise<void>;
}

// ---------------------------------------------------------------------------
// Wire shape (NotificationController.java / NotificationDtos.java) is handled
// by src/lib/api.ts's `notifications.list()` now (N-1 fix) — it returns
// already-classified NotificationItem rows matching this hook's own
// Notification shape one-for-one, so no separate wire type/mapper lives here
// any more.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Mock data
// ---------------------------------------------------------------------------

const MOCK_NOTIFICATIONS: Notification[] = [
  {
    id: 'n1',
    type: 'success',
    title: 'Website analyzed',
    body: 'Kavala Skincare profile is ready',
    read: false,
    createdAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    link: '/brand/meera',
  },
  {
    id: 'n2',
    type: 'info',
    title: '3 creators accepted',
    body: 'Your campaign invites are getting responses',
    read: false,
    createdAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
    link: '/brand/meera',
  },
  {
    id: 'n3',
    type: 'meera_nudge',
    title: 'Meera has a suggestion',
    body: 'Your serum campaign could reach 40% more people with 5 additional creators',
    read: true,
    createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    link: '/brand/meera',
    surfaceInChat: true,
  },
  {
    id: 'n4',
    type: 'success',
    title: 'Funds secured',
    body: 'Your funds are secured',
    read: true,
    createdAt: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
  },
];

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

/**
 * H-26: `role` picks which session token authenticates the calls (`brand_token` vs
 * `creator_token`) — the old hardcoded `brand_token` meant a creator-mounted bell would
 * always call with no/wrong auth. Defaults to `'brand'` to match every existing (pre-H-26,
 * currently unused) call shape.
 */
export function useNotifications(role: Role = 'brand'): UseNotificationsResult {
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Priya review finding (M-B follow-up): `loading` used to be set on every
  // refresh, including the 60s poll and the open-popover trigger — each one
  // blanked an already-populated, currently-open list back to a spinner.
  // `loading` now means "no data yet at all"; background refreshes after the
  // first successful load don't re-arm it.
  const hasLoadedOnceRef = useRef(false);
  // F-0436: `GET /notifications` pages at 20 (NotificationController#list) but its envelope
  // carries `unreadCount`, the server's count over ALL of the user's rows — and
  // `notificationsApi.list()` already hands it back. The badge used to be recomputed from the
  // one page held in state, so a user with more unread than fit on it saw an undercount that
  // never grew. Hold the server total and prefer it; `null` means "no server total yet"
  // (mock mode, or before the first successful fetch), where the page count is all there is.
  const [serverUnreadCount, setServerUnreadCount] = useState<number | null>(null);
  // Mirrors `notifications` so the optimistic handlers can read the pre-update rows without
  // taking `notifications` as a dependency (which would re-create them on every fetch).
  const notificationsRef = useRef<Notification[]>([]);
  // F-0093: consecutive auth failures, and how many poll ticks are still owed to the backoff.
  const authFailuresRef = useRef(0);
  const skipTicksRef = useRef(0);

  useEffect(() => {
    notificationsRef.current = notifications;
  }, [notifications]);

  const unreadCount = serverUnreadCount ?? notifications.filter((n) => !n.read).length;

  /**
   * Fetch notifications from server (or mock). N-1: routed through
   * `notificationsApi.list()` (src/lib/api.ts) instead of a raw `fetch` — gets
   * 401-refresh-and-retry, credentials, and typed errors for free.
   */
  const refresh = useCallback(async () => {
    if (!hasLoadedOnceRef.current) setLoading(true);
    setError(null);

    try {
      if (!isApiLive()) {
        // Mock mode - use local data. No server total exists here, so leave it null and let
        // the badge fall back to counting the mock rows.
        await new Promise((r) => setTimeout(r, 300));
        setNotifications(MOCK_NOTIFICATIONS);
        setServerUnreadCount(null);
      } else {
        const { items, unreadCount: serverUnread } = await notificationsApi.list(role);
        setNotifications(items);
        setServerUnreadCount(serverUnread);
      }
      hasLoadedOnceRef.current = true;
      authFailuresRef.current = 0;
      skipTicksRef.current = 0;
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load notifications');
      // F-0093: only an auth rejection earns the backoff — a 500 or a dropped connection is
      // transient and the plain 60s floor is the right retry for it.
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        authFailuresRef.current += 1;
        skipTicksRef.current = Math.min(
          2 ** authFailuresRef.current - 1,
          MAX_AUTH_BACKOFF_SKIPS,
        );
      }
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [role]);

  /**
   * Mark a single notification as read. Backend is `POST /notifications/read` with a JSON
   * body `{ notificationId }` (NotificationController.java) — not `POST /notifications/{id}/read`.
   */
  const markRead = useCallback(async (id: string) => {
    // F-0436: the badge reads the server total now, so an optimistic flip has to move that
    // total too — otherwise the row greys out while the badge sits unchanged. Only a row that
    // was actually unread counts. The next poll re-reads the authoritative number; the
    // mark-read response is NOT used for it, because NotificationController returns a bare
    // `MarkReadResponse` with no `data` envelope while `http.request` unwraps `envelope.data`,
    // so its typed `newUnreadCount` arrives `undefined` at runtime.
    const wasUnread = notificationsRef.current.some((n) => n.id === id && !n.read);

    // Optimistic update
    setNotifications((prev) =>
      prev.map((n) => (n.id === id ? { ...n, read: true } : n))
    );
    if (wasUnread) {
      setServerUnreadCount((c) => (c === null ? c : Math.max(0, c - 1)));
    }

    if (isApiLive()) {
      try {
        await notificationsApi.markRead(role, id);
      } catch {
        // Revert on error + tell the user (was a silent revert — the notification
        // just popped back to unread with no explanation).
        setNotifications((prev) =>
          prev.map((n) => (n.id === id ? { ...n, read: false } : n))
        );
        if (wasUnread) {
          setServerUnreadCount((c) => (c === null ? c : c + 1));
        }
        toast({ title: 'Couldn’t mark as read', description: 'Please try again.', variant: 'destructive' });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [role]);

  /**
   * Mark all notifications as read via the bulk `POST /notifications/read-all` route (a single
   * server-side UPDATE). Previously this fired one `POST /notifications/read` per unread
   * notification because no bulk route existed; the backend now has one.
   */
  const markAllRead = useCallback(async () => {
    const unreadIds = notifications.filter((n) => !n.read).map((n) => n.id);
    if (unreadIds.length === 0) return;

    // Optimistic update. F-0436: read-all clears the server's whole unread total, not only
    // the rows on this page, so the badge goes to 0 rather than down by `unreadIds.length`.
    const previousServerUnread = serverUnreadCount;
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
    setServerUnreadCount((c) => (c === null ? c : 0));

    if (isApiLive()) {
      try {
        await notificationsApi.markAllRead(role);
      } catch {
        // Revert the optimistic update — restore exactly the rows we flipped.
        const revertIds = new Set(unreadIds);
        setNotifications((prev) =>
          prev.map((n) => (revertIds.has(n.id) ? { ...n, read: false } : n)),
        );
        setServerUnreadCount(previousServerUnread);
        toast({
          title: 'Couldn’t mark notifications read',
          description: 'Please try again.',
          variant: 'destructive',
        });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notifications, serverUnreadCount, role]);

  // Fetch on mount / when the role changes
  useEffect(() => {
    refresh();
  }, [refresh]);

  // M-B: the bell used to fetch once at mount and never again for the rest of
  // the session. Poll on a fixed interval as a floor so unread state doesn't
  // go stale for a brand/creator who leaves the tab open all day; consumers
  // that also want an open-triggered refresh (e.g. popover onOpenChange) can
  // still call `refresh()` themselves — this doesn't replace that.
  useEffect(() => {
    if (!isApiLive()) return;
    const id = setInterval(() => {
      // F-0093: burn a tick instead of firing another unauthenticated request. A consumer's
      // own explicit `refresh()` (e.g. the bell popover opening) is never skipped — this
      // guard is on the unattended interval only.
      if (skipTicksRef.current > 0) {
        skipTicksRef.current -= 1;
        return;
      }
      refresh();
    }, REFRESH_INTERVAL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  return {
    notifications,
    unreadCount,
    loading,
    error,
    refresh,
    markRead,
    markAllRead,
  };
}

export default useNotifications;
