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
import { api, brandInvoicing } from '@/lib/api';
import type {
  BillingPlanStatus,
  BillingUsageSummary,
  BillingInvoice,
  CampaignServiceInvoice,
  PlatformCommissionInvoice,
} from '@/lib/api';

export const billingPlanQueryKey = ['billing', 'plan'] as const;
export const billingUsageQueryKey = ['billing', 'usage'] as const;
export const billingInvoicesQueryKey = ['billing', 'invoices'] as const;
/** D14 — Doc#2, creator service invoices billed to this workspace. */
export const billingCampaignInvoicesQueryKey = ['billing', 'campaign-invoices'] as const;
/** D14 — Doc#3a, Influora's commission invoice to this workspace. */
export const billingCommissionInvoicesQueryKey = ['billing', 'commission-invoices'] as const;

export interface UseBillingResult {
  plan: BillingPlanStatus | null;
  usage: BillingUsageSummary | null;
  invoices: BillingInvoice[];
  /** D14 Doc#2 — creator service invoices this workspace was billed, most recent first. */
  campaignInvoices: CampaignServiceInvoice[];
  /** D14 Doc#3a — Influora's commission invoices to this workspace, most recent first. */
  commissionInvoices: PlatformCommissionInvoice[];
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

  const campaignInvoicesQuery = useQuery({
    queryKey: billingCampaignInvoicesQueryKey,
    queryFn: () => brandInvoicing.getCampaignInvoices(),
    staleTime: 0,
    retry: 1,
  });

  const commissionInvoicesQuery = useQuery({
    queryKey: billingCommissionInvoicesQueryKey,
    queryFn: () => brandInvoicing.getCommissionInvoices(),
    staleTime: 0,
    retry: 1,
  });

  const error =
    planQuery.error ||
    usageQuery.error ||
    invoicesQuery.error ||
    campaignInvoicesQuery.error ||
    commissionInvoicesQuery.error;

  return {
    plan: planQuery.data ?? null,
    usage: usageQuery.data ?? null,
    invoices: invoicesQuery.data ?? [],
    campaignInvoices: campaignInvoicesQuery.data ?? [],
    commissionInvoices: commissionInvoicesQuery.data ?? [],
    isLoading:
      planQuery.isLoading ||
      usageQuery.isLoading ||
      invoicesQuery.isLoading ||
      campaignInvoicesQuery.isLoading ||
      commissionInvoicesQuery.isLoading,
    error: error ? String(error) : null,
    refetch: () => {
      void planQuery.refetch();
      void usageQuery.refetch();
      void invoicesQuery.refetch();
      void campaignInvoicesQuery.refetch();
      void commissionInvoicesQuery.refetch();
    },
  };
}

export default useBilling;
