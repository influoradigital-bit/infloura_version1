/**
 * Brand Pipeline — F-0263 (negative-duration-rendered).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `slaHoursRemaining` is computed from `hoursUntil(deal.nextDeadline)` with no floor. An
 * overdue deal (deadline already in the past) produces a negative number, and every render
 * site printed it verbatim ("SLA at risk: -37h remaining"), which reads as a countdown still
 * ticking rather than an overdue state. A negative number is type-correct
 * (`slaHoursRemaining?: number`), so nothing caught this statically.
 *
 * This exercises the LIVE-mode mapping path (`dealsApi.list` -> `mapDealToCollaboration` ->
 * `hoursUntil`) with a real past ISO deadline — not the hardcoded mock array, which never
 * contained a negative value — per the ticket's own "no test supplies a deadline in the past"
 * note.
 *
 * Run: npx vitest run src/pages/__tests__/brand-pipeline.sla.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import BrandPipelinePage from '../brand-pipeline';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const listDeals = vi.fn();
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    deals: { list: (...a: unknown[]) => listDeals(...a) },
  };
});

function renderPage() {
  return render(
    <MemoryRouter>
      <BrandPipelinePage />
    </MemoryRouter>,
  );
}

function baseDeal(overrides: Record<string, unknown>) {
  return {
    id: 'deal-1',
    campaignId: 'camp-1',
    campaignName: 'Test Campaign',
    counterpartyId: 'creator-1',
    counterpartyName: 'Test Creator',
    counterpartyAvatar: '',
    status: 'IN_PROGRESS',
    dealValue: 10000,
    currency: 'INR',
    unreadCount: 0,
    deliverablesDone: 1,
    deliverablesTotal: 4,
    escrowFunded: true,
    ...overrides,
  };
}

describe('BrandPipelinePage — overdue SLA rendering (F-0263)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('a deal whose deadline is already in the past renders an overdue state, not a negative hour count', async () => {
    const pastDeadline = new Date(Date.now() - 37 * 60 * 60 * 1000).toISOString();
    listDeals.mockResolvedValue([
      baseDeal({
        id: 'deal-overdue',
        counterpartyName: 'Overdue Creator',
        nextDeadline: pastDeadline,
      }),
    ]);

    renderPage();
    await screen.findByText('Overdue Creator');

    // Must read as overdue.
    expect(screen.getAllByText(/overdue/i).length).toBeGreaterThan(0);
    // Must NOT render a raw negative hour count anywhere on the page ("-37h" / "−37h" /
    // "SLA at risk: -37h remaining").
    expect(document.body.textContent).not.toMatch(/-\d+h/);
    expect(document.body.textContent).not.toMatch(/−\d+h/);
  });

  it('a deal well inside its SLA window still renders a plain positive hour count, unchanged', async () => {
    const futureDeadline = new Date(Date.now() + 5 * 60 * 60 * 1000).toISOString();
    listDeals.mockResolvedValue([
      baseDeal({
        id: 'deal-on-track',
        counterpartyName: 'On Track Creator',
        nextDeadline: futureDeadline,
      }),
    ]);

    renderPage();
    await screen.findByText('On Track Creator');

    expect(screen.getByText(/SLA at risk: 5h remaining/i)).toBeInTheDocument();
    expect(screen.queryByText(/overdue/i)).not.toBeInTheDocument();
  });

  it('a deal with no deadline at all renders neither an overdue nor an at-risk state', async () => {
    listDeals.mockResolvedValue([
      baseDeal({
        id: 'deal-no-deadline',
        counterpartyName: 'No Deadline Creator',
        nextDeadline: undefined,
      }),
    ]);

    renderPage();
    await screen.findByText('No Deadline Creator');

    expect(screen.queryByText(/overdue/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/SLA at risk/i)).not.toBeInTheDocument();
  });
});
