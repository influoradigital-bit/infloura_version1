import { describe, it, expect } from 'vitest';
import type { Deal } from '@/lib/api';
import { mapDealToDealsPageRow } from '@/lib/creator-deal-mappers';
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
