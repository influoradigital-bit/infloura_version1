/**
 * creator-portfolio-public — per-platform follower provenance (CR-119).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * This page is publicly linkable and brand-viewable, and it renders follower counts for
 * platforms whose numbers have wildly different trustworthiness: Instagram can be confirmed
 * against the Meta Graph API, while YouTube/TikTok/X have no OAuth or data-fetch integration
 * anywhere in this codebase and are therefore always creator-reported.
 *
 * Both surfaces used to signal that distinction with badge-presence alone (`{verified && <icon>}`),
 * which is far too quiet — a missing mark reads as an oversight, not as a claim — and the backend
 * flag driving it was hardcoded false anyway, so the badge never appeared at all.
 *
 * There are TWO independent per-platform surfaces on this page and they must agree:
 *   - `PlatformPill`      — the hero pills, rendered unconditionally
 *   - `PlatformStatCard`  — the stats grid, rendered only when `visibility.platformStats` is on
 * A creator who hides platform stats leaves the pills as the ONLY per-platform surface, which is
 * exactly why the pills cannot be left badge-only. Both are covered here.
 *
 * Run: npx vitest run src/pages/creator-portfolio-public-provenance.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import CreatorPortfolioPublicPage from './creator-portfolio-public';

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));

class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
// @ts-expect-error — jsdom has no IntersectionObserver global.
global.IntersectionObserver = MockIntersectionObserver;

const getPublic = vi.fn();
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { ...actual.api, portfolio: { ...actual.api.portfolio, getPublic: (...a: unknown[]) => getPublic(...a) } },
  };
});

function platformStats(platform: string, verified: boolean) {
  return {
    platform,
    handle: `@demo_${platform.toLowerCase()}`,
    url: `https://example.com/${platform.toLowerCase()}`,
    verified,
    followers: 120000,
    engagementRate: 4.2,
    lastSyncedAt: '2026-08-01T00:00:00Z',
  };
}

/** A portfolio page with Instagram platform-verified and YouTube creator-reported. */
function portfolioPage(opts: { platformStatsVisible: boolean }) {
  return {
    username: 'demo',
    displayName: 'Demo Creator',
    bio: 'bio',
    niches: [],
    verified: true,
    stats: { totalCollabs: 0, avgRating: 0, onTimeRate: 0, repeatBrands: 0 },
    badges: [],
    platforms: [platformStats('INSTAGRAM', true), platformStats('YOUTUBE', false)],
    collabs: [],
    pinnedPosts: [],
    customLinks: [],
    rateCard: [],
    languages: [],
    topAudienceCities: [],
    visibility: {
      trustBar: false,
      badges: false,
      platformStats: opts.platformStatsVisible,
      pastCollabs: false,
      contentPortfolio: false,
      customLinks: false,
      rateCard: 'hidden',
      languages: false,
    },
  };
}

async function renderPage(page: unknown) {
  getPublic.mockResolvedValue(page);
  render(
    <MemoryRouter initialEntries={['/@demo']}>
      <Routes>
        <Route path="/:handle" element={<CreatorPortfolioPublicPage />} />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.getByText('Demo Creator')).toBeInTheDocument());
}

describe('creator-portfolio-public — follower provenance (CR-119)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('states provenance on the hero pills even when the platform-stats grid is HIDDEN', async () => {
    // The regression this pins: with visibility.platformStats off, the pills are the only
    // per-platform surface. Leaving them badge-only made a self-reported YouTube figure
    // indistinguishable from a Meta-verified Instagram one, with nothing else on the page to
    // disambiguate.
    await renderPage(portfolioPage({ platformStatsVisible: false }));

    expect(screen.getByText('Followers verified')).toBeInTheDocument();
    expect(screen.getByText('Self-reported')).toBeInTheDocument();
  });

  it('states provenance on BOTH surfaces when the platform-stats grid is visible', async () => {
    await renderPage(portfolioPage({ platformStatsVisible: true }));

    // One from the pill, one from the stat card — for each of the two platforms.
    await waitFor(() => expect(screen.getAllByText('Followers verified')).toHaveLength(2));
    expect(screen.getAllByText('Self-reported')).toHaveLength(2);
  });

  it('a platform with no real integration is never presented as verified', async () => {
    // Every platform creator-reported — the true state for a creator with no Meta connection.
    const page = portfolioPage({ platformStatsVisible: true });
    page.platforms = [platformStats('INSTAGRAM', false), platformStats('YOUTUBE', false)];
    await renderPage(page);

    expect(screen.queryByText('Followers verified')).toBeNull();
    expect(screen.getAllByText('Self-reported').length).toBeGreaterThan(0);
  });
});

/**
 * The suite above stubs `api.portfolio.getPublic`, which means it cannot see anything wrong with
 * the REAL mock fixture the page actually renders in every non-live build. That blind spot is not
 * hypothetical: `mockPortfolio()` in src/lib/api.ts shipped `verified: true` on its YOUTUBE row,
 * so the page printed "Followers verified" next to a YouTube follower count — on a publicly
 * linkable, brand-viewable page — and all three tests above stayed green.
 *
 * This block therefore mocks NOTHING from `@/lib/api`. It exercises the genuine mock-mode path,
 * fixture included.
 */
describe('creator-portfolio-public — real mock-mode fixture provenance (CR-119)', () => {
  it('the shipped mock fixture never claims a YouTube follower count is platform-verified', async () => {
    vi.resetModules();
    vi.doUnmock('@/lib/api');
    const { default: FreshPage } = await import('./creator-portfolio-public');

    render(
      <MemoryRouter initialEntries={['/@priyacreates']}>
        <Routes>
          <Route path="/:handle" element={<FreshPage />} />
        </Routes>
      </MemoryRouter>,
    );

    // Wait on the heading specifically — "Priya Creates" is BOTH the fixture's display name and
    // its YouTube handle, so a bare getByText matches two nodes.
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Priya Creates' })).toBeInTheDocument(),
    );

    // Instagram legitimately carries the claim (real Meta integration exists); YouTube must not.
    // Asserting on counts rather than presence: the fixture has exactly one of each platform, and
    // each renders on two surfaces (hero pill + stat card).
    const verified = screen.queryAllByText('Followers verified');
    const selfReported = screen.queryAllByText('Self-reported');
    expect(
      selfReported.length,
      'the YouTube row must read as self-reported on both surfaces',
    ).toBeGreaterThan(0);
    expect(
      verified.length,
      'no more surfaces may claim verified than Instagram legitimately occupies',
    ).toBeLessThanOrEqual(selfReported.length);
  });
});
