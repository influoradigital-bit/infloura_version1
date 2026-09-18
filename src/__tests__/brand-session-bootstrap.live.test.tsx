/**
 * /brand/onboarding and /brand/invite sit OUTSIDE ProtectedRoute on purpose (a signed-out visitor
 * must be able to reach them), which used to mean nothing ever called `POST /auth/refresh` for
 * them. In live mode the access token is memory-only, so after a reload, a restored tab or a
 * click on an emailed invite link these pages saw the `brand_token` storage HINT, believed they
 * were signed in, and sent their first request with no Authorization header — onboarding's
 * "Continue" failed UNAUTHENTICATED and an invite could never be accepted.
 *
 * `BrandSessionBootstrap` restores the session before the page renders; `hasBrandToken()` then
 * answers from the real in-memory token rather than the hint.
 *
 * FALSIFICATION: render the probe without the wrapper (or revert hasBrandToken to the
 * localStorage read) and the first test goes red with "signed-in" on a session that has no token;
 * the second goes red the same way, because the hint outlives the dead session.
 *
 * Run: npx vitest run --config vitest.live.config.ts src/__tests__/brand-session-bootstrap.live.test.tsx
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { BrandSessionBootstrap } from '@/components/brand/brand-session-bootstrap';
import {
  LIVE_SESSION_TOKEN_HINT,
  clearMemoryAccessToken,
  getMemoryAccessToken,
  hasBrandToken,
} from '@/lib/auth-session';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A JWT-shaped token whose payload says BRAND, so the role-slot guard (F-0348) accepts it. */
function brandJwt(): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64({ userType: 'BRAND', sub: '01HBRANDUSER' })}.sig`;
}

const Probe = () => <div>{hasBrandToken() ? 'signed-in' : 'signed-out'}</div>;

let fetchMock: ReturnType<typeof vi.fn>;

beforeAll(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
  clearMemoryAccessToken('brand');
});

afterAll(() => {
  vi.unstubAllGlobals();
});

describe('BrandSessionBootstrap — cold load of a brand route that sits outside the guards', () => {
  it('a live refresh cookie: the session is restored BEFORE the page renders', async () => {
    localStorage.setItem('brand_token', LIVE_SESSION_TOKEN_HINT); // what a reload leaves behind
    fetchMock.mockImplementation((url: string) => {
      expect(String(url)).toContain('/auth/refresh');
      return Promise.resolve(
        jsonResponse({ success: true, data: { accessToken: brandJwt(), expiresIn: 900 } }),
      );
    });

    render(
      <BrandSessionBootstrap>
        <Probe />
      </BrandSessionBootstrap>,
    );

    // Nothing renders while the one silent refresh is in flight — the page must not get a
    // chance to fire an unauthenticated request first.
    expect(screen.queryByText(/signed-/)).not.toBeInTheDocument();

    await waitFor(() => expect(screen.getByText('signed-in')).toBeInTheDocument());
    expect(getMemoryAccessToken('brand')).not.toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('a dead session with a stale hint: renders signed-OUT, never redirects, never claims a session', async () => {
    localStorage.setItem('brand_token', LIVE_SESSION_TOKEN_HINT);
    fetchMock.mockResolvedValue(jsonResponse({ success: false }, 401));

    render(
      <BrandSessionBootstrap>
        <Probe />
      </BrandSessionBootstrap>,
    );

    await waitFor(() => expect(screen.getByText('signed-out')).toBeInTheDocument());
  });

  it('a brand-new visitor with nothing stored still gets the page (signed-out is valid here)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ success: false }, 401));

    render(
      <BrandSessionBootstrap>
        <Probe />
      </BrandSessionBootstrap>,
    );

    await waitFor(() => expect(screen.getByText('signed-out')).toBeInTheDocument());
  });
});
