/**
 * useAffiliateEarnings - creator-facing affiliate commission list + summary
 * ----------------------------------------------------------------------------
 * A-GA-4 / V-GA-7: live mode calls GET /creator/affiliate-earnings once via
 * `api.affiliateEarnings.get()` and returns both rows + summary.
 * `notImplemented` is retained for defensive UI if a future gap reappears.
 *
 * Same { data, loading, error, refresh } shape as useCreatorCoupons.ts, plus
 * a `summary` field for the four headline stat cards.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  api,
  ApiError,
  type AffiliateEarningRow,
  type AffiliateEarningsSummary,
} from '@/lib/api';

export interface UseAffiliateEarningsResult {
  data: AffiliateEarningRow[];
  summary: AffiliateEarningsSummary | null;
  loading: boolean;
  error: string | null;
  /** True when the failure is specifically a missing backend endpoint, not a transient error. */
  notImplemented: boolean;
  refresh: () => Promise<void>;
}

export function useAffiliateEarnings(): UseAffiliateEarningsResult {
  const [data, setData] = useState<AffiliateEarningRow[]>([]);
  const [summary, setSummary] = useState<AffiliateEarningsSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notImplemented, setNotImplemented] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    setNotImplemented(false);
    try {
      const result = await api.affiliateEarnings.get();
      setData(result.earnings);
      setSummary(result.summary);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'NOT_IMPLEMENTED') {
        setNotImplemented(true);
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to load affiliate earnings');
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, summary, loading, error, notImplemented, refresh };
}

export default useAffiliateEarnings;
