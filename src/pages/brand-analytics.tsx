import * as React from 'react';
import { Link } from 'react-router-dom';
import {
  BarChart3,
  Eye,
  Heart,
  TrendingUp,
  Users,
  ChevronRight,
} from 'lucide-react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription, EmptyContent } from '@/components/ui/empty';

import { CreatorMetricsCard } from '@/components/analytics/CreatorMetricsCard';
import { MetricsTrendChart } from '@/components/analytics/MetricsTrendChart';
import { useCreatorMetrics } from '@/hooks/analytics/useCreatorMetrics';
import { demoCreators } from '@/lib/demo-data';
import type { AnalyticsDateRange } from '@/lib/types';

const DATE_PRESETS = [
  { value: '7', label: 'Last 7 days' },
  { value: '14', label: 'Last 14 days' },
  { value: '30', label: 'Last 30 days' },
];

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
 */
export default function BrandAnalyticsPage() {
  const roster = demoCreators;
  const [selectedCreatorId, setSelectedCreatorId] = React.useState<string | undefined>(roster[0]?.id);
  const [days, setDays] = React.useState('14');

  const dateRange: AnalyticsDateRange = React.useMemo(() => {
    const end = new Date();
    const start = new Date(end.getTime() - Number(days) * 24 * 60 * 60 * 1000);
    return { start, end };
  }, [days]);

  const { data: metrics, loading, error } = useCreatorMetrics(selectedCreatorId, dateRange);

  const selectedCreator = roster.find((c) => c.id === selectedCreatorId);

  if (roster.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>No creators yet</EmptyTitle>
          <EmptyDescription>
            Analytics will appear here once you have creators in your roster.
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

      {error && (
        <Card className="border-destructive-foreground/30">
          <CardContent className="py-6 text-center text-sm text-destructive-foreground">
            Couldn't load metrics for this creator. {error}
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
          loading={loading}
        />
        <CreatorMetricsCard
          title="Engagement Rate"
          value={metrics?.engagementRate ?? 0}
          format="percentage"
          icon={Heart}
          loading={loading}
        />
        <CreatorMetricsCard
          title="Total Engagements"
          value={metrics?.totalEngagements ?? 0}
          format="compact"
          icon={TrendingUp}
          loading={loading}
        />
        <CreatorMetricsCard
          title="Follower Growth"
          value={metrics?.followerGrowth ?? 0}
          format="compact"
          icon={Users}
          loading={loading}
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
        loading={loading}
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
                  {creator.totalFollowers.toLocaleString('en-IN')} followers &middot;{' '}
                  {creator.engagementRate.toFixed(1)}% engagement
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
