/**
 * FundEscrowButton — money-path tests
 * ----------------------------------------------------------------------------
 * Flagged by Kavya (SHARED_CONTEXT.md "Kavya — Option 1 QA PASS", checklist
 * item #6): zero unit coverage on the real Razorpay launcher wiring. These
 * tests verify: human-click-only (never auto-fires from render), dismissed
 * Checkout on EITHER leg moves no money and returns to a clean state, and
 * the "money moves only when you approve" trust copy is present.
 *
 * Run: npx vitest run src/components/feature/meera/FundEscrowButton.test.tsx
 *
 * `@/lib/razorpay`'s `openRazorpayCheckout` is mocked — this never loads the
 * real Razorpay SDK or touches `window.Razorpay`. `@/lib/meera-api` and
 * `@/lib/api` are mocked the same way as useEscrowFund.test.ts so the two
 * suites exercise the identical real hook logic underneath.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    api: {
      ...actual.api,
      wallet: {
        ...actual.api.wallet,
        topUp: vi.fn(),
        get: vi.fn(),
      },
    },
  };
});

vi.mock('@/lib/meera-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/meera-api')>();
  return {
    ...actual,
    meeraApi: {
      ...actual.meeraApi,
      fundEscrow: vi.fn(),
      getEscrowStatus: vi.fn(),
    },
  };
});

vi.mock('@/lib/razorpay', () => ({
  openRazorpayCheckout: vi.fn(),
}));

import { api, ApiError } from '@/lib/api';
import { meeraApi } from '@/lib/meera-api';
import { openRazorpayCheckout, type OpenCheckoutParams } from '@/lib/razorpay';
import { FundEscrowButton } from './FundEscrowButton';

const fundEscrowMock = vi.mocked(meeraApi.fundEscrow);
const getEscrowStatusMock = vi.mocked(meeraApi.getEscrowStatus);
const topUpMock = vi.mocked(api.wallet.topUp);
const openCheckoutMock = vi.mocked(openRazorpayCheckout);

describe('FundEscrowButton', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('never calls fundEscrow on render — only on human click', async () => {
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);

    // Give any accidental effect-driven auto-fire a chance to happen.
    await new Promise((r) => setTimeout(r, 0));

    expect(fundEscrowMock).not.toHaveBeenCalled();
    expect(screen.getByText(/Money moves only when you approve\./i)).toBeInTheDocument();
  });

  it('human click initiates funding, disables the button while loading, and opens Checkout for the escrow order', async () => {
    let resolveFund: (v: unknown) => void;
    fundEscrowMock.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveFund = resolve;
      }) as never,
    );

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);

    const button = screen.getByRole('button');
    expect(button).toBeEnabled();

    await user.click(button);

    // Loading/disabled state while initiating.
    await waitFor(() => expect(button).toBeDisabled());
    expect(fundEscrowMock).toHaveBeenCalledTimes(1);

    resolveFund!({
      escrowHoldId: 'hold_1',
      amount: 5000,
      currency: 'INR',
      razorpayOrderId: 'order_1',
      status: 'PENDING',
    });

    await waitFor(() => expect(openCheckoutMock).toHaveBeenCalledTimes(1));
    const params = openCheckoutMock.mock.calls[0][0] as OpenCheckoutParams;
    expect(params.orderId).toBe('order_1');

    // Clicking again while a checkout is pending must NOT re-fire fundEscrow.
    await user.click(button);
    expect(fundEscrowMock).toHaveBeenCalledTimes(1);
  });

  it('[FIX: double-charge, 2026-07-26] never opens Checkout when the server funds immediately from the wallet', async () => {
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_1c',
      amount: 5000,
      currency: 'INR',
      status: 'FUNDED',
    });

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);

    await user.click(screen.getByRole('button'));

    await waitFor(() => expect(screen.getByRole('button')).toHaveTextContent(/secured/i));
    expect(openCheckoutMock).not.toHaveBeenCalled();
    expect(getEscrowStatusMock).not.toHaveBeenCalled();
  });

  it('dismissing the ESCROW-fund Checkout moves no money and returns to a clean idle state', async () => {
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_2',
      amount: 5000,
      currency: 'INR',
      razorpayOrderId: 'order_2',
      status: 'PENDING',
    });

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);

    await user.click(screen.getByRole('button'));
    await waitFor(() => expect(openCheckoutMock).toHaveBeenCalledTimes(1));

    // Simulate the human closing the Razorpay modal without paying.
    const { onDismiss } = openCheckoutMock.mock.calls[0][0] as OpenCheckoutParams;
    await waitFor(() => onDismiss());

    // No verification/poll was ever started — no money-confirming call fired.
    expect(getEscrowStatusMock).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole('button')).toBeEnabled());
    expect(screen.getByText(/Money moves only when you approve\./i)).toBeInTheDocument();
  });

  it('dismissing the TOP-UP Checkout (insufficient-funds leg) moves no money and returns to idle, not a stranded state', async () => {
    fundEscrowMock.mockRejectedValueOnce(
      new ApiError('INSUFFICIENT_FUNDS', 'Insufficient funds', 402, {
        requiredAmount: 5000,
        walletBalance: 1000,
        shortfallAmount: 4000,
        currency: 'INR',
      }),
    );
    topUpMock.mockResolvedValueOnce({
      topUpId: 'tu_1',
      amount: 4000,
      currency: 'INR',
      razorpayOrderId: 'order_topup_1',
      status: 'PENDING',
    });

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);

    await user.click(screen.getByRole('button'));

    // Auto-advances insufficient_funds -> beginTopUp -> awaiting_topup_payment
    // -> opens Checkout sized from the SERVER shortfall (4000), never the
    // displayAmount hint (5000).
    await waitFor(() => expect(openCheckoutMock).toHaveBeenCalledTimes(1));
    expect(topUpMock).toHaveBeenCalledTimes(1);
    expect(topUpMock.mock.calls[0][0]).toEqual({ amount: 4000 });

    const { onDismiss } = openCheckoutMock.mock.calls[0][0] as OpenCheckoutParams;
    await waitFor(() => onDismiss());

    // No wallet-credit confirmation and no escrow-fund retry after a dismissed top-up.
    expect(fundEscrowMock).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole('button')).toBeEnabled());
    expect(screen.getByText(/Money moves only when you approve\./i)).toBeInTheDocument();
  });

  it('shows an error and an accessible alert when the 402 has no server shortfall (no re-estimate, no top-up)', async () => {
    fundEscrowMock.mockRejectedValueOnce(new ApiError('INSUFFICIENT_FUNDS', 'Insufficient funds', 402, undefined));

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);

    await user.click(screen.getByRole('button'));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(topUpMock).not.toHaveBeenCalled();
    expect(openCheckoutMock).not.toHaveBeenCalled();
    expect(screen.getByRole('button')).toHaveTextContent(/try again/i);
  });
});
