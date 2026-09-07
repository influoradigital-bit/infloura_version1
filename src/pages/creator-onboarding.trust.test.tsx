/**
 * Creator onboarding Step 1 — the Instagram trust screen (T-IGTRUST-0907).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Creators were registering and then not connecting Instagram. Two causes, both pinned here:
 *
 * 1. THE ASK WAS UNJUSTIFIED. Step 1 said "We'll auto-import your profile info and verify your
 *    creator status" beside a bare Connect button — no statement of what Influora can read,
 *    what it can never do, that the grant is revocable, or what the creator loses by skipping.
 *    Next to a "Skip for now" link, declining was the rational choice. The stake is real:
 *    CreatorProfileSpecifications.hasPlatforms filters brand Discover with an EXISTS over
 *    platform_stats, and both writers of that table build the row from a Meta metric — so an
 *    unconnected creator cannot appear when a brand ticks the Instagram filter (ledger F-0694).
 *
 * 2. THE ONES WHO DID TRUST IT HIT A WALL. `handleConnectInstagram` called
 *    `api.metaOAuth.authorize()` with no argument, which the backend defaults to
 *    FACEBOOK_LOGIN — a dialog that demands an Instagram professional account already linked
 *    to a Facebook Page the creator can administer. Settings has asked that question before
 *    the redirect since T-IGLOGIN-0820; onboarding, the page every creator passes through,
 *    never got the fix and sent all of them down the Page-required path. A creator without a
 *    Page dead-ended inside Meta's own UI with nothing explaining why.
 *
 * The `authorize` assertions below are the falsifiable half: they fail against the pre-fix
 * tree, where the call carried no `authPath` at all.
 *
 * Run: npx vitest run src/pages/creator-onboarding.trust.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import CreatorOnboardingPage from './creator-onboarding';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: toastMock }),
}));

const authorizeMock = vi.fn();
const getLocalConnectionStateMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      metaOAuth: {
        ...actual.api.metaOAuth,
        authorize: (...a: unknown[]) => authorizeMock(...a),
        getLocalConnectionState: () => getLocalConnectionStateMock(),
      },
    },
  };
});

function renderOnboarding() {
  return render(
    <MemoryRouter>
      <CreatorOnboardingPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  // Step 1 renders its unconnected state only when the local mirror says so.
  getLocalConnectionStateMock.mockReturnValue({ connected: false, scopes: null });
  authorizeMock.mockResolvedValue({ authorizationUrl: 'https://meta.example/dialog', state: 's' });
  // jsdom has no navigation; the component assigns to window.location on success.
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...window.location, assign: vi.fn(), href: 'http://localhost/' },
  });
});

describe('Step 1 states what the creator is granting', () => {
  it('names the concrete cost of skipping — invisibility in the brand Instagram search', () => {
    renderOnboarding();
    expect(screen.getByText(/Brands search by verified Instagram reach/i)).toBeInTheDocument();
  });

  // The four-line permission list this test used to assert was removed at Swapnil's direction
  // (see creator-onboarding.tsx). What replaced it as the trust signal is the App Review
  // credential, asserted in the next test. The claims themselves were never wrong — they are
  // simply no longer made on this screen, and still stand on the Settings card, which
  // connected-accounts.test.tsx covers.
  it('no longer itemises the grant on this screen', () => {
    renderOnboarding();
    expect(screen.queryByText(/exactly what you.re granting/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/never post, comment or send DMs/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Disconnect whenever you want/i)).not.toBeInTheDocument();
  });

  it('attributes the integration to the Instagram Graph API without claiming Meta endorses us', () => {
    renderOnboarding();

    // True and specific: Meta App Review granted Advanced Access on the scopes we request.
    // The approval attaches to the PERMISSIONS, which is what makes it sayable.
    expect(screen.getByText(/Official Instagram Graph API/i)).toBeInTheDocument();
    expect(screen.getByText(/Permissions approved by Meta App Review/i)).toBeInTheDocument();
    expect(screen.getByText(/never on Influora/i)).toBeInTheDocument();

    // Priya ruling — none of these may appear. They attach the approval to the COMPANY rather
    // than the permissions: "Meta Verified" is a paid Meta product Influora does not hold, and
    // Influora is not a Meta Business Partner (pages_read_engagement was rejected at review).
    // A false endorsement on the trust screen defeats the trust screen.
    expect(screen.queryByText(/meta verified/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/verified by meta/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/meta partner/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/meta.business partner/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/endorsed by meta/i)).not.toBeInTheDocument();
  });

  it('no longer claims an account is required, since Skip is right below it', () => {
    renderOnboarding();
    expect(screen.queryByText(/Connect at least one account/i)).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Skip for now — connect from Settings later/i }),
    ).toBeInTheDocument();
  });
});

describe('Connect asks about the Facebook Page before handing the browser to Meta', () => {
  it('does not start OAuth on the first click — it asks which configuration applies', async () => {
    const user = userEvent.setup();
    renderOnboarding();

    await user.click(screen.getByRole('button', { name: /^Connect$/ }));

    expect(
      await screen.findByText(/Is your Instagram linked to a Facebook Page\?/i),
    ).toBeInTheDocument();
    // The redirect must not have been started yet: the answer decides which one it is.
    expect(authorizeMock).not.toHaveBeenCalled();
  });

  it('sends a creator with no Facebook Page down INSTAGRAM_LOGIN, not the Page-required path', async () => {
    const user = userEvent.setup();
    renderOnboarding();

    await user.click(screen.getByRole('button', { name: /^Connect$/ }));
    await user.click(await screen.findByText(/No — Instagram only/i));

    await waitFor(() => expect(authorizeMock).toHaveBeenCalledTimes(1));
    // Pre-fix this was `authorize()` with no argument, which the backend defaults to
    // FACEBOOK_LOGIN — the exact dead-end this ticket exists to remove.
    expect(authorizeMock).toHaveBeenCalledWith('INSTAGRAM_LOGIN');
    expect(authorizeMock).not.toHaveBeenCalledWith(undefined);
  });

  it('sends a creator who has a Page down FACEBOOK_LOGIN explicitly', async () => {
    const user = userEvent.setup();
    renderOnboarding();

    await user.click(screen.getByRole('button', { name: /^Connect$/ }));
    await user.click(await screen.findByText(/Yes — I have a Facebook Page/i));

    await waitFor(() => expect(authorizeMock).toHaveBeenCalledTimes(1));
    expect(authorizeMock).toHaveBeenCalledWith('FACEBOOK_LOGIN');
  });

  it('drops the onboarding resume marker so the callback returns here, not to Settings', async () => {
    const user = userEvent.setup();
    renderOnboarding();

    await user.click(screen.getByRole('button', { name: /^Connect$/ }));
    await user.click(await screen.findByText(/No — Instagram only/i));

    await waitFor(() =>
      expect(localStorage.getItem('creator_onboarding_meta_resume')).toBe('1'),
    );
  });

  it('clears the resume marker when authorize fails, so a retry is not misrouted', async () => {
    authorizeMock.mockRejectedValueOnce(new Error('meta down'));
    const user = userEvent.setup();
    renderOnboarding();

    await user.click(screen.getByRole('button', { name: /^Connect$/ }));
    await user.click(await screen.findByText(/No — Instagram only/i));

    await waitFor(() =>
      expect(localStorage.getItem('creator_onboarding_meta_resume')).toBeNull(),
    );
    expect(toastMock).toHaveBeenCalled();
  });
});
