/**
 * DealRoomDashboard — opening a single deal calls GET /deals/:id as the brand, so the
 * server can record APPLICATION_VIEWED for the creator's timeline (F-0328).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * DealService.get() (influora-api DealService.java:170) records an APPLICATION_VIEWED
 * history event the first time a BRAND principal hits GET /deals/:id. Nothing on the
 * brand side ever called that endpoint — the deal room only ever fetched the LIST
 * (GET /deals?role=brand). Rendering the list is not "opening" any one deal, so a
 * list-level fetch must not be mistaken for a per-deal view: recording a view for an
 * application the brand never actually opened would put a false claim in the creator's
 * audit trail, which is worse than recording nothing. This test asserts both directions:
 * the list render alone must NOT call `deals.get`, and opening one specific deal MUST.
 *
 * Run: npx vitest run src/components/brand/deals/deal-room-dashboard-view-recorded.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { DealRoomDashboard } from './deal-room-dashboard';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

const dealsList = vi.fn();
const dealsGet = vi.fn();
const messagesList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    deals: {
      list: (...a: unknown[]) => dealsList(...a),
      get: (...a: unknown[]) => dealsGet(...a),
      accept: vi.fn(),
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

const DEAL_A = {
  id: 'deal_42',
  campaignName: 'Winter Launch',
  counterpartyId: '01HUSERULID000000000CREATR',
  counterpartyName: 'Rhea Kapoor',
  counterpartyAvatar: '',
  status: 'IN_NEGOTIATION',
  dealValue: 60000,
  deliverablesTotal: 4,
  nextDeadline: null,
  lastMessage: 'Sounds good, sending revised terms',
  lastMessageAt: '2026-08-01T10:00:00Z',
  unreadCount: 0,
};

const DEAL_B = {
  id: 'deal_43',
  campaignName: 'Winter Launch',
  counterpartyId: '01HUSERULID000000000CREAT2',
  counterpartyName: 'Aman Gill',
  counterpartyAvatar: '',
  status: 'APPLIED',
  dealValue: 40000,
  deliverablesTotal: 2,
  nextDeadline: null,
  lastMessage: 'Looking forward to it',
  lastMessageAt: '2026-08-01T09:00:00Z',
  unreadCount: 0,
};

function renderDashboard(initialPath = '/brand/deals') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/brand/deals" element={<DealRoomDashboard />} />
        <Route path="/brand/deals/:id" element={<DealRoomDashboard />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('DealRoomDashboard — opening one deal records a brand view (F-0328)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([DEAL_A, DEAL_B]);
    dealsGet.mockResolvedValue(DEAL_A);
    messagesList.mockResolvedValue([]);
  });

  it('does NOT call deals.get merely from rendering the list', async () => {
    renderDashboard();

    // list has loaded (both rows visible) but nothing has been opened yet
    await screen.findByText('Rhea Kapoor');
    await screen.findByText('Aman Gill');

    // give any stray microtask a chance to fire before asserting the negative
    await waitFor(() => expect(dealsList).toHaveBeenCalled());
    expect(dealsGet).not.toHaveBeenCalled();
  });

  it('calls deals.get(\'brand\', id) for the specific deal the brand opens by clicking it', async () => {
    const user = userEvent.setup({ delay: null });
    renderDashboard();

    await user.click(await screen.findByText('Rhea Kapoor'));

    await waitFor(() => expect(dealsGet).toHaveBeenCalledWith('brand', DEAL_A.id));
    // only the opened deal was recorded, not the other row in the list
    expect(dealsGet).not.toHaveBeenCalledWith('brand', DEAL_B.id);
    expect(dealsGet).toHaveBeenCalledTimes(1);
  });

  it('calls deals.get(\'brand\', id) when a deal is opened via deep link (/brand/deals/:id)', async () => {
    renderDashboard(`/brand/deals/${DEAL_B.id}`);

    await waitFor(() => expect(dealsGet).toHaveBeenCalledWith('brand', DEAL_B.id));
  });

  it('a failed deals.get must not block the deal from being shown', async () => {
    dealsGet.mockRejectedValue(new Error('network down'));
    const user = userEvent.setup({ delay: null });
    renderDashboard();

    await user.click(await screen.findByText('Rhea Kapoor'));

    await waitFor(() => expect(dealsGet).toHaveBeenCalledWith('brand', DEAL_A.id));
    // the deal detail is still rendered despite the view-record call rejecting
    expect(await screen.findByRole('button', { name: 'View Profile' })).toBeInTheDocument();
  });
});
