/**
 * The engaged audience is creator-only (2026-09-26, owner decision E): the creator's own analytics
 * page turns on the "Engaged this month" section; the brand's view of a creator never shows it,
 * even when the demographics response carries the engaged fields. Both pages render the REAL
 * AudienceDemographicsPanel with the same demographics data.
 *
 * Run: npx vitest run src/pages/__tests__/analytics.engaged-audience-pages.test.tsx
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import type { CreatorDemographics } from '@/lib/types';
import CreatorAnalyticsPage from '../creator-analytics';
import BrandCreatorAnalyticsPage from '../brand-creator-analytics';

const DATA: CreatorDemographics = {
  hasData: true,
  ageGenderBreakdown: { '18-24_female': 640, '18-24_male': 330, '18-24_unknown': 30 },
  countryBreakdown: { IN: 1000 },
  cityBreakdown: { 'Mumbai, Maharashtra': 1000 },
  localeBreakdown: null,
  fetchedAt: '2026-09-24T03:30:00Z',
  engagedAgeGenderBreakdown: { '18-24_female': 150, '25-34_male': 50 },
  engagedCountryBreakdown: { IN: 200 },
  engagedCityBreakdown: { 'Delhi, Delhi': 200 },
  engagedFetchedAt: '2026-09-25T03:30:00Z',
};

vi.mock('@/hooks/analytics/useCreatorDemographics', () => ({
  useCreatorDemographics: () => ({ data: DATA, loading: false, error: null, refresh: vi.fn() }),
}));

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/hooks/analytics/useCreatorMetrics', async () => {
  const actual = await vi.importActual<typeof import('@/hooks/analytics/useCreatorMetrics')>(
    '@/hooks/analytics/useCreatorMetrics',
  );
  return {
    ...actual,
    useCreatorMetrics: () => ({ data: null, loading: false, error: null, upgradeRequired: false, refresh: vi.fn() }),
  };
});
vi.mock('@/hooks/brand/useBrandBillingAccess', () => ({
  useBrandBillingAccess: () => ({ role: 'OWNER', canManage: true, isLoading: false }),
}));
vi.mock('@/hooks/analytics/useCreatorScores', () => ({
  useCreatorScores: () => ({ data: null, loading: false, error: null, notFound: false, refresh: vi.fn() }),
}));
vi.mock('@/components/analytics/CreatorMetricsCard', () => ({
  CreatorMetricsCard: () => <div />,
}));
vi.mock('@/components/analytics/MetricsTrendChart', () => ({ MetricsTrendChart: () => <div /> }));
vi.mock('@/components/analytics/EngagementRateGauge', () => ({ EngagementRateGauge: () => <div /> }));
vi.mock('@/components/analytics/FakeFollowerIndicator', () => ({ FakeFollowerIndicator: () => <div /> }));
vi.mock('@/components/analytics/QualityScoreDisplay', () => ({ QualityScoreDisplay: () => <div /> }));
vi.mock('@/components/analytics/BrandSafetyBadge', () => ({ BrandSafetyBadge: () => <div /> }));
vi.mock('@/components/analytics/ContentPerformancePanel', () => ({ ContentPerformancePanel: () => <div /> }));
vi.mock('@/components/analytics/AccountInsightsCard', () => ({ AccountInsightsCard: () => <div /> }));

describe('engaged audience is shown on the creator page only', () => {
  it("creator's own analytics page shows the Engaged this month section", async () => {
    render(
      <MemoryRouter initialEntries={['/creator/analytics']}>
        <CreatorAnalyticsPage />
      </MemoryRouter>,
    );
    expect(await screen.findByRole('region', { name: 'Engaged this month' })).toBeInTheDocument();
    expect(screen.getByText('Delhi, Delhi')).toBeInTheDocument();
    expect(screen.getByText('Women 64% · Men 33% · Unknown 3%')).toBeInTheDocument();
  });

  it("brand's view of the same creator never shows it", async () => {
    render(
      <MemoryRouter initialEntries={['/brand/analytics/cr_42']}>
        <Routes>
          <Route path="/brand/analytics/:creatorId" element={<BrandCreatorAnalyticsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    // The follower panel is on screen (so the absence below is not a render failure) ...
    expect(await screen.findByRole('region', { name: 'Followers' })).toBeInTheDocument();
    expect(screen.getByText('Women 64% · Men 33% · Unknown 3%')).toBeInTheDocument();
    // ... but nothing from the engaged audience.
    expect(screen.queryByText('Engaged this month')).toBeNull();
    expect(screen.queryByText('Delhi, Delhi')).toBeNull();
    expect(screen.queryByText('Women 75% · Men 25%')).toBeNull();
  });
});
