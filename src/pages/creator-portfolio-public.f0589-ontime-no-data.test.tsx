/**
 * creator-portfolio-public — the On-Time Delivery trust stat when there is no data (F-0589).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `PortfolioStats.onTimeRate` used to be a non-null `int` that was inflated by default: a
 * deliverable with no deadline, a deliverable that was never submitted, and a collaboration with
 * zero deliverable rows all counted as ON TIME over a denominator of every completed
 * collaboration, so a creator we held no timeliness evidence for published a flat "100%" on a
 * public, brand-visible page. The backend now sends `null` for "we cannot measure this".
 *
 * That makes this page the risk surface. It interpolated the value straight into a template
 * literal (`${page.stats.onTimeRate}%`), which renders the string "null%" the moment the field
 * can be null — the exact failure mode of shipping a nullable field into a `number`-typed
 * consumer. Both directions are pinned here: "no data" must read as an honest "—", and a real
 * measured rate must still render as a percentage.
 *
 * Run: npx vitest run src/pages/creator-portfolio-public.f0589-ontime-no-data.test.tsx
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

/**
 * `totalCollabs: 2` on purpose — the trust bar only renders at `>= 1` collab, so this is a creator
 * with REAL completed collaborations whose timeliness we simply cannot measure. That is the case
 * that used to publish 100%, not a blank new profile.
 */
function portfolioPage(stats: { onTimeRate: number | null; onTimeSampleSize: number }) {
  return {
    username: 'demo',
    displayName: 'Demo Creator',
    bio: 'bio',
    niches: [],
    verified: true,
    stats: { totalCollabs: 2, avgRating: 4.5, repeatBrands: 0, ...stats },
    badges: [],
    platforms: [],
    collabs: [],
    pinnedPosts: [],
    customLinks: [],
    rateCard: [],
    languages: [],
    topAudienceCities: [],
    visibility: {
      trustBar: true,
      badges: false,
      platformStats: false,
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

/** The trust-bar tile, located by its label so the assertion reads the value next to it. */
function onTimeTileText() {
  const label = screen.getByText('On-Time Delivery');
  return label.closest('div')?.textContent ?? '';
}

describe('creator-portfolio-public — On-Time Delivery with no measurable data (F-0589)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders an honest "—" when the backend reports no measurable delivery data', async () => {
    await renderPage(portfolioPage({ onTimeRate: null, onTimeSampleSize: 0 }));

    expect(onTimeTileText()).toContain('—');
  });

  it('never prints the literal string "null%" for a no-data rate', async () => {
    await renderPage(portfolioPage({ onTimeRate: null, onTimeSampleSize: 0 }));

    // The specific breakage of interpolating a nullable field into a template literal. Asserted
    // across the whole document, not just the tile, so it also catches the value leaking into a
    // tooltip, aria-label or any other copy on the page.
    expect(document.body.textContent).not.toContain('null%');
    expect(document.body.textContent).not.toContain('null');
  });

  it('does not show 100% for a creator whose timeliness was never measurable', async () => {
    await renderPage(portfolioPage({ onTimeRate: null, onTimeSampleSize: 0 }));

    // The public claim this whole ticket exists to stop: a brand reading a perfect delivery
    // record off a creator with no delivery evidence at all.
    expect(onTimeTileText()).not.toContain('100%');
  });

  it('still renders a real measured rate as a percentage', async () => {
    await renderPage(portfolioPage({ onTimeRate: 92, onTimeSampleSize: 25 }));

    // The guard against over-correcting: making "unknown" honest must not blank out the number
    // for the creators who actually earned it.
    expect(onTimeTileText()).toContain('92%');
    expect(onTimeTileText()).not.toContain('—');
  });
});
