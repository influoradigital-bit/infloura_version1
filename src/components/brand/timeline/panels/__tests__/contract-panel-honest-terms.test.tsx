/**
 * F-0669 (fabricated-legal-terms) — remaining caller. `ContractPanel` (the brand-side
 * timeline panel) built its `contractData` object for "Download PDF" with FIVE invented
 * values:
 *
 *   creatorName: 'Priya Sharma'                        <-- a fabricated HUMAN NAME
 *   deadline: meta?.deadline || '2024-02-15'           <-- hardcoded past date
 *   usageRights: '6 months on social media platforms'
 *   exclusivity: 'No exclusivity agreement'
 *   revisionCap: 2
 *
 * This component only actually knows what's on `event.metadata` — which does carry an
 * optional `creatorName` and `deadline`, but never `usageRights`/`exclusivity`/
 * `revisionCap`. A brand reviewing this document was handed a document putting a real
 * person's invented name on a legal artifact, and asserting terms nobody agreed to.
 *
 * This spec renders the real component, drives the real "Download PDF" click path, and
 * inspects the ACTUAL generated document HTML (same Blob-capture technique as the
 * already-fixed deal-contract-tab-honest-terms.test.tsx) — not just that the component
 * renders.
 *
 * Run: npx vitest run src/components/brand/timeline/panels/__tests__/contract-panel-honest-terms.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
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

describe('ContractPanel download — F-0669 no fabricated legal terms', () => {
  let capturedHtml = '';

  beforeEach(() => {
    capturedHtml = '';
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:mock'),
    });
    vi.stubGlobal('open', vi.fn(() => null));
    // Blob.text() capture is async; grab the HTML synchronously off the ctor instead
    // (same technique as deal-contract-tab-honest-terms.test.tsx).
    vi.stubGlobal(
      'Blob',
      class MockBlob {
        parts: unknown[];
        type: string;
        constructor(parts: unknown[], opts?: { type?: string }) {
          this.parts = parts;
          this.type = opts?.type ?? '';
          capturedHtml = String(parts[0]);
        }
      },
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function renderAndDownload(metadata: TimelineEvent['metadata']) {
    render(
      <ContractPanel open={true} onOpenChange={vi.fn()} event={makeEvent(metadata)} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /download pdf/i }));
  }

  describe('when the app was not given creatorName/deadline (only amount)', () => {
    const metadata: TimelineEvent['metadata'] = {
      contractId: 'CONT-555',
      campaignName: 'Diwali Drop',
      amount: 75000,
      contractStatus: 'generated',
    };

    it('never renders the fabricated human name ("Priya Sharma")', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).not.toContain('Priya Sharma');
    });

    it('never renders the hardcoded past deadline ("2024-02-15" / its formatted form)', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).not.toContain('2024-02-15');
      expect(capturedHtml).not.toContain(new Date('2024-02-15').toLocaleDateString('en-IN'));
    });

    it('never renders the fabricated usage-rights value ("6 months on social media platforms")', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).not.toContain('6 months on social media platforms');
    });

    it('never renders the fabricated exclusivity value ("No exclusivity agreement")', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).not.toContain('No exclusivity agreement');
    });

    it('never renders a fabricated revision cap ("up to 2 revision rounds")', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).not.toMatch(/up to 2 revision/i);
    });

    it('renders an honest "Not specified" for each term the app was not given, and a neutral (non-person) label for the creator', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).toMatch(/Delivery Deadline:<\/strong>\s*Not specified\.?/);
      expect(capturedHtml).toMatch(/Usage Rights:<\/strong>\s*Not specified/);
      expect(capturedHtml).toMatch(/Exclusivity:<\/strong>\s*Not specified/);
      expect(capturedHtml).toMatch(/Revisions:<\/strong>\s*Not specified\.?/);
      // Neutral role label, not a specific invented human being.
      expect(capturedHtml).toMatch(/CREATOR:<\/span>\s*Creator/);
    });

    it('still renders the real, known fields (contractId, campaign, amount) — this is not a vacuous check', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).toContain('CONT-555');
      expect(capturedHtml).toContain('Diwali Drop');
      expect(capturedHtml).toContain('₹75,000');
    });
  });

  describe('when the app WAS given a real creatorName and deadline', () => {
    const metadata: TimelineEvent['metadata'] = {
      contractId: 'CONT-556',
      campaignName: 'Diwali Drop',
      creatorName: 'Rhea Kapoor',
      deadline: '2026-03-15',
      amount: 75000,
      contractStatus: 'generated',
    };

    it('renders the real creator name instead of any fallback or the fabricated one', () => {
      renderAndDownload(metadata);
      expect(capturedHtml).toContain('Rhea Kapoor');
      expect(capturedHtml).not.toContain('Priya Sharma');
      expect(capturedHtml).not.toMatch(/CREATOR:<\/span>\s*Creator\s*</);
    });

    it('renders the real deadline instead of the hardcoded past date', () => {
      renderAndDownload(metadata);
      const expectedFormatted = new Date('2026-03-15').toLocaleDateString('en-IN');
      expect(capturedHtml).toContain(expectedFormatted);
      expect(capturedHtml).not.toContain('2024-02-15');
    });
  });
});
