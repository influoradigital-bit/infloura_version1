/**
 * F-0660 (finding-premise-contradicted-by-backend) — real category facets wired from
 * `GET /creators/search`, the endpoint F-0411 asked to wire city/language facets from.
 *
 * F-0411 as worded is NOT achievable: `CreatorController.searchWithFacets`
 * (`GET /creators/search`, influora-api CreatorController.java:81) returns
 * `DiscoveryDtos.DiscoverySearchResponse(creators, filters)`, whose `filters.available`
 * (`AvailableFiltersMeta`, DiscoveryDtos.java:20) carries exactly two facet kinds —
 * `categories` and `followerRanges` — verified server-side. There is no city or language facet
 * anywhere in the backend.
 *
 * What WAS achievable, and was not done: `creator-discovery.tsx` called plain `GET /creators`
 * (`api.creators.search`), which returns no facets whatsoever, leaving the real facets endpoint
 * with zero FE consumers. This proves the actual fix — `api.creators.searchWithFacets` (the real
 * `/creators/search` endpoint) is now called instead, and its category facet (real ids + counts)
 * drives the Categories filter panel. Cities and languages stay exactly as hardcoded as before —
 * this file does not, and should not, find a city/language facet that does not exist.
 *
 * Run: npx vitest run src/components/brand/discover/__tests__/creator-discovery-facets.test.tsx
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

// Deliberately SEPARATE spies (unlike the other creator-discovery test files, which alias both
// to one mock) — the whole point of this suite is proving `search` is never the call the
// component makes for its main results anymore.
const creatorsSearch = vi.fn();
const creatorsSearchWithFacets = vi.fn();
const campaignsList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      creators: {
        search: (...a: unknown[]) => creatorsSearch(...a),
        searchWithFacets: (...a: unknown[]) => creatorsSearchWithFacets(...a),
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

async function openFilters(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /Filters/i }));
}

describe('CreatorDiscovery — F-0660 real category facets', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    campaignsList.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });
  });

  it('calls the real facets endpoint for its main results, never the plain (facet-less) one', async () => {
    creatorsSearchWithFacets.mockResolvedValue({
      creators: [],
      meta: { page: 1, limit: 20, hasMore: false, total: 0 },
      facets: { categories: [], followerRanges: [] },
    });
    renderDiscovery();

    await waitFor(() => expect(creatorsSearchWithFacets).toHaveBeenCalledTimes(1));
    expect(creatorsSearch).not.toHaveBeenCalled();
  });

  it('renders the real category facet counts, replacing the hardcoded list entirely once resolved', async () => {
    const user = userEvent.setup({ delay: null });
    creatorsSearchWithFacets.mockResolvedValue({
      creators: [],
      meta: { page: 1, limit: 20, hasMore: false, total: 0 },
      facets: {
        categories: [
          { id: 'fashion', count: 42 },
          { id: 'gaming', count: 7 },
        ],
        followerRanges: [],
      },
    });
    renderDiscovery();
    await waitFor(() => expect(creatorsSearchWithFacets).toHaveBeenCalledTimes(1));

    await openFilters(user);

    // Real ids, title-cased for display, with their real counts.
    expect(await screen.findByText('Fashion')).toBeInTheDocument();
    expect(screen.getByText('(42)')).toBeInTheDocument();
    expect(screen.getByText('Gaming')).toBeInTheDocument();
    expect(screen.getByText('(7)')).toBeInTheDocument();

    // The hardcoded fallback list is NOT layered in alongside real data — "Beauty" is on the
    // hardcoded list but was not in this facet response, so it must not appear.
    expect(screen.queryByText('Beauty')).not.toBeInTheDocument();
  });

  it('falls back to the hardcoded category list (no fabricated counts) when the facet comes back empty', async () => {
    const user = userEvent.setup({ delay: null });
    creatorsSearchWithFacets.mockResolvedValue({
      creators: [],
      meta: { page: 1, limit: 20, hasMore: false, total: 0 },
      facets: { categories: [], followerRanges: [] },
    });
    renderDiscovery();
    await waitFor(() => expect(creatorsSearchWithFacets).toHaveBeenCalledTimes(1));

    await openFilters(user);

    expect(await screen.findByText('Fashion')).toBeInTheDocument();
    // No real count exists yet — must not show a fabricated "(0)" or any other number.
    expect(screen.queryByText('(0)')).not.toBeInTheDocument();
  });

  it('selecting a real facet category sends its exact lowercase id back as `verticals`', async () => {
    const user = userEvent.setup({ delay: null });
    creatorsSearchWithFacets.mockResolvedValue({
      creators: [],
      meta: { page: 1, limit: 20, hasMore: false, total: 0 },
      facets: { categories: [{ id: 'gaming', count: 7 }], followerRanges: [] },
    });
    renderDiscovery();
    await waitFor(() => expect(creatorsSearchWithFacets).toHaveBeenCalledTimes(1));

    await openFilters(user);
    await user.click(await screen.findByText('Gaming'));

    await waitFor(() => expect(creatorsSearchWithFacets).toHaveBeenCalledTimes(2));
    expect(creatorsSearchWithFacets.mock.calls[1][0]).toMatchObject({ verticals: ['gaming'] });
  });

  it('never claims a city or language facet that does not exist on the backend', async () => {
    const user = userEvent.setup({ delay: null });
    creatorsSearchWithFacets.mockResolvedValue({
      creators: [],
      meta: { page: 1, limit: 20, hasMore: false, total: 0 },
      facets: { categories: [{ id: 'fashion', count: 42 }], followerRanges: [] },
    });
    renderDiscovery();
    await waitFor(() => expect(creatorsSearchWithFacets).toHaveBeenCalledTimes(1));

    await openFilters(user);

    // City/Language sections still render from the hardcoded reference lists — Mumbai and Hindi
    // are on those lists regardless of what the (nonexistent) facet would say.
    expect(await screen.findByText('Mumbai')).toBeInTheDocument();
    expect(screen.getByText('Hindi')).toBeInTheDocument();
  });
});
