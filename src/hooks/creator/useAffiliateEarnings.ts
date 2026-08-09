/**
 * useAffiliateEarnings - creator-facing affiliate commission list + summary
 * ----------------------------------------------------------------------------
 * A-GA-4 / V-GA-7: live mode calls GET /creator/affiliate-earnings once via
 * `api.affiliateEarnings.get()` and returns both rows + summary.
 * `notImplemented` is retained for defensive UI if a future gap reappears.
 *
 * CR-83 — the endpoint now pages (see api.ts CreatorAffiliateEarningsResponse);
 * `loadMore` appends the next page onto `data` rather than replacing it, and
 * `hasMore`/`loadingMore` drive the view's "Load more" affordance. `refresh`
 * still resets back to page 0, same as before.
 *
 * Same { data, loading, error, refresh } shape as useCreatorCoupons.ts, plus
 * a `summary` field for the four headline stat cards.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  api,
  ApiError,
  type AffiliateEarningRow,
  type AffiliateEarningsSummary,
} from '@/lib/api';

const PAGE_SIZE = 20;

export interface UseAffiliateEarningsResult {
  data: AffiliateEarningRow[];
  summary: AffiliateEarningsSummary | null;
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  error: string | null;
  /** True when the failure is specifically a missing backend endpoint, not a transient error. */
  notImplemented: boolean;
  refresh: () => Promise<void>;
  loadMore: () => Promise<void>;
}

export function useAffiliateEarnings(): UseAffiliateEarningsResult {
  const [data, setData] = useState<AffiliateEarningRow[]>([]);
  const [summary, setSummary] = useState<AffiliateEarningsSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notImplemented, setNotImplemented] = useState(false);
  const nextPageRef = useRef(0);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    setNotImplemented(false);
    try {
      const result = await api.affiliateEarnings.get(0, PAGE_SIZE);
      setData(result.earnings);
      setSummary(result.summary);
      setHasMore(result.hasMore);
      nextPageRef.current = 1;
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

  const loadMore = useCallback(async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    try {
      const result = await api.affiliateEarnings.get(nextPageRef.current, PAGE_SIZE);
      setData((prev) => [...prev, ...result.earnings]);
      setSummary(result.summary);
      setHasMore(result.hasMore);
      nextPageRef.current += 1;
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load more earnings');
    } finally {
      setLoadingMore(false);
    }
  }, [loadingMore, hasMore]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { data, summary, loading, loadingMore, hasMore, error, notImplemented, refresh, loadMore };
}

export default useAffiliateEarnings;
