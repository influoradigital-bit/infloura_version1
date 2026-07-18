/**
 * useCreatorCoupons - creator-facing "my coupons across all campaigns" list
 * ----------------------------------------------------------------------------
 * Backed by GET /creator/coupons (influora-api CreatorCouponController, Task #28).
 * Same { data, loading, error, refresh } shape as useCampaignCoupons.ts.
 *
 * (2026-07-17: dropped the `notImplemented` flag — it keyed on a
 * NOT_IMPLEMENTED error that `api.creatorCoupons.list` stopped throwing when
 * the endpoint shipped, so the page's "API not yet available" banner was
 * dead code that misdescribed a working feature.)
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type CreatorCouponResponse } from '@/lib/api';

export interface UseCreatorCouponsResult {
  data: CreatorCouponResponse[];
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useCreatorCoupons(): UseCreatorCouponsResult {
  const [data, setData] = useState<CreatorCouponResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.creatorCoupons.list();
      setData(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load coupons');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, loading, error, refresh };
}

export default useCreatorCoupons;
