/**
 * Creator Dashboard — "Contracts awaiting your signature" row identity + link safety.
 *
 * Two findings, both scoped to creator-dashboard.tsx only:
 *
 * F-0638 (unguarded-property-access): a null/missing `collaborationId` used to interpolate
 * unguarded into the row's href (`/creator/chat?deal=${contract.collaborationId}&tab=contract`),
 * producing a literal "...deal=undefined..." / "...deal=null..." link instead of a disabled/
 * omitted row.
 *
 * F-0637 (indistinguishable-list-rows): unsigned-contract rows used to show only amount,
 * milestone count and signed caption — no brand or campaign name — so two contracts of the same
 * amount were visually identical. The backend fix landed this same wave added two new fields to
 * `ContractResponse` (see MoneyDtos.java / ContractService.java): `campaignTitle` (String,
 * nullable) and `brandWorkspaceName` (String, nullable), both best-effort — null, never thrown,
 * when unresolvable. `ContractApiRecord` in lib/api.ts has not been regenerated to declare these
 * yet (out of this file's edit boundary), so the fixtures below attach them the same way real
 * JSON off the wire would — present at runtime, not in the declared TS shape — via a cast through
 * `unknown`, matching this directory's own established pattern for testing fields the interface
 * doesn't (yet) admit (see the F-0625 regression block in
 * creator-dashboard.unsigned-contracts.test.tsx).
 *
 * Scaffolding (mocks, fixture base, renderPage/findClickableNear helpers) is intentionally
 * duplicated from creator-dashboard.unsigned-contracts.test.tsx rather than shared, to keep this
 * new test file self-contained and avoid touching that file under the hard file boundary for this
 * task (creator-dashboard.tsx + new/updated test files only).
 *
 * Run: node_modules/.bin/vitest run src/pages/creator-dashboard.contract-identity.test.tsx
 */

import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

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

// Live mode, house pattern from creator-dashboard.unsigned-contracts.test.tsx: spread the real
// module, override isApiLive() so creator-dashboard.tsx takes its live-data branch, and stub only
// the specific api.* methods this test drives.
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

const baseContract: ContractApiRecord = {
  id: 'CTR_500',
  collaborationId: 'deal-77',
  status: 'PENDING_SIGNATURES',
  totalAmount: 45000,
  currency: 'INR',
  brandSignedAt: '2026-09-01T10:00:00Z',
  creatorSignedAt: null,
  milestones: [{ sequenceNo: 1, description: 'On signing', amount: 45000, status: 'PENDING' }],
};

const EMPTY_WALLET = { availableBalance: 0, escrowLocked: 0, pendingPayouts: 0, runwayDays: null };

beforeEach(() => {
  vi.clearAllMocks();
  walletGet.mockResolvedValue(EMPTY_WALLET);
  // Empty deals list keeps loadDeliverablePendingCount's dealIds.length===0 short-circuit, so
  // creatorDeliverables.listForDeals never needs stubbing for this file's tests.
  dealsList.mockResolvedValue([]);
  portfolioAnalytics.mockResolvedValue(null);
  portfolioGetMine.mockResolvedValue(null);
});

describe('creator dashboard — unsigned contract row: null collaborationId link guard (F-0638)', () => {
  it('never produces a link containing the literal string "undefined" or "null" when collaborationId is missing', async () => {
    // Cast through unknown: the real defect is the backend/DB sending a value the declared
    // ContractApiRecord type (collaborationId: string) doesn't admit — same pattern the
    // neighbouring F-0625 regression block uses for "the interface says required, the wire says
    // otherwise".
    const contractWithNullCollab = {
      ...baseContract,
      id: 'CTR_999',
      collaborationId: null,
    } as unknown as ContractApiRecord;
    contractsListUnsigned.mockResolvedValue([contractWithNullCollab]);

    renderPage();

    const amountEl = await screen.findByText(/45,000/);

    // No anchor anywhere on the page may point at a literal "...deal=undefined..." or
    // "...deal=null..." href.
    const anchors = Array.from(document.querySelectorAll('a'));
    const brokenLink = anchors.find((a) =>
      /deal=(undefined|null)(&|$)/.test(a.getAttribute('href') ?? ''),
    );
    expect(brokenLink).toBeUndefined();

    // Stronger: this specific row must not be wrapped in a clickable <a> at all — there is no
    // real deal to link to, so its info renders as plain content instead.
    let cur: HTMLElement | null = amountEl;
    for (let i = 0; cur && i < 8; i++, cur = cur.parentElement) {
      expect(cur.tagName).not.toBe('A');
    }
  });

  it('still links normally when collaborationId is a real id', async () => {
    contractsListUnsigned.mockResolvedValue([baseContract]);

    renderPage();

    const amountEl = await screen.findByText(/45,000/);
    let cur: HTMLElement | null = amountEl;
    let anchor: HTMLElement | null = null;
    for (let i = 0; cur && i < 8; i++, cur = cur.parentElement) {
      if (cur.tagName === 'A') {
        anchor = cur;
        break;
      }
    }
    expect(anchor).not.toBeNull();
    expect(anchor?.getAttribute('href')).toMatch(/deal=deal-77(&|$)/);
  });
});

describe('creator dashboard — unsigned contract row: campaignTitle/brandWorkspaceName identity (F-0637)', () => {
  it('renders two same-amount unsigned contracts with a visibly different, real identifier from the backend fields', async () => {
    const contractA = {
      ...baseContract,
      id: 'CTR_601',
      collaborationId: 'deal-101',
      totalAmount: 50000,
      campaignTitle: 'Diwali Launch Campaign',
      brandWorkspaceName: 'Zenith Foods',
    } as unknown as ContractApiRecord;
    const contractB = {
      ...baseContract,
      id: 'CTR_602',
      collaborationId: 'deal-102',
      totalAmount: 50000,
      // No campaignTitle on this one (best-effort resolution failed) — must fall back to the
      // brand workspace name rather than rendering identically to contractA.
      campaignTitle: null,
      brandWorkspaceName: 'Northwind Apparel',
    } as unknown as ContractApiRecord;

    contractsListUnsigned.mockResolvedValue([contractA, contractB]);

    renderPage();

    await waitFor(() => expect(contractsListUnsigned).toHaveBeenCalled());

    // Both rows share the same amount...
    const amountEls = await screen.findAllByText(/50,000/);
    expect(amountEls).toHaveLength(2);

    // ...yet each carries a distinct, real identifier straight off the new backend fields: A's
    // own campaignTitle, and B's brandWorkspaceName fallback since B has no campaignTitle.
    expect(screen.getByText('Diwali Launch Campaign')).toBeInTheDocument();
    expect(screen.getByText('Northwind Apparel')).toBeInTheDocument();
  });

  it('degrades gracefully — no crash, no fabricated name — when both identity fields are absent', async () => {
    const contractNoIdentity = {
      ...baseContract,
      id: 'CTR_700',
      campaignTitle: null,
      brandWorkspaceName: null,
    } as unknown as ContractApiRecord;
    contractsListUnsigned.mockResolvedValue([contractNoIdentity]);

    renderPage();

    // Survives and still renders its real data.
    expect(await screen.findByText(/45,000/)).toBeInTheDocument();
    // Nothing fabricated in place of the missing identity: no generic placeholder text such as
    // "Unknown brand"/"Untitled campaign" was invented for this row.
    expect(screen.queryByText(/unknown brand|untitled campaign/i)).not.toBeInTheDocument();
  });
});
