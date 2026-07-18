/**
 * INFLUORA ADMIN PANEL — Operations Summary data hook
 * Owner: Ananya (Frontend)
 * Reference: A5 (Priya-routed admin-panel audit fix), surfaced on
 * PulseDashboard.tsx alongside the existing CEO Pulse KPIs.
 *
 * Backed by the live `dashboardApi.getOperationsSummary()` call
 * (`GET /api/v1/admin/dashboard/operations`). Mirrors `usePulseData.ts`
 * exactly: fetch-on-mount, cancel-flag cleanup, `{ data, isLoading, error }`
 * shape — no react-query here since `usePulseData` (the sibling dashboard
 * hook this one sits next to) doesn't use it either.
 */

import { useEffect, useState } from 'react';
import { dashboardApi } from '../services/api-contracts';

// ============================================
// TYPES
// ============================================

/**
 * Not promoted to `admin.types.ts` — mirrors the inline return shape
 * `dashboardApi.getOperationsSummary()` already declares in
 * api-contracts.ts (no dedicated named type exists there yet).
 */
export interface OperationsSummary {
  activeCampaigns: number;
  campaignsAtRisk: number;
  reviewBacklog: number;
  supportQueueDepth: number;
  avgReviewTime: number;
}

export interface UseOperationsSummaryResult {
  data: OperationsSummary | null;
  isLoading: boolean;
  error: string | null;
}

// ============================================
// HOOK
// ============================================

/**
 * Returns operations KPI stats for the admin dashboard from the live backend.
 */
export function useOperationsSummary(): UseOperationsSummaryResult {
  const [data, setData] = useState<OperationsSummary | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    setIsLoading(true);
    setError(null);

    dashboardApi
      .getOperationsSummary()
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load operations summary');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load operations summary');
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
