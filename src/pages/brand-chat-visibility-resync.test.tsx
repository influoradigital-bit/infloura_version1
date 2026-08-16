/**
 * Brand deal chat — foreground resync on a backgrounded tab (CR-93 / F-0110 — brand half).
 * See creator-chat-visibility-resync.test.tsx for the full rationale (mirrors it exactly);
 * this is a separate file because vitest hoists `vi.mock('@/lib/api', ...)` per file, so the
 * two roles cannot share one module mock.
 *
 * Run: npx vitest run src/pages/brand-chat-visibility-resync.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandChatPage from './brand-chat';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const dealsList = vi.fn();
const dealsGet = vi.fn();
const messagesList = vi.fn();
const deliverablesList = vi.fn();
const brandPlatformFee = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => dealsList(...a),
    get: (...a: unknown[]) => dealsGet(...a),
    counter: vi.fn(),
    accept: vi.fn(),
  };
  const messages = {
    list: (...a: unknown[]) => messagesList(...a),
    markRead: vi.fn().mockResolvedValue({ ok: true }),
    send: vi.fn(),
    stream: vi.fn(() => ({ close: vi.fn() })),
  };
  const deliverables = {
    list: (...a: unknown[]) => deliverablesList(...a),
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

async function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

function renderChat() {
  return render(
    <MemoryRouter initialEntries={['/brand/chat?deal=deal_1']}>
      <BrandChatPage />
    </MemoryRouter>,
  );
}

describe('BrandChatPage — foreground resync (CR-93 / F-0110 — brand half)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([DEAL]);
    dealsGet.mockResolvedValue(DEAL);
    messagesList.mockResolvedValue([]);
    deliverablesList.mockResolvedValue([]);
    brandPlatformFee.mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '' });
  });

  it('does nothing on the initial mount beyond the normal load', async () => {
    renderChat();
    await screen.findAllByText('Summer Launch');
    const callsAfterMount = messagesList.mock.calls.length;

    await setVisibility('hidden');
    expect(messagesList.mock.calls.length).toBe(callsAfterMount);
  });

  it('refetches messages and the deal on becoming visible again', async () => {
    renderChat();
    await screen.findAllByText('Summer Launch');
    messagesList.mockClear();
    dealsGet.mockClear();

    await setVisibility('hidden');
    await setVisibility('visible');

    await waitFor(() => expect(messagesList).toHaveBeenCalledWith('brand', 'deal_1'));
    await waitFor(() => expect(dealsGet).toHaveBeenCalledWith('brand', 'deal_1'));
  });
});

function plainMessage(overrides: Record<string, unknown> = {}) {
  return {
    id: 'msg_1',
    dealId: 'deal_1',
    kind: 'text',
    senderId: 'cr_1',
    senderType: 'creator',
    content: 'Hey, excited to work on this!',
    metadata: {},
    createdAt: new Date('2026-07-20T11:00:00Z').toISOString(),
    readBy: [],
    ...overrides,
  };
}

describe('BrandChatPage — F-0151/F-0152: background resync must not blank or corrupt an already-rendered thread', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([DEAL]);
    dealsGet.mockResolvedValue(DEAL);
    deliverablesList.mockResolvedValue([]);
    brandPlatformFee.mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '' });
  });

  it('F-0151: a background resync (foreground visibility) never replaces an already-rendered thread with the loading spinner', async () => {
    messagesList.mockResolvedValue([plainMessage()]);
    renderChat();
    await screen.findByText('Hey, excited to work on this!');

    // Make the resync's fetch hang so messagesLoading is provably true while this assertion runs.
    let resolveResync: (v: unknown) => void = () => {};
    messagesList.mockImplementation(
      () => new Promise((resolve) => { resolveResync = resolve; }),
    );

    await setVisibility('hidden');
    await setVisibility('visible');

    await waitFor(() => expect(messagesList).toHaveBeenCalled());
    // Regression: the old code gated the entire message list on `!messagesLoading`, so this
    // resync (still in flight) would have already unmounted the thread below.
    expect(screen.getByText('Hey, excited to work on this!')).toBeInTheDocument();

    await act(async () => {
      resolveResync([plainMessage()]);
    });
  });

  it('F-0152: a slower earlier request cannot overwrite a faster, newer request\'s result', async () => {
    // Two overlapping loadMessages calls (e.g. a foreground resync racing SSE onReconnect):
    // the FIRST call is the slow one, the SECOND is faster and resolves first. The slow one
    // must not clobber the fast one's result when it finally resolves.
    let resolveFirst: (v: unknown) => void = () => {};
    const first = new Promise((resolve) => { resolveFirst = resolve; });
    messagesList
      .mockImplementationOnce(() => first) // initial mount load
      .mockResolvedValueOnce([plainMessage({ id: 'msg_fast', content: 'Fast, newer reply' })]); // second (racing) call

    renderChat();
    await screen.findAllByText('Summer Launch');

    // Trigger the second, faster call while the first is still in flight.
    await setVisibility('hidden');
    await setVisibility('visible');

    await waitFor(() => expect(screen.getByText('Fast, newer reply')).toBeInTheDocument());

    // Now let the slow, stale FIRST call resolve — it must be ignored, not overwrite the thread.
    await act(async () => {
      resolveFirst([plainMessage({ id: 'msg_stale', content: 'Stale, older reply' })]);
    });

    expect(screen.getByText('Fast, newer reply')).toBeInTheDocument();
    expect(screen.queryByText('Stale, older reply')).not.toBeInTheDocument();
  });

  it('F-0152: a failed background resync leaves the already-rendered thread alone instead of wiping it', async () => {
    messagesList.mockResolvedValueOnce([plainMessage()]);
    renderChat();
    await screen.findByText('Hey, excited to work on this!');

    messagesList.mockRejectedValueOnce(new Error('network blip'));
    await setVisibility('hidden');
    await setVisibility('visible');

    await waitFor(() => expect(screen.getByText('Could not load messages. Check your connection and retry.')).toBeInTheDocument());
    // Regression: this used to unconditionally clear liveMessages to [] on any failure.
    expect(screen.getByText('Hey, excited to work on this!')).toBeInTheDocument();
  });

  it('F-0192: switching deals clears the previous deal\'s thread instead of bleeding it into the new deal', async () => {
    const DEAL_2 = {
      ...DEAL,
      id: 'deal_2',
      campaignName: 'Winter Drop',
      counterpartyId: 'cr_2',
      counterpartyName: 'Rohan Verma',
    };
    dealsList.mockResolvedValue([DEAL, DEAL_2]);
    dealsGet.mockImplementation((_role: string, id: string) =>
      Promise.resolve(id === 'deal_2' ? DEAL_2 : DEAL),
    );

    let resolveDeal2Messages: (v: unknown) => void = () => {};
    messagesList.mockImplementation((_role: string, dealId: string) => {
      if (dealId === 'deal_2') {
        return new Promise((resolve) => {
          resolveDeal2Messages = resolve;
        });
      }
      return Promise.resolve([plainMessage()]);
    });

    renderChat();
    await screen.findByText('Hey, excited to work on this!');

    const user = userEvent.setup({ delay: null });
    await user.click(screen.getByText('Rohan Verma'));

    // Deal 2's fetch is still in flight — deal 1's message must NOT still be on screen.
    await waitFor(() => expect(messagesList).toHaveBeenCalledWith('brand', 'deal_2'));
    expect(screen.queryByText('Hey, excited to work on this!')).not.toBeInTheDocument();

    await act(async () => {
      resolveDeal2Messages([plainMessage({ id: 'msg_2', content: 'Deal 2 kickoff message' })]);
    });

    expect(await screen.findByText('Deal 2 kickoff message')).toBeInTheDocument();
    expect(screen.queryByText('Hey, excited to work on this!')).not.toBeInTheDocument();
  });
});

function deliverableRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 'del_1',
    title: 'Reel 1',
    status: 'SUBMITTED',
    ...overrides,
  };
}

function dealFixture(overrides: Record<string, unknown> = {}) {
  return { ...DEAL, ...overrides };
}

describe('BrandChatPage — F-0195: loadDeliverables must not be corrupted by a stale, out-of-order response', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([DEAL]);
    dealsGet.mockResolvedValue(DEAL);
    messagesList.mockResolvedValue([]);
    brandPlatformFee.mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '' });
  });

  // Priya review correction: F-0195 was opened as "cross-deal-deliverables-bleed-on-switch" (same
  // class as F-0192), but that symptom doesn't hold up — the deliverables panel is a Radix Sheet
  // gated by a real `deliverablesLoading` ternary (see brand-chat.tsx's render), and selectDeal()
  // already force-closes it on every switch. Both independently prevent a previous deal's rows
  // from ever being painted under a new deal's header; confirmed by mutation-testing a render-based
  // "switch, reopen, assert old rows absent" test — it passed identically with or without the
  // `setLiveDeliverables([])` clear, so that test was removed rather than kept as false coverage.
  // Reclassified: the real, currently-reachable defect this fixes is `unguarded-concurrent-fetch`
  // in loadDeliverables (same class F-0152 fixed for loadMessages) — a slow, stale response for a
  // previously-selected deal had no staleness guard and could overwrite a newer deal's
  // freshly-loaded rows. The test below is what's actually mutation-proven.
  it("F-0195: a slow, stale deliverables response for a previously-selected deal cannot overwrite a newer deal's freshly-loaded rows", async () => {
    const DEAL_2 = dealFixture({
      id: 'deal_2',
      campaignName: 'Winter Drop',
      counterpartyId: 'cr_2',
      counterpartyName: 'Rohan Verma',
    });
    dealsList.mockResolvedValue([DEAL, DEAL_2]);
    dealsGet.mockImplementation((_role: string, id: string) =>
      Promise.resolve(id === 'deal_2' ? DEAL_2 : DEAL),
    );

    let resolveDeal1Deliverables: (v: unknown) => void = () => {};
    deliverablesList.mockImplementation((_role: string, dealId: string) => {
      if (dealId === 'deal_1') {
        return new Promise((resolve) => {
          resolveDeal1Deliverables = resolve;
        });
      }
      return Promise.resolve([deliverableRow({ id: 'del_2', title: 'Deal 2 deliverable' })]);
    });

    renderChat();
    await screen.findAllByText('Summer Launch');

    const user = userEvent.setup({ delay: null });
    // deal 1's fetch is still pending (held by resolveDeal1Deliverables) when we switch away.
    await user.click(screen.getByText('Rohan Verma'));
    await user.click(screen.getByRole('button', { name: /Deliverables/ }));

    // deal 2 loads fast and correctly.
    expect(await screen.findByText('Deal 2 deliverable')).toBeInTheDocument();

    // NOW the slow, stale deal-1 response finally resolves — it must be ignored, not overwrite
    // deal 2's freshly-rendered rows (the same request-token race F-0152 closed for messages).
    await act(async () => {
      resolveDeal1Deliverables([deliverableRow({ id: 'del_1', title: 'Reel 1' })]);
    });

    expect(screen.getByText('Deal 2 deliverable')).toBeInTheDocument();
    expect(screen.queryByText('Reel 1')).not.toBeInTheDocument();
  });
});
