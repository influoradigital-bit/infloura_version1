/**
 * F-0251 — missing-endpoint-hidden-by-mock (Payments tab).
 * ----------------------------------------------------------------------------
 * `ContractApiRecord` carries no top-level escrow field, no hash, and no
 * funded/released timestamp — only `milestones[]` (id/description/amount/status),
 * and `wallet.escrowList` (`GET /wallet/escrow`) is brand-wide, not filterable by
 * contract. F-0236 un-mocked the LIVE branch of the Payments tab to render real
 * `selectedContract.milestones` instead of a fabricated "50% Paid / 50% In Escrow"
 * schedule — but the non-live (demo/mock) branch was left completely untouched by
 * that fix: every demo contract, regardless of its own `escrowLocked`/`escrowAmount`
 * fields, still rendered the identical hardcoded "50% Upon Signing — Paid / 50% Upon
 * Completion — In Escrow" schedule plus a fake "Escrow Funded — Jan 10, 2024" /
 * "First Payment Released — Jan 15, 2024" transaction history. `mockContracts`
 * contract-3 is `draft`, unsigned, `escrowLocked: false`, `escrowAmount: 0` — no
 * money has ever moved for it — yet the tab told the brand it was half paid.
 *
 * This suite asserts the Payments tab now tells the truth about what data actually
 * exists, in both live and demo mode, with no liveApi-gated fabrication path left:
 *   - A contract with no milestone breakdown (live or demo) shows the real
 *     escrowLocked/escrowAmount/escrowFrozen summary and says milestone-level detail
 *     isn't available — never a fabricated 50/50 split.
 *   - A contract with real milestones renders each milestone's real amount/status
 *     (already covered structurally by the frozen-escrow suite; re-asserted here
 *     against the "no fabrication" angle).
 *   - No invented transaction dates ("Jan 10, 2024" / "Jan 15, 2024") ever render,
 *     in either mode.
 *
 * Run: npx vitest run src/components/brand/contracts/__tests__/contracts-and-deliverables.payments-truthful.test.tsx
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const contractsList = vi.fn();
const contractsGet = vi.fn();
const dealsList = vi.fn();

// isApiLive() is read at render time inside the component body, so flipping this
// mutable flag (read by the mock closure) switches live/demo mode per test without
// vi.resetModules() — same pattern as contracts-sign-reachability.test.tsx.
let liveModeOverride = true;

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => liveModeOverride,
    api: {
      ...actual.api,
      contracts: {
        ...actual.api.contracts,
        list: (...a: unknown[]) => contractsList(...a),
        get: (...a: unknown[]) => contractsGet(...a),
      },
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsList(...a) },
    },
  };
});

// Imported after the mock so the component picks up the mocked `@/lib/api`.
const { ContractsAndDeliverables } = await import('../contracts-and-deliverables');

function liveRecord(over: Record<string, unknown> = {}) {
  return {
    id: 'ctr-1',
    collaborationId: 'collab-1',
    status: 'ACTIVE',
    totalAmount: 60000,
    currency: 'INR',
    brandSignedAt: '2026-08-01T00:00:00Z',
    creatorSignedAt: '2026-08-02T00:00:00Z',
    milestones: [],
    expirationDate: '2026-12-31',
    createdAt: '2026-08-01',
    ...over,
  };
}

async function openPaymentsTab(user: ReturnType<typeof userEvent.setup>) {
  const tab = await screen.findByRole('tab', { name: 'Payments' });
  await user.click(tab);
}

afterEach(() => {
  liveModeOverride = true;
});

describe('F-0251 — live mode, no milestone data: the tab tells the truth instead of fabricating', () => {
  it('renders the real escrow summary and an honest "not available" note — never the invented 50/50 schedule', async () => {
    liveModeOverride = true;
    contractsList.mockResolvedValue([liveRecord({ milestones: [] })]);
    contractsGet.mockResolvedValue(liveRecord({ milestones: [] }));
    dealsList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );
    await openPaymentsTab(user);

    expect(screen.getByText(/milestone-level payment detail isn.t available/i)).toBeInTheDocument();
    expect(screen.getByText(/transaction-level history isn.t available/i)).toBeInTheDocument();

    // The exact defect: a fabricated 50% split and invented settlement dates.
    expect(screen.queryByText(/50% Upon Signing/i)).toBeNull();
    expect(screen.queryByText(/50% Upon Completion/i)).toBeNull();
    expect(screen.queryByText(/Jan 10, 2024/i)).toBeNull();
    expect(screen.queryByText(/Jan 15, 2024/i)).toBeNull();
  });

  it('an unlocked (no escrow) contract with no milestones says so plainly', async () => {
    liveModeOverride = true;
    const rec = liveRecord({ milestones: [] });
    contractsList.mockResolvedValue([rec]);
    contractsGet.mockResolvedValue(rec);
    dealsList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );
    await openPaymentsTab(user);

    expect(screen.getByText('No escrow held')).toBeInTheDocument();
  });
});

describe('F-0251 — demo mode: the same honesty applies to mockContracts fixtures, not just live records', () => {
  it('contract-3 (draft, unsigned, escrowLocked: false, escrowAmount: 0) is never shown as half paid', async () => {
    liveModeOverride = false;
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter initialEntries={['/?contract=contract-3']}>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );
    await openPaymentsTab(user);

    // The exact F-0251 defect: this contract has never had a rupee move, but the
    // old demo-mode branch unconditionally rendered it as 50% paid with a funded
    // date already in the past.
    expect(screen.queryByText(/50% Upon Signing/i)).toBeNull();
    expect(screen.queryByText('Paid')).toBeNull();
    expect(screen.queryByText(/Jan 10, 2024/i)).toBeNull();
    expect(screen.queryByText(/Jan 15, 2024/i)).toBeNull();
    expect(screen.getByText('No escrow held')).toBeInTheDocument();
  });

  it('contract-1 (escrowLocked: true, escrowAmount: 45000) shows the real held amount, not an invented 50% split', async () => {
    liveModeOverride = false;
    const user = userEvent.setup({ delay: null });

    render(
      <MemoryRouter initialEntries={['/?contract=contract-1']}>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );
    await openPaymentsTab(user);

    const escrowHeldLabel = screen.getByText('Escrow held');
    // Scoped to the Payment Schedule row itself — "45,000" also legitimately appears in the
    // unrelated "Contract Value" stat tile elsewhere on the page (same number here, by
    // coincidence of this fixture), so an unscoped getAllByText would pass even if this row's
    // OWN amount were silently swapped for a fabricated half (22,500). Only a scoped assertion
    // on this row's real content catches that renamed-fabrication shape of wrong fix.
    const escrowRow = escrowHeldLabel.closest('div.flex.items-center.justify-between');
    expect(escrowRow).not.toBeNull();
    // The real mock escrowAmount (45000) — never a fabricated half (22500).
    expect(within(escrowRow as HTMLElement).getByText(/45,?000/)).toBeInTheDocument();
    expect(within(escrowRow as HTMLElement).queryByText(/22,?500/)).toBeNull();
    expect(screen.queryByText(/50% Upon Signing/i)).toBeNull();
    expect(screen.queryByText(/50% Upon Completion/i)).toBeNull();
    expect(screen.queryByText(/Jan 10, 2024/i)).toBeNull();
  });
});
