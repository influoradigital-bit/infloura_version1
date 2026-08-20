/**
 * Payments gate — preemptive block on money-moving requests
 * ----------------------------------------------------------------------------
 * The property under test is NOT "a nice error is shown". It is that **no request is
 * issued at all** when the money rails are unprovisioned.
 *
 * That distinction is the whole point of the gate. `WalletService.requestCreatorWithdrawal`
 * debits the creator's wallet through the ledger BEFORE it calls RazorpayX, so a request that
 * reaches the server and fails at the gateway has already reduced the creator's balance with no
 * transfer made — the orphaned-debit window `PayoutOrphanedDebitSweepJob` exists to sweep.
 * Catching the server error client-side would therefore be far too late. Hence: assert on
 * `fetch` never being called, not on the error being rendered.
 *
 * `api.ts` reads `import.meta.env` into module-level consts at import time, so each case stubs
 * the env, resets the module registry, and re-imports to get a freshly-evaluated module.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PaymentsUnavailableNotice } from '@/components/payments/payments-unavailable-notice';

async function loadApiWith(env: Record<string, string>) {
  vi.resetModules();
  for (const [k, v] of Object.entries(env)) vi.stubEnv(k, v);
  return import('@/lib/api');
}

describe('payments gate', () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchSpy = vi.fn(() => Promise.resolve(new Response('{}', { status: 200 })));
    vi.stubGlobal('fetch', fetchSpy);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('blocks a live withdrawal without issuing a request when payments are disabled', async () => {
    const { api, PaymentsUnavailableError, isMoneyActionBlocked } = await loadApiWith({
      VITE_API_MODE: 'live',
    });

    expect(isMoneyActionBlocked('withdraw')).toBe(true);
    expect(() => api.wallet.withdraw(1000, 'idem-key-1')).toThrow(PaymentsUnavailableError);
    // The assertion that actually matters — the debit never had a chance to post.
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('blocks escrow funding and wallet top-up the same way', async () => {
    const { api, payments, PaymentsUnavailableError } = await loadApiWith({
      VITE_API_MODE: 'live',
    });

    expect(() => payments.fundEscrow('camp_1', 'idem-key-2')).toThrow(PaymentsUnavailableError);
    expect(() => api.wallet.topUp({ amount: 5000 }, 'idem-key-3')).toThrow(PaymentsUnavailableError);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  /**
   * Regression guard. This repo has TWO client layers that both reach POST /wallet/escrow/fund —
   * `api.payments.fundEscrow` (api.ts) and `meeraApi.fundEscrow` (meera-api.ts) — and
   * `useEscrowFund`, the hook behind the real Fund Escrow control, uses the SECOND one. A guard
   * added only to api.ts looks complete and leaves the path users actually take wide open.
   */
  it('blocks the meera-api escrow route too, not just the api.ts one', async () => {
    vi.resetModules();
    vi.stubEnv('VITE_API_MODE', 'live');
    const { PaymentsUnavailableError } = await import('@/lib/api');
    const { meeraApi } = await import('@/lib/meera-api');

    await expect(meeraApi.fundEscrow('camp_1', 'idem-key-5')).rejects.toBeInstanceOf(
      PaymentsUnavailableError,
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('names the operation so the UI can say which step is waiting', async () => {
    const { api, PaymentsUnavailableError } = await loadApiWith({ VITE_API_MODE: 'live' });

    try {
      api.wallet.withdraw(1000, 'idem-key-4');
      expect.unreachable('withdraw should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(PaymentsUnavailableError);
      expect((err as InstanceType<typeof PaymentsUnavailableError>).operation).toBe('withdraw');
    }
  });

  /**
   * The reason the flag was split at all. Standard Razorpay (money in) and RazorpayX (money out)
   * are separately provisioned products, and the intended operating state is "collection live,
   * payouts recorded manually by an admin". A single combined flag could not express it: on, and
   * Withdraw is exposed against an unconfigured RazorpayX; off, and brands cannot fund anything.
   */
  it('lets collection go live while payouts stay closed — the whole point of the split', async () => {
    const { api, payments, PaymentsUnavailableError, isMoneyActionBlocked } = await loadApiWith({
      VITE_API_MODE: 'live',
      VITE_PAYMENTS_IN_ENABLED: 'true',
      // VITE_PAYOUTS_ENABLED deliberately unset — RazorpayX is not provisioned.
    });

    // Money in flows: these reach the network layer rather than throwing.
    expect(isMoneyActionBlocked('topup')).toBe(false);
    expect(isMoneyActionBlocked('escrow-fund')).toBe(false);
    expect(() => payments.fundEscrow('camp_1', 'idem-a')).not.toThrow();

    // Money out stays shut, and the request is still never issued.
    expect(isMoneyActionBlocked('withdraw')).toBe(true);
    expect(() => api.wallet.withdraw(1000, 'idem-b')).toThrow(PaymentsUnavailableError);
  });

  it('does NOT block mock mode — demo mode is the one place the flow must still run', async () => {
    const { isMoneyActionBlocked } = await loadApiWith({ VITE_API_MODE: 'mock' });
    expect(isMoneyActionBlocked('withdraw')).toBe(false);
    expect(isMoneyActionBlocked('topup')).toBe(false);
  });

  it('does NOT block when payments are explicitly enabled', async () => {
    const { isMoneyActionBlocked } = await loadApiWith({
      VITE_API_MODE: 'live',
      VITE_PAYMENTS_IN_ENABLED: 'true',
      VITE_PAYOUTS_ENABLED: 'true',
    });
    expect(isMoneyActionBlocked('topup')).toBe(false);
    expect(isMoneyActionBlocked('withdraw')).toBe(false);
  });
});

describe('PaymentsUnavailableNotice', () => {
  it('tells a creator their balance is untouched, and shows where the flow is waiting', () => {
    render(<PaymentsUnavailableNotice operation="withdraw" />);

    expect(screen.getByText('Withdrawals open shortly')).toBeInTheDocument();
    expect(screen.getByText(/nothing has been deducted/i)).toBeInTheDocument();
    // The whole lifecycle is listed, so "not switched on yet" is legible as distinct
    // from "this product cannot do it".
    expect(screen.getByText('Money is held in escrow')).toBeInTheDocument();
    expect(screen.getByText('Creator withdraws')).toBeInTheDocument();
    expect(screen.getByText('Waiting')).toBeInTheDocument();
  });

  it('marks the funding step for a brand, not the payout step', () => {
    render(<PaymentsUnavailableNotice operation="escrow-fund" />);

    expect(screen.getByText('Funding opens shortly')).toBeInTheDocument();
    // "Waiting" sits on step 1, so a brand is told the hold-up is collection, not delivery.
    const waiting = screen.getByText('Waiting').closest('p');
    expect(waiting).toHaveTextContent('Brand funds the deal');
  });
});
