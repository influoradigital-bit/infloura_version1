/**
 * Creator deal room — bare-invite dead-end fix.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Vikram root-caused (routed via Arjun, SHARED_CONTEXT.md): a brand invite sent with no budget
 * set (`Collaboration.invite()`, no priced offer) leaves the deal in `INVITED` status with ZERO
 * messages — `persistProposalMessage` only runs from `createProposal`/`doCounter`, never from a
 * bare invite. `dealAllowsProposalResponse`/`canRespondToProposal` correctly says the deal IS
 * still acceptable, but the only Accept/Decline UI lived inside the `type === 'proposal'`
 * message card, which a bare invite never creates — so the creator saw the plain "No messages
 * yet" empty state with no way to act.
 *
 * The fix adds a fallback card, gated on `events.length === 0 && canRespondToProposal`
 * (`showBareInviteResponse` in creator-chat.tsx), that reuses the existing deal-scoped
 * `handleAcceptProposal`/`handleDeclineProposal` handlers (`api.deals.accept`/`api.deals.reject`)
 * without fabricating any amount or earnings breakdown.
 *
 * HARNESS NOTES
 * -------------
 * Mock surface copied from `creator-chat-refresh.test.tsx`, the first (and so far only) test
 * harness for this page.
 *
 * Run: npx vitest run src/pages/creator-chat-bare-invite.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { DealMessage, DealMessageStreamHandlers } from '@/lib/api';
import CreatorChatPage from './creator-chat';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const dealsList = vi.fn();
const dealsGet = vi.fn();
const dealsAccept = vi.fn();
const dealsReject = vi.fn();
const messagesList = vi.fn();
const messagesStream = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => dealsList(...a),
    get: (...a: unknown[]) => dealsGet(...a),
    accept: (...a: unknown[]) => dealsAccept(...a),
    reject: (...a: unknown[]) => dealsReject(...a),
    counter: vi.fn().mockResolvedValue({ id: 'deal_1' }),
  };
  const messages = {
    list: (...a: unknown[]) => messagesList(...a),
    markRead: vi.fn().mockResolvedValue({ ok: true }),
    send: vi.fn(),
    stream: (...a: unknown[]) => messagesStream(...a),
  };
  return {
    ...actual,
    isApiLive: () => true,
    deals,
    messages,
    api: {
      deals,
      messages,
      contracts: { get: vi.fn().mockResolvedValue(null) },
      creatorDeliverables: {
        listForDeal: vi.fn().mockResolvedValue([]),
        upload: vi.fn(),
      },
      deliverables: { submit: vi.fn() },
      shipments: {
        get: vi.fn().mockResolvedValue(null),
        submitAddress: vi.fn(),
        confirmReceipt: vi.fn(),
      },
      wallet: {
        platformFee: vi
          .fn()
          .mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '' }),
      },
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
    },
  };
});

/** Bare invite — INVITED status, no priced offer, no messages. */
const INVITED_DEAL = {
  id: 'deal_bare',
  campaignId: 'camp_1',
  campaignName: 'Diwali Skincare Reels',
  counterpartyId: 'b_1',
  counterpartyName: 'Mamaearth',
  counterpartyHandle: '@mamaearth',
  counterpartyAvatar: '',
  status: 'INVITED',
  dealValue: null,
  currency: 'INR',
  lastMessage: null,
  lastMessageAt: new Date('2026-08-01T10:00:00Z').toISOString(),
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 0,
  nextDeadline: null,
  contractId: null,
  contractStatus: null,
  escrowFunded: false,
};

/**
 * CR-02 fresh-context reject (Priya) — the creator's OWN pending application. `APPLIED` is
 * in `ACCEPTABLE_COLLABORATION_STATUSES` (so `canRespondToProposal` is true) and has zero
 * messages for the same structural reason INVITED does — but this collaboration was started
 * BY the creator, not the brand, so the bare-invite card's "Brand X invited you..." copy would
 * be false, and its Accept button would self-accept the creator's own application.
 */
const APPLIED_DEAL = {
  ...INVITED_DEAL,
  id: 'deal_applied',
  status: 'APPLIED',
};

/**
 * A deal that is no longer respondable at all (already settled) and also happens to have
 * zero messages. Must fall through to the plain empty state, not any invite/response card.
 */
const TERMS_AGREED_DEAL = {
  ...INVITED_DEAL,
  id: 'deal_terms_agreed',
  status: 'TERMS_AGREED',
};

/** Same INVITED deal, but WITH a real priced proposal message already on the thread. */
const PROPOSAL_MESSAGE = {
  id: 'msg_proposal_1',
  dealId: 'deal_bare',
  kind: 'proposal',
  senderId: 'b_1',
  senderType: 'brand',
  content: 'Here is our proposal for your review.',
  createdAt: new Date('2026-08-01T11:00:00Z').toISOString(),
  readBy: [],
  metadata: {
    proposalType: 'initial',
    status: 'pending',
    amount: 45000,
    deliverables: [{ type: 'Instagram Reel', quantity: 2 }],
    usageRights: '6 months',
  },
} as unknown as DealMessage;

function renderRoom(dealPath = 'deal_bare') {
  return render(
    <MemoryRouter initialEntries={[`/creator/chat?deal=${dealPath}`]}>
      <CreatorChatPage />
    </MemoryRouter>,
  );
}

describe('CreatorChatPage — bare-invite Accept/Decline fallback', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    messagesStream.mockImplementation(
      (_role: string, _dealId: string, handlers: DealMessageStreamHandlers) => {
        void handlers;
        return { close: vi.fn() };
      },
    );
    dealsList.mockResolvedValue([INVITED_DEAL]);
    dealsGet.mockResolvedValue(INVITED_DEAL);
    dealsAccept.mockResolvedValue({ id: 'deal_bare' });
    dealsReject.mockResolvedValue({ id: 'deal_bare' });
    messagesList.mockResolvedValue([]);
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('shows the invite-response card (not "No messages yet") for a zero-message, still-acceptable deal, and wires Accept to api.deals.accept', async () => {
    renderRoom();

    // The old dead-end empty state must be gone.
    await waitFor(() =>
      expect(screen.queryByText(/No messages yet/i)).not.toBeInTheDocument(),
    );

    // Honest copy: names the campaign, does not fabricate an amount or "Counter".
    expect(await screen.findByText('Campaign Invite')).toBeInTheDocument();
    expect(screen.getAllByText(/Diwali Skincare Reels/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Earnings Breakdown/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Counter')).not.toBeInTheDocument();

    const acceptButton = screen.getByRole('button', { name: /^Accept$/ });
    await userEvent.click(acceptButton);

    await waitFor(() =>
      expect(dealsAccept).toHaveBeenCalledWith('deal_bare', 'creator'),
    );
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Proposal accepted' }),
      ),
    );
  });

  it('wires Decline to api.deals.reject with the real deal id', async () => {
    renderRoom();
    await screen.findByText('Campaign Invite');

    const declineButton = screen.getByRole('button', { name: /^Decline$/ });
    await userEvent.click(declineButton);

    await waitFor(() =>
      expect(dealsReject).toHaveBeenCalledWith('deal_bare', undefined, 'creator'),
    );
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Proposal declined' }),
      ),
    );
  });

  it('regression: a deal WITH a real proposal message still shows the original priced proposal card, not the bare-invite fallback', async () => {
    messagesList.mockResolvedValue([PROPOSAL_MESSAGE]);
    renderRoom();

    // The priced card's own content must render — amount, "Brand Proposal" heading, breakdown.
    await waitFor(() => expect(screen.getByText('Brand Proposal')).toBeInTheDocument());
    expect(screen.getByText('Your Earnings Breakdown')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Counter$/ })).toBeInTheDocument();

    // The bare-invite fallback must NOT render alongside it.
    expect(screen.queryByText('Campaign Invite')).not.toBeInTheDocument();

    // Accept on the real card still calls the same deal-scoped endpoint.
    const acceptButtons = screen.getAllByRole('button', { name: /^Accept$/ });
    expect(acceptButtons.length).toBe(1);
    await userEvent.click(acceptButtons[0]);
    await waitFor(() =>
      expect(dealsAccept).toHaveBeenCalledWith('deal_bare', 'creator'),
    );
  });

  it('CR-02: does NOT show the bare-invite card (or any Accept control) for the creator\'s own zero-message APPLIED application', async () => {
    dealsList.mockResolvedValue([APPLIED_DEAL]);
    dealsGet.mockResolvedValue(APPLIED_DEAL);
    renderRoom('deal_applied');

    // Zero-message, non-INVITED deal: falls through to the plain empty state.
    expect(await screen.findByText(/No messages yet/i)).toBeInTheDocument();

    // The false "Brand X invited you..." card must never render for the creator's own application.
    expect(screen.queryByText('Campaign Invite')).not.toBeInTheDocument();
    // No Accept control anywhere on the page — this would otherwise be a self-accept.
    expect(screen.queryByRole('button', { name: /^Accept$/ })).not.toBeInTheDocument();
  });

  it('CR-02: does NOT show the bare-invite card for an already-settled, zero-message TERMS_AGREED deal — falls back to the plain empty state', async () => {
    dealsList.mockResolvedValue([TERMS_AGREED_DEAL]);
    dealsGet.mockResolvedValue(TERMS_AGREED_DEAL);
    renderRoom('deal_terms_agreed');

    expect(await screen.findByText(/No messages yet/i)).toBeInTheDocument();
    expect(screen.queryByText('Campaign Invite')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Accept$/ })).not.toBeInTheDocument();
  });
});

/**
 * F-0291 — the gate that keeps this card alive once an invite HAS messages.
 *
 * The original gate was `events.length === 0`, which was correct only by accident: a bare invite
 * happened to produce zero messages because `CreatorDiscoveryService#invite` persisted none. That
 * is now fixed — an invitation writes a system row plus the brand's note — so the emptiness gate
 * would have silently retired this card and left an invited creator with NO way to accept, which
 * is strictly worse than the empty room it replaced.
 *
 * The card is now keyed off "no priced proposal card exists", which is what it was always for.
 * These two tests are the pair that has to hold: it SURVIVES ordinary messages, and it STANDS
 * DOWN when a real proposal card arrives with its own Accept controls.
 */
describe('CreatorChatPage — F-0291: the invite card survives a non-empty room', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    messagesStream.mockImplementation(
      (_role: string, _dealId: string, handlers: DealMessageStreamHandlers) => {
        void handlers;
        return { close: vi.fn() };
      },
    );
    dealsList.mockResolvedValue([INVITED_DEAL]);
    dealsGet.mockResolvedValue(INVITED_DEAL);
    dealsAccept.mockResolvedValue({ id: 'deal_bare' });
    dealsReject.mockResolvedValue({ id: 'deal_bare' });
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('still offers Accept when the invite wrote its own timeline rows', async () => {
    // Exactly what CreatorDiscoveryService#invite now persists: a system event + the brand note.
    messagesList.mockResolvedValue([
      {
        id: 'm_sys',
        dealId: 'deal_bare',
        kind: 'system',
        senderType: 'system',
        senderId: 'system',
        content: 'Brand invited this creator to the campaign',
        createdAt: new Date().toISOString(),
      },
      {
        id: 'm_note',
        dealId: 'deal_bare',
        kind: 'text',
        senderType: 'brand',
        senderId: 'brand_1',
        content: 'We would love to work with you',
        createdAt: new Date().toISOString(),
      },
    ] as unknown as DealMessage[]);

    renderRoom();

    // The whole point: a non-empty room must NOT cost the creator their Accept control.
    const acceptButton = await screen.findByRole('button', { name: /^Accept$/ });
    await userEvent.click(acceptButton);
    await waitFor(() => expect(dealsAccept).toHaveBeenCalledWith('deal_bare', 'creator'));
  });

  it('stands down once a real proposal card exists, so Accept is not offered twice', async () => {
    messagesList.mockResolvedValue([
      {
        id: 'm_prop',
        dealId: 'deal_bare',
        kind: 'proposal',
        senderType: 'brand',
        senderId: 'brand_1',
        content: 'Our offer',
        metadata: { amount: 7000, status: 'pending' },
        createdAt: new Date().toISOString(),
      },
    ] as unknown as DealMessage[]);

    renderRoom();

    await waitFor(() =>
      expect(screen.queryByText('Campaign Invite')).not.toBeInTheDocument(),
    );
  });
});
