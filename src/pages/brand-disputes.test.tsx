/**
 * Brand Disputes page — Kv3b (Kavya)
 * Stand-in coverage while Ananya #38 creator-disputes is in flight.
 * Exercises the same list/empty/error patterns creator-disputes should mirror.
 *
 * Run: npx vitest run src/pages/brand-disputes.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandDisputesPage from './brand-disputes';
import { ApiError } from '@/lib/api';

const listMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      brandDisputes: {
        list: (...args: unknown[]) => listMock(...args),
      },
    },
  };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/disputes']}>
      <BrandDisputesPage />
    </MemoryRouter>,
  );
}

describe('BrandDisputesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders heading and mock dispute rows', async () => {
    listMock.mockResolvedValue([
      {
        collaborationId: 'deal-disputed-1',
        campaignName: 'Summer Fashion Campaign',
        counterpartyName: 'Rahul Verma',
        dealValue: 45000,
        currency: 'INR',
        disputeStatus: 'UNDER_REVIEW',
        reason: 'Missing product tag.',
        openedAt: '2026-07-01T00:00:00.000Z',
      },
    ]);

    renderPage();

    expect(screen.getByRole('heading', { name: 'Disputes' })).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('Summer Fashion Campaign')).toBeInTheDocument();
    });
    expect(screen.getByText(/Rahul Verma/)).toBeInTheDocument();
    expect(screen.getByText(/Under review/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View deal room/i })).toHaveAttribute(
      'href',
      '/brand/chat?deal=deal-disputed-1',
    );
  });

  it('shows empty state when list is empty', async () => {
    listMock.mockResolvedValue([]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('No disputes')).toBeInTheDocument();
    });
  });

  it('shows error alert and retries on Try again', async () => {
    const user = userEvent.setup();
    listMock
      .mockRejectedValueOnce(new ApiError('SERVER_ERROR', 'Upstream failed'))
      .mockResolvedValueOnce([]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Could not load disputes')).toBeInTheDocument();
    });
    expect(screen.getByText('Upstream failed')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Try again/i }));

    await waitFor(() => {
      expect(screen.getByText('No disputes')).toBeInTheDocument();
    });
    expect(listMock).toHaveBeenCalledTimes(2);
  });

  /**
   * Rewritten 2026-07-26, mirroring the creator-side change. This asserted a "Showing partial
   * data" banner whose copy claimed no brand dispute-list endpoint existed — closed by P2-14
   * (`GET /brand/disputes/list`). The banner was also unreachable: the DTO builds `disputeStatus`
   * from `dispute.getStatus().name()` (non-null enum) and the mock rows hardcode it, so the
   * condition was false in both live and mock mode. Banner removed; this now pins the defensive
   * render path that survives it.
   */
  it('renders a neutral Disputed badge when a row carries no disputeStatus', async () => {
    listMock.mockResolvedValue([
      {
        collaborationId: 'deal-x',
        campaignName: 'Partial Campaign',
        counterpartyName: 'Creator X',
        dealValue: 10000,
        currency: 'INR',
      },
    ]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Partial Campaign')).toBeInTheDocument();
    });
    expect(screen.getByText('Disputed')).toBeInTheDocument();
    expect(screen.queryByText(/Showing partial data/i)).not.toBeInTheDocument();
  });
});
