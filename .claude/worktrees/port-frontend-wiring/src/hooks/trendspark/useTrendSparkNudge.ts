/**
 * useTrendSparkNudge — brand-facing Trend-Spark AI soft nudge (T7)
 * ----------------------------------------------------------------------------
 * Backed by `GET /api/v1/brand/trendspark/nudge` (TrendSparkController.java,
 * Vikram T4). Uses `@tanstack/react-query` per TECH-STACK.md's data-fetching
 * layer.
 *
 * Fail-closed by design (Snapsby-TrendSpark-AI-Spec.md §5b anti-spam gate):
 * a 204 response, a fetch error, or the query simply not having resolved yet
 * all resolve to `nudge: null` here — `TrendSparkNudgeCard` renders nothing
 * in every one of those cases. There is no fallback/demo nudge fabricated on
 * error; silence is the safe default, never a guess.
 */

import { useCallback, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '@/lib/api';
import type { TrendSparkNudge } from '@/lib/api';

export const trendSparkNudgeQueryKey = ['trendspark', 'nudge'] as const;

export interface UseTrendSparkNudgeResult {
  /** `null` while loading, on error, or when the backend has nothing to say (204). */
  nudge: TrendSparkNudge | null;
  isLoading: boolean;
  /** Human-readable message for the rare unexpected-failure case. Never surfaced as a
   *  blocking UI — the card just stays hidden (fail-closed). */
  error: string | null;
  /** Fires the click callback for the current nudge. No-ops if there is no nudge. */
  recordClick: () => void;
  /** Fires the purchase callback for the current nudge. No-ops if there is no nudge. */
  recordPurchase: (videoId?: string) => void;
}

export function useTrendSparkNudge(): UseTrendSparkNudgeResult {
  const queryClient = useQueryClient();

  const { data, isLoading, error } = useQuery({
    queryKey: trendSparkNudgeQueryKey,
    queryFn: () => api.trendspark.getNudge(),
    staleTime: 5 * 60 * 1000, // 5 min — a soft, low-frequency nudge, not a live feed
    retry: 1,
  });

  const clickMutation = useMutation({
    mutationFn: (nudgeId: string) => api.trendspark.postNudgeClick(nudgeId),
  });

  const purchaseMutation = useMutation({
    mutationFn: ({ nudgeId, videoId }: { nudgeId: string; videoId?: string }) =>
      api.trendspark.postNudgePurchase(nudgeId, videoId),
    onSuccess: () => {
      // Purchasing resolves the gap the nudge existed for — don't keep showing it.
      queryClient.setQueryData(trendSparkNudgeQueryKey, null);
    },
  });

  const nudge = data ?? null;

  const recordClick = useCallback(() => {
    if (!nudge) return;
    clickMutation.mutate(nudge.nudgeId);
  }, [nudge, clickMutation]);

  const recordPurchase = useCallback(
    (videoId?: string) => {
      if (!nudge) return;
      purchaseMutation.mutate({ nudgeId: nudge.nudgeId, videoId });
    },
    [nudge, purchaseMutation],
  );

  return useMemo(
    () => ({
      nudge,
      isLoading,
      error: error instanceof ApiError ? error.message : error ? 'Could not load suggestion.' : null,
      recordClick,
      recordPurchase,
    }),
    [nudge, isLoading, error, recordClick, recordPurchase],
  );
}

export default useTrendSparkNudge;
