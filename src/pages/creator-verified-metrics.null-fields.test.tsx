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
  it('renders "Not available yet" for every figure when no Meta-synced snapshot exists yet', async () => {
    // Shape Jackson actually sends for a Meta-connected creator with no Meta-synced CreatorMetric
    // row yet — NON_NULL means these keys are entirely absent, not `null`, but `| null` in the TS
    // type covers both. F-0964: followers is omitted too; it used to be filled from the profile's
    // cross-platform total, which includes creator-declared (unverified) platforms.
    const response: PublicCreatorVerifiedResponse = {
      username: 'foodie.mumbai',
      display_name: 'Foodie Mumbai',
      city: 'Mumbai',
      categories: ['Food & Beverage'],
      verified_metrics: {
        followers: null,
        reach_30d: null,
        engagement_rate: null,
        verified_at: null,
      },
      platform_deal_count: 3,
      snapshot_date: new Date().toISOString(),
    };
    getVerifiedMetricsMock.mockResolvedValueOnce(response);

    renderAtUsername('foodie.mumbai');

    // All three stat tiles (followers, reach, engagement) render the honest fallback.
    const notAvailable = await screen.findAllByText(/not available yet/i);
    expect(notAvailable.length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText('12,400')).not.toBeInTheDocument();
    expect(screen.getByText(/verification pending/i)).toBeInTheDocument();
    // No uncaught error reached an error boundary / blank screen.
    expect(screen.queryByText(/creator not found/i)).not.toBeInTheDocument();
  });

  it('the REAL wire shape (keys omitted, not null) renders safely and claims no verification', async () => {
    // Jackson NON_NULL drops null fields, so the page receives `verified_metrics: {}` and every
    // field is `undefined`, not `null`. A `!== null` guard would crash here with a white screen.
    const response = {
      username: 'foodie.mumbai',
      display_name: 'Foodie Mumbai',
      city: 'Mumbai',
      categories: ['Food & Beverage'],
      verified_metrics: {},
      platform_deal_count: 3,
      snapshot_date: new Date().toISOString(),
    } as unknown as PublicCreatorVerifiedResponse;
    getVerifiedMetricsMock.mockResolvedValueOnce(response);

    renderAtUsername('foodie.mumbai');

    const notAvailable = await screen.findAllByText(/not available yet/i);
    expect(notAvailable.length).toBeGreaterThanOrEqual(3);
    // Nothing is verified yet, so nothing may say it is: not the badge's label, not a heading,
    // caption, title attribute or screen-reader text anywhere on the page.
    expect(screen.queryByLabelText('Meta-verified metrics')).not.toBeInTheDocument();
    expect(screen.getByText(/verification pending/i)).toBeInTheDocument();
    const page = document.body;
    const claims = (page.textContent ?? '') + ' ' +
      Array.from(page.querySelectorAll('[title],[aria-label]'))
        .map((el) => `${el.getAttribute('title') ?? ''} ${el.getAttribute('aria-label') ?? ''}`)
        .join(' ');
    // Only the honest "Verification pending" may mention verification; nothing may mention a
    // snapshot, and no badge icon may render (even unlabelled or wrapped in a title).
    const withoutPending = claims.replace(/verification pending/gi, '');
    expect(withoutPending).not.toMatch(/verif/i);
    expect(withoutPending).not.toMatch(/snapshot/i);
    expect(page.querySelector('svg.lucide-badge-check')).toBeNull();
  });

  it('a real snapshot with only followers (0) still shows as verified, and a real 0 stays 0', async () => {
    // Mixed wire shape: Meta returned a snapshot (verified_at) but no reach/engagement, and the
    // account really has 0 followers. Verification must key on verified_at, and 0 is a value.
    const response = {
      username: 'foodie.mumbai',
      display_name: 'Foodie Mumbai',
      city: 'Mumbai',
      categories: [],
      verified_metrics: { followers: 0, verified_at: new Date('2026-08-01T00:00:00Z').toISOString() },
      platform_deal_count: 7,
      snapshot_date: new Date().toISOString(),
    } as unknown as PublicCreatorVerifiedResponse;
    getVerifiedMetricsMock.mockResolvedValueOnce(response);

    renderAtUsername('foodie.mumbai');

    expect(await screen.findByLabelText('Meta-verified metrics')).toBeInTheDocument();
    expect(screen.getByText('Verified Metrics')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
    // Reach and engagement are missing, so exactly those two say so.
    expect(screen.getAllByText(/not available yet/i)).toHaveLength(2);
    // The snapshot line shows the snapshot's own date, not today.
    expect(screen.getByText(/snapshot from/i).textContent).toMatch(/2026/);
    expect(screen.getByText(/snapshot from/i).textContent).toMatch(/aug/i);
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
    // With a real Meta snapshot the verified badge, heading and snapshot line ARE shown, and
    // the follower value is the formatted count.
    expect(screen.getByLabelText('Meta-verified metrics')).toBeInTheDocument();
    expect(screen.getByText('Verified Metrics')).toBeInTheDocument();
    expect(screen.getByText(/snapshot from/i)).toBeInTheDocument();
    expect(screen.getByText('12,400')).toBeInTheDocument();
  });
});
