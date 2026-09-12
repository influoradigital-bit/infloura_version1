/**
 * useBrandProfile — the poll budget must END OUT LOUD (P1-13).
 *
 * THE DEFECT: the budget was a poll *count* read only inside `refetchInterval`
 * (`if (query.state.dataUpdateCount >= 30) return false`). When it ran out the
 * hook stopped fetching and said nothing: `brandProfile` stayed `PENDING`,
 * `error` stayed null, and every consumer kept rendering a waiting state for a
 * poll that had already been abandoned. The brand was never told.
 *
 * WHY THIS FAILS AGAINST THE PRE-FIX HOOK: `UseBrandProfileResult` had no
 * `analysisTimedOut` and no `restartAnalysisPoll`, so `result.current
 * .analysisTimedOut` is `undefined` — never `true` — no matter how far the
 * clock is advanced. That undefined IS the silence this ticket is about.
 *
 * TIMER NOTE: fake timers drive both react-query's poll and the hook's own
 * deadline timer, so the ~2 min budget is exercised without a 2 min test.
 *
 * Run: npx vitest run src/hooks/useBrandProfile.poll-budget.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import type { MeeraBrandProfile } from '@/lib/meera-api';

const PENDING_PROFILE: MeeraBrandProfile = {
  workspaceId: 'ws_1',
  websiteUrl: 'acme.in',
  analysisStatus: 'PENDING',
  nicheTags: null,
  productCatalog: null,
  analysisError: null,
};

/** Always PENDING — the stuck backend job the budget exists for. */
const getBrandProfile = vi.fn(async () => PENDING_PROFILE);

vi.mock('@/lib/meera-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/meera-api')>();
  return {
    ...actual,
    meeraApi: { ...actual.meeraApi, getBrandProfile: () => getBrandProfile() },
  };
});

import { useBrandProfile, ANALYSIS_POLL_BUDGET_MS } from './useBrandProfile';

// One client per test, created in beforeEach — NOT inside `wrapper`, which
// re-runs on every re-render of the hook and would hand back a fresh
// QueryClient (and so a fresh, empty cache) each time.
let client: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers();
  client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useBrandProfile — poll budget', () => {
  it('reports a terminal timeout once the budget runs out, instead of staying silently PENDING', async () => {
    const { result } = renderHook(() => useBrandProfile(), { wrapper });

    // First fetch settles: analysis in flight, budget running, nothing wrong yet.
    await act(async () => {
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(10);
    });
    expect(result.current.brandProfile?.analysisStatus).toBe('PENDING');
    expect(result.current.analysisTimedOut).toBe(false);
    expect(result.current.error).toBeNull();

    // Still inside the budget — we are genuinely still waiting, so no terminal
    // state yet. (Guards against a timeout that fires immediately.)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_BUDGET_MS / 2);
    });
    expect(result.current.analysisTimedOut).toBe(false);

    // Past the budget: we have stopped asking, and we say so.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_BUDGET_MS / 2 + 5_000);
    });
    expect(result.current.analysisTimedOut).toBe(true);
    // The profile is untouched — this is OUR give-up, not a fabricated status.
    expect(result.current.brandProfile?.analysisStatus).toBe('PENDING');
  });

  it('actually stops polling at the budget rather than just labelling it', async () => {
    const { result } = renderHook(() => useBrandProfile(), { wrapper });

    await act(async () => {
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_BUDGET_MS + 5_000);
    });
    expect(result.current.analysisTimedOut).toBe(true);

    const callsAtTimeout = getBrandProfile.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(getBrandProfile.mock.calls.length).toBe(callsAtTimeout);
  });

  it('restartAnalysisPoll clears the terminal state and buys a fresh budget', async () => {
    const { result } = renderHook(() => useBrandProfile(), { wrapper });

    await act(async () => {
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_BUDGET_MS + 5_000);
    });
    expect(result.current.analysisTimedOut).toBe(true);
    const callsAtTimeout = getBrandProfile.mock.calls.length;

    await act(async () => {
      result.current.restartAnalysisPoll();
      await vi.advanceTimersByTimeAsync(10);
    });
    // Asked again immediately, and no longer claiming to have given up.
    expect(getBrandProfile.mock.calls.length).toBeGreaterThan(callsAtTimeout);
    expect(result.current.analysisTimedOut).toBe(false);
  });
});
