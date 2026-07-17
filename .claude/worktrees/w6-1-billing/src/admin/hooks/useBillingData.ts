/**
 * INFLUORA ADMIN PANEL — Billing data hooks
 * Owner: Ananya (Frontend)
 * Reference: W6-1 — admin billing console backend wiring
 *
 * Backs `BillingConsole.tsx` with live data from AdminBillingController (Vikram).
 * Four hooks:
 * - useBillingMetrics() → GET /admin/billing/metrics (MRR/ARR/churn)
 * - useBillingSubscriptions() → GET /admin/billing/subscriptions (paginated list)
 * - useGrantComp() → POST /admin/billing/comp (mutation)
 * - useOverridePlan() → POST /admin/billing/override (mutation)
 *
 * Pattern: follows usePulseData (fetch hooks) and useDisputeResolve (mutation hooks).
 */

import { useCallback, useEffect, useState } from 'react';
import type {
  BillingMetrics,
  PaginatedSubscriptionResponse,
  CompSubscriptionRequest,
  OverrideSubscriptionRequest,
  AdminSubscriptionActionResult,
} from '../types/admin.types';
import { billingApi } from '../services/api-contracts';

// ============================================
// METRICS HOOK (fetch)
// ============================================

export interface UseBillingMetricsResult {
  data: BillingMetrics | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => void;
}

/**
 * Fetches billing metrics (MRR/ARR/churn) from the live backend.
 * Auto-fetches on mount. Call `refresh()` to refetch manually.
 */
export function useBillingMetrics(): UseBillingMetricsResult {
  const [data, setData] = useState<BillingMetrics | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [trigger, setTrigger] = useState(0);

  const refresh = useCallback(() => setTrigger((prev) => prev + 1), []);

  useEffect(() => {
    let cancelled = false;

    setIsLoading(true);
    setError(null);

    billingApi
      .getMetrics()
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load billing metrics');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load billing metrics');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [trigger]);

  return { data, isLoading, error, refresh };
}

// ============================================
// SUBSCRIPTIONS LIST HOOK (fetch with filters)
// ============================================

export interface UseBillingSubscriptionsFilters {
  status?: string;
  search?: string;
}

export interface UseBillingSubscriptionsResult {
  data: PaginatedSubscriptionResponse | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => void;
}

/**
 * Fetches paginated subscription list from the live backend.
 * Refetches whenever page/pageSize/filters change.
 */
export function useBillingSubscriptions(
  page: number,
  pageSize: number,
  filters: UseBillingSubscriptionsFilters
): UseBillingSubscriptionsResult {
  const [data, setData] = useState<PaginatedSubscriptionResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [trigger, setTrigger] = useState(0);

  const refresh = useCallback(() => setTrigger((prev) => prev + 1), []);

  useEffect(() => {
    let cancelled = false;

    setIsLoading(true);
    setError(null);

    billingApi
      .listSubscriptions(page, pageSize, filters.status, filters.search)
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
        } else {
          setData(null);
          setError(res.error ?? 'Failed to load subscriptions');
        }
      })
      .catch(() => {
        if (cancelled) return;
        setData(null);
        setError('Failed to load subscriptions');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [page, pageSize, filters.status, filters.search, trigger]);

  return { data, isLoading, error, refresh };
}

// ============================================
// GRANT COMP MUTATION HOOK
// ============================================

export interface UseGrantCompResult {
  data: AdminSubscriptionActionResult | null;
  isGranting: boolean;
  error: string | null;
  grantComp: (request: CompSubscriptionRequest) => Promise<AdminSubscriptionActionResult | null>;
  reset: () => void;
}

/**
 * Mutation hook for granting complimentary Pro subscriptions.
 * Pattern: matches useDisputeResolve — caller invokes `grantComp()` directly,
 * then refetches the list via `useBillingSubscriptions().refresh()` on success.
 */
export function useGrantComp(): UseGrantCompResult {
  const [data, setData] = useState<AdminSubscriptionActionResult | null>(null);
  const [isGranting, setIsGranting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const grantComp = useCallback(
    async (request: CompSubscriptionRequest): Promise<AdminSubscriptionActionResult | null> => {
      setIsGranting(true);
      setError(null);

      try {
        const res = await billingApi.grantComp(request);
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
          return res.data;
        }

        setError(res.error ?? 'Failed to grant comp subscription');
        return null;
      } catch {
        setError('Failed to grant comp subscription');
        return null;
      } finally {
        setIsGranting(false);
      }
    },
    []
  );

  const reset = useCallback(() => {
    setData(null);
    setError(null);
  }, []);

  return { data, isGranting, error, grantComp, reset };
}

// ============================================
// OVERRIDE PLAN MUTATION HOOK
// ============================================

export interface UseOverridePlanResult {
  data: AdminSubscriptionActionResult | null;
  isOverriding: boolean;
  error: string | null;
  overridePlan: (request: OverrideSubscriptionRequest) => Promise<AdminSubscriptionActionResult | null>;
  reset: () => void;
}

/**
 * Mutation hook for overriding a workspace's plan assignment.
 * Pattern: matches useDisputeResolve — caller invokes `overridePlan()` directly,
 * then refetches the list via `useBillingSubscriptions().refresh()` on success.
 */
export function useOverridePlan(): UseOverridePlanResult {
  const [data, setData] = useState<AdminSubscriptionActionResult | null>(null);
  const [isOverriding, setIsOverriding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const overridePlan = useCallback(
    async (request: OverrideSubscriptionRequest): Promise<AdminSubscriptionActionResult | null> => {
      setIsOverriding(true);
      setError(null);

      try {
        const res = await billingApi.overridePlan(request);
        if (res.success && res.data) {
          setData(res.data);
          setError(null);
          return res.data;
        }

        setError(res.error ?? 'Failed to override plan');
        return null;
      } catch {
        setError('Failed to override plan');
        return null;
      } finally {
        setIsOverriding(false);
      }
    },
    []
  );

  const reset = useCallback(() => {
    setData(null);
    setError(null);
  }, []);

  return { data, isOverriding, error, overridePlan, reset };
}
