/**
 * Where a creator SEES their Meera credits outside the chat: the hero chip on the Co-pilot page
 * and the "Meera credits" card on the Wallet page. Both must vanish while the flag is off, show
 * the live balance when on, and open the same top-up sheet.
 *
 * Run: npx vitest run src/components/creator/credits/CreditsPlacements.test.tsx
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CreatorCreditBalance } from '@/lib/api';
import { CreatorCreditsWalletCard } from './CreatorCreditsWalletCard';
import { HeroCreditsChip } from './HeroCreditsChip';

const { hookMock, listOrdersMock } = vi.hoisted(() => ({
  hookMock: vi.fn(),
  listOrdersMock: vi.fn(),
}));

vi.mock('@/hooks/useCreatorCredits', () => ({ useCreatorCredits: hookMock }));
vi.mock('@/lib/api', () => ({ api: { creatorCredits: { listOrders: listOrdersMock } } }));
vi.mock('./BuyCreditsSheet', () => ({
  BuyCreditsSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="buy-sheet-open" /> : null),
}));

const BALANCE: CreatorCreditBalance = {
  enabled: true,
  total: 47,
  free: 12,
  paid: 35,
  dailyUsed: 4,
  dailyCap: 30,
  nextMonthlyGrantAt: '2026-10-01T00:00:00Z',
  paidExpiring: [{ credits: 35, expiresAt: '2026-12-20T00:00:00Z' }],
};

function hookReturning(balance: CreatorCreditBalance | null, enabled = true) {
  hookMock.mockReturnValue({
    loading: false,
    enabled,
    balance,
    refresh: vi.fn(),
    applyCreditsRemaining: vi.fn(),
  });
}

beforeEach(() => {
  hookMock.mockReset();
  listOrdersMock.mockReset();
  listOrdersMock.mockResolvedValue([]);
});

describe('HeroCreditsChip', () => {
  it('renders nothing while credits are switched off', () => {
    hookReturning({ enabled: false }, false);
    const { container } = render(<HeroCreditsChip language="en" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the balance and opens the top-up sheet on tap', () => {
    hookReturning(BALANCE);
    render(<HeroCreditsChip language="en" />);
    const pill = screen.getByTestId('credit-balance-pill');
    expect(pill.textContent).toContain('47');
    expect(screen.queryByTestId('buy-sheet-open')).toBeNull();
    fireEvent.click(pill);
    expect(screen.getByTestId('buy-sheet-open')).toBeInTheDocument();
  });
});

describe('CreatorCreditsWalletCard', () => {
  it('renders nothing and never calls the orders API while credits are switched off', () => {
    hookReturning({ enabled: false }, false);
    const { container } = render(<CreatorCreditsWalletCard />);
    expect(container).toBeEmptyDOMElement();
    expect(listOrdersMock).not.toHaveBeenCalled();
  });

  it('shows total, free/bought split, daily use, expiry, and the buy button opens the sheet', async () => {
    hookReturning(BALANCE);
    render(<CreatorCreditsWalletCard />);
    expect(screen.getByText('47 credits available')).toBeInTheDocument();
    expect(screen.getByText('12 free · 35 bought')).toBeInTheDocument();
    expect(screen.getByText('Used today: 4 of 30')).toBeInTheDocument();
    expect(screen.getByText(/35 bought credits expire on/)).toBeInTheDocument();
    expect(screen.getByText(/Your next 15 free credits arrive on/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('No purchases yet.')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /Buy 60 credits/ }));
    expect(screen.getByTestId('buy-sheet-open')).toBeInTheDocument();
  });

  it('lists purchases with amount, invoice number and status', async () => {
    hookReturning(BALANCE);
    listOrdersMock.mockResolvedValue([
      { orderId: 'o1', credits: 60, amountPaise: 24900, status: 'CREDITED', paidAt: '2026-09-20T10:00:00Z', invoiceNumber: 'INF-CR-0001' },
      { orderId: 'o2', credits: 60, amountPaise: 24900, status: 'PENDING' },
    ]);
    render(<CreatorCreditsWalletCard />);
    await waitFor(() => expect(screen.getAllByText(/60 credits · ₹249/)).toHaveLength(2));
    expect(screen.getByText(/INF-CR-0001/)).toBeInTheDocument();
    expect(screen.getByText('Added')).toBeInTheDocument();
    expect(screen.getByText('Processing')).toBeInTheDocument();
  });

  it('says so plainly when purchases fail to load', async () => {
    hookReturning(BALANCE);
    listOrdersMock.mockRejectedValue(new Error('boom'));
    render(<CreatorCreditsWalletCard />);
    await waitFor(() => expect(screen.getByText("Couldn't load your credits. Try again shortly.")).toBeInTheDocument());
  });
});
