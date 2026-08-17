/**
 * F-0260 (fabricated-zero-metrics) — brand-creator-profile.tsx.
 *
 * buildLiveCreatorView hardcoded avgLikes/avgComments/avgViews to a literal `0`, and
 * audience.gender to `{ female: 0, male: 0, other: 0 }`. DiscoveryDtos.
 * CreatorPublicProfileResponse (the real live-mode DTO, `CreatorPublicProfile` in src/lib/api.ts)
 * carries none of those fields at all, so every live creator printed "0 Avg Likes" and
 * "Female 0% Male 0%" as if measured. The fix renders an explicit absent state ("—" / "Not
 * available") instead, matching the pattern this file already uses for `stats.rating` and
 * `audience.authenticity`.
 *
 * Run: npx vitest run src/pages/__tests__/brand-creator-profile.absent-metrics.test.tsx
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

// A real CreatorPublicProfile response. The DTO has no avgLikes/avgComments/avgViews/gender
// fields — this object is exactly what the live server actually returns, not a partial fixture.
const CREATOR_WITH_NO_ENGAGEMENT_STATS = {
  id: 'cp_no_stats',
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

describe('F-0260 — absent avg-likes/avg-views/gender never renders as a fabricated 0', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    similarMock.mockResolvedValue({ similar: [] });
    campaignsListMock.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('a real creator whose DTO carries no engagement-stat fields renders "—", never "0"', async () => {
    getProfileMock.mockResolvedValue(CREATOR_WITH_NO_ENGAGEMENT_STATS);
    renderProfile('cp_no_stats');

    await waitFor(() => expect(screen.getByText('Quiet Creator')).toBeInTheDocument());

    // Stats grid: Avg Likes / Avg Views must render the absent-state dash.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);
    // Never a fabricated "0" sitting under "Avg Likes"/"Avg Views" labels.
    expect(screen.queryByText('Avg Likes')?.closest('div')?.textContent).not.toMatch(/^0$/);
    expect(screen.queryByText('Avg Views')?.closest('div')?.textContent).not.toContain('0Avg Views');
  });

  it('gender split renders "Not available" instead of a fabricated 0/0/0 split', async () => {
    const user = userEvent.setup({ delay: null });
    getProfileMock.mockResolvedValue(CREATOR_WITH_NO_ENGAGEMENT_STATS);
    renderProfile('cp_no_stats');

    await waitFor(() => expect(screen.getByText('Quiet Creator')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /Audience/i }));

    await waitFor(() => expect(screen.getByText('Gender Split')).toBeInTheDocument());
    expect(screen.getAllByText('Not available').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText(/Female 0%/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Male 0%/)).not.toBeInTheDocument();
  });
});
