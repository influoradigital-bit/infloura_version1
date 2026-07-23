/**
 * useCreatorScores - brand-facing creator scores
 * ----------------------------------------------------------------------------
 * Backed by GET /analytics/creators/{creatorId}/scores (influora-api
 * AnalyticsController). Same hook shape as useCreatorMetrics.ts.
 *
 * brandSafetyScore/garmFlags/contentSentiment come back null from the real
 * backend today (BrandSafetyScoreService isn't built yet) — consumers must
 * render a "not yet available" state for those fields, never a fake score.
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { CreatorScores } from '@/lib/types';
import { CREATOR_ANALYTICS_SELF } from '@/hooks/analytics/useCreatorMetrics';

export interface UseCreatorScoresResult {
  data: CreatorScores | null;
  loading: boolean;
  error: string | null;
  /**
   * C27: true when the backend explicitly said "no score computed for this
   * creator yet" (404 SCORE_NOT_FOUND) — an honest empty state, not a load
   * failure. Consumers should show friendly "not enough activity yet" copy
   * for this case instead of a page-level error banner.
   */
  notFound: boolean;
  refresh: () => Promise<void>;
}

export function useCreatorScores(creatorId: string | undefined): UseCreatorScoresResult {
  const [data, setData] = useState<CreatorScores | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const refresh = useCallback(async () => {
    if (!creatorId) {
      setData(null);
      setNotFound(false);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    setNotFound(false);
    try {
      const result =
        creatorId === CREATOR_ANALYTICS_SELF
          ? await api.creatorAnalytics.getMyScores()
          : await api.analytics.getCreatorScores(creatorId);
      setData(result);
    } catch (err) {
      // C27: 404 SCORE_NOT_FOUND is an honest "not computed yet" state, not a
      // page-level load failure — metrics/demographics on the same page can
      // load fine even when no score exists yet for this creator.
      if (err instanceof ApiError && (err.status === 404 || err.code === 'SCORE_NOT_FOUND')) {
        setData(null);
        setNotFound(true);
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load creator scores');
      }
    } finally {
      setLoading(false);
    }
  }, [creatorId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, notFound, refresh };
}

export default useCreatorScores;
