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
    // Escrow line renders an honest placeholder instead of a fabricated amount.
    expect(screen.getByText('Escrow amount not yet available')).toBeInTheDocument();
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
    expect(screen.getByText('₹75,000 secured in escrow')).toBeInTheDocument();
  });
});
