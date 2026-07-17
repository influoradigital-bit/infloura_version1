/**
 * useContentPerformance - brand-facing per-post media performance
 * ----------------------------------------------------------------------------
 * Intended backend: GET /analytics/creators/{creatorId}/media — NOT YET
 * BUILT. Checked influora-api/src/main/java/com/influora/web/AnalyticsController.java
 * directly; only /metrics, /scores, /demographics exist. See the gap note
 * above `contentPerformance` in src/lib/api.ts.
 *
 * Same hook shape as the other analytics hooks. In live mode, `api.contentPerformance.list`
 * always rejects with a typed ApiError('NOT_IMPLEMENTED', ...) — this hook
 * surfaces that as `error` with the code preserved so the panel can render an
 * explicit "API not yet available" banner instead of a generic failure.
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { ContentPerformanceItem } from '@/lib/api';

export interface UseContentPerformanceResult {
  data: ContentPerformanceItem[] | null;
  loading: boolean;
  error: string | null;
  /** True when the error is the known NOT_IMPLEMENTED gap, not a real failure. */
  notImplemented: boolean;
  refresh: () => Promise<void>;
}

export function useContentPerformance(
  creatorId: string | undefined,
): UseContentPerformanceResult {
  const [data, setData] = useState<ContentPerformanceItem[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notImplemented, setNotImplemented] = useState(false);

  const refresh = useCallback(async () => {
    if (!creatorId) {
      setData(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    setNotImplemented(false);
    try {
      const result = await api.contentPerformance.list(creatorId);
      setData(result);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'NOT_IMPLEMENTED') {
        setNotImplemented(true);
        setError(err.message);
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load content performance');
      }
    } finally {
      setLoading(false);
    }
  }, [creatorId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, notImplemented, refresh };
}

export default useContentPerformance;
