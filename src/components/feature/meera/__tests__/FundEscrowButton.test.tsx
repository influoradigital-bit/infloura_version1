/**
 * FundEscrowButton — F-0247 "premature success copy" regression spec
 * ----------------------------------------------------------------------------
 * LEDGER F-0247: the "₹X secured. Released only on your approval." string
 * must be reachable ONLY once the server confirms escrow status FUNDED
 * (see the component's header contract). Before this fix, it also rendered
 * during `initiating`, `awaiting_payment` (Razorpay modal open, nothing
 * paid) and `verifying` — telling the brand their money was secured while
 * zero rupees had moved.
 *
 * This drives the component through its real state machine (via the actual
 * `useEscrowFund` hook, with only the network edges mocked) the same way
 * FundEscrowButton.test.tsx does, and asserts the secured-money string is
 * absent for every non-FUNDED phase and present only once FUNDED.
 *
 * Run: npx vitest run src/components/feature/meera/__tests__/FundEscrowButton.test.tsx
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

import { api } from '@/lib/api';
import { meeraApi } from '@/lib/meera-api';
import { openRazorpayCheckout, type OpenCheckoutParams } from '@/lib/razorpay';
import { FundEscrowButton } from '../FundEscrowButton';

const fundEscrowMock = vi.mocked(meeraApi.fundEscrow);
const getEscrowStatusMock = vi.mocked(meeraApi.getEscrowStatus);
const openCheckoutMock = vi.mocked(openRazorpayCheckout);

/** The exact secured-money assertion this component may ONLY show once FUNDED. */
const SECURED_COPY = /secured\. Released only on your approval\./i;

describe('FundEscrowButton — F-0247 secured-money copy gating', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('idle: shows no secured-money assertion before any click', () => {
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);
    expect(screen.queryByText(SECURED_COPY)).not.toBeInTheDocument();
  });

  it('initiating: no secured-money assertion while the fund request is in flight', async () => {
    let resolveFund: (v: unknown) => void;
    fundEscrowMock.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveFund = resolve;
      }) as never,
    );

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);
    await user.click(screen.getByRole('button'));

    // Still initiating — the request hasn't resolved yet.
    await waitFor(() => expect(screen.getByRole('button')).toBeDisabled());
    expect(screen.queryByText(SECURED_COPY)).not.toBeInTheDocument();

    // Clean up the pending promise so it doesn't leak into later assertions.
    resolveFund!({ escrowHoldId: 'hold_x', amount: 5000, currency: 'INR', status: 'FUNDED' });
  });

  it('awaiting_payment: no secured-money assertion while the Razorpay modal is open and unpaid', async () => {
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_1',
      amount: 5000,
      currency: 'INR',
      razorpayOrderId: 'order_1',
      status: 'PENDING',
    });

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);
    await user.click(screen.getByRole('button'));

    await waitFor(() => expect(openCheckoutMock).toHaveBeenCalledTimes(1));

    // Zero rupees have moved: Checkout is open, nothing paid yet.
    expect(screen.queryByText(SECURED_COPY)).not.toBeInTheDocument();
    // Honest in-flight copy takes its place.
    expect(screen.getByText(/funds are not secured yet/i)).toBeInTheDocument();
  });

  it('verifying: no secured-money assertion while polling the server for FUNDED', async () => {
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_2',
      amount: 5000,
      currency: 'INR',
      razorpayOrderId: 'order_2',
      status: 'PENDING',
    });
    // Never resolves within this test — keeps the component parked in 'verifying'.
    getEscrowStatusMock.mockReturnValueOnce(new Promise(() => {}) as never);

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);
    await user.click(screen.getByRole('button'));

    await waitFor(() => expect(openCheckoutMock).toHaveBeenCalledTimes(1));
    const { onSuccess } = openCheckoutMock.mock.calls[0][0] as OpenCheckoutParams;

    // Razorpay client callback fires — the component must NOT trust it alone
    // and must move into 'verifying', not 'funded'.
    await waitFor(() => onSuccess({ razorpay_payment_id: 'pay_1', razorpay_order_id: 'order_2' }));
    await waitFor(() => expect(getEscrowStatusMock).toHaveBeenCalledTimes(1));

    expect(screen.queryByText(SECURED_COPY)).not.toBeInTheDocument();
    expect(screen.getByText(/verifying your payment/i)).toBeInTheDocument();
  });

  it('funded: shows the secured-money assertion only once the server confirms FUNDED', async () => {
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_3',
      amount: 5000,
      currency: 'INR',
      razorpayOrderId: 'order_3',
      status: 'PENDING',
    });
    getEscrowStatusMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_3',
      status: 'FUNDED',
      amount: 5000,
      currency: 'INR',
    } as never);

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);
    await user.click(screen.getByRole('button'));

    await waitFor(() => expect(openCheckoutMock).toHaveBeenCalledTimes(1));
    const { onSuccess } = openCheckoutMock.mock.calls[0][0] as OpenCheckoutParams;
    await waitFor(() => onSuccess({ razorpay_payment_id: 'pay_2', razorpay_order_id: 'order_3' }));

    await waitFor(() => expect(screen.getByText(SECURED_COPY)).toBeInTheDocument());
  });

  it('insufficient_funds / topping_up: no secured-money assertion while covering a shortfall', async () => {
    const { ApiError } = await import('@/lib/api');
    fundEscrowMock.mockRejectedValueOnce(
      new ApiError('INSUFFICIENT_FUNDS', 'Insufficient funds', 402, {
        requiredAmount: 5000,
        walletBalance: 1000,
        shortfallAmount: 4000,
        currency: 'INR',
      }),
    );
    // Never resolves — keeps the component parked past insufficient_funds, in topping_up.
    vi.mocked(api.wallet.topUp).mockReturnValueOnce(new Promise(() => {}) as never);

    const user = userEvent.setup();
    render(<FundEscrowButton campaignId="camp_1" displayAmount={5000} />);
    await user.click(screen.getByRole('button'));

    await waitFor(() => expect(api.wallet.topUp).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(SECURED_COPY)).not.toBeInTheDocument();
  });
});
