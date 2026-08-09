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
import { MemoryRouter } from 'react-router-dom';
import BrandChatPage from './brand-chat';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const dealsList = vi.fn();
const dealsGet = vi.fn();
const messagesList = vi.fn();
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
