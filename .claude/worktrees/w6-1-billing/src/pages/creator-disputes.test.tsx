/**
 * Creator Disputes page — Kv-GA-1 (Kavya)
 * Hostile + smoke coverage for Task #38 (shipped page — QA only).
 *
 * Run: npx vitest run src/pages/creator-disputes.test.tsx
 */

import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorDisputesPage from './creator-disputes';
import { ApiError, type CreatorDisputeRow, type Deal } from '@/lib/api';

const listMock = vi.fn();
const listEligibleMock = vi.fn();
const openMock = vi.fn();

// Radix Select requires PointerEvent capture APIs that jsdom lacks.
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'hasPointerCapture', {
    configurable: true,
    value: () => false,
  });
  Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', {
    configurable: true,
    value: () => undefined,
  });
  Object.defineProperty(HTMLElement.prototype, 'releasePointerCapture', {
    configurable: true,
    value: () => undefined,
  });
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: () => undefined,
  });
});

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => false,
    api: {
      ...actual.api,
      creatorDisputes: {
        list: (...args: unknown[]) => listMock(...args),
        listEligibleDeals: (...args: unknown[]) => listEligibleMock(...args),
        open: (...args: unknown[]) => openMock(...args),
      },
    },
  };
});

const OWN_DISPUTES: CreatorDisputeRow[] = [
  {
    collaborationId: 'deal-own-1',
    campaignName: 'Summer Fashion Campaign',
    counterpartyName: 'Luxe Apparel',
    dealValue: 45000,
    currency: 'INR',
    disputeStatus: 'UNDER_REVIEW',
    reason: 'Escrow not released after approval.',
    openedAt: '2026-07-01T00:00:00.000Z',
  },
];

const ELIGIBLE: Deal[] = [
  {
    id: 'deal-eligible-1',
    campaignId: 'camp-1',
    campaignName: 'Monsoon Drop',
    counterpartyId: 'brand-1',
    counterpartyName: 'Rainwear Studio',
    status: 'IN_PROGRESS',
    dealValue: 28000,
    currency: 'INR',
    unreadCount: 0,
    deliverablesDone: 1,
    deliverablesTotal: 2,
    escrowFunded: true,
  },
];

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/disputes']}>
      <CreatorDisputesPage />
    </MemoryRouter>,
  );
}

/** Prefer keyboard over pointer — more reliable with Radix Select in jsdom. */
async function selectEligibleDeal(user: ReturnType<typeof userEvent.setup>) {
  const trigger = screen.getByRole('combobox');
  await user.click(trigger);
  // ArrowDown + Enter selects first option without relying on portal pointer events
  await user.keyboard('{ArrowDown}{Enter}');
}

describe('CreatorDisputesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMock.mockResolvedValue(OWN_DISPUTES);
    listEligibleMock.mockResolvedValue(ELIGIBLE);
    openMock.mockResolvedValue({
      id: 'dsp_1',
      collaborationId: 'deal-eligible-1',
      openedByType: 'CREATOR',
      openedByUserId: 'cr_1',
      reason: 'Brand missed payment window after deliverable approval.',
      status: 'OPEN',
      createdAt: '2026-07-10T00:00:00.000Z',
    });
  });

  it('renders heading inside creator layout and loads via api.creatorDisputes', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Disputes' })).toBeInTheDocument();
    expect(
      screen.getByText(/Open a dispute on a funded collaboration/i),
    ).toBeInTheDocument();

    await waitFor(() => {
      expect(listMock).toHaveBeenCalled();
      expect(listEligibleMock).toHaveBeenCalled();
    });
    expect(screen.getByText('Summer Fashion Campaign')).toBeInTheDocument();
    expect(screen.getByText(/Luxe Apparel/)).toBeInTheDocument();
  });

  it('shows only own disputes from list() — no foreign fabricated rows', async () => {
    listMock.mockResolvedValue([
      {
        collaborationId: 'deal-own-only',
        campaignName: 'My Own Campaign',
        counterpartyName: 'My Brand',
        dealValue: 12000,
        currency: 'INR',
        disputeStatus: 'OPEN',
        reason: 'Own dispute only.',
        openedAt: '2026-07-02T00:00:00.000Z',
      },
    ]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('My Own Campaign')).toBeInTheDocument();
    });
    // Brand-side mock campaign must not appear unless returned by creator list
    expect(screen.queryByText('Rahul Verma')).not.toBeInTheDocument();
    expect(screen.queryByText(/Winter Collection/)).not.toBeInTheDocument();
    expect(listMock).toHaveBeenCalledTimes(1);
  });

  it('renders all five lifecycle labels correctly', async () => {
    listMock.mockResolvedValue([
      {
        collaborationId: 'd-open',
        campaignName: 'Case Open',
        counterpartyName: 'Brand A',
        dealValue: 1000,
        currency: 'INR',
        disputeStatus: 'OPEN',
      },
      {
        collaborationId: 'd-review',
        campaignName: 'Case Review',
        counterpartyName: 'Brand B',
        dealValue: 2000,
        currency: 'INR',
        disputeStatus: 'UNDER_REVIEW',
      },
      {
        collaborationId: 'd-brand',
        campaignName: 'Case Brand Win',
        counterpartyName: 'Brand C',
        dealValue: 3000,
        currency: 'INR',
        disputeStatus: 'RESOLVED_BRAND',
      },
      {
        collaborationId: 'd-creator',
        campaignName: 'Case Creator Win',
        counterpartyName: 'Brand D',
        dealValue: 4000,
        currency: 'INR',
        disputeStatus: 'RESOLVED_CREATOR',
      },
      {
        collaborationId: 'd-split',
        campaignName: 'Case Split',
        counterpartyName: 'Brand E',
        dealValue: 5000,
        currency: 'INR',
        disputeStatus: 'RESOLVED_SPLIT',
      },
    ]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Case Open')).toBeInTheDocument();
    });
    expect(screen.getByText('Open')).toBeInTheDocument();
    expect(screen.getByText('Under review')).toBeInTheDocument();
    expect(screen.getByText("Resolved — brand's favor")).toBeInTheDocument();
    expect(screen.getByText('Resolved — in your favor')).toBeInTheDocument();
    expect(screen.getByText('Resolved — split')).toBeInTheDocument();
  });

  it('shows honest empty eligible-deals copy (no fabricated select options)', async () => {
    listEligibleMock.mockResolvedValue([]);
    listMock.mockResolvedValue([]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/No eligible deals right now/i)).toBeInTheDocument();
    });
    expect(
      screen.getByText(/funded collaboration that is not already disputed/i),
    ).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.getByText('No disputes')).toBeInTheDocument();
  });

  it('does not offer a second-active-dispute path when eligible list is empty', async () => {
    // Backend one-active rule: already-disputed collabs are omitted from eligible.
    listEligibleMock.mockResolvedValue([]);
    listMock.mockResolvedValue(OWN_DISPUTES);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Summer Fashion Campaign')).toBeInTheDocument();
    });
    expect(screen.getByText(/No eligible deals right now/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Open dispute/i })).not.toBeInTheDocument();
  });

  it('opens a dispute via api.creatorDisputes.open with trimmed reason', async () => {
    const user = userEvent.setup();
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toBeInTheDocument();
    });

    await selectEligibleDeal(user);

    const reason = screen.getByLabelText(/Reason/i);
    await user.type(
      reason,
      'Brand missed the payment window after deliverable approval in chat.',
    );
    await user.click(screen.getByRole('button', { name: /^Open dispute$/i }));

    await waitFor(() => {
      expect(openMock).toHaveBeenCalledWith(
        'deal-eligible-1',
        'Brand missed the payment window after deliverable approval in chat.',
      );
    });
    expect(
      await screen.findByText(/Dispute opened\. An admin will review/i),
    ).toBeInTheDocument();
    expect(listMock.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(listEligibleMock.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('surfaces DISPUTE_ALREADY_OPEN from open() — no silent second-active UX', async () => {
    const user = userEvent.setup();
    openMock.mockRejectedValue(
      new ApiError('DISPUTE_ALREADY_OPEN', 'A dispute is already open on this collaboration.'),
    );

    renderPage();

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toBeInTheDocument();
    });
    await selectEligibleDeal(user);
    await user.type(
      screen.getByLabelText(/Reason/i),
      'Trying to open a second active dispute on the same deal.',
    );
    await user.click(screen.getByRole('button', { name: /^Open dispute$/i }));

    await waitFor(() => {
      expect(screen.getByText('Could not open dispute')).toBeInTheDocument();
    });
    expect(
      screen.getByText(/A dispute is already open on this collaboration/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Dispute opened\. An admin will review/i)).not.toBeInTheDocument();
  });

  it('links dispute cards to creator deal room', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Summer Fashion Campaign')).toBeInTheDocument();
    });
    const link = screen.getByRole('link', { name: /View deal room/i });
    expect(link).toHaveAttribute('href', '/creator/chat?deal=deal-own-1');
  });

  it('shows list error and retries on Try again', async () => {
    const user = userEvent.setup();
    listMock
      .mockRejectedValueOnce(new ApiError('SERVER_ERROR', 'Upstream failed'))
      .mockResolvedValueOnce([]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Could not load disputes')).toBeInTheDocument();
    });
    expect(screen.getByText('Upstream failed')).toBeInTheDocument();

    const alert = screen.getByText('Could not load disputes').closest('[role="alert"]');
    expect(alert).toBeTruthy();
    await user.click(within(alert as HTMLElement).getByRole('button', { name: /Try again/i }));

    await waitFor(() => {
      expect(screen.getByText('No disputes')).toBeInTheDocument();
    });
    expect(listMock).toHaveBeenCalledTimes(2);
  });

  it('shows partial-data banner when disputeStatus is missing', async () => {
    listMock.mockResolvedValue([
      {
        collaborationId: 'deal-partial',
        campaignName: 'Partial Campaign',
        counterpartyName: 'Brand X',
        dealValue: 10000,
        currency: 'INR',
      },
    ]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/Showing partial data/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/GET \/creator\/disputes/)).toBeInTheDocument();
    expect(screen.getByText('Disputed')).toBeInTheDocument();
  });
});
