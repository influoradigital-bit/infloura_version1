/**
 * F-0669 round 3 — the DOM surface.
 *
 * `creator-contract-panel-honest-terms.test.tsx` proved the generated PDF (`capturedHtml`) no
 * longer fabricates legal terms. It never asserted on what actually renders on screen — and the
 * on-screen "Key Terms" section (creator-contract-panel.tsx:252-277), read by the CREATOR before
 * they sign, kept showing:
 *
 *   :266  {meta?.deadline || '2024-02-15'}
 *   :270  6 months on social media platforms
 *   :274  2 revisions per deliverable
 *
 * This spec renders the real component (no PDF click involved) and asserts on the rendered DOM.
 *
 * Run: npx vitest run src/components/creator/deal-room/__tests__/creator-contract-panel-dom-honest-terms.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CreatorContractPanel } from '../creator-contract-panel';
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

describe('CreatorContractPanel — on-screen "Key Terms" (F-0669 round 3, DOM surface)', () => {
  it('never renders the fabricated deadline/usage-rights/revision-cap terms when the app was not given them', () => {
    render(
      <CreatorContractPanel
        open={true}
        onOpenChange={vi.fn()}
        event={makeEvent({
          contractId: 'CONT-881',
          brandName: 'Acme Co',
          campaignName: 'Diwali Drop',
          amount: 40000,
          contractStatus: 'generated',
          // deliberately no deadline
        })}
        status="generated"
        onStatusChange={vi.fn()}
      />,
    );

    const dom = document.body.textContent ?? '';
    expect(dom).not.toContain('2024-02-15');
    expect(dom).not.toContain('6 months on social media platforms');
    expect(dom).not.toContain('2 revisions per deliverable');

    // The honest replacement is actually visible, not silently dropped.
    expect(screen.getByText('Deadline')).toBeInTheDocument();
    // Scope "Not specified" to the Usage Rights / Revision Cap rows specifically.
    const usageRightsLabel = screen.getByText('Usage Rights');
    const revisionCapLabel = screen.getByText('Revision Cap');
    expect(usageRightsLabel.nextElementSibling?.textContent).toBe('Not specified');
    expect(revisionCapLabel.nextElementSibling?.textContent).toBe('Not specified');
  });

  it('renders the real deadline when the app was given one, never the hardcoded fallback', () => {
    render(
      <CreatorContractPanel
        open={true}
        onOpenChange={vi.fn()}
        event={makeEvent({
          contractId: 'CONT-882',
          brandName: 'Acme Co',
          campaignName: 'Diwali Drop',
          amount: 40000,
          contractStatus: 'generated',
          deadline: '2027-09-01',
        })}
        status="generated"
        onStatusChange={vi.fn()}
      />,
    );

    const deadlineLabel = screen.getByText('Deadline');
    expect(deadlineLabel.nextElementSibling?.textContent).toBe('2027-09-01');
    expect(document.body.textContent ?? '').not.toContain('2024-02-15');
  });
});
