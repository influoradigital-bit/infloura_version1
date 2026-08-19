/**
 * Brand Wallet — F-0324 (wallet-has-no-loading-affordance) regression spec.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * BrandWalletPage fetched its summary, transactions, escrow holdings and fundable campaigns on
 * mount with NO loading affordance at all — a slow or failing fetch was indistinguishable from a
 * genuinely empty wallet. The file used to hold a `loading` boolean written on both edges of
 * `loadWallet` and read by nothing (removed while fixing an eslint violation; its absence was the
 * actual F-0324 finding). The shape this spec exists to catch: a status variable that is SET but
 * changes nothing on screen.
 *
 * Three regions, each independently pending / rejected / resolved-empty:
 *   1. loadWallet   → Balance Cards row, Transactions tab, Payouts tab
 *   2. loadEscrow    → Active Escrow Holdings card (Escrow tab)
 *   3. loadFundableCampaigns → Fund Campaign Escrow card (Escrow tab)
 *
 * Mirrors the harness shape of src/components/brand/dashboard/__tests__/dashboard-page.test.tsx
 * (F-0245) and src/pages/__tests__/creator-wallet.money-buckets.test.tsx (F-0281): `isApiLive` as
 * a controllable mock fn, `api.*` as fine-grained vi.fn()s, real render via RTL, retries driven
 * with `@testing-library/user-event` (synthetic `.click()` gives false results on Radix here).
 *
 * FundEscrowButton pulls in a real Razorpay Checkout + useEscrowFund polling machinery that is
 * out of scope for this spec — stubbed out, same as dashboard-page.test.tsx stubs
 * TrendSparkNudgeCard.
 *
 * Run: npx vitest run src/pages/__tests__/brand-wallet.load-states.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandWalletPage from '../brand-wallet';
// F-0324: the page surfaces `err.message` ONLY for an ApiError and a generic
// "Could not load wallet data." for anything else — deliberate, so a raw exception
// string never lands on a money screen. These specs originally rejected with a plain
// Error and asserted the raw text appeared, which asserted a behaviour the product
// does not have (and should not). Rejecting with a real ApiError exercises the path
// the api layer actually produces and pins the stronger property: a SERVER-supplied
// message reaches the user.
import { ApiError } from '@/lib/api';

vi.mock('@/components/feature/meera/FundEscrowButton', () => ({
  FundEscrowButton: () => <div data-testid="fund-escrow-button-stub" />,
}));

const apiLive = vi.fn();
const walletGetMock = vi.fn();
const walletTransactionsMock = vi.fn();
const escrowListMock = vi.fn();
const campaignsListMock = vi.fn();

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

// A promise nothing ever resolves — keeps a region parked in 'loading'.
const never = () => new Promise(() => {});

describe('BrandWalletPage — F-0324 loading/error/empty are three distinct states per region', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiLive.mockReturnValue(true);
  });

  describe('region 1 — loadWallet (Balance Cards, Transactions tab, Payouts tab)', () => {
    it('pending fetch renders a loading affordance, never a fabricated ₹0', async () => {
      walletGetMock.mockReturnValue(never());
      walletTransactionsMock.mockReturnValue(never());
      escrowListMock.mockReturnValue(never());
      campaignsListMock.mockReturnValue(never());

      renderWallet();

      expect(await screen.findByRole('status', { name: 'Loading wallet balances' })).toBeInTheDocument();
      // The real F-0324 defect shape: a loading state that exists but changes nothing on
      // screen. This must NOT be reachable — no real currency figure renders while pending.
      expect(screen.queryByText('₹0')).not.toBeInTheDocument();
      expect(screen.queryByText('No recharge yet')).not.toBeInTheDocument();
    });

    it('rejected fetch renders a visible error with a working retry, never the empty copy', async () => {
      walletGetMock.mockRejectedValue(new ApiError('WALLET_UNAVAILABLE', 'wallet down', 503));
      walletTransactionsMock.mockRejectedValue(new ApiError('WALLET_UNAVAILABLE', 'wallet down', 503));
      escrowListMock.mockReturnValue(never());
      campaignsListMock.mockReturnValue(never());

      const user = userEvent.setup();
      renderWallet();

      const walletErrors = await screen.findAllByText('wallet down');
      expect(walletErrors.length).toBeGreaterThan(0);
      expect(screen.queryByRole('status', { name: 'Loading wallet balances' })).not.toBeInTheDocument();
      expect(screen.queryByText('No recharge yet')).not.toBeInTheDocument();

      // Retry actually re-invokes the fetch, not a decorative button — drive with userEvent,
      // synthetic .click() gives false positives on this codebase's Radix-backed controls.
      const callsBefore = walletGetMock.mock.calls.length;
      walletGetMock.mockResolvedValueOnce({
        availableBalance: 50000,
        escrowLocked: 0,
        pendingPayouts: 0,
        runwayDays: null,
      });
      walletTransactionsMock.mockResolvedValueOnce([]);
      const retryButtons = screen.getAllByRole('button', { name: 'Retry' });
      await user.click(retryButtons[0]);

      await waitFor(() => expect(walletGetMock.mock.calls.length).toBeGreaterThan(callsBefore));
      await waitFor(() => expect(screen.getByText('₹50,000')).toBeInTheDocument());
    });

    it('resolved-empty renders a genuine ₹0, distinct from loading/error', async () => {
      walletGetMock.mockResolvedValue({
        availableBalance: 0,
        escrowLocked: 0,
        pendingPayouts: 0,
        runwayDays: null,
      });
      walletTransactionsMock.mockResolvedValue([]);
      escrowListMock.mockResolvedValue([]);
      campaignsListMock.mockResolvedValue({ campaigns: [] });

      renderWallet();

      // Several balance cards legitimately read ₹0 at once on an empty wallet, so this is
      // getAllByText by necessity, not laxity — the distinctness this spec exists to prove
      // is carried by the two absence assertions below.
      await waitFor(() => expect(screen.getAllByText('₹0').length).toBeGreaterThan(0));
      expect(screen.queryByRole('status', { name: 'Loading wallet balances' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByText('No transactions to show.')).toBeInTheDocument();
    });
  });

  describe('region 2 — loadEscrow (Active Escrow Holdings)', () => {
    beforeEach(async () => {
      // Region 1 resolves cleanly so it doesn't obscure region 2's own states.
      walletGetMock.mockResolvedValue({
        availableBalance: 10000,
        escrowLocked: 0,
        pendingPayouts: 0,
        runwayDays: null,
      });
      walletTransactionsMock.mockResolvedValue([]);
      campaignsListMock.mockResolvedValue({ campaigns: [] });
    });

    it('pending escrow fetch renders a loading affordance under the Escrow tab', async () => {
      escrowListMock.mockReturnValue(never());
      const user = userEvent.setup();
      renderWallet();

      await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
      await user.click(screen.getByRole('tab', { name: /escrow/i }));

      expect(await screen.findByRole('status', { name: 'Loading escrow holdings' })).toBeInTheDocument();
      expect(screen.queryByText('No escrow holdings yet. Funds locked for a campaign will show up here.')).not.toBeInTheDocument();
    });

    it('rejected escrow fetch renders an error with a working retry', async () => {
      escrowListMock.mockRejectedValue(new ApiError('ESCROW_UNAVAILABLE', 'escrow down', 503));
      const user = userEvent.setup();
      renderWallet();

      await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
      await user.click(screen.getByRole('tab', { name: /escrow/i }));

      const escrowErrors = await screen.findAllByText('escrow down');
      expect(escrowErrors.length).toBeGreaterThan(0);
      expect(
        screen.queryByText('No escrow holdings yet. Funds locked for a campaign will show up here.'),
      ).not.toBeInTheDocument();

      const callsBefore = escrowListMock.mock.calls.length;
      escrowListMock.mockResolvedValueOnce([]);
      const retryButtons = screen.getAllByRole('button', { name: 'Retry' });
      await user.click(retryButtons[retryButtons.length - 1]);

      await waitFor(() => expect(escrowListMock.mock.calls.length).toBeGreaterThan(callsBefore));
      await waitFor(() =>
        expect(
          screen.getByText('No escrow holdings yet. Funds locked for a campaign will show up here.'),
        ).toBeInTheDocument(),
      );
    });

    it('resolved-empty renders the genuine empty state, not the loading/error copy', async () => {
      escrowListMock.mockResolvedValue([]);
      const user = userEvent.setup();
      renderWallet();

      await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
      await user.click(screen.getByRole('tab', { name: /escrow/i }));

      await waitFor(() =>
        expect(
          screen.getByText('No escrow holdings yet. Funds locked for a campaign will show up here.'),
        ).toBeInTheDocument(),
      );
      expect(screen.queryByRole('status', { name: 'Loading escrow holdings' })).not.toBeInTheDocument();
    });
  });

  describe('region 3 — loadFundableCampaigns (Fund Campaign Escrow card)', () => {
    beforeEach(() => {
      walletGetMock.mockResolvedValue({
        availableBalance: 10000,
        escrowLocked: 0,
        pendingPayouts: 0,
        runwayDays: null,
      });
      walletTransactionsMock.mockResolvedValue([]);
      escrowListMock.mockResolvedValue([]);
    });

    it('pending campaigns fetch renders a loading affordance, never the empty copy', async () => {
      campaignsListMock.mockReturnValue(never());
      const user = userEvent.setup();
      renderWallet();

      await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
      await user.click(screen.getByRole('tab', { name: /escrow/i }));

      expect(await screen.findByRole('status', { name: 'Loading your campaigns' })).toBeInTheDocument();
      expect(screen.queryAllByText('No active campaigns to fund right now.')).toHaveLength(0);
    });

    it('rejected campaigns fetch renders an error with a working retry, not the silent empty copy', async () => {
      campaignsListMock.mockRejectedValue(new ApiError('CAMPAIGNS_UNAVAILABLE', 'campaigns down', 503));
      const user = userEvent.setup();
      renderWallet();

      await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
      await user.click(screen.getByRole('tab', { name: /escrow/i }));

      const campaignErrors = await screen.findAllByText('campaigns down');
      expect(campaignErrors.length).toBeGreaterThan(0);
      expect(screen.queryAllByText('No active campaigns to fund right now.')).toHaveLength(0);

      const callsBefore = campaignsListMock.mock.calls.length;
      campaignsListMock.mockResolvedValueOnce({ campaigns: [] });
      const retryButtons = screen.getAllByRole('button', { name: 'Retry' });
      await user.click(retryButtons[0]);

      await waitFor(() => expect(campaignsListMock.mock.calls.length).toBeGreaterThan(callsBefore));
      await waitFor(() =>
        expect(screen.getAllByText('No active campaigns to fund right now.').length).toBeGreaterThan(0),
      );
    });

    it('resolved-empty renders the genuine empty state', async () => {
      campaignsListMock.mockResolvedValue({ campaigns: [] });
      const user = userEvent.setup();
      renderWallet();

      await waitFor(() => expect(screen.getByText('₹10,000')).toBeInTheDocument());
      await user.click(screen.getByRole('tab', { name: /escrow/i }));

      await waitFor(() =>
        expect(screen.getAllByText('No active campaigns to fund right now.').length).toBeGreaterThan(0),
      );
      expect(screen.queryByRole('status', { name: 'Loading your campaigns' })).not.toBeInTheDocument();
    });
  });
});
