/**
 * useCreatorChallenge — creator 7-day challenge data layer (CHALLENGE-SPEC.md, 2026-09-23,
 * Frontend §9): start -> refetch, and a 409 is exposed as a plain `ApiError` rather than
 * thrown uncaught or silently swallowed.
 *
 * Run: npx vitest run src/hooks/useCreatorChallenge.test.ts
 */

import * as React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useCreatorChallenge } from './useCreatorChallenge';
import { api, ApiError } from '@/lib/api';
import type { ChallengeState } from '@/lib/api';

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

const STATE_NO_CHALLENGE: ChallengeState = {
  instagramConnected: true,
  active: null,
  lastCompleted: null,
  comparison: {
    thisWeek: { from: '2026-09-16', to: '2026-09-22', posts: 0, settledPosts: 0, reach: 0, engagementRate: '0%' },
    lastWeek: { from: '2026-09-09', to: '2026-09-15', posts: 0, settledPosts: 0, reach: 0, engagementRate: '0%' },
    reachChangePercent: null,
    engagementChangePoints: null,
    enoughToCompare: false,
    note: 'Not enough settled posts in both weeks to compare yet.',
  },
};

const STATE_ACTIVE: ChallengeState = {
  ...STATE_NO_CHALLENGE,
  active: {
    id: '01J_NEW',
    startedOn: '2026-09-23',
    dayNumber: 1,
    streak: 0,
    days: [],
  },
};

describe('useCreatorChallenge', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads the challenge state via GET on mount', async () => {
    vi.spyOn(api.creatorChallenge, 'get').mockResolvedValue(STATE_NO_CHALLENGE);

    const { result } = renderHook(() => useCreatorChallenge(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.data).toEqual(STATE_NO_CHALLENGE);
  });

  it('start() -> refetch: a successful POST invalidates the GET, and the new state lands', async () => {
    const getSpy = vi
      .spyOn(api.creatorChallenge, 'get')
      .mockResolvedValueOnce(STATE_NO_CHALLENGE)
      .mockResolvedValueOnce(STATE_ACTIVE);
    vi.spyOn(api.creatorChallenge, 'start').mockResolvedValue(STATE_ACTIVE);

    const { result } = renderHook(() => useCreatorChallenge(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.data?.active).toBeNull();

    await result.current.start();

    // The refetch this hook triggers on a successful start — not the mutation's own response
    // trusted forever — is what the GET spy's second resolved value proves landed.
    await waitFor(() => expect(getSpy).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.data?.active?.id).toBe('01J_NEW'));
  });

  it('a 409 CHALLENGE_ALREADY_ACTIVE from start() is exposed as startError, not thrown to the caller unhandled', async () => {
    vi.spyOn(api.creatorChallenge, 'get').mockResolvedValue(STATE_NO_CHALLENGE);
    vi.spyOn(api.creatorChallenge, 'start').mockRejectedValue(
      new ApiError('CHALLENGE_ALREADY_ACTIVE', 'A challenge is already active', 409),
    );

    const { result } = renderHook(() => useCreatorChallenge(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));

    // react-query's mutateAsync re-throws — callers that don't want that must catch, which
    // ChallengeCard.tsx does not need to since it reads `startError` instead of awaiting a throw.
    await expect(result.current.start()).rejects.toThrow();

    await waitFor(() => expect(result.current.startError).toBeInstanceOf(ApiError));
    expect((result.current.startError as ApiError).code).toBe('CHALLENGE_ALREADY_ACTIVE');
  });

  it('end() also refetches on success', async () => {
    const getSpy = vi
      .spyOn(api.creatorChallenge, 'get')
      .mockResolvedValueOnce(STATE_ACTIVE)
      .mockResolvedValueOnce(STATE_NO_CHALLENGE);
    vi.spyOn(api.creatorChallenge, 'end').mockResolvedValue(STATE_NO_CHALLENGE);

    const { result } = renderHook(() => useCreatorChallenge(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.data?.active).not.toBeNull();

    await result.current.end('01J_NEW');

    await waitFor(() => expect(getSpy).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.data?.active).toBeNull());
  });
});
