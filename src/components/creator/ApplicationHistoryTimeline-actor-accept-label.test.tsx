/**
 * ApplicationHistoryTimeline — APPLICATION_ACCEPTED label varies by actor (F-0302).
 *
 * CEO ruling 2026-08-18, Decision 3 (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md):
 * `DealService#doAccept` is a mutual proposal-accept, so the same event type
 * `APPLICATION_ACCEPTED` fires whether the BRAND accepted the creator's application or the
 * CREATOR accepted the brand's counter-offer. Showing "Application Accepted" with actor "You"
 * for the creator's own accept reads as nonsense ("you accepted your own application"). The
 * stored event type is unchanged (no schema migration) — only the display label varies:
 *   actorType BRAND   -> "Application Accepted"
 *   actorType CREATOR -> "Proposal Accepted"
 *
 * Run: npx vitest run src/components/creator/ApplicationHistoryTimeline-actor-accept-label.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ApplicationHistoryTimeline } from './ApplicationHistoryTimeline';
import type { CreatorApplicationHistoryEvent } from '@/lib/api';

const historyMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorApplications: {
        ...actual.api.creatorApplications,
        history: (...a: unknown[]) => historyMock(...a),
      },
    },
  };
});

function renderTimeline(dealId = 'deal_1', brandName = 'Glow Naturals') {
  return render(
    <MemoryRouter>
      <ApplicationHistoryTimeline dealId={dealId} brandName={brandName} />
    </MemoryRouter>,
  );
}

const BASE_EVENT: CreatorApplicationHistoryEvent = {
  historyId: 'h_accept',
  campaignId: 'camp_1',
  applicationId: 'app_1',
  dealRoomId: 'deal_1',
  eventType: 'APPLICATION_ACCEPTED',
  eventStatus: 'TERMS_AGREED',
  actorType: 'BRAND',
  actorId: 'brand_1',
  description: 'Brand accepted the proposal',
  createdAt: '2026-08-03T10:00:00.000Z',
};

describe('ApplicationHistoryTimeline — accept label varies by actor (F-0302)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows "Application Accepted" when the BRAND is the actor', async () => {
    historyMock.mockResolvedValue([{ ...BASE_EVENT, actorType: 'BRAND' }]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Application Accepted')).toBeInTheDocument());
    expect(screen.queryByText('Proposal Accepted')).not.toBeInTheDocument();
  });

  it('shows "Proposal Accepted" — not "Application Accepted" — when the CREATOR is the actor', async () => {
    historyMock.mockResolvedValue([
      { ...BASE_EVENT, actorType: 'CREATOR', actorId: 'cr_1', description: 'You accepted the proposal' },
    ]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Proposal Accepted')).toBeInTheDocument());
    // The nonsensical "you accepted your own application" wording must never render for this actor.
    expect(screen.queryByText('Application Accepted')).not.toBeInTheDocument();
  });
});
