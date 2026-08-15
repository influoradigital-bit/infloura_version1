/**
 * F-0213 (filename-param-ignored): downloadContractPDF(data, filename) accepted a
 * filename from 5 call sites and never honored it. The print-dialog flow's only
 * naming channel is document.title (browsers use it as the default print-to-PDF
 * filename), so the contract asserts the filename lands in the opened doc's <title>.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { downloadContractPDF, type ContractData } from '@/lib/contract-generator';

const DATA = {
  contractId: 'CT-42',
  campaignTitle: 'Test Campaign',
  brandName: 'Test Brand',
  creatorName: 'Test Creator',
  amount: 10000,
  deliverables: [],
  terms: [],
  customClauses: [],
} as unknown as ContractData;

describe('downloadContractPDF filename (F-0213)', () => {
  let capturedHtml = '';

  beforeEach(() => {
    capturedHtml = '';
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:mock'),
    });
    vi.stubGlobal('open', vi.fn(() => null));
    // Blob.text() capture is async; grab the HTML synchronously off the ctor instead.
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

  it('injects the filename (sans .pdf) as the document title', () => {
    downloadContractPDF(DATA, 'CT-42.pdf');
    expect(capturedHtml).toContain('<title>CT-42</title>');
  });

  it('defaults to "contract" when no filename is given', () => {
    downloadContractPDF(DATA);
    expect(capturedHtml).toContain('<title>contract</title>');
  });

  it('escapes HTML-significant characters in the filename', () => {
    downloadContractPDF(DATA, '<b>&x.pdf');
    expect(capturedHtml).toContain('<title>&lt;b>&amp;x</title>');
    expect(capturedHtml).not.toContain('<title><b>');
  });
});
