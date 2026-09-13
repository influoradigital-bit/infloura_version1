import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Guard for the defect that made EVERY creator Instagram connect fail, in both the
 * FACEBOOK_LOGIN and INSTAGRAM_LOGIN branches, for as long as the flow has existed.
 *
 * Measured on production 2026-09-13 — three requests fired on this page's mount and all three
 * 401'd, with no POST /auth/refresh anywhere near them:
 *
 *   15:50:23  GET /api/v1/me/creator-profile          401
 *   15:50:23  GET /api/v1/deals?status=all            401
 *   15:50:23  GET /api/v1/meta/oauth/callback?code=…  401
 *
 * Three things compound to produce that, and no single file shows it:
 *   1. F-0551 made the access token memory-only in live mode; Meta's redirect is a top-level
 *      navigation, so the app reloads and that memory is empty.
 *   2. fetchWithAuthRetry gates refresh-and-replay on `hasAuthHeader` (api.ts:647) — with no
 *      token there is no header, so the 401 comes back untouched.
 *   3. This route is deliberately unguarded (App.tsx:541) so the auth guard cannot bounce a
 *      mid-connect creator to /creator/login — which also means the guard's cold-load
 *      `api.auth.bootstrap` never runs here.
 *
 * The fix restores the session explicitly before the exchange. These tests pin the ORDER, not
 * merely that bootstrap is called: Meta's `code` is single-use, so an exchange that goes out
 * before the session is restored burns the one chance there is. A test that only asserted
 * "bootstrap was called" would stay green if the two calls were swapped.
 *
 * Separate file from creator-meta-callback.test.tsx because that suite's `@/lib/api` mock has no
 * `api.auth`, and these tests need to drive `isApiLive`/`hasToken`/`bootstrap` directly.
 */

const calls: string[] = [];

const metaCallback = vi.fn(async () => {
  calls.push('metaOAuth.callback');
  return { connected: true, grantedScopes: [], accountType: 'business' as const };
});
const bootstrap = vi.fn(async () => {
  calls.push('auth.bootstrap');
  return true;
});
const hasToken = vi.fn(() => false);
const isApiLiveMock = vi.fn(() => true);

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => isApiLiveMock(),
    api: {
      auth: {
        hasToken: (...a: unknown[]) => hasToken(...(a as [])),
        bootstrap: (...a: unknown[]) => bootstrap(...(a as [])),
      },
      metaOAuth: {
        callback: (...a: unknown[]) => metaCallback(...(a as [])),
        authorize: vi.fn(),
        setLocalConnectionState: vi.fn(),
        consumeConnectReturnTo: () => null,
        setConnectReturnTo: vi.fn(),
      },
    },
  };
});

import CreatorMetaCallbackPage from './creator-meta-callback';

function renderAt(query: string) {
  window.history.pushState({}, '', `/creator/settings/meta/callback${query}`);
  return render(
    <MemoryRouter>
      <Routes>
        <Route path="*" element={<CreatorMetaCallbackPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

const CODE = 'AQJ4-z0p8-d7XMhgehNAZp';
const STATE = '01M2D4EXFS4B5W4HTP5WHQDKG4';

describe('creator meta callback — session restore before the one-time code is spent', () => {
  beforeEach(() => {
    calls.length = 0;
    vi.clearAllMocks();
    hasToken.mockReturnValue(false);
    isApiLiveMock.mockReturnValue(true);
  });

  it('restores the session BEFORE exchanging the code when memory holds no token', async () => {
    renderAt(`?code=${CODE}&state=${STATE}`);

    await waitFor(() => expect(metaCallback).toHaveBeenCalled());

    // Order is the assertion. Swapping these two lines in the component leaves "bootstrap was
    // called" true while the exchange still goes out unauthenticated and burns Meta's code.
    expect(calls).toEqual(['auth.bootstrap', 'metaOAuth.callback']);
    expect(bootstrap).toHaveBeenCalledWith('creator');
  });

  it('still sends THIS code and state after the restore — the one value that cannot be re-obtained', async () => {
    renderAt(`?code=${CODE}&state=${STATE}`);

    await waitFor(() => expect(metaCallback).toHaveBeenCalled());
    expect(metaCallback).toHaveBeenCalledWith(CODE, STATE);
  });

  it('does not spend a refresh when a token is already in memory', async () => {
    hasToken.mockReturnValue(true);

    renderAt(`?code=${CODE}&state=${STATE}`);

    await waitFor(() => expect(metaCallback).toHaveBeenCalled());
    expect(bootstrap).not.toHaveBeenCalled();
    expect(calls).toEqual(['metaOAuth.callback']);
  });

  it('leaves mock mode untouched — no bootstrap, exchange as before', async () => {
    isApiLiveMock.mockReturnValue(false);
    hasToken.mockReturnValue(false);

    renderAt(`?code=${CODE}&state=${STATE}`);

    await waitFor(() => expect(metaCallback).toHaveBeenCalled());
    expect(bootstrap).not.toHaveBeenCalled();
  });

  it('does not exchange at all when Meta sent no code — nothing to restore a session for', async () => {
    renderAt('?error=access_denied');

    await waitFor(() => expect(screen.getByText(/declined/i)).toBeInTheDocument());
    expect(metaCallback).not.toHaveBeenCalled();
    expect(bootstrap).not.toHaveBeenCalled();
  });
});
