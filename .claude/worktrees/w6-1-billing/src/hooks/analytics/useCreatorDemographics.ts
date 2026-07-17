/**
 * useCreatorDemographics - brand-facing audience demographics
 * ----------------------------------------------------------------------------
 * Backed by GET /analytics/creators/{creatorId}/demographics (influora-api
 * AnalyticsController, Wave B task B4). Same hook shape as
 * useCreatorMetrics.ts / useCreatorScores.ts — { data, loading, error, refresh }.
 *
 * `data.hasData === false` is a graceful, expected steady state (no snapshot
 * yet from the weekly AudienceDemographicsJob) — consumers must render an
 * explicit "will appear after the first sync" message for that case, not an
 * error and not fabricated zeros.
 */

import { useCallback, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { CreatorDemographics } from '@/lib/types';
import { CREATOR_ANALYTICS_SELF } from '@/hooks/analytics/useCreatorMetrics';

export interface UseCreatorDemographicsResult {
  data: CreatorDemographics | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useCreatorDemographics(
  creatorId: string | undefined,
): UseCreatorDemographicsResult {
  const [data, setData] = useState<CreatorDemographics | null>(null);
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
      const result =
        creatorId === CREATOR_ANALYTICS_SELF
          ? await api.creatorAnalytics.getMyDemographics()
          : await api.analytics.getCreatorDemographics(creatorId);
      setData(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load audience demographics');
    } finally {
      setLoading(false);
    }
  }, [creatorId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, refresh };
}

export default useCreatorDemographics;
