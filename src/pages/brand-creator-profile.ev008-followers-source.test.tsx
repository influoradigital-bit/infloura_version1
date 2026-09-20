/**
 * EV-008 — the brand-facing creator profile's headline Followers/Engagement tiles and the
 * "Similar Creators" cards printed the creator's total with no provenance. Since F-0965 that total
 * is either Meta-synced (VERIFIED) or Marketplace/admin-imported (IMPORTED), and an imported figure
 * must never read like a verified one to a brand about to spend money on it.
 *
 * Run: npx vitest run src/pages/brand-creator-profile.ev008-followers-source.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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

const BASE = {
  id: 'cp_ev008',
  username: 'ev008creator',
  displayName: 'Ev Creator',
  bio: 'bio',
  profilePhoto: null,
  coverPhoto: null,
  categories: ['Fashion'],
  languages: ['English'],
  city: 'Pune',
  platforms: [],
  totalFollowers: 184000,
  engagementRate: 3.4,
  scores: null,
  rateMin: null,
  rateMax: null,
  currency: null,
  isVerified: true,
  discoverable: true,
  completedCampaigns: 0,
  avgRating: null,
  saved: false,
};

function renderProfile() {
  return render(
    <MemoryRouter initialEntries={['/brand/creators/cp_ev008']}>
      <Routes>
        <Route path="/brand/creators/:id" element={<BrandCreatorProfilePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('EV-008 — brand creator profile labels imported follower totals', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    similarMock.mockResolvedValue({ similar: [] });
    campaignsListMock.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('IMPORTED total: Followers and Engagement tiles say "imported, not verified"', async () => {
    getProfileMock.mockResolvedValue({ ...BASE, followersSource: 'IMPORTED' });
    renderProfile();
    await waitFor(() => expect(screen.getByText('Ev Creator')).toBeInTheDocument());

    expect(screen.getByText('Followers · imported, not verified')).toBeInTheDocument();
    expect(screen.getByText('Engagement · imported, not verified')).toBeInTheDocument();
    expect(screen.queryByText('Followers')).not.toBeInTheDocument();
  });

  it('VERIFIED total: plain "Followers" tile, no imported label', async () => {
    getProfileMock.mockResolvedValue({ ...BASE, followersSource: 'VERIFIED' });
    renderProfile();
    await waitFor(() => expect(screen.getByText('Ev Creator')).toBeInTheDocument());

    expect(screen.getByText('Followers')).toBeInTheDocument();
    expect(screen.getByText('Engagement')).toBeInTheDocument();
    expect(screen.queryByText(/imported, not verified/)).not.toBeInTheDocument();
  });

  it('NONE: no measured-looking follower count or engagement is shown', async () => {
    getProfileMock.mockResolvedValue({
      ...BASE,
      totalFollowers: 0,
      engagementRate: null,
      followersSource: 'NONE',
    });
    renderProfile();
    await waitFor(() => expect(screen.getByText('Ev Creator')).toBeInTheDocument());

    const tile = screen.getByText('Followers').closest('div');
    expect(tile?.textContent).toContain('—');
    expect(screen.queryByText('null%')).not.toBeInTheDocument();
  });

  it('Similar Creators: an imported peer total is labelled, a verified one is not', async () => {
    getProfileMock.mockResolvedValue({ ...BASE, followersSource: 'VERIFIED' });
    similarMock.mockResolvedValue({
      similar: [
        {
          id: 'peer_imp',
          username: 'peerimp',
          displayName: 'Peer Imported',
          avatarUrl: null,
          totalFollowers: 90000,
          engagementRate: 2.5,
          matchScore: 80,
          matchReasons: ['same_niche'],
          followersSource: 'IMPORTED',
        },
        {
          id: 'peer_ver',
          username: 'peerver',
          displayName: 'Peer Verified',
          avatarUrl: null,
          totalFollowers: 50000,
          engagementRate: 4.1,
          matchScore: 70,
          matchReasons: ['same_niche'],
          followersSource: 'VERIFIED',
        },
      ],
    });
    renderProfile();

    await waitFor(() => expect(screen.getByText('Peer Imported')).toBeInTheDocument());
    const imported = screen.getByText('Peer Imported').parentElement;
    const verified = screen.getByText('Peer Verified').parentElement;
    expect(imported?.textContent).toContain('followers · imported, not verified');
    expect(verified?.textContent).not.toContain('imported');
  });
});
