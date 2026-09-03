/**
 * INFLUORA ADMIN PANEL — Creator Agent (Meera-for-Creators) Baselines Page
 * Owner: Ananya (Frontend)
 * Reference: T-MEERA-CREATOR-PHASE-A (A1, gate fix round 1 item 2). Mounted at
 * /admin/creator-agent by src/pages/admin-console.tsx.
 *
 * Priya's audit (SHARED_CONTEXT.md, Q2) found `GET /admin/creator-agent/baselines`
 * (AdminCreatorAgentController, real queries) had zero FE callers — this is that screen.
 * Read-only, no filters, no mutation: a point-in-time snapshot an admin re-pulls with
 * `refresh()`. Same thin-shell pattern as ErrorLogPage.tsx (KpiCard strip + a couple of
 * Cards), no dedicated table component needed for five numbers.
 */

import { Bot, Loader2, RefreshCw } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { useCreatorAgentBaselines } from '../hooks/useCreatorAgentBaselines';
import KpiCard from '../components/dashboard/KpiCard';

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
  } catch {
    return iso;
  }
}

function formatPercent(fraction: number): string {
  return `${(fraction * 100).toFixed(1)}%`;
}

const TIER_ORDER = ['BRONZE', 'SILVER', 'GOLD', 'PLATINUM'];

function sortedTiers(byTier: Record<string, number>): [string, number][] {
  const entries = Object.entries(byTier);
  return entries.sort((a, b) => {
    const ai = TIER_ORDER.indexOf(a[0]);
    const bi = TIER_ORDER.indexOf(b[0]);
    if (ai === -1 && bi === -1) return a[0].localeCompare(b[0]);
    if (ai === -1) return 1;
    if (bi === -1) return -1;
    return ai - bi;
  });
}

export default function CreatorAgentBaselinesPage() {
  const { data, isLoading, error, refresh } = useCreatorAgentBaselines();

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
            <Bot className="size-6" aria-hidden="true" />
            Creator Agent Baselines
          </h2>
          <p className="text-sm text-muted-foreground">
            Pre-rollout metrics snapshot for Meera-for-Creators Phase A — creators by tier, briefs
            per active creator per month, Meta connect rate, and median creator reply time.
          </p>
        </div>
        <Button type="button" variant="outline" size="sm" disabled={isLoading} onClick={refresh} className="gap-1.5">
          {isLoading ? (
            <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          ) : (
            <RefreshCw className="size-3.5" aria-hidden="true" />
          )}
          Refresh
        </Button>
      </div>

      {error && (
        <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load creator agent baselines: {error}
        </Card>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <KpiCard
          title="Meta connect rate"
          value={data ? formatPercent(data.meta_connect_rate) : '—'}
          icon="creators"
          isLoading={isLoading}
        />
        <KpiCard
          title="Median creator reply"
          value={
            data?.median_creator_reply_hours != null
              ? `${data.median_creator_reply_hours.toFixed(1)}h`
              : 'No data yet'
          }
          icon="creators"
          isLoading={isLoading}
        />
        <KpiCard
          title="Briefs / creator / month (median)"
          value={data ? data.briefs_per_creator_per_month.median.toFixed(1) : '—'}
          icon="campaigns"
          isLoading={isLoading}
        />
      </div>

      <Card className="gap-3 p-5">
        <h3 className="text-sm font-semibold text-foreground">Creators by tier</h3>
        {isLoading ? (
          <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        ) : !data || Object.keys(data.creators_by_tier).length === 0 ? (
          <p className="text-sm text-muted-foreground">No tiered creators yet.</p>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {sortedTiers(data.creators_by_tier).map(([tier, count]) => (
              <div key={tier} className="rounded-lg border border-border bg-muted/40 p-3">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{tier}</p>
                <p className="mt-1 text-xl font-semibold text-foreground">{count.toLocaleString('en-IN')}</p>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card className="gap-3 p-5">
        <h3 className="text-sm font-semibold text-foreground">Briefs per active creator / month</h3>
        {isLoading ? (
          <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        ) : !data ? (
          <p className="text-sm text-muted-foreground">No data yet.</p>
        ) : (
          <div className="grid grid-cols-3 gap-4 text-center sm:max-w-md">
            <div>
              <p className="text-lg font-semibold text-foreground">{data.briefs_per_creator_per_month.median.toFixed(1)}</p>
              <p className="text-xs text-muted-foreground">Median</p>
            </div>
            <div>
              <p className="text-lg font-semibold text-foreground">{data.briefs_per_creator_per_month.p75.toFixed(1)}</p>
              <p className="text-xs text-muted-foreground">p75</p>
            </div>
            <div>
              <p className="text-lg font-semibold text-foreground">{data.briefs_per_creator_per_month.p90.toFixed(1)}</p>
              <p className="text-xs text-muted-foreground">p90</p>
            </div>
          </div>
        )}
      </Card>

      <Card className="gap-2 p-5">
        <h3 className="text-sm font-semibold text-foreground">Sample label compliance</h3>
        {/* Priya's audit: CreatorAgentBaselineService.java:43 hardcodes this as a constant,
            not a live-computed rate — the label below says so rather than implying otherwise. */}
        <p className="text-xs text-muted-foreground">
          Fixed reference constant, not yet a live-measured rate — treat as a placeholder target.
        </p>
        {isLoading ? (
          <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        ) : !data ? (
          <p className="text-sm text-muted-foreground">No data yet.</p>
        ) : (
          <div className="grid grid-cols-3 gap-4 text-center sm:max-w-md">
            <div>
              <p className="text-lg font-semibold text-foreground">{data.sample_label_compliance.sample_size}</p>
              <p className="text-xs text-muted-foreground">Sample size</p>
            </div>
            <div>
              <p className="text-lg font-semibold text-foreground">{data.sample_label_compliance.labelled_count}</p>
              <p className="text-xs text-muted-foreground">Labelled</p>
            </div>
            <div>
              <p className="text-lg font-semibold text-foreground">
                {formatPercent(data.sample_label_compliance.compliance_rate)}
              </p>
              <p className="text-xs text-muted-foreground">Compliance</p>
            </div>
          </div>
        )}
      </Card>

      <p className="text-xs text-muted-foreground">
        Snapshot computed at {formatDateTime(data?.computed_at)}.
      </p>
    </div>
  );
}
