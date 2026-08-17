/**
 * F-0295 (absent-metric-surfaces-missed-in-scope) — brand-creator-profile.tsx.
 *
 * buildLiveCreatorView sets audience.ageGroups and audience.topCities to `[]` for every live
 * creator (DiscoveryDtos.CreatorPublicProfileResponse has no audience-demographics fields at
 * all), and the Audience tab renders both with a bare `.map()` under the "Age Distribution" /
 * "Top Cities" headings — an empty box with no "Not available" state. This is the same class of
 * bug as F-0260 (audience.gender rendering a fabricated "Female 0% Male 0%"), fixed three lines
 * away in the same file with an explicit `== null ? <absent state> : <real content>` branch —
 * ageGroups/topCities were left out of that fix's scope.
 *
 * This suite renders the real page with a live creator whose ageGroups/topCities come back
 * empty (the actual shape the live DTO produces today) and asserts an absent-state message is
 * shown under each heading — not a grep for source text, a rendered assertion, so a fix that
 * only special-cases `null` and still renders nothing for `[]` does not pass this either.
 *
 * Run: npx vitest run src/pages/__tests__/brand-creator-profile.absent-audience.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import BrandCreatorProfilePage from '../brand-creator-profile';

const getProfileMock = vi.fn();
const similarMock = vi.fn();
const campaignsListMock = vi.fn();

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
    },
  };
});

// A real CreatorPublicProfile response — the DTO has no audience-demographics fields at all, so
// buildLiveCreatorView produces ageGroups: [] / topCities: [] for this creator today.
const CREATOR_WITH_NO_AUDIENCE_DATA = {
  id: 'cp_no_audience',
  username: 'quietcreator',
  displayName: 'Quiet Creator',
  bio: 'Recently onboarded.',
  profilePhoto: null,
  coverPhoto: null,
  categories: ['Fashion'],
  languages: ['English'],
  city: 'Pune',
  platforms: [],
  totalFollowers: 12000,
  engagementRate: 3.4,
  scores: null,
  rateMin: null,
  rateMax: null,
  currency: null,
  isVerified: false,
  discoverable: true,
  completedCampaigns: 2,
  avgRating: 4.2,
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

describe('F-0295 — empty ageGroups/topCities never renders as a silent empty box', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    similarMock.mockResolvedValue({ similar: [] });
    campaignsListMock.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('Age Distribution shows an explicit absent state, not an empty box, for a live creator with no age data', async () => {
    const user = userEvent.setup({ delay: null });
    getProfileMock.mockResolvedValue(CREATOR_WITH_NO_AUDIENCE_DATA);
    renderProfile('cp_no_audience');

    await waitFor(() => expect(screen.getByText('Quiet Creator')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /Audience/i }));

    await waitFor(() => expect(screen.getByText('Age Distribution')).toBeInTheDocument());
    const ageCard = screen.getByText('Age Distribution').closest('div');
    expect(ageCard).not.toBeNull();
    expect(ageCard?.textContent).toMatch(/not available/i);
  });

  it('Top Cities shows an explicit absent state, not an empty box, for a live creator with no city data', async () => {
    const user = userEvent.setup({ delay: null });
    getProfileMock.mockResolvedValue(CREATOR_WITH_NO_AUDIENCE_DATA);
    renderProfile('cp_no_audience');

    await waitFor(() => expect(screen.getByText('Quiet Creator')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /Audience/i }));

    await waitFor(() => expect(screen.getByText('Top Cities')).toBeInTheDocument());
    const citiesCard = screen.getByText('Top Cities').closest('div');
    expect(citiesCard).not.toBeNull();
    expect(citiesCard?.textContent).toMatch(/not available/i);
  });
});
