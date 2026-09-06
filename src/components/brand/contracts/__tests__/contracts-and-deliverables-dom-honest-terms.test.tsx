/**
 * F-0669 round 3 — the DOM surface, most-serious instance.
 *
 * The demo-mode `mockContracts` fixture's "Revisions" clause (contracts-and-deliverables.tsx,
 * clause id 'c5') read:
 *
 *   'Up to 2 rounds of revisions are included. Additional revisions will be charged at
 *    INR 2,500 per round.'
 *
 * and is rendered verbatim in the "Contract" tab's clause list — the brand reads this as a real
 * clause of their real contract. The revision cap (2) matches this same fixture's own
 * `maxRevisions: 2` on its deliverables, so that part is self-consistent demo content; but no
 * field anywhere in this fixture (or the real Contract/ContractDeliverable types) backs an extra
 * per-round fee — 'INR 2,500' was invented out of nothing, presenting a PRICE as a term of the
 * agreement.
 *
 * This spec renders the real component in demo mode, opens the real "Contract" tab, and asserts
 * on the rendered clause-list DOM — plus a positive control that the clause referencing this
 * mock contract's own real `value` field (compensation, INR 45,000) still renders untouched.
 *
 * Run: npx vitest run src/components/brand/contracts/__tests__/contracts-and-deliverables-dom-honest-terms.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    // Demo/mock mode: contracts state seeds from the component's own internal
    // mockContracts fixture (fetchContracts returns early when !liveApi), never a network call.
    isApiLive: () => false,
  };
});

const { ContractsAndDeliverables } = await import('../contracts-and-deliverables');

describe('ContractsAndDeliverables — "Contract" tab clause list (F-0669 round 3, DOM surface)', () => {
  it('never renders the fabricated INR 2,500 per-round revision fee', async () => {
    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter>
        <ContractsAndDeliverables />
      </MemoryRouter>,
    );

    // mockContracts[0] is selected by default in demo mode. Open the "Contract" tab to reveal
    // the clause list (default active tab is "overview").
    await user.click(await screen.findByRole('tab', { name: 'Contract' }));

    const clauseHeading = await screen.findByText(/Revisions/, { selector: 'h4' });
    const clauseCard = clauseHeading.closest('.p-4') as HTMLElement;
    expect(clauseCard).toBeTruthy();
    const clauseText = within(clauseCard).getByText(/revisions/i, { selector: 'p' }).textContent ?? '';

    expect(clauseText).not.toContain('INR 2,500');
    expect(clauseText).not.toMatch(/charged at/i);
    expect(clauseText).toMatch(/mutually agreed/i);
    // The revision cap itself is genuine demo content (matches this fixture's own
    // maxRevisions: 2 on its deliverables) — not what this fix targets, so it stays.
    expect(clauseText).toContain('Up to 2 rounds of revisions are included');

    // Positive control: a real value tied to this mock contract's own `value` field (45000)
    // still renders untouched — this isn't a "delete everything" over-fix.
    expect(screen.getByText(/INR 45,000/)).toBeInTheDocument();
  });
});
