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
 */

import { AlertTriangle, ShieldAlert } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { usePulseData } from '../../hooks/usePulseData';
import KpiCard from './KpiCard';
import type { KpiCard as KpiCardData, RedFlag } from '../../types/admin.types';

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

// ============================================
// COMPONENT
// ============================================

export default function PulseDashboard() {
  const { data, isLoading, error } = usePulseData();

  const kpis: KpiCardData[] = data
    ? [
        { title: 'GMV', value: formatCompactINR(data.gmv), change: data.gmvChange, changeType: data.gmvChange >= 0 ? 'positive' : 'negative', icon: 'gmv' },
        { title: 'Revenue', value: formatCompactINR(data.revenue), change: data.revenueChange, changeType: data.revenueChange >= 0 ? 'positive' : 'negative', icon: 'revenue' },
        { title: 'Active Campaigns', value: data.activeCampaigns, change: data.activeCampaignsChange, changeType: data.activeCampaignsChange >= 0 ? 'positive' : 'negative', icon: 'campaigns' },
        { title: 'Escrow Float', value: formatCompactINR(data.escrowFloat), icon: 'escrow' },
        { title: 'Support Queue Depth', value: data.supportQueueDepth, icon: 'support' },
        { title: 'Brand MAU', value: formatCompactNumber(data.mauBrands), icon: 'brands' },
        { title: 'Creator MAU', value: formatCompactNumber(data.mauCreators), icon: 'creators' },
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
