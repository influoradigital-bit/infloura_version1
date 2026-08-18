/**
 * ApplicationHistoryTimeline — My Applications journey feed
 * (GET /creator/applications/:dealId/history).
 *
 * Covers:
 *  - events render in the exact order the API returned them (server contract is
 *    chronological/oldest-first — this component must not silently reorder it)
 *  - loading, error (with working retry), and empty states each render honestly
 *  - an event carrying a `targetRoute` renders a working CTA link
 *  - the server-computed `dealPhase` renders as its own badge when present, and is never
 *    coerced into a fabricated phase when the server sends `null`
 *  - `metadata` renders only for the two event types where it is a genuine human reason
 *    (APPLICATION_REJECTED/APPLICATION_WITHDRAWN) — the other six event types send `null` for
 *    it today, and a guard test pins that an opaque entity id in that slot (their old, since-
 *    fixed behaviour) still can't leak to a creator if a call site ever regresses
 *  - the status badge text comes from application-status.ts's canonical
 *    getApplicationStatusLabel, never a bypassed prettify() of the raw enum — pinned
 *    against the map's own output, not a hardcoded string, for CANCELLED and one other status
 *
 * Run: npx vitest run src/components/creator/ApplicationHistoryTimeline.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ApplicationHistoryTimeline } from './ApplicationHistoryTimeline';
import { ApiError } from '@/lib/api';
import type { CreatorApplicationHistoryEvent } from '@/lib/api';
import { getApplicationStatusLabel } from '@/lib/application-status';

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

// Fixtures below are transcribed field-for-field from the real backend emission sites, not
// from what this component would like to receive — see influora-api's
// CreatorCampaignService#recordApplicationHistory (CAMPAIGN_APPLIED), DealService#get's
// recordViewIfAbsent call (APPLICATION_VIEWED), and DealService#doAccept (APPLICATION_ACCEPTED).
// This is the exact failure mode the metadata/reason mismatch above already caught once: a
// fixture invented from the component's own interface can never catch the component reading a
// field the server never populates, or expecting a value (like a `targetRoute` the server
// deliberately leaves null) it doesn't actually send.

const EVENT_APPLIED: CreatorApplicationHistoryEvent = {
  historyId: 'h_1',
  campaignId: 'camp_1',
  applicationId: 'app_1',
  dealRoomId: null,
  eventType: 'CAMPAIGN_APPLIED',
  eventStatus: 'APPLIED',
  actorType: 'CREATOR',
  actorId: 'cr_1',
  description: 'Creator applied to this campaign',
  createdAt: '2026-08-01T10:00:00.000Z',
  // Real value: CreatorCampaignService.recordApplicationHistory points this at the CAMPAIGN
  // id, never the collaboration/deal id — there is no /creator/deals/:id route.
  targetRoute: '/creator/campaigns/camp_1',
  targetId: 'camp_1',
};

const EVENT_VIEWED: CreatorApplicationHistoryEvent = {
  historyId: 'h_2',
  campaignId: 'camp_1',
  applicationId: 'app_1',
  dealRoomId: null,
  eventType: 'APPLICATION_VIEWED',
  eventStatus: 'APPLIED',
  actorType: 'BRAND',
  actorId: 'brand_1',
  description: 'Brand viewed the application',
  createdAt: '2026-08-02T10:00:00.000Z',
  // Real value: recordViewIfAbsent always passes null/null — nothing for the creator to act
  // on yet, so no CTA renders for this row.
};

// Real value: DealService#doAccept sets dealRoomId = the collaboration id (a deal room now
// exists) but passes targetRoute = null — the frontend's own established
// `/creator/chat?deal=<id>` fallback is what's expected to supply the CTA, not a server route.
const EVENT_ACCEPTED: CreatorApplicationHistoryEvent = {
  historyId: 'h_3',
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

describe('ApplicationHistoryTimeline', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a loading skeleton while the fetch is in flight', async () => {
    let resolve!: (events: CreatorApplicationHistoryEvent[]) => void;
    historyMock.mockReturnValue(
      new Promise<CreatorApplicationHistoryEvent[]>((r) => {
        resolve = r;
      }),
    );

    renderTimeline();

    // The busy region (skeleton rows) is present before data loads.
    expect(document.querySelector('[aria-busy="true"]')).toBeInTheDocument();

    resolve([EVENT_APPLIED]);
    await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).not.toBeInTheDocument());
  });

  it('renders events in exactly the order the API returned them', async () => {
    historyMock.mockResolvedValue([EVENT_APPLIED, EVENT_VIEWED, EVENT_ACCEPTED]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Campaign Applied')).toBeInTheDocument());

    const list = screen.getByRole('list', { name: /application journey/i });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    // Order must match the array as returned — first item is the oldest (CAMPAIGN_APPLIED),
    // last item is the newest (APPLICATION_ACCEPTED) — never resorted client-side.
    expect(within(items[0]).getByText('Campaign Applied')).toBeInTheDocument();
    expect(within(items[1]).getByText('Application Viewed')).toBeInTheDocument();
    expect(within(items[2]).getByText('Application Accepted')).toBeInTheDocument();
  });

  it('renders a real error state with a working retry, not a silently empty list', async () => {
    const user = userEvent.setup({ delay: null });
    historyMock
      .mockRejectedValueOnce(new ApiError('SERVER_ERROR', 'Could not load journey'))
      .mockResolvedValueOnce([EVENT_APPLIED]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText(/could not load this application/i)).toBeInTheDocument());
    expect(screen.getByText('Could not load journey')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /retry/i }));

    await waitFor(() => expect(screen.getByText('Campaign Applied')).toBeInTheDocument());
    expect(historyMock).toHaveBeenCalledTimes(2);
  });

  it('says plainly when an application has no recorded journey, without fabricating one', async () => {
    historyMock.mockResolvedValue([]);

    renderTimeline();

    await waitFor(() =>
      expect(screen.getByText(/no journey events recorded for this application yet/i)).toBeInTheDocument(),
    );
    // Never invent a synthetic "Applied" step from nothing.
    expect(screen.queryByText('Campaign Applied')).not.toBeInTheDocument();
  });

  /**
   * Integration-seam regression. Every other fixture in this file was hand-authored from the
   * FRONTEND's own interface, which is exactly how the original defect survived: the component
   * rendered `event.reason`, the endpoint emits `metadata`, and because the server uses
   * `@JsonInclude(NON_NULL)` the phantom field was simply absent rather than null. The
   * rejection reason therefore rendered nowhere in production while every test on both sides
   * stayed green.
   *
   * This fixture is transcribed field-for-field from the real DTO
   * (`CreatorApplicationDtos.ApplicationHistoryEventItem`) instead — `metadata` carrying the
   * sanitized reason, no `reason` key at all, plus the server-computed `dealPhase`. Anything
   * that reintroduces a client-invented field name fails here.
   */
  it('renders the rejection reason the server actually sends (metadata, not a phantom reason field)', async () => {
    // description transcribed verbatim from DealService.java's doReject `historyDescription`:
    // the BRAND branch is "This application was closed by the brand" + (": " + reason when one
    // was given), and `metadata` carries that same sanitizedReason — null when none was given.
    const EVENT_REJECTED_AS_SERVER_SENDS_IT: CreatorApplicationHistoryEvent = {
      historyId: 'h_9',
      campaignId: 'camp_1',
      applicationId: 'app_1',
      eventType: 'APPLICATION_REJECTED',
      eventStatus: 'CANCELLED',
      actorType: 'BRAND',
      actorId: 'brand_1',
      description: 'This application was closed by the brand: Budget moved to Q4',
      createdAt: '2026-08-04T10:00:00.000Z',
      metadata: 'Budget moved to Q4',
      dealPhase: null,
    };

    historyMock.mockResolvedValue([EVENT_APPLIED, EVENT_REJECTED_AS_SERVER_SENDS_IT]);

    renderTimeline();

    // Both the APPLICATION_REJECTED event-type label and the CANCELLED status badge now
    // correctly resolve to "Closed" via the same canonical map — two matches, not one.
    await waitFor(() => expect(screen.getAllByText('Closed')).toHaveLength(2));
    expect(screen.getByText('Budget moved to Q4')).toBeInTheDocument();
  });

  /**
   * F-0303 regression. The wire value stays `APPLICATION_REJECTED` — that is the truthful audit
   * record — but the word shown to a creator must not be "Rejected". src/lib/application-status.ts
   * carries a CTO arbitration (Kabir R5) that a declined application reads "Closed", so that
   * brand-internal triage is never presented to a creator as a finalized verdict on them.
   *
   * The requirements document this feature was built from asks for the opposite wording. That
   * conflict is open (F-0287) and only swapnil can settle it.
   *
   * THIS TEST HAS BEEN RED, AND THAT MATTERED. It was written green against an invented
   * description, then re-transcribed from the real emission — `actorLabel + " rejected: " +
   * sanitizedReason` — at which point it correctly went red, because the label said "Closed"
   * while the line directly beneath it said "Brand rejected: …". The backend copy was then
   * fixed (DealService#doReject now builds a separate `historyDescription`, worded per branch),
   * and the fixture below is transcribed from THAT. So it has now failed for a real reason and
   * passed for a real reason, which is the only sequence that makes it worth keeping.
   *
   * Do not let it go green by editing the fixture. If the backend copy changes again, re-read
   * DealService#doReject and copy the new string — a fixture is only as honest as its last
   * transcription, and this file has already drifted twice.
   */
  it('never shows a creator the word "Rejected" for a brand decline (CTO arbitration, F-0303)', async () => {
    const EVENT_REJECTED: CreatorApplicationHistoryEvent = {
      historyId: 'h_10',
      campaignId: 'camp_1',
      applicationId: 'app_1',
      eventType: 'APPLICATION_REJECTED',
      eventStatus: 'CANCELLED',
      actorType: 'BRAND',
      actorId: 'brand_1',
      description: 'This application was closed by the brand: Went with another creator',
      createdAt: '2026-08-04T10:00:00.000Z',
      metadata: 'Went with another creator',
      dealPhase: null,
    };

    historyMock.mockResolvedValue([EVENT_REJECTED]);

    renderTimeline();

    // Both the APPLICATION_REJECTED event-type label and the CANCELLED status badge now
    // correctly resolve to "Closed" via the same canonical map — two matches, not one.
    await waitFor(() => expect(screen.getAllByText('Closed')).toHaveLength(2));
    expect(screen.queryByText(/rejected/i)).not.toBeInTheDocument();
  });

  /**
   * DEFENSIVE GUARD, not a transcription of current behaviour — say that plainly so nobody
   * "corrects" this fixture back to matching the live emission the way the F-0303 fixtures had
   * to be, twice.
   *
   * EscrowService.applyFunding (and the other five contract/escrow/deliverable/payout sites)
   * used to pass `hold.getId()` / `contract.getId()` / `deliverable.getId()` / `milestone.getId()`
   * as `metadata` — an internal ULID with no meaning to a creator, rendered in the theme's error
   * token as if something had gone wrong. That was fixed backend-side: all six now pass `null`
   * (ContractService:354/753, EscrowService:530, CreatorDeliverableService:388,
   * BrandDeliverableService:140, PayoutService:545). This fixture deliberately does NOT match
   * that current `null` — it manufactures the old, since-fixed shape on purpose, to prove the
   * frontend's own `metadataIsHumanReason` allowlist still refuses to render an id in this slot
   * if a call site ever regresses. If a real FUND_ESCROW event with a populated `metadata` ever
   * needs its own test, transcribe that one fresh from the real emission — do not repurpose this
   * one, which is intentionally testing a shape the server no longer sends.
   */
  it('never renders an opaque entity id in metadata, even if a call site regressed to sending one', async () => {
    const EVENT_ESCROW_FUNDED: CreatorApplicationHistoryEvent = {
      historyId: 'h_12',
      campaignId: 'camp_1',
      applicationId: 'app_1',
      dealRoomId: 'deal_1',
      eventType: 'FUND_ESCROW',
      eventStatus: 'IN_PROGRESS',
      actorType: 'SYSTEM',
      actorId: 'system',
      description: 'Escrow was funded — work can begin',
      createdAt: '2026-08-07T10:00:00.000Z',
      metadata: '01HESCROW1234567890AB',
      dealPhase: 'escrow',
    };

    historyMock.mockResolvedValue([EVENT_ESCROW_FUNDED]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Escrow was funded — work can begin')).toBeInTheDocument());
    // The description (real, informational) renders; the raw hold id in metadata must not.
    expect(screen.queryByText('01HESCROW1234567890AB')).not.toBeInTheDocument();
  });

  it('renders a working CTA for an event that carries a targetRoute', async () => {
    // EVENT_APPLIED is the one event type that actually carries a server-populated
    // targetRoute (see its fixture comment) — "/creator/campaigns/camp_1".
    historyMock.mockResolvedValue([EVENT_APPLIED]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Campaign Applied')).toBeInTheDocument());

    const cta = screen.getByRole('link', { name: /view campaign/i });
    expect(cta).toHaveAttribute('href', '/creator/campaigns/camp_1');
  });

  it('falls back to the /creator/chat?deal= convention when an event has a dealRoomId but no targetRoute', async () => {
    // EVENT_ACCEPTED is the real shape DealService#doAccept emits: dealRoomId set,
    // targetRoute deliberately null. The component, not the server, owns this fallback.
    historyMock.mockResolvedValue([EVENT_APPLIED, EVENT_ACCEPTED]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Application Accepted')).toBeInTheDocument());

    const cta = screen.getByRole('link', { name: /open deal room/i });
    expect(cta).toHaveAttribute('href', '/creator/chat?deal=deal_1');
  });

  it('shows the server-computed dealPhase as its own badge when present', async () => {
    const EVENT_WITH_PHASE: CreatorApplicationHistoryEvent = {
      historyId: 'h_10',
      campaignId: 'camp_1',
      applicationId: 'app_1',
      dealRoomId: 'deal_1',
      eventType: 'FUND_ESCROW',
      eventStatus: 'IN_PROGRESS',
      actorType: 'CREATOR',
      actorId: 'cr_1',
      description: 'Escrow funded',
      createdAt: '2026-08-05T10:00:00.000Z',
      dealPhase: 'escrow',
    };

    historyMock.mockResolvedValue([EVENT_WITH_PHASE]);

    renderTimeline();

    // "Fund Escrow" appears twice by coincidence — once as the FUND_ESCROW event-type label,
    // once as the 'escrow' dealPhase badge — so both must be present rather than exactly one.
    await waitFor(() => expect(screen.getAllByText('Fund Escrow')).toHaveLength(2));
  });

  it('never coerces a null dealPhase (CANCELLED/DISPUTED) into a fabricated phase badge', async () => {
    // No reason given. DealService#doReject gates both fields on `reasonGiven`, so the
    // description is the bare branch sentence with no ": …" suffix and `metadata` is null — the
    // internal "Deal rejected" default belongs to the DealMessage audit row and deliberately
    // never reaches this creator-visible record. (An earlier version of this fixture carried
    // that default in both fields; it was transcribed from the pre-fix emission.)
    const EVENT_NO_PHASE: CreatorApplicationHistoryEvent = {
      historyId: 'h_11',
      campaignId: 'camp_1',
      applicationId: 'app_1',
      eventType: 'APPLICATION_REJECTED',
      eventStatus: 'CANCELLED',
      actorType: 'BRAND',
      actorId: 'brand_1',
      description: 'This application was closed by the brand',
      createdAt: '2026-08-06T10:00:00.000Z',
      dealPhase: null,
    };

    historyMock.mockResolvedValue([EVENT_NO_PHASE]);

    renderTimeline();

    // Anchor is "Closed", not "Rejected" — the creator-facing label for APPLICATION_REJECTED
    // follows the CTO arbitration, not the wire value (F-0303).
    // Both the APPLICATION_REJECTED event-type label and the CANCELLED status badge now
    // correctly resolve to "Closed" via the same canonical map — two matches, not one.
    await waitFor(() => expect(screen.getAllByText('Closed')).toHaveLength(2));
    // None of the 5 real phase labels may appear — a null dealPhase must render no phase badge
    // at all, never a default like "Negotiate".
    ['Negotiate', 'Contract', 'Fund Escrow', 'Deliver', 'Pay'].forEach((label) => {
      expect(screen.queryByText(label)).not.toBeInTheDocument();
    });
  });

  /**
   * Sign-off review — the status badge used to call prettify(event.eventStatus), the raw enum
   * title-cased, bypassing application-status.ts entirely even though that file declares itself
   * the single source of truth for creator-facing status labels. Result: a declined application
   * showed "Closed" (event label) next to "Cancelled" (status badge) next to "Closed" again (the
   * enclosing CreatorApplicationCard's own badge) — three renderings of one status disagreeing in
   * one view. Asserted against getApplicationStatusLabel's own output, not a hardcoded string, so
   * this test tracks the canonical map if it ever changes rather than pinning today's copy.
   */
  it('renders the status badge text from the canonical label map, never the raw enum (CANCELLED -> "Closed")', async () => {
    const expectedLabel = getApplicationStatusLabel('CANCELLED');
    expect(expectedLabel).toBe('Closed'); // sanity: this test is worthless if the map itself drifts silently

    const EVENT_CANCELLED: CreatorApplicationHistoryEvent = {
      historyId: 'h_13',
      campaignId: 'camp_1',
      applicationId: 'app_1',
      eventType: 'APPLICATION_REJECTED',
      eventStatus: 'CANCELLED',
      actorType: 'BRAND',
      actorId: 'brand_1',
      description: 'This application was closed by the brand',
      createdAt: '2026-08-08T10:00:00.000Z',
      dealPhase: null,
    };

    historyMock.mockResolvedValue([EVENT_CANCELLED]);

    renderTimeline();

    // "Closed" appears twice here by coincidence — the APPLICATION_REJECTED event-type label and
    // the CANCELLED status badge both resolve to "Closed" — so both, not "exactly one", is correct.
    await waitFor(() => expect(screen.getAllByText(expectedLabel)).toHaveLength(2));
    // The bypassed behaviour must not reappear: the raw enum, title-cased, must never render.
    expect(screen.queryByText('Cancelled')).not.toBeInTheDocument();
  });

  it('renders the status badge text from the canonical label map for a non-terminal status (CONTRACT_PENDING -> "Contract pending")', async () => {
    const expectedLabel = getApplicationStatusLabel('CONTRACT_PENDING');
    expect(expectedLabel).toBe('Contract pending');

    // Real pairing: ContractService#generate records DEAL_ROOM_ACTIVATED with
    // eventStatus = CollaborationStatus.CONTRACT_PENDING.
    const EVENT_DEAL_ROOM_ACTIVATED: CreatorApplicationHistoryEvent = {
      historyId: 'h_14',
      campaignId: 'camp_1',
      applicationId: 'app_1',
      dealRoomId: 'deal_1',
      eventType: 'DEAL_ROOM_ACTIVATED',
      eventStatus: 'CONTRACT_PENDING',
      actorType: 'BRAND',
      actorId: 'brand_1',
      description: 'The deal room is now active — contract and milestones are set',
      createdAt: '2026-08-09T10:00:00.000Z',
      dealPhase: 'contract',
    };

    historyMock.mockResolvedValue([EVENT_DEAL_ROOM_ACTIVATED]);

    renderTimeline();

    await waitFor(() => expect(screen.getByText('Deal Room Activated')).toBeInTheDocument());
    expect(screen.getByText(expectedLabel)).toBeInTheDocument();
    // Neither the bypassed prettify() output nor any other guess may appear.
    expect(screen.queryByText('Contract Pending')).not.toBeInTheDocument();
  });
});
