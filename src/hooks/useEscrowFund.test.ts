/**
 * useEscrowFund — money-path state-machine tests
 * ----------------------------------------------------------------------------
 * Flagged by Kavya (SHARED_CONTEXT.md "Kavya — Option 1 QA PASS", checklist
 * item #6): the 9-state hook had zero unit coverage. These tests mechanically
 * verify the money-safety edges backend tests can't reach from the frontend
 * side: server-shortfall-only top-up sizing, bounded top-up rounds, dismiss
 * mid-flow, idempotency-key discipline, and timer cleanup on unmount.
 *
 * Run: npx vitest run src/hooks/useEscrowFund.test.ts
 *
 * Mocking strategy: `@/lib/meera-api` (fundEscrow/getEscrowStatus) and
 * `@/lib/api` (wallet.topUp/wallet.get) are mocked via vi.mock with
 * importOriginal so the REAL `ApiError` class / `InsufficientFundsDetails`
 * type flow through unchanged (the hook does `err instanceof ApiError`).
 * Never hits network, never loads the real Razorpay SDK (this hook doesn't
 * even import razorpay.ts — that's FundEscrowButton's job, covered
 * separately in FundEscrowButton.test.tsx).
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';

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

import { api, ApiError } from '@/lib/api';
import { meeraApi } from '@/lib/meera-api';
import { useEscrowFund } from './useEscrowFund';

const fundEscrowMock = vi.mocked(meeraApi.fundEscrow);
const getEscrowStatusMock = vi.mocked(meeraApi.getEscrowStatus);
const topUpMock = vi.mocked(api.wallet.topUp);
const walletGetMock = vi.mocked(api.wallet.get);

const BALANCE_RECHECK_INTERVAL_MS = 2000;
const BALANCE_RECHECK_MAX_ATTEMPTS = 5;
// Enough fake-timer runway to drain the bounded balance-recheck loop plus margin.
const RECHECK_LOOP_MS = BALANCE_RECHECK_INTERVAL_MS * BALANCE_RECHECK_MAX_ATTEMPTS + 500;

function insufficientFundsError(shortfallAmount: number, requiredAmount = 1000, walletBalance?: number) {
  return new ApiError('INSUFFICIENT_FUNDS', 'Insufficient funds', 402, {
    requiredAmount,
    walletBalance: walletBalance ?? requiredAmount - shortfallAmount,
    shortfallAmount,
    currency: 'INR',
  });
}

describe('useEscrowFund', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    walletGetMock.mockResolvedValue({
      availableBalance: 0,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: null,
    } as never);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // -------------------------------------------------------------------------
  // 1. Happy path
  // -------------------------------------------------------------------------
  it('happy path: sufficient balance funds and reaches "funded" only via server FUNDED status', async () => {
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_1',
      amount: 5000,
      currency: 'INR',
      razorpayOrderId: 'order_1',
      status: 'PENDING',
    });
    getEscrowStatusMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_1',
      status: 'FUNDED',
      amount: 5000,
      currency: 'INR',
      campaignId: 'camp_1',
      milestoneId: null,
      fundedAt: new Date().toISOString(),
    } as never);

    const { result } = renderHook(() => useEscrowFund());

    await act(async () => {
      await result.current.initiateFund('camp_1', undefined, 5000);
    });

    expect(result.current.status).toBe('awaiting_payment');
    expect(result.current.razorpayOrderId).toBe('order_1');

    // Simulate the Razorpay Checkout success callback (untrusted) firing.
    await act(async () => {
      await result.current.onPaymentComplete();
    });

    // "funded" must come from meeraApi.getEscrowStatus polling, not the
    // Razorpay callback alone — assert the server status endpoint was hit.
    expect(getEscrowStatusMock).toHaveBeenCalledWith('hold_1');
    expect(result.current.status).toBe('funded');
    expect(result.current.serverAmount).toBe(5000);
  });

  // -------------------------------------------------------------------------
  // 2. Insufficient funds → server shortfall sizes the top-up, not the hint
  // -------------------------------------------------------------------------
  it('sizes the inline top-up from the SERVER shortfallAmount, never the client displayAmount hint', async () => {
    fundEscrowMock
      .mockRejectedValueOnce(insufficientFundsError(800, 1000, 200))
      .mockResolvedValueOnce({
        escrowHoldId: 'hold_2',
        amount: 1000,
        currency: 'INR',
        razorpayOrderId: 'order_2',
        status: 'PENDING',
      });
    topUpMock.mockResolvedValueOnce({
      topUpId: 'tu_1',
      amount: 800,
      currency: 'INR',
      razorpayOrderId: 'order_topup_1',
      status: 'PENDING',
    });

    const { result } = renderHook(() => useEscrowFund());

    // Client hint (e.g. FundEscrowButton's displayAmount) deliberately WRONG
    // (999) to prove it never leaks into the charge.
    await act(async () => {
      await result.current.initiateFund('camp_2', undefined, 999);
    });

    expect(result.current.status).toBe('insufficient_funds');
    expect(result.current.serverShortfall).toEqual({
      requiredAmount: 1000,
      walletBalance: 200,
      shortfallAmount: 800,
      currency: 'INR',
    });

    await act(async () => {
      await result.current.beginTopUp(result.current.serverShortfall!.shortfallAmount);
    });

    // The ONLY assertion that matters for money-safety: the amount handed to
    // /wallet/topup is the server shortfall (800), never the hint (999).
    expect(topUpMock).toHaveBeenCalledTimes(1);
    expect(topUpMock.mock.calls[0][0]).toEqual({ amount: 800 });
    expect(result.current.status).toBe('awaiting_topup_payment');
    expect(result.current.topUpOrder?.amount).toBe(800);

    // Complete the top-up leg and drain the bounded balance-recheck loop.
    vi.useFakeTimers();
    let completePromise: Promise<void>;
    act(() => {
      completePromise = result.current.onTopUpPaymentComplete();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RECHECK_LOOP_MS);
      await completePromise;
    });
    vi.useRealTimers();

    // Retried the SAME fund attempt (fundEscrow called a second time).
    expect(fundEscrowMock).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(result.current.status).toBe('awaiting_payment'));
    expect(result.current.razorpayOrderId).toBe('order_2');
  });

  // -------------------------------------------------------------------------
  // 3. 402 without shortfallAmount → error, NEVER re-estimate
  // -------------------------------------------------------------------------
  it('402 without server shortfall details errors out and never re-estimates or charges', async () => {
    fundEscrowMock.mockRejectedValueOnce(new ApiError('INSUFFICIENT_FUNDS', 'Insufficient funds', 402, undefined));

    const { result } = renderHook(() => useEscrowFund());

    await act(async () => {
      await result.current.initiateFund('camp_3', undefined, 5000);
    });

    expect(result.current.status).toBe('error');
    expect(result.current.error).toMatch(/exact top-up amount/i);
    expect(result.current.serverShortfall).toBeNull();
    // Money-safety: no top-up was ever attempted from a client guess.
    expect(topUpMock).not.toHaveBeenCalled();
  });

  // -------------------------------------------------------------------------
  // 4. Bounded top-up retries (MAX_TOPUP_ROUNDS)
  // -------------------------------------------------------------------------
  it('gives up after MAX_TOPUP_ROUNDS instead of looping forever when the webhook credit never lands', async () => {
    // Every fundEscrow attempt (initial + both retries) is short — the wallet
    // never actually gets credited (webhook never lands).
    fundEscrowMock.mockRejectedValue(insufficientFundsError(500));
    topUpMock.mockResolvedValue({
      topUpId: 'tu_x',
      amount: 500,
      currency: 'INR',
      razorpayOrderId: 'order_topup_x',
      status: 'PENDING',
    });

    const { result } = renderHook(() => useEscrowFund());

    await act(async () => {
      await result.current.initiateFund('camp_4', undefined, 500);
    });
    expect(result.current.status).toBe('insufficient_funds');

    vi.useFakeTimers();

    // Round 1: top up, complete, drain recheck loop → retries fund → still short.
    await act(async () => {
      await result.current.beginTopUp(500);
    });
    let p1: Promise<void>;
    act(() => {
      p1 = result.current.onTopUpPaymentComplete();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RECHECK_LOOP_MS);
      await p1;
    });
    expect(result.current.status).toBe('insufficient_funds'); // round 1 of 2 consumed, one more allowed

    // Round 2: top up again, complete, drain recheck loop → retries fund →
    // still short → MAX_TOPUP_ROUNDS exhausted → error, no more looping.
    await act(async () => {
      await result.current.beginTopUp(500);
    });
    let p2: Promise<void>;
    act(() => {
      p2 = result.current.onTopUpPaymentComplete();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RECHECK_LOOP_MS);
      await p2;
    });

    vi.useRealTimers();

    expect(result.current.status).toBe('error');
    expect(result.current.error).toMatch(/still short after topping up/i);
    // Bounded: initial attempt + exactly 2 retries = 3 fundEscrow calls, not unbounded.
    expect(fundEscrowMock).toHaveBeenCalledTimes(3);
    // Bounded: exactly 2 top-up rounds attempted, not unbounded.
    expect(topUpMock).toHaveBeenCalledTimes(2);
  });

  // -------------------------------------------------------------------------
  // 6. Idempotency-Key discipline
  // -------------------------------------------------------------------------
  it('reuses the SAME escrow Idempotency-Key across a top-up retry, and mints a FRESH key per top-up round', async () => {
    fundEscrowMock
      .mockRejectedValueOnce(insufficientFundsError(300))
      .mockRejectedValueOnce(insufficientFundsError(300)); // still short on retry -> second top-up round
    topUpMock.mockResolvedValue({
      topUpId: 'tu_y',
      amount: 300,
      currency: 'INR',
      razorpayOrderId: 'order_topup_y',
      status: 'PENDING',
    });

    const { result } = renderHook(() => useEscrowFund());

    await act(async () => {
      await result.current.initiateFund('camp_5', undefined, 300);
    });

    vi.useFakeTimers();

    await act(async () => {
      await result.current.beginTopUp(300);
    });
    let p1: Promise<void>;
    act(() => {
      p1 = result.current.onTopUpPaymentComplete();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RECHECK_LOOP_MS);
      await p1;
    });

    await act(async () => {
      await result.current.beginTopUp(300);
    });

    vi.useRealTimers();

    // fundEscrow: initial call + one retry (both INSUFFICIENT_FUNDS) — same
    // logical submission, same idempotency key both times.
    expect(fundEscrowMock).toHaveBeenCalledTimes(2);
    const key1 = fundEscrowMock.mock.calls[0][1];
    const key2 = fundEscrowMock.mock.calls[1][1];
    expect(key1).toBe(key2);
    expect(key1).toMatch(/^escrow-/);

    // Top-up: two distinct rounds -> two distinct minted keys (fresh per beginTopUp call).
    expect(topUpMock).toHaveBeenCalledTimes(2);
    const topUpKey1 = topUpMock.mock.calls[0][1];
    const topUpKey2 = topUpMock.mock.calls[1][1];
    expect(topUpKey1).not.toBe(topUpKey2);
    expect(topUpKey1).toMatch(/^topup-/);
    expect(topUpKey2).toMatch(/^topup-/);
  });

  // -------------------------------------------------------------------------
  // 7. Timer cleanup on unmount
  // -------------------------------------------------------------------------
  it('clears pending poll and balance-recheck timers on unmount (no state update after unmount)', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_7',
      amount: 1000,
      currency: 'INR',
      razorpayOrderId: 'order_7',
      status: 'PENDING',
    });
    // Never resolves to FUNDED — stays PENDING so the poll timer keeps rescheduling.
    getEscrowStatusMock.mockResolvedValue({
      escrowHoldId: 'hold_7',
      status: 'PENDING',
      amount: 1000,
      currency: 'INR',
      campaignId: 'camp_7',
      milestoneId: null,
      fundedAt: null,
    } as never);

    vi.useFakeTimers();
    const { result, unmount } = renderHook(() => useEscrowFund());

    await act(async () => {
      await result.current.initiateFund('camp_7', undefined, 1000);
    });
    await act(async () => {
      await result.current.onPaymentComplete();
    });
    expect(result.current.status).toBe('verifying');

    // A poll timer is pending at this point.
    expect(vi.getTimerCount()).toBeGreaterThan(0);

    unmount();

    // Advance well past the poll interval — if the hook were still scheduling
    // (or React tried to setState on the unmounted instance), this would
    // either throw or log the "state update on unmounted component" warning.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });

    expect(consoleErrorSpy).not.toHaveBeenCalled();
    vi.useRealTimers();
    consoleErrorSpy.mockRestore();
  });

  // -------------------------------------------------------------------------
  // reset() clears state cleanly (used by the button on Razorpay dismiss)
  // -------------------------------------------------------------------------
  it('reset() returns to a clean idle state with no dangling refs', async () => {
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_8',
      amount: 1000,
      currency: 'INR',
      razorpayOrderId: 'order_8',
      status: 'PENDING',
    });

    const { result } = renderHook(() => useEscrowFund());

    await act(async () => {
      await result.current.initiateFund('camp_8', undefined, 1000);
    });
    expect(result.current.status).toBe('awaiting_payment');

    act(() => {
      result.current.reset();
    });

    expect(result.current.status).toBe('idle');
    expect(result.current.error).toBeNull();
    expect(result.current.razorpayOrderId).toBeNull();
    expect(result.current.serverShortfall).toBeNull();
    expect(result.current.topUpOrder).toBeNull();

    // A fresh initiateFund after reset mints a NEW idempotency key (not the
    // stale one from before reset) — assert via a second fundEscrow call.
    fundEscrowMock.mockResolvedValueOnce({
      escrowHoldId: 'hold_8b',
      amount: 1000,
      currency: 'INR',
      razorpayOrderId: 'order_8b',
      status: 'PENDING',
    });
    await act(async () => {
      await result.current.initiateFund('camp_8', undefined, 1000);
    });
    const firstKey = fundEscrowMock.mock.calls[0][1];
    const secondKey = fundEscrowMock.mock.calls[1][1];
    expect(firstKey).not.toBe(secondKey);
  });
});
