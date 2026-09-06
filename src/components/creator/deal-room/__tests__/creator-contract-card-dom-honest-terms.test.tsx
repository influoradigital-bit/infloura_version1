/**
 * F-0669 round 3 — the DOM surface.
 *
 * `CreatorContractCard` (the summary card shown in the creator's deal-room timeline, before the
 * full panel opens) has its own "Key Terms" grid (creator-contract-card.tsx:145-170) with the
 * same class of defect as the panel:
 *
 *   :159  {meta?.deadline || '2024-02-15'}
 *   :163  Usage Rights → hardcoded "6 months" (not individually named in the F-0669 round-3
 *         finding, but the identical no-source fabrication sitting between the two named lines
 *         in this same grid — fixed alongside them rather than left as a partial fix)
 *   :167  Up to 2
 *
 * This spec renders the real component and asserts on the rendered DOM.
 *
 * Run: npx vitest run src/components/creator/deal-room/__tests__/creator-contract-card-dom-honest-terms.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CreatorContractCard } from '../creator-contract-card';
import type { TimelineEvent } from '@/lib/types';

function makeEvent(metadata: TimelineEvent['metadata']): TimelineEvent {
  return {
    id: 'evt-1',
    collaborationId: 'collab-1',
    timestamp: new Date('2026-01-01T00:00:00Z'),
    senderId: 'system',
    senderType: 'system',
    tag: 'contract',
    status: 'sent',
    metadata,
  };
}

describe('CreatorContractCard — on-screen "Key Terms" grid (F-0669 round 3, DOM surface)', () => {
  it('never renders the fabricated deadline/usage-rights/revision-cap terms when the app was not given them', () => {
    render(
      <CreatorContractCard
        event={makeEvent({
          contractId: 'CONT-991',
          brandName: 'Acme Co',
          amount: 40000,
          contractStatus: 'brand_signed',
          // deliberately no deadline
        })}
        onViewClick={vi.fn()}
      />,
    );

    const dom = document.body.textContent ?? '';
    expect(dom).not.toContain('2024-02-15');

    const deadlineLabel = screen.getByText('Deadline');
    const usageRightsLabel = screen.getByText('Usage Rights');
    const revisionsLabel = screen.getByText('Revisions');
    // Deadline's value sits in the sibling <p>, not a plain "6 months"/"Up to 2" fabrication.
    expect(deadlineLabel.nextElementSibling?.textContent).toBe('Not specified');
    expect(usageRightsLabel.nextElementSibling?.textContent).toBe('Not specified');
    expect(usageRightsLabel.nextElementSibling?.textContent).not.toBe('6 months');
    expect(revisionsLabel.nextElementSibling?.textContent).toBe('Not specified');
    expect(revisionsLabel.nextElementSibling?.textContent).not.toBe('Up to 2');
  });

  it('renders the real deadline when the app was given one, never the hardcoded fallback', () => {
    render(
      <CreatorContractCard
        event={makeEvent({
          contractId: 'CONT-992',
          brandName: 'Acme Co',
          amount: 40000,
          contractStatus: 'brand_signed',
          deadline: '2027-09-01',
        })}
        onViewClick={vi.fn()}
      />,
    );

    const deadlineLabel = screen.getByText('Deadline');
    expect(deadlineLabel.nextElementSibling?.textContent).toBe('2027-09-01');
    expect(document.body.textContent ?? '').not.toContain('2024-02-15');
  });
});
