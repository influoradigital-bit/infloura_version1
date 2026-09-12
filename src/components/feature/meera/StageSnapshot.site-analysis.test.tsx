/**
 * StageSnapshot — the site-analysis give-up state (P1-13).
 *
 * THE DEFECT: `useBrandProfile` bounded its poll at 30 fetches (~2 min) and
 * then simply stopped, SILENTLY. The profile stayed `PENDING`, so the canvas
 * kept rendering a waiting state — the "Reading your site…" spinner, and after
 * its own 30s bound the `SnapshotIdle` card promising the snapshot "will appear
 * here" — while nothing was polling any more and nothing ever would. No error,
 * no message, no way back. Raising the poll count would only have lengthened
 * the silence, so the hook now raises `analysisTimedOut` and this component
 * renders a terminal state with a re-check control.
 *
 * WHY THESE FAIL AGAINST THE PRE-FIX COMPONENT: the pre-fix StageSnapshot reads
 * neither `analysisTimedOut` nor `restartAnalysisPoll` from the hook — the hook
 * did not expose them. With the profile at `PENDING` + a websiteUrl it takes the
 * `analysisInFlight` branch and renders `StageLoadingState` ("Reading your
 * site…"), so:
 *   - "renders a terminal state" fails: no /stopped waiting/ text exists;
 *   - "does not keep implying progress" fails: the spinner IS on screen;
 *   - "offers a re-check" fails: there is no button in the tree at all.
 * The ERROR case fails the same way: pre-fix it renders `analysisError` alone
 * in a div — no explanation of the no-JavaScript limitation and no control.
 *
 * Run: npx vitest run src/components/feature/meera/StageSnapshot.site-analysis.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import type { MeeraBrandProfile } from '@/lib/meera-api';

// jsdom has no IntersectionObserver, and the READY snapshot renders framer-motion's
// whileInView, whose viewport feature constructs one on mount. Without this the ready-state
// test dies with "IntersectionObserver is not defined" inside framer-motion rather than on
// anything about StageSnapshot. Same local stub every other framer-motion-rendering test file
// in this repo carries (e.g. src/pages/creator-analytics.test.tsx).
class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
// @ts-expect-error — jsdom has no IntersectionObserver global.
global.IntersectionObserver = MockIntersectionObserver;

const restartAnalysisPoll = vi.fn();
let hookState: {
  brandProfile: MeeraBrandProfile | null;
  isLoading: boolean;
  error: string | null;
  refetch: () => void;
  analysisTimedOut: boolean;
  restartAnalysisPoll: () => void;
};

vi.mock('@/hooks/useBrandProfile', () => ({
  useBrandProfile: () => hookState,
  default: () => hookState,
}));

// Live mode — the mock-data branch returns MOCK_BRAND_SNAPSHOT and never
// touches the profile at all, so these states only exist when the API is live.
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return { ...actual, isApiLive: () => true };
});

import { StageSnapshot } from './StageSnapshot';

function profile(overrides: Partial<MeeraBrandProfile> = {}): MeeraBrandProfile {
  return {
    workspaceId: 'ws_1',
    websiteUrl: 'kavalaskincare.com',
    analysisStatus: 'PENDING',
    nicheTags: null,
    productCatalog: null,
    analysisError: null,
    ...overrides,
  };
}

function setHook(overrides: Partial<typeof hookState> = {}) {
  hookState = {
    brandProfile: null,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    analysisTimedOut: false,
    restartAnalysisPoll,
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  setHook();
});

describe('StageSnapshot — poll budget exhausted while still PENDING', () => {
  beforeEach(() => {
    setHook({
      brandProfile: profile({ analysisStatus: 'PENDING' }),
      analysisTimedOut: true,
    });
  });

  it('says we stopped, naming the site', () => {
    render(<StageSnapshot />);
    expect(screen.getByText(/stopped waiting on your site/i)).toBeInTheDocument();
    expect(screen.getByText(/kavalaskincare\.com/i)).toBeInTheDocument();
  });

  it('stops implying progress — no spinner and no "will appear here" promise', () => {
    render(<StageSnapshot />);
    // The pre-fix silence: both of these stayed on screen forever.
    expect(screen.queryByText(/Reading your site/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/will appear here/i)).not.toBeInTheDocument();
  });

  it('offers a re-check, and is honest that we never ran the page scripts', async () => {
    const user = userEvent.setup();
    render(<StageSnapshot />);

    expect(screen.getByText(/without running its scripts/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /check again/i }));
    expect(restartAnalysisPoll).toHaveBeenCalledTimes(1);
  });
});

describe('StageSnapshot — analysis failed', () => {
  it('explains the failure and offers a way forward instead of a bare error string', async () => {
    const user = userEvent.setup();
    setHook({
      brandProfile: profile({
        analysisStatus: 'ERROR',
        analysisError: 'no readable content found',
      }),
    });
    render(<StageSnapshot />);

    expect(screen.getByText(/couldn't read your site/i)).toBeInTheDocument();
    expect(screen.getByText(/no readable content found/i)).toBeInTheDocument();
    expect(screen.getByText(/without running its scripts/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /check again/i }));
    expect(restartAnalysisPoll).toHaveBeenCalledTimes(1);
  });
});

describe('StageSnapshot — states that must not regress', () => {
  it('still shows the spinner while analysis is genuinely in flight', () => {
    setHook({ brandProfile: profile({ analysisStatus: 'ANALYZING' }) });
    render(<StageSnapshot />);
    expect(screen.getByText(/Reading your site/i)).toBeInTheDocument();
  });

  it('still renders the ready snapshot with its products', () => {
    setHook({
      brandProfile: profile({
        analysisStatus: 'READY',
        productCatalog: [{ name: 'Vitamin C Serum', price: 899 }],
      }),
    });
    render(<StageSnapshot />);
    expect(screen.getByText('Vitamin C Serum')).toBeInTheDocument();
  });
});
