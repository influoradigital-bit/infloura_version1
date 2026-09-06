/**
 * Creator Dashboard — "Pending actions" tile vs "Contracts awaiting your signature" list
 * agreement (F-0631, "two-queries-can-disagree").
 *
 * BACKGROUND (approved CEO ruling, not a discovered defect — see the task SPEC): the tile used
 * to derive its "awaiting signature" figure by filtering `GET /deals` rows for
 * `contractStatus === 'PENDING_SIGNATURES'` (the old creator-dashboard.tsx `fetchDashboardData`,
 * pre-fix). That status alone collapses "the OTHER party still needs to sign" and "this
 * creator already signed but the collaboration was CANCELLED" into the same value as "this
 * creator genuinely has a contract to sign" (see `ContractApiRecord`'s own doc comment in
 * lib/api.ts: "`status` alone collapses both into `PENDING_SIGNATURES`"). The list below the
 * tile has always used the correct, narrower definition — `GET /contracts/unsigned`
 * (`api.contracts.listUnsigned`, the F-0623 query: status=PENDING_SIGNATURES AND
 * creatorSignedAt IS NULL AND the collaboration is not CANCELLED) — so the two numbers could
 * disagree on screen at the same time.
 *
 * The ruling: the tile must count ONLY contracts awaiting the viewing creator's OWN signature —
 * the same definition the list already uses — ideally by deriving the tile from the exact same
 * data the list renders rather than maintaining a second definition that can drift again.
 *
 * This test drives THREE deals through `GET /deals` (`api.deals.list`), each carrying
 * `contractStatus: 'PENDING_SIGNATURES'` (the field the OLD buggy code filtered on) — two of
 * them not actually the creator's to-do (one already signed by this creator, one on a
 * cancelled collaboration) and one genuinely awaiting them — while `GET /contracts/unsigned`
 * (`api.contracts.listUnsigned`, mocked separately) returns only the one real record, matching
 * the backend's real F-0623 filter. Against the pre-fix code this test fails: the tile would
 * read "3" (dealRows.filter(contractStatus === 'PENDING_SIGNATURES').length) while the list
 * below renders exactly 1 row — visibly disagreeing, and not equal to "1".
 *
 * Each deal's `status` (CollaborationStatus, e.g. 'TERMS_AGREED'/'CANCELLED') is deliberately
 * picked from the DealStage buckets `isActiveDeal` (creator-dashboard.tsx) does NOT treat as
 * active ('new'/'negotiating'/'completed'/'disputed' — see lib/deal-stage.ts), so
 * `loadDeliverablePendingCount`'s `activeIds` stays empty and this file never needs to stub
 * `api.creatorDeliverables.listForDeals` (already covered by the CR-51 test in
 * creator-dashboard.test.tsx) — this test is isolated to the signature-count defect only.
 * `unreadCount: 0` on every fixture keeps `pending.unreadMessages` at 0 for the same reason.
 *
 * Edit ONLY this test file — src/pages/creator-dashboard.tsx is the production file for this
 * task and is edited separately, not by this file.
 *
 * Run: npx vitest run src/pages/creator-dashboard.pending-signature-tile.test.tsx
 */

import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import type { ContractApiRecord, Deal } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/components/motion', () => ({
  FadeUp: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  StaggerContainer: ({
    children,
    className,
  }: {
    children: React.ReactNode;
    className?: string;
  }) => <div className={className}>{children}</div>,
  StaggerItem: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { displayName: 'Priya Sharma', firstName: 'Priya', role: 'creator' },
    logout: vi.fn(),
  }),
}));

const walletGet = vi.fn();
const dealsList = vi.fn();
const portfolioAnalytics = vi.fn();
const portfolioGetMine = vi.fn();
const contractsListUnsigned = vi.fn();

// Live mode, house pattern from creator-dashboard.unsigned-contracts.test.tsx: spread the real
// module, override isApiLive() so creator-dashboard.tsx takes its live-data branch (the branch
// the F-0631 bug actually lived in — the mock branch always hardcoded 0), and stub only the
// specific api.* methods this test drives.
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      wallet: { ...actual.api.wallet, get: (...a: unknown[]) => walletGet(...a) },
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsList(...a) },
      portfolio: {
        ...actual.api.portfolio,
        analytics: (...a: unknown[]) => portfolioAnalytics(...a),
        getMine: (...a: unknown[]) => portfolioGetMine(...a),
      },
      contracts: {
        ...actual.api.contracts,
        listUnsigned: (...a: unknown[]) => contractsListUnsigned(...a),
      },
    },
  };
});

import CreatorDashboardPage from './creator-dashboard';

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <CreatorDashboardPage />
    </MemoryRouter>,
  );
}

/** Finds the nearest ancestor Card by its CardTitle text, via the shadcn `data-slot="card"`
 *  marker (components/ui/card.tsx) rather than a guessed className — stable across styling. */
function getCardByTitle(titleMatcher: string | RegExp): HTMLElement {
  const titleEl = screen.getByText(titleMatcher);
  const card = titleEl.closest('[data-slot="card"]');
  if (!card) throw new Error(`No ancestor [data-slot="card"] found for title "${titleMatcher}"`);
  return card as HTMLElement;
}

const EMPTY_WALLET = { availableBalance: 0, escrowLocked: 0, pendingPayouts: 0, runwayDays: null };

// Deal A: contractStatus reads PENDING_SIGNATURES at the GET /deals level, but this creator has
// ALREADY signed — `GET /deals` cannot express that (ContractApiRecord doc: "status alone
// collapses both into PENDING_SIGNATURES"), which is exactly why the old tile over-counted it.
const dealAlreadySigned: Deal = {
  id: 'deal-signed-1',
  campaignId: 'camp-1',
  campaignName: 'Diwali Launch',
  counterpartyId: 'brand-1',
  counterpartyName: 'Zenith Foods',
  status: 'TERMS_AGREED',
  dealValue: 45000,
  currency: 'INR',
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 0,
  contractStatus: 'PENDING_SIGNATURES',
  escrowFunded: false,
};

// Deal B: contract on a CANCELLED collaboration — nothing left for the creator to act on, but
// still reads PENDING_SIGNATURES at the GET /deals level.
const dealCancelledCollab: Deal = {
  id: 'deal-cancelled-1',
  campaignId: 'camp-2',
  campaignName: 'Holi Collab',
  counterpartyId: 'brand-2',
  counterpartyName: 'Northwind Apparel',
  status: 'CANCELLED',
  dealValue: 30000,
  currency: 'INR',
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 0,
  contractStatus: 'PENDING_SIGNATURES',
  escrowFunded: false,
};

// Deal C: the ONE genuinely awaiting this creator's own signature.
const dealGenuinelyAwaiting: Deal = {
  id: 'deal-77',
  campaignId: 'camp-3',
  campaignName: 'Summer Drop',
  counterpartyId: 'brand-3',
  counterpartyName: 'Aarohi Wellness',
  status: 'TERMS_AGREED',
  dealValue: 50000,
  currency: 'INR',
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 0,
  contractStatus: 'PENDING_SIGNATURES',
  escrowFunded: false,
};

// The real backend answer (F-0623 query) — only the genuinely-awaiting deal's contract.
const theOneRealUnsignedContract: ContractApiRecord = {
  id: 'CTR_900',
  collaborationId: 'deal-77',
  status: 'PENDING_SIGNATURES',
  totalAmount: 50000,
  currency: 'INR',
  brandSignedAt: '2026-09-01T10:00:00Z',
  creatorSignedAt: null,
  milestones: [{ sequenceNo: 1, description: 'On signing', amount: 50000, status: 'PENDING' }],
};

beforeEach(() => {
  vi.clearAllMocks();
  walletGet.mockResolvedValue(EMPTY_WALLET);
  portfolioAnalytics.mockResolvedValue(null);
  portfolioGetMine.mockResolvedValue(null);
});

describe('creator dashboard — pending-actions tile agrees with the unsigned-contracts list (F-0631)', () => {
  it('the tile counts ONLY the contract genuinely awaiting the creator\'s own signature — not the already-signed one, not the cancelled one — and matches the list rendered below it', async () => {
    dealsList.mockResolvedValue([dealAlreadySigned, dealCancelledCollab, dealGenuinelyAwaiting]);
    contractsListUnsigned.mockResolvedValue([theOneRealUnsignedContract]);

    renderPage();

    await waitFor(() => expect(dealsList).toHaveBeenCalled());
    await waitFor(() => expect(contractsListUnsigned).toHaveBeenCalled());

    // The list itself renders exactly the one genuinely-awaiting row (a real Link, since
    // collaborationId is present) — establishes what "the list" says before comparing the tile
    // to it.
    const listCard = getCardByTitle(/contracts awaiting your signature/i);
    await waitFor(() => {
      expect(within(listCard).getAllByRole('link')).toHaveLength(1);
    });

    // The "Pending actions" tile must read exactly 1 — not 2, not 3 (the old
    // `dealRows.filter(contractStatus === 'PENDING_SIGNATURES').length` count, which this
    // fixture set deliberately contains three of).
    const pendingCard = getCardByTitle('Pending actions');
    await waitFor(() => {
      expect(within(pendingCard).getByText('1')).toBeInTheDocument();
    });
    expect(within(pendingCard).queryByText('2')).not.toBeInTheDocument();
    expect(within(pendingCard).queryByText('3')).not.toBeInTheDocument();

    // Same story in the "Action breakdown" panel's own "Awaiting signature" figure — it must
    // read the identical number, not a third, independently-drifting count.
    const awaitingSignatureLabel = screen.getByText('Awaiting signature');
    const breakdownRow = awaitingSignatureLabel.closest('button');
    expect(breakdownRow).not.toBeNull();
    expect(within(breakdownRow as HTMLElement).getByText('1')).toBeInTheDocument();

    // And the header's "N items need your attention" copy agrees too — one unified count, not
    // three separate places that can each say something different.
    await waitFor(() => {
      expect(screen.getByText(/1 item need your attention/i)).toBeInTheDocument();
    });
  });
});
