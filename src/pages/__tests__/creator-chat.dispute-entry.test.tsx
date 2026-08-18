/**
 * F-0301 (missing-creator-dispute-entry) — src/pages/creator-chat.tsx:1947.
 *
 * F-0242 gave the BRAND a real dispute-open control in the deal room. The creator's mirror
 * control (F-0289) was left permanently `disabled` on the mistaken premise that no creator-side
 * dispute action existed — but DealController.java:187 lets either party open a dispute, and
 * `api.creatorDisputes.open` already existed unused. This proves the RENDERED, REACHABLE
 * control: not disabled, gated on `escrowFunded` like the brand side, and an actual click path
 * from "Deal options" → "Report a problem with this deal" → typed reason → submit that calls
 * `creatorDisputes.open(dealId, reason)`.
 *
 * Uses `userEvent` (not `fireEvent.click`) for the Radix DropdownMenu/Dialog — synthetic
 * `.click()` gives false "dead control" positives on Radix components in this repo (see
 * dashboard-page.test.tsx for the same pattern working against a Radix DropdownMenu).
 *
 * Harness copied from creator-chat-verified-badge.test.tsx (same page, same wide api mock
 * surface needed just to get the room to mount).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorChatPage from '../creator-chat';

// `toastSpy` is read eagerly (not inside a lazily-invoked closure) while building the mocked
// module's return object, so it must be created via vi.hoisted — vi.mock factories are hoisted
// above ordinary `const` declarations and a plain top-level const here hits a TDZ error.
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: toastSpy }),
  toast: toastSpy,
}));

const dealsList = vi.fn();
const dealsGet = vi.fn();
const messagesList = vi.fn();
const messagesStream = vi.fn();
const disputesOpen = vi.fn();

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
  const creatorDisputes = {
    open: (...a: unknown[]) => disputesOpen(...a),
  };
  return {
    ...actual,
    isApiLive: () => true,
    deals,
    messages,
    creatorDisputes,
    api: {
      deals,
      messages,
      creatorDisputes,
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

function makeDeal(escrowFunded: boolean) {
  return {
    id: 'deal_1',
    campaignId: 'camp_1',
    campaignName: 'Diwali Skincare Reels',
    counterpartyId: 'b_1',
    counterpartyName: 'Mamaearth',
    counterpartyHandle: '@mamaearth',
    counterpartyAvatar: '',
    counterpartyVerificationStatus: 'VERIFIED',
    status: 'IN_PROGRESS',
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
    escrowFunded,
  };
}

function renderRoom() {
  return render(
    <MemoryRouter initialEntries={['/creator/chat?deal=deal_1']}>
      <CreatorChatPage />
    </MemoryRouter>,
  );
}

describe('CreatorChatPage — creator dispute entry point (F-0301)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    messagesStream.mockImplementation(() => ({ close: vi.fn() }));
    messagesList.mockResolvedValue([]);
    disputesOpen.mockResolvedValue({ id: 'dsp_new' });
  });

  it('the "Deal options" trigger is not hard-disabled once a deal is loaded', async () => {
    const deal = makeDeal(true);
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    renderRoom();

    const trigger = await screen.findByRole('button', { name: 'Deal options' });
    expect(trigger).not.toBeDisabled();
    expect(trigger).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('escrow not funded: the menu explains why, with no clickable dispute item', async () => {
    const deal = makeDeal(false);
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    const user = userEvent.setup();
    renderRoom();

    const trigger = await screen.findByRole('button', { name: 'Deal options' });
    await user.click(trigger);

    await waitFor(() =>
      expect(
        screen.getByText(/dispute can only be raised once escrow is funded/i),
      ).toBeInTheDocument(),
    );
    expect(screen.queryByText('Report a problem with this deal')).not.toBeInTheDocument();
  });

  it('escrow funded: opens the menu, opens the dialog, and reaches creatorDisputes.open with the typed reason', async () => {
    const deal = makeDeal(true);
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    const user = userEvent.setup();
    renderRoom();

    const trigger = await screen.findByRole('button', { name: 'Deal options' });
    await user.click(trigger);

    const menuItem = await screen.findByText('Report a problem with this deal');
    await user.click(menuItem);

    const dialogTitle = await screen.findByRole('heading', { name: 'Report a problem with this deal' });
    expect(dialogTitle).toBeInTheDocument();

    const textbox = screen.getByLabelText('What went wrong?');
    const submit = screen.getByRole('button', { name: 'Open dispute' });

    // Below the 10-character floor: the control is reachable but the submit stays disabled —
    // a control that can be clicked into a broken request is not the fix either.
    await user.type(textbox, 'too short');
    expect(submit).toBeDisabled();

    await user.type(textbox, ' — now it is long enough to submit');
    expect(submit).not.toBeDisabled();

    await user.click(submit);

    await waitFor(() => expect(disputesOpen).toHaveBeenCalledTimes(1));
    const [dealIdArg, reasonArg] = disputesOpen.mock.calls[0];
    expect(dealIdArg).toBe('deal_1');
    expect(typeof reasonArg).toBe('string');
    expect((reasonArg as string).length).toBeGreaterThanOrEqual(10);

    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: 'Report a problem with this deal' })).not.toBeInTheDocument(),
    );
    expect(toastSpy).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Dispute opened' }),
    );
  });

  it('a rejected creatorDisputes.open surfaces the server error inline instead of failing silently', async () => {
    const deal = makeDeal(true);
    dealsList.mockResolvedValue([deal]);
    dealsGet.mockResolvedValue(deal);
    const { ApiError } = await import('@/lib/api');
    disputesOpen.mockRejectedValue(new ApiError('CONFLICT', 'DISPUTE_ALREADY_OPEN', 409));
    const user = userEvent.setup();
    renderRoom();

    const trigger = await screen.findByRole('button', { name: 'Deal options' });
    await user.click(trigger);
    const menuItem = await screen.findByText('Report a problem with this deal');
    await user.click(menuItem);

    const textbox = screen.getByLabelText('What went wrong?');
    await user.type(textbox, 'this is definitely long enough to submit');
    await user.click(screen.getByRole('button', { name: 'Open dispute' }));

    await waitFor(() => expect(screen.getByText('DISPUTE_ALREADY_OPEN')).toBeInTheDocument());
    // Dialog must stay open on failure — the user's typed reason should not be thrown away.
    expect(screen.getByRole('heading', { name: 'Report a problem with this deal' })).toBeInTheDocument();
  });
});
