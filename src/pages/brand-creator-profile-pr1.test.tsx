/**
 * PR-1 (BrandF.md §82b/§86.1) — brand-creator-profile.tsx used to call GET /creators/:id
 * (CreatorDtos.CreatorResponse, no track-record fields) and hardcode completedCampaigns/
 * avgRating/completionRate/onTimeDelivery/repeatClients/reviewCount to 0, rendering six
 * fabricated zeros as fact for EVERY creator, plus an affirmative false attestation
 * ("Based on verified brand collaborations") beside them.
 *
 * The fix switches to GET /creators/profile/:usernameOrId (CreatorPublicProfileResponse),
 * which carries real completedCampaigns/avgRating (avgRating is `null`, not `0`, when the
 * creator has no reviews — CreatorDiscoveryService.computeAvgRating). Fields the richer DTO
 * still doesn't carry (completionRate/onTimeDelivery/repeatClients/reviewCount) stay `null`
 * and render an honest "—" / no-count copy instead of a fabricated 0.
 *
 * This test asserts both ends of that fix:
 *  1. A creator with zero real reviews/campaigns renders honest empty states — never "0",
 *     never "0 stars", never the "Based on verified brand collaborations" attestation.
 *  2. A creator with real reviews/campaigns renders the real numbers and the attestation.
 *
 * Run: npx vitest run src/pages/brand-creator-profile-pr1.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import BrandCreatorProfilePage from './brand-creator-profile';

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

const UNRATED_CREATOR = {
  id: 'cp_unrated',
  username: 'newcreator',
  displayName: 'New Creator',
  bio: 'Just joined.',
  profilePhoto: null,
  coverPhoto: null,
  categories: ['Fashion'],
  languages: ['English'],
  city: 'Pune',
  platforms: [],
  totalFollowers: 5000,
  engagementRate: 2.1,
  scores: null,
  rateMin: null,
  rateMax: null,
  currency: null,
  isVerified: false,
  discoverable: true,
  completedCampaigns: 0,
  avgRating: null,
  saved: false,
};

const RATED_CREATOR = {
  ...UNRATED_CREATOR,
  id: 'cp_rated',
  username: 'establishedcreator',
  displayName: 'Established Creator',
  isVerified: true,
  completedCampaigns: 23,
  avgRating: 4.6,
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

describe('PR-1 — creator profile track-record fields are never fabricated zeros', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    similarMock.mockResolvedValue({ similar: [] });
    campaignsListMock.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('creator with zero real reviews/campaigns: honest empty states, no fabricated zeros, no false attestation', async () => {
    const user = userEvent.setup();
    getProfileMock.mockResolvedValue(UNRATED_CREATOR);
    renderProfile('cp_unrated');

    await waitFor(() => expect(screen.getByText('New Creator')).toBeInTheDocument());

    // Campaigns stat: real 0 (an honest true zero from a real field is fine) — but Rating
    // must NOT render as "0" / "0.0".
    expect(screen.getByText('Not yet rated')).toBeInTheDocument();
    expect(screen.queryByText('0.0')).not.toBeInTheDocument();

    // Work-quality metrics (no backend field at all): honest "—", never "0%".
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText('0%')).not.toBeInTheDocument();

    // Reviews tab headline: em dash, not "0", not fabricated star fill implied by a 0 rating.
    await user.click(screen.getByRole('tab', { name: /Reviews/i }));
    await waitFor(() => expect(screen.getByText('No reviews yet')).toBeInTheDocument());

    // The false attestation must not appear next to missing data.
    expect(screen.queryByText('Based on verified brand collaborations')).not.toBeInTheDocument();
    expect(screen.queryByText('All reviews are from completed campaigns')).not.toBeInTheDocument();
  });

  it('creator with real reviews/campaigns: real numbers render, attestation only appears with real data', async () => {
    const user = userEvent.setup();
    getProfileMock.mockResolvedValue(RATED_CREATOR);
    renderProfile('cp_rated');

    await waitFor(() => expect(screen.getByText('Established Creator')).toBeInTheDocument());

    // Real completedCampaigns count.
    expect(screen.getByText('23')).toBeInTheDocument();
    // Real avgRating, formatted to 1 decimal.
    expect(screen.getByText('4.6')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /Reviews/i }));
    await waitFor(() =>
      expect(screen.getByText('Based on verified brand collaborations')).toBeInTheDocument(),
    );
    // No fabricated review count sitting next to the real average (DTO has no reviewCount field).
    expect(screen.queryByText(/^0 reviews$/)).not.toBeInTheDocument();
  });
});
