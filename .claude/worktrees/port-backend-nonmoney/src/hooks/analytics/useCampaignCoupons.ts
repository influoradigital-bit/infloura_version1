/**
 * useCampaignCoupons - brand-facing coupon codes for one campaign
 * ----------------------------------------------------------------------------
 * Backed by GET/POST /campaigns/{campaignId}/coupons (influora-api
 * CampaignTrackingController, Wave A task A1). Same { data, loading, error,
 * refresh } + separate `create` shape as useCampaignTrackingLinks.ts.
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type CouponResponse, type CreateCouponPayload } from '@/lib/api';

export interface UseCampaignCouponsResult {
  data: CouponResponse[];
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  create: (payload: CreateCouponPayload) => Promise<CouponResponse>;
  creating: boolean;
  createError: string | null;
}

export function useCampaignCoupons(campaignId: string | undefined): UseCampaignCouponsResult {
  const [data, setData] = useState<CouponResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!campaignId) {
      setData([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await api.campaignTracking.listCoupons(campaignId);
      setData(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load coupons');
    } finally {
      setLoading(false);
    }
  }, [campaignId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const create = useCallback(
    async (payload: CreateCouponPayload) => {
      if (!campaignId) throw new Error('No campaign selected');
      setCreating(true);
      setCreateError(null);
      try {
        const created = await api.campaignTracking.createCoupon(campaignId, payload);
        setData((prev) => {
          const withoutExisting = prev.filter((coupon) => coupon.id !== created.id);
          return [created, ...withoutExisting];
        });
        return created;
      } catch (err) {
        const message = err instanceof ApiError ? err.message : 'Failed to create coupon';
        setCreateError(message);
        throw err;
      } finally {
        setCreating(false);
      }
    },
    [campaignId],
  );

  return { data, loading, error, refresh, create, creating, createError };
}

export default useCampaignCoupons;
