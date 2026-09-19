/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.6, §8.10, B0-37) — deal risk flags in the creator deal room.
 *
 * Three behaviours, none of them visible to `tsc`:
 *   1. the flags render on a PENDING brand proposal, after the deal-terms summary;
 *   2. a 403 is swallowed in silence — `DealService.risksForCreator` refuses any principal who is
 *      not the creator on the deal with `CREATOR_ONLY`, which is an ordinary state, not an error
 *      worth a toast;
 *   3. the read follows the same per-deal supersede token as `refreshDeal`, so an older read of a
 *      deal cannot overwrite a newer one — and flags stay keyed to the deal they belong to.
 *
 * Both halves of (3) were falsified against the code: deleting `isSupersededRisksRead` reddens the
 * out-of-order test, and deleting the 403 branch reddens the silence test.
 *
 * Harness copied from `creator-chat-verified-badge.test.tsx` — wide but shallow: every `api.*`
 * member the page touches is stubbed, because an omitted one throws on property access and the
 * failure then looks unrelated.
 *
 * Run: npx vitest run src/pages/creator-chat.risks.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { DealMessage, DealMessageStreamHandlers, DealRisksResponse } from '@/lib/api';
import CreatorChatPage from './creator-chat';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const dealsList = vi.fn();
const dealsGet = vi.fn();
const dealsRisks = vi.fn();
const messagesList = vi.fn();
const messagesStream = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => dealsList(...a),
    get: (...a: unknown[]) => dealsGet(...a),
    risks: (...a: unknown[]) => dealsRisks(...a),
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
      creatorDeliverables: { listForDeal: vi.fn().mockResolvedValue([]), upload: vi.fn() },
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

function makeDeal(id: string) {
  const second = id === 'deal_2';
  return {
    id,
    campaignId: 'camp_1',
    campaignName: second ? 'Holi Haircare Reels' : 'Diwali Skincare Reels',
    counterpartyId: second ? 'b_2' : 'b_1',
    counterpartyName: second ? 'Nykaa Fashion' : 'Mamaearth',
    counterpartyHandle: second ? '@nykaafashion' : '@mamaearth',
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
}

/** A live brand offer — `status: 'pending'` is the gate the risk block shares with the terms block. */
const pendingProposal = (dealId: string) =>
  ({
    id: `msg_prop_${dealId}`,
    dealId,
    kind: 'proposal',
    senderId: 'b_1',
    senderType: 'brand',
    content: 'Our offer',
    createdAt: new Date('2026-07-20T11:00:00Z').toISOString(),
    readBy: [],
    metadata: { proposalType: 'initial', status: 'pending', amount: 40000 },
  }) as unknown as DealMessage;

const CRITICAL_RISKS: DealRisksResponse = {
  flags: [
    {
      code: 'USAGE_PERPETUAL',
      severity: 'CRITICAL',
      title: 'They want your content forever',
      detail: 'Usage is perpetual and across paid ads.',
      cost: '≈ 12,000 of lost income',
      action: 'Ask for a 6-month window instead.',
      data: {},
      dismissible: false,
    },
  ],
  highest_severity: 'CRITICAL',
  target: 'DEAL',
  target_id: 'deal_1',
};

function renderRoom(dealId = 'deal_1') {
  return render(
    <MemoryRouter initialEntries={[`/creator/chat?deal=${dealId}`]}>
      <CreatorChatPage />
    </MemoryRouter>,
  );
}

describe('CreatorChatPage — deal risk flags (§8.6)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // U-3: dismissals live in sessionStorage — one test's dismissal must not hide another's flag.
    window.sessionStorage.clear();
    messagesStream.mockImplementation(() => ({ close: vi.fn() }));
    dealsList.mockResolvedValue([makeDeal('deal_1')]);
    dealsGet.mockResolvedValue(makeDeal('deal_1'));
    messagesList.mockResolvedValue([pendingProposal('deal_1')]);
    dealsRisks.mockResolvedValue({ flags: [], target: 'DEAL', target_id: 'deal_1' });
  });

  it('renders the flags on a pending brand proposal', async () => {
    dealsRisks.mockResolvedValue(CRITICAL_RISKS);
    renderRoom();

    expect((await screen.findAllByText('Diwali Skincare Reels')).length).toBeGreaterThan(0);
    await waitFor(() => expect(dealsRisks).toHaveBeenCalledWith('deal_1'));

    const row = await screen.findByTestId('deal-risk-row');
    expect(row).toHaveAttribute('data-severity', 'CRITICAL');
    expect(screen.getByText('They want your content forever')).toBeInTheDocument();
    expect(screen.getByText('≈ 12,000 of lost income')).toBeInTheDocument();
    // Not dismissible -> no control, and the room offers no other button inside the card.
    expect(
      screen.queryByRole('button', { name: /Dismiss They want your content forever/ }),
    ).not.toBeInTheDocument();
  });

  it('renders no risk section at all when the deal is clean', async () => {
    renderRoom();
    expect((await screen.findAllByText('Diwali Skincare Reels')).length).toBeGreaterThan(0);
    await waitFor(() => expect(dealsRisks).toHaveBeenCalledWith('deal_1'));
    expect(screen.queryByTestId('deal-risk-card')).not.toBeInTheDocument();
    expect(screen.queryByText('What to watch')).not.toBeInTheDocument();
  });

  it('ignores a 403 silently — no toast, no console error', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    dealsRisks.mockRejectedValue(new ApiError('CREATOR_ONLY', 'Not your deal', 403));

    renderRoom();
    expect((await screen.findAllByText('Diwali Skincare Reels')).length).toBeGreaterThan(0);
    await waitFor(() => expect(dealsRisks).toHaveBeenCalledWith('deal_1'));

    expect(screen.queryByTestId('deal-risk-card')).not.toBeInTheDocument();
    expect(toastMock).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalledWith(
      'Failed to load deal risk flags',
      expect.anything(),
      expect.anything(),
    );
    consoleError.mockRestore();
  });

  it('lets the NEWEST read of a deal win when two of them resolve out of order', async () => {
    // This is what the per-deal supersede token is actually for, and it is the only case that
    // falsifies it: deal-keyed state already makes a cross-deal bleed impossible (the test
    // below pins that separately), but two reads of the SAME deal resolving out of order will
    // happily overwrite the fresh answer with the stale one unless the token stops them.
    //
    // Sequence: the select-time read is held open; a stream frame triggers `refreshDeal`, whose
    // risks read resolves FIRST and clean; the held select-time read then resolves with a
    // CRITICAL flag that is no longer true. Without the guard that CRITICAL lands on screen.
    let streamHandlers: DealMessageStreamHandlers | null = null;
    messagesStream.mockImplementation(
      (_role: string, _dealId: string, handlers: DealMessageStreamHandlers) => {
        streamHandlers = handlers;
        return { close: vi.fn() };
      },
    );

    let releaseFirst: ((v: DealRisksResponse) => void) | null = null;
    let call = 0;
    dealsRisks.mockImplementation(() => {
      call += 1;
      if (call === 1) {
        return new Promise<DealRisksResponse>((resolve) => {
          releaseFirst = resolve;
        });
      }
      return Promise.resolve({ flags: [], target: 'DEAL', target_id: 'deal_1' });
    });

    renderRoom();
    expect((await screen.findAllByText('Diwali Skincare Reels')).length).toBeGreaterThan(0);
    await waitFor(() => expect(dealsRisks).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(streamHandlers).not.toBeNull());

    // A system frame drives `refreshDeal`, which re-reads the risks. That read resolves clean.
    await act(async () => {
      streamHandlers?.onMessage({
        id: 'msg_sys_1',
        dealId: 'deal_1',
        kind: 'system',
        senderId: 'system',
        senderType: 'system',
        content: 'Brand accepted the proposal',
        createdAt: new Date('2026-07-20T11:05:00Z').toISOString(),
        readBy: [],
      } as unknown as DealMessage);
    });
    await waitFor(() => expect(dealsRisks).toHaveBeenCalledTimes(2));

    // Only now does the older read land, carrying a flag that is no longer current.
    await act(async () => {
      releaseFirst?.(CRITICAL_RISKS);
    });

    expect(screen.queryByText('They want your content forever')).not.toBeInTheDocument();
    expect(screen.queryByTestId('deal-risk-card')).not.toBeInTheDocument();
  });

  it('keeps each deal’s flags under its own deal id when the creator switches rooms', async () => {
    dealsList.mockResolvedValue([makeDeal('deal_1'), makeDeal('deal_2')]);
    dealsGet.mockImplementation(async (_role: string, id: string) => makeDeal(id));
    messagesList.mockImplementation(async (_role: string, id: string) => [pendingProposal(id)]);

    let releaseA: ((v: DealRisksResponse) => void) | null = null;
    dealsRisks.mockImplementation((id: string) => {
      if (id === 'deal_1') {
        return new Promise<DealRisksResponse>((resolve) => {
          releaseA = resolve;
        });
      }
      return Promise.resolve({ flags: [], target: 'DEAL', target_id: id });
    });

    renderRoom('deal_1');
    await waitFor(() => expect(dealsRisks).toHaveBeenCalledWith('deal_1'));

    // Switch rooms the way a creator does — by clicking the other deal in the sidebar.
    const user = userEvent.setup({ delay: null });
    await user.click(screen.getByText('Nykaa Fashion'));
    await waitFor(() => expect(dealsRisks).toHaveBeenCalledWith('deal_2'));

    // deal_1's read lands now, long after the creator moved on.
    await act(async () => {
      releaseA?.(CRITICAL_RISKS);
    });

    expect((await screen.findAllByText('Holi Haircare Reels')).length).toBeGreaterThan(0);
    expect(screen.queryByText('They want your content forever')).not.toBeInTheDocument();
  });

  it('U-3: dismisses a dismissible flag for the session; a non-dismissible flag has no control', async () => {
    dealsRisks.mockResolvedValue({
      ...CRITICAL_RISKS,
      flags: [
        ...CRITICAL_RISKS.flags,
        {
          code: 'EXCLUSIVITY_LONG',
          severity: 'WARN',
          title: 'Exclusivity runs 90 days',
          detail: 'No other haircare brand for three months.',
          action: 'Ask for 30 days.',
          data: {},
          dismissible: true,
        },
      ],
    } satisfies DealRisksResponse);

    renderRoom();
    const user = userEvent.setup({ delay: null });

    const dismiss = await screen.findByRole('button', { name: 'Dismiss Exclusivity runs 90 days' });
    // The page supplies the handler, yet the non-dismissible flag still gets no control.
    expect(
      screen.queryByRole('button', { name: /Dismiss They want your content forever/ }),
    ).not.toBeInTheDocument();

    await user.click(dismiss);

    expect(screen.queryByText('Exclusivity runs 90 days')).not.toBeInTheDocument();
    expect(screen.getByText('They want your content forever')).toBeInTheDocument();
    expect(screen.getByTestId('deal-risk-hidden-note')).toHaveTextContent(
      '1 flag hidden for this session.',
    );
    // Scoped to this deal, in the tab's session store.
    expect(window.sessionStorage.getItem('influora.riskFlagDismissals.v1')).toBe(
      JSON.stringify({ 'DEAL:deal_1': ['EXCLUSIVITY_LONG'] }),
    );
  });
});
