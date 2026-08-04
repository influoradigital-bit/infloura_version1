/**
 * INFLUORA ADMIN PANEL — Finance / Escrow / Revenue Console data hook
 * Owner: Ananya (Frontend)
 *
 * Backs the Finance console (src/admin/components/finance/FinanceConsole.tsx,
 * mounted at /admin/revenue). Pulls together the platform's revenue-adjacent
 * read surfaces in one parallel fetch, same `Promise.all` + `cancelled` guard +
 * `reloadKey`-driven `refresh()` shape as `useErrorLog.ts` / `useFeeConfig.ts`:
 *
 * - `dashboardApi.getFinancialSummary(period)` — trailing 12 revenue buckets at
 *   the requested granularity (GET /admin/dashboard/financial). NOTE: despite
 *   the task brief calling this `financeApi.getFinancialSummary`, the actual
 *   wrapper lives on `dashboardApi` (api-contracts.ts:154) — verified against
 *   AdminDashboardController before wiring; `financeApi` has no such method.
 * - `financeApi.getEscrowSummary()` — locked/pending/flagged escrow totals
 *   (GET /admin/finance/escrow, AdminFinanceController).
 * - `escrowApi.getFlagged()` — flagged escrow rows (GET /admin/escrow/flagged,
 *   AdminEscrowController).
 * - `campaignApi.getAtRisk()` — campaigns whose SLA/timeline is at risk
 *   (GET /admin/campaigns/at-risk).
 * - `campaignApi.getHypeOps()` — HYPE campaign ops snapshot
 *   (GET /admin/campaigns/hype/ops).
 * - `moderationApi.getSuspensions()` — currently-suspended accounts
 *   (GET /admin/moderation/suspensions). The `status` query param is part of
 *   the wire contract but has no server-side effect yet (every row's
 *   `appealStatus` is `NONE` until the appeal subsystem exists — see
 *   `AdminModerationController.listSuspensions` javadoc), so it is
 *   intentionally not sent as a dead filter here.
 * - `marketingApi.getReputation()` — platform reputation score
 *   (GET /admin/marketing/reputation, AdminMarketingController).
 *
 * - `marketingApi.getGrowth()` — data-backed Growth subset: funnel signups/
 *   first-campaign/repeat-campaign counts + creator-application→approval and
 *   brand-signup→first-campaign conversion rates (GET /admin/marketing/growth,
 *   admin-backend-0804). cohortRetention/referralStats/profileComplete are
 *   omitted by the backend (no backing data) and render as "not tracked yet".
 *
 * All eight are live, backend-verified GET routes. None of the remaining
 * `unavailable()` stubs (retryPayout, getReconciliation, getTdsReport, escrow
 * hold/release/refund, getAcquisition, reviewAppeal, suspension mutate) are
 * touched here — this hook is read-only.
 */

import { useCallback, useEffect, useState } from 'react';
import type {
  AccountSuspension,
  CampaignSummary,
  EscrowSummary,
  GrowthMetrics,
  PlatformReputationScore,
  RevenueSnapshot,
} from '../types/admin.types';
import { campaignApi, dashboardApi, escrowApi, financeApi, marketingApi, moderationApi } from '../services/api-contracts';

export type FinancialPeriod = 'day' | 'week' | 'month' | 'quarter';

/** Matches the anonymous return shape of `escrowApi.getFlagged()` (api-contracts.ts:466-474). */
export interface FlaggedEscrowRow {
  id: string;
  campaignId: string;
  campaignName: string;
  amount: number;
  flagReason: string;
  createdAt: string;
}

/** Matches the anonymous return shape of `campaignApi.getHypeOps()` (api-contracts.ts:337-344). */
export interface HypeOpsSnapshot {
  activeHype: number;
  totalSlots: number;
  filledSlots: number;
  avgFillRate: number;
  approvalsPerHour: number;
}

export interface UseFinanceConsoleResult {
  revenue: RevenueSnapshot[];
  escrowSummary: EscrowSummary | null;
  flaggedEscrows: FlaggedEscrowRow[];
  atRiskCampaigns: CampaignSummary[];
  hypeOps: HypeOpsSnapshot | null;
  suspensions: AccountSuspension[];
  reputation: PlatformReputationScore | null;
  /** Data-backed subset (funnel counts + 2 conversion rates); cohortRetention/referralStats omitted by backend. */
  growth: GrowthMetrics | null;
  isLoading: boolean;
  /** Per-panel errors so one failing endpoint doesn't blank out the rest of the console. */
  errors: {
    revenue?: string;
    escrowSummary?: string;
    flaggedEscrows?: string;
    atRiskCampaigns?: string;
    hypeOps?: string;
    suspensions?: string;
    reputation?: string;
    growth?: string;
  };
  refresh: () => void;
}

/**
 * Returns the admin Finance console's data from the live backend, refetching
 * the revenue trend whenever `period` changes and everything on `refresh()`.
 */
export function useFinanceConsole(period: FinancialPeriod): UseFinanceConsoleResult {
  const [revenue, setRevenue] = useState<RevenueSnapshot[]>([]);
  const [escrowSummary, setEscrowSummary] = useState<EscrowSummary | null>(null);
  const [flaggedEscrows, setFlaggedEscrows] = useState<FlaggedEscrowRow[]>([]);
  const [atRiskCampaigns, setAtRiskCampaigns] = useState<CampaignSummary[]>([]);
  const [hypeOps, setHypeOps] = useState<HypeOpsSnapshot | null>(null);
  const [suspensions, setSuspensions] = useState<AccountSuspension[]>([]);
  const [reputation, setReputation] = useState<PlatformReputationScore | null>(null);
  const [growth, setGrowth] = useState<GrowthMetrics | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [errors, setErrors] = useState<UseFinanceConsoleResult['errors']>({});
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);

    Promise.all([
      dashboardApi.getFinancialSummary(period),
      financeApi.getEscrowSummary(),
      escrowApi.getFlagged(),
      campaignApi.getAtRisk(),
      campaignApi.getHypeOps(),
      moderationApi.getSuspensions(),
      marketingApi.getReputation(),
      marketingApi.getGrowth(),
    ])
      .then(
        ([
          revenueRes,
          escrowSummaryRes,
          flaggedRes,
          atRiskRes,
          hypeOpsRes,
          suspensionsRes,
          reputationRes,
          growthRes,
        ]) => {
          if (cancelled) return;

          const nextErrors: UseFinanceConsoleResult['errors'] = {};

          if (revenueRes.success && revenueRes.data) {
            setRevenue(revenueRes.data);
          } else {
            setRevenue([]);
            nextErrors.revenue = revenueRes.error ?? 'Failed to load revenue trend';
          }

          if (escrowSummaryRes.success && escrowSummaryRes.data) {
            setEscrowSummary(escrowSummaryRes.data);
          } else {
            setEscrowSummary(null);
            nextErrors.escrowSummary = escrowSummaryRes.error ?? 'Failed to load escrow summary';
          }

          if (flaggedRes.success && flaggedRes.data) {
            setFlaggedEscrows(flaggedRes.data);
          } else {
            setFlaggedEscrows([]);
            nextErrors.flaggedEscrows = flaggedRes.error ?? 'Failed to load flagged escrow';
          }

          if (atRiskRes.success && atRiskRes.data) {
            setAtRiskCampaigns(atRiskRes.data);
          } else {
            setAtRiskCampaigns([]);
            nextErrors.atRiskCampaigns = atRiskRes.error ?? 'Failed to load at-risk campaigns';
          }

          if (hypeOpsRes.success && hypeOpsRes.data) {
            setHypeOps(hypeOpsRes.data);
          } else {
            setHypeOps(null);
            nextErrors.hypeOps = hypeOpsRes.error ?? 'Failed to load HYPE ops';
          }

          if (suspensionsRes.success && suspensionsRes.data) {
            setSuspensions(suspensionsRes.data);
          } else {
            setSuspensions([]);
            nextErrors.suspensions = suspensionsRes.error ?? 'Failed to load suspensions';
          }

          if (reputationRes.success && reputationRes.data) {
            setReputation(reputationRes.data);
          } else {
            setReputation(null);
            nextErrors.reputation = reputationRes.error ?? 'Failed to load platform reputation';
          }

          if (growthRes.success && growthRes.data) {
            setGrowth(growthRes.data);
          } else {
            setGrowth(null);
            nextErrors.growth = growthRes.error ?? 'Failed to load growth metrics';
          }

          setErrors(nextErrors);
        },
      )
      .catch(() => {
        if (cancelled) return;
        setRevenue([]);
        setEscrowSummary(null);
        setFlaggedEscrows([]);
        setAtRiskCampaigns([]);
        setHypeOps(null);
        setSuspensions([]);
        setReputation(null);
        setGrowth(null);
        setErrors({ revenue: 'Failed to load the finance console' });
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [period, reloadKey]);

  return {
    revenue,
    escrowSummary,
    flaggedEscrows,
    atRiskCampaigns,
    hypeOps,
    suspensions,
    reputation,
    growth,
    isLoading,
    errors,
    refresh,
  };
}
