/**
 * INFLUORA ADMIN PANEL — Campaign Detail data hook
 * Owner: Ananya (Frontend)
 * Reference: Wire-up pass — campaign detail drawer (CampaignTable.tsx)
 *
 * Backed by the live `campaignApi.getById(id)` call
 * (`GET /api/v1/admin/campaigns/{id}`, AdminCampaignController). Same
 * fetch-on-id-change shape as `useBrandDetail.ts`/`useCreatorDetail.ts` —
 * `refresh()` is exposed for parity with that pattern, though this detail
 * view is currently read-only (no mutation endpoints exist yet on
 * `CampaignDetail`).
 */

import { useCallback, useEffect, useState } from 'react';
import type { CampaignDetail } from '../types/admin.types';
import { campaignApi } from '../services/api-contracts';

// ============================================
// HOOK
// ============================================

export interface UseCampaignDetailResult {
  data: CampaignDetail | null;
  isLoading: boolean;
  error: string | null;
  /** Re-fetch the campaign. */
  refresh: () => void;
}

/**
 * Returns full campaign detail for the admin campaign drawer from the live backend.
 */
export function useCampaignDetail(campaignId: string | undefined): UseCampaignDetailResult {
  const [data, setData] = useState<CampaignDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    if (!campaignId) {
      setData(null);
      setIsLoading(false);
      setError(null);
      return;
    }

    let cancelled = false;
    setIsLoading(true);
    setError(null);

    campaignApi
      .getById(campaignId)
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load campaign');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load campaign');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [campaignId, reloadKey]);

  return { data, isLoading, error, refresh };
}
