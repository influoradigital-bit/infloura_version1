/**
 * Creator Wallet — there is no self-serve withdrawal (paytrigger).
 *
 * WHAT THIS FILE REPLACED
 * -----------------------
 * `creator-wallet-withdraw-idempotency.test.tsx` used to live here. It pinned a real fix: one
 * Idempotency-Key minted per logical withdrawal submission and reused across retries, so a
 * network failure plus a user retry could not slip past `WalletService.requestCreatorWithdrawal`'s
 * server-side dedupe and double-spend. That fix was correct for a product where the creator
 * pressed Withdraw.
 *
 * The owner's ruling of 2026-09-21 removed that product: Influora pays creators itself, by bank
 * transfer (NEFT/IMPS) to the account on their profile, within 2 working days of the live post
 * link. Self-serve withdrawal is off. So the guarantee this page needs is no longer "the retry
 * key is stable" but the strictly stronger "this page never issues the withdrawal at all" — and
 * that is what is pinned below. The coverage moved; it was not dropped.
 *
 * `api.wallet.withdraw` itself is deliberately left in the client facade and is NOT what this
 * file is about: it is still exercised by `src/lib/payments-gate.test.tsx` as a gated money
 * action. What must never happen again is a creator-facing surface calling it.
 *
 * Run: npx vitest run src/pages/creator-wallet-no-self-serve-withdrawal.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorWalletPage from './creator-wallet';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { displayName: 'Priya Sharma', firstName: 'Priya', role: 'creator' },
    logout: vi.fn(),
  }),
}));

const withdrawMock = vi.fn();
const walletGetMock = vi.fn();
const transactionsMock = vi.fn();
const getPayoutMethodsMock = vi.fn();
const platformFeeMock = vi.fn();
const payoutsMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      wallet: {
        get: (...a: unknown[]) => walletGetMock(...a),
        transactions: (...a: unknown[]) => transactionsMock(...a),
        getPayoutMethods: (...a: unknown[]) => getPayoutMethodsMock(...a),
        platformFee: (...a: unknown[]) => platformFeeMock(...a),
        payouts: (...a: unknown[]) => payoutsMock(...a),
        withdraw: (...a: unknown[]) => withdrawMock(...a),
      },
    },
  };
});

const PRIMARY_METHOD = {
  id: 'pm_1',
  type: 'UPI' as const,
  displayMask: 'priya@okaxis',
  isPrimary: true,
  usable: true,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/wallet']}>
      <CreatorWalletPage />
    </MemoryRouter>,
  );
}

describe('CreatorWalletPage — Influora pays; the creator never withdraws', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    walletGetMock.mockResolvedValue({
      availableBalance: 50000,
      escrowLocked: 10000,
      pendingPayouts: 0,
    });
    transactionsMock.mockResolvedValue([]);
    getPayoutMethodsMock.mockResolvedValue([PRIMARY_METHOD]);
    payoutsMock.mockResolvedValue([]);
    platformFeeMock.mockResolvedValue({
      feeBps: 1500,
      feePercent: 15,
      source: 'GLOBAL_DEFAULT',
      copy: '',
    });
  });

  it('renders no withdraw control in any state, enabled or disabled', async () => {
    renderPage();
    await waitFor(() => expect(getPayoutMethodsMock).toHaveBeenCalled());

    // `queryAllByRole` with `hidden: true` so a control that is merely disabled, or hidden
    // behind an unopened dialog, still counts as present. A DISABLED Withdraw button was the
    // previous state of this page: it moved no money, so every money-safety check passed, while
    // the screen kept promising a feature nobody is building.
    expect(screen.queryAllByRole('button', { name: /withdraw/i, hidden: true })).toHaveLength(0);
    expect(document.body.textContent).not.toMatch(/withdraw/i);
  });

  it('never calls api.wallet.withdraw, even with a usable payout method and a live balance', async () => {
    renderPage();
    await waitFor(() => expect(walletGetMock).toHaveBeenCalled());
    await waitFor(() => expect(getPayoutMethodsMock).toHaveBeenCalled());

    expect(withdrawMock).not.toHaveBeenCalled();
  });

  it('states who pays, how, and when — with the live link as the trigger', async () => {
    renderPage();

    const heading = await screen.findByRole('heading', { name: 'How you get paid' });
    const panel = heading.closest('div');
    expect(panel).not.toBeNull();

    expect(panel).toHaveTextContent(/Influora pays you by bank transfer \(NEFT\/IMPS\)/i);
    expect(panel).toHaveTextContent(/within 2 working days/i);
    // Approval is explicitly NOT the trigger — this is the sentence that stops a creator
    // waiting for money the moment a brand clicks approve.
    expect(panel).toHaveTextContent(/approving your draft\s+does not pay you, the live link does/i);
    expect(panel).toHaveTextContent(/Working days are Monday to Friday/i);
  });

  it('shows the creator which account the money is going to', async () => {
    renderPage();
    await waitFor(() => expect(getPayoutMethodsMock).toHaveBeenCalled());

    // The payout account is the only part of this the creator controls, so it has to be legible
    // without opening a dialog to find it.
    expect(await screen.findByText(/UPI priya@okaxis/i)).toBeInTheDocument();
  });

  it('tells a creator with no payout account on file that we cannot pay them yet', async () => {
    getPayoutMethodsMock.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(getPayoutMethodsMock).toHaveBeenCalled());

    expect(
      await screen.findByText(/No payout account on file yet/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Add a payout account/i })).toBeInTheDocument();
  });
});
