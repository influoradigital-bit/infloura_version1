/**
 * F-0669 round 5 (sibling sweep) — the deliverable review sheet and its timeline card must say
 * only what the event carries.
 *
 * Both are live and ungated: the card is rendered by `timeline-event.tsx` for every
 * `deliverable`-tagged event, and it mounts `DeliverableReviewPanel` with no mode guard. The
 * three fallbacks this spec exists to catch were:
 *
 *   heading      : `Reel #{n || 1} — {platform || 'INSTAGRAM'}` (sheet) and
 *                  `{platformEmojis[platform || 'instagram']} Reel #{n || 1}` (card) — a content
 *                  type, a platform and an index for a deliverable the app knew nothing about.
 *   File         : `submittedFilename || 'reel-1.mp4'` — a file that does not exist, under a
 *                  heading that says "File".
 *   Revisions    : `revisionCount || 0}/{revisionLimit || 2` — a two-revision cap asserted as a
 *                  fact. A revision cap is a contract term; contract-generator.ts renders an
 *                  absent one as "Not specified".
 *
 * As in the contract-panel twin, the with-data cases use values that DIFFER from the constants
 * (YouTube, #3, a four-revision cap) so they cannot pass against the fallbacks by coincidence.
 *
 * Run: npx vitest run src/components/brand/timeline/panels/__tests__/deliverable-review-panel-honest-identity.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DeliverableReviewPanel, deliverableHeading } from '../deliverable-review-panel';
import { DeliverableEventCard } from '../../event-cards/deliverable-card';
import type { TimelineEvent } from '@/lib/types';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

function makeEvent(metadata: TimelineEvent['metadata']): TimelineEvent {
  return {
    id: 'evt-1',
    collaborationId: 'deal-1',
    timestamp: new Date('2026-09-01T10:00:00Z'),
    senderId: 'creator-1',
    senderType: 'creator',
    tag: 'deliverable',
    status: 'delivered',
    metadata,
  };
}

/** Only the id — no number, platform, filename, count or cap. */
const BARE: TimelineEvent['metadata'] = { deliverableId: 'dlv-1', deliverableStatus: 'submitted' };

const FULL: TimelineEvent['metadata'] = {
  deliverableId: 'dlv-2',
  deliverableStatus: 'submitted',
  deliverableNumber: 3,
  platform: 'youtube',
  submittedFilename: 'launch-cut-v2.mov',
  revisionCount: 1,
  revisionLimit: 4,
};

function body(): string {
  return document.body.textContent ?? '';
}

function renderPanel(metadata: TimelineEvent['metadata']) {
  render(
    <DeliverableReviewPanel
      open
      onOpenChange={() => {}}
      event={makeEvent(metadata)}
      currentUserType="brand"
    />,
  );
}

describe('deliverableHeading', () => {
  it('names no type, platform or index the event did not carry', () => {
    expect(deliverableHeading(BARE)).toBe('Deliverable');
    expect(deliverableHeading(undefined)).toBe('Deliverable');
  });

  it('uses the real index and platform when present', () => {
    expect(deliverableHeading(FULL)).toBe('Deliverable #3 — YOUTUBE');
    expect(deliverableHeading({ deliverableNumber: 2 })).toBe('Deliverable #2');
  });
});

describe('DeliverableReviewPanel — identity comes from data', () => {
  it('invents no content type, platform, file name or revision cap for a bare event', () => {
    renderPanel(BARE);

    expect(screen.getByText('Deliverable', { exact: true })).toBeInTheDocument();
    const dom = body();
    expect(dom).not.toMatch(/Reel|Instagram/i);
    expect(dom).not.toContain('reel-1.mp4');
    // The File row is omitted rather than filled in.
    expect(screen.queryByText('File')).toBeNull();
    // Count shown, no invented denominator.
    expect(screen.getByText('Revisions Used').nextElementSibling?.textContent).toBe('0');
    expect(dom).not.toMatch(/\d+\s*\/\s*2\b/);
  });

  it('shows the real index, platform, file and cap when the event carries them', () => {
    renderPanel(FULL);

    expect(screen.getByText('Deliverable #3 — YOUTUBE')).toBeInTheDocument();
    expect(screen.getByText('launch-cut-v2.mov')).toBeInTheDocument();
    expect(screen.getByText('Revisions Used').nextElementSibling?.textContent).toBe('1/4');
    const dom = body();
    expect(dom).not.toMatch(/Reel|INSTAGRAM/);
    expect(dom).not.toContain('reel-1.mp4');
  });
});

describe('DeliverableEventCard — the card agrees with the sheet', () => {
  it('invents no Instagram icon, "Reel" or index for a bare event', () => {
    render(<DeliverableEventCard event={makeEvent(BARE)} currentUserType="brand" />);

    const dom = body();
    expect(dom).toContain('Deliverable');
    expect(dom).not.toMatch(/Reel/);
    expect(dom).not.toContain('📷');
  });

  it('uses the real platform icon and index when present', () => {
    render(<DeliverableEventCard event={makeEvent(FULL)} currentUserType="brand" />);

    expect(body()).toContain('Deliverable #3 — YOUTUBE');
    expect(body()).toContain('▶️');
    expect(body()).not.toContain('📷');
  });
});
