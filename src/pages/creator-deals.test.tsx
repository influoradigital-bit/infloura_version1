/**
 * Creator Deals page — smoke + P0 regression (2026-07-23).
 *
 * Two things changed from the original Kv3b smoke test:
 *  1. The tree now needs a QueryClientProvider — `DailySuggestionSection` →
 *     `useDailySuggestion()` calls `useQueryClient()`, added after the original
 *     test was written, so an un-wrapped render throws "No QueryClient set".
 *     (In the app that provider comes from App.tsx.)
 *  2. The old "shows mock deal brand (Nykaa Fashion)" assertion tested behavior
 *     b6b0677 intentionally removed (no fabricated deals in the live inbox). It is
 *     replaced by the real regression guard: feed a LIVE-shaped `Deal`
 *     (counterpartyName/campaignName/TERMS_AGREED) through the actual component and
 *     assert the mapped brand renders instead of crashing on `deal.brandName.split`.
 *
 * Run: npx vitest run src/pages/creator-deals.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CreatorDealsPage from './creator-deals';
import { api, type Deal } from '@/lib/api';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/components/creator/hype-inbox-card', () => ({
  HypeInboxCard: () => <div data-testid="hype-inbox-card" />,
}));

// Exact live API shape from GET /deals (http://200.141.1.6/, demo.creator) — the
// payload that crashed the page before the mapper was wired in.
const liveDeal: Deal = {
  id: '01KY52585HY09G9CJWP930SJX8',
  campaignId: '01KY523ES7ZW5T2KCX1B8Q0450',
  campaignName: 'QA E2E — Diwali Skincare Reels',
  counterpartyId: '01KY4Y1PR2A2CHE0933YPZ3R7R',
  counterpartyName: 'Demo Brand Co',
  status: 'TERMS_AGREED',
  dealValue: 0,
  currency: 'INR',
  lastMessage: 'Brand accepted the proposal',
  lastMessageAt: '2026-07-22T14:03:09Z',
  unreadCount: 1,
  deliverablesDone: 0,
  deliverablesTotal: 0,
  escrowFunded: false,
};

function renderPage(initialEntry = '/creator/deals') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <CreatorDealsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('CreatorDealsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(api.deals, 'list').mockResolvedValue([]);
  });

  it('renders deals header and status filter chips', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Deals' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^All/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^New/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Active/i })).toBeInTheDocument();
  });

  it('renders a live-shaped deal via the mapper without crashing (P0 regression)', async () => {
    vi.spyOn(api.deals, 'list').mockResolvedValue([liveDeal]);
    renderPage();

    // brandName ← counterpartyName, campaignTitle ← campaignName. Before the fix,
    // deal.brandName was undefined and `.split(' ')` threw, blanking the whole page.
    await waitFor(() => {
      expect(screen.getAllByText('Demo Brand Co').length).toBeGreaterThanOrEqual(1);
    });
    expect(
      screen.getAllByText(/QA E2E — Diwali Skincare Reels/i).length,
    ).toBeGreaterThanOrEqual(1);
  });

  it('F-0168-followup: seeds the active filter from ?status= on mount — App.tsx\'s /creator/inbox redirect and CR-59\'s "New tab in Deals" cross-link both promise this and neither worked before', async () => {
    renderPage('/creator/deals?status=new');

    // The page's own effect re-fetches scoped to whatever activeFilter resolved to; asserting
    // on that call (not just the visual "active" chip class) proves the seeded value actually
    // drives data fetching, not just a cosmetic highlight. (A separate unfiltered 'all' fetch
    // for the badge counts, per the CR-12 comment above, is expected and not asserted against.)
    await waitFor(() => {
      expect(api.deals.list).toHaveBeenCalledWith('creator', 'new');
    });
  });

  it('F-0168-followup: an unrecognized ?status= value falls back to "all", same as no param at all', async () => {
    renderPage('/creator/deals?status=not-a-real-chip');

    // Priya's fresh-context review caught the first version of this test as vacuous: the
    // separate unfiltered badge-counts effect (CR-12, always api.deals.list('creator', 'all'))
    // satisfies a bare toHaveBeenCalledWith('creator', 'all') on every mount regardless of
    // filter — it would still pass if validation were deleted and the bad token forwarded
    // straight to the API. Assert on the FULL call set instead: the invalid token must never
    // reach api.deals.list at all, and every call this mount makes must be the 'all' shape.
    await waitFor(() => expect(api.deals.list).toHaveBeenCalledTimes(2));
    expect(api.deals.list).not.toHaveBeenCalledWith('creator', 'not-a-real-chip');
    expect(
      vi.mocked(api.deals.list).mock.calls.every(([role, status]) => role === 'creator' && status === 'all'),
    ).toBe(true);
  });

  it('F-0275: the "all" empty state gives an honest, working CTA instead of implying a purely passive product', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage('/creator/deals?status=all');

    // Was "Brands will reach out as they discover your profile" with no way for the
    // creator to act — this asserts the actual dead-end wording is gone.
    await waitFor(() => {
      expect(screen.getByText('No deals yet')).toBeInTheDocument();
    });
    expect(
      screen.queryByText(/^Brands will reach out as they discover your profile\.\s*Make sure/i),
    ).not.toBeInTheDocument();

    const cta = screen.getByRole('button', { name: /Browse campaigns/i });
    await user.click(cta);
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/campaigns'));
  });
});
