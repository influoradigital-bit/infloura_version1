/**
 * Creator Dashboard — "Contracts awaiting your signature" (regression test, written ahead of
 * the feature per the audit gap below).
 *
 * BACKGROUND (do not re-derive): the backend has always had this — `GET /contracts/unsigned`
 * (`ContractController.java:58`), creator-only, delegating to
 * `ContractService.listUnsignedForCreator` (`ContractService.java:987`), covered by
 * `ContractServiceTest#testListUnsignedForCreator`. The gap is 100% frontend: `src/lib/api.ts`'s
 * `contracts` object (~L2760) has `list`/`get`/`generate`/`sign`/`pdfDownloadUrl` but no method
 * for this endpoint, and creator-dashboard.tsx (639 lines, checked directly — see the comment at
 * its old L144-151 asserting no such endpoint exists, which this task's BACKGROUND says is now
 * stale) renders no such section. This build's job is DISCOVERY, not a second signing UI: each
 * row must link back to the deal whose contract tab already signs (contracts.sign, wired into
 * each deal's contract tab), never reimplement signing here.
 *
 * `contracts.listUnsigned` (asserted below) does not exist yet on `api.ts` — this is a guessed
 * name, chosen to match this file's own `list`/`get`/`generate`/`sign` naming idiom and the
 * backend's `listUnsignedForCreator`. If the eventual implementation names it differently, this
 * test's mock target (and only that) needs a matching rename; the shape of `ContractApiRecord`
 * (`api.ts:2718` — id, collaborationId, status, totalAmount, currency, milestones,
 * brandSignedAt/creatorSignedAt) is NOT guessed, it's read directly off the real interface.
 *
 * Per this audit's own recurring defect class (empty-state-misleads, F-0067-class): "list came
 * back empty" and "the fetch failed" must render differently. Test 3 and 4 exist to pin that.
 *
 * Edit ONLY this test file — src/lib/api.ts and src/pages/creator-dashboard.tsx are out of scope
 * for this task.
 *
 * Run: npx vitest run src/pages/creator-dashboard.unsigned-contracts.test.tsx
 */

import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';

import type { ContractApiRecord } from '@/lib/api';

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

// Live mode, house pattern from brand-campaign-detail.contract-ui.test.tsx: spread the real
// module, override isApiLive() so creator-dashboard.tsx takes its live-data branch, and stub
// only the specific api.* methods this test drives.
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
        // Not a real method on api.ts yet — see file header. Whatever CreatorDashboardPage ends
        // up calling to reach GET /contracts/unsigned lands here once wired.
        listUnsigned: (...a: unknown[]) => contractsListUnsigned(...a),
      },
    },
  };
});

import CreatorDashboardPage from './creator-dashboard';

/** Renders alongside the page inside the same MemoryRouter so we can read where a click landed
 *  without presuming whether the row is a react-router `<Link>` or a `navigate()` call. */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname + location.search}</div>;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <CreatorDashboardPage />
      <LocationProbe />
    </MemoryRouter>,
  );
}

/** Walks up from a text node to the nearest clickable ancestor (whole-row-clickable, the pattern
 *  this file already uses for its quick-link cards and action-breakdown buttons), then falls back
 *  to the nearest link/button descendant within a bounded number of container levels. */
function findClickableNear(start: HTMLElement): HTMLElement {
  let cur: HTMLElement | null = start;
  for (let i = 0; cur && i < 8; i++, cur = cur.parentElement) {
    if (cur.matches('a,button')) return cur;
  }
  cur = start;
  for (let i = 0; cur && i < 8; i++, cur = cur.parentElement) {
    const found = cur.querySelector('a,button');
    if (found) return found as HTMLElement;
  }
  throw new Error('No clickable link/button found near the contract row');
}

const contractFixture: ContractApiRecord = {
  id: 'CTR_500',
  collaborationId: 'deal-77',
  status: 'PENDING_SIGNATURES',
  totalAmount: 45000,
  currency: 'INR',
  brandSignedAt: '2026-09-01T10:00:00Z', // brand already signed — this is what "awaiting the
  creatorSignedAt: null, //                creator's signature" means (architecture doc §4).
  milestones: [
    { sequenceNo: 1, description: 'On signing', amount: 20000, status: 'PENDING' },
    { sequenceNo: 2, description: 'On delivery', amount: 25000, status: 'PENDING' },
  ],
};

const EMPTY_WALLET = { availableBalance: 0, escrowLocked: 0, pendingPayouts: 0, runwayDays: null };

/** The heading text is directive copy taken verbatim from this task's BACKGROUND
 *  ("Contracts awaiting your signature"), not a guess. */
const SECTION_HEADING = /contracts awaiting your signature/i;

beforeEach(() => {
  vi.clearAllMocks();
  walletGet.mockResolvedValue(EMPTY_WALLET);
  // Empty deals list keeps loadDeliverablePendingCount's dealIds.length===0 short-circuit, so
  // creatorDeliverables.listForDeals never needs stubbing for this file's tests.
  dealsList.mockResolvedValue([]);
  portfolioAnalytics.mockResolvedValue(null);
  portfolioGetMine.mockResolvedValue(null);
});

describe('creator dashboard — contracts awaiting your signature', () => {
  it('calls the API for unsigned contracts on load', async () => {
    contractsListUnsigned.mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(contractsListUnsigned).toHaveBeenCalled());
  });

  it('renders each unsigned contract with identifying info and a link toward its deal', async () => {
    contractsListUnsigned.mockResolvedValue([contractFixture]);

    renderPage();

    // totalAmount is real data off ContractApiRecord — grouped digits are format-agnostic
    // (works whether the row renders "₹45,000" via formatINR or some other currency display).
    const amountEl = await screen.findByText(/45,000/);

    const clickable = findClickableNear(amountEl);
    await userEvent.click(clickable);

    // Must navigate toward the deal that owns this contract (collaborationId === the deal id,
    // per ContractApiRecord's own doc comment) — not just anywhere, and not stay put.
    await waitFor(() => {
      const loc = screen.getByTestId('location-probe').textContent ?? '';
      expect(loc).not.toBe('/creator/dashboard');
      expect(loc).toMatch(/deal-77/);
    });
  });

  it('shows a genuine empty state — not nothing, not an error — when there are no unsigned contracts', async () => {
    contractsListUnsigned.mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(contractsListUnsigned).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText(SECTION_HEADING)).toBeInTheDocument());

    // Not an error: this is the empty-state-misleads defect class this audit keeps finding —
    // "fetch failed" must never look like "list is empty" and vice versa.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    // Not nothing: some explanatory copy must exist, not a silently absent list. Broad net over
    // this file's own established empty-state vocabulary ("All caught up", "No deals yet",
    // "Nothing waiting on you") since exact copy for this new section is not directive.
    expect(document.body.textContent ?? '').toMatch(
      /no .*(contract|signature)|nothing.*(sign|await)|all caught up|you'?re all set|no pending/i,
    );
  });

  it('shows an error state distinct from the empty state when the fetch fails', async () => {
    contractsListUnsigned.mockRejectedValue(new Error('network down'));

    renderPage();

    await waitFor(() => expect(contractsListUnsigned).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText(SECTION_HEADING)).toBeInTheDocument());

    // Some alert must exist and it must be about contracts specifically — otherwise this can't
    // be told apart from an unrelated wallet/deals failure elsewhere on the page.
    const alerts = await screen.findAllByRole('alert');
    const contractAlert = alerts.find((a) => /contract/i.test(a.textContent ?? ''));
    expect(contractAlert).toBeTruthy();

    // And it must not read like the empty state — that collision is exactly the defect class
    // this test exists to catch.
    expect(contractAlert?.textContent ?? '').not.toMatch(
      /no .*(contract|signature)|nothing.*(sign|await)/i,
    );
  });
});

// F-0625 regression: creator-dashboard.tsx:573-574 used to read the unguarded
// `contract.milestones.length` when rendering each row's "N milestones" caption. A contract
// whose `milestones` field came back absent/undefined/null (it's optional server-side per
// ContractApiRecord's own doc — see file header) threw during render and, with no error
// boundary wrapping this tree, crashed the *entire* dashboard, not just this card. The fix
// (already in place, not touched by this file) is the `contract.milestones?.length ?? 0` guard.
//
// TS note: ContractApiRecord types `milestones` as a required `ContractMilestone[]`, so each
// case below casts through `unknown` to put a runtime shape the type doesn't admit — that's the
// point: pinning what the *server* can actually send, not what the interface promises.
describe('creator dashboard — unsigned contract missing milestones (F-0625 regression)', () => {
  function omitMilestones(record: ContractApiRecord): ContractApiRecord {
    const { milestones: _drop, ...rest } = record;
    return rest as unknown as ContractApiRecord;
  }

  const casesWithoutMilestones: Array<[string, ContractApiRecord]> = [
    ['milestones absent (key not present in the payload)', omitMilestones(contractFixture)],
    [
      'milestones undefined',
      { ...contractFixture, milestones: undefined } as unknown as ContractApiRecord,
    ],
    ['milestones null', { ...contractFixture, milestones: null } as unknown as ContractApiRecord],
  ];

  it.each(casesWithoutMilestones)(
    'renders the section without throwing when %s',
    async (_label, contractWithoutMilestones) => {
      contractsListUnsigned.mockResolvedValue([contractWithoutMilestones]);

      // If F-0625 regresses, the unguarded `.length` throws synchronously during this render —
      // with no error boundary in this tree, render() itself throws and this test fails right
      // here, before any of the assertions below run.
      renderPage();

      await waitFor(() => expect(contractsListUnsigned).toHaveBeenCalled());

      // The row survived: its real, unaffected data (amount) still renders.
      expect(await screen.findByText(/45,000/)).toBeInTheDocument();

      // The guard's fallback ("?? 0") is what actually rendered, not just "didn't throw".
      expect(screen.getByText(/0 milestones/i)).toBeInTheDocument();

      // The rest of the dashboard rendered too — this section crashing used to take the whole
      // page down with it, so prove a sibling of this card is on the page.
      expect(screen.getByText(SECTION_HEADING)).toBeInTheDocument();
      expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    },
  );
});
