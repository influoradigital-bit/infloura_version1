/**
 * Admin session renewal — the behaviour, not the wiring.
 *
 * WHY THIS FILE EXISTS: the bug it guards was invisible to every static gate. `tsc` was clean,
 * `eslint` was clean, the FE<->BE contract gate saw `apiRequest('/auth/refresh')` declared and the
 * backend endpoint present, and 177 admin tests passed — all while NOTHING in the client ever
 * called the refresh. `authApi.refreshToken()` had zero call sites and `useAdminAuth` deleted the
 * access token the moment `exp` passed, so an admin session died unrecoverably at
 * `JWT_ACCESS_EXPIRY` (900s) even though the HttpOnly refresh cookie was good for 30 days.
 *
 * A test that only asserts "the function exists" would have passed against the broken code too.
 * These assert the observable consequence instead: a second `fetch` to `/auth/refresh` happens,
 * and the retried request carries the NEW bearer.
 */

import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../services/api-contracts';
import {
  ADMIN_TOKEN_KEY,
  getAdminToken,
  isTokenExpired,
  isTokenWellFormed,
} from '../services/admin-session';

/** Builds a syntactically real JWT whose payload carries the given `exp` (seconds since epoch). */
function jwtExpiringAt(expSeconds: number, marker = 'x'): string {
  const encode = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({
    sub: 'admin-1',
    userType: 'ADMIN',
    exp: expSeconds,
    marker,
  })}.sig`;
}

const nowSeconds = () => Math.floor(Date.now() / 1000);

/** Well past expiry. */
const EXPIRED = jwtExpiringAt(nowSeconds() - 600, 'expired');
/** Comfortably inside the 60s pre-expiry guard band, so no proactive refresh should fire. */
const FRESH = jwtExpiringAt(nowSeconds() + 3600, 'fresh');
/** What the server hands back from a successful rotation. */
const ROTATED = jwtExpiringAt(nowSeconds() + 900, 'rotated');

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

/** The `Authorization` bearer on a recorded `fetch` call, or null. */
function bearerOf(call: [string, RequestInit]): string | null {
  const headers = (call[1]?.headers ?? {}) as Record<string, string>;
  const auth = headers.Authorization;
  return typeof auth === 'string' ? auth.replace(/^Bearer /, '') : null;
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  localStorage.clear();
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('admin-session token predicates', () => {
  it('separates "expired" from "malformed" — the conflation that caused the 15-minute wall', () => {
    // The old `isTokenValid` returned false for BOTH, and the caller deleted the token on false.
    // An expired token is refreshable; a malformed one is not the same thing, and the distinction
    // is what lets `useAdminAuth` try the cookie instead of ending the session.
    expect(isTokenWellFormed(EXPIRED)).toBe(true);
    expect(isTokenExpired(EXPIRED)).toBe(true);

    expect(isTokenWellFormed(FRESH)).toBe(true);
    expect(isTokenExpired(FRESH)).toBe(false);

    expect(isTokenWellFormed('not-a-jwt')).toBe(false);
    expect(isTokenWellFormed('a.b')).toBe(false);
  });
});

describe('apiRequest — proactive renewal', () => {
  it('refreshes BEFORE spending an expired token, and sends the rotated one', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { token: ROTATED })) // /auth/refresh
      .mockResolvedValueOnce(jsonResponse(200, { id: 'admin-1' })); // /auth/me

    localStorage.setItem(ADMIN_TOKEN_KEY, EXPIRED);

    const res = await authApi.getCurrentUser();

    expect(res.success).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [refreshCall, meCall] = fetchMock.mock.calls as [string, RequestInit][][] as unknown as [
      [string, RequestInit],
      [string, RequestInit],
    ];

    expect(refreshCall[0]).toBe('/api/v1/admin/auth/refresh');
    expect(refreshCall[1].method).toBe('POST');
    // The cookie is HttpOnly + SameSite=Strict and path-scoped to /api/v1/admin/auth; without
    // credentials on a same-origin request it would never be attached and refresh would 401.
    expect(refreshCall[1].credentials).toBe('same-origin');

    expect(meCall[0]).toBe('/api/v1/admin/auth/me');
    expect(bearerOf(meCall)).toBe(ROTATED);
    expect(getAdminToken()).toBe(ROTATED);
  });

  it('does NOT refresh when the token is still comfortably valid', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { id: 'admin-1' }));
    localStorage.setItem(ADMIN_TOKEN_KEY, FRESH);

    await authApi.getCurrentUser();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(bearerOf(fetchMock.mock.calls[0] as [string, RequestInit])).toBe(FRESH);
  });
});

describe('apiRequest — reactive 401 retry', () => {
  it('refreshes and retries once when the server rejects a token that looked fresh', async () => {
    // Covers what the proactive check cannot see: revoked server-side, role changed, or expiring
    // mid-flight.
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { error: { message: 'Unauthorized' } }))
      .mockResolvedValueOnce(jsonResponse(200, { token: ROTATED }))
      .mockResolvedValueOnce(jsonResponse(200, { id: 'admin-1' }));

    localStorage.setItem(ADMIN_TOKEN_KEY, FRESH);

    const res = await authApi.getCurrentUser();

    expect(res.success).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect((fetchMock.mock.calls[1] as [string, RequestInit])[0]).toBe(
      '/api/v1/admin/auth/refresh'
    );
    expect(bearerOf(fetchMock.mock.calls[2] as [string, RequestInit])).toBe(ROTATED);
  });

  it('clears the token and stops when the refresh itself fails — no retry loop', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { error: { message: 'Unauthorized' } }))
      .mockResolvedValueOnce(jsonResponse(401, { error: { message: 'Refresh token is invalid' } }));

    localStorage.setItem(ADMIN_TOKEN_KEY, FRESH);

    const res = await authApi.getCurrentUser();

    expect(res.success).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(getAdminToken()).toBeNull();
  });

  it('never refresh-retries a failed login — a wrong password must stay a wrong password', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(401, { error: { message: 'Invalid credentials' } })
    );

    const res = await authApi.login({ email: 'a@b.com', password: 'wrong' });

    expect(res.success).toBe(false);
    expect(res.error).toBe('Invalid credentials');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('apiRequest — rotation safety', () => {
  it('collapses concurrent refreshes into ONE call', async () => {
    // AdminAuthService#refresh REVOKES the presented token before minting the next. The dashboard
    // fans out ~12 hooks on load; without the shared in-flight promise the first refresh would
    // succeed and the rest would present an already-burned token, 401, and sign the admin out.
    let refreshCalls = 0;
    fetchMock.mockImplementation((url: string) => {
      if (url.endsWith('/auth/refresh')) {
        refreshCalls += 1;
        return Promise.resolve(jsonResponse(200, { token: ROTATED }));
      }
      return Promise.resolve(jsonResponse(200, { id: 'admin-1' }));
    });

    localStorage.setItem(ADMIN_TOKEN_KEY, EXPIRED);

    await Promise.all([
      authApi.getCurrentUser(),
      authApi.getCurrentUser(),
      authApi.getCurrentUser(),
      authApi.getCurrentUser(),
    ]);

    expect(refreshCalls).toBe(1);
    expect(getAdminToken()).toBe(ROTATED);
  });
});
