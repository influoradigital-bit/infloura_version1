/**
 * T-MEERA-CREATOR-PHASE-A (A9, gate fix round 1 item 3) regression pin.
 *
 * Priya's audit (SHARED_CONTEXT.md Q2/Q6): Java's `VerifiedMetrics` record is
 * `@JsonInclude(NON_NULL)` with nullable `reach30d`/`engagementRate`/`verifiedAt` — a
 * Meta-connected creator whose metrics haven't been polled yet OMITS all three keys from the
 * response. The old TS type declared them required, so this page called `.toLocaleString()` on
 * `undefined` and crashed a public, unauthenticated, indexable page. This pins that the page
 * renders an honest "not available yet" instead of throwing when those fields are absent.
 *
 * Run: npx vitest run src/pages/creator-verified-metrics.null-fields.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import CreatorVerifiedMetricsPage from './creator-verified-metrics';
import type { PublicCreatorVerifiedResponse } from '@/lib/api';

const { getVerifiedMetricsMock } = vi.hoisted(() => ({ getVerifiedMetricsMock: vi.fn() }));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      publicCreators: { ...actual.api.publicCreators, getVerifiedMetrics: getVerifiedMetricsMock },
    },
  };
});

function renderAtUsername(username: string) {
  return render(
    <MemoryRouter initialEntries={[`/c/${username}/verified`]}>
      <Routes>
        <Route path="/c/:username/verified" element={<CreatorVerifiedMetricsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CreatorVerifiedMetricsPage — nullable metrics (A9)', () => {
  it('renders "Not available yet" instead of crashing when reach_30d/engagement_rate/verified_at are omitted', async () => {
    // Shape Jackson actually sends for a Meta-connected creator with no CreatorMetric row yet —
    // NON_NULL means these keys are entirely absent, not `null`, but `| null` in the TS type
    // covers both an absent key and an explicit JSON null.
    const response: PublicCreatorVerifiedResponse = {
      username: 'foodie.mumbai',
      display_name: 'Foodie Mumbai',
      city: 'Mumbai',
      categories: ['Food & Beverage'],
      verified_metrics: {
        followers: 12400,
        reach_30d: null,
        engagement_rate: null,
        verified_at: null,
      },
      platform_deal_count: 3,
      snapshot_date: new Date().toISOString(),
    };
    getVerifiedMetricsMock.mockResolvedValueOnce(response);

    renderAtUsername('foodie.mumbai');

    // Followers is never null — renders normally.
    expect(await screen.findByText('12,400')).toBeInTheDocument();
    // The two nullable stat tiles render the honest fallback, not a crash.
    const notAvailable = await screen.findAllByText(/not available yet/i);
    expect(notAvailable.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText(/verification pending/i)).toBeInTheDocument();
    // No uncaught error reached an error boundary / blank screen.
    expect(screen.queryByText(/creator not found/i)).not.toBeInTheDocument();
  });

  it('renders real values when the metrics are fully populated', async () => {
    const response: PublicCreatorVerifiedResponse = {
      username: 'foodie.mumbai',
      display_name: 'Foodie Mumbai',
      city: 'Mumbai',
      categories: ['Food & Beverage'],
      verified_metrics: {
        followers: 12400,
        reach_30d: 45600,
        engagement_rate: 3.2,
        verified_at: new Date('2026-08-01T00:00:00Z').toISOString(),
      },
      platform_deal_count: 3,
      snapshot_date: new Date().toISOString(),
    };
    getVerifiedMetricsMock.mockResolvedValueOnce(response);

    renderAtUsername('foodie.mumbai');

    expect(await screen.findByText('45,600')).toBeInTheDocument();
    expect(screen.getByText('3.2%')).toBeInTheDocument();
    expect(screen.queryByText(/not available yet/i)).not.toBeInTheDocument();
  });
});
