/**
 * Creator deal chat — foreground resync on a backgrounded tab (CR-93 / F-0110 — creator half).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * CR-31 already made the SSE transport reconnect with exponential backoff and refetch
 * (`onReconnect` -> loadMessages + refreshDeal) whenever the connection actually errors or
 * closes. What it does NOT cover: a backgrounded tab, where a browser can suspend/throttle an
 * in-flight `fetch` read with no error and no close event ever firing — the stream just goes
 * quiet with nothing to trigger `onReconnect`. The counterparty's accept/reject/message is
 * already persisted server-side but never appears until something else forces a refetch. The
 * fix: foregrounding the tab (`visibilitychange` -> 'visible') unconditionally re-syncs
 * messages + deal state, independent of whatever the SSE transport currently believes.
 *
 * HARNESS NOTES — mirrors creator-chat-refresh.test.tsx's mock surface. See
 * brand-chat-visibility-resync.test.tsx for the brand half (a separate file: vitest hoists
 * `vi.mock('@/lib/api', ...)` per file, so the two roles cannot share one module mock).
 * `messages.stream` is stubbed to a no-op handle (a real `close()`), since these tests exercise
 * the visibility listener, not the transport.
 *
 * Run: npx vitest run src/pages/creator-chat-visibility-resync.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorChatPage from './creator-chat';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const creatorDealsList = vi.fn();
const creatorDealsGet = vi.fn();
const creatorMessagesList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => creatorDealsList(...a),
    get: (...a: unknown[]) => creatorDealsGet(...a),
    accept: vi.fn().mockResolvedValue({ id: 'deal_1' }),
    reject: vi.fn().mockResolvedValue({ id: 'deal_1' }),
    counter: vi.fn().mockResolvedValue({ id: 'deal_1' }),
  };
  const messages = {
    list: (...a: unknown[]) => creatorMessagesList(...a),
    markRead: vi.fn().mockResolvedValue({ ok: true }),
    send: vi.fn(),
    stream: vi.fn(() => ({ close: vi.fn() })),
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
      creatorDeliverables: { listForDeal: vi.fn().mockResolvedValue([]), upload: vi.fn() },
      deliverables: { submit: vi.fn(), list: vi.fn().mockResolvedValue([]) },
      shipments: { get: vi.fn().mockResolvedValue(null), submitAddress: vi.fn(), confirmReceipt: vi.fn() },
      wallet: {
        platformFee: vi.fn().mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '' }),
        brandPlatformFee: vi.fn().mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '' }),
      },
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
    },
  };
});

const DEAL = {
  id: 'deal_1',
  campaignId: 'camp_1',
  campaignName: 'Diwali Skincare Reels',
  counterpartyId: 'b_1',
  counterpartyName: 'Mamaearth',
  counterpartyHandle: '@mamaearth',
  counterpartyAvatar: '',
  status: 'IN_NEGOTIATION',
  dealValue: 40000,
  currency: 'INR',
  lastMessage: 'Sounds good',
  lastMessageAt: new Date('2026-07-20T10:00:00Z').toISOString(),
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 2,
  nextDeadline: null,
  contractId: null,
  contractStatus: null,
  escrowFunded: false,
};

/** Fires the same event the browser fires on tab foreground/background. */
async function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

describe('CreatorChatPage — foreground resync (CR-93 / F-0110 — creator half)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    creatorDealsList.mockResolvedValue([DEAL]);
    creatorDealsGet.mockResolvedValue(DEAL);
    creatorMessagesList.mockResolvedValue([]);
  });

  function renderRoom() {
    return render(
      <MemoryRouter initialEntries={['/creator/chat?deal=deal_1']}>
        <CreatorChatPage />
      </MemoryRouter>,
    );
  }

  it('does nothing on the initial mount beyond the normal load', async () => {
    renderRoom();
    await screen.findAllByText('Diwali Skincare Reels');
    const callsAfterMount = creatorMessagesList.mock.calls.length;

    // Sanity: hiding the tab must not itself trigger a refetch — only foregrounding does.
    await setVisibility('hidden');
    expect(creatorMessagesList.mock.calls.length).toBe(callsAfterMount);
  });

  it('refetches messages and the deal on becoming visible again', async () => {
    renderRoom();
    await screen.findAllByText('Diwali Skincare Reels');
    creatorMessagesList.mockClear();
    creatorDealsGet.mockClear();

    await setVisibility('hidden');
    await setVisibility('visible');

    await waitFor(() => expect(creatorMessagesList).toHaveBeenCalledWith('creator', 'deal_1'));
    await waitFor(() => expect(creatorDealsGet).toHaveBeenCalledWith('creator', 'deal_1'));
  });

  function plainMessage(overrides: Record<string, unknown> = {}) {
    return {
      id: 'msg_1',
      dealId: 'deal_1',
      kind: 'text',
      senderId: 'b_1',
      senderType: 'brand',
      content: 'Hey, excited to work on this!',
      metadata: {},
      createdAt: new Date('2026-07-20T11:00:00Z').toISOString(),
      readBy: [],
      ...overrides,
    };
  }

  it('F-0192: switching deals clears the previous deal\'s thread instead of bleeding it into the new deal', async () => {
    const DEAL_2 = {
      ...DEAL,
      id: 'deal_2',
      campaignName: 'Winter Drop',
      counterpartyId: 'b_2',
      counterpartyName: 'Nykaa Fashion',
    };
    creatorDealsList.mockResolvedValue([DEAL, DEAL_2]);
    creatorDealsGet.mockImplementation((_role: string, id: string) =>
      Promise.resolve(id === 'deal_2' ? DEAL_2 : DEAL),
    );

    let resolveDeal2Messages: (v: unknown) => void = () => {};
    creatorMessagesList.mockImplementation((_role: string, dealId: string) => {
      if (dealId === 'deal_2') {
        return new Promise((resolve) => {
          resolveDeal2Messages = resolve;
        });
      }
      return Promise.resolve([plainMessage()]);
    });

    renderRoom();
    await screen.findByText('Hey, excited to work on this!');

    const user = userEvent.setup({ delay: null });
    await user.click(screen.getByText('Nykaa Fashion'));

    // Deal 2's fetch is still in flight — deal 1's message must NOT still be on screen.
    await waitFor(() => expect(creatorMessagesList).toHaveBeenCalledWith('creator', 'deal_2'));
    expect(screen.queryByText('Hey, excited to work on this!')).not.toBeInTheDocument();

    await act(async () => {
      resolveDeal2Messages([plainMessage({ id: 'msg_2', content: 'Deal 2 kickoff message', senderId: 'b_2' })]);
    });

    expect(await screen.findByText('Deal 2 kickoff message')).toBeInTheDocument();
    expect(screen.queryByText('Hey, excited to work on this!')).not.toBeInTheDocument();
  });
});
