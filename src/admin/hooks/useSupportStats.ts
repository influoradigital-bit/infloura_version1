/**
 * INFLUORA ADMIN PANEL — Support Stats hook
 * Owner: Ananya (Frontend)
 * Reference: Wire-up pass — support console stats strip (TicketList.tsx)
 *
 * Backed by the live `supportApi.getStats()` call
 * (`GET /api/v1/admin/support/stats`, AdminSupportController). Same
 * fetch-on-mount shape as `usePulseData.ts` — no filters/pagination, just a
 * single snapshot read.
 *
 * NOTE: `avgResponseTime`/`avgResolutionTime` come back as plain numbers with
 * no distinct "not measured yet" sentinel — a queue with no resolved tickets
 * yet legitimately reports `0`, indistinguishable on the wire from "a ticket
 * really was answered instantly". `TicketList.tsx`'s stats strip renders `0`
 * as "No data yet" rather than a misleading "0m" (an honest-empty state, not
 * a fabricated one — TECH-STACK.md rule 7).
 */

import { useEffect, useState } from 'react';
import { supportApi } from '../services/api-contracts';

// ============================================
// HOOK
// ============================================

export interface SupportStats {
  open: number;
  inProgress: number;
  waitingUser: number;
  avgResponseTime: number;
  avgResolutionTime: number;
}

export interface UseSupportStatsResult {
  data: SupportStats | null;
  isLoading: boolean;
  error: string | null;
}

/**
 * Returns the admin support queue stats snapshot from the live backend.
 */
export function useSupportStats(): UseSupportStatsResult {
  const [data, setData] = useState<SupportStats | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    setIsLoading(true);
    setError(null);

    supportApi
      .getStats()
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load support stats');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load support stats');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return { data, isLoading, error };
}
