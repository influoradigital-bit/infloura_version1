/**
 * F-0441 (mock-data-in-live-mode) — brand-creator-analytics.tsx.
 *
 * This page looked up `demoCreators.find((c) => c.id === creatorId)` with NO live-mode check
 * at all, unlike the app-wide demo banner (and sibling brand-analytics.tsx) which correctly
 * stay silent once `isApiLive()` is true. `demoCreators` ships fake displayName/isVerified/
 * location. If a real `creatorId` in live mode ever collided with one of the hardcoded demo
 * ids (e.g. `cr_1`), the brand would see a fabricated identity — a verified badge and name
 * that do not belong to the real creator — with no indication it's fake.
 *
 * This suite renders the real page in live mode with creatorId = 'cr_1' (one of the actual
 * ids baked into src/lib/demo-data.ts's demoCreators, whose displayName is "Priya Creates"
 * and isVerified is true) and asserts the demo identity never renders — the page must fall
 * back to the honest "no profile data" state (bare creatorId, no verified badge) instead.
 *
 * Run: npx vitest run src/pages/__tests__/brand-creator-analytics.live-demo-gate.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { demoCreators } from '@/lib/demo-data';
import BrandCreatorAnalyticsPage from '../brand-creator-analytics';

// Sanity check the fixture this suite depends on: demoCreators really does contain an id
// this test can collide with, and that entry really is fake/verified data.
const DEMO_MATCH = demoCreators.find((c) => c.id === 'cr_1');
if (!DEMO_MATCH) {
  throw new Error('Fixture assumption broken: demo-data.ts no longer has an id "cr_1" entry');
}

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
  };
});

vi.mock('@/hooks/analytics/useCreatorMetrics', () => ({
  useCreatorMetrics: () => ({ data: null, loading: false, error: null, refresh: vi.fn() }),
}));
vi.mock('@/hooks/analytics/useCreatorScores', () => ({
  useCreatorScores: () => ({ data: null, loading: false, error: null, notFound: false, refresh: vi.fn() }),
}));
vi.mock('@/hooks/analytics/useContentPerformance', () => ({
  useContentPerformance: () => ({
    data: null,
    loading: false,
    error: null,
    notImplemented: false,
    refresh: vi.fn(),
  }),
}));

vi.mock('@/components/analytics/CreatorMetricsCard', () => ({
  CreatorMetricsCard: ({ title }: { title: string }) => <div data-testid={`metric-${title}`} />,
}));
vi.mock('@/components/analytics/MetricsTrendChart', () => ({
  MetricsTrendChart: () => <div data-testid="trend-chart" />,
}));
vi.mock('@/components/analytics/EngagementRateGauge', () => ({
  EngagementRateGauge: () => <div data-testid="engagement-gauge" />,
}));
vi.mock('@/components/analytics/FakeFollowerIndicator', () => ({
  FakeFollowerIndicator: () => <div data-testid="fake-follower" />,
}));
vi.mock('@/components/analytics/QualityScoreDisplay', () => ({
  QualityScoreDisplay: () => <div data-testid="quality-score" />,
}));
vi.mock('@/components/analytics/BrandSafetyBadge', () => ({
  BrandSafetyBadge: () => <div data-testid="brand-safety" />,
}));
vi.mock('@/components/analytics/ContentPerformancePanel', () => ({
  ContentPerformancePanel: () => <div data-testid="content-performance" />,
}));

function renderPage(creatorId: string) {
  return render(
    <MemoryRouter initialEntries={[`/brand/analytics/${creatorId}`]}>
      <Routes>
        <Route path="/brand/analytics/:creatorId" element={<BrandCreatorAnalyticsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('F-0441 — demo creator fixture never renders in live mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not show the fabricated demo displayName when live and creatorId collides with a demo id', () => {
    renderPage('cr_1');

    // The old bug: this would be on screen because demoCreators.find() ran unconditionally.
    expect(screen.queryByText(DEMO_MATCH!.displayName)).not.toBeInTheDocument();
    // Honest fallback: the raw id, per the page's own `creator?.displayName ?? creatorId`.
    expect(screen.getByText('cr_1')).toBeInTheDocument();
  });

  it('does not show the demo fixture\'s verified badge in live mode', () => {
    renderPage('cr_1');

    expect(screen.queryByLabelText('Verified creator')).not.toBeInTheDocument();
  });

  it('does not show the demo fixture\'s location in live mode', () => {
    renderPage('cr_1');

    if (DEMO_MATCH!.location) {
      expect(screen.queryByText(DEMO_MATCH!.location)).not.toBeInTheDocument();
    }
  });
});
