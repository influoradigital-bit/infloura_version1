/**
 * T-TSOFF-0920 — the card's OWN flag check, in isolation.
 *
 * WHY THIS FILE IS SEPARATE from `trendspark-nudge-card.trends-off.test.tsx`. That file drives
 * the card through the real hooks, which is the honest end-to-end proof — but it means two
 * independent guards are active at once (`useTrendSparkNudge` refuses to fetch AND nulls its
 * result while the flag is off), so removing the card's own `trendsEnabled &&` alone does not
 * make it fail. A defence that no test can falsify on its own is a defence nobody can safely
 * refactor. Here the data hook is mocked to hand the card a live nudge unconditionally, so the
 * only thing standing between that nudge and the DOM is the card's own check.
 *
 * Module mocks are file-scoped and hoisted, which is why this cannot live in the other file.
 *
 * Run: npx vitest run src/components/trendspark/trendspark-nudge-card.gate.test.tsx
 */

import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { TrendSparkNudgeCard } from './TrendSparkNudgeCard';
import { useTrendSparkNudge } from '@/hooks/trendspark/useTrendSparkNudge';
import { useTrendsEnabled } from '@/hooks/useTrendsEnabled';
import type { TrendSparkNudge } from '@/lib/api';

vi.mock('@/hooks/trendspark/useTrendSparkNudge', () => ({ useTrendSparkNudge: vi.fn() }));
vi.mock('@/hooks/useTrendsEnabled', () => ({ useTrendsEnabled: vi.fn() }));

const mockedNudge = vi.mocked(useTrendSparkNudge);
const mockedFlag = vi.mocked(useTrendsEnabled);

const NUDGE: TrendSparkNudge = {
  nudgeId: 'nudge_1',
  mode: 'OWN_CONTENT',
  campaignType: 'SEASONAL',
  trendText: 'Monsoon comfort cravings are trending this week',
  message: 'Monsoon cravings are trending this week — your last reel fits this moment.',
  messageSource: 'AI',
  videos: [],
};

function renderCard() {
  return render(
    <MemoryRouter>
      <TrendSparkNudgeCard />
    </MemoryRouter>,
  );
}

describe('TrendSparkNudgeCard — the component-level flag check', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The data layer is made maximally unhelpful on purpose: it hands over a real nudge whatever
    // the flag says, exactly as it would if a future refactor dropped the hook's own guard.
    mockedNudge.mockReturnValue({
      nudge: NUDGE,
      isLoading: false,
      error: null,
      recordClick: vi.fn(),
      recordPurchase: vi.fn(),
    });
  });

  it('flag off: the nudge handed to it is still not rendered', () => {
    mockedFlag.mockReturnValue({ trendsEnabled: false, isLoading: false });

    const { container } = renderCard();

    expect(container).toBeEmptyDOMElement();
  });

  it('flag still loading: nothing renders (no flash of a card that then disappears)', () => {
    mockedFlag.mockReturnValue({ trendsEnabled: false, isLoading: true });

    const { container } = renderCard();

    expect(container).toBeEmptyDOMElement();
  });

  it('flag on: the same nudge renders, dismiss control included', () => {
    mockedFlag.mockReturnValue({ trendsEnabled: true, isLoading: false });

    renderCard();

    expect(screen.getByText(/your last reel fits this moment/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /dismiss suggestion/i })).toBeInTheDocument();
  });
});
