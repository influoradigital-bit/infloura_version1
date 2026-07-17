/**
 * useCreatorCoupons - creator-facing "My Coupons" across all campaigns
 * ----------------------------------------------------------------------------
 * Backed by GET /creator/coupons, which is NOT yet built on the backend (no
 * creator-authed read path exists for a creator's coupons across campaigns —
 * see the gap note in src/lib/api.ts above `creatorCoupons`). In live mode
 * `api.creatorCoupons.listMine()` rejects with a NOT_IMPLEMENTED ApiError,
 * surfaced here as `notImplemented` so the page can render an explicit
 * "API not yet available" banner rather than a silent empty state. In mock
 * mode (VITE_API_MODE !== 'live') it returns clearly-labeled illustrative rows.
 *
 * Same { data, loading, error, refresh } convention as the analytics hooks.
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type CreatorCouponResponse } from '@/lib/api';

export interface UseCreatorCouponsResult {
  data: CreatorCouponResponse[];
  loading: boolean;
  error: string | null;
  notImplemented: boolean;
  refresh: () => Promise<void>;
}

export function useCreatorCoupons(): UseCreatorCouponsResult {
  const [data, setData] = useState<CreatorCouponResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notImplemented, setNotImplemented] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    setNotImplemented(false);
    try {
      const result = await api.creatorCoupons.listMine();
      setData(result);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'NOT_IMPLEMENTED') {
        setNotImplemented(true);
        setData([]);
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to load coupons');
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, notImplemented, refresh };
}

export default useCreatorCoupons;
