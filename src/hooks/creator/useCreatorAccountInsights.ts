/**
 * useCreatorAccountInsights — the authenticated creator's own account numbers, last 28 full days.
 * ----------------------------------------------------------------------------
 * Wraps GET /creator/analytics/me/account-insights (2026-09-24). Feeds AccountInsightsCard on
 * creator-analytics. Same shape as useCreatorOwnMedia.
 */

import { useCallback, useEffect, useState } from 'react';
import { ApiError, creatorAnalytics, type CreatorAccountInsights } from '@/lib/api';

export interface UseCreatorAccountInsightsResult {
  data: CreatorAccountInsights | null;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

export function useCreatorAccountInsights(): UseCreatorAccountInsightsResult {
  const [data, setData] = useState<CreatorAccountInsights | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await creatorAnalytics.getMyAccountInsights());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load your account numbers');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { data, loading, error, reload: () => void load() };
}

export default useCreatorAccountInsights;
