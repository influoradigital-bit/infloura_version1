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
 * BOUNDED: the poll also gives up after `ANALYSIS_POLL_BUDGET_MS`. Without a
 * bound, a profile that never leaves `PENDING`/`ANALYZING` — e.g. a brand-new
 * account that never ran site analysis, or a stuck backend job — would hit
 * `GET /meera/brand-profile` every 4s FOREVER in the background. `analyze_site`
 * targets <=45s, so ~2 min of polling is a generous ceiling.
 *
 * P1-13 — the give-up used to be SILENT. The budget was a poll *count*
 * (`dataUpdateCount >= 30`) consumed only by `refetchInterval`, so when it ran
 * out the hook simply stopped fetching and kept reporting `PENDING` with no
 * error, no flag and no way back: every consumer rendered a waiting state
 * forever and the brand was never told we had stopped. The budget is now a
 * wall-clock deadline that BOTH stops the poll and raises `analysisTimedOut`,
 * a real terminal state callers can render, plus `restartAnalysisPoll()` to mint a
 * fresh budget and fetch again. Raising the poll count instead would only have
 * made the silence longer.
 *
 * The deadline starts when analysis is first seen in flight (NOT at mount) —
 * a canvas that has been open for ten minutes before the brand pastes a URL
 * must still get a full window.
 *
 * Contract note: `brandProfile` / `isLoading` / `error` / `refetch` are
 * unchanged; the two new fields are purely additive, so the Meera canvas keeps
 * working exactly as before if it ignores them.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError } from '@/lib/api';
import { meeraApi, type MeeraBrandProfile } from '@/lib/meera-api';

export const brandProfileQueryKey = ['meera', 'brand-profile'] as const;

const POLL_INTERVAL_MS = 4000;
/**
 * Stop polling this long after analysis is first seen in flight, even if it is
 * still PENDING/ANALYZING (~2 min — the old 30 polls × 4s ceiling, expressed as
 * time so the deadline can also drive the terminal state below).
 */
export const ANALYSIS_POLL_BUDGET_MS = 120_000;

export interface UseBrandProfileResult {
  /** `null` while loading or on error — never a fabricated profile. */
  brandProfile: MeeraBrandProfile | null;
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
  /**
   * The backend was still `PENDING`/`ANALYZING` when the poll budget ran out —
   * we have stopped asking. A terminal state, not a loading state: render
   * something with a way forward, never a spinner.
   */
  analysisTimedOut: boolean;
  /**
   * Clear `analysisTimedOut`, mint a fresh poll budget and fetch immediately.
   * This re-checks the backend's status; it does NOT re-run `analyze_site`
   * (only `saveBrandCompany` and Meera's own tool call can do that), so callers
   * must not label it as re-reading the site.
   */
  restartAnalysisPoll: () => void;
}

export function useBrandProfile(): UseBrandProfileResult {
  const [analysisTimedOut, setAnalysisTimedOut] = useState(false);
  /** Bumped by `restartAnalysisPoll` to re-run the deadline effect below. */
  const [budgetEpoch, setBudgetEpoch] = useState(0);
  /** Wall-clock ms after which we stop asking. `null` = no analysis in flight yet. */
  const deadlineRef = useRef<number | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: brandProfileQueryKey,
    queryFn: () => meeraApi.getBrandProfile(),
    staleTime: 30 * 1000,
    retry: 1,
    refetchInterval: (query) => {
      const status = query.state.data?.analysisStatus;
      const inFlight = status === 'PENDING' || status === 'ANALYZING';
      if (!inFlight) return false;
      // `deadlineRef` is still null on the very first evaluation (the effect
      // that mints it runs after commit) — poll, the deadline lands next tick.
      if (deadlineRef.current !== null && Date.now() >= deadlineRef.current) return false;
      return POLL_INTERVAL_MS;
    },
  });

  const status = data?.analysisStatus;
  const inFlight = status === 'PENDING' || status === 'ANALYZING';

  useEffect(() => {
    if (!inFlight) {
      // Settled (READY/ERROR) or nothing fetched yet — no budget, no timeout.
      deadlineRef.current = null;
      setAnalysisTimedOut(false);
      return;
    }
    if (deadlineRef.current === null) {
      deadlineRef.current = Date.now() + ANALYSIS_POLL_BUDGET_MS;
    }
    const remaining = deadlineRef.current - Date.now();
    if (remaining <= 0) {
      setAnalysisTimedOut(true);
      return;
    }
    setAnalysisTimedOut(false);
    const timer = window.setTimeout(() => setAnalysisTimedOut(true), remaining);
    return () => window.clearTimeout(timer);
  }, [inFlight, budgetEpoch]);

  const restartAnalysisPoll = useCallback(() => {
    deadlineRef.current = null;
    setAnalysisTimedOut(false);
    setBudgetEpoch((epoch) => epoch + 1);
    void refetch();
  }, [refetch]);

  return {
    brandProfile: data ?? null,
    isLoading,
    error: error instanceof ApiError ? error.message : error ? "Couldn't load your brand profile." : null,
    refetch: () => {
      void refetch();
    },
    analysisTimedOut,
    restartAnalysisPoll,
  };
}

export default useBrandProfile;
