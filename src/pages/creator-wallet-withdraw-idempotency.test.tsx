/**
 * Creator Wallet — withdraw Idempotency-Key lifecycle (P-4, BrandF.md §57).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * `handleWithdraw` used to mint `withdraw-${Date.now()}` fresh on every call. A network
 * failure followed by a user-initiated retry (click Withdraw again without closing the
 * dialog) therefore sent a DIFFERENT Idempotency-Key on the retry, defeating
 * `WalletService.requestCreatorWithdrawal`'s server-side dedupe (a scoped key derived from
 * the header — see `influora-api/.../WalletService.java:251`) and reopening the double-spend
 * window the money-in path (`brand-wallet.tsx`'s `topUpIdempotencyKey`) was already fixed for.
 *
 * This file pins the fix: one key minted per logical withdrawal submission, held in state,
 * reused across retries of that SAME submission, and only replaced when a genuinely new
 * submission begins (amount changed, dialog closed/reopened, or the prior submission
 * succeeded).
 *
 * Run: npx vitest run src/pages/creator-wallet-withdraw-idempotency.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

async function openWithdrawDialogWithAmount(amount: string) {
  renderPage();
  const user = userEvent.setup({ delay: null });
  // Wait for the payout-methods fetch to resolve so the dialog doesn't render the
  // "No payout method on file" empty state (which disables the confirm button).
  await waitFor(() => expect(getPayoutMethodsMock).toHaveBeenCalled());
  await user.click(screen.getByRole('button', { name: /^Withdraw$/i }));
  const input = await screen.findByLabelText('Amount to Withdraw');
  await user.clear(input);
  await user.type(input, amount);
  return user;
}

describe('CreatorWalletPage — withdraw Idempotency-Key lifecycle (P-4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    walletGetMock.mockResolvedValue({
      availableBalance: 50000,
      escrowLocked: 10000,
      pendingPayouts: 0,
    });
    transactionsMock.mockResolvedValue([]);
    getPayoutMethodsMock.mockResolvedValue([PRIMARY_METHOD]);
    platformFeeMock.mockResolvedValue({
      feeBps: 1500,
      feePercent: 15,
      source: 'GLOBAL_DEFAULT',
      copy: '',
    });
  });

  it('reuses the same Idempotency-Key when the user retries after a failed withdrawal', async () => {
    // First attempt fails (simulated network/server error) — no money moved.
    withdrawMock.mockRejectedValueOnce(new Error('network down'));
    // Retry succeeds.
    withdrawMock.mockResolvedValueOnce({ payoutId: 'po_1' });

    const user = await openWithdrawDialogWithAmount('2000');

    const withdrawButtons = () => screen.getAllByRole('button', { name: /^Withdraw$/ });
    // The dialog's confirm button is the second "Withdraw"-labelled button (header CTA is first).
    const confirmButton = () => withdrawButtons()[withdrawButtons().length - 1];

    await user.click(confirmButton());
    await waitFor(() => expect(withdrawMock).toHaveBeenCalledTimes(1));
    await screen.findByText(/Withdrawal failed|network down/i);

    await user.click(confirmButton());
    await waitFor(() => expect(withdrawMock).toHaveBeenCalledTimes(2));

    const firstKey = withdrawMock.mock.calls[0][1];
    const secondKey = withdrawMock.mock.calls[1][1];
    expect(typeof firstKey).toBe('string');
    expect(firstKey.length).toBeGreaterThan(0);
    // The bug: `withdraw-${Date.now()}` regenerated the key on every call. The fix: the SAME
    // key must be reused across retries of the same submission.
    expect(secondKey).toBe(firstKey);
  });

  it('does not fire a second request with a different key when Withdraw is clicked again before the first response returns', async () => {
    let resolveWithdraw: (v: { payoutId: string }) => void = () => {};
    withdrawMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveWithdraw = resolve;
        }),
    );

    const user = await openWithdrawDialogWithAmount('2000');
    const withdrawButtons = () => screen.getAllByRole('button', { name: /^Withdraw$/ });
    // Capture the actual DOM node before clicking — once isWithdrawing flips true the button's
    // accessible name becomes empty (spinner icon, no text), so it must be re-queried by role
    // BEFORE the click, not after.
    const confirmButtonNode = withdrawButtons()[withdrawButtons().length - 1];

    await user.click(confirmButtonNode);
    await waitFor(() => expect(withdrawMock).toHaveBeenCalledTimes(1));

    // While the first call is still in flight, the confirm button is disabled (isWithdrawing),
    // so a rapid second click cannot dispatch a second request with a different key — the
    // in-flight submission owns the single held key until it settles.
    expect(confirmButtonNode).toBeDisabled();
    fireEvent.click(confirmButtonNode);
    expect(withdrawMock).toHaveBeenCalledTimes(1);

    resolveWithdraw({ payoutId: 'po_2' });
    await waitFor(() => expect(screen.queryByText(/Withdraw Funds/i)).not.toBeInTheDocument());
    expect(withdrawMock).toHaveBeenCalledTimes(1);
  });

  it('mints a fresh Idempotency-Key for a new withdrawal after a prior one succeeded', async () => {
    withdrawMock.mockResolvedValueOnce({ payoutId: 'po_3' });
    withdrawMock.mockResolvedValueOnce({ payoutId: 'po_4' });

    const user = await openWithdrawDialogWithAmount('2000');
    const withdrawButtons = () => screen.getAllByRole('button', { name: /^Withdraw$/ });
    const confirmButton = () => withdrawButtons()[withdrawButtons().length - 1];

    await user.click(confirmButton());
    await waitFor(() => expect(withdrawMock).toHaveBeenCalledTimes(1));
    // Dialog closes on success.
    await waitFor(() => expect(screen.queryByText(/Withdraw Funds/i)).not.toBeInTheDocument());

    const firstKey = withdrawMock.mock.calls[0][1];

    // Start a genuinely new withdrawal (same rendered page — reopen the dialog, don't re-render).
    await user.click(screen.getByRole('button', { name: /^Withdraw$/i }));
    const input = await screen.findByLabelText('Amount to Withdraw');
    await user.clear(input);
    await user.type(input, '3000');
    await user.click(confirmButton());
    await waitFor(() => expect(withdrawMock).toHaveBeenCalledTimes(2));

    const secondKey = withdrawMock.mock.calls[1][1];
    expect(secondKey).not.toBe(firstKey);
  });
});
