/**
 * Deal-room "Send Proposal" — wired to POST /deals/:id/counter.
 *
 * Until 2026-07-26 `handleSendProposal` was `await new Promise(r => setTimeout(r, 1500))` with a
 * comment reading "In real app: add proposal to mockEvents or call API". A brand filled in five
 * steps of terms, watched a spinner, and the modal closed having sent nothing.
 *
 * Counter — not create — is the correct verb here: by the time a deal room exists the
 * Collaboration exists, so POST /deals would 409 COLLABORATION_EXISTS.
 *
 * Run: npx vitest run src/pages/brand-chat-proposal.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandChatPage from './brand-chat';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const dealsList = vi.fn();
const dealsCounter = vi.fn();
const messagesList = vi.fn();
const brandPlatformFee = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  const deals = {
    list: (...a: unknown[]) => dealsList(...a),
    counter: (...a: unknown[]) => dealsCounter(...a),
  };
  const messages = {
    list: (...a: unknown[]) => messagesList(...a),
    markRead: vi.fn().mockResolvedValue({ ok: true }),
    send: vi.fn(),
    stream: () => ({ close: vi.fn() }),
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

/** IN_NEGOTIATION — the state in which Collaboration.canCounter() is true. */
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

function renderChat() {
  return render(
    <MemoryRouter initialEntries={['/brand/chat']}>
      <BrandChatPage />
    </MemoryRouter>,
  );
}

/**
 * Opens the modal and walks the 5-step wizard: Next x4, then submit.
 *
 * Everything is scoped to the modal because "Send Proposal" names BOTH the header trigger and
 * the wizard's submit button — unscoped queries match two elements once the modal is open.
 */
async function openAndCompleteWizard(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /Send Proposal/i }));

  const heading = await screen.findByRole('heading', { name: /Send Proposal to Aarti Menon/i });
  const modal = heading.closest('div.fixed') as HTMLElement;
  expect(modal).toBeTruthy();

  for (let i = 0; i < 4; i++) {
    await user.click(within(modal).getByRole('button', { name: /^Next$/i }));
  }
  await user.click(within(modal).getByRole('button', { name: /^Send Proposal$/i }));
}

describe('BrandChatPage — deal-room proposal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([DEAL]);
    messagesList.mockResolvedValue([]);
    dealsCounter.mockResolvedValue({ id: 'deal_1' });
    brandPlatformFee.mockResolvedValue({
      feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '',
    });
  });

  it('sends the proposal as a counter with the collected terms', async () => {
    const user = userEvent.setup();
    renderChat();

    await openAndCompleteWizard(user);

    await waitFor(() => expect(dealsCounter).toHaveBeenCalledTimes(1));

    const [dealId, body, role, idempotencyKey] = dealsCounter.mock.calls[0];
    expect(dealId).toBe('deal_1');
    expect(role).toBe('brand');
    expect(body.amount).toBe(50000);
    // Local shape is {id, type, count}; DealDtos.DeliverableSlot expects {type, qty}.
    expect(body.deliverables).toEqual([{ type: 'Instagram Reel', qty: 1 }]);
    // Since CounterRequest was aligned with CreateDealRequest, usage rights is a real field the
    // server persists onto the deal — not prose stuffed into the message where it can only be
    // read by a human.
    expect(body.usageRights).toBe('6 months');
    expect(body.message).not.toMatch(/Usage rights:/i);
    // Fresh key per submit — otherwise the server derives one from dealId+amount and a
    // re-proposal at the same figure is swallowed as a replay.
    expect(idempotencyKey).toMatch(/^deal_1-proposal-\d+$/);
  });

  it('surfaces a DEAL_NOT_NEGOTIABLE conflict instead of failing silently', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    dealsCounter.mockRejectedValue(
      new ApiError('DEAL_NOT_NEGOTIABLE', 'This deal cannot be countered in its current state', 409),
    );
    const user = userEvent.setup();
    renderChat();

    await openAndCompleteWizard(user);

    await waitFor(() => expect(toastMock).toHaveBeenCalled());
    const call = toastMock.mock.calls.at(-1)?.[0];
    expect(call.title).toBe('Proposal failed');
    expect(call.description).toMatch(/moved past negotiation/i);
  });

  it('hides Send Proposal once the deal is past negotiation', async () => {
    dealsList.mockResolvedValue([{ ...DEAL, status: 'COMPLETED' }]);
    renderChat();

    // Wait for the room to load, then assert the control is absent — countering a COMPLETED
    // deal could only ever 409, so offering a five-step form for it is a dead end.
    await waitFor(() => expect(dealsList).toHaveBeenCalled());
    await waitFor(() => expect(screen.getAllByText(/Aarti Menon/i).length).toBeGreaterThan(0));
    expect(screen.queryAllByRole('button', { name: /Send Proposal/i })).toHaveLength(0);
  });
});
