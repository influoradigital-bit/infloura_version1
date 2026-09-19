/**
 * F-0965 — ruling 2026-09-19 (option a, separate imported total): a creator's follower total counts
 * only Meta-verified platforms; an external creator's imported (Meta Creator Marketplace / admin)
 * total still shows, so they stay findable, but must be labelled "imported, not verified".
 *
 * Renders the real discovery grid with one IMPORTED and one VERIFIED creator.
 *
 * Run: npx vitest run src/components/brand/discover/__tests__/creator-discovery-followers-source.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorDiscovery, followersCaption } from '../creator-discovery';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const creatorsSearch = vi.fn();
const featuredMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      creators: {
        search: (...a: unknown[]) => creatorsSearch(...a),
        searchWithFacets: (...a: unknown[]) => creatorsSearch(...a),
        invite: vi.fn(),
        toggleSaved: vi.fn().mockResolvedValue({ saved: true }),
        featured: (...a: unknown[]) => featuredMock(...a),
      },
      deals: { create: vi.fn() },
      campaigns: { list: vi.fn().mockResolvedValue([]) },
    },
  };
});

// Every default filter must pass or the card never renders (see creator-discovery.test.tsx).
const base = {
  location: 'Mumbai',
  averageRate: 40000,
  engagementRate: 4.2,
  verified: true,
  platforms: [],
  categories: [],
  languages: [],
};
const IMPORTED = { ...base, id: 'cp_IMPORTED0000001', displayName: 'Imported Ira', totalFollowers: 184000, followersSource: 'IMPORTED' };
const VERIFIED = { ...base, id: 'cp_VERIFIED0000001', displayName: 'Verified Vikas', totalFollowers: 120000, followersSource: 'VERIFIED' };

function cardFor(name: string): HTMLElement {
  let el: HTMLElement | null = screen.getByText(name);
  // Walk up to the card that holds both the name and the follower stat.
  while (el && !within(el).queryByText(/^Followers/)) {
    el = el.parentElement;
  }
  if (!el) throw new Error(`no card for ${name}`);
  return el;
}

describe('creator discovery — follower total provenance (F-0965)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    featuredMock.mockResolvedValue({ featured: [] });
    creatorsSearch.mockResolvedValue({
      creators: [IMPORTED, VERIFIED],
      meta: { page: 1, pageSize: 20, total: 2, totalPages: 1 },
    });
  });

  it('labels an imported total as not verified, and a verified total plainly', async () => {
    render(
      <MemoryRouter initialEntries={['/brand/discover']}>
        <CreatorDiscovery />
      </MemoryRouter>,
    );
    await waitFor(() => expect(creatorsSearch).toHaveBeenCalled());
    await screen.findByText('Imported Ira');

    expect(within(cardFor('Imported Ira')).getByText('Followers · imported, not verified')).toBeInTheDocument();
    expect(within(cardFor('Verified Vikas')).getByText('Followers')).toBeInTheDocument();
    expect(within(cardFor('Verified Vikas')).queryByText(/imported/i)).not.toBeInTheDocument();
  });

  it('the list layout carries the same caveat', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/brand/discover']}>
        <CreatorDiscovery />
      </MemoryRouter>,
    );
    await screen.findByText('Imported Ira');
    await user.click(screen.getByRole('button', { name: 'List view' }));

    await waitFor(() =>
      expect(within(cardFor('Imported Ira')).getByText('Followers · imported, not verified')).toBeInTheDocument(),
    );
    expect(within(cardFor('Verified Vikas')).queryByText(/imported/i)).not.toBeInTheDocument();
  });

  it('the Featured row labels an imported total too', async () => {
    featuredMock.mockResolvedValue({
      featured: [{ category: 'beauty', title: 'Featured beauty', creators: [{ ...IMPORTED, id: 'cp_FEATIMP000001', displayName: 'Featured Ira' }] }],
    });
    render(
      <MemoryRouter initialEntries={['/brand/discover']}>
        <CreatorDiscovery />
      </MemoryRouter>,
    );
    const featured = await screen.findByText('Featured Ira');
    const tile = featured.parentElement as HTMLElement;
    expect(within(tile).getByText(/imported, not verified/i)).toBeInTheDocument();
  });

  it('followersCaption: only IMPORTED gets the caveat', () => {
    expect(followersCaption('IMPORTED')).toBe('Followers · imported, not verified');
    expect(followersCaption('VERIFIED')).toBe('Followers');
    expect(followersCaption('NONE')).toBe('Followers');
    expect(followersCaption(undefined)).toBe('Followers');
  });
});
