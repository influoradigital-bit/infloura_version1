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
import { BrandSafetyBadge } from '@/components/analytics/BrandSafetyBadge';
import { ContentPerformancePanel } from '@/components/analytics/ContentPerformancePanel';
import { AudienceDemographicsPanel } from '@/components/analytics/AudienceDemographicsPanel';

import { useCreatorMetrics } from '@/hooks/analytics/useCreatorMetrics';
import { useCreatorScores } from '@/hooks/analytics/useCreatorScores';
import { useContentPerformance } from '@/hooks/analytics/useContentPerformance';
import { useCreatorDemographics } from '@/hooks/analytics/useCreatorDemographics';
import { useBrandBillingAccess } from '@/hooks/brand/useBrandBillingAccess';
import { UpgradeGate } from '@/components/brand/billing/UpgradeGate';
import { isApiLive } from '@/lib/api';
import { demoCreators } from '@/lib/demo-data';
import type { AnalyticsDateRange } from '@/lib/types';

/**
 * Individual creator analytics — route: /brand/analytics/:creatorId.
 * Maps ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md section 1.3, adapted from the
 * spec's Next.js dynamic route (/dashboard/analytics/[creatorId]) to this
 * repo's React Router convention (:creatorId param, src/pages file).
 *
 * Audience demographics: the real AudienceDemographicsPanel, fed by GET
 * /analytics/creators/{id}/demographics (AudienceDemographicsJob fills it weekly). F-0953
 * replaced a "coming soon — not built yet" placeholder that had become false.
 *
 * BrandSafetyBadge is mounted below (Brand Surface Audit PARTIAL #5,
 * wiki/reports/brand-feature-audit.md item 5) — the earlier comment here
 * claiming it was "intentionally not built" because BrandSafetyScoreService
 * isn't built was stale: the service exists (ScoreCalculationJob ->
 * BrandSafetyScoreService, Kabir-signed-off wiki/errors/wave-c-task-c3-security-review.md)
 * and this page's own `useCreatorScores` already carries brandSafetyScore/
 * garmFlags from GET /analytics/creators/{creatorId}/scores — same read path
 * creator-analytics.tsx uses for the creator self-view. `brandSafetyScore`
 * is still null whenever the score hasn't been computed yet for a given
 * creator (job hasn't run / disabled by default), which BrandSafetyBadge
 * already renders as an explicit "not yet available" empty state, so no
 * placeholder/fake data is introduced here.
 *
 * F-0441: `demoCreators` is a hardcoded fixture (fake displayName/isVerified/location). It
 * is gated on `isApiLive()` — the same live-mode check this codebase already uses elsewhere
 * (e.g. brand-analytics.tsx's `live` const) — so a fabricated creator identity can never
 * render in the header when the app is talking to the real backend, even if a demo id ever
 * collided with a real `creatorId`.
 */
export default function BrandCreatorAnalyticsPage() {
  const { creatorId } = useParams();
  const navigate = useNavigate();

  const dateRange: AnalyticsDateRange = React.useMemo(() => {
    const end = new Date();
    const start = new Date(end.getTime() - 30 * 24 * 60 * 60 * 1000);
    return { start, end };
  }, []);

  const {
    data: metrics,
    loading: metricsLoading,
    error: metricsError,
    upgradeRequired: metricsUpgradeRequired,
  } = useCreatorMetrics(creatorId, dateRange);
  const { canManage: canManageBilling } = useBrandBillingAccess();
  const { data: scores, loading: scoresLoading, error: scoresError } = useCreatorScores(creatorId);
  const {
    data: content,
    loading: contentLoading,
    error: contentError,
    notImplemented: contentNotImplemented,
  } = useContentPerformance(creatorId);
  const {
    data: demographics,
    loading: demographicsLoading,
    error: demographicsError,
  } = useCreatorDemographics(creatorId);

  // F-0441: never surface the demo fixture while the app is in live mode — a brand on a real
  // backend must never see a fabricated creator identity (name/verified badge/location).
  const creator = isApiLive() ? undefined : demoCreators.find((c) => c.id === creatorId);

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

      {/* F-0886 — B39's per-creator monthly analytics limit 402s as UPGRADE_REQUIRED. Previously
          this fell into the generic error banner below as plain text with no way to act on it;
          now it renders the shared UpgradeGate instead of the (necessarily zeroed-out) metric
          tiles, which would otherwise misleadingly show "0" reach/impressions for a blocked view. */}
      {(metricsUpgradeRequired ? scoresError : metricsError || scoresError) && (
        <p className="text-sm text-destructive-foreground">
          Some data couldn't be loaded: {metricsUpgradeRequired ? scoresError : metricsError ?? scoresError}
        </p>
      )}

      {metricsUpgradeRequired ? (
        <UpgradeGate feature="analytics" canManageBilling={canManageBilling} />
      ) : (
        <>
          {/* Metric tiles */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <CreatorMetricsCard
              title="Followers"
              value={metrics?.followers ?? 0}
              format="compact"
              icon={Users}
              loading={metricsLoading}
            />
            {/* F-0953: these are per-post AVERAGES from the latest sync, not totals. The old
                "Avg. Views Per Post" card read the same field as impressions, so it is gone. */}
            <CreatorMetricsCard
              title="Avg. reach per post"
              value={metrics?.totalReach ?? 0}
              format="compact"
              icon={Eye}
              loading={metricsLoading}
            />
            <CreatorMetricsCard
              title="Avg. views per post"
              value={metrics?.totalImpressions ?? 0}
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
        </>
      )}

      {/* Scores */}
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

      {/* Content performance (per-post media) */}
      <ContentPerformancePanel
        data={content}
        loading={contentLoading}
        error={contentError}
        notImplemented={contentNotImplemented}
      />

      {/* F-0953: this said "Coming soon — … hasn't been built yet", but GET
          /analytics/creators/{id}/demographics exists and AudienceDemographicsJob fills it weekly. */}
      <AudienceDemographicsPanel
        data={demographics}
        loading={demographicsLoading}
        error={demographicsError}
      />
    </div>
  );
}
