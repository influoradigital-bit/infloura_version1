/**
 * F-0447 round 2 — `?deal=<id>` that does not match any of the creator's real deals.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `selectedDeal` used to resolve as `dealRooms.find(d => d.id === selectedDealId) ??
 * dealRooms[0] ?? null` — so an id that does not match ANYTHING silently fell through to the
 * creator's FIRST deal room instead of erroring. That made a stale/wrong id in a deep link
 * (exactly what the creator-dashboard "awaiting your signature" links pass, as
 * `contract.collaborationId`) show a totally unrelated deal's messages, contract and
 * deliverables, with no indication anything was wrong. A zero-context QA pass found this by
 * reading the routing code directly, not by exercising the app — this test is the exercise.
 *
 * The fix: the `dealRooms[0]` fallback now applies ONLY when nobody asked for a specific deal
 * (`selectedDealId == null`, e.g. bare `/creator/chat`). An explicit, unmatched id falls through
 * to `null` and the page's existing "No deals yet" guard, never to a substituted deal.
 *
 * Run: npx vitest run src/pages/creator-chat-unmatched-deal-id.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorChatPage from './creator-chat';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const dealsList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => dealsList(...a),
    get: vi.fn().mockResolvedValue(null),
    accept: vi.fn(),
    reject: vi.fn(),
    counter: vi.fn(),
  };
  const messages = {
    list: vi.fn().mockResolvedValue([]),
    markRead: vi.fn().mockResolvedValue({ ok: true }),
    send: vi.fn(),
    stream: vi.fn().mockReturnValue({ close: vi.fn() }),
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
      shipments: { get: vi.fn().mockResolvedValue(null), submitAddress: vi.fn(), confirmReceipt: vi.fn() },
      wallet: {
        platformFee: vi
          .fn()
          .mockResolvedValue({ feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '' }),
      },
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
    },
  };
});

/** Two real deals — a mismatch must NOT silently land on either of them. */
const REAL_DEAL_A = {
  id: 'deal_real_a',
  campaignId: 'camp_a',
  campaignName: 'Real Campaign A',
  counterpartyId: 'b_a',
  counterpartyName: 'Brand A',
  counterpartyHandle: '@brand_a',
  counterpartyAvatar: '',
  status: 'ACTIVE',
  dealValue: 30000,
  currency: 'INR',
  lastMessage: '',
  lastMessageAt: new Date('2026-07-01T10:00:00Z').toISOString(),
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 1,
  nextDeadline: null,
  contractId: 'ctr_a',
  contractStatus: 'ACTIVE',
  escrowFunded: true,
};

const REAL_DEAL_B = { ...REAL_DEAL_A, id: 'deal_real_b', campaignName: 'Real Campaign B' };

describe('CreatorChatPage — an unmatched ?deal= id must not substitute a different deal', () => {
  it('shows "No deals yet", not the first real deal, when the id in the URL matches nothing', async () => {
    dealsList.mockResolvedValue([REAL_DEAL_A, REAL_DEAL_B]);

    render(
      <MemoryRouter initialEntries={['/creator/chat?deal=stale_or_wrong_id_123']}>
        <CreatorChatPage />
      </MemoryRouter>,
    );

    // The wrong-deal failure mode this guards against: silently rendering deal_real_a's
    // campaign name as if it were the one the link asked for.
    expect(await screen.findByText(/no deals yet/i)).toBeInTheDocument();
    expect(screen.queryByText('Real Campaign A')).not.toBeInTheDocument();
    expect(screen.queryByText('Real Campaign B')).not.toBeInTheDocument();
  });

  it('still falls back to the first deal when NO id is requested at all', async () => {
    dealsList.mockResolvedValue([REAL_DEAL_A, REAL_DEAL_B]);

    render(
      <MemoryRouter initialEntries={['/creator/chat']}>
        <CreatorChatPage />
      </MemoryRouter>,
    );

    // No id was ever specified, so defaulting to the first deal is the correct, unchanged
    // behaviour — only an EXPLICIT unmatched id must fall through to "No deals yet". The name
    // legitimately renders more than once (sidebar row + main panel), so assert presence, not
    // uniqueness.
    expect((await screen.findAllByText('Real Campaign A')).length).toBeGreaterThan(0);
  });
});
