/**
 * DealRoomDashboard — a failed "Accept Proposal" must not strand the brand outside the
 * dialog they were reviewing (F-0440 / optimistic-update-not-rolled-back).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `handleAcceptProposal` used to call `setShowProposalDialog(false)` SYNCHRONOUSLY, before
 * `await dealsApi.accept(...)`. When the request then failed, the catch block only set
 * `actionError` — which the View Proposal dialog itself renders (so the brand could see why
 * it failed and retry from the same place) — but the dialog was already gone by the time the
 * catch ran. The brand was left back on the deal-room screen with no visible path back into
 * the proposal, even though the accept never actually went through.
 *
 * This test drives the flow exactly as a user would: open "View Proposal", click
 * "Accept & Create Contract" inside it, let the API call reject, and assert the dialog
 * (title + error text + a retry-able Accept button) is still on screen afterward. It also
 * proves a SUCCESSFUL accept still closes the dialog, so the fix isn't "never close it".
 *
 * Falsified against src/components/brand/deals/deal-room-dashboard.tsx's real
 * handleAcceptProposal (~line 440) — reverting the fix there turns the first test red for the
 * documented reason (dialog title unmounts) and does not affect the success-path test.
 *
 * Run: npx vitest run src/components/brand/deals/deal-room-dashboard-accept-rollback.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { DealRoomDashboard } from './deal-room-dashboard';

const dealsList = vi.fn();
const dealsAccept = vi.fn();
const messagesList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    deals: {
      list: (...a: unknown[]) => dealsList(...a),
      // F-0328 — dashboard calls deals.get('brand', id) on open; not under test here.
      get: vi.fn().mockResolvedValue(null),
      accept: (...a: unknown[]) => dealsAccept(...a),
      reject: vi.fn(),
      counter: vi.fn(),
    },
    messages: {
      list: (...a: unknown[]) => messagesList(...a),
      markRead: vi.fn().mockResolvedValue({ ok: true }),
      stream: vi.fn(() => ({ close: vi.fn() })),
    },
  };
});

const PROPOSED_DEAL = {
  id: 'deal_1',
  campaignName: 'Summer Launch',
  counterpartyName: 'Aarti Menon',
  counterpartyAvatar: '',
  status: 'INVITED', // -> DealRoom.status 'proposed'
  dealValue: 40000,
  deliverablesTotal: 2,
  nextDeadline: null,
  lastMessage: 'Looking forward to it',
  lastMessageAt: '2026-07-20T10:00:00Z',
  unreadCount: 0,
};

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/brand/deals']}>
      <Routes>
        <Route path="/brand/deals" element={<DealRoomDashboard />} />
        <Route path="/brand/deals/:id" element={<DealRoomDashboard />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function selectDealAndOpenProposal(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByText('Aarti Menon'));
  await user.click(await screen.findByRole('button', { name: 'View Proposal' }));
  // Dialog is open: its own "Accept & Create Contract" button, not the Overview tab's.
  await screen.findByRole('heading', { name: /Proposal Details/ });
}

describe('DealRoomDashboard — failed accept keeps a retry path (F-0440)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([PROPOSED_DEAL]);
    messagesList.mockResolvedValue([]);
  });

  it('keeps the View Proposal dialog open with the error visible when accept fails', async () => {
    dealsAccept.mockRejectedValue(new Error('network down'));
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await selectDealAndOpenProposal(user);

    await user.click(await screen.findByRole('button', { name: 'Accept & Create Contract' }));

    await waitFor(() => expect(dealsAccept).toHaveBeenCalledWith('deal_1', 'brand'));

    // THE regression this guards against: the dialog used to close synchronously before the
    // await, so by now its title and error text would already be gone from the DOM.
    const dialogHeading = await waitFor(() => {
      const heading = screen.getByRole('heading', { name: /Proposal Details/ });
      expect(heading).toBeInTheDocument();
      return heading;
    });
    // Scope to the dialog panel — the same actionError text also renders in the (still
    // mounted, now-obscured) Overview tab behind it, so an unscoped query matches both.
    const dialogPanel = dialogHeading.closest('[data-slot="dialog-content"]') as HTMLElement;
    expect(dialogPanel).toBeTruthy();
    expect(
      await within(dialogPanel).findByText('Could not accept the proposal. Try again.'),
    ).toBeInTheDocument();
    // A real retry path: the same Accept button is still there, inside the dialog, and
    // re-clickable.
    expect(
      within(dialogPanel).getByRole('button', { name: 'Accept & Create Contract' }),
    ).toBeInTheDocument();
  });

  it('closes the dialog once a retried accept actually succeeds', async () => {
    dealsAccept.mockResolvedValue({ ...PROPOSED_DEAL, status: 'CONTRACT_PENDING' });
    const user = userEvent.setup({ delay: null });
    renderDashboard();
    await selectDealAndOpenProposal(user);

    await user.click(await screen.findByRole('button', { name: 'Accept & Create Contract' }));

    await waitFor(() => expect(dealsAccept).toHaveBeenCalledWith('deal_1', 'brand'));
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: /Proposal Details/ })).not.toBeInTheDocument(),
    );
  });
});
