/**
 * HttpClient resilience — Ananya, F-0438/F-0439/F-0466.
 *
 * F-0438 — on a mid-session 401 whose refresh also fails, `fetchWithAuthRetry` already cleared
 * the stale token and returned the original 401 to the caller, but nothing told the SHELL the
 * session was gone: `ProtectedRoute` (src/App.tsx) only reads the token at render time, so a
 * user sitting on an already-mounted page stayed on it, looking logged in, until they happened
 * to trigger a fresh navigation. Fixed with a hard redirect to the same `/brand/login` /
 * `/creator/login` routes `ProtectedRoute` itself would have sent them to.
 *
 * F-0439 — the main request path (`request`/`requestWithMeta`/`requestOrNull`/`downloadBlob`)
 * carried no `AbortController`/timeout at all; a hung connection left the caller's `await`
 * pending forever, and every `finally { setIsSubmitting(false) }` never ran. Fixed with a 30s
 * timeout that rejects as a normal `ApiError` (`code: 'TIMEOUT'`) instead of hanging — see
 * `REQUEST_TIMEOUT_MS`'s doc comment in src/lib/api.ts for the chosen value's rationale and why
 * uploads are deliberately excluded.
 *
 * F-0466 — `ApiError` read `field`/`fields` off the server envelope and then dropped them before
 * they reached the caller, so no server-side validation error could ever be mapped back to the
 * form control that caused it. Fixed by carrying both through the constructor.
 *
 * F-0671 — `refreshAccessToken` (the `POST /auth/refresh` call `bootstrap()` and the reactive
 * 401 path both depend on) carried no `AbortController`/timeout of its own — the F-0439 timer
 * above only bounds the OUTER request, not this inner refresh fetch. `bootstrap()` is awaited
 * directly by `useAuthGuardState` (src/App.tsx) on a live-mode cold load with no in-memory
 * token yet, and while that state is `'checking'` the guard renders `null`. A hung refresh here
 * therefore left a protected route blank forever, with no redirect and no error. Fixed by
 * giving this fetch the same `REQUEST_TIMEOUT_MS` bound, so a hang resolves `bootstrap()` to
 * `false` instead of never resolving at all.
 *
 * Run: npx vitest run --config vitest.live.config.ts src/lib/__tests__/api-client-resilience.live.test.ts
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/** Minimal unsigned JWT with the given `exp` — only the payload is ever read client-side.
 *  Mirrors token-refresh.live.test.ts's helper of the same name/shape. */
function jwtExpiringIn(seconds: number): string {
  const payload = { sub: 'cr_1', exp: Math.floor(Date.now() / 1000) + seconds };
  const b64 = btoa(JSON.stringify(payload)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `header.${b64}.signature`;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('HttpClient resilience', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let api: typeof import('@/lib/api').api;
  let ApiError: typeof import('@/lib/api').ApiError;

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    ({ api, ApiError } = await import('@/lib/api'));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  // ---------------------------------------------------------------------------
  // F-0438 — terminal-401 hard redirect
  // ---------------------------------------------------------------------------
  describe('terminal session recovery (F-0438)', () => {
    // jsdom doesn't implement navigation — same settable-stub pattern
    // src/pages/creator-meta-callback.test.tsx already uses for `window.location.href`.
    let originalLocation: Location;

    beforeEach(() => {
      originalLocation = window.location;
    });

    afterEach(() => {
      window.location = originalLocation;
    });

    function stubLocation(pathname: string) {
      // @ts-expect-error — deleting a non-optional global for the test stub below.
      delete window.location;
      window.location = { ...originalLocation, href: '', pathname };
    }

    it('redirects to /creator/login when a mid-session 401 survives refresh', async () => {
      stubLocation('/creator/dashboard');
      // Token looks fresh to the client, so only the REACTIVE 401 path can fire — mirrors
      // token-refresh.live.test.ts's "still recovers reactively" setup.
      api.auth.setToken('creator', jwtExpiringIn(600));
      fetchMock.mockImplementation((url: string) => {
        if (String(url).includes('/auth/refresh')) {
          // Refresh itself fails — the refresh cookie is gone too. This is the terminal case.
          return Promise.resolve(new Response('', { status: 401 }));
        }
        return Promise.resolve(
          jsonResponse({ success: false, error: { code: 'UNAUTHENTICATED', message: 'expired' } }, 401),
        );
      });

      await expect(api.creatorCampaigns.browse()).rejects.toBeInstanceOf(ApiError);

      expect(window.location.href).toBe('/creator/login');
      // The stale token must not survive a terminal failure either.
      expect(localStorage.getItem('creator_token')).toBeNull();
    });

    it('redirects to /brand/login (not /creator/login) for a brand-role terminal 401', async () => {
      stubLocation('/brand/dashboard');
      api.auth.setToken('brand', jwtExpiringIn(600));
      fetchMock.mockImplementation((url: string) => {
        if (String(url).includes('/auth/refresh')) return Promise.resolve(new Response('', { status: 401 }));
        return Promise.resolve(
          jsonResponse({ success: false, error: { code: 'UNAUTHENTICATED', message: 'expired' } }, 401),
        );
      });

      await expect(api.workspaces.getMe()).rejects.toBeInstanceOf(ApiError);

      expect(window.location.href).toBe('/brand/login');
    });

    it('does NOT redirect when the refresh succeeds and the retry goes through', async () => {
      stubLocation('/creator/dashboard');
      api.auth.setToken('creator', jwtExpiringIn(600));
      let calls = 0;
      fetchMock.mockImplementation((url: string) => {
        if (String(url).includes('/auth/refresh')) {
          return Promise.resolve(
            jsonResponse({ success: true, data: { accessToken: 'rotated.token', expiresIn: 900 } }),
          );
        }
        calls += 1;
        return Promise.resolve(
          calls === 1
            ? jsonResponse({ success: false, error: { code: 'UNAUTHENTICATED', message: 'expired' } }, 401)
            : jsonResponse({ success: true, data: [], meta: { hasMore: false } }),
        );
      });

      await api.creatorCampaigns.browse();

      // Untouched — the stub's own default ('') from stubLocation, proving no redirect fired.
      expect(window.location.href).toBe('');
    });

    it('does not loop-redirect when already sitting on the login page', async () => {
      stubLocation('/creator/login');
      api.auth.setToken('creator', jwtExpiringIn(600));
      fetchMock.mockImplementation((url: string) => {
        if (String(url).includes('/auth/refresh')) return Promise.resolve(new Response('', { status: 401 }));
        return Promise.resolve(
          jsonResponse({ success: false, error: { code: 'UNAUTHENTICATED', message: 'expired' } }, 401),
        );
      });

      await expect(api.creatorCampaigns.browse()).rejects.toBeInstanceOf(ApiError);

      expect(window.location.href).toBe('');
    });
  });

  // ---------------------------------------------------------------------------
  // F-0439 — request timeout
  // ---------------------------------------------------------------------------
  describe('request timeout (F-0439)', () => {
    it('rejects with ApiError(TIMEOUT) instead of hanging forever on a dead connection', async () => {
      vi.useFakeTimers();
      api.auth.setToken('creator', 'mock_creator_token');
      // Simulates a connection that never resolves on its own — only an abort ends it, exactly
      // like a real hung `fetch` behaves once its AbortSignal fires.
      fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
        return new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted.', 'AbortError'));
          });
        });
      });

      const promise = api.creatorCampaigns.browse();
      // The rejection handler MUST be attached BEFORE the timers are flushed. Advancing them
      // first is what actually fires the abort, so the ApiError(TIMEOUT) rejects while nothing
      // is listening — every assertion still passed, but node reported an unhandled rejection
      // and `npm run test:live` exited 1 on a suite whose 16 tests were all green.
      const rejects = expect(promise).rejects.toMatchObject({ code: 'TIMEOUT' });
      // Comfortably clears any reasonable timeout value without depending on the exact constant.
      await vi.advanceTimersByTimeAsync(5 * 60 * 1000);

      await rejects;
    });

    it('does not time out a request that resolves promptly', async () => {
      vi.useFakeTimers();
      api.auth.setToken('creator', 'mock_creator_token');
      // `requestWithMeta<CreatorCampaignListItem[]>` treats `data` itself as the array —
      // `browse()` then wraps it as `{ campaigns: data, meta }`. `data` must be `[]` here, not
      // `{ campaigns: [] }`, or the resolved value double-nests.
      fetchMock.mockResolvedValue(
        jsonResponse({ success: true, data: [], meta: { hasMore: false } }),
      );

      const promise = api.creatorCampaigns.browse();
      await vi.advanceTimersByTimeAsync(0);

      await expect(promise).resolves.toMatchObject({ campaigns: [] });
    });
  });

  // ---------------------------------------------------------------------------
  // F-0671 — refresh call (bootstrap's cold-load path) times out instead of hanging
  // ---------------------------------------------------------------------------
  describe('refresh-call timeout (F-0671)', () => {
    it('resolves bootstrap() to false instead of hanging forever when /auth/refresh never answers', async () => {
      vi.useFakeTimers();
      // No token in memory — the exact F-0551 cold-load shape `useAuthGuardState` hits, where
      // `bootstrap()` is the ONLY thing standing between "checking" and a definite answer.
      // Same hung-connection simulation as the F-0439 test above: nothing but an abort ends it.
      fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
        return new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted.', 'AbortError'));
          });
        });
      });

      const promise = api.auth.bootstrap('creator');
      // Attach the resolution assertion before advancing timers — same unhandled-rejection
      // trap the F-0439 test above documents, transposed to a resolved value instead of a
      // rejection: without a timeout this promise never settles, so this `await` would hang
      // the test until vitest's own suite timeout instead of failing fast and legibly.
      const resolves = expect(promise).resolves.toBe(false);
      await vi.advanceTimersByTimeAsync(5 * 60 * 1000);

      await resolves;
    });

    it('still bootstraps normally when /auth/refresh answers promptly', async () => {
      vi.useFakeTimers();
      fetchMock.mockResolvedValue(
        jsonResponse({ success: true, data: { accessToken: 'fresh.token', expiresIn: 900 } }),
      );

      const promise = api.auth.bootstrap('creator');
      await vi.advanceTimersByTimeAsync(0);

      await expect(promise).resolves.toBe(true);
      expect(api.auth.hasToken('creator')).toBe(true);
    });
  });

  // ---------------------------------------------------------------------------
  // F-0466 — ApiError carries field-level validation errors
  // ---------------------------------------------------------------------------
  describe('ApiError field-level validation (F-0466)', () => {
    it('carries the server field and fields members through instead of dropping them', async () => {
      api.auth.setToken('creator', 'mock_creator_token');
      fetchMock.mockResolvedValue(
        jsonResponse(
          {
            success: false,
            error: {
              code: 'VALIDATION_ERROR',
              message: 'Invalid input',
              field: 'phone',
              fields: [{ field: 'phone', message: 'Enter a valid 10-digit mobile number' }],
            },
          },
          422,
        ),
      );

      await expect(api.creatorCampaigns.browse()).rejects.toMatchObject({
        code: 'VALIDATION_ERROR',
        field: 'phone',
        fields: [{ field: 'phone', message: 'Enter a valid 10-digit mobile number' }],
      });
    });

    it('leaves field/fields undefined for an error the server sent neither on', async () => {
      api.auth.setToken('creator', 'mock_creator_token');
      fetchMock.mockResolvedValue(
        jsonResponse({ success: false, error: { code: 'NOT_FOUND', message: 'Gone' } }, 404),
      );

      await expect(api.creatorCampaigns.browse()).rejects.toMatchObject({
        code: 'NOT_FOUND',
        field: undefined,
        fields: undefined,
      });
    });
  });
});
