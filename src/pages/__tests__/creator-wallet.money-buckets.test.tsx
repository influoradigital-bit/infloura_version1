/**
 * Creator Wallet — F-0281 (label-ambiguity) + F-0336 (creator-escrow-tile-reads-dead-column).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Two records, closed together because the first could not be fixed with copy alone:
 *
 *   F-0336: `escrowLocked` for a creator used to read `wallets.escrow_balance` — a column no
 *   backend service ever writes — so "In Escrow" was permanently ₹0 no matter how much money a
 *   brand had actually funded into escrow for that creator. WalletService#getSummaryForUser now
 *   derives it from the creator's FUNDED PaymentMilestone sum, the same way the money is real
 *   elsewhere on this page (Pending Payouts used to read this exact figure).
 *
 *   F-0281: `pendingPayouts` used to hold that same FUNDED-milestone figure under a label
 *   ("Pending Payouts") that reads as "a withdrawal is already on its way to my bank" — the
 *   opposite of what FUNDED-but-not-released money is. It now holds the sum of the creator's
 *   in-flight Payout rows (already debited from Available Balance, not yet gateway-confirmed).
 *
 * This file proves the RENDERED page, not the derivation (WalletServiceTest covers that): each
 * tile's number is what its label says, each tile carries a one-line definition matching what the
 * figure actually is, and an unavailable fetch renders "—" — visibly distinct from a genuine ₹0 —
 * so a creator whose data failed to load cannot be mistaken for one who has no money at all
 * (the F-0260 absent-vs-zero class).
 *
 * Run: npx vitest run src/pages/__tests__/creator-wallet.money-buckets.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorWalletPage from '../creator-wallet';

const walletGetMock = vi.fn();
const walletTransactionsMock = vi.fn();
const walletPayoutsMock = vi.fn();
const walletPayoutMethodsMock = vi.fn();
const walletPlatformFeeMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      wallet: {
        ...actual.api.wallet,
        get: (...a: unknown[]) => walletGetMock(...a),
        transactions: (...a: unknown[]) => walletTransactionsMock(...a),
        payouts: (...a: unknown[]) => walletPayoutsMock(...a),
        getPayoutMethods: (...a: unknown[]) => walletPayoutMethodsMock(...a),
        platformFee: (...a: unknown[]) => walletPlatformFeeMock(...a),
      },
    },
  };
});

function renderWallet() {
  return render(
    <MemoryRouter>
      <CreatorWalletPage />
    </MemoryRouter>,
  );
}

/** Opens a WalletFigureLabel tooltip by focusing its info trigger (Radix opens on focus). */
async function openDefinition(labelText: string): Promise<HTMLElement> {
  const trigger = screen.getByRole('button', { name: new RegExp(`what is ${labelText}\\?`, 'i') });
  fireEvent.focus(trigger);
  return await waitFor(() => {
    const tooltip = screen.getByRole('tooltip');
    expect(tooltip).toBeInTheDocument();
    return tooltip;
  });
}

beforeEach(() => {
  walletGetMock.mockReset();
  walletTransactionsMock.mockReset().mockResolvedValue({ items: [], meta: { page: 1, limit: 20, total: 0, hasNext: false } });
  walletPayoutsMock.mockReset().mockResolvedValue([]);
  walletPayoutMethodsMock.mockReset().mockResolvedValue([]);
  walletPlatformFeeMock.mockReset().mockResolvedValue({ feePercent: 15 });
  localStorage.clear();
});

describe('CreatorWalletPage — F-0336 escrow figure is no longer a permanent zero', () => {
  it('renders a non-zero "In Escrow" figure for a creator with FUNDED milestone money', async () => {
    walletGetMock.mockResolvedValue({
      availableBalance: 5000,
      escrowLocked: 15000, // the creator's FUNDED milestone sum — the F-0336 figure
      pendingPayouts: 2500,
      runwayDays: null,
    });

    renderWallet();

    await waitFor(() => {
      expect(screen.getByLabelText('In escrow')).toHaveTextContent('₹15,000');
    });
    // The three tiles must be numerically distinct, not the same figure duplicated under two
    // labels (which is what the pre-fix code effectively did once F-0336 fed dead-column zero
    // into one tile while pushing the real escrow figure out under "Pending Payouts").
    expect(screen.getByLabelText('Available balance')).toHaveTextContent('₹5,000');
    expect(screen.getByLabelText('Pending payouts')).toHaveTextContent('₹2,500');
  });
});

describe('CreatorWalletPage — F-0281 each label matches the bucket beside it', () => {
  it('"In Escrow" is defined as brand-funded, not-yet-approved money — not a withdrawal in flight', async () => {
    walletGetMock.mockResolvedValue({
      availableBalance: 5000,
      escrowLocked: 15000,
      pendingPayouts: 2500,
      runwayDays: null,
    });
    renderWallet();
    await waitFor(() => expect(screen.getByLabelText('In escrow')).toHaveTextContent('₹15,000'));

    const tooltip = await openDefinition('In Escrow');
    expect(within(tooltip).getByText(/locked for a deal/i)).toBeInTheDocument();
    expect(within(tooltip).getByText(/not withdrawable yet/i)).toBeInTheDocument();
    // Must NOT describe the OTHER bucket (a withdrawal already headed to the bank) — the exact
    // mislabeling F-0281 opened against this tile's neighbor.
    expect(tooltip.textContent).not.toMatch(/on its way to your bank/i);
  });

  it('"Pending Payouts" is defined as a withdrawal already requested and in flight — not escrowed money', async () => {
    walletGetMock.mockResolvedValue({
      availableBalance: 5000,
      escrowLocked: 15000,
      pendingPayouts: 2500,
      runwayDays: null,
    });
    renderWallet();
    await waitFor(() => expect(screen.getByLabelText('Pending payouts')).toHaveTextContent('₹2,500'));

    const tooltip = await openDefinition('Pending Payouts');
    expect(within(tooltip).getByText(/on its way to your bank/i)).toBeInTheDocument();
    expect(within(tooltip).getByText(/already deducted from Available Balance/i)).toBeInTheDocument();
    // Must NOT describe the OTHER bucket (brand-funded, not-yet-released escrow) — the F-0281
    // defect verbatim: this tile used to carry exactly that definition over exactly that number.
    expect(tooltip.textContent).not.toMatch(/brand has committed but hasn't released/i);
  });

  it('"Available Balance" is defined as already released and withdrawable now', async () => {
    walletGetMock.mockResolvedValue({
      availableBalance: 5000,
      escrowLocked: 15000,
      pendingPayouts: 2500,
      runwayDays: null,
    });
    renderWallet();
    await waitFor(() => expect(screen.getByLabelText('Available balance')).toHaveTextContent('₹5,000'));

    const tooltip = await openDefinition('Available Balance');
    expect(within(tooltip).getByText(/already released to you/i)).toBeInTheDocument();
    expect(within(tooltip).getByText(/right now/i)).toBeInTheDocument();
  });
});

describe('CreatorWalletPage — absent is not zero (F-0260 class)', () => {
  it('a failed wallet fetch renders "—" on all three tiles, not a fabricated ₹0', async () => {
    walletGetMock.mockRejectedValue(new Error('network down'));

    renderWallet();

    await waitFor(() => {
      expect(screen.getByText(/could not refresh wallet balance/i)).toBeInTheDocument();
    });
    expect(screen.getByLabelText('Available balance')).toHaveTextContent('—');
    expect(screen.getByLabelText('In escrow')).toHaveTextContent('—');
    expect(screen.getByLabelText('Pending payouts')).toHaveTextContent('—');
    // None of the three may render a bare "0"/"₹0" while the fetch is known to have failed.
    expect(screen.getByLabelText('Available balance')).not.toHaveTextContent('₹0');
    expect(screen.getByLabelText('In escrow')).not.toHaveTextContent('₹0');
    expect(screen.getByLabelText('Pending payouts')).not.toHaveTextContent('₹0');
  });

  it('a creator who genuinely has no money at all sees a real ₹0, distinct from the unavailable "—" state', async () => {
    walletGetMock.mockResolvedValue({
      availableBalance: 0,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: null,
    });

    renderWallet();

    await waitFor(() => {
      expect(screen.getByLabelText('Available balance')).toHaveTextContent('₹0');
    });
    expect(screen.getByLabelText('In escrow')).toHaveTextContent('₹0');
    expect(screen.getByLabelText('Pending payouts')).toHaveTextContent('₹0');
    expect(screen.queryByText(/could not refresh wallet balance/i)).not.toBeInTheDocument();
  });
});
