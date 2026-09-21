/**
 * F-0669 round 4 — the three numbers on this sheet must come from DATA, not from constants.
 *
 * `CreatorContractPanel` is the sheet a creator reads immediately before typing their legal name
 * and signing. It is live and ungated: the component carries no `isApiLive`/mock guard, and its
 * only render site (`creator-chat.tsx:3312`) is gated on `hasContract`, which is derived from the
 * real deal. Everything below therefore shipped to real creators.
 *
 * THE THREE FABRICATIONS THIS SPEC EXISTS TO CATCH
 * ------------------------------------------------
 *   Payment Terms : '50% upfront (secured), 50% on completion'
 *       Presented under a "Key Terms" heading as this creator's signed payment schedule.
 *       Nothing in the contract event, the collaboration, or any API this page calls carries a
 *       payment split — the figure was invented. The comment on the very next field already
 *       forbade fabricating terms; this row sat one line above it.
 *   Deliverables  : '2 Instagram Reels, 1 Instagram Story'
 *       The same sentence for every contract on the platform, while `metadata.deliverables`
 *       carries the brand's real `DeliverableSlot[]` (DealService.java:1132).
 *   Platform fee  : a literal 'Platform Fee (15%)' label, `amount * 0.15`, and a "You Receive"
 *       of `amount * 0.85` — while `GET /creator/platform-fee` (api.ts:3755) returns the
 *       configurable rate, and the admin Fee Control panel exists precisely to change it.
 *
 * WHY THE ASSERTIONS ARE SHAPED THE WAY THEY ARE
 * ----------------------------------------------
 * A spec that fed the panel Instagram Reels and a 15% fee would pass against the fabricated
 * constants too — it would be testing nothing. So every case here drives the component with data
 * that DIFFERS from the constant it replaced (YouTube instead of Instagram, 12%/7.5% instead of
 * 15%) and asserts both halves: the real value is on screen, AND the constant is not. The last
 * case is the general form — every percentage rendered anywhere in the sheet must equal the rate
 * the server served — so a future hardcoded percentage fails here without anyone having to
 * predict which one it will be.
 *
 * Run: npx vitest run src/components/creator/deal-room/__tests__/creator-contract-panel-terms-from-data.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { CreatorContractPanel } from '../creator-contract-panel';
import { api } from '@/lib/api';
import { formatINR } from '@/lib/utils';
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
  contractId: 'CONT-881',
  brandName: 'Acme Co',
  campaignName: 'Diwali Drop',
  amount: 40000,
  contractStatus: 'generated',
};

function renderPanel(metadata: TimelineEvent['metadata']) {
  render(
    <CreatorContractPanel
      open={true}
      onOpenChange={vi.fn()}
      event={makeEvent(metadata)}
      status="generated"
      onStatusChange={vi.fn()}
    />,
  );
}

/** The value cell of a "Key Terms" / "Contract Details" row, addressed by its label. */
function valueOf(label: string): string | null | undefined {
  return screen.queryByText(label)?.nextElementSibling?.textContent;
}

function body(): string {
  return document.body.textContent ?? '';
}

let feeSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // Silence the component's deliberate console.error on a failed fee fetch.
  vi.spyOn(console, 'error').mockImplementation(() => {});
  feeSpy = vi.spyOn(api.wallet, 'platformFee');
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('CreatorContractPanel — Payment Terms', () => {
  beforeEach(() => {
    feeSpy.mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT' });
  });

  it('never renders a payment split: no data backs one, so the row is honest-blank', async () => {
    renderPanel(BASE);
    await screen.findByText(/Platform Fee/);

    expect(valueOf('Payment Terms')).toBe('Not specified');

    const dom = body();
    // The exact string that shipped, and the shape of any replacement for it.
    expect(dom).not.toContain('50% upfront (secured), 50% on completion');
    expect(dom).not.toMatch(/\d+\s*%\s*(?:upfront|up\s?front|advance|in\s+advance)/i);
    expect(dom).not.toMatch(/\d+\s*%\s*on\s+completion/i);
  });
});

describe('CreatorContractPanel — Deliverables', () => {
  beforeEach(() => {
    feeSpy.mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT' });
  });

  it("renders the brand's real slots — deliberately NOT the Instagram list the constant named", async () => {
    renderPanel({
      ...BASE,
      deliverables: [
        { type: 'YOUTUBE_VIDEO', qty: 3 },
        { type: 'INSTAGRAM_CAROUSEL', qty: 1 },
      ],
      deliverableCount: 4,
    });
    await screen.findByText(/Platform Fee/);

    expect(valueOf('Deliverables')).toBe('3x YouTube Video · 1x Instagram Carousel');

    const dom = body();
    expect(dom).not.toContain('2 Instagram Reels, 1 Instagram Story');
    // The constant's own vocabulary. This deal ordered neither, so either word appearing means
    // something other than the data put it there.
    expect(dom).not.toContain('Instagram Reel');
    expect(dom).not.toContain('Instagram Story');
  });

  it('falls back to the real count for pre-2026-07-26 messages that stored only a number', async () => {
    renderPanel({ ...BASE, deliverables: 5 });
    await screen.findByText(/Platform Fee/);

    expect(valueOf('Deliverables')).toBe('5 pieces');
    expect(body()).not.toContain('Instagram');
  });

  it('drops the row entirely when the message carries no deliverable data', async () => {
    renderPanel(BASE);
    await screen.findByText(/Platform Fee/);

    expect(screen.queryByText('Deliverables')).toBeNull();
    // No platform name has any other reason to appear in this sheet, so this catches a
    // fabricated list wherever in the panel it is reintroduced.
    expect(body()).not.toMatch(/Instagram|YouTube|TikTok|Facebook|Reel|Story\b/i);
  });
});

describe('CreatorContractPanel — platform fee and take-home', () => {
  it('uses the rate the server actually serves, never a hardcoded 15%', async () => {
    feeSpy.mockResolvedValue({ feeBps: 1200, feePercent: 12, source: 'WORKSPACE_OVERRIDE' });
    renderPanel(BASE);

    await screen.findByText('Platform Fee (12%)');

    const dom = body();
    expect(dom).toContain(`-${formatINR(4800)}`); // 12% of 40,000
    expect(dom).toContain(formatINR(35200)); // the real take-home
    // The two figures the constants produced for this same amount.
    expect(dom).not.toContain('15%');
    expect(dom).not.toContain(formatINR(34000));
    expect(dom).not.toContain(formatINR(6000));
  });

  it('hides the fee and take-home rows when the fee call fails, rather than assuming 15%', async () => {
    feeSpy.mockRejectedValue(new Error('network down'));
    renderPanel(BASE);

    await screen.findByText(/take-home figure isn't shown/i);

    expect(screen.queryByText(/Platform Fee/)).toBeNull();
    expect(screen.queryByText('You Receive')).toBeNull();
    const dom = body();
    expect(dom).not.toContain('15%');
    expect(dom).not.toContain(formatINR(34000));
    // Contract Value is still shown — the panel is not blanked wholesale, so this is not a
    // vacuous pass.
    expect(dom).toContain(formatINR(40000));
  });

  it('hides them while the fee is still in flight, so no rate is shown before one is known', () => {
    feeSpy.mockReturnValue(new Promise(() => {})); // never settles
    renderPanel(BASE);

    expect(screen.queryByText(/Platform Fee/)).toBeNull();
    expect(screen.queryByText('You Receive')).toBeNull();
    expect(body()).not.toMatch(/\d+(?:\.\d+)?\s*%/);
  });

  it('EVERY percentage rendered in the sheet equals the served rate', async () => {
    // The general form of all three fabrications: a percentage on this screen that no response
    // produced. A fractional rate is used on purpose — a hardcoded 15, a 50/50 split, or an
    // 85% take-home all read as a different number than the one the server sent.
    feeSpy.mockResolvedValue({ feeBps: 750, feePercent: 7.5, source: 'WORKSPACE_OVERRIDE' });
    renderPanel({ ...BASE, deliverables: [{ type: 'YOUTUBE_SHORT', qty: 2 }] });

    await screen.findByText('Platform Fee (7.50%)');

    await waitFor(() => {
      const percentages = [...body().matchAll(/(\d+(?:\.\d+)?)\s*%/g)].map((m) => m[1]);
      // Anti-vacuity: the scan found the one legitimate percentage.
      expect(percentages).toContain('7.50');
      expect(percentages.every((p) => p === '7.50')).toBe(true);
    });
  });
});

/**
 * The fabricated contract reference. `'CONT-001'` was the fallback contract ID written into the
 * downloadable contract document — and because the backend never writes `contractId` into the
 * deal-message metadata, the fallback won on EVERY live contract. On this side the fabrication
 * only ever reached the DOCUMENT (the on-screen row already said 'Not specified'), so the
 * assertion is on the generated HTML, captured off the Blob constructor.
 */
describe('CreatorContractPanel — contract reference is never invented when the message carries none', () => {
  const FABRICATED_REFERENCE = /\b(?:CONT|CTR|CON|CNT)-?\d+/i;
  let capturedHtml = '';

  function withoutContractId(): TimelineEvent['metadata'] {
    const { contractId: _omit, ...rest } = BASE ?? {};
    void _omit;
    return rest;
  }

  beforeEach(() => {
    capturedHtml = '';
    feeSpy.mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT' });
    vi.stubGlobal('URL', { ...URL, createObjectURL: vi.fn(() => 'blob:mock') });
    vi.stubGlobal('open', vi.fn(() => null));
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

  it('writes an honest "Not available" into the generated document, never a CONT- number', async () => {
    const meta = withoutContractId();
    expect(meta?.contractId).toBeUndefined(); // the case under test really is the missing one
    renderPanel(meta);
    await screen.findByText(/Platform Fee/);

    fireEvent.click(screen.getByRole('button', { name: /download pdf/i }));

    // Anti-vacuity: the document really was generated from this message.
    expect(capturedHtml).toContain('Acme Co');
    expect(capturedHtml).toContain('Diwali Drop');
    expect(capturedHtml).toMatch(/Contract ID:\s*Not available/);
    expect(capturedHtml).not.toContain('CONT-001');
    expect(capturedHtml).not.toMatch(FABRICATED_REFERENCE);
    expect(capturedHtml).toMatch(/<title>contract<\/title>/);

    // On screen: the honest blank, and no reference number anywhere in the sheet.
    expect(valueOf('Contract ID')).toBe('Not specified');
    expect(body()).not.toMatch(FABRICATED_REFERENCE);
  });
});
