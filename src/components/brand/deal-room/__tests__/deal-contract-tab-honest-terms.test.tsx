/**
 * F-0669 (fabricated-legal-terms) — the BRAND-side twin of F-0666. The pre-signature
 * client-side PDF fallback in `DealContractTab` (reached whenever a brand clicks
 * "Download PDF" in mock mode) built a `demoContractData` object with FOUR invented
 * legal terms:
 *
 *   deadline: now + 14 days (a made-up date)
 *   usageRights: '6 months on social media platforms'
 *   exclusivity: 'As per campaign brief'
 *   revisionCap: 2
 *
 * This component only actually knows contractId/creatorName/campaignName/dealValue
 * (plus whatever `contractRecord` it fetches, which carries `terms`/`effectiveDate`/
 * `expirationDate` — none of which are the same claim as these four clauses). A brand
 * reviewing this document was handed a document asserting legal terms nobody agreed
 * to — and because the creator-side copy (F-0666) already renders "Not specified" for
 * these same four fields, the two parties' copies of the "same" contract disagreed.
 *
 * This spec renders the real component, drives the real "Download PDF" click path
 * (mock mode is forced in vitest.config.ts, so this always takes the client-side
 * generator branch), and inspects the ACTUAL generated document HTML — not just that
 * the component renders.
 *
 * Run: npx vitest run src/components/brand/deal-room/__tests__/deal-contract-tab-honest-terms.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { DealContractTab } from '../deal-contract-tab';

describe('DealContractTab download — F-0669 no fabricated legal terms', () => {
  let capturedHtml = '';

  beforeEach(() => {
    capturedHtml = '';
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:mock'),
    });
    vi.stubGlobal('open', vi.fn(() => null));
    // Blob.text() capture is async; grab the HTML synchronously off the ctor instead
    // (same technique as contract-generator-filename.test.ts / the creator-side spec).
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

  async function renderAndDownload() {
    render(
      <DealContractTab
        dealId="deal-1"
        creatorName="Priya Sharma"
        campaignName="Summer Launch"
        dealValue={50000}
        contractId="CTR-777"
        status="generated"
        onStatusChange={vi.fn()}
      />,
    );
    // Wait past the contract-record fetch so the Download control is settled, same as
    // the panel's other specs (demo-sign-flow-alive.test.tsx) — the button is present
    // throughout, but let the effect resolve before clicking.
    await waitFor(() =>
      expect(screen.queryByText(/loading contract terms/i)).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole('button', { name: /download pdf/i }));
  }

  it('never renders the fabricated usage-rights value ("6 months on social media platforms")', async () => {
    await renderAndDownload();
    expect(capturedHtml).not.toContain('6 months on social media platforms');
  });

  it('never renders the fabricated exclusivity value ("As per campaign brief")', async () => {
    await renderAndDownload();
    expect(capturedHtml).not.toContain('As per campaign brief');
  });

  it('never renders a fabricated revision cap ("up to 2 revision rounds")', async () => {
    await renderAndDownload();
    expect(capturedHtml).not.toMatch(/up to 2 revision/i);
  });

  it('never renders a fabricated deadline (14 days from today)', async () => {
    await renderAndDownload();
    const fabricatedDeadline = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000)
      .toISOString()
      .slice(0, 10);
    // The old code formatted this ISO date via toLocaleDateString('en-IN') into the
    // document — assert neither the raw ISO value nor that formatted date appear.
    expect(capturedHtml).not.toContain(fabricatedDeadline);
    expect(capturedHtml).not.toContain(new Date(fabricatedDeadline).toLocaleDateString('en-IN'));
  });

  it('renders an honest "Not specified" for each term the app was not given', async () => {
    await renderAndDownload();
    // Delivery Deadline, Usage Rights, Exclusivity, and Revisions should each read
    // "Not specified" rather than silently vanishing, crashing, or showing an invented value.
    expect(capturedHtml).toMatch(/Delivery Deadline:<\/strong>\s*Not specified\.?/);
    expect(capturedHtml).toMatch(/Usage Rights:<\/strong>\s*Not specified/);
    expect(capturedHtml).toMatch(/Exclusivity:<\/strong>\s*Not specified/);
    expect(capturedHtml).toMatch(/Revisions:<\/strong>\s*Not specified\.?/);
  });

  it('still renders the real, known fields (contractId, creator, campaign, amount) — this is not a vacuous check', async () => {
    await renderAndDownload();
    expect(capturedHtml).toContain('CTR-777');
    expect(capturedHtml).toContain('Priya Sharma');
    expect(capturedHtml).toContain('Summer Launch');
    expect(capturedHtml).toContain('₹50,000');
  });
});
