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
 *
 * BOUNDED (fix): the poll also gives up after `MAX_POLLS`. Without this, a
 * profile that never leaves `PENDING`/`ANALYZING` — e.g. a brand-new account
 * that never ran site analysis, or a stuck backend job — would hit
 * `GET /meera/brand-profile` every 4s FOREVER in the background. `analyze_site`
 * targets <=45s, so ~2 min of polling is a generous ceiling; past that the
 * state isn't going to change on its own and the constant request is pure waste.
 */

import { useQuery } from '@tanstack/react-query';
import { ApiError } from '@/lib/api';
import { meeraApi, type MeeraBrandProfile } from '@/lib/meera-api';

export const brandProfileQueryKey = ['meera', 'brand-profile'] as const;

const POLL_INTERVAL_MS = 4000;
/** Stop polling after this many fetches even if still PENDING/ANALYZING (~2 min at 4s). */
const MAX_POLLS = 30;

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
      const inFlight = status === 'PENDING' || status === 'ANALYZING';
      // dataUpdateCount increments on every successful fetch (initial + each
      // poll); once we've fetched MAX_POLLS times and it's still not settled,
      // give up instead of hammering the endpoint indefinitely.
      if (query.state.dataUpdateCount >= MAX_POLLS) return false;
      return inFlight ? POLL_INTERVAL_MS : false;
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
