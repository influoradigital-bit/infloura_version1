/**
 * INFLUORA ADMIN PANEL — CEO Pulse data hook
 * Owner: Ananya (Frontend) · live-wired by Priya (CTO), P1-WIRE-1
 *
 * Backed by the live `dashboardApi.getPulse()` call
 * (`GET /api/v1/admin/dashboard/pulse`, implemented in AdminDashboardController).
 * The previous mock implementation has been removed now that the endpoint ships.
 *
 * NOTE: this fires a real network request on mount; the `error` branch renders
 * whenever the backend is unreachable or returns a non-2xx envelope. The public
 * `UsePulseDataResult` shape is unchanged, so `PulseDashboard` needs no edits.
 */

import { useEffect, useState } from 'react';
import type { CeoPulseData } from '../types/admin.types';
import { dashboardApi } from '../services/api-contracts';

// ============================================
// HOOK
// ============================================

export interface UsePulseDataResult {
  data: CeoPulseData | null;
  isLoading: boolean;
  error: string | null;
}

/**
 * Returns CEO Pulse stats for the admin dashboard from the live backend.
 */
export function usePulseData(): UsePulseDataResult {
  const [data, setData] = useState<CeoPulseData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    setIsLoading(true);
    setError(null);

    dashboardApi
      .getPulse()
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load dashboard pulse');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load dashboard pulse');
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
