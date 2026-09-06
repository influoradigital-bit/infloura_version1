/**
 * F-0664 (dishonest-null-render) — creator-profile.tsx "Connected Accounts" card, PER-PLATFORM
 * engagement rate. Sibling of the already-fixed F-0662 (see
 * creator-profile.engagement-rate-null.test.tsx), one level down.
 *
 * `CreatorPlatformStat.engagementRate` (api.ts) mirrors `CreatorDtos.PlatformStatResponse`
 * (influora-api CreatorDtos.java:17), a `BigDecimal` column that is null until THAT platform's
 * own stats actually sync — independent of whether the profile-level aggregate has synced. The
 * FE type declared it a plain, non-null `number`, and this render site interpolated it raw:
 * `{social.engagementRate}% engagement`. For a connected-but-unsynced platform, React renders
 * the `null` as nothing, producing a bare "% engagement" with no digit in front of it — absent
 * data presented as a zero-width measurement, exactly F-0662's fabrication-by-omission one
 * level up.
 *
 * This proves the fix: the type is now honest (`number | null`) and the render shows an
 * explicit "not available yet" state, never a bare "% engagement" and never a fabricated "0%".
 *
 * Run: node_modules/.bin/vitest run src/pages/creator-profile.platform-engagement-rate-null.test.tsx
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

describe('CreatorProfilePage — F-0664 per-platform engagementRate honest-empty render', () => {
  beforeEach(() => {
    getMeMock.mockReset();
  });

  it('a connected-but-unsynced platform (engagementRate: null) shows an explicit not-available state, never a bare "% engagement" or a fabricated 0%', async () => {
    getMeMock.mockResolvedValue({
      ...BASE_PROFILE,
      // Deliberately distinct from `BASE_PROFILE.username` ("priya_creates") — the profile
      // header renders "@{username}" too, and an identical handle here would make queries for
      // "@priya_creates" ambiguous between the header and this platform row.
      platforms: [
        {
          platform: 'instagram',
          handle: '@priya_ig',
          followers: 125000,
          engagementRate: null,
          isVerified: true,
          profileUrl: null,
        },
      ],
    });

    renderPage();

    expect(await screen.findByText('@priya_ig')).toBeInTheDocument();
    expect(screen.getByText(/engagement not available yet/i)).toBeInTheDocument();

    // The old dishonest render put `{formatNumber(followers)} followers • {rate}% engagement`
    // in one <p>, so the whole element's textContent — not an isolated text node — is what a
    // bare-"%" regression would corrupt; check the full row text instead of an exact string
    // that would never match this multi-expression paragraph even in the fixed version.
    const row = screen.getByText('@priya_ig').closest('div.space-y-2') as HTMLElement;
    expect(row).toBeTruthy();
    expect(row.textContent).not.toMatch(/•\s*% engagement/);
    expect(row.textContent).not.toMatch(/0% engagement/);
  });

  it('a platform with a real, synced engagementRate still renders the number normally', async () => {
    getMeMock.mockResolvedValue({
      ...BASE_PROFILE,
      platforms: [
        {
          platform: 'instagram',
          handle: '@priya_ig',
          followers: 125000,
          engagementRate: 4.2,
          isVerified: true,
          profileUrl: null,
        },
      ],
    });

    renderPage();

    await screen.findByText('@priya_ig');
    expect(screen.getByText(/4\.2% engagement/)).toBeInTheDocument();
    expect(screen.queryByText(/engagement not available yet/i)).not.toBeInTheDocument();
  });

  it('two platforms with independent sync states each render honestly on their own row', async () => {
    getMeMock.mockResolvedValue({
      ...BASE_PROFILE,
      platforms: [
        {
          platform: 'instagram',
          handle: '@priya_ig',
          followers: 125000,
          engagementRate: 4.2,
          isVerified: true,
          profileUrl: null,
        },
        {
          platform: 'youtube',
          handle: 'Priya Creates TV',
          followers: 40000,
          engagementRate: null,
          isVerified: false,
          profileUrl: null,
        },
      ],
    });

    renderPage();

    await screen.findByText('@priya_ig');
    expect(screen.getByText(/4\.2% engagement/)).toBeInTheDocument();
    expect(screen.getByText(/engagement not available yet/i)).toBeInTheDocument();
  });
});
