/**
 * F-0459 (storage-split-breaks-guard) — regression test.
 *
 * "Remember me" unchecked on creator login makes `api.auth.setToken('creator', token, false)`
 * write `creator_token` into `sessionStorage` (src/lib/api.ts, CR-121). `CreatorProtectedRoute`
 * (src/App.tsx) used to read `localStorage.getItem('creator_token')` only, so a legitimate,
 * just-completed login with the box unchecked was invisible to the guard: it redirected straight
 * back to `/creator/login` instead of rendering the protected page.
 *
 * The fix (already landed in src/App.tsx, see the `readAuthToken` helper) makes the guard check
 * BOTH stores — `localStorage.getItem(key) ?? sessionStorage.getItem(key)` — the same fallback
 * `HttpClient.getToken` already used. This test renders the actual `CreatorProtectedRoute`
 * component (not a reimplementation) and proves both branches:
 *   1. remember-me UNCHECKED (token in sessionStorage only) -> protected content renders.
 *   2. remember-me CHECKED (token in localStorage, the pre-existing/default path) -> unchanged.
 *
 * Run: npx vitest run src/__tests__/creator-protected-route.test.tsx
 */

import { describe, it, expect, beforeEach, beforeAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { api } from '@/lib/api';

// jsdom does not implement `window.matchMedia` (a known jsdom gap, unrelated to F-0459).
// `src/App.tsx` is a big module — importing it (the only way to reach the real, un-duplicated
// `CreatorProtectedRoute`) pulls in GSAP's ScrollTrigger via src/lib/scroll/smooth-scroll.ts,
// which calls `matchMedia` at *import time* and throws in jsdom before any test body runs. No
// other test file imports App.tsx directly (nothing else needed to), so this is stubbed locally
// here rather than in the shared src/test/setup.ts, and the App import is deferred (dynamic
// `import()` inside `beforeAll`) so the stub is in place before App.tsx's module graph evaluates.
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

let CreatorProtectedRoute: typeof import('@/App').CreatorProtectedRoute;

// Importing `@/App` pulls the whole route graph — GSAP/ScrollTrigger, Three.js, every lazy page —
// so this single import is genuinely slow. Alone it lands in ~13s, but under a full `npm test`
// run (many workers competing) it exceeded vitest's 30s default hookTimeout and the file failed as
// a SUITE with zero assertions run: `npm test` exited 1 while every individual test still passed
// in isolation. Raised here rather than globally so the rest of the suite keeps the tighter
// default — a slow hook anywhere else is still worth failing on.
beforeAll(async () => {
  ({ CreatorProtectedRoute } = await import('@/App'));
}, 120_000);

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

describe('CreatorProtectedRoute storage-split guard (F-0459)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('remember-me UNCHECKED: a token written to sessionStorage still lands the user on the protected page, not bounced back to login', () => {
    // Mirrors what a real unchecked-remember-me login does: creator-login.tsx calls
    // api.auth.setToken('creator', token, false), which HttpClient routes to sessionStorage.
    api.auth.setToken('creator', 'session-only-tok', false);
    expect(sessionStorage.getItem('creator_token')).toBe('session-only-tok');
    expect(localStorage.getItem('creator_token')).toBeNull();

    renderGuardedRoute();

    expect(screen.getByText('Protected Creator Content')).toBeInTheDocument();
    expect(screen.queryByText('Creator Login Page')).not.toBeInTheDocument();
  });

  it('remember-me CHECKED: a token written to localStorage keeps working exactly as before', () => {
    api.auth.setToken('creator', 'remembered-tok', true);
    expect(localStorage.getItem('creator_token')).toBe('remembered-tok');
    expect(sessionStorage.getItem('creator_token')).toBeNull();

    renderGuardedRoute();

    expect(screen.getByText('Protected Creator Content')).toBeInTheDocument();
    expect(screen.queryByText('Creator Login Page')).not.toBeInTheDocument();
  });

  it('no token anywhere: still redirects to /creator/login (guard is not a no-op)', () => {
    renderGuardedRoute();

    expect(screen.getByText('Creator Login Page')).toBeInTheDocument();
    expect(screen.queryByText('Protected Creator Content')).not.toBeInTheDocument();
  });
});
