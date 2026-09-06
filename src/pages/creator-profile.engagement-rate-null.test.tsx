/**
 * F-0662 (dishonest-null-render) — creator-profile.tsx "Profile Stats" card.
 *
 * `CreatorProfileSelfResponse.engagementRate` was correctly retyped as `number | null` in
 * F-0464 (see api.ts's doc comment on the field: the backing column is never initialised at
 * profile creation and stays null until `applyAggregatedStats` runs after a platform actually
 * syncs) — but this render site still interpolated it raw: `<p>{profile.engagementRate}%</p>`.
 * React renders `null` as nothing, so a creator who has never synced a platform saw a bare "%"
 * with no number — absent data presented as a zero-width measurement, not a crash but still a
 * fabrication by omission.
 *
 * This proves the fix renders the same honest "Not available yet" idiom already used elsewhere
 * in this codebase for the identical nullable field (see creator-verified-metrics.tsx's
 * `NOT_AVAILABLE_YET` handling of `engagement_rate`), never a bare "%" and never a fabricated
 * number such as "0%".
 *
 * Run: node_modules/.bin/vitest run src/pages/creator-profile.engagement-rate-null.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorProfilePage from './creator-profile';
import type { CreatorProfileSelfResponse } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

const getMeMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorProfile: {
        ...actual.api.creatorProfile,
        getMe: (...a: unknown[]) => getMeMock(...a),
      },
    },
  };
});

const BASE_PROFILE: CreatorProfileSelfResponse = {
  id: 'cr_1',
  userId: 'user_1',
  displayName: 'Priya Creates',
  username: 'priya_creates',
  bio: 'Fashion & lifestyle content creator.',
  avatarUrl: '',
  coverImageUrl: '',
  city: 'Mumbai',
  phone: null,
  categories: ['Fashion & Lifestyle'],
  languages: ['Hindi', 'English'],
  contentStyles: [],
  platforms: [],
  rateMin: null,
  rateMax: null,
  currency: 'INR',
  discoverable: true,
  verified: false,
  totalFollowers: 0,
  engagementRate: null,
  onboardingComplete: true,
  profileCompleteness: 40,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/profile']}>
      <CreatorProfilePage />
    </MemoryRouter>,
  );
}

describe('CreatorProfilePage — F-0662 engagementRate honest-empty render', () => {
  beforeEach(() => {
    getMeMock.mockReset();
  });

  it('a creator who has never synced a platform (engagementRate: null) sees an explicit "Not available yet", never a bare "%"', async () => {
    getMeMock.mockResolvedValue({ ...BASE_PROFILE, engagementRate: null });

    renderPage();

    const notAvailable = await screen.findByText(/not available yet/i);
    expect(notAvailable).toBeInTheDocument();

    // The dishonest old render produced a lone "%" character with no digits in front of it
    // inside the Engagement Rate tile. Assert that string never appears anywhere on the page.
    expect(screen.queryByText(/^%$/)).not.toBeInTheDocument();
    // Guard against the other dishonest fallback this ticket explicitly forbids: a fabricated 0%.
    expect(screen.queryByText('0%')).not.toBeInTheDocument();
  });

  it('a creator with a real, synced engagementRate still renders the number normally', async () => {
    getMeMock.mockResolvedValue({ ...BASE_PROFILE, engagementRate: 4.2 });

    renderPage();

    expect(await screen.findByText('4.2%')).toBeInTheDocument();
    expect(screen.queryByText(/not available yet/i)).not.toBeInTheDocument();
  });
});
