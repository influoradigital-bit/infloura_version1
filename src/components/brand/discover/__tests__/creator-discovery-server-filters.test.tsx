/**
 * F-0405 / F-0409 / F-0410 — discovery filters, price-filter contract drift, and result count.
 * ----------------------------------------------------------------------------
 * Three previously-disconnected pieces of `CreatorDiscovery`:
 *
 *   F-0405 (dead-control, live mode) — language, engagement rate, verified-only and sort order
 *   only ever re-filtered the 20 rows already on screen; they never reached `GET /creators`. A
 *   brand narrowing by language was narrowing the current page, not the creator base.
 *
 *   F-0409 (contract-drift, mock mode) — the server's `rateOverlap` spec deliberately ORs an
 *   isNull check so an unpriced creator still matches a price filter, but the client's price
 *   predicate required `averageRate != null` and silently dropped exactly those rows once the
 *   price slider was touched.
 *
 *   F-0410 (empty-state-misleads, live mode) — "Showing N creators" read `filteredCreators.length`
 *   (the current page, capped at 20), not the server's total match count for the filter set.
 *
 * Run: npx vitest run src/components/brand/discover/__tests__/creator-discovery-server-filters.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorDiscovery } from '../creator-discovery';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const creatorsSearch = vi.fn();
const campaignsList = vi.fn();
let liveMode = true;

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => liveMode,
    api: {
      creators: {
        search: (...a: unknown[]) => creatorsSearch(...a),
        // F-0660 — see creator-discovery.test.tsx's identical comment.
        searchWithFacets: (...a: unknown[]) => creatorsSearch(...a),
        invite: vi.fn(),
        toggleSaved: vi.fn().mockResolvedValue({ saved: true }),
        featured: vi.fn().mockResolvedValue({ featured: [] }),
      },
      deals: { create: vi.fn() },
      campaigns: { list: (...a: unknown[]) => campaignsList(...a) },
    },
  };
});

function renderDiscovery() {
  return render(
    <MemoryRouter initialEntries={['/brand/discover']}>
      <CreatorDiscovery />
    </MemoryRouter>,
  );
}

/** Presses ArrowRight `n` times on the currently-focused slider thumb (Radix, step-driven). */
async function pressArrowRight(user: ReturnType<typeof userEvent.setup>, n: number) {
  await user.keyboard('{ArrowRight}'.repeat(n));
}

describe('F-0405 — discovery filters reach the server search request (live mode)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    liveMode = true;
    campaignsList.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
    creatorsSearch.mockResolvedValue({
      creators: [],
      meta: { page: 1, limit: 20, hasMore: false, total: 0 },
    });
  });

  it('sends language, engagement range, verified-only and sort order as query params', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();

    // Initial debounced load on mount.
    await waitFor(() => expect(creatorsSearch).toHaveBeenCalledTimes(1));
    expect(creatorsSearch.mock.calls[0][0]).toMatchObject({
      languages: undefined,
      isVerified: undefined,
      minEngagementRate: undefined,
      maxEngagementRate: undefined,
      sortBy: 'followers',
    });

    await user.click(screen.getByRole('button', { name: /Filters/i }));

    // Language — one of eight facet chips in the Sheet (a clickable Badge, not a <button>).
    await user.click(await screen.findByText('Tamil'));
    // Verified-only checkbox.
    await user.click(screen.getByRole('checkbox', { name: /Verified creators only/i }));
    // Engagement Rate range slider is the third of three sliders in the sheet (price, followers,
    // engagement), min thumb first — move its lower bound up from the untouched default of 0.
    const sliders = screen.getAllByRole('slider');
    expect(sliders).toHaveLength(6);
    const engagementMinThumb = sliders[4];
    engagementMinThumb.focus();
    await pressArrowRight(user, 4); // step=0.5 -> 0 to 2

    // Close the sheet — Radix marks everything outside an open Sheet aria-hidden, so the Sort
    // control (which lives outside it) is unreachable until it closes.
    await user.click(screen.getByRole('button', { name: /Apply Filters/i }));

    // Sort order — outside the sheet.
    await user.click(screen.getByRole('button', { name: /Sort/i }));
    await user.click(await screen.findByRole('menuitem', { name: /Highest Engagement/i }));

    await waitFor(() => {
      const last = creatorsSearch.mock.calls.at(-1)?.[0];
      expect(last).toMatchObject({
        languages: ['Tamil'],
        isVerified: true,
        minEngagementRate: 2,
        sortBy: 'engagement',
      });
    });
  });
});

describe('F-0410 — the displayed count is the server total, not the page length (live mode)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    liveMode = true;
    campaignsList.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('shows the server total alongside a short page, not the page length alone', async () => {
    creatorsSearch.mockResolvedValue({
      creators: [
        { id: 'c1', displayName: 'One', totalFollowers: 10000, engagementRate: 2, verified: true, platforms: [], categories: [], languages: [] },
        { id: 'c2', displayName: 'Two', totalFollowers: 20000, engagementRate: 2, verified: true, platforms: [], categories: [], languages: [] },
        { id: 'c3', displayName: 'Three', totalFollowers: 30000, engagementRate: 2, verified: true, platforms: [], categories: [], languages: [] },
      ],
      meta: { page: 1, limit: 20, hasMore: true, total: 4820 },
    });

    renderDiscovery();

    await screen.findByText('One');
    expect(screen.getByText('Showing 3 of 4820 creators')).toBeInTheDocument();
    // The bug this pins down: the count must not silently read as the page length alone.
    expect(screen.queryByText('Showing 3 creators')).not.toBeInTheDocument();
  });
});

describe('F-0409 — an unpriced creator survives a touched price filter (mock mode)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    liveMode = false;
  });

  it('keeps the unpriced creator once the price slider is moved above every priced row', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();

    // Untouched defaults: full mock roster visible, including the unpriced creator.
    await screen.findByText('Rahul Verma');
    expect(screen.getByText('Priya Sharma')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Filters/i }));

    // Price Range is the first slider pair in the sheet; move its min thumb from ₹5K to ₹1.05L
    // (step ₹5K x 20) — above every priced mock creator's averageRate (max ₹80K).
    const sliders = screen.getAllByRole('slider');
    expect(sliders).toHaveLength(6);
    const priceMinThumb = sliders[0];
    priceMinThumb.focus();
    await pressArrowRight(user, 20);

    await waitFor(() => {
      // Every priced mock creator falls below the new floor and drops out...
      expect(screen.queryByText('Priya Sharma')).not.toBeInTheDocument();
      expect(screen.queryByText('Rohan Mehta')).not.toBeInTheDocument(); // highest-priced at 80000
      // ...but the unpriced creator is not "priced below the floor" — it has no price at all,
      // and the server's rateOverlap spec (OR isNull) keeps it. The client filter must match.
      expect(screen.getByText('Rahul Verma')).toBeInTheDocument();
    });
    expect(screen.getByText('Showing 1 creators')).toBeInTheDocument();
  });
});
