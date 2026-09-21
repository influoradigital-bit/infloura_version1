/**
 * INFLUORA ADMIN PANEL — Creator Agent rate calibration data hook
 * Owner: Ananya (Frontend)
 * Reference: T-MEERA-CREATOR-PHASE-B (SPEC.md §14.1.g, B0-35) —
 * src/admin/pages/CreatorAgentBaselinesPage.tsx.
 *
 * Backed by `creatorAgentApi.getRateCalibration()`
 * (`GET /api/v1/admin/creator-agent/rate-calibration`, AdminCreatorAgentController).
 *
 * A sibling of `useCreatorAgentBaselines.ts` on purpose, and deliberately NOT an `api.ts`
 * namespace entry: the page that renders this fetches through hooks, and a second fetching
 * convention on one screen is how two callers end up with two different loading states for
 * one request. Single fetch-on-mount + `refresh()`, same as the baselines hook — there is no
 * filtering or pagination surface here, just a point-in-time snapshot the admin re-pulls.
 *
 * Deliberately its own request rather than a field on the baselines payload: the calibration
 * report reads a cross-tenant collaboration pool and the audit log, so it is a materially more
 * expensive query than the Phase A snapshot, and folding it in would make the whole page wait
 * on it (and fail with it).
 */

import { useCallback, useEffect, useState } from 'react';
import type { CreatorAgentRateCalibration } from '../types/admin.types';
import { creatorAgentApi } from '../services/api-contracts';

export interface UseCreatorAgentRateCalibrationResult {
  data: CreatorAgentRateCalibration | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => void;
}

export function useCreatorAgentRateCalibration(): UseCreatorAgentRateCalibrationResult {
  const [data, setData] = useState<CreatorAgentRateCalibration | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    creatorAgentApi
      .getRateCalibration()
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load rate calibration');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load rate calibration');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  return { data, isLoading, error, refresh };
}
