/**
 * useCreatorOwnMedia — the authenticated creator's own per-post content performance.
 * ----------------------------------------------------------------------------
 * Wraps GET /creator/analytics/me/media (creator-missing-0804) — the principal-scoped
 * self-service mirror of the brand-facing `/analytics/creators/:id/media`. Feeds
 * ContentPerformancePanel in creator-analytics.
 */

import { useCallback, useEffect, useState } from 'react';
import { ApiError, creatorAnalytics, type ContentPerformanceItem } from '@/lib/api';

export interface UseCreatorOwnMediaResult {
  data: ContentPerformanceItem[];
  loading: boolean;
  error: string | null;
  reload: () => void;
}

export function useCreatorOwnMedia(): UseCreatorOwnMediaResult {
  const [data, setData] = useState<ContentPerformanceItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await creatorAnalytics.getMyMedia());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load your content performance');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return { data, loading, error, reload: () => void load() };
}

export default useCreatorOwnMedia;
