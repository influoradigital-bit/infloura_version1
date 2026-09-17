/**
 * F-0795/F-0796 [ananya · 2026-09-17] — brand-creator-profile.tsx's Audience tab used to hardcode
 * age/gender/city breakdown to `null` for every live creator, citing a comment that claimed "no
 * backend audience-demographics endpoint exists at all" (F-0295/F-0260). That comment went stale:
 * GET /analytics/creators/{creatorId}/demographics (AnalyticsController.getDemographics) exists
 * and is real. The fix wires it, gated per
 * wiki/decisions/2026-09-15-brand-preconsent-visibility.md (LOCKED):
 *
 *  - Pre-consent (MetricsAuthorizationService 403s — no MetaOAuthToken pairing yet): render the
 *    locked band state, never an exact number, never an error toast.
 *  - Post-consent (the endpoint returns CreatorDemographicsResponse with hasData=true): render
 *    the real, exact percentages derived from the raw count breakdowns the DTO actually sends.
 *
 * Run: npx vitest run src/pages/brand-creator-profile.demographics-bands.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import BrandCreatorProfilePage from './brand-creator-profile';
import { ApiError } from '@/lib/api';

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
      creators: {
        ...actual.api.creators,
        getProfile: (...a: unknown[]) => getProfileMock(...a),
        similar: (...a: unknown[]) => similarMock(...a),
      },
      campaigns: {
        ...actual.api.campaigns,
        list: (...a: unknown[]) => campaignsListMock(...a),
      },
      analytics: {
        ...actual.api.analytics,
        getCreatorDemographics: (...a: unknown[]) => getCreatorDemographicsMock(...a),
      },
    },
  };
});

// A real CreatorPublicProfile response (DiscoveryDtos.CreatorPublicProfileResponse) — the
// profile fetch always succeeds regardless of the demographics-endpoint scenario under test.
const CREATOR = {
  id: 'cp_demo',
  username: 'democreator',
  displayName: 'Demo Creator',
  bio: 'Fashion creator.',
  profilePhoto: null,
  coverPhoto: null,
  categories: ['Fashion'],
  languages: ['English'],
  city: 'Pune',
  platforms: [],
  totalFollowers: 50000,
  engagementRate: 4.1,
  scores: null,
  rateMin: null,
  rateMax: null,
  currency: null,
  isVerified: false,
  discoverable: true,
  completedCampaigns: 3,
  avgRating: 4.5,
  saved: false,
};

function renderProfile(id: string) {
  return render(
    <MemoryRouter initialEntries={[`/brand/creators/${id}`]}>
      <Routes>
        <Route path="/brand/creators/:id" element={<BrandCreatorProfilePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function openAudienceTab() {
  const user = userEvent.setup({ delay: null });
  await waitFor(() => expect(screen.getByText('Demo Creator')).toBeInTheDocument());
  await user.click(screen.getByRole('tab', { name: /Audience/i }));
  await waitFor(() => expect(screen.getByText('Age Distribution')).toBeInTheDocument());
}

describe('F-0795/F-0796 — pre-consent bands vs. post-consent exact demographics', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getProfileMock.mockResolvedValue(CREATOR);
    similarMock.mockResolvedValue({ similar: [] });
    campaignsListMock.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('a 403 from the demographics endpoint renders the locked band state, never a percentage, never an error toast', async () => {
    getCreatorDemographicsMock.mockRejectedValue(
      new ApiError('FORBIDDEN', 'This workspace is not authorized to view metrics for that creator', 403),
    );
    renderProfile('cp_demo');
    await openAudienceTab();

    await waitFor(() => expect(screen.getAllByText('Locked pre-connection').length).toBeGreaterThanOrEqual(3));

    const ageCard = screen.getByText('Age Distribution').closest('div') as HTMLElement;
    const genderCard = screen.getByText('Gender Split').closest('div') as HTMLElement;
    const citiesCard = screen.getByText('Top Cities').closest('div') as HTMLElement;

    // Band text present in every section — identify, don't quantify.
    expect(within(ageCard).getByText(/hidden until this creator connects/i)).toBeInTheDocument();
    expect(within(genderCard).getByText(/hidden until this creator connects/i)).toBeInTheDocument();
    expect(within(citiesCard).getByText(/hidden until this creator connects/i)).toBeInTheDocument();

    // Never an exact number: no "%" anywhere in any of the three demographic cards.
    expect(ageCard.textContent).not.toMatch(/%/);
    expect(genderCard.textContent).not.toMatch(/%/);
    expect(citiesCard.textContent).not.toMatch(/%/);

    // Never the page-level error path (F-0795/F-0796 requires the band state, not a toast).
    expect(screen.queryByText('Could not load creator')).not.toBeInTheDocument();
  });

  it('a successful demographics response renders exact percentages, not bands', async () => {
    getCreatorDemographicsMock.mockResolvedValue({
      hasData: true,
      ageGenderBreakdown: { '18-24_female': 30, '18-24_male': 10, '25-34_female': 40, '25-34_male': 20 },
      countryBreakdown: { India: 90, Other: 10 },
      cityBreakdown: { Mumbai: 50, Delhi: 30, Pune: 20 },
      localeBreakdown: { 'en-IN': 100 },
      fetchedAt: '2026-09-17T00:00:00Z',
    });
    renderProfile('cp_demo');
    await openAudienceTab();

    const ageCard = screen.getByText('Age Distribution').closest('div') as HTMLElement;
    const genderCard = screen.getByText('Gender Split').closest('div') as HTMLElement;
    const citiesCard = screen.getByText('Top Cities').closest('div') as HTMLElement;

    // ageGenderBreakdown totals 100; "18-24" = 30+10=40%, "25-34" = 40+20=60%.
    await waitFor(() => expect(within(ageCard).getByText('60%')).toBeInTheDocument());
    expect(within(ageCard).getByText('40%')).toBeInTheDocument();

    // female = 30+40=70%, male = 10+20=30%.
    expect(within(genderCard).getByText(/Female 70%/)).toBeInTheDocument();
    expect(within(genderCard).getByText(/Male 30%/)).toBeInTheDocument();

    // cityBreakdown totals 100; Mumbai 50%, Delhi 30%, Pune 20%.
    expect(within(citiesCard).getByText('Mumbai')).toBeInTheDocument();
    expect(within(citiesCard).getByText('50%')).toBeInTheDocument();

    // Never the locked band copy once real data has arrived.
    expect(screen.queryByText('Locked pre-connection')).not.toBeInTheDocument();
  });
});
