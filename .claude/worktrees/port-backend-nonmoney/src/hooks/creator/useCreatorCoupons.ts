/**
 * useCreatorCoupons - creator-facing "my coupons across all campaigns" list
 * ----------------------------------------------------------------------------
 * Backed by GET /creator/coupons (influora-api CreatorCouponController, Task #28).
 * Same { data, loading, error, refresh } shape as useCampaignCoupons.ts.
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type CreatorCouponResponse } from '@/lib/api';

export interface UseCreatorCouponsResult {
  data: CreatorCouponResponse[];
  loading: boolean;
  error: string | null;
  /** True when the backend answered NOT_IMPLEMENTED — lets the page show an honest gap banner. */
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
      const result = await api.creatorCoupons.list();
      setData(result);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'NOT_IMPLEMENTED') {
        setNotImplemented(true);
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
