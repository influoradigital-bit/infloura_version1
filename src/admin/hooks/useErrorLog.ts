/**
 * INFLUORA ADMIN PANEL — Error Log data hook
 * Owner: Ananya (Frontend)
 * Reference: Wire-up pass — Error Log console (src/admin/pages/ErrorLogPage.tsx)
 *
 * Backed by the live `errorApi.getRecent(limit)` + `errorApi.getStats()` calls
 * (`GET /api/v1/admin/errors/recent`, `GET /api/v1/admin/errors/stats`,
 * AdminErrorController-equivalent). Two independent fetches — a stats
 * snapshot (fetch-on-mount, same shape as `usePulseData.ts`) and a recent-
 * errors list (fetch-on-mount + refreshable, same `refresh()` shape as
 * `useBrandDetail.ts`) — plus a `resolve(id)` action wired to
 * `errorApi.resolve()` that refreshes both on success so the stats strip
 * (`unresolved` count) and the table agree.
 */

import { useCallback, useEffect, useState } from 'react';
import type { ErrorLogEntry } from '../types/admin.types';
import { errorApi } from '../services/api-contracts';

const RECENT_ERRORS_LIMIT = 50;

// ============================================
// TYPES
// ============================================

export interface ErrorLogStats {
  total24h: number;
  critical24h: number;
  unresolved: number;
  topEndpoints: { endpoint: string; count: number }[];
}

export interface UseErrorLogResult {
  entries: ErrorLogEntry[];
  stats: ErrorLogStats | null;
  isLoading: boolean;
  error: string | null;
  /** Re-fetch both the entry list and the stats snapshot. */
  refresh: () => void;
  /** Resolve one error entry; refreshes the list + stats on success. */
  resolve: (id: string) => Promise<{ success: boolean; error?: string }>;
}

// ============================================
// HOOK
// ============================================

/**
 * Returns the admin error log console's entries + stats from the live backend.
 */
export function useErrorLog(): UseErrorLogResult {
  const [entries, setEntries] = useState<ErrorLogEntry[]>([]);
  const [stats, setStats] = useState<ErrorLogStats | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    Promise.all([errorApi.getRecent(RECENT_ERRORS_LIMIT), errorApi.getStats()])
      .then(([recentRes, statsRes]) => {
        if (cancelled) return;

        if (recentRes.success && recentRes.data) {
          setEntries(recentRes.data);
        } else {
          setEntries([]);
        }

        if (statsRes.success && statsRes.data) {
          setStats(statsRes.data);
        } else {
          setStats(null);
        }

        if (!recentRes.success || !statsRes.success) {
          setError(recentRes.error ?? statsRes.error ?? 'Failed to load error log');
        } else {
          setError(null);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setEntries([]);
        setStats(null);
        setError('Failed to load error log');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  const resolve = useCallback(
    async (id: string) => {
      const res = await errorApi.resolve(id);
      if (res.success) {
        refresh();
        return { success: true };
      }
      return { success: false, error: res.error ?? 'Failed to resolve error.' };
    },
    [refresh],
  );

  return { entries, stats, isLoading, error, refresh, resolve };
}
