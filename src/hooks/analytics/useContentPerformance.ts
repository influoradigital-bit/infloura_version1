/**
 * useContentPerformance - brand-facing per-post media performance
 * ----------------------------------------------------------------------------
 * STALE-DOC FIX (Brand Surface Audit fix #4, wiki/reports/brand-feature-audit.md
 * item 4): this comment used to claim `api.contentPerformance.list` "always
 * rejects with a typed ApiError('NOT_IMPLEMENTED', ...)". That was already
 * false — `contentPerformance.list` in src/lib/api.ts makes a real
 * `GET /analytics/creators/{creatorId}/media` call via `http.request` in live
 * mode (no NOT_IMPLEMENTED short-circuit exists there). The route itself
 * isn't live on the backend yet either — `AnalyticsController` only exposes
 * `/metrics`, `/scores`, `/demographics` today — so until Vikram ships it,
 * this live call 404s. `http.request` doesn't special-case that into an
 * `ApiError('NOT_IMPLEMENTED', ...)` (a 404 with no JSON envelope fails JSON
 * parsing and surfaces as `ApiError('NETWORK_ERROR', ...)` instead), so the
 * `notImplemented` branch below is dead until either the backend starts
 * returning a real `NOT_IMPLEMENTED` envelope or the route ships for real.
 * `ContentPerformanceItem` (src/lib/api.ts) is the FE's expected row shape —
 * confirm it against Vikram's real response once wiki/build/brand-fixes-backend.md
 * documents it; not yet written as of this pass.
 *
 * Same hook shape as the other analytics hooks: `error` carries whatever
 * `ApiError` the live call throws, with `notImplemented` set only for the
 * (currently unreachable) `NOT_IMPLEMENTED` code so the panel can render an
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
