/**
 * F-0551 — silent session recovery on a cold page load (live mode).
 *
 * Access token in memory only (src/lib/api.ts) means a full page reload starts with nothing in
 * memory even for a visitor who is genuinely still logged in via the HttpOnly refresh cookie.
 * Before this ticket NOTHING called `POST /auth/refresh` on load at all — the only pre-existing
 * caller of `HttpClient.bootstrap` is the deal message stream's reactive 401 handler
 * (src/lib/api.ts), which fires mid-session, never on load. A naive memory-only access token
 * with no replacement for that would have bounced every returning, still-logged-in visitor
 * straight back to the login page on every single page load.
 *
 * This test proves the guard added to `CreatorProtectedRoute` (src/App.tsx) closes that gap —
 * and that a visitor with no valid session is still correctly redirected, not stranded on a
 * blank screen forever.
 *
 * FALSIFICATION: reverting `useAuthGuardState`/`CreatorProtectedRoute` in src/App.tsx to the
 * pre-F-0551 synchronous `readAuthToken('creator_token')` check turns the first test red — with
 * no token in Storage (F-0551 never puts the real token there) the guard redirects straight to
 * `/creator/login` and the silent `/auth/refresh` this test asserts on is never even sent.
 *
 * Run: npx vitest run --config vitest.live.config.ts src/__tests__/f0551-silent-refresh-on-load.live.test.tsx
 */
import { describe, it, expect, vi, beforeAll, afterEach, afterAll } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { clearMemoryAccessToken } from '@/lib/auth-session';

// jsdom does not implement `window.matchMedia`. Importing `src/App.tsx` (the only way to reach
// the real, un-duplicated `CreatorProtectedRoute`) pulls in GSAP's ScrollTrigger via
// src/lib/scroll/smooth-scroll.ts, which calls `matchMedia` at *import time* — see
// src/__tests__/creator-protected-route.test.tsx for the identical stub and the same reason the
// App import below is deferred into `beforeAll` rather than a static import.
beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    });
  }
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

let CreatorProtectedRoute: typeof import('@/App').CreatorProtectedRoute;
let fetchMock: ReturnType<typeof vi.fn>;

// Importing `@/App` pulls the whole route graph (GSAP/ScrollTrigger, every lazy page) and is
// genuinely slow — see creator-protected-route.test.tsx's identical note. Imported once here,
// not per test.
beforeAll(async () => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  ({ CreatorProtectedRoute } = await import('@/App'));
}, 120_000);

afterEach(() => {
  fetchMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
  // The module (and its in-memory token slot) is imported once for this whole file — without
  // this, a session recovered by one test would still be sitting in memory for the next one,
  // masking the exact cold-load state each test means to start from.
  clearMemoryAccessToken('creator');
});

afterAll(() => {
  vi.unstubAllGlobals();
});

function renderGuardedRoute() {
  return render(
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <Routes>
        <Route
          path="/creator/dashboard"
          element={
            <CreatorProtectedRoute>
              <div>Protected Creator Content</div>
            </CreatorProtectedRoute>
          }
        />
        <Route path="/creator/login" element={<div>Creator Login Page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('silent refresh on a cold load (F-0551)', () => {
  it('no in-memory token but a valid refresh cookie: lands on the protected page, never bounced to login first', async () => {
    fetchMock.mockImplementation((url: string) => {
      expect(String(url)).toContain('/auth/refresh');
      return Promise.resolve(
        jsonResponse({ success: true, data: { accessToken: 'recovered.jwt.value', expiresIn: 900 } }),
      );
    });

    renderGuardedRoute();

    // Mid-bootstrap: neither branch has rendered yet.
    expect(screen.queryByText('Protected Creator Content')).not.toBeInTheDocument();
    expect(screen.queryByText('Creator Login Page')).not.toBeInTheDocument();

    await waitFor(() => expect(screen.getByText('Protected Creator Content')).toBeInTheDocument());
    // Never redirected, even transiently, once the guard has finished checking.
    expect(screen.queryByText('Creator Login Page')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalled();
  });

  it('no in-memory token and no valid refresh cookie: redirected to /creator/login, not stranded on a blank guard forever', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        jsonResponse({ success: false, error: { code: 'INVALID_REFRESH_TOKEN', message: 'missing' } }, 401),
      ),
    );

    renderGuardedRoute();

    await waitFor(() => expect(screen.getByText('Creator Login Page')).toBeInTheDocument());
    expect(screen.queryByText('Protected Creator Content')).not.toBeInTheDocument();
  });
});
