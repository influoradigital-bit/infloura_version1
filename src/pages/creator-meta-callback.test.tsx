/**
 * Creator Meta OAuth callback — CR-63/CR-105 / CR-109 / CR-118 / CR-66 / CR-103/F-0116.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Five defects lived in this one page:
 *  - CR-63/CR-105 (same root cause, two rows): `setLocalConnectionState` was called with only 2
 *    args, dropping `accountType` — which is why `requiresBusinessAccount`
 *    (useDailySuggestion.ts) could never see 'personal' and a personal-IG creator looped on the
 *    connect prompt forever.
 *  - CR-109: a failed callback never cleared a previously-connected:true mirror, so the app kept
 *    gating features as if still connected after a reconnect attempt had just failed.
 *  - CR-118: `?error=`/`?error_description=` off the URL — attacker-controlled, this route has
 *    no auth guard — were rendered back to the page verbatim.
 *  - CR-66: "Try Again" on the error screen navigated to Settings instead of re-running the
 *    OAuth flow, dead-ending the recovery path.
 *  - CR-103/F-0116: the page moved to the 'success' state unconditionally after ANY 200
 *    response, including `connected: false` (NO_BUSINESS_ACCOUNT) — falsely telling a
 *    personal-account creator that brands could now see their verified metrics.
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
const setConnectReturnTo = vi.fn();

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
        setConnectReturnTo: (...a: unknown[]) => setConnectReturnTo(...a),
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

/** Variant with a real /creator/onboarding route, so an actual navigate() there is observable
 *  as a distinct rendered screen rather than inferred from a same-route remount side effect. */
function renderCallbackWithOnboardingRoute() {
  return render(
    <MemoryRouter initialEntries={['/creator/settings/meta/callback']}>
      <Routes>
        <Route path="/creator/settings/meta/callback" element={<CreatorMetaCallbackPage />} />
        <Route path="/creator/onboarding" element={<div>ONBOARDING WIZARD</div>} />
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
    metaCallback.mockResolvedValue({ connected: true, grantedScopes: ['instagram_basic'], accountType: 'business' });

    renderCallback();

    await waitFor(() => expect(setLocalConnectionState).toHaveBeenCalled());
    expect(setLocalConnectionState).toHaveBeenCalledWith(true, ['instagram_basic'], 'business');
    expect(await screen.findByText('Account connected')).toBeInTheDocument();
  });

  it("CR-105: passes accountType:'personal' through to setLocalConnectionState too — not just 'business'", async () => {
    // Priya's review flagged this exact gap: the only prior CR-105 test hardcoded 'business' as
    // the third setLocalConnectionState arg, so a regression that hardcoded ANY fixed value
    // there would still pass every test in this file while re-breaking requiresBusinessAccount
    // (useDailySuggestion.ts), which needs the real 'personal' value to render
    // BusinessAccountRequired instead of looping the creator on the connect prompt forever.
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockResolvedValue({ connected: false, grantedScopes: [], accountType: 'personal' });

    renderCallback();

    await waitFor(() => expect(setLocalConnectionState).toHaveBeenCalled());
    expect(setLocalConnectionState).toHaveBeenCalledWith(false, [], 'personal');
  });

  it('CR-103/F-0116: a personal-account response (connected:false) never claims "Account connected"', async () => {
    // This is the exact regression: a 200 response with connected:false previously still
    // flipped the page to the same "success" copy as a real connection, falsely telling a
    // personal-account creator that brands could now see their verified metrics.
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockResolvedValue({ connected: false, grantedScopes: [], accountType: 'personal' });

    renderCallback();

    expect(await screen.findByText('Business account needed')).toBeInTheDocument();
    expect(screen.queryByText('Account connected')).not.toBeInTheDocument();
    expect(
      screen.getByText(/brands need a business or creator account/i),
    ).toBeInTheDocument();
  });

  it('CR-103/F-0116: connected:false with no accountType gets a generic incomplete message, not a false claim', async () => {
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockResolvedValue({ connected: false, grantedScopes: [], accountType: null });

    renderCallback();

    expect(await screen.findByText('Connection incomplete')).toBeInTheDocument();
    expect(screen.queryByText('Account connected')).not.toBeInTheDocument();
    expect(screen.queryByText(/brands can now see your verified/i)).not.toBeInTheDocument();
    expect(screen.getByText(/couldn.t verify full access/i)).toBeInTheDocument();
  });

  it('F-0164: a personal-account response during onboarding-resume shows the "Business account needed" explanation instead of auto-redirecting straight back into the wizard', async () => {
    // Regression: the onboarding-resume redirect previously fired whenever resumingOnboarding
    // was true, regardless of result.connected, silently skipping this explanation screen for a
    // personal-account creator who started the connect from onboarding.
    localStorage.setItem('creator_onboarding_meta_resume', '1');
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockResolvedValue({ connected: false, grantedScopes: [], accountType: 'personal' });

    renderCallbackWithOnboardingRoute();

    expect(await screen.findByText('Business account needed')).toBeInTheDocument();
    // Give any wrongful auto-navigation a chance to fire before asserting it didn't.
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.getByText('Business account needed')).toBeInTheDocument();
    expect(screen.queryByText('ONBOARDING WIZARD')).not.toBeInTheDocument();
    // The manual escape hatch must still be there for the creator to use.
    expect(screen.getByRole('button', { name: 'Back to onboarding' })).toBeInTheDocument();
  });

  it('CR-120: a real connection during onboarding-resume still auto-redirects straight back into the wizard', async () => {
    localStorage.setItem('creator_onboarding_meta_resume', '1');
    setCallbackUrl('?code=abc123&state=xyz789');
    metaCallback.mockResolvedValue({ connected: true, grantedScopes: ['instagram_basic'], accountType: 'business' });

    renderCallbackWithOnboardingRoute();

    await waitFor(() => expect(setLocalConnectionState).toHaveBeenCalledWith(true, ['instagram_basic'], 'business'));
    expect(await screen.findByText('ONBOARDING WIZARD')).toBeInTheDocument();
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

  // F-0181 — a plain `{}[errorCode]` bracket lookup on the allowlist walks Object.prototype for
  // an attacker-controlled key on this unguarded route. Each of these previously bypassed the
  // generic-message fallback: `__proto__`/`valueOf` crashed the render entirely (caught by
  // ErrorBoundary), `toString` rendered the literal string "[object Undefined]", `constructor`
  // rendered nothing. All four must now behave identically to any other unrecognized code.
  it.each(['__proto__', 'constructor', 'toString', 'valueOf', 'hasOwnProperty'])(
    'F-0181: %s as ?error= is an Object.prototype key, not a real error code — must still fall through to the generic message, not crash or leak',
    async (protoKey) => {
      setCallbackUrl(`?error=${encodeURIComponent(protoKey)}`);

      renderCallback();

      expect(
        await screen.findByText('Could not connect your account. Please try again.'),
      ).toBeInTheDocument();
      // Would have crashed to the ErrorBoundary's copy, or rendered "[object Undefined]" /
      // nothing, before the fix — assert the real page heading is still there, not a fallback.
      expect(screen.getByText('Connection failed')).toBeInTheDocument();
      expect(screen.queryByText('[object Undefined]')).not.toBeInTheDocument();
    },
  );

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

  it('F-0168: "Try Again" re-persists the captured return path before redirecting, so a retried connect still returns to the deal room instead of falling back to Settings', async () => {
    // The general-return-path marker is read-and-cleared at MOUNT (into `generalReturnPath`
    // state), before the first connect attempt even fails. A naive retry that just calls
    // authorize() again has nothing left in storage for the callback's *next* mount to consume
    // — this test proves handleRetry re-writes it first.
    const user = userEvent.setup();
    setCallbackUrl('?code=abc123&state=xyz789');
    consumeConnectReturnTo.mockReturnValue('/creator/chat?deal=deal_1&tab=deliverables');
    metaCallback.mockRejectedValue(new Error('boom'));
    metaAuthorize.mockResolvedValue({ authorizationUrl: 'https://meta.example/oauth', state: 's' });

    const originalLocation = window.location;
    // @ts-expect-error -- test-only override
    delete window.location;
    window.location = { ...originalLocation, href: '' };

    renderCallback();
    await screen.findByText('Connection failed');

    await user.click(screen.getByRole('button', { name: 'Try Again' }));

    await waitFor(() => expect(metaAuthorize).toHaveBeenCalled());
    // The re-persist must happen BEFORE authorize() redirects, not after.
    expect(setConnectReturnTo).toHaveBeenCalledWith('/creator/chat?deal=deal_1&tab=deliverables');
    const setOrder = setConnectReturnTo.mock.invocationCallOrder[0];
    const authorizeOrder = metaAuthorize.mock.invocationCallOrder[0];
    expect(setOrder).toBeLessThan(authorizeOrder);

    window.location = originalLocation;
  });
});
