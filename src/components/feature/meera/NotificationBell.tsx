/**
 * NotificationBell - In-app notifications dropdown
 * ----------------------------------------------------------------------------
 * P13: Notification bell with badge count and dropdown list.
 *
 * Built against mock data - clean adapter swap when Domain B lands.
 */

import { useState } from 'react';
import { Bell, Check, Info, Sparkles, AlertTriangle, CheckCircle } from 'lucide-react';

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { useNotifications, type Notification, type NotificationType } from '@/hooks/useNotifications';
import { cn } from '@/lib/utils';

interface NotificationBellProps {
  className?: string;
}

/**
 * Get icon for notification type
 */
function getNotificationIcon(type: NotificationType) {
  switch (type) {
    case 'success':
      return <CheckCircle className="h-4 w-4 text-meera-escrow" />;
    case 'warning':
      return <AlertTriangle className="h-4 w-4 text-meera-warning" />;
    case 'meera_nudge':
      return <Sparkles className="h-4 w-4 text-meera-accent" />;
    default:
      return <Info className="h-4 w-4 text-meera-accent" />;
  }
}

/**
 * Format relative time
 */
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

/**
 * Single notification item
 */
function NotificationItem({
  notification,
  onMarkRead,
}: {
  notification: Notification;
  onMarkRead: (id: string) => void;
}) {
  const handleClick = () => {
    if (!notification.read) {
      onMarkRead(notification.id);
    }
    if (notification.link) {
      // In a real app, use router navigation
      window.location.href = notification.link;
    }
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className={cn(
        'flex w-full items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-meera-surface-2',
        !notification.read && 'bg-meera-accent-soft/30'
      )}
    >
      <span className="mt-0.5 shrink-0">
        {getNotificationIcon(notification.type)}
      </span>
      <div className="min-w-0 flex-1">
        <p className={cn(
          'text-sm',
          notification.read ? 'text-meera-text-muted' : 'font-medium text-meera-text'
        )}>
          {notification.title}
        </p>
        {notification.body && (
          <p className="mt-0.5 text-xs text-meera-text-muted line-clamp-2">
            {notification.body}
          </p>
        )}
        <p className="mt-1 text-[10px] text-meera-text-muted">
          {formatRelativeTime(notification.createdAt)}
        </p>
      </div>
      {!notification.read && (
        <span className="mt-2 h-2 w-2 shrink-0 rounded-full bg-meera-accent" />
      )}
    </button>
  );
}

export function NotificationBell({ className }: NotificationBellProps) {
  const [open, setOpen] = useState(false);
  const { notifications, unreadCount, loading, error, markRead, markAllRead } = useNotifications();

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={cn(
            'relative flex h-9 w-9 items-center justify-center rounded-lg transition-colors hover:bg-meera-surface-2',
            className
          )}
          aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
        >
          <Bell className="h-5 w-5 text-meera-text-muted" />
          {unreadCount > 0 && (
            <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-meera-accent px-1 text-[10px] font-medium text-white">
              {unreadCount > 9 ? '9+' : unreadCount}
            </span>
          )}
        </button>
      </PopoverTrigger>

      <PopoverContent
        align="end"
        className="w-80 p-0 border-meera-border bg-meera-surface"
        sideOffset={8}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-meera-border px-4 py-3">
          <h3 className="text-sm font-semibold text-meera-text">Notifications</h3>
          {unreadCount > 0 && (
            <button
              type="button"
              onClick={() => markAllRead()}
              className="flex items-center gap-1 text-xs text-meera-accent hover:underline"
            >
              <Check className="h-3 w-3" />
              Mark all read
            </button>
          )}
        </div>

        {/* List */}
        <div className="max-h-80 overflow-y-auto">
          {loading ? (
            <div className="flex items-center justify-center py-8">
              <div className="h-5 w-5 animate-spin rounded-full border-2 border-meera-border border-t-meera-accent" />
            </div>
          ) : error && notifications.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-8 text-center">
              <Bell className="mb-2 h-8 w-8 text-meera-border-strong" />
              <p className="text-sm text-meera-text-muted">Couldn’t load notifications</p>
              <p className="mt-0.5 text-xs text-meera-text-muted/80">Please try again shortly.</p>
            </div>
          ) : notifications.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-8 text-center">
              <Bell className="mb-2 h-8 w-8 text-meera-border-strong" />
              <p className="text-sm text-meera-text-muted">No notifications yet</p>
            </div>
          ) : (
            <div className="divide-y divide-meera-border">
              {notifications.map((notification) => (
                <NotificationItem
                  key={notification.id}
                  notification={notification}
                  onMarkRead={markRead}
                />
              ))}
            </div>
          )}
        </div>

        {/* Footer */}
        {notifications.length > 0 && (
          <div className="border-t border-meera-border px-4 py-2">
            <button
              type="button"
              className="w-full text-center text-xs text-meera-accent hover:underline"
            >
              View all notifications
            </button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}

export default NotificationBell;
