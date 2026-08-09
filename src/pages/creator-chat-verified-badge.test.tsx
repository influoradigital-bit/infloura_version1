/**
 * M-1 (BrandF.md §83c/§87) — "Verified Brand" badge fabrication.
 *
 * BEFORE THE FIX
 * ---------------
 * `creator-deal-mappers.ts:184` hardcoded `brandVerified: true` for every deal (used by
 * `creator-deals.tsx`), and the deal room header in `creator-chat.tsx` rendered the
 * "Verified Brand" `<Badge>` as unconditional JSX — not gated on any field at all. Every
 * workspace defaults to `UNVERIFIED` (`Workspace.java:126`), so an unverified brand
 * displayed as verified to a creator at the exact moment they're deciding whether to ship
 * physical product.
 *
 * AFTER THE FIX
 * -------------
 * The backend now carries `DealDtos.DealResponse.counterpartyVerificationStatus`
 * (DealDtos.java:39) — the brand's real `Workspace.verificationStatus` — and
 * `mapDealToChatRoom` (creator-deal-mappers.ts) derives `brandVerified` from it:
 * `deal.counterpartyVerificationStatus === 'VERIFIED'`. The deal room header now renders
 * the badge only when that is true.
 *
 * This file proves the render, not just the mapper: an UNVERIFIED-brand deal must NOT show
 * "Verified Brand" anywhere on screen, and a VERIFIED-brand deal must.
 *
 * Harness copied from `creator-chat-refresh.test.tsx` (the first — and at the time of
 * writing, only other — full-render harness for this page); see that file's header for why
 * the api mock surface is wide.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorChatPage from './creator-chat';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const dealsList = vi.fn();
const dealsGet = vi.fn();
const messagesList = vi.fn();
const messagesStream = vi.fn();

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
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
    },
  };
});

/** Base deal payload — only `counterpartyVerificationStatus` varies per test. */
function makeDeal(counterpartyVerificationStatus: string | null) {
  return {
    id: 'deal_1',
    campaignId: 'camp_1',
    campaignName: 'Diwali Skincare Reels',
    counterpartyId: 'b_1',
    counterpartyName: 'Mamaearth',
    counterpartyHandle: '@mamaearth',
    counterpartyAvatar: '',
    counterpartyVerificationStatus,
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

function renderRoom() {
  return render(
    <MemoryRouter initialEntries={['/creator/chat?deal=deal_1']}>
      <CreatorChatPage />
    </MemoryRouter>,
  );
}

describe('CreatorChatPage — "Verified Brand" badge honesty (M-1, BrandF.md §83c/§87)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    messagesStream.mockImplementation(() => ({ close: vi.fn() }));
    messagesList.mockResolvedValue([]);
  });

  it('does NOT render "Verified Brand" for an UNVERIFIED brand', async () => {
    const deal = makeDeal('UNVERIFIED');
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    renderRoom();

    // Sanity check the room actually mounted for this deal before asserting an absence —
    // otherwise "not found" could mean "wrong deal" instead of "correctly gated".
    await screen.findAllByText('Mamaearth');
    expect(screen.queryByText('Verified Brand')).not.toBeInTheDocument();
  });

  it('does NOT render "Verified Brand" for a PENDING brand', async () => {
    const deal = makeDeal('PENDING');
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    renderRoom();

    await screen.findAllByText('Mamaearth');
    expect(screen.queryByText('Verified Brand')).not.toBeInTheDocument();
  });

  it('does NOT render "Verified Brand" when the field is null (brand counterparty missing/unresolved)', async () => {
    const deal = makeDeal(null);
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    renderRoom();

    await screen.findAllByText('Mamaearth');
    expect(screen.queryByText('Verified Brand')).not.toBeInTheDocument();
  });

  it('DOES render "Verified Brand" for a VERIFIED brand', async () => {
    const deal = makeDeal('VERIFIED');
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    renderRoom();

    await screen.findAllByText('Mamaearth');
    expect(await screen.findByText('Verified Brand')).toBeInTheDocument();
  });
});
