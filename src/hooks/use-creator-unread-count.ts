/**
 * CR-16 — the real unread-message total for the creator shell.
 *
 * The sidebar's "Deals" badge and the header bell were both driven by
 * `React.useState(3)`: a literal with no setter and no data source. Observed
 * live as "Deals | 3" on an account holding 2 deals and 0 unread messages. A
 * badge that is always 3 trains creators to ignore the badge.
 *
 * `GET /deals?role=creator&status=all` already returns `unreadCount` per deal
 * (see `Deal` in lib/api.ts), so no new backend is needed — the number just has
 * to be read.
 */
import * as React from 'react';
import { api } from '@/lib/api';

/**
 * @param refreshKey change this to force a re-read — the layout passes the
 * current pathname, so the badge settles after the creator opens a deal room
 * and its messages are marked read.
 */
export function useCreatorUnreadCount(refreshKey?: string | number): number {
  const [unreadCount, setUnreadCount] = React.useState(0);

  React.useEffect(() => {
    if (!localStorage.getItem('creator_token')) return;
    let cancelled = false;
    (async () => {
      try {
        const deals = await api.deals.list('creator', 'all');
        if (cancelled || !Array.isArray(deals)) return;
        setUnreadCount(deals.reduce((sum, d) => sum + (d.unreadCount ?? 0), 0));
      } catch {
        // Silent by design. This is chrome, not content — the pages themselves
        // surface deal-loading failures. Leaving the badge at its last known
        // value is better than a toast from the shell on every navigation.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  return unreadCount;
}
