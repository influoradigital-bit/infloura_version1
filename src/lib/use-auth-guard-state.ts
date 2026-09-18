import React from 'react';
import { api, isApiLive, type Role } from '@/lib/api';

// Moved out of src/App.tsx unchanged so that route wrappers living outside App (and their tests)
// can use the same session check without importing the entire route graph. App.tsx re-exports
// `readAuthToken` for the tests that already import it from there.

// F-0459 — "remember me" (see HttpClient.tokenStorage / setToken in src/lib/api.ts) puts the
// token in localStorage when checked but sessionStorage when unchecked; api.ts's own private
// getToken() already reads both, so the guards below do the same rather than checking
// localStorage alone and bouncing a just-logged-in, unremembered session back to the login form.
// Exported (in addition to the module-local usages below) solely so
// F-0459's regression test (src/__tests__/creator-protected-route.test.tsx)
// can exercise the exact function/component the router uses, rather than a
// reimplementation that could drift from production behavior.
export const readAuthToken = (key: string): string | null => localStorage.getItem(key) ?? sessionStorage.getItem(key);

const TOKEN_KEY_BY_ROLE: Record<Role, string> = { brand: 'brand_token', creator: 'creator_token' };

/**
 * F-0551 — access token in memory only (`HttpClient.getToken`/`setToken`, src/lib/api.ts). A
 * fresh page load starts with nothing in memory even for a genuinely still-logged-in visitor —
 * unlike the pre-F-0551 stored token, memory cannot itself prove a session survived a reload —
 * so the guards below can no longer answer synchronously from `Storage` the way F-0459's
 * `readAuthToken` did. This hook is what replaces that synchronous read in LIVE mode:
 *
 *   1. Check memory first (`api.auth.hasToken`) — the fast path for a same-tab navigation
 *      between two protected pages after login, or after an earlier bootstrap this page-load
 *      already resolved. No network call.
 *   2. Only when memory is empty, spend exactly one `POST /auth/refresh` (`api.auth.bootstrap`)
 *      to ask whether the HttpOnly refresh cookie still holds a valid session before concluding
 *      the visitor is logged out. This is the "silent refresh on page load" the ruling calls
 *      for — nothing pre-existing did this; the only other caller of `bootstrap` is the deal
 *      message stream's reactive 401 handler (src/lib/api.ts), which fires mid-session, not on
 *      load.
 *
 * Mock mode is untouched by F-0551 (see `HttpClient.getToken`'s own mode split) and keeps the
 * exact pre-existing synchronous, storage-based check (F-0459's `readAuthToken`) — no network
 * call, no `'checking'` state — so every mock-mode page, and every test that runs under the
 * default (mock) vitest config, keeps its exact prior behavior unchanged.
 */
export function useAuthGuardState(role: Role): 'checking' | 'authenticated' | 'unauthenticated' {
  const [state, setState] = React.useState<'checking' | 'authenticated' | 'unauthenticated'>(() => {
    if (!isApiLive()) {
      return readAuthToken(TOKEN_KEY_BY_ROLE[role]) ? 'authenticated' : 'unauthenticated';
    }
    return api.auth.hasToken(role) ? 'authenticated' : 'checking';
  });

  React.useEffect(() => {
    if (!isApiLive() || state !== 'checking') return;
    let cancelled = false;
    api.auth.bootstrap(role).then((recovered) => {
      if (!cancelled) setState(recovered ? 'authenticated' : 'unauthenticated');
    });
    return () => {
      cancelled = true;
    };
  }, [role, state]);

  return state;
}
