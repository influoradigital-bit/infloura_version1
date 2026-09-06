/**
 * F-0666 (fabricated-legal-terms): the pre-signature client-side PDF fallback in
 * `CreatorDealContractTab` (reached whenever a creator clicks "Download PDF" in mock
 * mode, or when the live presigned-URL fetch fails before both parties have signed)
 * built a `contractData` object with FOUR invented legal terms:
 *
 *   deadline: now + 14 days (a made-up date)
 *   usageRights: '6 months'
 *   exclusivity: 'Per brief'
 *   revisionCap: 2
 *
 * The component only actually knows contractId/brandName/campaignName/amount — it has
 * no prop carrying the real usage rights, exclusivity, revision cap, or deadline. A
 * creator reviewing this document BEFORE signing was handed a document asserting legal
 * terms nobody agreed to.
 *
 * This spec renders the real component, drives the real "Download PDF" click path
 * (mock mode is forced in vitest.config.ts, so this always takes the client-side
 * generator branch), and inspects the ACTUAL generated document HTML — not just that
 * the component renders.
 *
 * Run: npx vitest run src/components/creator/deal-room/__tests__/creator-deal-contract-tab-honest-terms.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CreatorDealContractTab } from '../creator-deal-contract-tab';

describe('CreatorDealContractTab download — F-0666 no fabricated legal terms', () => {
  let capturedHtml = '';

  beforeEach(() => {
    capturedHtml = '';
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:mock'),
    });
    vi.stubGlobal('open', vi.fn(() => null));
    // Blob.text() capture is async; grab the HTML synchronously off the ctor instead
    // (same technique as contract-generator-filename.test.ts).
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

  function renderAndDownload() {
    render(
      <CreatorDealContractTab
        contractId="CTR-777"
        brandName="Acme Co"
        campaignName="Summer Launch"
        amount={20000}
        contractAmount={20000}
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /download pdf/i }));
  }

  it('never renders the fabricated usage-rights value ("6 months")', () => {
    renderAndDownload();
    expect(capturedHtml).not.toContain('6 months');
  });

  it('never renders the fabricated exclusivity value ("Per brief")', () => {
    renderAndDownload();
    expect(capturedHtml).not.toContain('Per brief');
  });

  it('never renders a fabricated revision cap ("up to 2 revision rounds")', () => {
    renderAndDownload();
    expect(capturedHtml).not.toMatch(/up to 2 revision/i);
  });

  it('never renders a fabricated deadline (14 days from today)', () => {
    renderAndDownload();
    const fabricatedDeadline = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000)
      .toISOString()
      .slice(0, 10);
    // The old code formatted this ISO date via toLocaleDateString('en-IN') into the
    // document — assert neither the raw ISO value nor that formatted date appear.
    expect(capturedHtml).not.toContain(fabricatedDeadline);
    expect(capturedHtml).not.toContain(new Date(fabricatedDeadline).toLocaleDateString('en-IN'));
  });

  it('renders an honest "Not specified" for each term the app was not given', () => {
    renderAndDownload();
    // Delivery Deadline, Usage Rights, Exclusivity, and Revisions should each read
    // "Not specified" rather than silently vanishing or crashing on missing data.
    expect(capturedHtml).toMatch(/Delivery Deadline:<\/strong>\s*Not specified\.?/);
    expect(capturedHtml).toMatch(/Usage Rights:<\/strong>\s*Not specified/);
    expect(capturedHtml).toMatch(/Exclusivity:<\/strong>\s*Not specified/);
    expect(capturedHtml).toMatch(/Revisions:<\/strong>\s*Not specified\.?/);
  });

  it('still renders the real, known fields (contractId, brand, campaign, amount) — this is not a vacuous check', () => {
    renderAndDownload();
    expect(capturedHtml).toContain('CTR-777');
    expect(capturedHtml).toContain('Acme Co');
    expect(capturedHtml).toContain('Summer Launch');
    expect(capturedHtml).toContain('₹20,000');
  });
});
