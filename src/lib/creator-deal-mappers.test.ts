import { describe, it, expect } from 'vitest';
import type { Deal } from '@/lib/api';
import type { CollaborationStatus } from '@/lib/types';
import {
  mapCollaborationStatusToDealStage,
  mapDealToChatRoom,
  mapDealToDealsPageRow,
  type DealStage,
} from '@/lib/creator-deal-mappers';
import { getInitials } from '@/lib/helpers';

/**
 * Regression guard for the 2026-07-23 P0: the creator Deals page crashed
 * (`TypeError: Cannot read properties of undefined (reading 'split')`) for any
 * creator with >=1 deal, because `creator-deals.tsx` cast the API `Deal[]`
 * straight to its `DealRoom` view model with `as unknown as` — leaving
 * `brandName`/`campaignTitle`/`status` undefined at runtime.
 *
 * `liveDeal` below is the EXACT payload captured from the live deployment
 * (http://200.141.1.6/ · GET /deals?status=all as demo.creator@influora.com).
 */
const liveDeal: Deal = {
  id: '01KY52585HY09G9CJWP930SJX8',
  campaignId: '01KY523ES7ZW5T2KCX1B8Q0450',
  campaignName: 'QA E2E — Diwali Skincare Reels',
  counterpartyId: '01KY4Y1PR2A2CHE0933YPZ3R7R',
  counterpartyName: 'Demo Brand Co',
  counterpartyAvatar: undefined,
  counterpartyHandle: undefined,
  status: 'TERMS_AGREED',
  dealValue: 0,
  currency: 'INR',
  lastMessage: 'Brand accepted the proposal',
  lastMessageAt: '2026-07-22T14:03:09Z',
  unreadCount: 1,
  deliverablesDone: 0,
  deliverablesTotal: 0,
  nextDeadline: undefined,
  contractId: undefined,
  contractStatus: undefined,
  escrowFunded: false,
};

describe('mapDealToDealsPageRow (P0 creator-deals crash regression)', () => {
  it('maps the live API Deal shape onto the page view model — no undefined fields', () => {
    const row = mapDealToDealsPageRow(liveDeal);

    // The fields the page renders — previously all undefined behind the cast.
    expect(row.brandName).toBe('Demo Brand Co');       // <- was deal.brandName (undefined)
    expect(row.campaignTitle).toBe('QA E2E — Diwali Skincare Reels'); // <- was deal.campaignTitle
    expect(row.brandId).toBe('01KY4Y1PR2A2CHE0933YPZ3R7R');
    expect(typeof row.budget).toBe('number');
    expect(row.unreadCount).toBe(1);
    expect(row.escrowFunded).toBe(false);
  });

  it('normalizes the uppercase server enum to the lowercase UI status union', () => {
    // TERMS_AGREED -> negotiating (so STATUS_CHIPS counts/filters actually match).
    expect(mapDealToDealsPageRow(liveDeal).status).toBe('negotiating');
  });

  it('sets API-absent optional fields to explicit undefined (honest empty state)', () => {
    // The summary payload carries no rating / payout-speed / offer-expiry. The page
    // guards each, so they render nothing rather than a fabricated value.
    const row = mapDealToDealsPageRow(liveDeal);
    expect(row.brandRating).toBeUndefined();
    expect(row.brandPaymentSpeed).toBeUndefined();
    expect(row.expiresAt).toBeUndefined();
    // deliverables is a safe empty array, never undefined (the page may map over it).
    expect(Array.isArray(row.deliverables)).toBe(true);
    // deadline defaults to '' (string), never undefined — safe for date rendering.
    expect(typeof row.deadline).toBe('string');
  });

  it('renders avatar initials without throwing (the exact crash site)', () => {
    const row = mapDealToDealsPageRow(liveDeal);
    // creator-deals.tsx:505 does getInitials(deal.brandName) in the AvatarFallback.
    expect(() => getInitials(row.brandName)).not.toThrow();
    expect(getInitials(row.brandName)).toBe('DB'); // "Demo Brand Co" -> D, B
  });
});

describe('getInitials (null-safe hardening)', () => {
  it('returns empty string for null/undefined instead of throwing', () => {
    expect(() => getInitials(undefined)).not.toThrow();
    expect(() => getInitials(null)).not.toThrow();
    expect(getInitials(undefined)).toBe('');
    expect(getInitials('')).toBe('');
  });

  it('still produces initials for real names', () => {
    expect(getInitials('Demo Brand Co')).toBe('DB');
    expect(getInitials('Nykaa')).toBe('N');
  });
});

/**
 * CR-05 regression guard.
 *
 * The bug: `creator-deals.tsx` and `creator-chat.tsx` each derived a coarse stage from
 * `Deal.status` using their own switch, and the two disagreed on APPLIED, SHORTLISTED,
 * TERMS_AGREED and DISPUTED. `liveDeal` above — a real captured payload — is TERMS_AGREED,
 * so the same collaboration read "Negotiating" in the list and "Contracted" in its own room.
 *
 * The structural half of the fix is that only one switch now exists and both pages import
 * it, which no test can meaningfully re-assert. What a test CAN pin is the switch's contents
 * against the backend, so an edit to it has to be a deliberate one.
 */
describe('mapCollaborationStatusToDealStage (CR-05 — one mapping, mirroring the server)', () => {
  // Mirrors the three backend DISPLAY mappers — DashboardService.bucketFor:126,
  // AdminCampaignService:307, CreatorApplicationMapper:40 — which all agree TERMS_AGREED is
  // still pre-contract. Note the server is NOT unanimous: DealService.statusesForFilter:1030
  // (the filter path) and AdminBrandService:94 put TERMS_AGREED on the contracted side. The
  // badge follows the display mappers; the filter disagreement is tracked as CR-13. See the
  // header comment on mapCollaborationStatusToDealStage for the full argument.
  //
  // 'new' is the one UI-only split of the server's NEGOTIATING bucket, and the filter path is
  // what legitimises it: statusesForFilter("new") is exactly [INVITED] for creators.
  const expected: Record<CollaborationStatus, DealStage> = {
    INVITED: 'new',
    APPLIED: 'negotiating',
    SHORTLISTED: 'negotiating',
    IN_NEGOTIATION: 'negotiating',
    TERMS_AGREED: 'negotiating',
    CONTRACT_PENDING: 'contracted',
    CONTRACTED: 'contracted',
    IN_PROGRESS: 'in_progress',
    REVIEW_PENDING: 'review',
    REVISION_REQUESTED: 'review',
    COMPLETED: 'completed',
    // CR-26 — was 'completed' for both, which rendered the badge "Done" on a deal the creator
    // was actively contesting. They now have their own bucket.
    CANCELLED: 'disputed',
    DISPUTED: 'disputed',
  };

  it.each(Object.entries(expected))('maps %s -> %s', (status, stage) => {
    expect(mapCollaborationStatusToDealStage(status as CollaborationStatus)).toBe(stage);
  });

  it('covers every status in the backend enum — no silent default fallthrough', () => {
    // If CollaborationStatus.java gains a state, this table must gain a row too rather than
    // letting it land on the `default:` arm unnoticed.
    expect(Object.keys(expected)).toHaveLength(13);
  });

  it('keeps TERMS_AGREED out of "contracted" — no contract row exists yet', () => {
    // DealService.doAccept transitions to TERMS_AGREED, and
    // CollaborationLifecycleService.BEFORE_CONTRACT_PENDING lists TERMS_AGREED as a state a
    // contract may still advance FROM. The deal room used to claim 'contracted' here, which
    // is the exact divergence CR-05 reported.
    expect(mapCollaborationStatusToDealStage('TERMS_AGREED')).toBe('negotiating');
  });

  it('gives the deals list and the deal room the same stage for one deal', () => {
    expect(mapDealToChatRoom(liveDeal).status).toBe(mapDealToDealsPageRow(liveDeal).status);
  });

  /**
   * CR-26 — a disputed deal must never read as finished.
   *
   * This is the assertion that matters to a person: the previous mapping told a creator whose
   * deal was in dispute that it was "Done". Pinned separately from the table above so the
   * intent survives even if the table is ever regenerated.
   */
  it('never reports a disputed or cancelled deal as completed', () => {
    expect(mapCollaborationStatusToDealStage('DISPUTED')).not.toBe('completed');
    expect(mapCollaborationStatusToDealStage('CANCELLED')).not.toBe('completed');
    expect(mapCollaborationStatusToDealStage('DISPUTED')).toBe('disputed');
  });
});

/**
 * CR-24 — the brand side reads the SAME switch.
 *
 * `brand-chat.tsx` used to carry a private copy mapping `TERMS_AGREED -> 'contracted'`,
 * character-for-character the mapping deleted from `creator-chat.tsx` as the CR-05 defect. The
 * consequence was live: the moment a creator pressed Accept, the creator's room said
 * "Negotiating" and the brand's room said "Contracted" for the same deal, with no contract
 * existing on either side.
 *
 * The structural half — that only one switch exists — is pinned by `deal-stage.ts` being the
 * only module that switches over `CollaborationStatus`. What is pinned here is the agreement
 * itself, which is what a reader of either room actually experiences.
 */
describe('mapCollaborationStatusToDealStage (CR-24 — brand and creator agree)', () => {
  it('puts TERMS_AGREED pre-contract, which is where the brand room now reads it too', () => {
    expect(mapCollaborationStatusToDealStage('TERMS_AGREED')).toBe('negotiating');
  });

  it('is re-exported from creator-deal-mappers, so the creator imports are unchanged', async () => {
    const shared = await import('@/lib/deal-stage');
    const viaCreator = await import('@/lib/creator-deal-mappers');
    expect(viaCreator.mapCollaborationStatusToDealStage).toBe(
      shared.mapCollaborationStatusToDealStage,
    );
  });
});

describe('mapDealToChatRoom (CR-02 gate input)', () => {
  it('carries the raw collaborationStatus through untouched', () => {
    // `dealAllowsProposalResponse` in creator-chat.tsx mirrors Collaboration.canAccept()
    // against this field, not the coarse stage. Dropping it here silently disables
    // Accept/Counter/Decline on every deal.
    expect(mapDealToChatRoom(liveDeal).collaborationStatus).toBe('TERMS_AGREED');
  });
});
