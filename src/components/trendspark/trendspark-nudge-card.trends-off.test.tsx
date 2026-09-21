/**
 * T-TSOFF-0920 — the BRAND Trend-Spark nudge renders nothing while the feature is off, and in
 * particular never renders the mock nudge.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Two distinct failure modes, one component:
 *
 *  1. LIVE MODE — the card was only *accidentally* silent: `GET /brand/trendspark/nudge`
 *     returned 204 because the `trends` table was empty, and the card hides on 204. Nothing
 *     asserted that, so any future change that rendered a placeholder on 204 would have shipped
 *     unnoticed. It also fired a request on every brand dashboard load for a feature that can
 *     never answer.
 *  2. MOCK MODE — `api.trendspark.getNudge()` returns `MOCK_TRENDSPARK_NUDGE`, a hand-written
 *     "Monsoon comfort cravings are trending this week" nudge, whenever `VITE_API_MODE !== 'live'`.
 *     A build that shipped without that env var set would have shown a real brand a fabricated
 *     trend as though Meera had found it. That is the exact class of defect the audit flagged
 *     ("mock data rendered to real users"), and the server switch now closes it: mock mode
 *     reports `trendsEnabled: false` unless VITE_MOCK_TRENDS_ENABLED=true is set on purpose.
 *
 * Run: npx vitest run src/components/trendspark/trendspark-nudge-card.trends-off.test.tsx
 */

import * as React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { TrendSparkNudgeCard } from './TrendSparkNudgeCard';
import { api, type TrendSparkNudge } from '@/lib/api';

const REAL_NUDGE: TrendSparkNudge = {
  nudgeId: 'nudge_1',
  mode: 'OWN_CONTENT',
  campaignType: 'SEASONAL',
  trendText: 'Monsoon comfort cravings are trending this week',
  message: 'Monsoon cravings are trending this week — your last reel fits this moment.',
  messageSource: 'AI',
  videos: [],
};

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TrendSparkNudgeCard />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function pinFlag(trendsEnabled: boolean) {
  return vi
    .spyOn(api.config, 'public')
    .mockResolvedValue({ requireEmailOtp: false, trendsEnabled });
}

describe('TrendSparkNudgeCard — T-TSOFF-0920 server switch', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('OFF: renders nothing at all, not even an empty card shell', async () => {
    pinFlag(false);
    const getNudge = vi.spyOn(api.trendspark, 'getNudge').mockResolvedValue(REAL_NUDGE);

    const { container } = renderCard();

    // Give the (suppressed) query every chance to resolve and paint.
    await waitFor(() => expect(api.config.public).toHaveBeenCalled());
    await Promise.resolve();

    expect(container).toBeEmptyDOMElement();
    expect(getNudge).not.toHaveBeenCalled();
  });

  it('OFF: a nudge already sitting in the cache is still not rendered', async () => {
    // Simulates the flag flipping off mid-session (or a stale hydrated cache): the data exists
    // client-side, but the server says the feature is off, so the server wins.
    pinFlag(false);
    vi.spyOn(api.trendspark, 'getNudge').mockResolvedValue(REAL_NUDGE);

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['trendspark', 'nudge'], REAL_NUDGE);
    const { container } = render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <TrendSparkNudgeCard />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(api.config.public).toHaveBeenCalled());
    expect(container.textContent ?? '').not.toMatch(/trend|Meera|Plan a campaign/i);
  });

  it('OFF in mock mode: the fabricated MOCK_TRENDSPARK_NUDGE never reaches the DOM', async () => {
    // No spy on getNudge at all — this exercises the REAL mock-mode client, which is what a
    // build with VITE_API_MODE unset would run.
    pinFlag(false);

    const { container } = renderCard();

    await waitFor(() => expect(api.config.public).toHaveBeenCalled());
    await Promise.resolve();

    expect(container.textContent ?? '').not.toMatch(/monsoon/i);
    expect(container).toBeEmptyDOMElement();
  });

  it('ON: the real nudge renders normally (the gate is not a mute button)', async () => {
    pinFlag(true);
    vi.spyOn(api.trendspark, 'getNudge').mockResolvedValue(REAL_NUDGE);

    renderCard();

    expect(await screen.findByText(/your last reel fits this moment/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /dismiss suggestion/i })).toBeInTheDocument();
  });

  it('ON + backend has nothing to say (204 → null): still renders nothing', async () => {
    // The pre-existing, CORRECT silence. Distinct from OFF, and it must survive this change.
    pinFlag(true);
    vi.spyOn(api.trendspark, 'getNudge').mockResolvedValue(null);

    const { container } = renderCard();

    await waitFor(() => expect(api.trendspark.getNudge).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
