/**
 * F-0669 round 3 — the DOM surface.
 *
 * `contract-panel-honest-terms.test.tsx` proved the generated PDF (`capturedHtml`) no longer
 * fabricates legal terms. It never asserted on what actually renders on screen — and the
 * on-screen "Contract Terms (Read-only)" clause list (contract-panel.tsx:171-189) kept showing:
 *
 *   :182  Content must be delivered by {meta?.deadline || '2024-02-15'}
 *   :183  Brand retains usage rights for 6 months from delivery
 *   :185  Either party may request revisions up to 2 times
 *
 * A brand reads this list — under a heading literally saying "Contract Terms" — as the actual
 * agreement. This spec renders the real component (no PDF click involved) and asserts on the
 * rendered DOM.
 *
 * Run: npx vitest run src/components/brand/timeline/panels/__tests__/contract-panel-dom-honest-terms.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ContractPanel } from '../contract-panel';
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

describe('ContractPanel — on-screen "Contract Terms" clause list (F-0669 round 3, DOM surface)', () => {
  it('never renders the fabricated deadline/usage-rights/revision-cap terms when the app was not given them', () => {
    render(
      <ContractPanel
        open={true}
        onOpenChange={vi.fn()}
        event={makeEvent({
          contractId: 'CONT-777',
          campaignName: 'Diwali Drop',
          amount: 75000,
          contractStatus: 'generated',
          // deliberately no deadline
        })}
      />,
    );

    const dom = document.body.textContent ?? '';
    expect(dom).not.toContain('2024-02-15');
    expect(dom).not.toContain('usage rights for 6 months');
    expect(dom).not.toContain('revisions up to 2 times');

    // The honest replacement is actually visible, not silently dropped.
    expect(screen.getByText(/Content must be delivered by Not specified/)).toBeInTheDocument();
    expect(screen.getByText('Usage rights: Not specified')).toBeInTheDocument();
    expect(screen.getByText('Revisions: Not specified')).toBeInTheDocument();
  });

  it('renders the real deadline when the app was given one, never the hardcoded fallback', () => {
    render(
      <ContractPanel
        open={true}
        onOpenChange={vi.fn()}
        event={makeEvent({
          contractId: 'CONT-778',
          campaignName: 'Diwali Drop',
          amount: 75000,
          contractStatus: 'generated',
          deadline: '2027-09-01',
        })}
      />,
    );

    expect(screen.getByText('Content must be delivered by 2027-09-01')).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toContain('2024-02-15');
  });
});
