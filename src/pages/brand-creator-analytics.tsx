import * as React from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, Eye, TrendingUp, Users } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription, EmptyContent } from '@/components/ui/empty';
import { Skeleton } from '@/components/ui/skeleton';

import { CreatorMetricsCard } from '@/components/analytics/CreatorMetricsCard';
import { MetricsTrendChart } from '@/components/analytics/MetricsTrendChart';
import { EngagementRateGauge } from '@/components/analytics/EngagementRateGauge';
import { FakeFollowerIndicator } from '@/components/analytics/FakeFollowerIndicator';
import { QualityScoreDisplay } from '@/components/analytics/QualityScoreDisplay';

import { useCreatorMetrics } from '@/hooks/analytics/useCreatorMetrics';
import { useCreatorScores } from '@/hooks/analytics/useCreatorScores';
import { demoCreators } from '@/lib/demo-data';
import type { AnalyticsDateRange } from '@/lib/types';

/**
 * Individual creator analytics — route: /brand/analytics/:creatorId.
 * Maps ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 1.3, adapted from the
 * spec's Next.js dynamic route (/dashboard/analytics/[creatorId]) to this
 * repo's React Router convention (:creatorId param, src/pages file).
 *
 * No audience-demographics panel — the spec's section 1.3 layout includes
 * one, but there is no /demographics backend endpoint (no AudienceDemographics
 * entity exists yet, per AnalyticsController's javadoc). Omitted rather than
 * faked; see the "coming soon" note below the quality/authenticity row.
 *
 * BrandSafetyBadge / letter-grade UI is intentionally not built —
 * brandSafetyScore/garmFlags/contentSentiment are always null from the real
 * backend today (BrandSafetyScoreService isn't built), so
 * FakeFollowerIndicator/QualityScoreDisplay already render "not yet
 * available" for their null fields instead.
 */
export default function BrandCreatorAnalyticsPage() {
  const { creatorId } = useParams();
  const navigate = useNavigate();

  const dateRange: AnalyticsDateRange = React.useMemo(() => {
    const end = new Date();
    const start = new Date(end.getTime() - 30 * 24 * 60 * 60 * 1000);
    return { start, end };
  }, []);

  const { data: metrics, loading: metricsLoading, error: metricsError } = useCreatorMetrics(
    creatorId,
    dateRange,
  );
  const { data: scores, loading: scoresLoading, error: scoresError } = useCreatorScores(creatorId);

  const creator = demoCreators.find((c) => c.id === creatorId);

  if (!creatorId) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>No creator selected</EmptyTitle>
          <EmptyDescription>Choose a creator from the analytics overview to continue.</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button asChild>
            <Link to="/brand/analytics">Back to Analytics</Link>
          </Button>
        </EmptyContent>
      </Empty>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" onClick={() => navigate('/brand/analytics')} aria-label="Back to analytics overview">
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <Avatar className="h-10 w-10">
            <AvatarFallback>{(creator?.displayName ?? creatorId)[0]}</AvatarFallback>
          </Avatar>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h1 className="truncate text-xl font-bold">{creator?.displayName ?? creatorId}</h1>
              {creator?.isVerified && (
                <CheckCircle2 className="h-4 w-4 shrink-0 text-primary" aria-label="Verified creator" />
              )}
            </div>
            {creator?.location && <p className="text-sm text-muted-foreground">{creator.location}</p>}
          </div>
        </div>
      </div>

      {(metricsError || scoresError) && (
        <p className="text-sm text-destructive-foreground">
          Some data couldn't be loaded: {metricsError ?? scoresError}
        </p>
      )}

      {/* Metric tiles */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <CreatorMetricsCard
          title="Total Reach"
          value={metrics?.totalReach ?? 0}
          format="compact"
          icon={Eye}
          loading={metricsLoading}
        />
        <CreatorMetricsCard
          title="Total Impressions"
          value={metrics?.totalImpressions ?? 0}
          format="compact"
          icon={Users}
          loading={metricsLoading}
        />
        <CreatorMetricsCard
          title="Avg. Views Per Post"
          value={metrics?.avgViewsPerPost ?? 0}
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

      {/* Trend + engagement gauge */}
      <div className="grid gap-4 lg:grid-cols-3">
        <MetricsTrendChart
          className="lg:col-span-2"
          title="Follower & Reach Trend (30d)"
          data={metrics?.trendData ?? []}
          metrics={[
            { key: 'followers', label: 'Followers', color: 'var(--chart-1)' },
            { key: 'reach', label: 'Reach', color: 'var(--chart-2)' },
          ]}
          loading={metricsLoading}
        />
        {metricsLoading ? (
          <Skeleton className="h-full min-h-[260px] w-full" />
        ) : (
          <EngagementRateGauge rate={metrics?.engagementRate ?? null} />
        )}
      </div>

      {/* Scores */}
      <div className="grid gap-4 lg:grid-cols-2">
        <FakeFollowerIndicator
          authenticityScore={scores?.authenticityScore ?? null}
          reasons={scores?.fakeFollowerReasons}
          loading={scoresLoading}
        />
        <QualityScoreDisplay
          overallScore={scores?.qualityScore ?? null}
          engagementConsistency={scores?.engagementConsistency}
          postingFrequency={scores?.postingFrequency}
          audienceMatchScore={scores?.audienceMatchScore}
          loading={scoresLoading}
        />
      </div>

      {/* Audience demographics — no backend data source yet */}
      <div className="rounded-lg border border-dashed border-border p-6 text-center">
        <p className="text-sm font-medium text-muted-foreground">Audience demographics</p>
        <p className="mt-1 text-sm text-muted-foreground">
          Coming soon — age, gender, and location breakdowns require a demographics data pipeline
          that hasn't been built yet.
        </p>
      </div>
    </div>
  );
}
