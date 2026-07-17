/**
 * INFLUORA ADMIN PANEL — Creator Detail data hook
 * Owner: Ananya (Frontend) · live-wired P1-WIRE-3
 *
 * Backed by the live `creatorApi.getById(id)` call
 * (`GET /api/v1/admin/creators/{id}`, implemented in AdminCreatorController).
 * The previous mock has been removed. Public `UseCreatorDetailResult` shape is
 * otherwise unchanged so most of `CreatorProfile` doesn't need to change;
 * `refresh()` lets action handlers (application review / suspend / reinstate /
 * force Instagram re-auth) re-pull the record after a mutation. Same pattern
 * as `useBrandDetail.ts`.
 *
 * MODERATION FIELDS: `CreatorDetail` (src/admin/types/admin.types.ts, Priya-owned)
 * carries `flaggedContentCount` — a server-side rollup of open `ContentFlag`
 * rows for this creator.
 *
 * SECURITY: this surface is moderation/compliance-sensitive — server-side
 * re-authorization (AdminContextService role + MFA gate) is the source of
 * truth; this hook only reads. PENDING Kabir review before ship.
 */

import { useCallback, useEffect, useState } from 'react';
import type { CreatorDetail } from '../types/admin.types';
import { creatorApi } from '../services/api-contracts';

// ============================================
// HOOK
// ============================================

export interface UseCreatorDetailResult {
  data: CreatorDetail | null;
  isLoading: boolean;
  error: string | null;
  /** Re-fetch the creator (e.g. after an application/suspend/reinstate/reauth action). */
  refresh: () => void;
}

/**
 * Returns full creator detail for the admin creator profile view from the live backend.
 */
export function useCreatorDetail(creatorId: string | undefined): UseCreatorDetailResult {
  const [data, setData] = useState<CreatorDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    if (!creatorId) {
      setData(null);
      setIsLoading(false);
      setError(null);
      return;
    }

    let cancelled = false;
    const controller = new AbortController();
    setIsLoading(true);
    setError(null);

    creatorApi
      .getById(creatorId, controller.signal)
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load creator');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load creator');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [creatorId, reloadKey]);

  return { data, isLoading, error, refresh };
}
