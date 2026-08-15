import * as React from 'react';
import { Bell, CheckCheck } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { useNotifications } from '@/hooks/useNotifications';

/**
 * CR-123 — the header bell's unread badge was correctly driven by a real API aggregation, but
 * the bell button itself had no onClick, so clicking it never opened a panel or navigated
 * anywhere. This page is that destination: mirrors `brand-notifications.tsx` exactly (same
 * `useNotifications` hook, real GET/POST /notifications routes, no new backend), just role
 * 'creator' and wrapped in `CreatorLayout` per this page's own inline-wrap convention (see
 * creator-settings.tsx) rather than a route-level wrapper.
 */

/** Mirrors brand-notifications.tsx's local helper — no shared util module exists yet. */
function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;
  return date.toLocaleDateString();
}

export default function CreatorNotificationsPage() {
  const navigate = useNavigate();
  const { notifications, unreadCount, loading, error, markRead, markAllRead } = useNotifications('creator');

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-2xl px-4 py-6">
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              {unreadCount > 0 ? `${unreadCount} unread` : 'All caught up'}
            </p>
          </div>
          {unreadCount > 0 && (
            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => markAllRead()}>
              <CheckCheck className="h-4 w-4" />
              Mark all read
            </Button>
          )}
        </div>

        <Card>
          <CardContent className="p-1">
            {loading ? (
              <div className="flex items-center justify-center py-16">
                <div className="h-6 w-6 animate-spin rounded-full border-2 border-border border-t-primary" />
              </div>
            ) : error && notifications.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-center px-4">
                <Bell className="mb-2 h-8 w-8 text-muted-foreground/50" />
                <p className="text-sm text-muted-foreground">Couldn't load notifications</p>
                <p className="mt-0.5 text-xs text-muted-foreground/80">Please try again shortly.</p>
              </div>
            ) : notifications.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-center px-4">
                <Bell className="mb-2 h-8 w-8 text-muted-foreground/50" />
                <p className="text-sm text-muted-foreground">No notifications yet</p>
              </div>
            ) : (
              <div className="p-1">
                {notifications.map((notification) => (
                  <div
                    key={notification.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => {
                      if (!notification.read) markRead(notification.id);
                      if (notification.link) navigate(notification.link);
                    }}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        if (!notification.read) markRead(notification.id);
                        if (notification.link) navigate(notification.link);
                      }
                    }}
                    className={cn(
                      'flex gap-3 p-3 hover:bg-accent cursor-pointer rounded-lg transition-colors',
                      !notification.read && 'bg-primary/5',
                    )}
                  >
                    {!notification.read && (
                      <span className="bg-primary h-2 w-2 rounded-full mt-1.5 shrink-0" />
                    )}
                    <div className={cn('flex-1 min-w-0', notification.read && 'ml-5')}>
                      <p className="text-sm font-medium">{notification.title}</p>
                      {notification.body && (
                        <p className="text-xs text-muted-foreground">{notification.body}</p>
                      )}
                      <p className="text-xs text-muted-foreground mt-1">
                        {formatRelativeTime(notification.createdAt)}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </CreatorLayout>
  );
}
