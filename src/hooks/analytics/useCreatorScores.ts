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
import { api } from '@/lib/api';
import type { CreatorScores } from '@/lib/types';

export interface UseCreatorScoresResult {
  data: CreatorScores | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useCreatorScores(creatorId: string | undefined): UseCreatorScoresResult {
  const [data, setData] = useState<CreatorScores | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!creatorId) {
      setData(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await api.analytics.getCreatorScores(creatorId);
      setData(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load creator scores');
    } finally {
      setLoading(false);
    }
  }, [creatorId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, refresh };
}

export default useCreatorScores;
