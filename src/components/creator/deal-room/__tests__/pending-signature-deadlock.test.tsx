/**
 * F-0250 follow-up — creator-side half of the deadlock-prevention property.
 *
 * `mapDealApiContractStatus` cannot resolve which party signed on a bare
 * `PENDING_SIGNATURES` + unfunded escrow, so it returns the ambiguous
 * `'pending_signature'` member (src/lib/creator-contract-mappers.ts). The bug this guards
 * against is NOT visible from the mapper's return value alone — the previous ('generated')
 * fix passed a spec that only checked `mapDealApiContractStatus`'s output while the CREATOR
 * was, in fact, permanently blocked from signing on the brand-first path (the common one).
 *
 * The property that actually prevents the deadlock: in the 'pending_signature' state, the
 * creator's Sign control must be reachable (both in `CreatorDealContractTab`, the deal-room
 * tab, and in `CreatorContractPanel`, the chat-timeline sheet), and no copy anywhere may claim
 * the brand specifically has signed (that would be a guess this state cannot support).
 *
 * See the companion brand-side spec:
 * src/components/brand/deal-room/__tests__/pending-signature-deadlock.test.tsx
 *
 * Run: npx vitest run src/components/creator/deal-room/__tests__/pending-signature-deadlock.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CreatorDealContractTab } from '../creator-deal-contract-tab';
import { CreatorContractPanel } from '../creator-contract-panel';
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

describe('CreatorDealContractTab — pending_signature keeps the creator Sign control reachable', () => {
  it('renders the signer-name input and Sign button on pending_signature', () => {
    render(
      <CreatorDealContractTab
        contractId="CTR-1"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={10000}
        contractAmount={10000}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/type your full legal name to sign/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign contract/i })).toBeInTheDocument();
  });

  it('does not claim the brand specifically has signed on pending_signature', () => {
    render(
      <CreatorDealContractTab
        contractId="CTR-1"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={10000}
        contractAmount={10000}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    // The false claim this regresses: "{brandName} has signed" when the signer is unknown.
    expect(screen.queryByText(/acme co has signed/i)).not.toBeInTheDocument();
    // Nor may it claim the opposite (that the brand hasn't signed yet) — that's the other lie.
    expect(screen.queryByText(/awaiting brand signature/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/hasn.t signed this contract yet/i)).not.toBeInTheDocument();
  });

  it('still shows the honest "awaiting brand" copy on the real generated state (control)', () => {
    render(
      <CreatorDealContractTab
        contractId="CTR-1"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={10000}
        contractAmount={10000}
        status="generated"
        onStatusChange={vi.fn()}
      />,
    );

    expect(screen.getByText(/awaiting brand signature/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /sign contract/i })).not.toBeInTheDocument();
  });
});

describe('CreatorContractPanel — pending_signature keeps the creator Sign control reachable', () => {
  it('renders the Sign Now button on pending_signature', () => {
    const event = makeEvent({ contractId: 'CTR-1', amount: 10000, brandName: 'Acme Co' });
    render(
      <CreatorContractPanel
        open
        onOpenChange={vi.fn()}
        event={event}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: /sign now/i })).toBeInTheDocument();
  });

  it('does not label pending_signature as "Brand Signed - Your Turn to Sign"', () => {
    const event = makeEvent({ contractId: 'CTR-1', amount: 10000, brandName: 'Acme Co' });
    render(
      <CreatorContractPanel
        open
        onOpenChange={vi.fn()}
        event={event}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    // Note: the static "Brand Signed" step-timeline label is expected to stay in the DOM
    // (it names a step, not a claim about the current state) — only the status HEADER and the
    // "the brand has signed" description are the false claims under test here.
    expect(screen.queryByText(/brand signed - your turn to sign/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/the brand has signed the contract/i)).not.toBeInTheDocument();
    expect(screen.getByText(/signature pending - your turn to sign/i)).toBeInTheDocument();
  });
});
