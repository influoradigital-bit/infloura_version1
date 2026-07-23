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
 *
 * When creatorId === CREATOR_ANALYTICS_SELF, routes to the creator-self
 * endpoint (api.creatorAnalytics.getMyMetrics) instead of the brand-facing
 * /analytics/creators/{id}/metrics endpoint — see the branch in refresh()
 * below (matches the pattern in useCreatorDemographics.ts).
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { AnalyticsDateRange, CreatorMetrics } from '@/lib/types';

/**
 * B39: GET /analytics/creators/:id/metrics correctly gates per-creator
 * metrics behind the plan limit (402 UPGRADE_REQUIRED on Free) — that's
 * working as designed, not an authz failure. Without this check the raw
 * backend message ("...not authorized to view metrics for that creator...")
 * read as a real permission error instead of an upgrade prompt.
 */
const UPGRADE_PROMPT_MESSAGE =
  'Upgrade to view more creator analytics — you\'ve reached your plan\'s monthly limit.';

function messageForMetricsError(err: unknown): string {
  if (err instanceof ApiError && (err.status === 402 || err.code === 'UPGRADE_REQUIRED')) {
    return UPGRADE_PROMPT_MESSAGE;
  }
  return err instanceof Error ? err.message : 'Failed to load creator metrics';
}

/**
 * Sentinel creatorId meaning "the logged-in creator viewing their own analytics".
 * Hooks route this to the creator-self endpoints (/creator/analytics/me/*) instead
 * of the brand-facing /analytics/creators/{id}/* ones.
 */
export const CREATOR_ANALYTICS_SELF = '__me__';

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
      const result =
        creatorId === CREATOR_ANALYTICS_SELF
          ? await api.creatorAnalytics.getMyMetrics(startIso, endIso)
          : await api.analytics.getCreatorMetrics(creatorId, startIso, endIso);
      setData(result);
    } catch (err) {
      setError(messageForMetricsError(err));
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
