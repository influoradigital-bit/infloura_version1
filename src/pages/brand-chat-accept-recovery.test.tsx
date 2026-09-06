/**
 * F-0440 regression guard — deal-room Accept must leave a usable path forward on failure.
 *
 * The ticket describes: "the deal room accept handler dismisses the proposal dialog before
 * issuing the request and never reopens it, so on failure the dialog is gone and the user has no
 * way back."
 *
 * brand-chat.tsx's `handleAcceptProposal` renders the proposal as a card inline in the message
 * feed, not behind a dialog — there is nothing dismissed before the `await dealsApi.accept(...)`
 * call, and `isAcceptingProposal` always resets in a `finally`, so a transient failure leaves the
 * card, and its Accept/Counter buttons, exactly where they were: the brand can simply click
 * Accept again. This file pins that property so it cannot regress silently.
 *
 * (The bug AS DESCRIBED is real, but lives in a different `handleAcceptProposal` — the one in
 * src/components/brand/deals/deal-room-dashboard.tsx, which really does call
 * `setShowProposalDialog(false)` synchronously before its `await dealsApi.accept(...)` and never
 * reopens that dialog on the catch path. That file is out of scope for this assignment.)
 *
 * Run: npx vitest run src/pages/brand-chat-accept-recovery.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { DealMessageStreamHandlers } from '@/lib/api';
import BrandChatPage from './brand-chat';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const dealsList = vi.fn();
const dealsGet = vi.fn();
const dealsCounter = vi.fn();
const dealsAccept = vi.fn();
const messagesList = vi.fn();
const brandPlatformFee = vi.fn();
const messagesStream = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => dealsList(...a),
    get: (...a: unknown[]) => dealsGet(...a),
    counter: (...a: unknown[]) => dealsCounter(...a),
    accept: (...a: unknown[]) => dealsAccept(...a),
  };
  const messages = {
    list: (...a: unknown[]) => messagesList(...a),
    markRead: vi.fn().mockResolvedValue({ ok: true }),
    send: vi.fn(),
    stream: (...a: unknown[]) => messagesStream(...a),
  };
  const deliverables = {
    list: vi.fn().mockResolvedValue([]),
    approve: vi.fn(),
    requestRevision: vi.fn(),
  };
  return {
    ...actual,
    isApiLive: () => true,
    deals,
    messages,
    deliverables,
    api: {
      deals,
      messages,
      deliverables,
      contracts: { get: vi.fn(), generate: vi.fn() },
      wallet: { brandPlatformFee: (...a: unknown[]) => brandPlatformFee(...a) },
    },
  };
});

/** IN_NEGOTIATION — the state in which Collaboration.canCounter() is true. */
const DEAL = {
  id: 'deal_1',
  campaignId: 'camp_1',
  campaignName: 'Summer Launch',
  counterpartyId: 'cr_1',
  counterpartyName: 'Aarti Menon',
  counterpartyHandle: '@aarti',
  counterpartyAvatar: '',
  status: 'IN_NEGOTIATION',
  dealValue: 40000,
  currency: 'INR',
  lastMessage: 'Looking forward to it',
  lastMessageAt: new Date('2026-07-20T10:00:00Z').toISOString(),
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 2,
  nextDeadline: null,
  contractId: null,
  contractStatus: null,
  escrowFunded: false,
};

function proposalMessage(overrides: Record<string, unknown> = {}) {
  return {
    id: 'msg_p1',
    dealId: 'deal_1',
    kind: 'proposal',
    senderId: 'cr_1',
    senderType: 'creator',
    content: 'Counter proposal — ₹50,000',
    metadata: {
      amount: 50000,
      deliverables: [{ type: 'Instagram Reel', qty: 1 }],
      usageRights: '6 months',
      status: 'pending',
    },
    createdAt: new Date('2026-07-20T11:00:00Z').toISOString(),
    readBy: [],
    ...overrides,
  };
}

function renderChat() {
  return render(
    <MemoryRouter initialEntries={['/brand/chat']}>
      <BrandChatPage />
    </MemoryRouter>,
  );
}

describe('BrandChatPage — Accept survives a failed attempt (F-0440)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    messagesStream.mockImplementation(
      (_role: string, _dealId: string, _handlers: DealMessageStreamHandlers) => ({
        close: vi.fn(),
      }),
    );
    dealsGet.mockResolvedValue(DEAL);
    dealsList.mockResolvedValue([DEAL]);
    messagesList.mockResolvedValue([proposalMessage()]);
    brandPlatformFee.mockResolvedValue({
      feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '',
    });
  });

  it('keeps Accept clickable after a failed attempt, so a retry can still succeed', async () => {
    // A transient, non-409 failure — the class describeAcceptError treats as retryable
    // (`stale: false`), so the card is not supposed to hide its buttons.
    dealsAccept.mockRejectedValueOnce(new Error('network down'));
    dealsAccept.mockResolvedValueOnce({ id: 'deal_1', status: 'TERMS_AGREED' });

    const user = userEvent.setup({ delay: null });
    renderChat();

    const acceptButton = await screen.findByRole('button', { name: /^Accept$/i });
    await user.click(acceptButton);

    // Failure surfaced — both as a toast and as a banner left on the card.
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Could not accept proposal', variant: 'destructive' }),
      ),
    );
    expect(
      await screen.findByText(/Check your connection and try again/i),
    ).toBeTruthy();

    // The crux of F-0440: nothing was dismissed and never restored. The SAME Accept button is
    // still in the document and not stuck disabled — this is the "way back" the ticket says was
    // missing. If `isAcceptingProposal` were ever left `true` after a failure (the loading-flag
    // equivalent of a dialog that never reopens), this button would still read "Accepting..." and
    // be disabled, and the click below would find nothing to fire.
    const acceptAgain = await screen.findByRole('button', { name: /^Accept$/i });
    expect(acceptAgain).toBeEnabled();

    await user.click(acceptAgain);

    await waitFor(() => expect(dealsAccept).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Proposal accepted' }),
      ),
    );
  });

  it('a successful accept (no prior failure) still resolves exactly as before', async () => {
    dealsAccept.mockResolvedValue({ id: 'deal_1', status: 'TERMS_AGREED' });

    const user = userEvent.setup({ delay: null });
    renderChat();

    await user.click(await screen.findByRole('button', { name: /^Accept$/i }));

    await waitFor(() => expect(dealsAccept).toHaveBeenCalledTimes(1));
    expect(dealsAccept.mock.calls[0]).toEqual(['deal_1', 'brand']);
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Proposal accepted' }),
      ),
    );
    // No error banner left behind on a clean success.
    expect(screen.queryByText(/Check your connection and try again/i)).toBeNull();
  });
});
