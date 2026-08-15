/**
 * ProposalEventCard — regression test for the live crash on
 * /brand/campaigns/:id (React error #31, 2026-08-15).
 *
 * `DealService.persistProposalMessage` (DealService.java:1132) stores the proposal's
 * `DeliverableSlot[]` under `metadata.deliverables`; the card rendered that array straight into
 * JSX as `{meta?.deliverables || 0} pieces`. An array is truthy, so `|| 0` never fired and React
 * threw "Objects are not valid as a React child (found: object with keys {qty, type})", which the
 * route-level ErrorBoundary turned into a full-page "Something went wrong" the moment a brand
 * opened the collaboration timeline on a deal that had a real proposal.
 *
 * The mock fixtures used the older plain-number shape, so this only ever broke on live data.
 * Both shapes are still in the messages table, so both are asserted here.
 *
 * Run: npx vitest run src/components/brand/timeline/event-cards/proposal-card.test.tsx
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ProposalEventCard } from './proposal-card';
import type { TimelineEvent } from '@/lib/types';

function makeEvent(metadata: TimelineEvent['metadata']): TimelineEvent {
  return {
    id: 'evt-1',
    collaborationId: 'collab-1',
    timestamp: new Date('2024-01-01'),
    senderId: 'brand-1',
    senderName: 'Acme',
    senderType: 'brand',
    tag: 'proposal',
    metadata,
    status: 'sent',
  };
}

describe('ProposalEventCard — DeliverableSlot[] metadata must not crash the render', () => {
  it('renders the live slot-array shape as a count instead of throwing React #31', () => {
    const event = makeEvent({
      amount: 25000,
      status: 'pending',
      deliverables: [
        { type: 'REEL', qty: 2 },
        { type: 'STORY', qty: 1 },
      ],
      deliverableCount: 2,
    });

    // Before the fix this render threw, so the assertion is that it completes at all.
    expect(() => render(<ProposalEventCard event={event} currentUserType="brand" />)).not.toThrow();

    // Prefers the backend's own deliverableCount (number of slots), not the summed qty.
    expect(screen.getByText('2 pieces')).toBeInTheDocument();
    // The slot detail is what the array shape exists to expose — surfaced, not dropped.
    expect(screen.getByText('2x REEL · 1x STORY')).toBeInTheDocument();
    // The object must never reach the DOM in any stringified form.
    expect(screen.queryByText(/\[object Object\]/)).not.toBeInTheDocument();
  });

  it('falls back to the slot-array length when deliverableCount is absent', () => {
    const event = makeEvent({
      amount: 1000,
      deliverables: [{ type: 'POST', qty: 3 }],
    });
    render(<ProposalEventCard event={event} currentUserType="brand" />);

    expect(screen.getByText('1 piece')).toBeInTheDocument();
    expect(screen.getByText('3x POST')).toBeInTheDocument();
  });

  it('still renders pre-2026-07-26 proposals that stored a plain count', () => {
    const event = makeEvent({ amount: 1000, deliverables: 3 });
    render(<ProposalEventCard event={event} currentUserType="brand" />);

    expect(screen.getByText('3 pieces')).toBeInTheDocument();
  });

  it('says so honestly when the proposal carries no deliverable information', () => {
    const event = makeEvent({ amount: 1000 });
    render(<ProposalEventCard event={event} currentUserType="brand" />);

    // Not "0 pieces" — absent terms are unknown, not zero (TECH-STACK.md rule 7).
    expect(screen.getByText('Not specified')).toBeInTheDocument();
    expect(screen.queryByText('0 pieces')).not.toBeInTheDocument();
  });
});
