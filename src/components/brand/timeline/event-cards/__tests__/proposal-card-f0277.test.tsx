/**
 * F-0277 regression test — the unreachable creator-side action buttons must stay deleted.
 *
 * ProposalEventCard is only ever mounted brand-side (brand-campaign-detail.tsx passes
 * currentUserType="brand" to CollaborationTimeline, which routes to this card). The
 * creator-gated Accept/Counter/Reject buttons were dead code that would ship enabled
 * with no handlers the day the timeline is mounted creator-side. They were deleted in
 * favor of the creator's own dedicated, working components
 * (src/components/creator/deal-room/counter-proposal-card.tsx + counter-proposal-form.tsx).
 *
 * This test asserts that those buttons stay gone — a mutation test will catch any
 * accidental resurrection.
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ProposalEventCard } from '../proposal-card';
import type { TimelineEvent } from '@/lib/types';

function makeProposal(status: 'pending' | 'accepted' | 'rejected'): TimelineEvent {
  return {
    id: 'evt-1',
    collaborationId: 'deal-1',
    timestamp: new Date('2024-01-01'),
    senderId: 'brand-1',
    senderName: 'Acme',
    senderType: 'brand',
    tag: 'proposal',
    metadata: { amount: 25000, status },
    status: 'sent',
  };
}

describe('F-0277 — unreachable action buttons must not return', () => {
  it('does not render Accept/Counter/Reject buttons for a pending proposal', () => {
    render(<ProposalEventCard event={makeProposal('pending')} />);

    // The component is brand-side only (no currentUserType param anymore), so these
    // creator-action buttons must never render.
    expect(screen.queryByRole('button', { name: /accept/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /counter/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument();
  });

  it('renders the proposal content without action controls', () => {
    render(<ProposalEventCard event={makeProposal('pending')} />);

    // The card still shows the proposal details — only the unreachable buttons are gone.
    expect(screen.getByText(/acme sent a proposal/i)).toBeInTheDocument();
    expect(screen.getByText('₹25,000')).toBeInTheDocument();
  });

  it('shows the accepted badge when the proposal was accepted (read-only)', () => {
    render(<ProposalEventCard event={makeProposal('accepted')} />);

    expect(screen.getByText(/accepted/i)).toBeInTheDocument();
    expect(screen.getByText(/both parties agreed/i)).toBeInTheDocument();
    // No action buttons on a settled proposal either.
    expect(screen.queryByRole('button', { name: /accept/i })).not.toBeInTheDocument();
  });

  it('shows the rejected badge when the proposal was rejected (read-only)', () => {
    render(<ProposalEventCard event={makeProposal('rejected')} />);

    // Use more specific text to avoid matching both badge and message
    expect(screen.getByText(/proposal rejected/i)).toBeInTheDocument();
    // Verify no action buttons
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument();
  });
});
