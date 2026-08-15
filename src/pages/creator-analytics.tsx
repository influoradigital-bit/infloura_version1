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
import { ContentPerformancePanel } from '@/components/analytics/ContentPerformancePanel';
import { CreatorReceivedReviews } from '@/components/creator/creator-received-reviews';
import { useCreatorOwnMedia } from '@/hooks/creator/useCreatorOwnMedia';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
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
/** Default window: last 30 days (inclusive of today), matching prior hardcoded behavior. */
function defaultDateRange(): AnalyticsDateRange {
  const end = new Date();
  const start = new Date(end.getTime() - 30 * 24 * 60 * 60 * 1000);
  return { start, end };
}

/** yyyy-mm-dd for a native <input type="date"> value, in UTC to match ISO serialization. */
function toDateInputValue(d: Date): string {
  return d.toISOString().slice(0, 10);
}

function parseDateInputStart(value: string): Date {
  return new Date(`${value}T00:00:00.000Z`);
}

function parseDateInputEnd(value: string): Date {
  return new Date(`${value}T23:59:59.999Z`);
}

export default function CreatorAnalyticsPage() {
  // CR-69: was a useMemo(() => ..., []) fixed to the last 30 days with no way
  // to change it, despite the backend (CreatorAnalyticsController.getMyMetrics)
  // accepting arbitrary startDate/endDate. Now real, user-editable state —
  // default view is unchanged (last 30 days).
  const [dateRange, setDateRange] = React.useState<AnalyticsDateRange>(defaultDateRange);

  const handleStartDateChange = (value: string) => {
    if (!value) return;
    const start = parseDateInputStart(value);
    if (Number.isNaN(start.getTime())) return;
    setDateRange((prev) => (start > prev.end ? prev : { ...prev, start }));
  };

  const handleEndDateChange = (value: string) => {
    if (!value) return;
    const end = parseDateInputEnd(value);
    if (Number.isNaN(end.getTime())) return;
    setDateRange((prev) => (end < prev.start ? prev : { ...prev, end }));
  };

  const handleResetToLast30Days = () => setDateRange(defaultDateRange());

  const rangeDays = Math.max(
    1,
    Math.round((dateRange.end.getTime() - dateRange.start.getTime()) / (24 * 60 * 60 * 1000)),
  );

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
    notFound: scoresNotFound,
    refresh: refreshScores,
  } = useCreatorScores(CREATOR_ANALYTICS_SELF);
  const {
    data: demographics,
    loading: demographicsLoading,
    error: demographicsError,
    refresh: refreshDemographics,
  } = useCreatorDemographics(CREATOR_ANALYTICS_SELF);
  // creator-missing-0804 — the creator's own per-post content performance (GET /me/media).
  const {
    data: myMedia,
    loading: myMediaLoading,
    error: myMediaError,
    reload: reloadMyMedia,
  } = useCreatorOwnMedia();

  // C27: scoresError no longer fires for a 404 SCORE_NOT_FOUND (useCreatorScores
  // treats that as an honest empty state via `notFound`) — only a real load
  // failure trips the page-level banner below.
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
            Track your reach, engagement, and audience growth over the last {rangeDays} days.
          </p>
          {!isApiLive() && (
            <p className="mt-2 text-xs text-muted-foreground">
              Demo data — connect a live API (`VITE_API_MODE=live`) for your real metrics.
            </p>
          )}

          <div className="mt-3 flex flex-wrap items-end gap-2">
            <div className="flex flex-col gap-1">
              <label htmlFor="analytics-start-date" className="text-xs font-medium text-muted-foreground">
                From
              </label>
              <Input
                id="analytics-start-date"
                type="date"
                value={toDateInputValue(dateRange.start)}
                max={toDateInputValue(dateRange.end)}
                onChange={(e) => handleStartDateChange(e.target.value)}
                className="h-8 w-[150px]"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label htmlFor="analytics-end-date" className="text-xs font-medium text-muted-foreground">
                To
              </label>
              <Input
                id="analytics-end-date"
                type="date"
                value={toDateInputValue(dateRange.end)}
                min={toDateInputValue(dateRange.start)}
                max={toDateInputValue(new Date())}
                onChange={(e) => handleEndDateChange(e.target.value)}
                className="h-8 w-[150px]"
              />
            </div>
            <Button size="sm" variant="outline" onClick={handleResetToLast30Days}>
              Last 30 days
            </Button>
          </div>
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
                title={`Follower & Reach Trend (${rangeDays}d)`}
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

            {scoresNotFound && !scoresLoading && (
              <Alert>
                <AlertTitle>Your Influora score is on its way</AlertTitle>
                <AlertDescription>
                  Your Influora score will appear once you have enough activity.
                </AlertDescription>
              </Alert>
            )}

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

            {/* creator-missing-0804 — your own per-post content performance + received reviews */}
            <ContentPerformancePanel
              data={myMedia}
              loading={myMediaLoading}
              error={myMediaError}
              onRetry={reloadMyMedia}
            />

            <CreatorReceivedReviews />
          </div>
        )}
      </div>
    </CreatorLayout>
  );
}
