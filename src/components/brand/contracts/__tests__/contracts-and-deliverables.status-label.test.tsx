/**
 * F-0659 — new-status-unhandled-in-ui.
 * ----------------------------------------------------------------------------
 * Backend `ContractStatus.COMPLETED` became reachable in production for the first time when
 * `ContractService#retirePredecessorIfSuperseded` started retiring a superseded contract's
 * predecessor to COMPLETED the moment its amendment is signed (same transaction the amendment
 * itself becomes ACTIVE in). Both an ACTIVE contract and a retired COMPLETED one could now sit
 * side by side in the same brand's contract list.
 *
 * Before this fix, `mapApiContractStatus` collapsed COMPLETED onto the same 'signed' UI status as
 * ACTIVE, so `contractStatusBadgeLabel`/`statusConfig` rendered the identical "Active" badge for
 * both — a brand had no way to tell the still-live agreement from the one it replaced.
 *
 * Two guarantees:
 *   A. Unit — mapApiContractStatus/contractStatusBadgeLabel give ACTIVE and COMPLETED different
 *      UI statuses and different, non-empty labels (not the fallback 'Draft'/'Unknown' either).
 *   B. Behavioral — rendering the real contract list with one ACTIVE and one COMPLETED contract
 *      shows exactly one "Active" badge, not two, plus a distinct label for the retired one.
 *
 * Run: npx vitest run src/components/brand/contracts/__tests__/contracts-and-deliverables.status-label.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { ContractApiRecord } from '@/lib/api';

const contractsList = vi.fn();
const contractsGet = vi.fn();
const dealsList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
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

// Imported after the mock so the component and the exported helpers both pick up the mocked
// `@/lib/api` module graph.
const { ContractsAndDeliverables, mapApiContractStatus, contractStatusBadgeLabel } = await import(
  '../contracts-and-deliverables'
);

function record(over: Partial<ContractApiRecord> = {}): ContractApiRecord {
  return {
    id: 'ctr-1',
    collaborationId: 'collab-1',
    status: 'ACTIVE',
    totalAmount: 50000,
    currency: 'INR',
    brandSignedAt: '2026-08-01T00:00:00Z',
    creatorSignedAt: '2026-08-02T00:00:00Z',
    milestones: [],
    expirationDate: '2026-12-31',
    createdAt: '2026-08-01',
    ...over,
  };
}

describe('F-0659 — COMPLETED gets its own honest label, distinct from ACTIVE', () => {
  it('mapApiContractStatus maps COMPLETED to a different UI status than ACTIVE', () => {
    const activeUiStatus = mapApiContractStatus('ACTIVE');
    const completedUiStatus = mapApiContractStatus('COMPLETED');

    expect(activeUiStatus).toBe('signed');
    // The regression: pre-fix this was also 'signed', identical to ACTIVE.
    expect(completedUiStatus).not.toBe('signed');
    expect(completedUiStatus).not.toBe(activeUiStatus);
    expect(completedUiStatus).toBeDefined();
  });

  it('contractStatusBadgeLabel gives COMPLETED its own non-empty label, not "Active"', () => {
    const activeLabel = contractStatusBadgeLabel('ACTIVE');
    const completedLabel = contractStatusBadgeLabel('COMPLETED');

    expect(activeLabel).toBe('Active');
    expect(completedLabel.length).toBeGreaterThan(0);
    // Not the same string as ACTIVE...
    expect(completedLabel).not.toBe(activeLabel);
    // ...and not silently falling through to the unmapped/default 'Draft' bucket either.
    expect(completedLabel).not.toBe('Draft');
    expect(completedLabel.toLowerCase()).not.toBe('active');
  });

  it('a brand looking at one ACTIVE contract and its superseded COMPLETED predecessor sees two different badges, not two "Active" badges', async () => {
    const active = record({ id: 'ctr-active', collaborationId: 'collab-1', status: 'ACTIVE' });
    const completed = record({ id: 'ctr-completed', collaborationId: 'collab-1', status: 'COMPLETED' });
    contractsList.mockResolvedValue([active, completed]);
    contractsGet.mockImplementation((_role: string, id: string) =>
      Promise.resolve([active, completed].find((r) => r.id === id) ?? null),
    );
    dealsList.mockResolvedValue([]);

    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    // Wait for the live list to render both rows.
    await screen.findByText(contractStatusBadgeLabel('COMPLETED'));

    // Scope to the sidebar contract-list rows (each one a <button>) so this doesn't also count
    // the unrelated "Active" <option> in the status-filter <select>, or the detail pane's own
    // (legitimately duplicated) badge for whichever row is currently selected.
    const rowBadgesLabelled = (label: string) =>
      screen.getAllByText(label).filter((el) => el.closest('button') !== null);

    // Exactly one contract ROW in this list is genuinely still live/binding.
    expect(rowBadgesLabelled('Active')).toHaveLength(1);
    // The superseded one reads as something else entirely — never the same "Active" badge —
    // and it renders in its own list row too.
    expect(rowBadgesLabelled(contractStatusBadgeLabel('COMPLETED'))).toHaveLength(1);
  });
});
