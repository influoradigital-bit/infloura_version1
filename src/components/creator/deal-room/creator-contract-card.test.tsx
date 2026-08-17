/**
 * CreatorContractCard — regression test for BrandF.md P-3.
 *
 * P-3: `₹{(meta?.amount || 50000).toLocaleString('en-IN')}` fabricated a fake ₹50,000
 * escrow figure whenever the real amount was 0, null, or undefined (e.g.
 * `parseDealAmount()` returning 0 for an unparsed/missing `dealValue` in the live
 * path). This test locks in the fix: a missing amount must never render as ₹50,000,
 * and must instead render an honest placeholder.
 *
 * Run: npx vitest run src/components/creator/deal-room/creator-contract-card.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CreatorContractCard } from './creator-contract-card';
import type { TimelineEvent } from '@/lib/types';

function makeEvent(metadata: TimelineEvent['metadata']): TimelineEvent {
  return {
    id: 'evt-1',
    collaborationId: 'collab-1',
    timestamp: new Date('2024-01-01'),
    senderId: 'system',
    senderType: 'system',
    tag: 'contract',
    metadata,
    status: 'sent',
  };
}

describe('CreatorContractCard — no fabricated ₹50,000 fallback', () => {
  it('never renders ₹50,000 when amount is undefined (missing)', () => {
    const event = makeEvent({ contractStatus: 'active' });
    render(<CreatorContractCard event={event} onViewClick={vi.fn()} />);

    expect(screen.queryByText(/50,000/)).not.toBeInTheDocument();
    // Contract Value row falls back to the codebase's shared "—" convention (formatINR).
    expect(screen.getByText('Contract Value').nextSibling ?? screen.getByText('—')).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('never renders ₹50,000 when amount is explicitly 0', () => {
    const event = makeEvent({ contractStatus: 'active', amount: 0 });
    render(<CreatorContractCard event={event} onViewClick={vi.fn()} />);

    expect(screen.queryByText(/50,000/)).not.toBeInTheDocument();
    // 0 is a real, finite number — matches this codebase's formatINR convention of only
    // treating null/undefined/NaN as "missing", so it renders as an honest ₹0, not a
    // fabricated figure and not silently merged with the "amount not set" case.
    expect(screen.getByText('₹0')).toBeInTheDocument();
  });

  it('renders the real amount when present, untouched by any fallback', () => {
    const event = makeEvent({ contractStatus: 'active', amount: 75000 });
    render(<CreatorContractCard event={event} onViewClick={vi.fn()} />);

    expect(screen.getAllByText('₹75,000').length).toBeGreaterThan(0);
  });
});

/**
 * F-0226 (false-success-copy). This card used to render an "Escrow Funded" block with a green
 * check whenever `contractStatus === 'active'`. Contract ACTIVE means both parties signed and
 * nothing more: the collaboration reaches CONTRACTED there, and only
 * `CollaborationLifecycleService.onEscrowFunded` — driven by the brand funding a milestone,
 * a separate and later action — reaches IN_PROGRESS, which is what actually gates the
 * creator's submit control. The card has no escrow data of its own, so the block asserted a
 * funding state it could not know.
 *
 * Two of the P-3 tests above used to assert that block's copy ("₹75,000 secured in escrow",
 * "Escrow amount not yet available"). Those assertions are gone rather than adjusted: the
 * element they described should not exist at all. P-3's own subject — never fabricating a
 * ₹50,000 fallback — is still covered by the ₹0 / "—" / no-50,000 assertions above.
 */
describe('CreatorContractCard — F-0226: never claims escrow is funded', () => {
  it('shows no escrow-funded claim on a fully signed contract', () => {
    const event = makeEvent({ contractStatus: 'active', amount: 75000 });
    render(<CreatorContractCard event={event} onViewClick={vi.fn()} />);

    expect(screen.queryByText(/secured in escrow/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/escrow funded/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/funded/i)).not.toBeInTheDocument();
  });

  it('does not label a signed contract as funded in its status badge', () => {
    const event = makeEvent({ contractStatus: 'active', amount: 75000 });
    render(<CreatorContractCard event={event} onViewClick={vi.fn()} />);

    expect(screen.queryByText(/active & funded/i)).not.toBeInTheDocument();
    expect(screen.getByText('Both Signed')).toBeInTheDocument();
  });
});
