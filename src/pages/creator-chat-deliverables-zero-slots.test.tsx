/**
 * F-0349 — the deal-room Deliverables panel on a 0/0 CONTRACTED deal (no deliverable slots,
 * no Submit control rendered anywhere on the page) must not tell the creator "No deliverables
 * yet. They appear here once the creator submits content." — that instructs an action the
 * creator has no control to take. Slots are materialized server-side by
 * ContractService#materializeDeliverables at contract generation; a creator can never make
 * slots appear by "submitting".
 *
 * This is a regression test: creator-chat.tsx's own zero-slots branch (search "F-0349" in that
 * file) already renders honest copy ("No deliverable slots yet" / "Slots are created from the
 * contract, not by uploading…") instead of delegating to DealDeliverablesTab's generic
 * "once the creator submits content" empty state. This file locks that behaviour down.
 *
 * Run: npx vitest run src/pages/creator-chat-deliverables-zero-slots.test.tsx
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

/** A CONTRACTED deal with 0/0 deliverable slots — the exact state that had no Submit control. */
const ZERO_SLOT_DEAL = {
  id: 'deal_zero_slots',
  campaignId: 'camp_zero',
  campaignName: 'Zero Slots Campaign',
  counterpartyId: 'b_zero',
  counterpartyName: 'Brand Zero',
  counterpartyHandle: '@brand_zero',
  counterpartyAvatar: '',
  status: 'ACTIVE',
  dealValue: 10000,
  currency: 'INR',
  lastMessage: '',
  lastMessageAt: new Date('2026-07-01T10:00:00Z').toISOString(),
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 0,
  nextDeadline: null,
  contractId: 'ctr_zero',
  contractStatus: 'ACTIVE',
  escrowFunded: true,
};

describe('CreatorChatPage — F-0349 zero-slots deliverables empty state', () => {
  it('does not tell the creator to submit content when there are 0/0 slots and no Submit control', async () => {
    dealsList.mockResolvedValue([ZERO_SLOT_DEAL]);

    render(
      <MemoryRouter initialEntries={['/creator/chat?deal=deal_zero_slots&tab=deliverables']}>
        <CreatorChatPage />
      </MemoryRouter>,
    );

    // The honest zero-slots copy confirms we're actually in the Deliverables sheet, not some
    // other tab (also proves the sheet opened at all before asserting on absent text below).
    expect(await screen.findByText(/no deliverable slots yet/i)).toBeInTheDocument();

    // The misleading copy this guards against — it implies the creator can act ("submits
    // content") when no Submit control renders for a 0/0 slot deal.
    expect(screen.queryByText(/appear here once the creator submits content/i)).not.toBeInTheDocument();

    // Honest replacement copy is shown instead.
    expect(screen.getByText(/slots are created from the contract/i)).toBeInTheDocument();
  });
});
