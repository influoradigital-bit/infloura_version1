import * as React from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
  BarChart3,
  Eye,
  Heart,
  Loader2,
  TrendingUp,
  Users,
  ChevronRight,
} from 'lucide-react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription, EmptyContent } from '@/components/ui/empty';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';

import { CreatorMetricsCard } from '@/components/analytics/CreatorMetricsCard';
import { MetricsTrendChart } from '@/components/analytics/MetricsTrendChart';
import { useCreatorMetrics } from '@/hooks/analytics/useCreatorMetrics';
import { api, ApiError, isApiLive } from '@/lib/api';
import { demoCreators } from '@/lib/demo-data';
import type { AnalyticsDateRange } from '@/lib/types';

const DATE_PRESETS = [
  { value: '7', label: 'Last 7 days' },
  { value: '14', label: 'Last 14 days' },
  { value: '30', label: 'Last 30 days' },
];

/**
 * Roster entry rendered by this page. Demo mode supplies the full
 * `CreatorProfile` shape (followers/engagement included); live mode derives
 * only `id`/`displayName` from the brand's real deals (see
 * `deriveRosterFromDeals` below) — the deals endpoint carries no follower or
 * engagement data, so those fields stay undefined rather than fabricated.
 */
interface RosterCreator {
  id: string;
  displayName: string;
  totalFollowers?: number;
  engagementRate?: number;
}

function demoRoster(): RosterCreator[] {
  return demoCreators.map((c) => ({
    id: c.id,
    displayName: c.displayName,
    totalFollowers: c.totalFollowers,
    engagementRate: c.engagementRate,
  }));
}

/**
 * Real roster (live mode) — distinct creators the brand has actual deals
 * with, derived from GET /deals?role=brand (already used elsewhere; no new
 * backend endpoint invented). Keeps first-seen counterparty name per id.
 */
async function deriveRosterFromDeals(): Promise<RosterCreator[]> {
  const dealRows = await api.deals.list('brand', 'all');
  const seen = new Map<string, RosterCreator>();
  for (const deal of dealRows) {
    if (!seen.has(deal.counterpartyId)) {
      seen.set(deal.counterpartyId, { id: deal.counterpartyId, displayName: deal.counterpartyName });
    }
  }
  return Array.from(seen.values());
}

/**
 * Main analytics overview — brand-facing dashboard summarizing performance
 * for the brand's roster of creators. Route: /brand/analytics.
 *
 * Scope note (per Ananya's task brief): this maps
 * ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 1.2, adapted from the
 * spec's assumed Next.js route (/dashboard/analytics) to this repo's real
 * React Router convention (src/pages + src/App.tsx). Comparison (1.4) and
 * campaign/UTM tracking (1.5) are out of scope — those depend on
 * unbuilt Phase 4 endpoints.
 *
 * There is no brand-wide "aggregate across all creators" endpoint yet — the
 * real backend (AnalyticsController) only exposes per-creator metrics/scores.
 * Rather than fabricate an aggregate, this overview lets the brand pick one
 * of their roster's creators from a selector and shows that creator's real
 * metrics trend, with the full roster listed below for quick navigation to
 * each creator's individual analytics page.
 *
 * Roster source: live mode derives the roster from the brand's actual deals
 * (GET /deals?role=brand — see `deriveRosterFromDeals`) instead of demo
 * fixtures, so the creator selector and per-creator metrics below are always
 * fed real creator IDs. Mock mode keeps `demoCreators`.
 */
export default function BrandAnalyticsPage() {
  const live = isApiLive();
  const [roster, setRoster] = React.useState<RosterCreator[]>(() => (live ? [] : demoRoster()));
  const [rosterLoading, setRosterLoading] = React.useState(live);
  const [rosterError, setRosterError] = React.useState<string | null>(null);
  const [selectedCreatorId, setSelectedCreatorId] = React.useState<string | undefined>(() =>
    live ? undefined : demoRoster()[0]?.id,
  );
  const [days, setDays] = React.useState('14');

  const refreshRoster = React.useCallback(async () => {
    if (!live) return;
    setRosterLoading(true);
    setRosterError(null);
    try {
      const derived = await deriveRosterFromDeals();
      setRoster(derived);
      setSelectedCreatorId((prev) => prev ?? derived[0]?.id);
    } catch (err) {
      setRoster([]);
      setRosterError(err instanceof ApiError ? err.message : 'Could not load your creator roster.');
    } finally {
      setRosterLoading(false);
    }
  }, [live]);

  React.useEffect(() => {
    void refreshRoster();
  }, [refreshRoster]);

  const dateRange: AnalyticsDateRange = React.useMemo(() => {
    const end = new Date();
    const start = new Date(end.getTime() - Number(days) * 24 * 60 * 60 * 1000);
    return { start, end };
  }, [days]);

  const { data: metrics, loading: metricsLoading, error: metricsError } = useCreatorMetrics(
    selectedCreatorId,
    dateRange,
  );

  const selectedCreator = roster.find((c) => c.id === selectedCreatorId);

  if (rosterLoading) {
    return (
      <div className="flex items-center justify-center py-24">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label="Loading your creator roster" />
      </div>
    );
  }

  if (rosterError) {
    return (
      <Alert variant="destructive">
        <AlertTriangle className="h-4 w-4" />
        <AlertTitle>Could not load your creator roster</AlertTitle>
        <AlertDescription>
          {rosterError}
          <div className="mt-2">
            <Button size="sm" variant="outline" onClick={() => void refreshRoster()}>
              Try again
            </Button>
          </div>
        </AlertDescription>
      </Alert>
    );
  }

  if (roster.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>{live ? 'No creators to analyze yet' : 'No creators yet'}</EmptyTitle>
          <EmptyDescription>
            {live
              ? "Analytics will appear here once you have a deal with a creator."
              : 'Analytics will appear here once you have creators in your roster.'}
          </EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button asChild>
            <Link to="/brand/discover">Discover Creators</Link>
          </Button>
        </EmptyContent>
      </Empty>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-bold">Analytics Overview</h1>
          <p className="text-muted-foreground">
            Creator performance metrics for your roster
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Select value={selectedCreatorId} onValueChange={setSelectedCreatorId}>
            <SelectTrigger className="w-56">
              <SelectValue placeholder="Select a creator" />
            </SelectTrigger>
            <SelectContent>
              {roster.map((creator) => (
                <SelectItem key={creator.id} value={creator.id}>
                  {creator.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={days} onValueChange={setDays}>
            <SelectTrigger className="w-40">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {DATE_PRESETS.map((preset) => (
                <SelectItem key={preset.value} value={preset.value}>
                  {preset.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {metricsError && (
        <Card className="border-destructive-foreground/30">
          <CardContent className="py-6 text-center text-sm text-destructive-foreground">
            Couldn't load metrics for this creator. {metricsError}
          </CardContent>
        </Card>
      )}

      {/* Summary cards */}
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <CreatorMetricsCard
          title="Total Reach"
          value={metrics?.totalReach ?? 0}
          format="compact"
          icon={Eye}
          loading={metricsLoading}
        />
        <CreatorMetricsCard
          title="Engagement Rate"
          value={metrics?.engagementRate ?? 0}
          format="percentage"
          icon={Heart}
          loading={metricsLoading}
        />
        <CreatorMetricsCard
          title="Total Engagements"
          value={metrics?.totalEngagements ?? 0}
          format="compact"
          icon={TrendingUp}
          loading={metricsLoading}
        />
        <CreatorMetricsCard
          title="Follower Growth"
          value={metrics?.followerGrowth ?? 0}
          format="compact"
          icon={Users}
          loading={metricsLoading}
        />
      </div>

      {/* Trend chart */}
      <MetricsTrendChart
        title={`Reach & Engagement Trend${selectedCreator ? ` — ${selectedCreator.displayName}` : ''}`}
        description={`Last ${days} days`}
        data={metrics?.trendData ?? []}
        metrics={[
          { key: 'reach', label: 'Reach', color: 'var(--chart-1)' },
          { key: 'impressions', label: 'Impressions', color: 'var(--chart-2)' },
        ]}
        loading={metricsLoading}
      />

      {/* Roster list */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <BarChart3 className="h-5 w-5" />
            Your Creator Roster
          </CardTitle>
          <CardDescription>Open a creator's full analytics breakdown</CardDescription>
        </CardHeader>
        <CardContent className="divide-y">
          {roster.map((creator) => (
            <Link
              key={creator.id}
              to={`/brand/analytics/${creator.id}`}
              className="flex items-center gap-4 py-3 transition-colors hover:bg-muted/50 first:pt-0 last:pb-0"
            >
              <Avatar className="h-10 w-10">
                <AvatarFallback>{creator.displayName[0]}</AvatarFallback>
              </Avatar>
              <div className="min-w-0 flex-1">
                <p className="font-medium">{creator.displayName}</p>
                <p className="text-sm text-muted-foreground">
                  {creator.totalFollowers !== undefined && creator.engagementRate !== undefined
                    ? `${creator.totalFollowers.toLocaleString('en-IN')} followers · ${creator.engagementRate.toFixed(1)}% engagement`
                    : 'View full analytics'}
                </p>
              </div>
              <ChevronRight className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
            </Link>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
