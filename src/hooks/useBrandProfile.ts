/**
 * useBrandProfile — persisted Meera brand/site-analysis profile
 * ----------------------------------------------------------------------------
 * Backed by `GET /meera/brand-profile` (`meeraApi.getBrandProfile`, 02 §1.7).
 * Uses `@tanstack/react-query` per TECH-STACK.md's data-fetching layer, same
 * shape as `useTrendSparkNudge` — this is StageSnapshot's primary live data
 * source (persisted across sessions), independent of whether an `analyze_site`
 * tool_result happened to fire in the *current* chat session.
 *
 * Polls while analysis is in flight (`PENDING`/`ANALYZING`) and stops once the
 * backend settles on `READY`/`ERROR`, so the canvas catches up even if no SSE
 * event drives a refetch.
 */

import { useQuery } from '@tanstack/react-query';
import { ApiError } from '@/lib/api';
import { meeraApi, type MeeraBrandProfile } from '@/lib/meera-api';

export const brandProfileQueryKey = ['meera', 'brand-profile'] as const;

const POLL_INTERVAL_MS = 4000;

export interface UseBrandProfileResult {
  /** `null` while loading or on error — never a fabricated profile. */
  brandProfile: MeeraBrandProfile | null;
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
}

export function useBrandProfile(): UseBrandProfileResult {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: brandProfileQueryKey,
    queryFn: () => meeraApi.getBrandProfile(),
    staleTime: 30 * 1000,
    retry: 1,
    refetchInterval: (query) => {
      const status = query.state.data?.analysisStatus;
      return status === 'PENDING' || status === 'ANALYZING' ? POLL_INTERVAL_MS : false;
    },
  });

  return {
    brandProfile: data ?? null,
    isLoading,
    error: error instanceof ApiError ? error.message : error ? "Couldn't load your brand profile." : null,
    refetch: () => {
      void refetch();
    },
  };
}

export default useBrandProfile;
