/**
 * creator-wallet — Payouts tab reads the real payout history (CR-77).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The Payouts tab used to derive itself by filtering wallet transactions to `WITHDRAWAL` debits.
 * A ledger debit records that money left the creator's Influora wallet; it does NOT record that it
 * reached their bank. When RazorpayX reverses/rejects/cancels a payout, the backend posts a
 * SEPARATE compensating credit and correctly leaves the original debit standing — so a bounced
 * payout kept rendering here as money paid out, and the offsetting credit was invisible to this
 * tab because a credit is not a WITHDRAWAL. The tab now reads `GET /wallet/payouts`.
 *
 * This file exists because the absence of it is exactly how two real bugs shipped past review:
 * a failed payout rendered "· Settled <date>" underneath "Payout failed", and the status badge
 * was colour-INVERTED (the shared substring-matching helper painted 'reversed' green as a success
 * and 'processed' amber as in-progress). Both are asserted below.
 *
 * Run: npx vitest run src/pages/creator-wallet-payouts.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorWalletPage from './creator-wallet';

// A STABLE toast identity, matching production: the real useToast returns the module-level
// `toast` function (src/hooks/use-toast.ts), not a fresh closure per render. Returning a new
// vi.fn() each call would change `loadPayouts`'s useCallback identity every render and spin the
// fetch effect — an artifact of the mock, not of the page.
// vi.hoisted because vi.mock factories are hoisted above module-scope consts (TDZ otherwise).
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }), toast: toastSpy }));
vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/hooks/creator/useServiceInvoices', () => ({
  useServiceInvoices: () => ({ invoices: [], loading: false, error: null, refetch: vi.fn() }),
}));

const payouts = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    creatorInvoicing: { listCommissionInvoices: vi.fn().mockResolvedValue([]) },
    api: {
      ...actual.api,
      wallet: {
        get: vi.fn().mockResolvedValue({ availableBalance: 0, escrowLocked: 0, pendingPayouts: 0 }),
        transactions: vi.fn().mockResolvedValue([]),
        payouts: (...a: unknown[]) => payouts(...a),
        getPayoutMethods: vi.fn().mockResolvedValue([]),
      },
    },
  };
});

function payoutRow(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'p1',
    reference: 'pout_ABC123',
    amount: 2500,
    currency: 'INR',
    status: 'processed',
    failed: false,
    requestedAt: '2026-08-01T10:00:00Z',
    settledAt: '2026-08-02T10:00:00Z',
    ...over,
  };
}

async function renderPayoutsTab() {
  render(
    <MemoryRouter>
      <CreatorWalletPage />
    </MemoryRouter>,
  );
  // The Payouts tab is the default tab on this page, so its content renders without a click.
  await waitFor(() => expect(payouts).toHaveBeenCalled());
}

describe('creator-wallet Payouts tab — real payout history (CR-77)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('reads GET /wallet/payouts rather than deriving from wallet transactions', async () => {
    payouts.mockResolvedValue([payoutRow()]);
    await renderPayoutsTab();

    expect(payouts).toHaveBeenCalled();
    expect(await screen.findByText(/Ref: pout_ABC123/)).toBeInTheDocument();
  });

  it('a FAILED payout says so, and never claims a settlement date', async () => {
    // The exact regression: the backend stamps confirmedAt on every terminal webhook — including
    // reversals — so a naive pass-through printed "· Settled 2 Aug" directly beneath
    // "Payout failed". Money that never landed has no landing date.
    payouts.mockResolvedValue([
      payoutRow({ status: 'reversed', failed: true, settledAt: '2026-08-02T10:00:00Z' }),
    ]);
    await renderPayoutsTab();

    expect(await screen.findByText(/Payout failed/)).toBeInTheDocument();
    expect(screen.queryByText(/Settled/)).toBeNull();
  });

  it('the status badge is NOT colour-inverted: failed reads Failed, settled reads Paid', async () => {
    // The shared statusBadgeClass helper substring-matches, and RazorpayX's vocabulary inverts
    // under it — 'reversed' matched none of its FAIL/PROCESS/PEND patterns and fell through to the
    // GREEN success style, while 'processed' (terminal SUCCESS) matched 'PROCESS' and rendered
    // amber in-progress. The badge is driven off the server-computed `failed` flag instead.
    payouts.mockResolvedValue([
      payoutRow({ id: 'p1', reference: 'pout_FAILED', status: 'reversed', failed: true, settledAt: null }),
      payoutRow({ id: 'p2', reference: 'pout_OK', status: 'processed', failed: false }),
    ]);
    await renderPayoutsTab();

    const failedCard = (await screen.findByText(/Ref: pout_FAILED/)).closest('.p-4') as HTMLElement;
    const okCard = (await screen.findByText(/Ref: pout_OK/)).closest('.p-4') as HTMLElement;

    const failedBadge = within(failedCard).getByText('Failed');
    const okBadge = within(okCard).getByText('Paid');
    expect(within(failedCard).queryByText('Paid')).toBeNull();
    expect(within(okCard).queryByText('Failed')).toBeNull();

    // Assert the actual CLASS, not just the label. A label-only assertion passes even when the
    // badge is reverted to the substring-matching statusBadgeClass helper — which is exactly the
    // inversion this test is named for, and exactly how a bug of this class ships. The failure
    // badge must carry destructive tone and must NOT carry the success tone, and vice versa.
    // Match the BACKGROUND tokens specifically — the shadcn Badge base class already carries a
    // destructive-tinted focus ring, so a bare /destructive/ would match on every badge.
    expect(failedBadge.className).toMatch(/bg-destructive/);
    expect(failedBadge.className).not.toMatch(/bg-stage-approved/);
    expect(okBadge.className).toMatch(/bg-stage-approved/);
    expect(okBadge.className).not.toMatch(/bg-destructive/);
  });

  it('an in-progress payout uses a real, defined design token, not a mistyped class', async () => {
    // A class Tailwind emits no CSS for is invisible to tsc, eslint and every label assertion —
    // it just silently renders an unstyled badge. `stage-negotiating` is the token that exists in
    // globals.css; `stage-negotiation` (no -ing) was a real typo that shipped through review.
    payouts.mockResolvedValue([
      payoutRow({ reference: 'pout_INFLIGHT', status: 'queued', failed: false, settledAt: null }),
    ]);
    await renderPayoutsTab();

    const badge = await screen.findByText('In progress');
    expect(badge.className).toMatch(/stage-negotiating/);
    expect(badge.className).not.toMatch(/stage-negotiation[^i]/);
  });

  it('states the missing breakdown in words rather than shipping placeholder columns', async () => {
    payouts.mockResolvedValue([payoutRow()]);
    await renderPayoutsTab();

    expect(await screen.findByText(/detailed breakdown/i)).toBeInTheDocument();
  });

  it('an error clears the list rather than leaving stale rows on a money screen', async () => {
    payouts.mockRejectedValue(new Error('boom'));
    await renderPayoutsTab();

    expect(await screen.findByText(/Couldn’t load your payouts/)).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(payouts).toHaveBeenCalledTimes(2));
  });
});
