/**
 * F-0551 (CEO ruling, 2026-09-05) — access token in memory only; the refresh token stays exactly
 * where it already was, an HttpOnly cookie this SPA never reads (Kabir A1, unchanged).
 *
 * Direct regression test for the ruling's three required guarantees:
 *   1. A cold load with no stored access token recovers a session via refresh (`bootstrap`).
 *   2. Logout leaves nothing recoverable in any store.
 *   3. No access token is written to localStorage or sessionStorage by any path — including
 *      `persistBrandSession` (src/lib/auth-session.ts), which is the ONLY place a brand's real
 *      access token ever reaches the client at all (brand login/register never calls
 *      `api.auth.setToken` the way creator login/register explicitly does).
 *
 * Must run under vitest.live.config.ts (VITE_API_MODE=live). Mock mode's HttpClient branch is
 * deliberately untouched by F-0551 (see `HttpClient.getToken`'s own doc in src/lib/api.ts) —
 * running these assertions under the default (mock) config would pass every one of them
 * vacuously, proving nothing about the code path this ticket actually changed.
 *
 * FALSIFICATION: reverting src/lib/api.ts's `getToken`/`setToken`/`clearToken` and
 * src/lib/auth-session.ts's `persistBrandSession` to their pre-F-0551 form (token read/written
 * straight to `Storage`, no memory slot) turns every "not written to Storage" assertion red —
 * `localStorage.getItem`/`sessionStorage.getItem` then return the real token literally — and the
 * cold-load-recovers-a-session test also goes red for a different reason: `getMemoryAccessToken`
 * would not exist for `bootstrap`'s `setToken` call to populate, so the recovered token would
 * never be readable back out via the memory-only accessor this test calls.
 *
 * Run: npx vitest run --config vitest.live.config.ts src/lib/__tests__/f0551-access-token-memory-only.live.test.ts
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Deliberately dot-free so decodeJwtClaims (3-dot-part JWT check) treats it as a non-JWT mock-
 *  shaped string and never attempts a proactive refresh on it — these tests are about where the
 *  token lands, not the separate near-expiry-refresh behavior already covered by
 *  token-refresh.live.test.ts. */
const REAL_ACCESS_TOKEN = 'super-secret-real-bearer-credential-value';

describe('F-0551 — access token in memory only (live mode)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let api: typeof import('@/lib/api').api;
  let authSession: typeof import('@/lib/auth-session');

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    ({ api } = await import('@/lib/api'));
    authSession = await import('@/lib/auth-session');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** Every value currently sitting in either web store, across every key. */
  function everyStoredValue(): string[] {
    const values: string[] = [];
    for (const store of [localStorage, sessionStorage]) {
      for (const key of Object.keys(store)) {
        const v = store.getItem(key);
        if (v != null) values.push(v);
      }
    }
    return values;
  }

  it('setToken (creator, remembered) never writes the real access token to any Storage', () => {
    api.auth.setToken('creator', REAL_ACCESS_TOKEN, true);

    expect(api.auth.hasToken('creator')).toBe(true);
    expect(everyStoredValue()).not.toContain(REAL_ACCESS_TOKEN);
  });

  it('setToken (brand, remember=false / session-only) never writes the real access token to any Storage', () => {
    api.auth.setToken('brand', REAL_ACCESS_TOKEN, false);

    expect(api.auth.hasToken('brand')).toBe(true);
    expect(everyStoredValue()).not.toContain(REAL_ACCESS_TOKEN);
  });

  it('persistBrandSession — the login/register path that never calls setToken at all — also never writes the real access token to any Storage', () => {
    authSession.persistBrandSession({
      user: { id: 'brand_1', email: 'brand@example.com' },
      accessToken: REAL_ACCESS_TOKEN,
      onboardingCompleted: true,
    });

    expect(authSession.getMemoryAccessToken('brand')).toBe(REAL_ACCESS_TOKEN);
    expect(everyStoredValue()).not.toContain(REAL_ACCESS_TOKEN);
  });

  it('a cold load (nothing in memory, simulating a fresh page) recovers a session via bootstrap(), and the recovered token still never touches Storage', async () => {
    // Genuinely nothing yet — this IS the cold-load state, not merely an empty Storage.
    expect(api.auth.hasToken('creator')).toBe(false);

    fetchMock.mockImplementation((url: string) => {
      expect(String(url)).toContain('/auth/refresh');
      return Promise.resolve(
        jsonResponse({ success: true, data: { accessToken: REAL_ACCESS_TOKEN, expiresIn: 900 } }),
      );
    });

    const recovered = await api.auth.bootstrap('creator');

    expect(recovered).toBe(true);
    expect(api.auth.hasToken('creator')).toBe(true);
    expect(everyStoredValue()).not.toContain(REAL_ACCESS_TOKEN);
  });

  it('a cold load with no valid refresh cookie fails closed — bootstrap resolves false, nothing recovered', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        jsonResponse({ success: false, error: { code: 'INVALID_REFRESH_TOKEN', message: 'missing' } }, 401),
      ),
    );

    const recovered = await api.auth.bootstrap('creator');

    expect(recovered).toBe(false);
    expect(api.auth.hasToken('creator')).toBe(false);
  });

  it('logout leaves nothing recoverable — not in memory, not in either store', async () => {
    api.auth.setToken('creator', REAL_ACCESS_TOKEN, true);
    expect(api.auth.hasToken('creator')).toBe(true);

    fetchMock.mockImplementation(() =>
      Promise.resolve(jsonResponse({ success: true, data: { message: 'Logged out successfully' } })),
    );
    await api.auth.logout('creator');

    expect(api.auth.hasToken('creator')).toBe(false);
    expect(everyStoredValue()).not.toContain(REAL_ACCESS_TOKEN);
    expect(localStorage.getItem('creator_token')).toBeNull();
    expect(sessionStorage.getItem('creator_token')).toBeNull();
  });

  it('clearCreatorSession alone (no logout API call) also clears the in-memory token — both real call sites gate api.auth.logout behind isApiLive() (F-0459 residual), so this must not depend on that call happening', () => {
    api.auth.setToken('creator', REAL_ACCESS_TOKEN, true);
    expect(api.auth.hasToken('creator')).toBe(true);

    authSession.clearCreatorSession();

    expect(api.auth.hasToken('creator')).toBe(false);
  });
});
