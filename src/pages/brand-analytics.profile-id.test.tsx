/**
 * Brand analytics is keyed on the CreatorProfile id.
 *
 * `AnalyticsService` routes the caller-supplied id through
 * `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId`, which matches
 * `meta_oauth_tokens.creator_profile_id`. Both brand surfaces used to hand it a USER id instead:
 *   - the roster was built from `Deal.counterpartyId` (the creator's User id), so every
 *     per-creator metrics call and every "open full analytics" link carried an id that could
 *     never resolve;
 *   - the creator profile page passed its `:id` route param straight through, and links into that
 *     page carry a User id in practice (the deal room's "View Profile" passes
 *     `Deal.counterpartyId`), while the param itself may be a profile id, a user id OR a username.
 * Either way the endpoint 403s, which the UI renders as the genuine "creator has not consented"
 * state — so a plain id bug was indistinguishable from a real authorization decision.
 *
 * Run: npx vitest run src/pages/brand-analytics.profile-id.test.tsx
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// jsdom has no IntersectionObserver; the page's CountUp KPI tiles construct one at render and
// would otherwise throw the whole tree away before the roster renders. Same stub as
// brand-creator-profile-provenance.test.tsx.
class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return [];
  }
}
// @ts-expect-error — jsdom has no IntersectionObserver global.
global.IntersectionObserver = MockIntersectionObserver;

const dealsListMock = vi.fn();
const getCreatorMetricsMock = vi.fn();
const getProfileMock = vi.fn();
const similarMock = vi.fn();
const campaignsListMock = vi.fn();
const getCreatorDemographicsMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsListMock(...a) },
      creators: {
        ...actual.api.creators,
        getProfile: (...a: unknown[]) => getProfileMock(...a),
        similar: (...a: unknown[]) => similarMock(...a),
      },
      campaigns: { ...actual.api.campaigns, list: (...a: unknown[]) => campaignsListMock(...a) },
      analytics: {
        ...actual.api.analytics,
        getCreatorMetrics: (...a: unknown[]) => getCreatorMetricsMock(...a),
        getCreatorDemographics: (...a: unknown[]) => getCreatorDemographicsMock(...a),
      },
    },
  };
});

import BrandAnalyticsPage from './brand-analytics';
import BrandCreatorProfilePage from './brand-creator-profile';

/** The two id kinds a brand-side Deal carries for the same creator. */
const CREATOR_USER_ID = 'usr_creator_0000000000001';
const CREATOR_PROFILE_ID = 'cp_creator_000000000001';

const DEAL = {
  id: 'deal_1',
  campaignId: 'camp_1',
  campaignName: 'Summer Launch',
  counterpartyId: CREATOR_USER_ID,
  counterpartyProfileId: CREATOR_PROFILE_ID,
  counterpartyName: 'Aarti Menon',
  status: 'IN_PROGRESS',
  dealValue: 40000,
  currency: 'INR',
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 2,
  escrowFunded: true,
};

describe('brand analytics roster — CreatorProfile id, never the User id', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // A real CreatorMetricsResponse shape: the page sums these fields, so `null` would throw
    // inside the aggregate and the roster card would never render.
    getCreatorMetricsMock.mockResolvedValue({
      totalReach: 1000,
      totalImpressions: 2000,
      totalEngagements: 100,
      followerGrowth: 10,
      engagementRate: 4.2,
      avgViewsPerPost: 500,
      trend: [],
    });
  });

  it('fetches per-creator metrics with the profile id and links to it', async () => {
    dealsListMock.mockResolvedValue([DEAL]);
    render(
      <MemoryRouter initialEntries={['/brand/analytics']}>
        <Routes>
          <Route path="/brand/analytics" element={<BrandAnalyticsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(getCreatorMetricsMock).toHaveBeenCalled());
    for (const call of getCreatorMetricsMock.mock.calls) {
      expect(call[0]).toBe(CREATOR_PROFILE_ID);
      expect(call[0]).not.toBe(CREATOR_USER_ID);
    }

    const link = await screen.findByRole('link', { name: /Aarti Menon/ });
    expect(link).toHaveAttribute('href', `/brand/analytics/${CREATOR_PROFILE_ID}`);
  });

  it('a deal with no profile id is left out rather than given a link that cannot resolve', async () => {
    dealsListMock.mockResolvedValue([{ ...DEAL, counterpartyProfileId: null }]);
    render(
      <MemoryRouter initialEntries={['/brand/analytics']}>
        <Routes>
          <Route path="/brand/analytics" element={<BrandAnalyticsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(dealsListMock).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: /Aarti Menon/ })).toBeNull();
    expect(getCreatorMetricsMock).not.toHaveBeenCalledWith(CREATOR_USER_ID, expect.anything(), expect.anything());
  });
});

describe('brand creator profile — demographics use the resolved profile id, not the route param', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    similarMock.mockResolvedValue({ similar: [] });
    campaignsListMock.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
    getCreatorDemographicsMock.mockResolvedValue({ hasData: false });
    getProfileMock.mockResolvedValue({
      id: CREATOR_PROFILE_ID,
      username: 'aarti',
      displayName: 'Aarti Menon',
      bio: null,
      profilePhoto: null,
      coverPhoto: null,
      categories: [],
      languages: [],
      city: null,
      platforms: [],
      totalFollowers: 1000,
      engagementRate: 3,
      scores: null,
      rateMin: null,
      rateMax: null,
      currency: null,
      isVerified: false,
      discoverable: true,
      completedCampaigns: 0,
      avgRating: null,
      saved: false,
    });
  });

  it('reached by USER id (as the deal room links it), demographics still ask for the profile id', async () => {
    render(
      <MemoryRouter initialEntries={[`/brand/creators/${CREATOR_USER_ID}`]}>
        <Routes>
          <Route path="/brand/creators/:id" element={<BrandCreatorProfilePage />} />
        </Routes>
      </MemoryRouter>,
    );

    // The profile endpoint tolerates any of the three id kinds, so it still gets the raw param.
    await waitFor(() => expect(getProfileMock).toHaveBeenCalledWith(CREATOR_USER_ID));
    // The analytics endpoint does not — it must get the id the profile row reported.
    await waitFor(() => expect(getCreatorDemographicsMock).toHaveBeenCalledWith(CREATOR_PROFILE_ID));
    expect(getCreatorDemographicsMock).not.toHaveBeenCalledWith(CREATOR_USER_ID);
  });
});
