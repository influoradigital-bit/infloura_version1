/**
 * useDailySuggestion — Creator AI Co-pilot Tier-1 daily content suggestion
 * ----------------------------------------------------------------------------
 * Backed by `GET/POST /api/v1/creator/copilot/suggestion/today|:id/dismiss|:id/acted`
 * (`api.creatorCopilot.*`, `src/lib/api.ts`). Contract frozen v1 —
 * `wiki/build/creator-copilot-API-CONTRACT.md`; this hook's shape is
 * `wiki/build/creator-copilot-fe-datalayer-plan.md` §1 verbatim.
 *
 * Data-layer only — no components here (Ananya owns `DailySuggestionCard` /
 * `IGConnectPrompt` / `BusinessAccountRequired`, which consume this hook's return
 * shape 1:1). Uses `@tanstack/react-query` v5 (no `onError` on `useQuery` — v5
 * removed it — so the hook never toasts itself; it only exposes a stable
 * `error: string | null` and leaves toast-firing to the component, mirroring
 * `useEscrowFund.ts`'s hook/component boundary).
 *
 * IG connect is deliberately NOT surfaced here (no `connect()` method): "not connected
 * yet" collapses into the public `idle` status, and the component that renders `idle`
 * (Ananya's `IGConnectPrompt`) calls `api.metaOAuth.authorize()` directly — see
 * API-CONTRACT.md §5 / datalayer plan §1.5.
 *
 * F-0480 — `isConnected` used to be read ONLY from the localStorage mirror
 * (`api.metaOAuth.getLocalConnectionState()`), which is written by the OAuth callback page
 * in THIS browser and wiped by every logout (auth-session.ts F-0165). A creator who
 * connected Instagram on another device, or simply logged out and back in, therefore saw
 * the "Connect Instagram" prompt on Co-pilot forever — the backend had a live token, the
 * mirror said `connected: false`, and nothing here ever asked the backend. Settings already
 * re-verified against `GET /meta/oauth/status` (CR-107, `useMetaConnection`); Co-pilot did
 * not. The mirror is now only the synchronous first-paint seed; the backend answer (run
 * through the same `reconcileMetaConnectionStatus` rule as Settings, via react-query so the
 * page's two hook instances share one request) is what actually gates the suggestion.
 * While that first verification is in flight and the seed says "not connected", the hook
 * reports `loading` + `verifyingConnection: true` rather than `idle`, so a connected creator
 * never flashes the connect prompt before the answer lands.
 */

import { useCallback, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '@/lib/api';
import type { DailySuggestion, MetaConnectionState } from '@/lib/api';
import { reconcileMetaConnectionStatus } from '@/hooks/creator/useMetaConnection';

export type { DailySuggestion } from '@/lib/api';

/** UI-facing state machine (spec §3.4's literal 5 states). Distinct from the wire-level
 *  `CreatorCopilotWireStatus` ('pending_tagging' | 'ready' | 'no_suggestion_today') this hook
 *  derives it from — see the derivation table below. `acted` collapses into `dismissed`
 *  (API-CONTRACT.md §6 item 2, still open whether it deserves its own value; not a wire-shape
 *  question either way). */
export type SuggestionStatus = 'idle' | 'loading' | 'ready' | 'dismissed' | 'error';

export interface UseDailySuggestionResult {
  suggestion: DailySuggestion | null;
  status: SuggestionStatus;
  /** Only meaningful when `status === 'idle'`: true iff the last IG OAuth round-trip came back
   *  `accountType: 'personal'` (NO_BUSINESS_ACCOUNT, API-CONTRACT.md §4.2) — distinguishes
   *  "never connected" (renders `IGConnectPrompt`) from "connected but wrong account type"
   *  (renders `BusinessAccountRequired`) within the same `idle` status. */
  requiresBusinessAccount: boolean;
  /** F-0480 — true while the first backend `GET /meta/oauth/status` re-verification is still
   *  in flight AND the local seed says "not connected". `status` is `'loading'` in that window
   *  (never `'idle'`), so the component can show a neutral "checking your connection" row
   *  instead of either the connect prompt or the "usually ready within a day" copy. */
  verifyingConnection: boolean;
  /** Human-readable message for the `'error'` state. The hook never toasts this itself — the
   *  component's own `useEffect(() => { if (error) toast(...) }, [error])` does. */
  error: string | null;
  /** POST .../{id}/dismiss. Takes the id explicitly — the component already has
   *  `suggestion.id` when it calls this; the hook never looks up "the current suggestion"
   *  internally. Optimistic: the card collapses immediately, before the network resolves. */
  dismiss: (id: string) => Promise<void>;
  /** POST .../{id}/acted. Same shape as `dismiss`. */
  markActed: (id: string) => Promise<void>;
  /** Re-fetch after an `'error'` status — the inline-retry affordance (spec §3.4). */
  retry: () => void;
}

/** Creator-local calendar day (UTC-based `toISOString` slice, matches `expiresAt`'s
 *  end-of-UTC-day semantics, API-CONTRACT.md §2). Deliberately NOT memoized — a day boundary
 *  crossed while the tab stays open is picked up on next mount/render, not via a live timer
 *  (datalayer plan §1.2 / §5.7, accepted for a once-a-day card). */
function todayKey(): string {
  return new Date().toISOString().slice(0, 10);
}

export const dailySuggestionQueryKey = (day: string) => ['creator', 'copilot', 'suggestion', day] as const;
/** F-0480 — shared by every `useDailySuggestion` mount on a page (creator-copilot.tsx +
 *  DailySuggestionSection.tsx both call the hook), so the status re-verification is ONE
 *  request, not one per instance. */
export const metaConnectionStatusQueryKey = ['creator', 'meta', 'connection-status'] as const;

type CreatorSuggestionInteraction = 'dismissed' | 'acted';

/** sessionStorage marker, mirrors `metaOAuth`'s own localStorage-backed mirror
 *  (`META_CONNECTION_KEY`/`getLocalConnectionState`, `src/lib/api.ts`). Keyed per day+suggestion
 *  so a same-day refresh still shows the collapsed row without depending on the backend to
 *  filter dismissed/acted suggestions out of `GET .../today` — it deliberately does not
 *  (API-CONTRACT.md §1.1's "dismissed/acted suggestions still return here" ruling). */
function sessionInteractionKey(day: string, suggestionId: string): string {
  return `creator_copilot_interaction_${day}_${suggestionId}`;
}

function getSessionInteraction(day: string, suggestionId: string): CreatorSuggestionInteraction | null {
  try {
    const v = sessionStorage.getItem(sessionInteractionKey(day, suggestionId));
    return v === 'dismissed' || v === 'acted' ? v : null;
  } catch {
    return null;
  }
}

function setSessionInteraction(day: string, suggestionId: string, kind: CreatorSuggestionInteraction): void {
  try {
    sessionStorage.setItem(sessionInteractionKey(day, suggestionId), kind);
  } catch {
    // sessionStorage unavailable (private mode) — optimistic collapse just won't persist
    // locally; not fatal, the next successful GET still reflects server truth.
  }
}

function clearSessionInteraction(day: string, suggestionId: string): void {
  try {
    sessionStorage.removeItem(sessionInteractionKey(day, suggestionId));
  } catch {
    // no-op, see getSessionInteraction
  }
}

export function useDailySuggestion(): UseDailySuggestionResult {
  const queryClient = useQueryClient();
  const day = todayKey();

  // F-0480 — the backend is the source of truth for "is Instagram connected"; the localStorage
  // mirror is only the synchronous seed for first paint (and the fallback if the status call
  // fails, matching useMetaConnection's "keep last-known state on a network hiccup" rule).
  // `reconcileMetaConnectionStatus` also writes the verified answer back into the mirror, so
  // the next mount anywhere in the app seeds from truth.
  const statusQuery = useQuery({
    queryKey: metaConnectionStatusQueryKey,
    queryFn: async () => reconcileMetaConnectionStatus(await api.metaOAuth.status()),
    staleTime: 30_000,
    retry: 1,
  });
  const localSeed = api.metaOAuth.getLocalConnectionState();
  const connectionState: MetaConnectionState = statusQuery.data ?? localSeed;
  const isConnected = connectionState.connected;
  // Only the very first verification (no data yet) can leave us not knowing; an error falls
  // back to the seed and is NOT "verifying" — otherwise a creator with the backend down would
  // spin forever instead of seeing the connect prompt / last-known state.
  const verifyingConnection = statusQuery.isPending && !isConnected;
  // A personal IG account never completes a usable "connected" transition (spec §3.3) — this
  // stays a sub-branch of `idle`, not a 6th status value (API-CONTRACT.md §4.2 / datalayer plan §1.3).
  const requiresBusinessAccount = !connectionState.connected && connectionState.accountType === 'personal';

  const query = useQuery({
    queryKey: dailySuggestionQueryKey(day),
    queryFn: () => api.creatorCopilot.getTodaySuggestion(),
    enabled: isConnected, // don't fetch until IG is linked
    staleTime: Infinity, // per-day cache; a new day is simply a cache miss (new query key)
    retry: 1,
  });

  const interactionMutation = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: CreatorSuggestionInteraction }) =>
      kind === 'dismissed' ? api.creatorCopilot.dismissSuggestion(id) : api.creatorCopilot.markSuggestionActed(id),
    onMutate: ({ id, kind }) => {
      // Optimistic: flip the local flag immediately, card collapses without waiting on the network.
      setSessionInteraction(day, id, kind);
    },
    onError: (_err, { id }) => {
      // Roll back — the component sees 'ready' again, and `error` is set so it can toast.
      clearSessionInteraction(day, id);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: dailySuggestionQueryKey(day) });
    },
  });

  const suggestion = query.data?.suggestion ?? null;
  const localInteraction = suggestion ? getSessionInteraction(day, suggestion.id) : null;

  const status: SuggestionStatus = useMemo(() => {
    if (verifyingConnection) return 'loading';
    if (!isConnected) return 'idle';
    if (query.isError) return 'error';
    if (query.isLoading || !query.data) return 'loading';
    if (query.data.status === 'pending_tagging') return 'loading';
    if (query.data.status === 'no_suggestion_today') return 'dismissed';
    // query.data.status === 'ready'
    return localInteraction ? 'dismissed' : 'ready';
  }, [verifyingConnection, isConnected, query.isError, query.isLoading, query.data, localInteraction]);

  const error = useMemo(() => {
    if (!query.isError) return null;
    const err = query.error;
    return err instanceof ApiError ? err.message : 'Could not load your suggestion.';
  }, [query.isError, query.error]);

  const dismiss = useCallback(
    (id: string) => interactionMutation.mutateAsync({ id, kind: 'dismissed' }),
    [interactionMutation],
  );

  const markActed = useCallback(
    (id: string) => interactionMutation.mutateAsync({ id, kind: 'acted' }),
    [interactionMutation],
  );

  const retry = useCallback(() => {
    void query.refetch();
  }, [query]);

  return useMemo(
    () => ({
      suggestion,
      status,
      requiresBusinessAccount,
      verifyingConnection,
      error,
      dismiss,
      markActed,
      retry,
    }),
    [suggestion, status, requiresBusinessAccount, verifyingConnection, error, dismiss, markActed, retry],
  );
}

export default useDailySuggestion;
