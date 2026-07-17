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
import { isApiLive } from '@/lib/api';

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

export function useNotifications(): UseNotificationsResult {
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const unreadCount = notifications.filter((n) => !n.read).length;

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
        // Live mode - call API
        // TODO: Replace with real API call when Domain B lands
        const res = await fetch('/api/v1/notifications', {
          headers: {
            Authorization: `Bearer ${localStorage.getItem('brand_token')}`,
          },
        });
        if (!res.ok) throw new Error('Failed to fetch notifications');
        const data = await res.json();
        setNotifications(data.data || []);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load notifications');
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * Mark a single notification as read
   */
  const markRead = useCallback(async (id: string) => {
    // Optimistic update
    setNotifications((prev) =>
      prev.map((n) => (n.id === id ? { ...n, read: true } : n))
    );

    if (isApiLive()) {
      try {
        await fetch(`/api/v1/notifications/${id}/read`, {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${localStorage.getItem('brand_token')}`,
          },
        });
      } catch {
        // Revert on error
        setNotifications((prev) =>
          prev.map((n) => (n.id === id ? { ...n, read: false } : n))
        );
      }
    }
  }, []);

  /**
   * Mark all notifications as read
   */
  const markAllRead = useCallback(async () => {
    // Optimistic update
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));

    if (isApiLive()) {
      try {
        await fetch('/api/v1/notifications/read-all', {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${localStorage.getItem('brand_token')}`,
          },
        });
      } catch {
        // Revert would need to track previous state - skip for now
      }
    }
  }, []);

  // Fetch on mount
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
