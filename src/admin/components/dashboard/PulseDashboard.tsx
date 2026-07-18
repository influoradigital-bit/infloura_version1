/**
 * INFLUORA ADMIN PANEL — CEO Pulse Dashboard
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P0)
 *
 * Composes KpiCard into the CEO Pulse grid. Data comes from
 * `usePulseData()` (src/admin/hooks/usePulseData.ts), live-wired to the
 * real `dashboardApi.getPulse()` call (`GET /api/v1/admin/dashboard/pulse`,
 * `AdminDashboardController`). The hook is a hand-rolled fetch-on-mount
 * (not a `react-query` `useQuery`), but the data is real, not mocked.
 *
 * A5 (Priya-routed admin-panel audit fix): also surfaces
 * `useOperationsSummary()` (src/admin/hooks/useOperationsSummary.ts), wired
 * to the previously-unused `dashboardApi.getOperationsSummary()` call
 * (`GET /api/v1/admin/dashboard/operations`) — reviewBacklog/
 * campaignsAtRisk/avgReviewTime have no equivalent in `CeoPulseData` above,
 * so this renders as its own "Operations" KPI row rather than folding into
 * the Pulse grid.
 */

import { AlertTriangle, ShieldAlert } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { usePulseData } from '../../hooks/usePulseData';
import { useOperationsSummary } from '../../hooks/useOperationsSummary';
import KpiCard from './KpiCard';
import type { KpiCard as KpiCardData, RedFlag } from '../../types/admin.types';

// ============================================
// KPI CHANGE CLASSIFICATION
// ============================================

/**
 * Classifies a WoW change value into the KpiCard's changeType.
 *
 * `null` MUST be checked first and explicitly (`=== null`), before any
 * relational comparison — `null >= 0` evaluates to `true` in JS, which
 * would silently paint a missing/unknown value as a green "positive"
 * delta. That reads as fabricated good news on a CFO-facing dashboard,
 * which is exactly the reporting defect this type was changed to prevent
 * (see admin.types.ts CeoPulseData.gmvChange/revenueChange/activeCampaignsChange).
 *
 * A genuine `0` is a real, measured "flat" reading and must be visually
 * distinct from "unknown" — it renders as neutral, not positive.
 */
function classifyChange(change: number | null): 'positive' | 'negative' | 'neutral' | 'unknown' {
  if (change === null) return 'unknown';
  if (change > 0) return 'positive';
  if (change < 0) return 'negative';
  return 'neutral';
}

// ============================================
// FORMATTING
// ============================================

function formatCompactINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(amount);
}

function formatCompactNumber(value: number): string {
  return new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

function formatHours(hours: number): string {
  return `${new Intl.NumberFormat('en-IN', { maximumFractionDigits: 1 }).format(hours)}h`;
}

// ============================================
// COMPONENT
// ============================================

export default function PulseDashboard() {
  const { data, isLoading, error } = usePulseData();
  const { data: opsData, isLoading: opsIsLoading, error: opsError } = useOperationsSummary();

  const kpis: KpiCardData[] = data
    ? [
        { title: 'GMV', value: formatCompactINR(data.gmv), change: data.gmvChange, changeType: classifyChange(data.gmvChange), icon: 'gmv' },
        { title: 'Revenue', value: formatCompactINR(data.revenue), change: data.revenueChange, changeType: classifyChange(data.revenueChange), icon: 'revenue' },
        { title: 'Active Campaigns', value: data.activeCampaigns, change: data.activeCampaignsChange, changeType: classifyChange(data.activeCampaignsChange), icon: 'campaigns' },
        { title: 'Escrow Float', value: formatCompactINR(data.escrowFloat), icon: 'escrow' },
        { title: 'Support Queue Depth', value: data.supportQueueDepth, icon: 'support' },
        { title: 'Brand MAU', value: formatCompactNumber(data.mauBrands), icon: 'brands' },
        { title: 'Creator MAU', value: formatCompactNumber(data.mauCreators), icon: 'creators' },
      ]
    : [];

  const opsKpis: KpiCardData[] = opsData
    ? [
        { title: 'Active Campaigns', value: opsData.activeCampaigns, icon: 'campaigns' },
        { title: 'Campaigns At Risk', value: opsData.campaignsAtRisk, icon: 'risk' },
        { title: 'Review Backlog', value: opsData.reviewBacklog, icon: 'moderation' },
        { title: 'Support Queue Depth', value: opsData.supportQueueDepth, icon: 'support' },
        { title: 'Avg Review Time', value: formatHours(opsData.avgReviewTime), icon: 'risk' },
      ]
    : [];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="text-xl font-semibold text-foreground">CEO Pulse</h2>
        <p className="text-sm text-muted-foreground">Real-time snapshot across GMV, operations, and platform health.</p>
      </div>

      {error && (
        <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load dashboard stats: {error}
        </Card>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {isLoading
          ? Array.from({ length: 4 }).map((_, i) => <KpiCard key={i} title="" value="" isLoading />)
          : kpis.map((kpi) => <KpiCard key={kpi.title} {...kpi} />)}
      </div>

      <RedFlagsPanel redFlags={data?.redFlags ?? []} isLoading={isLoading} />

      <div>
        <h2 className="text-xl font-semibold text-foreground">Operations</h2>
        <p className="text-sm text-muted-foreground">Campaign health, review backlog, and support queue depth.</p>
      </div>

      {opsError && (
        <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load operations summary: {opsError}
        </Card>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {opsIsLoading
          ? Array.from({ length: 5 }).map((_, i) => <KpiCard key={i} title="" value="" isLoading />)
          : opsKpis.map((kpi) => <KpiCard key={kpi.title} {...kpi} />)}
      </div>
    </div>
  );
}

// ============================================
// RED FLAGS PANEL
// ============================================

function RedFlagsPanel({ redFlags, isLoading }: { redFlags: RedFlag[]; isLoading: boolean }) {
  if (isLoading) {
    return (
      <Card className="p-5">
        <div className="h-5 w-32 animate-pulse rounded bg-muted" />
      </Card>
    );
  }

  if (redFlags.length === 0) {
    return (
      <Card className="p-5 text-sm text-muted-foreground">No active red flags. Platform is healthy.</Card>
    );
  }

  return (
    <Card className="gap-3 p-5">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <ShieldAlert className="size-4" aria-hidden="true" />
        Red Flags
      </h3>
      <ul className="flex flex-col gap-2">
        {redFlags.map((flag) => (
          <li
            key={flag.id}
            className={cn(
              'flex items-start gap-2 rounded-lg border px-3 py-2 text-sm',
              flag.severity === 'CRITICAL'
                ? 'border-destructive-foreground/30 bg-destructive text-destructive-foreground'
                : 'border-warning-foreground/30 bg-warning text-warning-foreground',
            )}
          >
            <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            <span>{flag.message}</span>
          </li>
        ))}
      </ul>
    </Card>
  );
}
