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
import { useCreatorAgentRateCalibration } from '../hooks/useCreatorAgentRateCalibration';
import type { CreatorAgentRateCalibrationTier } from '../types/admin.types';
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

/**
 * SIZE tiers — a DIFFERENT vocabulary from `TIER_ORDER` above, which holds loyalty tiers and
 * shares not one value with this list. Reusing `TIER_ORDER` here would sort every rate tier into
 * the -1 bucket and fall through to `localeCompare`, i.e. MACRO, MEGA, MICRO, MID, NANO — which
 * reads as an ordering while being alphabetical noise. (Priya flagged exactly this: SPEC.md
 * §14.6 W7.)
 *
 * These are plain strings and must stay plain strings: `MEGA` is not a value of the backend's
 * `CreatorTier` enum, so round-tripping these through it would throw on the largest tier.
 */
const RATE_TIER_ORDER = ['NANO', 'MICRO', 'MID', 'MACRO', 'MEGA'];

function sortedRateTiers(tiers: CreatorAgentRateCalibrationTier[]): CreatorAgentRateCalibrationTier[] {
  return [...tiers].sort((a, b) => {
    const ai = RATE_TIER_ORDER.indexOf(a.tier);
    const bi = RATE_TIER_ORDER.indexOf(b.tier);
    if (ai === -1 && bi === -1) return a.tier.localeCompare(b.tier);
    if (ai === -1) return 1;
    if (bi === -1) return -1;
    return ai - bi;
  });
}

/**
 * The one string that must never become a dash, a zero, or an em-dash.
 *
 * A suppressed `realised_median` and a realised median that happens to be low are completely
 * different facts, and the k-anonymity floor is the entire reason the first one exists. Rendering
 * "—" in that cell puts it in the same visual class as every other empty cell on the admin
 * console, and someone reading the table for B0-36 would set a pricing override off a blank.
 */
const UNKNOWN_LABEL = 'Not enough data';

function formatRupees(value: number | null): string {
  if (value == null) return UNKNOWN_LABEL;
  return `₹${value.toLocaleString('en-IN')}`;
}


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
  const calibration = useCreatorAgentRateCalibration();

  function refreshAll() {
    refresh();
    calibration.refresh();
  }

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
        <Button type="button" variant="outline" size="sm" disabled={isLoading || calibration.isLoading} onClick={refreshAll} className="gap-1.5">
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

      {/* =========================================================================
          T-MEERA-CREATOR-PHASE-B (SPEC.md 14.1.g, B0-35) - rate calibration.

          What a reader is here to decide: whether the compiled tier base rates match what
          creators actually close at, before B1 freezes a package total into a link a brand can
          accept. SPEC.md 14.5.c metric 4 - the gate on starting B1 - has no other source.

          Expect every realised cell to read "Not enough data" until real priced deals
          accumulate. That is the correct reading of an uncalibrated system, not a broken panel,
          and the caption below says so rather than leaving an admin to guess.
          ========================================================================= */}
      <Card className="gap-3 p-5">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <h3 className="text-sm font-semibold text-foreground">Rate calibration by tier</h3>
          {calibration.data && (
            <span className="text-xs text-muted-foreground">
              Realised figures suppressed below {calibration.data.sample_floor} deals; window{' '}
              {calibration.data.window_days} days.
            </span>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          <span className="font-medium text-foreground">Benchmark</span> is what the quote formula
          prices from, <span className="font-medium text-foreground">realised</span> is what
          creators in that tier actually closed at, and{' '}
          <span className="font-medium text-foreground">quoted</span> is what Meera has been
          saying. Benchmark vs realised is the recalibration signal; realised vs quoted is the
          anchoring signal.
        </p>

        {calibration.error && (
          <p className="text-sm text-destructive-foreground">
            Failed to load rate calibration: {calibration.error}
          </p>
        )}

        {calibration.isLoading ? (
          <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        ) : !calibration.data || calibration.data.tiers.length === 0 ? (
          <p className="text-sm text-muted-foreground">No calibration data available.</p>
        ) : (
          <>
            {/* overflow-x on the wrapper, min-w-0 on it too: this is a flex child and a wide
                table would otherwise size the track and get clipped rather than scrolled. */}
            <div className="min-w-0 overflow-x-auto">
              <table className="w-full min-w-[52rem] border-collapse text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th scope="col" className="py-2 pr-3 font-medium">Tier</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Benchmark band</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Benchmark unit</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Source</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Realised median</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Sample</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Workspaces</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Meera-anchored</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Quoted median (90d)</th>
                  </tr>
                </thead>
                <tbody>
                  {sortedRateTiers(calibration.data.tiers).map((tier) => {
                    const suppressed = tier.realised_median == null;
                    return (
                      <tr key={tier.tier} className="border-b border-border/60 last:border-0">
                        <th scope="row" className="py-2 pr-3 text-left font-semibold text-foreground">
                          {tier.tier}
                        </th>
                        <td className="py-2 pr-3 text-foreground">
                          {formatRupees(tier.benchmark_min)} &ndash; {formatRupees(tier.benchmark_max)}
                        </td>
                        <td className="py-2 pr-3 text-foreground">{formatRupees(tier.benchmark_unit)}</td>
                        <td className="py-2 pr-3">
                          <span
                            className={
                              tier.benchmark_source === 'yml override'
                                ? 'rounded bg-primary/10 px-1.5 py-0.5 text-xs font-medium text-foreground'
                                : 'text-xs text-muted-foreground'
                            }
                          >
                            {tier.benchmark_source}
                          </span>
                        </td>
                        {/* The suppressed cell says so in words. Never a dash, never a zero:
                            "no median" and "a low median" are different facts and this column is
                            read to set pricing constants. */}
                        <td
                          className={
                            suppressed
                              ? 'py-2 pr-3 text-xs italic text-muted-foreground'
                              : 'py-2 pr-3 font-semibold text-foreground'
                          }
                        >
                          {suppressed ? UNKNOWN_LABEL : formatRupees(tier.realised_median)}
                        </td>
                        <td className="py-2 pr-3 text-foreground">
                          {tier.realised_n.toLocaleString('en-IN')}
                          {suppressed && tier.realised_n > 0 && (
                            <span className="ml-1 text-xs text-muted-foreground">(under floor)</span>
                          )}
                        </td>
                        <td className="py-2 pr-3 text-foreground">
                          {tier.distinct_workspaces.toLocaleString('en-IN')}
                        </td>
                        <td
                          className={
                            tier.meera_anchored_share == null
                              ? 'py-2 pr-3 text-xs italic text-muted-foreground'
                              : 'py-2 pr-3 text-foreground'
                          }
                        >
                          {tier.meera_anchored_share == null
                            ? UNKNOWN_LABEL
                            : formatPercent(tier.meera_anchored_share)}
                        </td>
                        <td className="py-2 pr-3 text-foreground">
                          {tier.quoted_n_90d === 0 ? (
                            <span className="text-xs italic text-muted-foreground">
                              No quotes issued
                            </span>
                          ) : (
                            <>
                              {formatRupees(tier.quoted_median_90d)}
                              <span className="ml-1 text-xs text-muted-foreground">
                                (n={tier.quoted_n_90d})
                              </span>
                            </>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="text-xs text-muted-foreground">
              &ldquo;{UNKNOWN_LABEL}&rdquo; means the tier&rsquo;s sample is below the{' '}
              {calibration.data.sample_floor}-deal privacy floor, so no median is computed &mdash;
              it does not mean zero. With no priced deals closed yet every tier reads that way, and
              recalibrating a tier&rsquo;s override (SPEC.md &sect;14.1.h) needs a realised sample
              of 20 or more, not just a figure in the cell.
            </p>
            <p className="text-xs text-muted-foreground">
              Calibration computed at {formatDateTime(calibration.data.computed_at)}.
            </p>
          </>
        )}
      </Card>

      <p className="text-xs text-muted-foreground">
        Snapshot computed at {formatDateTime(data?.computed_at)}.
      </p>
    </div>
  );
}
