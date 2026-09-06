/**
 * F-0640 — the creator deal room must actually PASS the contract's milestones to the contract tab.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * A prior pass "fixed" F-0640 by adding a milestones render block to
 * `creator-deal-contract-tab.tsx` — and wrote four tests for it. All four passed. The feature did
 * not work. The block was dead: this page, the tab's ONLY caller, never passed a `milestones`
 * prop, so every real creator still saw "No payment milestones are on file" on the tab they
 * countersign from.
 *
 * A fresh-context review proved the existing suite could not detect this, with an inverse probe:
 * it ADDED the missing wiring and the component tests returned identical 4/4 passes. A test that
 * reports the same result whether or not the defect is present is not a gate.
 *
 * Those component tests are still legitimate as COMPONENT contracts (an unpassed prop must render
 * an honest empty state, not crash). What was missing is a test at the CALL SITE. That is this
 * file: it renders the real page against a contract carrying real milestones and asserts they
 * reach the screen. Reverting the one-line prop at `creator-chat.tsx` turns this red; nothing in
 * the component-level suite moves.
 *
 * Run: npx vitest run src/pages/creator-chat-contract-milestones-wired.test.tsx
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
const contractsGet = vi.fn();

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
      contracts: { get: (...a: unknown[]) => contractsGet(...a) },
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

const DEAL = {
  id: 'deal_ms',
  campaignId: 'camp_ms',
  campaignName: 'Milestone Campaign',
  counterpartyId: 'brand_ms',
  counterpartyName: 'Milestone Brand',
  counterpartyHandle: '@milestone_brand',
  counterpartyAvatar: '',
  status: 'ACTIVE',
  dealValue: 50000,
  currency: 'INR',
  lastMessage: '',
  lastMessageAt: new Date('2026-07-01T10:00:00Z').toISOString(),
  unreadCount: 0,
  deliverablesDone: 0,
  deliverablesTotal: 2,
  nextDeadline: null,
  contractId: 'ctr_ms',
  contractStatus: 'PENDING_SIGNATURES',
  escrowFunded: false,
};

/**
 * Amounts chosen so they cannot be confused with the deal value (50,000) shown elsewhere on the
 * page — if these strings appear, they came from the contract's own milestone rows.
 */
const CONTRACT_WITH_MILESTONES = {
  id: 'ctr_ms',
  collaborationId: 'deal_ms',
  status: 'PENDING_SIGNATURES',
  totalAmount: 50000,
  currency: 'INR',
  brandSignedAt: new Date('2026-07-02T10:00:00Z').toISOString(),
  creatorSignedAt: null,
  milestones: [
    { id: 'ms_1', sequenceNo: 1, description: 'Kickoff reel delivery', amount: 18500, status: 'PENDING' },
    { id: 'ms_2', sequenceNo: 2, description: 'Story set and analytics', amount: 31500, status: 'PENDING' },
  ],
};

describe('CreatorChatPage — the contract tab receives the real milestone schedule (F-0640)', () => {
  it('renders the contract’s own milestone rows, not the empty state', async () => {
    dealsList.mockResolvedValue([DEAL]);
    contractsGet.mockResolvedValue(CONTRACT_WITH_MILESTONES);

    render(
      <MemoryRouter initialEntries={['/creator/chat?deal=deal_ms&tab=contract']}>
        <CreatorChatPage />
      </MemoryRouter>,
    );

    // The descriptions and amounts can only be on screen if the page passed `milestones` down.
    expect(await screen.findByText(/Kickoff reel delivery/i)).toBeInTheDocument();
    expect(await screen.findByText(/Story set and analytics/i)).toBeInTheDocument();

    // And the honest-empty-state copy must NOT be showing while a real schedule exists — that
    // string appearing here is the exact production symptom this finding was about.
    expect(
      screen.queryByText(/no payment milestones are on file for this contract yet/i),
    ).not.toBeInTheDocument();
  });

  it('still shows the honest empty state when the contract genuinely carries no milestones', async () => {
    dealsList.mockResolvedValue([DEAL]);
    contractsGet.mockResolvedValue({ ...CONTRACT_WITH_MILESTONES, milestones: [] });

    render(
      <MemoryRouter initialEntries={['/creator/chat?deal=deal_ms&tab=contract']}>
        <CreatorChatPage />
      </MemoryRouter>,
    );

    expect(
      await screen.findByText(/no payment milestones are on file for this contract yet/i),
    ).toBeInTheDocument();
  });
});
