/**
 * /brand/deals — which actions a brand is offered on a LIVE deal.
 *
 * The page used to show "Accept Proposal" exactly when a deal was INVITED / APPLIED / SHORTLISTED
 * — the states that carry no rate, where the server always answers 409 AGREED_RATE_REQUIRED — and
 * to hide it in IN_NEGOTIATION, the only state where a creator's offer can actually be accepted.
 * Accept could therefore never succeed, and every failure was shown as "Try again.".
 *
 * Its counter dialog also sent an amount with no deliverables. The contract later materialises
 * Deliverable rows from the agreed offer's deliverables, so such a deal got a contract with
 * nothing to deliver: the creator had nothing to submit and the deal could never complete.
 *
 * Run: npx vitest run src/components/brand/deals/deal-room-dashboard-live-actions.test.tsx
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

const dealsList = vi.fn();
const dealsAccept = vi.fn();
const dealsCounter = vi.fn();
const messagesList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    deals: {
      list: (...a: unknown[]) => dealsList(...a),
      get: vi.fn().mockResolvedValue(null),
      accept: (...a: unknown[]) => dealsAccept(...a),
      reject: vi.fn(),
      counter: (...a: unknown[]) => dealsCounter(...a),
    },
    messages: {
      list: (...a: unknown[]) => messagesList(...a),
      markRead: vi.fn().mockResolvedValue({ ok: true }),
      stream: vi.fn(() => ({ close: vi.fn() })),
    },
  };
});

import { ApiError } from '@/lib/api';
import { DealRoomDashboard, brandDealActions, findOfferBeyondFirstPage } from './deal-room-dashboard';
import type { DealMessage } from '@/lib/api';

/** `n` plain chat messages, oldest first, ending at minute `endMinute` of a fixed hour. */
function chatPage(n: number, endMinute: number): DealMessage[] {
  return Array.from({ length: n }, (_, i) => ({
    id: `m_${endMinute}_${i}`,
    dealId: 'deal_1',
    kind: 'text',
    senderId: 'creator_user_1',
    senderType: 'creator',
    content: 'hi',
    createdAt: new Date(Date.UTC(2026, 6, 20, 10, endMinute - (n - 1 - i))).toISOString(),
    readBy: [],
  })) as DealMessage[];
}

// QA review: the timeline loads 50 messages at a time (newest page). After a long chat the offer
// on the table can sit on an OLDER page, and reading only the loaded page would hide Accept.
describe('findOfferBeyondFirstPage', () => {
  const olderOffer = {
    id: 'm_offer',
    dealId: 'deal_1',
    kind: 'proposal',
    senderId: 'creator_user_1',
    senderType: 'creator',
    metadata: { amount: 40000, status: 'pending' },
    createdAt: '2026-07-20T08:00:00.000Z',
    readBy: [],
  } as DealMessage;

  it('does not fetch anything when the newest page already contains an offer', async () => {
    const fetchPage = vi.fn();
    expect(await findOfferBeyondFirstPage('deal_1', [...chatPage(49, 59), olderOffer], fetchPage)).toBeNull();
    expect(fetchPage).not.toHaveBeenCalled();
  });

  it('does not fetch when the newest page is the whole history', async () => {
    const fetchPage = vi.fn();
    expect(await findOfferBeyondFirstPage('deal_1', chatPage(12, 59), fetchPage)).toBeNull();
    expect(fetchPage).not.toHaveBeenCalled();
  });

  it('a full page of chat with no offer: pages back from the OLDEST loaded message and finds it', async () => {
    const newest = chatPage(50, 59);
    const fetchPage = vi.fn().mockResolvedValue([olderOffer, ...chatPage(10, 5)]);

    expect(await findOfferBeyondFirstPage('deal_1', newest, fetchPage)).toBe(olderOffer);
    expect(fetchPage).toHaveBeenCalledWith('deal_1', newest[0].createdAt);
    // ...and that offer then drives the action bar exactly as a loaded one would.
    expect(brandDealActions('IN_NEGOTIATION', [olderOffer, ...newest]).canAccept).toBe(true);
  });

  it('is bounded, and a failed page fetch just leaves Accept hidden', async () => {
    const endless = vi.fn().mockImplementation(() => Promise.resolve(chatPage(50, 30)));
    expect(await findOfferBeyondFirstPage('deal_1', chatPage(50, 59), endless)).toBeNull();
    expect(endless).toHaveBeenCalledTimes(5);

    const failing = vi.fn().mockRejectedValue(new Error('network down'));
    expect(await findOfferBeyondFirstPage('deal_1', chatPage(50, 59), failing)).toBeNull();
  });
});

const offer = (senderType: 'brand' | 'creator', status = 'pending') => ({
  kind: 'proposal' as const,
  senderType,
  metadata: { amount: 40000, status },
});
const chat = { kind: 'text' as const, senderType: 'creator' as const, metadata: undefined };

describe('brandDealActions — mirrors DealService.doAccept / Collaboration.canReject', () => {
  it('a rate-less invite or application: nothing to accept, an offer can be made, it can be rejected', () => {
    for (const status of ['INVITED', 'APPLIED', 'SHORTLISTED'] as const) {
      expect(brandDealActions(status, []), status).toEqual({
        canAccept: false,
        canMakeOffer: true,
        canReject: true,
      });
    }
  });

  it("the creator's open offer in IN_NEGOTIATION is the one thing a brand can accept", () => {
    expect(brandDealActions('IN_NEGOTIATION', [offer('brand', 'countered'), chat, offer('creator')])).toEqual({
      canAccept: true,
      canMakeOffer: true,
      canReject: true,
    });
  });

  it("the brand's OWN open offer cannot be accepted by the brand (CANNOT_ACCEPT_OWN_OFFER)", () => {
    expect(brandDealActions('IN_NEGOTIATION', [offer('creator', 'countered'), offer('brand')]).canAccept).toBe(false);
  });

  it('only the LATEST offer counts, and only while it is still open', () => {
    expect(brandDealActions('IN_NEGOTIATION', [offer('creator'), offer('brand')]).canAccept).toBe(false);
    expect(brandDealActions('IN_NEGOTIATION', [offer('creator', 'accepted')]).canAccept).toBe(false);
    expect(brandDealActions('IN_NEGOTIATION', [offer('creator', 'rejected')]).canAccept).toBe(false);
  });

  it('terms agreed: no more offers, but still rejectable until a contract exists', () => {
    expect(brandDealActions('TERMS_AGREED', [offer('creator', 'accepted')])).toEqual({
      canAccept: false,
      canMakeOffer: false,
      canReject: true,
    });
  });

  it('from the contract onwards nothing on this page moves the deal', () => {
    for (const status of [
      'CONTRACT_PENDING',
      'CONTRACTED',
      'IN_PROGRESS',
      'REVIEW_PENDING',
      'REVISION_REQUESTED',
      'COMPLETED',
      'CANCELLED',
      'DISPUTED',
    ] as const) {
      expect(brandDealActions(status, [offer('creator')]), status).toEqual({
        canAccept: false,
        canMakeOffer: false,
        canReject: false,
      });
    }
    expect(brandDealActions(undefined, [])).toEqual({ canAccept: false, canMakeOffer: false, canReject: false });
  });
});

const deal = (status: string) => ({
  id: 'deal_1',
  campaignName: 'Summer Launch',
  counterpartyId: 'creator_user_1',
  counterpartyName: 'Aarti Menon',
  counterpartyAvatar: '',
  status,
  dealValue: 40000,
  deliverablesTotal: 2,
  nextDeadline: null,
  lastMessage: 'Looking forward to it',
  lastMessageAt: '2026-07-20T10:00:00Z',
  unreadCount: 0,
});

const creatorOfferMessage = {
  id: 'msg_offer_1',
  dealId: 'deal_1',
  kind: 'proposal',
  senderId: 'creator_user_1',
  senderType: 'creator',
  content: 'I can do this for 40,000',
  metadata: { amount: 40000, status: 'pending' },
  createdAt: '2026-07-20T10:00:00Z',
  readBy: [],
};

function DealRoomProbe() {
  const location = useLocation();
  return <div>deal room {location.search}</div>;
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/brand/deals']}>
      <Routes>
        <Route path="/brand/deals" element={<DealRoomDashboard />} />
        <Route path="/brand/deals/:id" element={<DealRoomDashboard />} />
        <Route path="/brand/chat" element={<DealRoomProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('DealRoomDashboard — live action bar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('a fresh application offers no Accept (it could only 409) and routes the offer to the deal room', async () => {
    dealsList.mockResolvedValue([deal('APPLIED')]);
    messagesList.mockResolvedValue([]);
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await user.click(await screen.findByText('Aarti Menon'));

    const makeOffer = await screen.findByRole('button', { name: 'Make an offer in deal room' });
    expect(screen.queryByRole('button', { name: /^Accept/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();

    await user.click(makeOffer);
    expect(await screen.findByText('deal room ?deal=deal_1')).toBeInTheDocument();
    expect(dealsCounter).not.toHaveBeenCalled();
  });

  it("a creator's counter in IN_NEGOTIATION can be accepted here", async () => {
    dealsList.mockResolvedValue([deal('IN_NEGOTIATION')]);
    messagesList.mockResolvedValue([creatorOfferMessage]);
    dealsAccept.mockResolvedValue(deal('TERMS_AGREED'));
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await user.click(await screen.findByText('Aarti Menon'));

    await user.click(await screen.findByRole('button', { name: 'Accept Offer' }));

    await waitFor(() => expect(dealsAccept).toHaveBeenCalledWith('deal_1', 'brand'));
  });

  it('after the accept lands, the pane follows the refreshed deal: no stale Accept/Reject, and the contract lives in the deal room', async () => {
    dealsList.mockResolvedValueOnce([deal('IN_NEGOTIATION')]).mockResolvedValue([deal('CONTRACT_PENDING')]);
    messagesList
      .mockResolvedValueOnce([creatorOfferMessage])
      .mockResolvedValue([{ ...creatorOfferMessage, metadata: { amount: 40000, status: 'accepted' } }]);
    dealsAccept.mockResolvedValue(deal('CONTRACT_PENDING'));
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await user.click(await screen.findByText('Aarti Menon'));
    await user.click(await screen.findByRole('button', { name: 'Accept Offer' }));

    const openContract = await screen.findByRole('button', { name: 'Open Contract' });
    expect(screen.queryByRole('button', { name: 'Accept Offer' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Reject' })).toBeNull();

    await user.click(openContract);
    expect(await screen.findByText('deal room ?deal=deal_1&tab=contract')).toBeInTheDocument();
  });

  it('a 409 is explained, not reported as "Try again"', async () => {
    dealsList.mockResolvedValue([deal('IN_NEGOTIATION')]);
    messagesList.mockResolvedValue([creatorOfferMessage]);
    dealsAccept.mockRejectedValue(
      new ApiError('CANNOT_ACCEPT_OWN_OFFER', 'You cannot accept your own offer', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await user.click(await screen.findByText('Aarti Menon'));
    await user.click(await screen.findByRole('button', { name: 'Accept Offer' }));

    expect(await screen.findByText(/You made the last offer, so the creator has to accept it/)).toBeInTheDocument();
    expect(screen.queryByText(/Try again\.$/)).toBeNull();
  });
});
