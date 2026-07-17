/**
 * useCreatorMetrics - brand-facing creator metrics
 * ----------------------------------------------------------------------------
 * Backed by GET /analytics/creators/{creatorId}/metrics (influora-api
 * AnalyticsController). Follows this repo's existing async-data hook
 * convention (see useNotifications.ts) — { data, loading, error, refresh } —
 * rather than TanStack Query, since no page in this codebase uses
 * @tanstack/react-query today despite it being a dependency.
 *
 * trendData is only populated by the backend when both startDate and endDate
 * are supplied; otherwise it comes back as an empty array (never fabricated).
 */

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { AnalyticsDateRange, CreatorMetrics } from '@/lib/types';

export interface UseCreatorMetricsResult {
  data: CreatorMetrics | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useCreatorMetrics(
  creatorId: string | undefined,
  dateRange?: AnalyticsDateRange,
): UseCreatorMetricsResult {
  const [data, setData] = useState<CreatorMetrics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const startIso = dateRange?.start?.toISOString();
  const endIso = dateRange?.end?.toISOString();

  const refresh = useCallback(async () => {
    if (!creatorId) {
      setData(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await api.analytics.getCreatorMetrics(creatorId, startIso, endIso);
      setData(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load creator metrics');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [creatorId, startIso, endIso]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, refresh };
}

export default useCreatorMetrics;
