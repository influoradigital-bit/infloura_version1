/**
 * MEERA-CHAT-DESIGN-SPEC.md Part B — unit tests for the "Meera is on it" desk tiles in
 * isolation (independent load, fail-soft, real routes). End-to-end behaviour inside the chat
 * panel itself — desk visibility tied to "no creator message yet", prefill-not-send — is covered
 * by `../MeeraCopilotChat.test.tsx`.
 *
 * Round 2 QA (F-0631 class): tile 1 ("N items need your attention") now goes through the real
 * `@/lib/creator-needs-attention` module (not mocked here) so these tests also exercise
 * `api.creatorDeliverables.listForDeals` — the third source the dashboard already counted that
 * round 1 of this tile omitted.
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraDesk } from './MeeraDesk';

const {
  dealsListMock,
  contractsUnsignedMock,
  walletGetMock,
  portfolioAnalyticsMock,
  creatorDeliverablesListForDealsMock,
} = vi.hoisted(() => ({
  dealsListMock: vi.fn(),
  contractsUnsignedMock: vi.fn(),
  walletGetMock: vi.fn(),
  portfolioAnalyticsMock: vi.fn(),
  creatorDeliverablesListForDealsMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  api: {
    deals: { list: (...args: unknown[]) => dealsListMock(...args) },
    contracts: { listUnsigned: (...args: unknown[]) => contractsUnsignedMock(...args) },
    wallet: { get: (...args: unknown[]) => walletGetMock(...args) },
    portfolio: { analytics: (...args: unknown[]) => portfolioAnalyticsMock(...args) },
    creatorDeliverables: { listForDeals: (...args: unknown[]) => creatorDeliverablesListForDealsMock(...args) },
  },
}));

function renderDesk(onPrefill = vi.fn()) {
  return {
    onPrefill,
    ...render(
      <MemoryRouter>
        <MeeraDesk language="en-IN" onPrefill={onPrefill} />
      </MemoryRouter>,
    ),
  };
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('MeeraDesk — attention tile (F-0631: same count as the dashboard)', () => {
  it('sums unread + submittable deliverables (active deals only) + unsigned contracts', async () => {
    dealsListMock.mockResolvedValue([
      {
        id: 'd1',
        campaignId: 'c1',
        campaignName: 'Campaign A',
        counterpartyId: 'b1',
        counterpartyName: 'Brand A',
        status: 'INVITED', // not active — its deliverables are never fetched
        dealValue: 10000,
        currency: 'INR',
        unreadCount: 2,
        deliverablesDone: 0,
        deliverablesTotal: 1,
        escrowFunded: false,
      },
      {
        id: 'd2',
        campaignId: 'c2',
        campaignName: 'Campaign B',
        counterpartyId: 'b2',
        counterpartyName: 'Brand B',
        status: 'IN_PROGRESS', // active — counts toward submittableDeliverables
        dealValue: 5000,
        currency: 'INR',
        unreadCount: 1,
        deliverablesDone: 1,
        deliverablesTotal: 2,
        escrowFunded: true,
      },
    ]);
    contractsUnsignedMock.mockResolvedValue([{ id: 'ct1' }]);
    creatorDeliverablesListForDealsMock.mockResolvedValue({
      d2: [{ id: 'x', status: 'PENDING', completed: false }],
    });
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    // unread (2 + 1 = 3) + submittable deliverables (1, only from the active deal d2) +
    // unsigned contracts (1) = 5.
    const tile = await screen.findByTestId('desk-tile-attention');
    expect(tile).toHaveTextContent('5');
    expect(tile).toHaveTextContent('items need your attention');
    expect(tile).toHaveAttribute('href', '/creator/deals');
    // Only the ACTIVE deal's id was ever asked for deliverables.
    expect(creatorDeliverablesListForDealsMock).toHaveBeenCalledWith(['d2']);
  });

  it('uses the singular label for exactly 1', async () => {
    dealsListMock.mockResolvedValue([]);
    contractsUnsignedMock.mockResolvedValue([{ id: 'ct1' }]);
    creatorDeliverablesListForDealsMock.mockResolvedValue({});
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    const tile = await screen.findByTestId('desk-tile-attention');
    expect(tile).toHaveTextContent('1');
    expect(tile).toHaveTextContent('item needs your attention');
    expect(tile).not.toHaveTextContent('items need');
  });

  it('shows a check icon and "All caught up" instead of a bare 0', async () => {
    dealsListMock.mockResolvedValue([]);
    contractsUnsignedMock.mockResolvedValue([]);
    creatorDeliverablesListForDealsMock.mockResolvedValue({});
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    const tile = await screen.findByTestId('desk-tile-attention');
    expect(tile).toHaveTextContent('All caught up');
    expect(tile.textContent).not.toMatch(/^0$/);
    expect(tile).toHaveAttribute('href', '/creator/deals');
  });

  it('hides the tile when any source in the chain rejects, never showing a fake number', async () => {
    dealsListMock.mockRejectedValue(new Error('deals down'));
    contractsUnsignedMock.mockResolvedValue([]);
    creatorDeliverablesListForDealsMock.mockResolvedValue({});
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    await waitFor(() => expect(dealsListMock).toHaveBeenCalled());
    expect(screen.queryByTestId('desk-tile-attention')).not.toBeInTheDocument();
  });
});

describe('MeeraDesk — other tiles', () => {
  it('prefers "Pending Payouts" when there is money on the way, with the wallet page\'s exact wording', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockResolvedValue({ availableBalance: 12000, escrowLocked: 0, pendingPayouts: 3000, runwayDays: null });
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    const tile = await screen.findByTestId('desk-tile-wallet');
    expect(tile).toHaveTextContent('Pending Payouts');
    expect(tile).toHaveTextContent('₹3,000');
    expect(tile).toHaveAttribute('href', '/creator/wallet');
  });

  it('falls back to "Available Balance" when there is no pending payout', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockResolvedValue({ availableBalance: 8000, escrowLocked: 0, pendingPayouts: 0, runwayDays: null });
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    const tile = await screen.findByTestId('desk-tile-wallet');
    expect(tile).toHaveTextContent('Available Balance');
    expect(tile).toHaveTextContent('₹8,000');
  });

  it('shows the engagement tile only with a real analytics value, labelled exactly as the dashboard does', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockResolvedValue({
      pageViews: { last30Days: 1247, deltaPercent: 18 },
      profileClicks: 342,
      profileClicksEstimated: true,
      linkClicks: [],
      brandInquiries: 6,
      mediaKitDownloads: 23,
    });

    renderDesk();

    const tile = await screen.findByTestId('desk-tile-engagement');
    expect(tile).toHaveTextContent('1,247');
    expect(tile).toHaveTextContent('Profile views · 30d');
    expect(tile).toHaveTextContent('+18%');
  });

  it('never shows an "up from last month" delta when the API gives no positive delta', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockResolvedValue({
      pageViews: { last30Days: 90, deltaPercent: 0 },
      profileClicks: 5,
      profileClicksEstimated: true,
      linkClicks: [],
      brandInquiries: 0,
      mediaKitDownloads: 0,
    });

    renderDesk();

    const tile = await screen.findByTestId('desk-tile-engagement');
    expect(tile).toHaveTextContent('90');
    expect(tile.textContent).not.toMatch(/%/);
  });

  it('hides the engagement tile when the analytics call fails', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    await waitFor(() => expect(portfolioAnalyticsMock).toHaveBeenCalled());
    expect(screen.queryByTestId('desk-tile-engagement')).not.toBeInTheDocument();
  });

  it('always renders "Ask Meera my rate" — it never fetches anything', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    renderDesk();

    expect(screen.getByTestId('desk-tile-ask-rate')).toBeInTheDocument();
    // Let the other three tiles' rejected fetches settle inside `act` before the test tears
    // down, so their state updates don't warn on an unmounted/outside-act render.
    await waitFor(() => expect(dealsListMock).toHaveBeenCalled());
  });
});

describe('MeeraDesk — prefill-only actions (R-U1)', () => {
  it('"Ask Meera my rate" prefills the exact question, never sends', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    const user = userEvent.setup();
    const { onPrefill } = renderDesk();

    await user.click(screen.getByTestId('desk-tile-ask-rate'));
    expect(onPrefill).toHaveBeenCalledWith('What rate should I charge for a reel?');
    expect(onPrefill).toHaveBeenCalledTimes(1);
  });

  it('renders all 4 starter prompts and prefills their exact text on tap', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    const user = userEvent.setup();
    const { onPrefill } = renderDesk();

    const prompts = screen.getAllByTestId('desk-starter-prompt');
    expect(prompts).toHaveLength(4);

    await user.click(screen.getByRole('button', { name: 'Write me a reel script' }));
    expect(onPrefill).toHaveBeenCalledWith('Write me a reel script');
  });
});
