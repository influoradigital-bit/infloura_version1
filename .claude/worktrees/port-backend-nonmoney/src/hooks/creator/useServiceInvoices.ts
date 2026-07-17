/**
 * useServiceInvoices — creator-facing D14 marketplace invoice reads
 * ----------------------------------------------------------------------------
 * Doc#2 (`campaign_service_invoices` — the creator's own earnings invoice,
 * Creator -> Brand) and Doc#3b (`platform_commission_invoices`, leg CREATOR —
 * Influora's commission invoice to the creator). Backed by
 * `CreatorInvoicingController` (`GET /creator/campaign-invoices`,
 * `GET /creator/commission-invoices`) via `creatorInvoicing` in `src/lib/api.ts`.
 *
 * Same `{ data, loading, error, refresh }` shape as `useAffiliateEarnings.ts` /
 * `useCreatorCoupons.ts`, extended to two parallel lists since the creator
 * earnings area (Wallet) shows both documents together.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  ApiError,
  creatorInvoicing,
  type CampaignServiceInvoice,
  type PlatformCommissionInvoice,
} from '@/lib/api';

export interface UseServiceInvoicesResult {
  campaignInvoices: CampaignServiceInvoice[];
  commissionInvoices: PlatformCommissionInvoice[];
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useServiceInvoices(): UseServiceInvoicesResult {
  const [campaignInvoices, setCampaignInvoices] = useState<CampaignServiceInvoice[]>([]);
  const [commissionInvoices, setCommissionInvoices] = useState<PlatformCommissionInvoice[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [campaign, commission] = await Promise.all([
        creatorInvoicing.getCampaignInvoices(),
        creatorInvoicing.getCommissionInvoices(),
      ]);
      setCampaignInvoices(campaign);
      setCommissionInvoices(commission);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load invoices');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { campaignInvoices, commissionInvoices, loading, error, refresh };
}

export default useServiceInvoices;
