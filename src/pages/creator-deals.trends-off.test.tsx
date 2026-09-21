/**
 * T-TSOFF-0920 — the Co-pilot teaser on the creator Deals page is a dead control while trend
 * ingest is off, so it must not be rendered at all.
 *
 * WHY THIS FILE EXISTS. The card's entire proposition is its own label: "Get today's content idea
 * from Co-pilot". With the feature off there is no idea behind it — tapping it navigates to a
 * page whose only honest answer is "not available yet". A control that cannot do the one thing it
 * advertises is exactly the "no dead controls" rule this task is about, and no existing test on
 * this page touched it.
 *
 * The /creator/copilot ROUTE and the sidebar nav entry are deliberately NOT hidden: that page
 * also hosts Meera chat, which is switched ON for the beta. Only this teaser goes.
 *
 * Run: npx vitest run src/pages/creator-deals.trends-off.test.tsx
 */

import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CreatorDealsPage from './creator-deals';
import { api } from '@/lib/api';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/components/creator/hype-inbox-card', () => ({
  HypeInboxCard: () => <div data-testid="hype-inbox-card" />,
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/creator/deals']}>
        <CreatorDealsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const TEASER = /get today.s content idea from co-pilot/i;

describe('CreatorDealsPage — T-TSOFF-0920 Co-pilot teaser', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(api.deals, 'list').mockResolvedValue([]);
  });

  it('trends off: the teaser card is not rendered', async () => {
    vi.spyOn(api.config, 'public').mockResolvedValue({
      requireEmailOtp: false,
      trendsEnabled: false,
    });

    renderPage();

    await waitFor(() => expect(api.config.public).toHaveBeenCalled());
    // Settle any pending state flush so a late render cannot sneak the card in after the assert.
    await waitFor(() => expect(screen.getByTestId('creator-layout')).toBeInTheDocument());
    expect(screen.queryByText(TEASER)).toBeNull();
  });

  it('trends off: there is therefore no way to navigate to Co-pilot from Deals', async () => {
    vi.spyOn(api.config, 'public').mockResolvedValue({
      requireEmailOtp: false,
      trendsEnabled: false,
    });
    const user = userEvent.setup({ delay: null });

    renderPage();
    await waitFor(() => expect(api.config.public).toHaveBeenCalled());

    const teaser = screen.queryByText(TEASER);
    expect(teaser).toBeNull();
    if (teaser) await user.click(teaser);
    expect(navigateMock).not.toHaveBeenCalledWith('/creator/copilot');
  });

  it('trends on: the teaser is restored and still navigates to /creator/copilot', async () => {
    vi.spyOn(api.config, 'public').mockResolvedValue({
      requireEmailOtp: false,
      trendsEnabled: true,
    });
    const user = userEvent.setup({ delay: null });

    renderPage();

    const teaser = await screen.findByText(TEASER);
    await user.click(teaser);
    expect(navigateMock).toHaveBeenCalledWith('/creator/copilot');
  });
});
