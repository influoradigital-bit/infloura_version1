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

import { useCallback, useEffect, useState } from 'react';
import { isApiLive, type Role } from '@/lib/api';
import { toast } from '@/hooks/use-toast';

// ---------------------------------------------------------------------------
// Environment / config — same local-const pattern as src/lib/meera-api.ts
// (API_BASE_URL is not exported from src/lib/api.ts).
// ---------------------------------------------------------------------------

const API_BASE_URL =
  (import.meta as any).env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

const ROLE_TOKEN_KEYS: Record<Role, string> = {
  brand: 'brand_token',
  creator: 'creator_token',
};

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
// Wire shape (NotificationController.java / NotificationDtos.java) — raw, NOT
// wrapped in the {success,data,error} envelope most other controllers use.
// ---------------------------------------------------------------------------

interface NotificationWire {
  id: string;
  eventType: string;
  title: string;
  body: string | null;
  link: string | null;
  isRead: boolean;
  createdAt: string;
}

interface NotificationListWire {
  notifications: NotificationWire[];
  unreadCount: number;
  page: number;
  size: number;
}

/**
 * `eventType` is a free-form `domain.event` string (`escrow.funded`, `ai.site_analyzed`,
 * `subscription.payment_failed`, …) — not one of the UI's four coarse buckets. Classify it
 * for iconography rather than pretending it already matches `NotificationType`.
 */
function classifyEventType(eventType: string): NotificationType {
  if (eventType.startsWith('ai.')) return 'meera_nudge';
  if (/failed|rejected|halted|low_balance|exhausted|disputed/i.test(eventType)) return 'warning';
  if (/funded|signed|accepted|released|approved|received|reset|completed/i.test(eventType)) return 'success';
  return 'info';
}

function fromWire(n: NotificationWire): Notification {
  return {
    id: n.id,
    type: classifyEventType(n.eventType),
    title: n.title,
    body: n.body ?? undefined,
    read: n.isRead,
    createdAt: n.createdAt,
    link: n.link ?? undefined,
  };
}

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
    title: 'Escrow funded',
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

  const unreadCount = notifications.filter((n) => !n.read).length;
  const authHeader = () => ({ Authorization: `Bearer ${localStorage.getItem(ROLE_TOKEN_KEYS[role])}` });

  /**
   * Fetch notifications from server (or mock)
   */
  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      if (!isApiLive()) {
        // Mock mode - use local data
        await new Promise((r) => setTimeout(r, 300));
        setNotifications(MOCK_NOTIFICATIONS);
      } else {
        // BR-34: NotificationController.list() is now wrapped in the standard
        // {success, data, error} ApiResponse envelope (feature-audit-brand-creator-2026-07-23.md
        // P2 fix) like every other controller — the NotificationListResponse shape
        // ({notifications, unreadCount, page, size}) lives under `.data`, not the body root.
        const res = await fetch(`${API_BASE_URL}/notifications`, { headers: authHeader() });
        if (!res.ok) throw new Error('Failed to fetch notifications');
        const envelope = (await res.json()) as { success: boolean; data?: NotificationListWire };
        if (!envelope.success || !envelope.data) throw new Error('Failed to fetch notifications');
        setNotifications((envelope.data.notifications ?? []).map(fromWire));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load notifications');
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
    // Optimistic update
    setNotifications((prev) =>
      prev.map((n) => (n.id === id ? { ...n, read: true } : n))
    );

    if (isApiLive()) {
      try {
        const res = await fetch(`${API_BASE_URL}/notifications/read`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...authHeader() },
          body: JSON.stringify({ notificationId: id }),
        });
        if (!res.ok) throw new Error('Failed to mark notification read');
      } catch {
        // Revert on error + tell the user (was a silent revert — the notification
        // just popped back to unread with no explanation).
        setNotifications((prev) =>
          prev.map((n) => (n.id === id ? { ...n, read: false } : n))
        );
        toast({ title: 'Couldn’t mark as read', description: 'Please try again.', variant: 'destructive' });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [role]);

  /**
   * Mark all notifications as read. `NotificationController` has no bulk `/read-all` route —
   * only single-notification `POST /notifications/read` exists — so this fires one call per
   * currently-unread notification instead of hitting a 404.
   */
  const markAllRead = useCallback(async () => {
    const unreadIds = notifications.filter((n) => !n.read).map((n) => n.id);
    if (unreadIds.length === 0) return;

    // Optimistic update
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));

    if (isApiLive()) {
      const results = await Promise.allSettled(
        unreadIds.map((id) =>
          fetch(`${API_BASE_URL}/notifications/read`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...authHeader() },
            body: JSON.stringify({ notificationId: id }),
          }).then((res) => {
            if (!res.ok) throw new Error('Failed to mark notification read');
          }),
        ),
      );
      const failedIds = new Set(
        unreadIds.filter((_, i) => results[i].status === 'rejected'),
      );
      if (failedIds.size > 0) {
        // Revert only the ones that actually failed.
        setNotifications((prev) =>
          prev.map((n) => (failedIds.has(n.id) ? { ...n, read: false } : n)),
        );
        toast({
          title: 'Some notifications couldn’t be marked read',
          description: 'Please try again.',
          variant: 'destructive',
        });
      }
    }
  }, [notifications]);

  // Fetch on mount / when the role changes
  useEffect(() => {
    refresh();
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
