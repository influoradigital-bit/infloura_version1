/**
 * INFLUORA ADMIN PANEL — Creator Agent Baselines data hook
 * Owner: Ananya (Frontend)
 * Reference: T-MEERA-CREATOR-PHASE-A (A1, fix round 1 item 2) —
 * src/admin/pages/CreatorAgentBaselinesPage.tsx.
 *
 * Backed by the live `creatorAgentApi.getBaselines()` call
 * (`GET /api/v1/admin/creator-agent/baselines`, AdminCreatorAgentController).
 * Single fetch-on-mount + `refresh()`, same shape as `useErrorLog.ts`'s
 * stats fetch — there is no filtering/pagination surface here, just a
 * point-in-time snapshot the admin can re-pull.
 */

import { useCallback, useEffect, useState } from 'react';
import type { CreatorAgentBaselines } from '../types/admin.types';
import { creatorAgentApi } from '../services/api-contracts';

export interface UseCreatorAgentBaselinesResult {
  data: CreatorAgentBaselines | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => void;
}

export function useCreatorAgentBaselines(): UseCreatorAgentBaselinesResult {
  const [data, setData] = useState<CreatorAgentBaselines | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    creatorAgentApi
      .getBaselines()
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load creator agent baselines');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load creator agent baselines');
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
