/**
 * useCreatorChallenge — data layer for the creator 7-day challenge
 * (CHALLENGE-SPEC.md, 2026-09-23, Frontend §2). Backed by `GET/POST /creator/challenge`
 * and `POST /creator/challenge/:id/end` (`api.creatorChallenge.*`, `src/lib/api.ts`).
 *
 * Follows the same hook/component split as `useDailySuggestion.ts`: this hook never
 * toasts — it only exposes plain `error`/`startError`/`endError` strings and leaves
 * rendering (including the 409 "already active" / "not connected" messages) to
 * `ChallengeCard.tsx`. Uses `@tanstack/react-query` v5 (no `onError` on `useQuery`).
 *
 * `start`/`end` invalidate the GET query on success rather than trusting the mutation's
 * own response to be the new canonical state forever — the very next render already
 * reflects a real refetch, matching the spec's "start -> refetch" requirement, and
 * keeps one code path (the GET) as the single source of the rendered `ChallengeState`.
 */

import { useCallback, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '@/lib/api';
import type { ChallengeState } from '@/lib/api';

export const creatorChallengeQueryKey = ['creator', 'challenge'] as const;

export type CreatorChallengeStatus = 'loading' | 'ready' | 'error';

export interface UseCreatorChallengeResult {
  data: ChallengeState | null;
  status: CreatorChallengeStatus;
  /** Error from the GET itself. */
  error: string | null;
  /** True while POST /creator/challenge is in flight. */
  starting: boolean;
  /** True while POST /creator/challenge/:id/end is in flight. */
  ending: boolean;
  /** Error from the last start attempt (e.g. 409 CHALLENGE_ALREADY_ACTIVE /
   *  INSTAGRAM_NOT_CONNECTED), kept separate from `error` so a failed start never blanks
   *  out an already-loaded challenge card. Carries the raw `ApiError` so the component can
   *  match on `.code` for the plain, specific message rather than a generic one. */
  startError: ApiError | Error | null;
  endError: ApiError | Error | null;
  start: () => Promise<void>;
  end: (id: string) => Promise<void>;
  retry: () => void;
}

export function useCreatorChallenge(): UseCreatorChallengeResult {
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: creatorChallengeQueryKey,
    queryFn: () => api.creatorChallenge.get(),
    staleTime: 60_000,
    retry: 1,
  });

  const startMutation = useMutation({
    mutationFn: () => api.creatorChallenge.start(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: creatorChallengeQueryKey });
    },
  });

  const endMutation = useMutation({
    mutationFn: (id: string) => api.creatorChallenge.end(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: creatorChallengeQueryKey });
    },
  });

  const status: CreatorChallengeStatus = useMemo(() => {
    if (query.isError) return 'error';
    if (query.isLoading || !query.data) return 'loading';
    return 'ready';
  }, [query.isError, query.isLoading, query.data]);

  const error = useMemo(() => {
    if (!query.isError) return null;
    const err = query.error;
    return err instanceof ApiError ? err.message : "Couldn't load your challenge.";
  }, [query.isError, query.error]);

  const start = useCallback(async () => {
    await startMutation.mutateAsync();
  }, [startMutation]);

  const end = useCallback(
    async (id: string) => {
      await endMutation.mutateAsync(id);
    },
    [endMutation],
  );

  const retry = useCallback(() => {
    void query.refetch();
  }, [query]);

  return useMemo(
    () => ({
      data: query.data ?? null,
      status,
      error,
      starting: startMutation.isPending,
      ending: endMutation.isPending,
      startError: startMutation.error ?? null,
      endError: endMutation.error ?? null,
      start,
      end,
      retry,
    }),
    [
      query.data,
      status,
      error,
      startMutation.isPending,
      startMutation.error,
      endMutation.isPending,
      endMutation.error,
      start,
      end,
      retry,
    ],
  );
}

export default useCreatorChallenge;
