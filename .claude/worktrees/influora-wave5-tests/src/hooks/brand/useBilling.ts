/**
 * useBilling — brand billing settings live data (Task 26, Phase 5)
 * ----------------------------------------------------------------------------
 * Backed by `GET /api/v1/billing/{plan,usage,invoices}` (BillingController.java).
 *
 * `staleTime: 0` is deliberate, not a default left in place — Priya's audit note
 * (SUBSCRIPTION-BILLING-PLAN.md §0.5) flags that `Plan.aiMonthlyAllotment` only
 * reconciles server-side on subscription lifecycle webhooks, not continuously, so
 * a client-side cache can show a stale allotment across an upgrade/downgrade.
 * Every mount/focus refetches instead of serving a cached value.
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { BillingPlanStatus, BillingUsageSummary, BillingInvoice } from '@/lib/api';

export const billingPlanQueryKey = ['billing', 'plan'] as const;
export const billingUsageQueryKey = ['billing', 'usage'] as const;
export const billingInvoicesQueryKey = ['billing', 'invoices'] as const;

export interface UseBillingResult {
  plan: BillingPlanStatus | null;
  usage: BillingUsageSummary | null;
  invoices: BillingInvoice[];
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
}

export function useBilling(): UseBillingResult {
  const planQuery = useQuery({
    queryKey: billingPlanQueryKey,
    queryFn: () => api.billing.getPlan(),
    staleTime: 0,
    refetchOnWindowFocus: true,
    retry: 1,
  });

  const usageQuery = useQuery({
    queryKey: billingUsageQueryKey,
    queryFn: () => api.billing.getUsage(),
    staleTime: 0,
    refetchOnWindowFocus: true,
    retry: 1,
  });

  const invoicesQuery = useQuery({
    queryKey: billingInvoicesQueryKey,
    queryFn: () => api.billing.getInvoices(),
    staleTime: 0,
    retry: 1,
  });

  const error = planQuery.error || usageQuery.error || invoicesQuery.error;

  return {
    plan: planQuery.data ?? null,
    usage: usageQuery.data ?? null,
    invoices: invoicesQuery.data ?? [],
    isLoading: planQuery.isLoading || usageQuery.isLoading || invoicesQuery.isLoading,
    error: error ? String(error) : null,
    refetch: () => {
      void planQuery.refetch();
      void usageQuery.refetch();
      void invoicesQuery.refetch();
    },
  };
}

export default useBilling;
