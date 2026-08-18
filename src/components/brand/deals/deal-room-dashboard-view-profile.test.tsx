/**
 * DealRoomDashboard — "View Profile" navigates to the real counterparty, not a hardcoded
 * fixture id (F-0157).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The View Profile button in the deal header was hardcoded to `navigate('/brand/creators/creator-1')`
 * for every deal, live or mock, unlike the rest of this file which gates carefully on `isApiLive()`.
 * Root cause: `DealRoom` (this file's local render shape) never carried the counterparty's real id —
 * `mapDealToRoom` mapped every other `Deal` field except `counterpartyId`. In live mode this was a
 * guaranteed dead link: `GET /creators/profile/creator-1` matches none of the backend resolver's three
 * branches (profile id / user id / username) for a real deal, so it 404s on every real deal every time.
 *
 * Run: npx vitest run src/components/brand/deals/deal-room-dashboard-view-profile.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { DealRoomDashboard } from './deal-room-dashboard';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => (...a: unknown[]) => navigateMock(...a) };
});

const dealsList = vi.fn();
const messagesList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    deals: {
      list: (...a: unknown[]) => dealsList(...a),
      // F-0328 — the dashboard now calls deals.get('brand', id) on open; this test
      // isn't about that call, so stub it to a resolved no-op rather than let it
      // reject unhandled.
      get: vi.fn().mockResolvedValue(null),
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

// A real deal's counterpartyId is a User id (per DealService.resolveCounterparty), never the
// literal 'creator-1' this button used to hardcode.
const REAL_DEAL = {
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

describe('DealRoomDashboard — View Profile navigates to the real counterparty (F-0157)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([REAL_DEAL]);
    messagesList.mockResolvedValue([]);
  });

  it('navigates to the deal\'s real counterpartyId, never the hardcoded creator-1 fixture', async () => {
    const user = userEvent.setup({ delay: null });
    renderDashboard();

    await user.click(await screen.findByText('Rhea Kapoor'));
    await user.click(await screen.findByRole('button', { name: 'View Profile' }));

    await waitFor(() => expect(navigateMock).toHaveBeenCalled());
    expect(navigateMock).toHaveBeenCalledWith(`/brand/creators/${REAL_DEAL.counterpartyId}`);
    expect(navigateMock).not.toHaveBeenCalledWith('/brand/creators/creator-1');
  });
});
