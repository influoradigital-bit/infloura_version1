/**
 * F-0669 round 5 — the BRAND twin of
 * `src/components/creator/deal-room/__tests__/creator-contract-panel-terms-from-data.test.tsx`.
 *
 * `ContractPanel` is the brand-side timeline sheet, and its "Download PDF" button writes a
 * CONTRACT DOCUMENT. It is live and ungated: the component carries no `isApiLive`/mock guard,
 * and its render chain — `collaboration-timeline.tsx` -> `timeline-event.tsx` ->
 * `event-cards/contract-card.tsx` -> this panel — is mounted unconditionally from
 * `brand-campaign-detail.tsx:2495`, whose live branch feeds it the server's own
 * `DealMessage.metadata`.
 *
 * THE TWO FABRICATIONS THIS SPEC EXISTS TO CATCH
 * ----------------------------------------------
 *   campaignName : 'Summer Fashion'
 *       An invented campaign title, printed on the document under "CAMPAIGN:". Nothing this
 *       panel receives carries a campaign name — `DealService` writes `deliverables` and
 *       `deliverableCount` onto the deal-message metadata (DealService.java:2052-2053) and
 *       never a campaign title.
 *   deliverables : [2x 'Instagram Reel', 1x 'Instagram Story']
 *       A fixed two-row table baked into the downloadable contract of EVERY brand deal,
 *       whatever had actually been ordered — while `metadata.deliverables` carries the real
 *       `DeliverableSlot[]`.
 *
 * WHY THE ASSERTIONS ARE SHAPED THE WAY THEY ARE
 * ----------------------------------------------
 * A spec that fed the panel Instagram Reels would pass against the constants too, so every
 * case drives the component with data that DIFFERS from the constant it replaced (YouTube and
 * a carousel instead of reels and stories; "Diwali Drop" instead of "Summer Fashion") and
 * asserts BOTH halves: the real value is in the document, AND the constant is not. The
 * absent-data cases assert the honest shape — a deliverables table with nothing but its header
 * row, and no platform vocabulary anywhere in the document or the DOM — so a fabricated list
 * fails here wherever in the panel it is reintroduced.
 *
 * Run: npx vitest run src/components/brand/timeline/panels/__tests__/contract-panel-terms-from-data.test.tsx
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

const BASE: TimelineEvent['metadata'] = {
  contractId: 'CONT-901',
  brandName: 'Acme Co',
  creatorName: 'Rhea Kapoor',
  amount: 75000,
  contractStatus: 'generated',
};

/** Every platform/content word the two constants were written in. */
const FABRICATED_VOCABULARY = /Instagram|YouTube|TikTok|Facebook|Reel|Story|Carousel/i;

let capturedHtml = '';

beforeEach(() => {
  capturedHtml = '';
  vi.stubGlobal('URL', { ...URL, createObjectURL: vi.fn(() => 'blob:mock') });
  vi.stubGlobal('open', vi.fn(() => null));
  // Blob.text() is async; capture the HTML synchronously off the ctor instead (same
  // technique as the sibling contract-panel-honest-terms.test.tsx).
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

function renderPanel(metadata: TimelineEvent['metadata']) {
  render(<ContractPanel open={true} onOpenChange={vi.fn()} event={makeEvent(metadata)} />);
}

function renderAndDownload(metadata: TimelineEvent['metadata']) {
  renderPanel(metadata);
  fireEvent.click(screen.getByRole('button', { name: /download pdf/i }));
}

/**
 * Just the DELIVERABLES table of the generated document, so the FINANCIAL TERMS rows below it
 * can never be mistaken for deliverable rows.
 */
function deliverablesSection(html: string): string {
  const start = html.indexOf('DELIVERABLES');
  const end = html.indexOf('FINANCIAL TERMS');
  expect(start).toBeGreaterThan(-1);
  expect(end).toBeGreaterThan(start);
  return html.slice(start, end);
}

function rowCount(section: string): number {
  return (section.match(/<tr>/g) ?? []).length;
}

function body(): string {
  return document.body.textContent ?? '';
}

describe('ContractPanel document — deliverables come from data, never from a constant', () => {
  it("writes the brand's real slots — deliberately NOT the Instagram pair the constant named", () => {
    renderAndDownload({
      ...BASE,
      deliverables: [
        { type: 'YOUTUBE_VIDEO', qty: 3 },
        { type: 'INSTAGRAM_CAROUSEL', qty: 1 },
      ],
      deliverableCount: 4,
    });

    const section = deliverablesSection(capturedHtml);
    // Header row + one row per real slot. Anti-vacuity: the table is not empty.
    expect(rowCount(section)).toBe(3);
    expect(section).toContain('YouTube Video');
    expect(section).toContain('Instagram Carousel');
    expect(section).toMatch(/<td>YouTube Video<\/td>\s*<td><\/td>\s*<td>3<\/td>/);
    expect(section).toMatch(/<td>Instagram Carousel<\/td>\s*<td><\/td>\s*<td>1<\/td>/);

    // The constant's own vocabulary. This deal ordered neither, so either appearing means
    // something other than the data put it there.
    expect(capturedHtml).not.toContain('Instagram Reel');
    expect(capturedHtml).not.toContain('Instagram Story');
    expect(capturedHtml).not.toContain('High-quality reel');
    expect(capturedHtml).not.toContain('Story series');
  });

  it('writes an EMPTY deliverables table when the message carries no slots, rather than inventing rows', () => {
    renderAndDownload(BASE);

    const section = deliverablesSection(capturedHtml);
    // Header row only — an absent row makes no claim, an invented one does.
    expect(rowCount(section)).toBe(1);
    expect(section).not.toMatch(FABRICATED_VOCABULARY);
    // Nowhere else in the document either.
    expect(capturedHtml).not.toMatch(FABRICATED_VOCABULARY);
    // Anti-vacuity: the document really was generated and still carries its real fields.
    expect(capturedHtml).toContain('CONT-901');
    expect(capturedHtml).toContain('Rhea Kapoor');
    expect(capturedHtml).toContain('Acme Co');
  });

  it('carries the plain count shape of pre-2026-07-26 messages without inventing a breakdown', () => {
    renderAndDownload({ ...BASE, deliverables: 5 });

    const section = deliverablesSection(capturedHtml);
    // A bare number is not a slot list — there is nothing to itemise, so nothing is itemised.
    expect(rowCount(section)).toBe(1);
    expect(capturedHtml).not.toMatch(FABRICATED_VOCABULARY);
  });
});

describe('ContractPanel document — campaign name comes from data, never from a constant', () => {
  it('writes the real campaign name when the message carries one', () => {
    renderAndDownload({ ...BASE, campaignName: 'Diwali Drop' });

    expect(capturedHtml).toMatch(/CAMPAIGN:<\/span>\s*Diwali Drop/);
    expect(capturedHtml).not.toContain('Summer Fashion');
  });

  it('writes an honest "Not specified" when it does not — never an invented campaign', () => {
    renderAndDownload(BASE);

    expect(capturedHtml).toMatch(/CAMPAIGN:<\/span>\s*Not specified/);
    expect(capturedHtml).not.toContain('Summer Fashion');
    // The general form: no campaign-shaped title other than the honest placeholder.
    expect(capturedHtml).not.toMatch(/CAMPAIGN:<\/span>\s*(?!Not specified)\S/);
  });
});

describe('ContractPanel on screen — the clause list states only what the data supports', () => {
  it('states the real number of pieces the message carries', () => {
    renderPanel({
      ...BASE,
      deliverables: [{ type: 'YOUTUBE_SHORT', qty: 2 }],
      deliverableCount: 2,
    });

    expect(
      screen.getByText(/Creator shall deliver 2 pieces of content as agreed/),
    ).toBeInTheDocument();
  });

  it('names no deliverable, campaign or payment split when the message carries none', () => {
    renderPanel(BASE);

    const dom = body();
    // No platform/content vocabulary has any other reason to appear in this sheet, so this
    // catches a fabricated deliverable list wherever it is reintroduced.
    expect(dom).not.toMatch(FABRICATED_VOCABULARY);
    expect(dom).not.toContain('Summer Fashion');
    // A payment split is a term nothing here carries (the creator-side twin of this row was
    // "50% upfront (secured), 50% on completion").
    expect(dom).not.toMatch(/\d+\s*%\s*(?:upfront|up\s?front|advance|in\s+advance)/i);
    expect(dom).not.toMatch(/\d+\s*%\s*on\s+completion/i);
    // The honest replacement is actually visible, not silently dropped — this is not vacuous.
    expect(
      screen.getByText(/Creator shall deliver the agreed pieces of content as agreed/),
    ).toBeInTheDocument();
  });
});

/**
 * The fabricated contract reference. `'CONT-001'` was the fallback contract ID for the PDF
 * payload AND the on-screen Contract ID row — and because the backend never writes `contractId`
 * into the deal-message metadata, the fallback won on EVERY live contract. Every other case in
 * this spec supplies a contractId, which is how it slipped: none ever rendered the missing case.
 */
const FABRICATED_REFERENCE = /\b(?:CONT|CTR|CON|CNT)-?\d+/i;

describe('ContractPanel — contract reference is never invented when the message carries none', () => {
  function withoutContractId(): TimelineEvent['metadata'] {
    const { contractId: _omit, ...rest } = BASE ?? {};
    void _omit;
    return rest;
  }

  it('writes an honest "Not available" into the generated document, never a CONT- number', () => {
    const meta = withoutContractId();
    expect(meta?.contractId).toBeUndefined(); // the case under test really is the missing one
    renderAndDownload(meta);

    // Anti-vacuity: the document really was generated.
    expect(capturedHtml).toContain('Rhea Kapoor');
    expect(capturedHtml).toMatch(/Contract ID:\s*Not available/);
    expect(capturedHtml).not.toContain('CONT-001');
    expect(capturedHtml).not.toMatch(FABRICATED_REFERENCE);
    // The download title falls back to a generic name, not a reference number.
    expect(capturedHtml).toMatch(/<title>contract<\/title>/);
  });

  it('shows "Not available" in the on-screen Contract ID row, never a CONT- number', () => {
    renderPanel(withoutContractId());

    expect(screen.getByText('Contract ID').nextElementSibling?.textContent).toBe('Not available');
    expect(body()).not.toContain('CONT-001');
    expect(body()).not.toMatch(FABRICATED_REFERENCE);
  });
});
