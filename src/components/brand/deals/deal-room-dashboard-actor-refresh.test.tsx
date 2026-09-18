/**
 * DealRoomDashboard — brand's own accept/counter/reject refreshes the message timeline
 * (CR-98 / F-0112).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * `handleAcceptProposal`/`handleSendCounter`/`handleRejectProposal` called only `loadDeals()`
 * after a successful action — never `loadMessages()`. This is the brand's own action on their
 * most-reached "Deal Rooms" page, so the message timeline (which carries the settled/superseded
 * proposal card and any system message) went stale even for the ACTOR, not just a counterparty
 * whose SSE connection might be dead. The fix runs both refetches together.
 *
 * Run: npx vitest run src/components/brand/deals/deal-room-dashboard-actor-refresh.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { DealRoomDashboard } from './deal-room-dashboard';

const dealsList = vi.fn();
const dealsAccept = vi.fn();
const dealsReject = vi.fn();
const dealsCounter = vi.fn();
const messagesList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    deals: {
      list: (...a: unknown[]) => dealsList(...a),
      // F-0328 — the dashboard now calls deals.get('brand', id) on open; this test
      // isn't about that call, so stub it to a resolved no-op rather than let it
      // reject unhandled.
      get: vi.fn().mockResolvedValue(null),
      accept: (...a: unknown[]) => dealsAccept(...a),
      reject: (...a: unknown[]) => dealsReject(...a),
      counter: (...a: unknown[]) => dealsCounter(...a),
    },
    messages: {
      list: (...a: unknown[]) => messagesList(...a),
      markRead: vi.fn().mockResolvedValue({ ok: true }),
      stream: vi.fn(() => ({ close: vi.fn() })),
    },
  };
});

const PROPOSED_DEAL = {
  id: 'deal_1',
  campaignName: 'Summer Launch',
  counterpartyName: 'Aarti Menon',
  counterpartyAvatar: '',
  status: 'IN_NEGOTIATION',
  dealValue: 40000,
  deliverablesTotal: 2,
  nextDeadline: null,
  lastMessage: 'Looking forward to it',
  lastMessageAt: '2026-07-20T10:00:00Z',
  unreadCount: 0,
};

// Accept is only legal against the CREATOR's open offer in IN_NEGOTIATION (DealService.doAccept:
// AGREED_RATE_REQUIRED for a rate-less invite, CANNOT_ACCEPT_OWN_OFFER for the brand's own). This
// fixture used to be an INVITED deal with an empty timeline — a state in which the real server
// refuses every accept — so the test drove a button that could never have worked.
const CREATOR_OFFER = {
  id: 'msg_offer_1',
  dealId: 'deal_1',
  kind: 'proposal',
  senderId: 'creator_user_1',
  senderType: 'creator',
  content: 'I can do this for 40,000',
  metadata: { amount: 40000, status: 'pending', deliverables: [{ type: 'INSTAGRAM_REEL', quantity: 2 }] },
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

async function selectTheDeal(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByText('Aarti Menon'));
}

describe('DealRoomDashboard — actor-side refresh (CR-98 / F-0112)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([PROPOSED_DEAL]);
    messagesList.mockResolvedValue([CREATOR_OFFER]);
  });

  it('refetches messages (not just deals) after the brand accepts its own proposal', async () => {
    dealsAccept.mockResolvedValue({ ...PROPOSED_DEAL, status: 'CONTRACT_PENDING' });
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await selectTheDeal(user);
    messagesList.mockClear();
    dealsList.mockClear();

    await user.click(await screen.findByRole('button', { name: 'Accept Offer' }));

    await waitFor(() => expect(dealsAccept).toHaveBeenCalledWith('deal_1', 'brand'));
    await waitFor(() => expect(dealsList).toHaveBeenCalled());
    // THE regression this guards against: only loadDeals() ran, messages never refetched.
    await waitFor(() => expect(messagesList).toHaveBeenCalledWith('brand', 'deal_1'));
  });

  it('refetches messages after the brand rejects its own proposal', async () => {
    dealsReject.mockResolvedValue({ ...PROPOSED_DEAL, status: 'CANCELLED' });
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await selectTheDeal(user);
    messagesList.mockClear();
    dealsList.mockClear();

    await user.click(await screen.findByRole('button', { name: 'Reject' }));
    // Confirm in the dialog — its confirm button shares the dialog's own title text, so
    // scope by role to avoid matching the <DialogTitle>.
    await user.click(await screen.findByRole('button', { name: 'Reject Proposal' }));

    await waitFor(() => expect(dealsReject).toHaveBeenCalled());
    await waitFor(() => expect(dealsList).toHaveBeenCalled());
    await waitFor(() => expect(messagesList).toHaveBeenCalledWith('brand', 'deal_1'));
  });

  // The amount-only counter dialog is gone from live mode: an offer made here carried no
  // deliverables, so the contract later created zero Deliverable rows and the deal could never
  // be delivered or completed. Offers are made in the deal room, whose form collects them.
  it('sends the brand to the deal room to make an offer, and never posts an amount-only counter', async () => {
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await selectTheDeal(user);

    await user.click(await screen.findByRole('button', { name: 'Counter in deal room' }));

    expect(await screen.findByText('deal room ?deal=deal_1')).toBeInTheDocument();
    expect(dealsCounter).not.toHaveBeenCalled();
  });
});
