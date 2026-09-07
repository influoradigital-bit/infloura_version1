/**
 * creator-portfolio-editor — F-0694/F-0695, the self-declared platform row
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * A brand narrowing Discover to `platforms=INSTAGRAM` runs
 * `CreatorProfileSpecifications#hasPlatforms`, an EXISTS subquery over `platform_stats`. That
 * table's only two writers (`PlatformStatsAggregationJob` and `PortfolioService#syncPlatforms`)
 * both build their row from a Meta `CreatorMetric`, and `creator-onboarding.tsx`'s only Instagram
 * branch is a full-page Meta OAuth redirect. So a creator who declined, failed, or could not
 * complete OAuth had NO platform_stats row at all and was invisible to the single most obvious
 * search a brand performs. The backend half is proved by
 * `influora-api/.../portfolio/PortfolioServiceDeclarePlatformTest.java`.
 *
 * WHY A CLICK TEST AND NOT A TYPECHECK (F-0341)
 * ----------------------------------------------
 * `tsc --noEmit` and eslint pass just as happily on a form whose Add button is wired to nothing —
 * a div styled as a control is indistinguishable from a live one to every static check. The only
 * thing that separates "the creator can now declare a platform" from "there is a form on the page"
 * is actually clicking it and watching the request leave. That is what this file does.
 *
 * WHAT IT PINS, AND HOW EACH ASSERTION FALSIFIES:
 *   - The Add button is enabled and reaches `api.portfolio.declarePlatform` with the typed handle
 *     and follower count. Unwiring `onClick`, or dropping a field from the call, turns this RED.
 *   - The follower count is sent as a NUMBER with separators stripped — "48,000" must not arrive
 *     as a string or as NaN, either of which the backend rejects with INVALID_FOLLOWERS.
 *   - After a successful declaration the page re-reads itself, so the new row renders instead of
 *     stale client state (the F-0434 "saves but reverts on reload" failure mode).
 *   - A PLATFORM_ALREADY_VERIFIED conflict renders its own honest explanation rather than a
 *     generic retry message — a Meta-synced row is not something the creator can type over, and
 *     telling them to "try again" would be a lie.
 *
 * Run: node_modules/.bin/vitest run src/pages/creator-portfolio-editor.f0694-declare-platform.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorPortfolioEditorPage from './creator-portfolio-editor';
import { ApiError } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({ toast: (...a: unknown[]) => toastMock(...a) }));

const getMineMock = vi.fn();
const analyticsMock = vi.fn();
const declarePlatformMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      portfolio: {
        ...actual.api.portfolio,
        getMine: (...a: unknown[]) => getMineMock(...a),
        analytics: (...a: unknown[]) => analyticsMock(...a),
        declarePlatform: (...a: unknown[]) => declarePlatformMock(...a),
      },
    },
  };
});

/** `platforms` is parameterized so one fixture builds both the empty "before" state and the
 *  post-declaration "after" state the reload is supposed to render. */
function portfolioPage(platforms: Array<Record<string, unknown>> = []) {
  return {
    username: 'demo_creator',
    displayName: 'Demo Creator',
    bio: 'Fashion & lifestyle creator.',
    niches: ['Fashion'],
    verified: false,
    stats: { totalCollabs: 0, avgRating: 0, onTimeRate: 0, repeatBrands: 0 },
    badges: [],
    platforms,
    collabs: [],
    pinnedPosts: [],
    customLinks: [],
    rateCard: [],
    languages: [],
    topAudienceCities: [],
    visibility: {
      trustBar: true,
      badges: true,
      platformStats: true,
      pastCollabs: true,
      contentPortfolio: true,
      customLinks: true,
      rateCard: 'public' as const,
      languages: true,
      contactForm: true,
    },
  };
}

function mockAnalytics() {
  return {
    pageViews: { last30Days: 0, deltaPercent: 0 },
    profileClicks: 0,
    profileClicksEstimated: true,
    linkClicks: [],
    brandInquiries: 0,
    mediaKitDownloads: 0,
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/portfolio']}>
      <CreatorPortfolioEditorPage />
    </MemoryRouter>,
  );
}

async function fillTheForm(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => {
    expect(screen.getByText('Add a platform manually')).toBeInTheDocument();
  });
  await user.type(screen.getByLabelText('Username on that platform'), '@riya.creates');
  await user.type(screen.getByLabelText('Follower count'), '48,000');
}

describe('CreatorPortfolioEditorPage — F-0694 a creator with no Meta connection can declare a platform', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getMineMock.mockResolvedValue(portfolioPage());
    analyticsMock.mockResolvedValue(mockAnalytics());
    declarePlatformMock.mockResolvedValue({ syncedAt: '2026-09-07T00:00:00Z' });
  });

  it('the Add control is live — clicking it actually reaches api.portfolio.declarePlatform', async () => {
    const user = userEvent.setup();
    renderPage();
    await fillTheForm(user);

    const addButton = screen.getByTestId('declare-platform-add');
    expect(addButton).toBeEnabled();
    await user.click(addButton);

    await waitFor(() => {
      expect(declarePlatformMock).toHaveBeenCalledTimes(1);
    });
    expect(declarePlatformMock).toHaveBeenCalledWith({
      platform: 'INSTAGRAM',
      handle: '@riya.creates',
      followers: 48000,
    });
  });

  it('sends the follower count as a number with separators stripped, never a string or NaN', async () => {
    const user = userEvent.setup();
    renderPage();
    await fillTheForm(user);
    await user.click(screen.getByTestId('declare-platform-add'));

    await waitFor(() => expect(declarePlatformMock).toHaveBeenCalled());
    const sent = declarePlatformMock.mock.calls[0][0] as { followers: unknown };
    expect(typeof sent.followers).toBe('number');
    expect(Number.isNaN(sent.followers as number)).toBe(false);
    expect(sent.followers).toBe(48000);
  });

  it('re-reads the page after declaring, so the new row renders instead of stale client state', async () => {
    const user = userEvent.setup();
    getMineMock
      .mockResolvedValueOnce(portfolioPage())
      .mockResolvedValueOnce(
        portfolioPage([
          { platform: 'INSTAGRAM', handle: 'riya.creates', followers: 48000, isVerified: false },
        ]),
      );
    renderPage();
    await fillTheForm(user);
    await user.click(screen.getByTestId('declare-platform-add'));

    await waitFor(() => {
      expect(screen.getByText('riya.creates')).toBeInTheDocument();
    });
    // Unverified: the badge the Meta sync earns must NOT appear for a typed row.
    expect(screen.queryByText('Verified')).not.toBeInTheDocument();
  });

  it('refuses honestly when a Meta sync already owns that platform, instead of saying "try again"', async () => {
    const user = userEvent.setup();
    declarePlatformMock.mockRejectedValue(
      new ApiError('PLATFORM_ALREADY_VERIFIED', 'already connected', 409),
    );
    renderPage();
    await fillTheForm(user);
    await user.click(screen.getByTestId('declare-platform-add'));

    await waitFor(() => {
      expect(toastMock).toHaveBeenCalled();
    });
    const description = toastMock.mock.calls
      .map((c) => (c[0] as { description?: string })?.description ?? '')
      .join(' ');
    expect(description).toMatch(/already connected/i);
    expect(description).not.toMatch(/try again/i);
  });

  it('does not call the API at all when the handle is blank', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('Add a platform manually')).toBeInTheDocument();
    });
    await user.type(screen.getByLabelText('Follower count'), '48000');
    await user.click(screen.getByTestId('declare-platform-add'));

    await waitFor(() => expect(toastMock).toHaveBeenCalled());
    expect(declarePlatformMock).not.toHaveBeenCalled();
  });
});
