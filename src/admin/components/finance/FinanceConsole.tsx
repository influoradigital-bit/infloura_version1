/**
 * INFLUORA ADMIN PANEL — Finance / Escrow / Revenue Console
 * Owner: Ananya (Frontend)
 * Reference: task brief "Admin Finance / Escrow / Revenue console UI". Mounted
 * at /admin/revenue by `src/admin/pages/RevenuePage.tsx`.
 *
 * Read-only console over the platform's revenue-adjacent admin GET routes —
 * no mutations. Data comes from `useFinanceConsole()`
 * (src/admin/hooks/useFinanceConsole.ts), which fans out to seven live
 * backend endpoints in parallel (see that file's header for the exact routes
 * and controllers). Same overall shape as `BillingConsole.tsx` (KPI strip +
 * Card-wrapped tables), split into tabs because this console has five
 * distinct data surfaces rather than one.
 *
 * Reconciliation tab (ReconciliationPanel.tsx) wires the two finance routes
 * that were flipped from `unavailable()` to real `apiRequest` 2026-08-04:
 * `financeApi.getReconciliation(date)` and `financeApi.retryPayout(id)`.
 * Still deliberately does NOT touch: reconciliation resolve/write-off, TDS
 * report, direct escrow hold/release/refund, acquisition/growth metrics,
 * appeal review, or suspension mutation — all remaining `unavailable()`
 * stubs with no backend (see api-contracts.ts). `/admin/finance`
 * (FeeControlPanel) already owns the platform fee schedule; this console is
 * additive, not a replacement.
 */

import { useState } from 'react';
import {
  AlertTriangle,
  BadgeIndianRupee,
  Clock,
  Flag,
  Gauge,
  ListChecks,
  ScrollText,
  ShieldOff,
  Sparkles,
  Star,
  TrendingUp,
  Wallet,
} from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';
import KpiCard from '../dashboard/KpiCard';
import { useFinanceConsole, type FinancialPeriod } from '../../hooks/useFinanceConsole';
import RevenueRangePanel from './RevenueRangePanel';
import EntityAuditPanel from './EntityAuditPanel';
import ReconciliationPanel from './ReconciliationPanel';

// ============================================
// FORMATTING
// ============================================

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
  } catch {
    return 'Invalid date';
  }
}

function formatPeriodLabel(period: string): string {
  return period;
}

const PERIOD_OPTIONS: { value: FinancialPeriod; label: string }[] = [
  { value: 'day', label: 'Daily' },
  { value: 'week', label: 'Weekly' },
  { value: 'month', label: 'Monthly' },
  { value: 'quarter', label: 'Quarterly' },
];

// ============================================
// STATUS PILL — solid, AA-legible fill, same pattern as
// ErrorLogPage/BillingConsole/DisputeList per prior brand-CTA-contrast feedback.
// ============================================

type PillTone = 'success' | 'warning' | 'destructive' | 'neutral';

const PILL_TONE_CLASSES: Record<PillTone, string> = {
  success: 'bg-success-foreground',
  warning: 'bg-warning-foreground',
  destructive: 'bg-destructive-foreground',
  neutral: 'bg-foreground',
};

function StatusPill({ tone, children }: { tone: PillTone; children: React.ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex w-fit items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold text-white',
        PILL_TONE_CLASSES[tone],
      )}
    >
      {children}
    </span>
  );
}

function campaignStatusTone(status: string): PillTone {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'PAUSED':
      return 'warning';
    case 'CANCELLED':
      return 'destructive';
    default:
      return 'neutral';
  }
}

// ============================================
// ERROR NOTICE
// ============================================

function ErrorNotice({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
      <p>{message}</p>
    </div>
  );
}

// ============================================
// COMPONENT
// ============================================

export interface FinanceConsoleProps {
  className?: string;
}

export default function FinanceConsole({ className }: FinanceConsoleProps) {
  const [period, setPeriod] = useState<FinancialPeriod>('month');
  const {
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
  } = useFinanceConsole(period);

  // Latest bucket in the trailing window — used for the top-line revenue KPI
  // cards. `dashboardApi.getFinancialSummary` returns buckets ascending, so
  // the most recent one is last.
  const latestBucket = revenue.length > 0 ? revenue[revenue.length - 1] : null;

  return (
    <div className={cn('flex flex-col gap-6', className)}>
      {Object.values(errors).filter(Boolean).length > 0 && (
        <div className="flex flex-col gap-2">
          {errors.revenue && <ErrorNotice message={`Revenue: ${errors.revenue}`} />}
          {errors.escrowSummary && <ErrorNotice message={`Escrow summary: ${errors.escrowSummary}`} />}
          {errors.flaggedEscrows && <ErrorNotice message={`Flagged escrow: ${errors.flaggedEscrows}`} />}
          {errors.atRiskCampaigns && <ErrorNotice message={`At-risk campaigns: ${errors.atRiskCampaigns}`} />}
          {errors.hypeOps && <ErrorNotice message={`HYPE ops: ${errors.hypeOps}`} />}
          {errors.suspensions && <ErrorNotice message={`Suspensions: ${errors.suspensions}`} />}
          {errors.reputation && <ErrorNotice message={`Platform reputation: ${errors.reputation}`} />}
          {errors.growth && <ErrorNotice message={`Growth metrics: ${errors.growth}`} />}
        </div>
      )}

      {/* Top-line KPI strip */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard
          title={`GMV (${latestBucket ? formatPeriodLabel(latestBucket.period) : 'latest period'})`}
          value={latestBucket ? formatCurrency(latestBucket.gmv) : '—'}
          icon="gmv"
          isLoading={isLoading}
        />
        <KpiCard
          title="Platform Revenue"
          value={latestBucket ? formatCurrency(latestBucket.totalRevenue) : '—'}
          icon="revenue"
          isLoading={isLoading}
        />
        <KpiCard
          title="Escrow Locked"
          value={escrowSummary ? formatCurrency(escrowSummary.totalLocked) : '—'}
          icon="escrow"
          isLoading={isLoading}
        />
        <KpiCard
          title="Flagged Escrow"
          value={escrowSummary ? String(escrowSummary.flaggedTransactions) : '—'}
          icon="risk"
          isLoading={isLoading}
        />
      </div>

      <Tabs defaultValue="revenue" className="gap-4">
        <TabsList className="flex-wrap">
          <TabsTrigger value="revenue">
            <TrendingUp className="size-4" aria-hidden="true" />
            Revenue
          </TabsTrigger>
          <TabsTrigger value="escrow">
            <Flag className="size-4" aria-hidden="true" />
            Flagged Escrow
          </TabsTrigger>
          <TabsTrigger value="campaigns">
            <Gauge className="size-4" aria-hidden="true" />
            At-Risk Campaigns
          </TabsTrigger>
          <TabsTrigger value="hype">
            <Sparkles className="size-4" aria-hidden="true" />
            HYPE Ops
          </TabsTrigger>
          <TabsTrigger value="suspensions">
            <ShieldOff className="size-4" aria-hidden="true" />
            Suspensions
          </TabsTrigger>
          <TabsTrigger value="revenue-range">
            <BadgeIndianRupee className="size-4" aria-hidden="true" />
            Revenue Range
          </TabsTrigger>
          <TabsTrigger value="entity-audit">
            <ScrollText className="size-4" aria-hidden="true" />
            Entity Audit
          </TabsTrigger>
          <TabsTrigger value="reconciliation">
            <ListChecks className="size-4" aria-hidden="true" />
            Reconciliation
          </TabsTrigger>
        </TabsList>

        {/* ---------------- Revenue ---------------- */}
        <TabsContent value="revenue" className="flex flex-col gap-4">
          <Card className="gap-4 p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <BadgeIndianRupee className="size-4" aria-hidden="true" />
                Revenue Trend
              </h3>
              <Select value={period} onValueChange={(v) => setPeriod(v as FinancialPeriod)}>
                <SelectTrigger className="w-40" aria-label="Revenue trend granularity">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PERIOD_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <p className="text-xs text-muted-foreground">
              Trailing 12 {period} buckets. GMV = released escrow value; Platform Fees = commission
              invoiced in the bucket; Setup Fees is a structural 0 (no setup-fee product exists yet).
            </p>

            <div className="overflow-x-auto rounded-lg border border-border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Period</TableHead>
                    <TableHead>GMV</TableHead>
                    <TableHead>Platform Fees</TableHead>
                    <TableHead>Setup Fees</TableHead>
                    <TableHead>Total Revenue</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {isLoading ? (
                    Array.from({ length: 6 }).map((_, i) => (
                      <TableRow key={i}>
                        {Array.from({ length: 5 }).map((__, j) => (
                          <TableCell key={j}>
                            <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                          </TableCell>
                        ))}
                      </TableRow>
                    ))
                  ) : revenue.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                        No revenue data for this window.
                      </TableCell>
                    </TableRow>
                  ) : (
                    [...revenue].reverse().map((snap) => (
                      <TableRow key={snap.period}>
                        <TableCell className="font-medium text-foreground">{snap.period}</TableCell>
                        <TableCell className="whitespace-nowrap text-foreground">
                          {formatCurrency(snap.gmv)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {formatCurrency(snap.platformFees)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {formatCurrency(snap.setupFees)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap font-medium text-foreground">
                          {formatCurrency(snap.totalRevenue)}
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </div>
          </Card>

          {/* Escrow summary + platform reputation, side by side */}
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Card className="gap-3 p-5">
              <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <Wallet className="size-4" aria-hidden="true" />
                Escrow Summary
              </h3>
              {isLoading ? (
                <div className="h-20 animate-pulse rounded bg-muted" />
              ) : !escrowSummary ? (
                <p className="text-sm text-muted-foreground">Escrow summary not available.</p>
              ) : (
                <dl className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <dt className="text-muted-foreground">Total Locked</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {formatCurrency(escrowSummary.totalLocked)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Pending Release</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {formatCurrency(escrowSummary.pendingRelease)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Flagged Transactions</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {escrowSummary.flaggedTransactions}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Avg. Release Time</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {escrowSummary.averageReleaseTime.toFixed(1)}h
                    </dd>
                  </div>
                </dl>
              )}
            </Card>

            <Card className="gap-3 p-5">
              <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <Star className="size-4" aria-hidden="true" />
                Platform Reputation
              </h3>
              {isLoading ? (
                <div className="h-20 animate-pulse rounded bg-muted" />
              ) : !reputation ? (
                <p className="text-sm text-muted-foreground">Reputation score not available.</p>
              ) : (
                <dl className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <dt className="text-muted-foreground">Overall</dt>
                    <dd className="text-base font-semibold text-foreground">{reputation.overall.toFixed(1)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Creator Quality Avg.</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {reputation.creatorQualityAvg.toFixed(1)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Brand Satisfaction Avg.</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {reputation.brandSatisfactionAvg.toFixed(1)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Dispute Resolution Speed</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {reputation.disputeResolutionSpeed.toFixed(1)}h
                    </dd>
                  </div>
                </dl>
              )}
              {reputation && (
                <p className="text-xs text-muted-foreground">Calculated {formatDateTime(reputation.calculatedAt)}</p>
              )}
            </Card>
          </div>

          {/* Growth funnel + conversion (data-backed subset — GET /admin/marketing/growth) */}
          <Card className="gap-3 p-5">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <TrendingUp className="size-4" aria-hidden="true" />
              Growth Funnel &amp; Conversion
            </h3>
            {isLoading ? (
              <div className="h-20 animate-pulse rounded bg-muted" />
            ) : !growth ? (
              <p className="text-sm text-muted-foreground">Growth metrics not available.</p>
            ) : (
              <>
                <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3 lg:grid-cols-5">
                  <div>
                    <dt className="text-muted-foreground">Signups</dt>
                    <dd className="text-base font-semibold text-foreground">{growth.funnel.signups}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">First Campaign</dt>
                    <dd className="text-base font-semibold text-foreground">{growth.funnel.firstCampaign}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Repeat Campaign</dt>
                    <dd className="text-base font-semibold text-foreground">{growth.funnel.repeatCampaign}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Creator App → Approval</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {(growth.conversionRates.creatorApplicationToApproval * 100).toFixed(1)}%
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Brand Signup → Campaign</dt>
                    <dd className="text-base font-semibold text-foreground">
                      {(growth.conversionRates.brandSignupToFirstCampaign * 100).toFixed(1)}%
                    </dd>
                  </div>
                </dl>
                <p className="text-xs text-muted-foreground">
                  Cohort retention and referral analytics are not tracked yet (no retention-event or referral data) — shown when that data exists.
                </p>
              </>
            )}
          </Card>
        </TabsContent>

        {/* ---------------- Flagged Escrow ---------------- */}
        <TabsContent value="escrow" className="flex flex-col gap-4">
          <Card className="gap-4 p-5">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <Flag className="size-4" aria-hidden="true" />
              Flagged Escrow ({isLoading ? '…' : flaggedEscrows.length})
            </h3>
            <div className="overflow-x-auto rounded-lg border border-border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Campaign</TableHead>
                    <TableHead>Amount</TableHead>
                    <TableHead>Flag Reason</TableHead>
                    <TableHead>Flagged</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {isLoading ? (
                    Array.from({ length: 4 }).map((_, i) => (
                      <TableRow key={i}>
                        {Array.from({ length: 4 }).map((__, j) => (
                          <TableCell key={j}>
                            <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                          </TableCell>
                        ))}
                      </TableRow>
                    ))
                  ) : flaggedEscrows.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={4} className="py-10 text-center text-sm text-muted-foreground">
                        <div className="flex flex-col items-center gap-2">
                          <Flag className="size-6 text-muted-foreground/60" aria-hidden="true" />
                          No flagged escrow transactions.
                        </div>
                      </TableCell>
                    </TableRow>
                  ) : (
                    flaggedEscrows.map((row) => (
                      <TableRow key={row.id}>
                        <TableCell className="font-medium text-foreground">{row.campaignName}</TableCell>
                        <TableCell className="whitespace-nowrap text-foreground">
                          {formatCurrency(row.amount)}
                        </TableCell>
                        <TableCell className="max-w-96 whitespace-normal text-muted-foreground">
                          {row.flagReason}
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {formatDateTime(row.createdAt)}
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </div>
          </Card>
        </TabsContent>

        {/* ---------------- At-Risk Campaigns ---------------- */}
        <TabsContent value="campaigns" className="flex flex-col gap-4">
          <Card className="gap-4 p-5">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <Gauge className="size-4" aria-hidden="true" />
              At-Risk Campaigns ({isLoading ? '…' : atRiskCampaigns.length})
            </h3>
            <div className="overflow-x-auto rounded-lg border border-border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Campaign</TableHead>
                    <TableHead>Brand</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Budget</TableHead>
                    <TableHead>Spent</TableHead>
                    <TableHead>SLA Breach Rate</TableHead>
                    <TableHead>Deliverables Pending</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {isLoading ? (
                    Array.from({ length: 4 }).map((_, i) => (
                      <TableRow key={i}>
                        {Array.from({ length: 7 }).map((__, j) => (
                          <TableCell key={j}>
                            <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                          </TableCell>
                        ))}
                      </TableRow>
                    ))
                  ) : atRiskCampaigns.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={7} className="py-10 text-center text-sm text-muted-foreground">
                        <div className="flex flex-col items-center gap-2">
                          <Gauge className="size-6 text-muted-foreground/60" aria-hidden="true" />
                          No at-risk campaigns right now.
                        </div>
                      </TableCell>
                    </TableRow>
                  ) : (
                    atRiskCampaigns.map((c) => (
                      <TableRow key={c.id}>
                        <TableCell className="font-medium text-foreground">{c.name}</TableCell>
                        <TableCell className="text-muted-foreground">{c.brandName}</TableCell>
                        <TableCell>
                          <StatusPill tone={campaignStatusTone(c.status)}>{c.status}</StatusPill>
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {formatCurrency(c.budget)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {formatCurrency(c.spent)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-destructive-foreground">
                          {(c.slaBreachRate * 100).toFixed(0)}%
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {c.deliverablesPending}
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </div>
          </Card>
        </TabsContent>

        {/* ---------------- HYPE Ops ---------------- */}
        <TabsContent value="hype" className="flex flex-col gap-4">
          <Card className="gap-4 p-5">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <Sparkles className="size-4" aria-hidden="true" />
              HYPE Campaign Ops
            </h3>
            {isLoading ? (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div key={i} className="h-20 animate-pulse rounded bg-muted" />
                ))}
              </div>
            ) : !hypeOps ? (
              <p className="text-sm text-muted-foreground">HYPE ops data not available.</p>
            ) : (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
                <div>
                  <dt className="text-sm text-muted-foreground">Active HYPE Campaigns</dt>
                  <dd className="text-xl font-semibold text-foreground">{hypeOps.activeHype}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Total Slots</dt>
                  <dd className="text-xl font-semibold text-foreground">{hypeOps.totalSlots}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Filled Slots</dt>
                  <dd className="text-xl font-semibold text-foreground">{hypeOps.filledSlots}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Avg. Fill Rate</dt>
                  <dd className="text-xl font-semibold text-foreground">{(hypeOps.avgFillRate * 100).toFixed(0)}%</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Approvals / Hour</dt>
                  <dd className="text-xl font-semibold text-foreground">{hypeOps.approvalsPerHour.toFixed(1)}</dd>
                </div>
              </div>
            )}
          </Card>
        </TabsContent>

        {/* ---------------- Suspensions ---------------- */}
        <TabsContent value="suspensions" className="flex flex-col gap-4">
          <Card className="gap-4 p-5">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <ShieldOff className="size-4" aria-hidden="true" />
              Suspended Accounts ({isLoading ? '…' : suspensions.length})
            </h3>
            <div className="overflow-x-auto rounded-lg border border-border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>User</TableHead>
                    <TableHead>Type</TableHead>
                    <TableHead>Reason</TableHead>
                    <TableHead>Suspended By</TableHead>
                    <TableHead>Suspended</TableHead>
                    <TableHead>Appeal</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {isLoading ? (
                    Array.from({ length: 4 }).map((_, i) => (
                      <TableRow key={i}>
                        {Array.from({ length: 6 }).map((__, j) => (
                          <TableCell key={j}>
                            <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                          </TableCell>
                        ))}
                      </TableRow>
                    ))
                  ) : suspensions.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                        <div className="flex flex-col items-center gap-2">
                          <ShieldOff className="size-6 text-muted-foreground/60" aria-hidden="true" />
                          No suspended accounts.
                        </div>
                      </TableCell>
                    </TableRow>
                  ) : (
                    suspensions.map((s) => (
                      <TableRow key={s.id}>
                        <TableCell className="font-medium text-foreground">{s.userName}</TableCell>
                        <TableCell>
                          <Badge variant="outline">{s.userType}</Badge>
                        </TableCell>
                        <TableCell className="max-w-96 whitespace-normal text-muted-foreground">
                          {s.reason}
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">{s.suspendedBy}</TableCell>
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {formatDateTime(s.suspendedAt)}
                        </TableCell>
                        <TableCell>
                          <StatusPill tone={s.appealStatus === 'PENDING' ? 'warning' : 'neutral'}>
                            {s.appealStatus}
                          </StatusPill>
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </div>
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Clock className="size-3.5" aria-hidden="true" />
              Appeal review is not built yet — every appeal shows NONE until that subsystem ships.
            </p>
          </Card>
        </TabsContent>

        {/* ---------------- Revenue Range (on-demand, custom date range) ---------------- */}
        <TabsContent value="revenue-range" className="flex flex-col gap-4">
          <RevenueRangePanel />
        </TabsContent>

        {/* ---------------- Entity Audit (on-demand lookup) ---------------- */}
        <TabsContent value="entity-audit" className="flex flex-col gap-4">
          <EntityAuditPanel />
        </TabsContent>

        {/* ---------------- Reconciliation (on-demand, per-date) ---------------- */}
        <TabsContent value="reconciliation" className="flex flex-col gap-4">
          <ReconciliationPanel />
        </TabsContent>
      </Tabs>
    </div>
  );
}
