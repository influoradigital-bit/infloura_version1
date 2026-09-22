/**
 * BuyCreditsSheet (T-CREATOR-CREDITS-V2, SPEC.md §9.3 F6/A48) — verify-driven success.
 *
 * THE ONE RULE THIS FILE EXISTS TO PIN (K-11/R7, see the component's own file-level doc):
 * Razorpay Checkout's `handler` callback (`onSuccess`) only means "the browser saw a success
 * screen" — it is UNTRUSTED. This component must NEVER flip to the success state just because
 * `onSuccess` fired; it may only do so once a server-side `verify` call (or a later poll of
 * `GET /creator/credits` showing the balance actually grew) confirms `CREDITED`.
 *
 * Every test below drives `onSuccess`/`onDismiss` directly (captured from the mocked
 * `openRazorpayCheckout` call) rather than a real Razorpay modal — jsdom has no Checkout iframe,
 * and the whole point is to prove this component's OWN state machine, independent of the SDK.
 *
 * Run: npx vitest run src/components/creator/credits/BuyCreditsSheet.test.tsx
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BuyCreditsSheet } from './BuyCreditsSheet';
import { ApiError, type CreatorCreditBalance } from '@/lib/api';

const { createOrderMock, verifyMock, getMock, openCheckoutMock } = vi.hoisted(() => ({
  createOrderMock: vi.fn(),
  verifyMock: vi.fn(),
  getMock: vi.fn(),
  openCheckoutMock: vi.fn(),
}));

vi.mock('@/lib/api', () => {
  class ApiError extends Error {
    code: string;
    constructor(code: string, message: string) {
      super(message);
      this.code = code;
    }
  }
  return {
    ApiError,
    api: {
      creatorCredits: {
        createOrder: (...args: unknown[]) => createOrderMock(...args),
        verify: (...args: unknown[]) => verifyMock(...args),
        get: (...args: unknown[]) => getMock(...args),
      },
    },
  };
});

vi.mock('@/lib/razorpay', () => ({
  openRazorpayCheckout: (...args: unknown[]) => openCheckoutMock(...args),
}));

interface CapturedCheckoutParams {
  orderId: string;
  amount?: number;
  currency?: string;
  onSuccess: (response: { razorpay_payment_id: string; razorpay_order_id: string; razorpay_signature?: string }) => void;
  onDismiss: (reason?: string) => void;
}

/** An order response shaped like `CreatorCreditController`'s real `createOrder` reply. */
const ORDER_RESPONSE = {
  orderId: 'order_1',
  razorpayOrderId: 'rzp_order_1',
  amountPaise: 24900,
  currency: 'INR',
  credits: 60,
  keyId: 'rzp_test_key',
};

const BASE_BALANCE: CreatorCreditBalance = {
  enabled: true,
  total: 55,
  free: 40,
  paid: 15,
  dailyUsed: 3,
  dailyCap: 30,
};

/** Resolves once `openRazorpayCheckout` has been called, and returns the params it was given. */
async function getCheckoutParams(): Promise<CapturedCheckoutParams> {
  await waitFor(() => expect(openCheckoutMock).toHaveBeenCalled());
  return openCheckoutMock.mock.calls[openCheckoutMock.mock.calls.length - 1][0] as CapturedCheckoutParams;
}

beforeEach(() => {
  createOrderMock.mockReset().mockResolvedValue(ORDER_RESPONSE);
  verifyMock.mockReset();
  getMock.mockReset();
  openCheckoutMock.mockReset().mockImplementation(async (params: CapturedCheckoutParams) => {
    // Mirrors the real `openRazorpayCheckout`: resolves once the modal is "opened", well before
    // the human interacts with it — it never waits on `onSuccess`/`onDismiss` itself.
    return params;
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('BuyCreditsSheet (A48 — verify-driven success, never credits on onSuccess alone)', () => {
  it('does NOT show success or call onCredited when Checkout onSuccess fires but verify has not resolved yet', async () => {
    let resolveVerify!: (value: { status: 'CREDITED' | 'PENDING'; balance: number }) => void;
    verifyMock.mockReturnValue(
      new Promise((resolve) => {
        resolveVerify = resolve;
      }),
    );
    const onCredited = vi.fn();

    render(<BuyCreditsSheet open onOpenChange={vi.fn()} balance={BASE_BALANCE} onCredited={onCredited} />);

    fireEvent.click(screen.getByTestId('buy-sheet-confirm'));
    await waitFor(() => expect(createOrderMock).toHaveBeenCalled());

    const params = await getCheckoutParams();
    act(() => {
      params.onSuccess({ razorpay_payment_id: 'pay_1', razorpay_order_id: 'rzp_order_1', razorpay_signature: 'sig_1' });
    });

    // The handler fired and verify() was called with it — but until verify SETTLES, the sheet
    // must show neither success nor call the caller's credited callback.
    await waitFor(() => expect(verifyMock).toHaveBeenCalledWith('order_1', 'pay_1', 'sig_1'));
    expect(screen.queryByTestId('buy-sheet-success')).not.toBeInTheDocument();
    expect(onCredited).not.toHaveBeenCalled();

    // Only once verify itself resolves CREDITED does the sheet flip to success.
    await act(async () => {
      resolveVerify({ status: 'CREDITED', balance: 115 });
    });

    expect(await screen.findByTestId('buy-sheet-success')).toBeInTheDocument();
    expect(onCredited).toHaveBeenCalledWith(115);
    expect(onCredited).toHaveBeenCalledTimes(1);
  });

  it('a PENDING verify never credits on its own — only a later poll showing the balance actually grew does', async () => {
    vi.useFakeTimers();
    verifyMock.mockResolvedValue({ status: 'PENDING', balance: 55 });
    // First poll tick: balance hasn't moved yet. Second: the webhook landed and it grew by the
    // full pack size (55 + 60 = 115) — only THAT is allowed to flip the sheet to success.
    getMock
      .mockResolvedValueOnce({ enabled: true, total: 55 })
      .mockResolvedValueOnce({ enabled: true, total: 115 });
    const onCredited = vi.fn();

    render(<BuyCreditsSheet open onOpenChange={vi.fn()} balance={BASE_BALANCE} onCredited={onCredited} />);

    fireEvent.click(screen.getByTestId('buy-sheet-confirm'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(openCheckoutMock).toHaveBeenCalled();
    const params = openCheckoutMock.mock.calls[openCheckoutMock.mock.calls.length - 1][0] as CapturedCheckoutParams;

    await act(async () => {
      params.onSuccess({ razorpay_payment_id: 'pay_2', razorpay_order_id: 'rzp_order_1', razorpay_signature: 'sig_2' });
      await vi.advanceTimersByTimeAsync(0);
    });

    // verify() came back PENDING — still no success, no credit.
    expect(screen.queryByTestId('buy-sheet-success')).not.toBeInTheDocument();
    expect(onCredited).not.toHaveBeenCalled();

    // First poll tick (3s): balance unchanged — must still not credit.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(getMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('buy-sheet-success')).not.toBeInTheDocument();
    expect(onCredited).not.toHaveBeenCalled();

    // Second poll tick: the balance actually grew by the pack size — NOW it may credit.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });

    expect(screen.getByTestId('buy-sheet-success')).toBeInTheDocument();
    expect(onCredited).toHaveBeenCalledWith(115);
    expect(onCredited).toHaveBeenCalledTimes(1);
  });

  it('a failed verify shows the failure state with the server message and never credits', async () => {
    verifyMock.mockRejectedValue(new ApiError('PAYMENT_VERIFY_FAILED', 'We could not verify that payment.'));
    const onCredited = vi.fn();

    render(<BuyCreditsSheet open onOpenChange={vi.fn()} balance={BASE_BALANCE} onCredited={onCredited} />);

    fireEvent.click(screen.getByTestId('buy-sheet-confirm'));
    const params = await getCheckoutParams();

    await act(async () => {
      params.onSuccess({ razorpay_payment_id: 'pay_3', razorpay_order_id: 'rzp_order_1', razorpay_signature: 'sig_3' });
    });

    expect(await screen.findByTestId('buy-sheet-failed')).toHaveTextContent('We could not verify that payment.');
    expect(screen.queryByTestId('buy-sheet-success')).not.toBeInTheDocument();
    expect(onCredited).not.toHaveBeenCalled();
  });

  it('closing Checkout without paying (onDismiss with no reason) resets to idle — never failed, never success', async () => {
    verifyMock.mockResolvedValue({ status: 'CREDITED', balance: 999 });

    render(<BuyCreditsSheet open onOpenChange={vi.fn()} balance={BASE_BALANCE} onCredited={vi.fn()} />);

    fireEvent.click(screen.getByTestId('buy-sheet-confirm'));
    const params = await getCheckoutParams();

    act(() => {
      params.onDismiss();
    });

    expect(screen.queryByTestId('buy-sheet-failed')).not.toBeInTheDocument();
    expect(screen.queryByTestId('buy-sheet-success')).not.toBeInTheDocument();
    expect(screen.queryByTestId('buy-sheet-pending')).not.toBeInTheDocument();
    // verify() must never even be invoked — no money-moved signal was ever received.
    expect(verifyMock).not.toHaveBeenCalled();
    // Back to the un-busy Buy button, not stuck spinning.
    await waitFor(() => expect(screen.getByTestId('buy-sheet-confirm')).not.toBeDisabled());
  });

  it('a createOrder failure never opens Checkout and shows the failure state instead', async () => {
    createOrderMock.mockReset().mockRejectedValue(new ApiError('ORDER_FAILED', 'Could not start checkout.'));

    render(<BuyCreditsSheet open onOpenChange={vi.fn()} balance={BASE_BALANCE} onCredited={vi.fn()} />);

    fireEvent.click(screen.getByTestId('buy-sheet-confirm'));

    expect(await screen.findByTestId('buy-sheet-failed')).toHaveTextContent('Could not start checkout.');
    expect(openCheckoutMock).not.toHaveBeenCalled();
    expect(verifyMock).not.toHaveBeenCalled();
  });
});
