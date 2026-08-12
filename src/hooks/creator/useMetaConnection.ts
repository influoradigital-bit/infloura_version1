/**
 * useMetaConnection — creator Meta (Instagram/Facebook) OAuth connection status
 * ----------------------------------------------------------------------------
 * CR-107 fix: `connected-accounts.tsx` used to seed connection state once from
 * the `meta_connection` localStorage mirror (`api.ts` `metaOAuth
 * .getLocalConnectionState`) via a bare `useState` initializer and never
 * re-verified it. Revoking access, clearing storage, or token expiry left the
 * UI showing stale "connected" state indefinitely.
 *
 * This hook re-verifies against the real `GET /meta/oauth/status` endpoint
 * (MetaOAuthController.java:116, CR-106) on mount and whenever the tab regains
 * visibility — the same `visibilitychange` refresh pattern already used by
 * creator-chat.tsx/brand-chat.tsx for "did this go stale in another tab" — then
 * writes the confirmed truth back into the localStorage mirror via
 * `setLocalConnectionState` so it stops drifting from the backend.
 *
 * Same `{ data, loading, error, refresh }` shape as the other async-data hooks
 * in this codebase (see useStoreIntegration.ts / useCreatorMetrics.ts).
 *
 * `data` is seeded synchronously from localStorage so first paint isn't blank —
 * callers should treat `loading === true` as "unverified" and avoid flashing a
 * hard "not connected" state off a stale-but-connected seed until it settles.
 */

import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, type MetaConnectionState } from '@/lib/api';

export interface UseMetaConnectionResult {
  data: MetaConnectionState;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export function useMetaConnection(): UseMetaConnectionResult {
  const [data, setData] = useState<MetaConnectionState>(() => api.metaOAuth.getLocalConnectionState());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const status = await api.metaOAuth.status();
      // accountType isn't part of the status response (only resolved during the OAuth
      // callback) — keep whatever the last callback recorded while still connected, and
      // drop it once the backend says disconnected so a stale "business"/"personal" label
      // can't survive a revoke.
      const accountType = status.connected ? api.metaOAuth.getLocalConnectionState().accountType : null;
      // CR-105-followup — GET /meta/oauth/status reports `connected: true` on the mere
      // existence of a non-revoked token row (MetaConnectionService.getStatus), regardless
      // of account type: CreatorMetaOAuthService.connect() stores the token for a personal
      // account too, deliberately, before branching on business/personal. But
      // useDailySuggestion's `requiresBusinessAccount` is `!connectionState.connected &&
      // accountType === 'personal'` — so reconciling straight from `status.connected` here
      // silently flips a personal-account creator BACK to "connected" on the very next
      // status re-verification (mount, or any tab-focus), undoing what the OAuth callback
      // correctly set to `connected: false` moments earlier and re-trapping them in exactly
      // the loop CR-105 exists to close. A personal account is not a usable connection for
      // this app regardless of what the token-existence check reports.
      const usableConnected = accountType === 'personal' ? false : status.connected;
      const reconciled: MetaConnectionState = {
        connected: usableConnected,
        scopes: status.grantedScopes,
        accountType,
      };
      api.metaOAuth.setLocalConnectionState(reconciled.connected, reconciled.scopes, reconciled.accountType);
      setData(reconciled);
    } catch (err) {
      // Leave `data` as the last-known (localStorage-seeded or previously-verified) state —
      // a network hiccup shouldn't yank a connected creator's UI to "disconnected".
      setError(err instanceof ApiError ? err.message : 'Failed to verify connection status');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      void refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [refresh]);

  return { data, loading, error, refresh };
}

export default useMetaConnection;
