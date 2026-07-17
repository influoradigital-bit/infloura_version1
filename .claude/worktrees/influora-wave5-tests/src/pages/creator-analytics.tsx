import * as React from 'react';
import { AlertTriangle, Eye, Loader2, TrendingUp, Users } from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { CreatorMetricsCard } from '@/components/analytics/CreatorMetricsCard';
import { MetricsTrendChart } from '@/components/analytics/MetricsTrendChart';
import { EngagementRateGauge } from '@/components/analytics/EngagementRateGauge';
import { FakeFollowerIndicator } from '@/components/analytics/FakeFollowerIndicator';
import { QualityScoreDisplay } from '@/components/analytics/QualityScoreDisplay';
import { BrandSafetyBadge } from '@/components/analytics/BrandSafetyBadge';
import { AudienceDemographicsPanel } from '@/components/analytics/AudienceDemographicsPanel';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import {
  CREATOR_ANALYTICS_SELF,
  useCreatorMetrics,
} from '@/hooks/analytics/useCreatorMetrics';
import { useCreatorScores } from '@/hooks/analytics/useCreatorScores';
import { useCreatorDemographics } from '@/hooks/analytics/useCreatorDemographics';
import { isApiLive } from '@/lib/api';
import type { AnalyticsDateRange } from '@/lib/types';

/**
 * Creator-facing analytics — route: /creator/analytics (Ananya A5 / Task #35).
 * Principal-scoped reads via GET /creator/analytics/me/{metrics,scores,demographics}.
 * Reuses the same analytics components as brand-creator-analytics.tsx; hooks
 * route to creatorAnalytics client when creatorId is CREATOR_ANALYTICS_SELF.
 */
export default function CreatorAnalyticsPage() {
  const dateRange: AnalyticsDateRange = React.useMemo(() => {
    const end = new Date();
    const start = new Date(end.getTime() - 30 * 24 * 60 * 60 * 1000);
    return { start, end };
  }, []);

  const {
    data: metrics,
    loading: metricsLoading,
    error: metricsError,
    refresh: refreshMetrics,
  } = useCreatorMetrics(CREATOR_ANALYTICS_SELF, dateRange);
  const {
    data: scores,
    loading: scoresLoading,
    error: scoresError,
    refresh: refreshScores,
  } = useCreatorScores(CREATOR_ANALYTICS_SELF);
  const {
    data: demographics,
    loading: demographicsLoading,
    error: demographicsError,
    refresh: refreshDemographics,
  } = useCreatorDemographics(CREATOR_ANALYTICS_SELF);

  const hasLoadError = Boolean(metricsError || scoresError || demographicsError);
  const isInitialLoading = metricsLoading && scoresLoading && demographicsLoading;
  const hasNoMetrics =
    !metricsLoading &&
    !metricsError &&
    metrics !== null &&
    metrics.totalReach === 0 &&
    metrics.totalImpressions === 0 &&
    metrics.trendData.length === 0;

  const handleRetry = () => {
    void refreshMetrics();
    void refreshScores();
    void refreshDemographics();
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-6xl px-4 py-6">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Analytics</h1>
          <p className="text-muted-foreground">
            Track your reach, engagement, and audience growth over the last 30 days.
          </p>
          {!isApiLive() && (
            <p className="mt-2 text-xs text-muted-foreground">
              Demo data — connect a live API (`VITE_API_MODE=live`) for your real metrics.
            </p>
          )}
        </div>

        {hasLoadError && (
          <Alert className="mb-5" variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Couldn&apos;t load analytics</AlertTitle>
            <AlertDescription>
              {[metricsError, scoresError, demographicsError].filter(Boolean).join(' · ')}
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={handleRetry}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        {isInitialLoading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label="Loading analytics" />
          </div>
        ) : (
          <div className="space-y-6">
            {hasNoMetrics && !hasLoadError && (
              <Alert>
                <AlertTitle>No metrics yet</AlertTitle>
                <AlertDescription>
                  Connect Instagram in Settings and complete a few campaigns — your growth metrics
                  will appear here after the first sync.
                </AlertDescription>
              </Alert>
            )}

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

            <div className="grid gap-4 lg:grid-cols-3">
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
              <BrandSafetyBadge
                brandSafetyScore={scores?.brandSafetyScore ?? null}
                garmFlags={scores?.garmFlags}
                loading={scoresLoading}
              />
            </div>

            <AudienceDemographicsPanel
              data={demographics}
              loading={demographicsLoading}
              error={demographicsError}
            />
          </div>
        )}
      </div>
    </CreatorLayout>
  );
}
