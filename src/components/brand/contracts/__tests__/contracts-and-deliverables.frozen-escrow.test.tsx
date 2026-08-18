/**
 * F-0273 — frozen-escrow-reads-unlocked.
 * ----------------------------------------------------------------------------
 * `deriveEscrowFromMilestones` (contracts-and-deliverables.tsx) used to count only
 * FUNDED milestones as "locked" escrow. A milestone under an active dispute is
 * FROZEN — money the platform is still holding, but explicitly NOT releasable —
 * and fell through to the `false` branch, so a brand with a disputed deal was told
 * their escrow was "Not Locked" (F-0236's original bug, reopened for the one case
 * that matters most).
 *
 * A first-pass fix started counting FROZEN as "locked" too, which stopped the
 * false "Not Locked" reading but reintroduced the same class of false money
 * statement one level up: FROZEN and FUNDED both rendered as the identical green
 * "Locked" tile, so a brand in a dispute was told their money was ordinary,
 * releasable, funded escrow — which it is not.
 *
 * This suite asserts the money now reads as three genuinely different facts:
 *   - FROZEN (held, NOT releasable, dispute) must never render as "Locked" and
 *     must never share the green "Locked" styling.
 *   - Ordinary FUNDED-only escrow must still render as "Locked" (no regression).
 *   - No held milestones at all must still render "Not Locked".
 *
 * Run: npx vitest run src/components/brand/contracts/__tests__/contracts-and-deliverables.frozen-escrow.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

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

// Imported after the mock so the component picks up the mocked `@/lib/api`.
const { ContractsAndDeliverables } = await import('../contracts-and-deliverables');

function record(over: Record<string, unknown> = {}) {
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

async function setup(rec: ReturnType<typeof record>) {
  contractsList.mockResolvedValue([rec]);
  contractsGet.mockResolvedValue(rec);
  dealsList.mockResolvedValue([]);
  render(
    <MemoryRouter>
      <ContractsAndDeliverables />
    </MemoryRouter>,
  );
  return userEvent.setup({ delay: null });
}

describe('F-0273 — a FROZEN milestone reads as frozen, not ordinary funded escrow', () => {
  it('Escrow Status tile shows Frozen — never the green "Locked" label — when a milestone is FROZEN', async () => {
    await setup(
      record({
        milestones: [
          { id: 'ms-1', sequenceNo: 1, description: 'Kickoff', amount: 30000, status: 'FUNDED' },
          { id: 'ms-2', sequenceNo: 2, description: 'Final delivery', amount: 30000, status: 'FROZEN' },
        ],
      }),
    );

    const frozenLabel = await screen.findByText('Frozen');
    expect(frozenLabel).toBeInTheDocument();
    // The exact defect: reusing the funded/releasable "Locked" green styling for
    // frozen (held-not-releasable) money.
    expect(frozenLabel.className).not.toMatch(/text-green-500/);
    expect(screen.queryByText('Locked')).toBeNull();
    // The brand must be told plainly that this money cannot be released right now.
    expect(screen.getByText(/not releasable/i)).toBeInTheDocument();
  });

  it('Payment Schedule renders the FROZEN milestone distinctly from a merely-pending one', async () => {
    await setup(
      record({
        milestones: [
          { id: 'ms-1', sequenceNo: 1, description: 'Kickoff', amount: 30000, status: 'PENDING' },
          { id: 'ms-2', sequenceNo: 2, description: 'Final delivery', amount: 30000, status: 'FROZEN' },
        ],
      }),
    );

    const user = userEvent.setup({ delay: null });
    const paymentsTab = await screen.findByRole('tab', { name: 'Payments' });
    await user.click(paymentsTab);

    const frozenRow = (await screen.findByText('Final delivery')).closest(
      'div.flex.items-center.justify-between',
    );
    expect(frozenRow).not.toBeNull();
    const frozenStatus = within(frozenRow as HTMLElement).getByText('Frozen');
    const pendingRow = screen.getByText('Kickoff').closest('div.flex.items-center.justify-between');
    const pendingStatus = within(pendingRow as HTMLElement).getByText('Pending');

    // Regression guard for the exact "wrong fix" shape: a FROZEN row must not carry
    // the identical styling as a row that was simply never funded.
    expect(frozenStatus.className).not.toBe(pendingStatus.className);
    expect(frozenStatus.className).not.toMatch(/text-muted-foreground/);
  });

  it('an all-FUNDED contract with no dispute still reads as ordinary Locked (no regression)', async () => {
    await setup(
      record({
        milestones: [
          { id: 'ms-1', sequenceNo: 1, description: 'Kickoff', amount: 30000, status: 'FUNDED' },
        ],
      }),
    );

    const lockedLabel = await screen.findByText('Locked');
    expect(lockedLabel.className).toMatch(/text-green-500/);
    expect(screen.queryByText('Frozen')).toBeNull();
  });

  it('a contract with no held milestones still reads Not Locked (no regression)', async () => {
    await setup(
      record({
        milestones: [
          { id: 'ms-1', sequenceNo: 1, description: 'Kickoff', amount: 30000, status: 'PENDING' },
        ],
      }),
    );

    const notLockedLabel = await screen.findByText('Not Locked');
    expect(notLockedLabel).toBeInTheDocument();
    expect(screen.queryByText('Frozen')).toBeNull();
  });
});
