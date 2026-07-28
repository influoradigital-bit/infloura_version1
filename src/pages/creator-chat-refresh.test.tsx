/**
 * Creator deal room — `refreshDeal` staleness guard (CR-29, creator half of W2-L1).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * CR-23 ported an `isSupersededRefresh` guard into brand `refreshDeal`'s catch block; the
 * creator copy at `creator-chat.tsx:721-753` is where the pattern originated (W2-L1). Neither
 * was test-pinned: CR-29 reported that all 252 tests passed identically with and without the
 * guard, so reverting it broke nothing visible. The brand half got its tripwire in
 * `brand-chat-refresh` coverage; this is the creator half, and it is the first test harness for
 * `creator-chat.tsx` in the repo.
 *
 * WHAT THE GUARD DOES
 * -------------------
 * Every `refreshDeal(id)` claims a per-deal token. On BOTH the success and failure paths it
 * checks whether a newer refresh of the same deal has since started. Without the check on the
 * failure path, a slow refresh that FAILS after a newer one already SUCCEEDED pops "Couldn't
 * refresh this deal" — flatly contradicting the fresh data already on screen.
 *
 * `console.error` is deliberately UNCONDITIONAL: a request really did fail and that is worth
 * diagnosing whether or not its result is still wanted. Only the toast is suppressed. Both
 * halves are asserted below, because asserting "no toast" alone would also pass if the entire
 * catch block were deleted.
 *
 * HARNESS NOTES
 * -------------
 * The page is large, so the mock surface is wide but shallow — every `api.*` member the page
 * touches is stubbed, and the ones this test doesn't exercise resolve empty rather than being
 * omitted (an omitted member throws on property access and the failure looks unrelated).
 * `messages.stream` captures the handlers so a test can push SSE frames, which is how
 * `refreshDeal` is reached: the stream's `onMessage` calls it for any `proposal`/`system` frame
 * (see `mayIndicateDealStatusChange`).
 *
 * Run: npx vitest run src/pages/creator-chat-refresh.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
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
const messagesList = vi.fn();
const messagesStream = vi.fn();

/** Handlers the page handed to `messages.stream`, so a test can push frames at the room. */
let streamHandlers: DealMessageStreamHandlers | null = null;

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => dealsList(...a),
    get: (...a: unknown[]) => dealsGet(...a),
    accept: vi.fn().mockResolvedValue({ id: 'deal_1' }),
    reject: vi.fn().mockResolvedValue({ id: 'deal_1' }),
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
      // CR-06/CR-16 — CreatorLayout wraps this page and now reads real identity and a real
      // unread total. Both hooks bail out when there is no creator_token (there isn't one in
      // jsdom), but the members must exist: an omitted one throws on property access.
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
    },
  };
});

/** IN_NEGOTIATION — a live offer, so the room renders its proposal gate. */
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

const systemFrame = (id: string) =>
  ({
    id,
    dealId: 'deal_1',
    kind: 'system',
    senderId: 'system',
    senderType: 'system',
    content: 'Brand accepted the proposal',
    createdAt: new Date('2026-07-20T11:05:00Z').toISOString(),
    readBy: [],
  }) as unknown as DealMessage;

function renderRoom() {
  // ?deal=deal_1 — the page reads the selected deal off the query string.
  return render(
    <MemoryRouter initialEntries={['/creator/chat?deal=deal_1']}>
      <CreatorChatPage />
    </MemoryRouter>,
  );
}

describe('CreatorChatPage — refreshDeal staleness guard (CR-29 / W2-L1)', () => {
  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.clearAllMocks();
    streamHandlers = null;
    messagesStream.mockImplementation(
      (_role: string, _dealId: string, handlers: DealMessageStreamHandlers) => {
        streamHandlers = handlers;
        return { close: vi.fn() };
      },
    );
    dealsList.mockResolvedValue([DEAL]);
    dealsGet.mockResolvedValue(DEAL);
    messagesList.mockResolvedValue([]);
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it('mounts the room for the deal named in ?deal=', async () => {
    renderRoom();
    // Sanity check on the harness itself — if this fails, the guard tests below are testing
    // nothing and would pass for the wrong reason.
    expect((await screen.findAllByText('Diwali Skincare Reels')).length).toBeGreaterThan(0);
    await waitFor(() => expect(messagesStream).toHaveBeenCalled());
    expect(streamHandlers).not.toBeNull();
  });

  it('toasts a failed refresh when it is still the newest one', async () => {
    // The guard must not suppress everything — a failure that IS current still has to be
    // surfaced, because the visible consequence is Accept/Counter/Decline continuing to render
    // on a deal that has already moved on (the CR-02 confusion).
    dealsGet.mockRejectedValue(new Error('network down'));
    renderRoom();
    await screen.findAllByText('Diwali Skincare Reels');

    await act(async () => {
      streamHandlers?.onMessage(systemFrame('msg_sys1'));
    });

    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Couldn’t refresh this deal',
          variant: 'destructive',
        }),
      ),
    );
  });

  it('suppresses the failure toast when a newer refresh already succeeded, but still logs', async () => {
    // Two refreshes of the SAME deal, resolved out of order: the older one rejects only after
    // the newer one has already applied a result.
    const deferred: Array<{ resolve: (v: unknown) => void; reject: (e: unknown) => void }> = [];
    dealsGet.mockImplementation(
      () =>
        new Promise((resolve, reject) => {
          deferred.push({ resolve, reject });
        }),
    );

    renderRoom();
    await screen.findAllByText('Diwali Skincare Reels');
    await waitFor(() => expect(streamHandlers).not.toBeNull());
    const before = deferred.length;

    await act(async () => {
      streamHandlers?.onMessage(systemFrame('msg_sys1'));
    });
    await waitFor(() => expect(deferred.length).toBeGreaterThan(before));
    const refreshA = deferred[deferred.length - 1];

    await act(async () => {
      streamHandlers?.onMessage(systemFrame('msg_sys2'));
    });
    await waitFor(() => expect(deferred.length).toBeGreaterThan(before + 1));
    const refreshB = deferred[deferred.length - 1];

    // Newer refresh lands first and succeeds; the older one fails afterwards.
    await act(async () => {
      refreshB.resolve(DEAL);
    });
    await act(async () => {
      refreshA.reject(new Error('network down'));
    });

    // Logged unconditionally — the failure is real and diagnosable.
    await waitFor(() =>
      expect(consoleError).toHaveBeenCalledWith(
        'Failed to refresh deal',
        'deal_1',
        expect.any(Error),
      ),
    );
    // But NOT toasted: what is on screen came from the newer, successful refresh, so the
    // failure has nothing to report.
    expect(toastMock).not.toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Couldn’t refresh this deal' }),
    );
  });
});
