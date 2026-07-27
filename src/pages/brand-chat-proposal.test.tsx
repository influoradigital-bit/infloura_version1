/**
 * Deal-room "Send Proposal" — wired to POST /deals/:id/counter.
 *
 * Until 2026-07-26 `handleSendProposal` was `await new Promise(r => setTimeout(r, 1500))` with a
 * comment reading "In real app: add proposal to mockEvents or call API". A brand filled in five
 * steps of terms, watched a spinner, and the modal closed having sent nothing.
 *
 * Counter — not create — is the correct verb here: by the time a deal room exists the
 * Collaboration exists, so POST /deals would 409 COLLABORATION_EXISTS.
 *
 * Run: npx vitest run src/pages/brand-chat-proposal.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { DealMessage, DealMessageStreamHandlers } from '@/lib/api';
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

/**
 * Handlers the page passed to `messages.stream`, so a test can push SSE frames at the room the
 * way CR-08's publishes will. Captured rather than stubbed away — W2-C1 lives entirely in what
 * `onMessage` does with a frame.
 */
let streamHandlers: DealMessageStreamHandlers | null = null;
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

function renderChat() {
  return render(
    <MemoryRouter initialEntries={['/brand/chat']}>
      <BrandChatPage />
    </MemoryRouter>,
  );
}

/**
 * Opens the modal and walks the 5-step wizard: Next x4, then submit.
 *
 * Everything is scoped to the modal because "Send Proposal" names BOTH the header trigger and
 * the wizard's submit button — unscoped queries match two elements once the modal is open.
 */
async function openAndCompleteWizard(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /Send Proposal/i }));

  const heading = await screen.findByRole('heading', { name: /Send Proposal to Aarti Menon/i });
  const modal = heading.closest('div.fixed') as HTMLElement;
  expect(modal).toBeTruthy();

  for (let i = 0; i < 4; i++) {
    await user.click(within(modal).getByRole('button', { name: /^Next$/i }));
  }
  await user.click(within(modal).getByRole('button', { name: /^Send Proposal$/i }));
}

describe('BrandChatPage — deal-room proposal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    streamHandlers = null;
    messagesStream.mockImplementation(
      (_role: string, _dealId: string, handlers: DealMessageStreamHandlers) => {
        streamHandlers = handlers;
        return { close: vi.fn() };
      },
    );
    dealsGet.mockResolvedValue(DEAL);
    dealsList.mockResolvedValue([DEAL]);
    messagesList.mockResolvedValue([]);
    dealsCounter.mockResolvedValue({ id: 'deal_1' });
    dealsAccept.mockResolvedValue({ id: 'deal_1', status: 'TERMS_AGREED' });
    brandPlatformFee.mockResolvedValue({
      feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '',
    });
  });

  it('sends the proposal as a counter with the collected terms', async () => {
    const user = userEvent.setup();
    renderChat();

    await openAndCompleteWizard(user);

    await waitFor(() => expect(dealsCounter).toHaveBeenCalledTimes(1));

    const [dealId, body, role, idempotencyKey] = dealsCounter.mock.calls[0];
    expect(dealId).toBe('deal_1');
    expect(role).toBe('brand');
    expect(body.amount).toBe(50000);
    // Local shape is {id, type, count}; DealDtos.DeliverableSlot expects {type, qty}.
    expect(body.deliverables).toEqual([{ type: 'Instagram Reel', qty: 1 }]);
    // Since CounterRequest was aligned with CreateDealRequest, usage rights is a real field the
    // server persists onto the deal — not prose stuffed into the message where it can only be
    // read by a human.
    expect(body.usageRights).toBe('6 months');
    expect(body.message).not.toMatch(/Usage rights:/i);
    // Fresh key per submit — otherwise the server derives one from dealId+amount and a
    // re-proposal at the same figure is swallowed as a replay.
    expect(idempotencyKey).toMatch(/^deal_1-proposal-\d+$/);
  });

  it('surfaces a DEAL_NOT_NEGOTIABLE conflict instead of failing silently', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    dealsCounter.mockRejectedValue(
      new ApiError('DEAL_NOT_NEGOTIABLE', 'This deal cannot be countered in its current state', 409),
    );
    const user = userEvent.setup();
    renderChat();

    await openAndCompleteWizard(user);

    await waitFor(() => expect(toastMock).toHaveBeenCalled());
    const call = toastMock.mock.calls.at(-1)?.[0];
    expect(call.title).toBe('Proposal failed');
    expect(call.description).toMatch(/moved past negotiation/i);
  });

  it('hides Send Proposal once the deal is past negotiation', async () => {
    dealsList.mockResolvedValue([{ ...DEAL, status: 'COMPLETED' }]);
    renderChat();

    // Wait for the room to load, then assert the control is absent — countering a COMPLETED
    // deal could only ever 409, so offering a five-step form for it is a dead end.
    await waitFor(() => expect(dealsList).toHaveBeenCalled());
    await waitFor(() => expect(screen.getAllByText(/Aarti Menon/i).length).toBeGreaterThan(0));
    expect(screen.queryAllByRole('button', { name: /Send Proposal/i })).toHaveLength(0);
  });
});

/**
 * CR-07 — Accept and Counter on the proposal card.
 *
 * Both buttons shipped as bare `<Button>` elements with styling and no `onClick` whatsoever:
 * not disabled, not gated, no handler that threw or swallowed anything — clicking them was a
 * no-op. Separately, the live-mode feed rendered every message as a plain text bubble, so a
 * creator's counter (a real `kind: 'proposal'` message) arrived without its terms and without
 * the buttons at all.
 */
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

describe('BrandChatPage — responding to a proposal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    streamHandlers = null;
    messagesStream.mockImplementation(
      (_role: string, _dealId: string, handlers: DealMessageStreamHandlers) => {
        streamHandlers = handlers;
        return { close: vi.fn() };
      },
    );
    dealsGet.mockResolvedValue(DEAL);
    dealsList.mockResolvedValue([DEAL]);
    messagesList.mockResolvedValue([proposalMessage()]);
    dealsCounter.mockResolvedValue({ id: 'deal_1' });
    dealsAccept.mockResolvedValue({ id: 'deal_1', status: 'TERMS_AGREED' });
    brandPlatformFee.mockResolvedValue({
      feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '',
    });
  });

  it('accepts the creator\'s offer as the brand', async () => {
    const user = userEvent.setup();
    renderChat();

    await user.click(await screen.findByRole('button', { name: /^Accept$/i }));

    await waitFor(() => expect(dealsAccept).toHaveBeenCalledTimes(1));
    // Deal-level, role-tagged: DealService.accept resolves the offer on the table itself, and
    // 403'd every brand caller before B-4 made it role-aware.
    expect(dealsAccept.mock.calls[0]).toEqual(['deal_1', 'brand']);

    await waitFor(() => {
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Proposal accepted' }),
      );
    });
  });

  it('surfaces a failed accept instead of leaving the click silent', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    dealsAccept.mockRejectedValue(
      new ApiError('DEAL_NOT_ACCEPTABLE', 'This deal cannot be accepted in its current state', 409),
    );
    const user = userEvent.setup();
    renderChat();

    await user.click(await screen.findByRole('button', { name: /^Accept$/i }));

    await waitFor(() => expect(toastMock).toHaveBeenCalled());
    const call = toastMock.mock.calls.at(-1)?.[0];
    expect(call.title).toBe('Could not accept proposal');
    expect(call.variant).toBe('destructive');
    // The banner persists on the card after the toast has gone.
    expect(await screen.findByText(/moved past the offer stage/i)).toBeTruthy();
  });

  it('offers Counter but not Accept on the brand\'s own offer', async () => {
    // CANNOT_ACCEPT_OWN_OFFER (DealService.doAccept): only the counterparty may accept the
    // offer currently on the table, so Accept must not be rendered at all here.
    messagesList.mockResolvedValue([proposalMessage({ senderType: 'brand', senderId: 'u_1' })]);
    renderChat();

    await waitFor(() => expect(messagesList).toHaveBeenCalled());
    const counterButtons = await screen.findAllByRole('button', { name: /^Counter$/i });
    expect(counterButtons.length).toBe(1);
    expect(screen.queryByRole('button', { name: /^Accept$/i })).toBeNull();
  });

  it('hides both controls once the deal has left negotiation', async () => {
    dealsList.mockResolvedValue([{ ...DEAL, status: 'CONTRACTED' }]);
    renderChat();

    await waitFor(() => expect(messagesList).toHaveBeenCalled());
    // The card still says "pending" — settleLatestProposal only runs on accept/reject — so the
    // deal's own status is what has to close the buttons down.
    expect(await screen.findByText(/no longer on the table/i)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Accept$/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Counter$/i })).toBeNull();
  });

  /**
   * W2-C1 regression guard.
   *
   * The stream merge used to be ignore-if-present, which was correct while only `sendMessage`
   * published and messages were immutable. CR-08 republishes the SETTLED proposal card under
   * its ORIGINAL id with mutated metadata, and DealMessageStreamRegistry keys emitters by deal
   * id with no role partition, so this room receives the creator's frames too. Discarding the
   * known id threw away the very update that retires Accept — the brand then saw "Creator
   * accepted the proposal" above a Pending card with a live Accept beneath it, and clicking it
   * 409'd DEAL_NOT_ACCEPTABLE. That is CR-02, reopened.
   */
  it('replaces a settled card republished under its original id, retiring the buttons', async () => {
    renderChat();
    expect(await screen.findByRole('button', { name: /^Accept$/i })).toBeTruthy();

    // Frame 1, exactly as CR-08 publishes it: same id, mutated metadata, settled.
    const settled = proposalMessage({
      metadata: {
        amount: 50000,
        deliverables: [{ type: 'Instagram Reel', qty: 1 }],
        usageRights: '6 months',
        status: 'accepted',
      },
    }) as unknown as DealMessage;
    await act(async () => {
      streamHandlers?.onMessage(settled);
    });

    // Replaced in place, not appended alongside: one card, now reading Accepted.
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Accept$/i })).toBeNull());
    expect(screen.queryByRole('button', { name: /^Counter$/i })).toBeNull();
    expect(screen.getAllByText(/^Accepted$/)).toHaveLength(1);
  });

  /**
   * W2-H1 regression guard — the half the card alone cannot cover.
   *
   * `settleLatestProposal` no-ops when a deal has no proposal card, so an INVITED deal accepted
   * off the invite reaches TERMS_AGREED with nothing to settle and only a system frame is
   * published. Without a deal refetch, `rawStatus` stays IN_NEGOTIATION forever and the gate
   * keeps both buttons alive on a closed deal.
   */
  it('re-reads the deal on a system frame, closing the gate with the card untouched', async () => {
    dealsGet.mockResolvedValue({ ...DEAL, status: 'TERMS_AGREED' });
    renderChat();
    expect(await screen.findByRole('button', { name: /^Accept$/i })).toBeTruthy();

    const systemFrame = {
      id: 'msg_sys1',
      dealId: 'deal_1',
      kind: 'system',
      senderId: 'system',
      senderType: 'system',
      content: 'Creator accepted the proposal',
      createdAt: new Date('2026-07-20T11:05:00Z').toISOString(),
      readBy: [],
    } as unknown as DealMessage;
    await act(async () => {
      streamHandlers?.onMessage(systemFrame);
    });

    await waitFor(() => expect(dealsGet).toHaveBeenCalledWith('brand', 'deal_1'));
    // The card still says Pending — nothing settled it — so only the deal's own status can
    // close this down.
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Accept$/i })).toBeNull());
    expect(screen.queryByRole('button', { name: /^Counter$/i })).toBeNull();
    expect(screen.getAllByText(/^Pending$/)).toHaveLength(1);
  });

  it('surfaces a failed deal refresh instead of leaving a stale gate silent', async () => {
    dealsGet.mockRejectedValue(new Error('network down'));
    renderChat();
    await screen.findByRole('button', { name: /^Accept$/i });

    await act(async () => {
      streamHandlers?.onMessage({
        id: 'msg_sys1',
        dealId: 'deal_1',
        kind: 'system',
        senderId: 'system',
        senderType: 'system',
        content: 'Creator accepted the proposal',
        createdAt: new Date('2026-07-20T11:05:00Z').toISOString(),
        readBy: [],
      } as unknown as DealMessage);
    });

    await waitFor(() => {
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Could not refresh this deal', variant: 'destructive' }),
      );
    });
  });

  it('opens the proposal wizard when Counter is clicked', async () => {
    const user = userEvent.setup();
    renderChat();

    await user.click(await screen.findByRole('button', { name: /^Counter$/i }));

    // Counter IS the five-step wizard — the same form that POSTs /deals/:id/counter.
    expect(
      await screen.findByRole('heading', { name: /Send Proposal to Aarti Menon/i }),
    ).toBeTruthy();
  });
});
