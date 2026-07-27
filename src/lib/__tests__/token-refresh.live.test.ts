/**
 * Silent session renewal.
 *
 * The bug this pins down (2026-07-26): users were forced to re-login every time the access token
 * aged out, while a 30-day refresh cookie sat unused. Two independent faults combined —
 *
 *   1. SecurityConfig had no `exceptionHandling`, so Spring fell back to
 *      Http403ForbiddenEntryPoint and answered UNAUTHENTICATED requests with 403 + an empty body.
 *   2. The client's refresh-and-retry interceptor keys on 401, so a 403 sailed past it and no
 *      refresh was ever attempted. The empty body then surfaced as
 *      "Unexpected non-JSON response from the server (HTTP 403)".
 *
 * The backend now returns 401 for unauthenticated. The client additionally refreshes BEFORE
 * expiry so renewal no longer depends on how the server phrases a rejection at all.
 *
 * Run: npx vitest run src/lib/__tests__/token-refresh.test.ts
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/** Minimal unsigned JWT with the given `exp` — only the payload is ever read client-side. */
function jwtExpiringIn(seconds: number): string {
  const payload = { sub: 'u_1', exp: Math.floor(Date.now() / 1000) + seconds };
  const b64 = btoa(JSON.stringify(payload)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `header.${b64}.signature`;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const REFRESHED = 'refreshed.token.value';

describe('access-token renewal', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let api: typeof import('@/lib/api').api;

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    // Live mode comes from vitest.live.config.ts — `vi.stubEnv` cannot set it, because the base
    // config pins VITE_API_MODE through a transform-time `define`.
    ({ api } = await import('@/lib/api'));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** Routes /auth/refresh to a rotated token and everything else to `rest`. */
  function route(rest: () => Response) {
    fetchMock.mockImplementation((url: string) => {
      if (String(url).includes('/auth/refresh')) {
        return Promise.resolve(jsonResponse({ success: true, data: { accessToken: REFRESHED, expiresIn: 900 } }));
      }
      return Promise.resolve(rest());
    });
  }

  it('renews a near-expiry token BEFORE the request and sends the new one', async () => {
    api.auth.setToken('creator', jwtExpiringIn(30)); // inside the 60s skew window
    route(() => jsonResponse({ success: true, data: [] }));

    await api.creatorCampaigns.browse();

    const refreshCalls = fetchMock.mock.calls.filter(([u]) => String(u).includes('/auth/refresh'));
    expect(refreshCalls).toHaveLength(1);

    // The campaigns request must carry the ROTATED token, not the stale one.
    const dataCall = fetchMock.mock.calls.find(([u]) => String(u).includes('/creator/campaigns'));
    expect(dataCall).toBeTruthy();
    expect((dataCall![1] as RequestInit & { headers: Record<string, string> }).headers.Authorization).toBe(
      `Bearer ${REFRESHED}`,
    );
    expect(localStorage.getItem('creator_token')).toBe(REFRESHED);
  });

  it('does not refresh a token that is comfortably valid', async () => {
    api.auth.setToken('creator', jwtExpiringIn(600));
    route(() => jsonResponse({ success: true, data: [] }));

    await api.creatorCampaigns.browse();

    expect(fetchMock.mock.calls.filter(([u]) => String(u).includes('/auth/refresh'))).toHaveLength(0);
  });

  it('still recovers reactively when the server rejects with 401', async () => {
    // Token looks fresh to the client (e.g. clock skew, or revoked server-side), so only the
    // reactive path can save this request.
    api.auth.setToken('creator', jwtExpiringIn(600));
    let served = 0;
    route(() => {
      served += 1;
      return served === 1
        ? jsonResponse({ success: false, error: { code: 'UNAUTHENTICATED', message: 'expired' } }, 401)
        : jsonResponse({ success: true, data: [] });
    });

    await api.creatorCampaigns.browse();

    expect(fetchMock.mock.calls.filter(([u]) => String(u).includes('/auth/refresh'))).toHaveLength(1);
    expect(served).toBe(2); // original + retry
  });

  it('does NOT refresh-retry a 403, which means "not permitted" and never resolves by refreshing', async () => {
    api.auth.setToken('creator', jwtExpiringIn(600));
    route(() =>
      jsonResponse({ success: false, error: { code: 'FORBIDDEN', message: 'no permission' } }, 403),
    );

    await expect(api.creatorCampaigns.browse()).rejects.toMatchObject({ code: 'FORBIDDEN' });

    // Retrying a role-gate rejection would loop forever on a request the server is correctly
    // refusing — and would re-introduce the confusion this whole fix removes.
    expect(fetchMock.mock.calls.filter(([u]) => String(u).includes('/auth/refresh'))).toHaveLength(0);
  });

  it('leaves non-JWT mock-mode tokens alone instead of trying to refresh them', async () => {
    api.auth.setToken('creator', 'mock_creator_token');
    route(() => jsonResponse({ success: true, data: [] }));

    await api.creatorCampaigns.browse();

    expect(fetchMock.mock.calls.filter(([u]) => String(u).includes('/auth/refresh'))).toHaveLength(0);
  });
});
