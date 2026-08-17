/**
 * F-0259 — no-loading-no-empty-state.
 * ----------------------------------------------------------------------------
 * Discover had no loading state and no empty state: while `GET /creators` was in flight the grid
 * was just blank (identical to "zero results"), and a genuine zero-result search looked
 * identical to a stalled page — nothing told the brand which one they were looking at. Filtering
 * and sorting all ran client-side over the single fetched page, and the price-range filter's
 * UNTOUCHED default ([5000, 200000]) silently dropped every creator with no `averageRate` set,
 * because the filter read `(c.averageRate ?? 0)` — 0 sits below the 5000 floor — even though the
 * brand never touched that slider.
 *
 * Three guarantees:
 *   A. While the search is in flight, a loading state renders — and the empty-state copy does
 *      NOT also render at the same time (they must be distinguishable, not just "both algo
 *      branches happen to produce no cards").
 *   B. A genuine zero-result response renders the empty state, and the loading state is gone.
 *   C. A creator with no `averageRate` set is still visible at the untouched default filters —
 *      the default price range must not act as an invisible filter the brand never asked for.
 *
 * Run: npx vitest run src/components/brand/discover/__tests__/creator-discovery-loading-empty.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      creators: {
        search: (...a: unknown[]) => creatorsSearch(...a),
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

describe('F-0259 — Discover has a real loading state and a real empty state', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    campaignsList.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('shows a loading state while the search is in flight, distinct from the empty state', async () => {
    let resolveSearch: (v: unknown) => void = () => {};
    creatorsSearch.mockReturnValue(
      new Promise((res) => {
        resolveSearch = res;
      }),
    );

    renderDiscovery();

    // Guarantee A — loading renders, empty-state copy does not (they are not the same thing).
    await screen.findByTestId('discover-loading');
    expect(screen.queryByTestId('discover-empty')).not.toBeInTheDocument();
    expect(screen.queryByText(/no creators match/i)).not.toBeInTheDocument();

    resolveSearch({ creators: [], meta: { page: 1, limit: 20, hasMore: false } });
    await waitFor(() => expect(screen.queryByTestId('discover-loading')).not.toBeInTheDocument());
  });

  it('shows a distinct empty state for a genuine zero-result search', async () => {
    creatorsSearch.mockResolvedValue({ creators: [], meta: { page: 1, limit: 20, hasMore: false } });

    renderDiscovery();

    // Guarantee B — empty state renders once loading has finished; loading must not still be up.
    await screen.findByTestId('discover-empty');
    expect(screen.queryByTestId('discover-loading')).not.toBeInTheDocument();
  });

  it('keeps a creator with no averageRate visible at the untouched default filters', async () => {
    const NO_RATE_CREATOR = {
      id: 'cp_no_rate',
      displayName: 'Rakesh Iyer',
      location: 'Pune',
      // averageRate intentionally absent — never priced yet.
      totalFollowers: 80000,
      engagementRate: 3.1,
      verified: true,
      platforms: [],
      categories: [],
      languages: [],
    };
    creatorsSearch.mockResolvedValue({
      creators: [NO_RATE_CREATOR],
      meta: { page: 1, limit: 20, hasMore: false },
    });

    renderDiscovery();

    // Guarantee C — present at defaults, and no empty state fires instead.
    await screen.findByText('Rakesh Iyer');
    expect(screen.queryByTestId('discover-empty')).not.toBeInTheDocument();
  });
});
