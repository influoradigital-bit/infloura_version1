/**
 * Creator Meta OAuth callback — CR-105 / CR-109 / CR-118 / CR-66.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Four defects lived in this one page, and none had a test:
 *  - CR-105: `setLocalConnectionState` was called with only 2 args, dropping `accountType` —
 *    which is why `requiresBusinessAccount` (useDailySuggestion.ts) could never see 'personal'
 *    and a personal-IG creator looped on the connect prompt forever.
 *  - CR-109: a failed callback never cleared a previously-connected:true mirror, so the app kept
 *    gating features as if still connected after a reconnect attempt had just failed.
 *  - CR-118: `?error=`/`?error_description=` off the URL — attacker-controlled, this route has
 *    no auth guard — were rendered back to the page verbatim.
 *  - CR-66: "Try Again" on the error screen navigated to Settings instead of re-running the
 *    OAuth flow, dead-ending the recovery path.
 *
 * Run: npx vitest run src/pages/creator-meta-callback.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import CreatorMetaCallbackPage from './creator-meta-callback';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const metaCallback = vi.fn();
const metaAuthorize = vi.fn();
const setLocalConnectionState = vi.fn();
const consumeConnectReturnTo = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      metaOAuth: {
        callback: (...a: unknown[]) => metaCallback(...a),
        authorize: (...a: unknown[]) => metaAuthorize(...a),
        setLocalConnectionState: (...a: unknown[]) => setLocalConnectionState(...a),
        consumeConnectReturnTo: (...a: unknown[]) => consumeConnectReturnTo(...a),
      },
    },
  };
});

/** Sets window.location to a real callback URL — the component reads window.location.search
 *  directly, not the router's location, so react-router's MemoryRouter alone won't do it. */
function setCallbackUrl(query: string) {
  window.history.pushState({}, '', `/creator/settings/meta/callback${query}`);
}

function renderCallback() {
  return render(
    <MemoryRouter>
      <Routes>
        <Route path="*" element={<CreatorMetaCallbackPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CreatorMetaCallbackPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    consumeConnectReturnTo.mockReturnValue(null);
  });

  afterEach(() => {
    window.history.pushState({}, '', '/');
  });

  it('CR-105: passes accountType through to setLocalConnectionState on a successful callback', async () => {
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockResolvedValue({ connected: false, grantedScopes: [], accountType: 'personal' });

    renderCallback();

    await waitFor(() => expect(setLocalConnectionState).toHaveBeenCalled());
    expect(setLocalConnectionState).toHaveBeenCalledWith(false, [], 'personal');
    expect(await screen.findByText('Account connected')).toBeInTheDocument();
  });

  it('CR-109: a failed callback clears the local connection mirror instead of leaving it stale', async () => {
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockRejectedValue(new Error('server error'));

    renderCallback();

    await waitFor(() => expect(setLocalConnectionState).toHaveBeenCalledWith(false, [], null));
    expect(await screen.findByText('Connection failed')).toBeInTheDocument();
  });

  it('CR-109: an oauth error redirect (no code) also clears the local connection mirror', async () => {
    setCallbackUrl('?error=access_denied');

    renderCallback();

    await waitFor(() => expect(setLocalConnectionState).toHaveBeenCalledWith(false, [], null));
  });

  it('CR-118: a known error code gets its own message', async () => {
    setCallbackUrl('?error=access_denied');
    renderCallback();
    expect(await screen.findByText('You declined the connection request.')).toBeInTheDocument();
  });

  it('CR-118: an unrecognized error is NEVER reflected verbatim — generic message only', async () => {
    const injected = '<script>alert(1)</script>attacker-controlled-text';
    setCallbackUrl(`?error=${encodeURIComponent('totally_made_up_code')}&error_description=${encodeURIComponent(injected)}`);

    renderCallback();

    expect(await screen.findByText('Could not connect your account. Please try again.')).toBeInTheDocument();
    // The raw injected string must not appear anywhere on the page.
    expect(screen.queryByText(injected)).not.toBeInTheDocument();
    expect(screen.queryByText(/totally_made_up_code/)).not.toBeInTheDocument();
  });

  it('CR-66: "Try Again" re-runs the OAuth flow instead of just navigating to Settings', async () => {
    const user = userEvent.setup();
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockRejectedValue(new Error('boom'));
    metaAuthorize.mockResolvedValue({ authorizationUrl: 'https://meta.example/oauth', state: 's' });

    // jsdom doesn't implement navigation; give window.location a settable href for this test.
    const originalLocation = window.location;
    // @ts-expect-error -- test-only override
    delete window.location;
    window.location = { ...originalLocation, href: '' };

    renderCallback();
    await screen.findByText('Connection failed');

    await user.click(screen.getByRole('button', { name: 'Try Again' }));

    await waitFor(() => expect(metaAuthorize).toHaveBeenCalled());
    await waitFor(() => expect(window.location.href).toBe('https://meta.example/oauth'));

    window.location = originalLocation;
  });
});
