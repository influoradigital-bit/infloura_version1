/**
 * F-0489 (unreachable-endpoint) — brand-wallet.tsx UI regression spec.
 *
 * SYMPTOM: a Meera-funded campaign-level escrow hold has no `milestoneId` by construction (see
 * `deal-payments-tab.tsx`'s "Funding WITHOUT a milestoneId" comment). Before this fix, nothing on
 * `/brand/wallet` — the one screen that renders `GET /wallet/escrow` rows — offered any way to
 * release such a hold, so a FUNDED milestone-less hold had no UI path out at all. The wire-shape
 * half of this fix (`api.payments.releasePayout` sending `{ escrowHoldId }`) is covered separately
 * in `src/lib/__tests__/release-payout-xor.f0489.test.ts` — that file deliberately avoids
 * `vi.mock('@/lib/api', ...)` so the real implementation runs; this file mocks it, mirroring
 * `src/pages/__tests__/brand-wallet.load-states.test.tsx`'s harness.
 *
 * Proves: a FUNDED hold with no `milestoneId` renders a real "Release" button; a FUNDED hold that
 * DOES carry a `milestoneId` (deal-room territory, released from `deal-payments-tab.tsx` instead)
 * does not, so there is exactly one release control per hold, not two; and clicking the button
 * calls `releasePayout({ escrowHoldId })` for that row and refreshes.
 *
 * Run: npx vitest run src/pages/__tests__/brand-wallet.release-hold.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandWalletPage from '../brand-wallet';
import { ApiError } from '@/lib/api';

vi.mock('@/components/feature/meera/FundEscrowButton', () => ({
  FundEscrowButton: () => <div data-testid="fund-escrow-button-stub" />,
}));

const apiLive = vi.fn();
const walletGetMock = vi.fn();
const walletTransactionsMock = vi.fn();
const escrowListMock = vi.fn();
const campaignsListMock = vi.fn();
const releasePayoutMock = vi.fn();

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    isApiLive: () => apiLive(),
    api: {
      ...actual.api,
      wallet: {
        ...actual.api.wallet,
        get: (...a: unknown[]) => walletGetMock(...a),
        transactions: (...a: unknown[]) => walletTransactionsMock(...a),
        escrowList: (...a: unknown[]) => escrowListMock(...a),
      },
      campaigns: {
        ...actual.api.campaigns,
        list: (...a: unknown[]) => campaignsListMock(...a),
      },
      payments: {
        ...actual.api.payments,
        releasePayout: (...a: unknown[]) => releasePayoutMock(...a),
      },
    },
  };
});

function renderWallet() {
  return render(
    <MemoryRouter>
      <BrandWalletPage />
    </MemoryRouter>,
  );
}

const FUNDED_NO_MILESTONE = {
  escrowHoldId: 'esc_pool_1',
  status: 'FUNDED' as const,
  amount: 25000,
  currency: 'INR',
  campaignId: 'camp_1',
  milestoneId: null,
  fundedAt: '2026-09-01T00:00:00.000Z',
};

const FUNDED_WITH_MILESTONE = {
  escrowHoldId: 'esc_deal_1',
  status: 'FUNDED' as const,
  amount: 15000,
  currency: 'INR',
  campaignId: 'camp_2',
  milestoneId: 'ms_9',
  fundedAt: '2026-09-01T00:00:00.000Z',
};

describe('BrandWalletPage — F-0489 release control for a milestone-less FUNDED hold', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiLive.mockReturnValue(true);
    walletGetMock.mockResolvedValue({
      availableBalance: 10000,
      escrowLocked: 40000,
      pendingPayouts: 0,
      runwayDays: null,
    });
    walletTransactionsMock.mockResolvedValue([]);
    campaignsListMock.mockResolvedValue({ campaigns: [] });
  });

  it('renders a Release button for a FUNDED hold with no milestoneId', async () => {
    escrowListMock.mockResolvedValue([FUNDED_NO_MILESTONE]);
    const user = userEvent.setup();
    renderWallet();

    await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /secure payments/i }));

    const releaseButton = await screen.findByRole('button', { name: /^release$/i });
    expect(releaseButton).toBeInTheDocument();
  });

  it('does NOT render a Release button for a FUNDED hold that carries a milestoneId (deal-room territory)', async () => {
    escrowListMock.mockResolvedValue([FUNDED_WITH_MILESTONE]);
    const user = userEvent.setup();
    renderWallet();

    await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /secure payments/i }));

    // The hold itself still renders (proves the assertion below isn't vacuously true).
    await screen.findByText('Campaign camp_2');
    expect(screen.queryByRole('button', { name: /^release$/i })).not.toBeInTheDocument();
  });

  it('clicking Release calls releasePayout with { escrowHoldId } for that row, then refreshes', async () => {
    escrowListMock.mockResolvedValueOnce([FUNDED_NO_MILESTONE]);
    releasePayoutMock.mockResolvedValueOnce({ escrowHoldId: 'esc_pool_1', status: 'RELEASED' });
    // Refresh after release — the hold is gone, balance moved.
    escrowListMock.mockResolvedValueOnce([]);
    walletGetMock.mockResolvedValueOnce({
      availableBalance: 10000,
      escrowLocked: 40000,
      pendingPayouts: 0,
      runwayDays: null,
    });
    walletGetMock.mockResolvedValueOnce({
      availableBalance: 10000,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: null,
    });

    const user = userEvent.setup();
    renderWallet();

    await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /secure payments/i }));

    const releaseButton = await screen.findByRole('button', { name: /^release$/i });
    await user.click(releaseButton);

    await waitFor(() =>
      expect(releasePayoutMock).toHaveBeenCalledWith({ escrowHoldId: 'esc_pool_1' }),
    );
    // Never the milestone-shaped call — the whole point of the fix.
    expect(releasePayoutMock).not.toHaveBeenCalledWith('esc_pool_1');
    // Re-fetches so the row/balance reflect the release.
    await waitFor(() => expect(escrowListMock).toHaveBeenCalledTimes(2));
  });

  it('surfaces a server refusal without crashing and re-enables the button', async () => {
    escrowListMock.mockResolvedValue([FUNDED_NO_MILESTONE]);
    releasePayoutMock.mockRejectedValueOnce(
      new ApiError('RELEASE_CONDITION_NOT_MET', 'This hold is frozen pending a dispute', 409),
    );

    const user = userEvent.setup();
    renderWallet();

    await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /secure payments/i }));

    const releaseButton = await screen.findByRole('button', { name: /^release$/i });
    await user.click(releaseButton);

    await waitFor(() => expect(releasePayoutMock).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^release$/i })).not.toBeDisabled(),
    );
  });
});
