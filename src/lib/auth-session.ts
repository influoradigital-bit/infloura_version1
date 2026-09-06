import type { User } from './types';

// ---------------------------------------------------------------------------
// F-0551 — in-memory access token store (live mode only)
// ---------------------------------------------------------------------------
//
// CEO ruling F-0551 (2026-09-05): the access token lives in memory only; the refresh token
// stays exactly where it already was, an HttpOnly cookie this SPA never reads (Kabir A1). This
// closes the bug class F-0459/F-0667 both came from — the access token sitting in TWO different
// JS-readable web stores (localStorage vs sessionStorage per "remember me", CR-121) with call
// sites that disagreed about which one to check.
//
// The store lives HERE, not in api.ts's HttpClient, for one reason: `persistBrandSession` below
// is the ONLY place a brand's real access token ever reaches the client at all — `mapBrandAuth`
// (src/lib/api.ts) never calls `HttpClient.setToken` for brand login/register the way
// `creator-login.tsx` explicitly does for creator (see that function's own doc comment) — so this
// module has to be able to write the live-mode slot directly. `api.ts` already imports FROM this
// module (`persistBrandSession`/`persistCreatorSession`); importing `isApiLive` the other way
// would make that a genuine cycle, so `isLiveMode` below is a deliberate one-line duplicate of
// `api.ts`'s `isApiLive`, the same way `clearCreatorSession`'s `meta_connection` cleanup
// deliberately duplicates api.ts's literal key rather than importing it (see that function's own
// comment) — both pairs stay in lockstep by construction (a single `import.meta.env` read),
// not by a shared import.
//
// Mock mode is deliberately untouched: nothing minted there (`mock_brand_token` /
// `mock_creator_token`) is ever a real, server-issued bearer credential — `assertMockAuthAllowed`
// (api.ts) fails closed before one could reach a production build — so there is no XSS blast
// radius to close, and changing it would only risk breaking dev/demo flows for zero security
// benefit.
type TokenRole = 'brand' | 'creator';

const isLiveMode = (): boolean => import.meta.env?.VITE_API_MODE === 'live';

const memoryAccessTokens: Partial<Record<TokenRole, string>> = {};

/**
 * Written under the SAME storage key the pre-F-0551 access token used to occupy
 * (`brand_token` / `creator_token`) — but this is NOT a credential, never the token itself. It
 * exists solely so two call sites outside this ticket's file boundary that gate on that key's
 * mere presence (never its value) keep working: `src/hooks/use-creator-identity.ts` and
 * `src/hooks/use-creator-unread-count.ts`, both of which do
 * `if (!localStorage.getItem('creator_token')) return;` and read the value nowhere. An XSS
 * payload reading this key gets an inert string it cannot replay as an `Authorization` header —
 * the real token is only ever in `memoryAccessTokens` above, never in any `Storage`.
 */
export const LIVE_SESSION_TOKEN_HINT = 'session-active';

/** The live-mode access token for `role`, or `null` if nothing has been set (or it was cleared). */
export function getMemoryAccessToken(role: TokenRole): string | null {
  return memoryAccessTokens[role] ?? null;
}

export function setMemoryAccessToken(role: TokenRole, token: string): void {
  memoryAccessTokens[role] = token;
}

export function clearMemoryAccessToken(role: TokenRole): void {
  delete memoryAccessTokens[role];
}

/**
 * Maps backend TokenPair (§4) to client session + localStorage keys used by the UI.
 */
export interface BackendTokenPair {
  user: {
    id: string;
    email: string;
    /**
     * F-0282 — populated for BOTH creator and brand users: AuthDtos.UserDto.displayName is not
     * flow-specific, and AuthService#brandRegister builds it from
     * BrandRegisterRequest.firstName/lastName exactly like the creator path builds its own. This
     * field being optional here is about JSON shape safety, not about brand accounts lacking a
     * personal name — they don't.
     */
    displayName?: string;
    userType?: string;
  };
  workspace?: {
    id: string;
    name?: string;
    slug?: string;
  };
  accessToken: string;
  refreshToken?: string;
  expiresIn?: number;
  onboardingCompleted?: boolean;
}

export interface BrandSession {
  token: string;
  userId: string;
  email: string;
  workspaceId?: string;
  onboardingComplete: boolean;
  /**
   * F-0282 — the brand user's personal display name (see BackendTokenPair.user.displayName doc
   * above for why this is genuinely populated server-side, not a name this SPA has to invent).
   */
  displayName?: string;
}

const ONBOARDING_KEY = 'brand_onboarding_complete';

/**
 * F-0282 — persists the identity fields from a brand login/register, mirroring
 * `persistCreatorSession` below.
 *
 * Before this fix, this function dropped `data.user.displayName` on the floor even though the
 * backend returns it on every brand `TokenPair` (login and register alike — see the doc comment
 * on `BackendTokenPair.user.displayName`). `login()`/`setUser()` on the shared auth store are
 * only ever called from the creator login/register pages and the demo panel — never from the
 * brand login flow — so this was the point in the pipeline where a real brand user's name had
 * its only chance to survive into any persisted storage. Losing it here is why the audit found
 * the app "greets nobody" for brand sessions.
 *
 * Same rule as the creator helper: the refresh token is NEVER written to JS-readable storage
 * (Kabir A1) — it arrives as an HttpOnly cookie.
 *
 * F-0551 — the ACCESS token is now subject to the same rule in live mode. Brand has no separate
 * `api.auth.setToken('brand', ...)` call site the way creator does (see `creatorLogin`'s doc in
 * api.ts), so this is the ONLY place a brand's real access token ever reaches the client at all,
 * and therefore where the memory-only rule has to be enforced for this role. See the
 * `memoryAccessTokens` file-level comment above for why the store lives in this module and how
 * mock mode stays untouched.
 */
export function persistBrandSession(data: BackendTokenPair): BrandSession {
  const token = data.accessToken;
  const userId = data.user.id;
  const email = data.user.email;
  const workspaceId = data.workspace?.id;
  const onboardingComplete = data.onboardingCompleted ?? false;
  const displayName = data.user.displayName;

  if (isLiveMode()) {
    setMemoryAccessToken('brand', token);
    localStorage.setItem('brand_token', LIVE_SESSION_TOKEN_HINT);
  } else {
    localStorage.setItem('brand_token', token);
  }
  // Kabir A1 — the refresh token is NEVER stored in JS-readable storage. It is delivered by the
  // backend as an HttpOnly cookie (see AuthCookieService) and this SPA never sees it. Storing it in
  // localStorage would turn any XSS into durable account takeover.
  localStorage.setItem('brand_user_id', userId);
  localStorage.setItem('brand_email', email);
  if (workspaceId) localStorage.setItem('brand_workspace_id', workspaceId);
  if (data.workspace?.name) localStorage.setItem('brand_company', data.workspace.name);
  if (displayName) localStorage.setItem('brand_display_name', displayName);

  if (onboardingComplete) {
    localStorage.setItem(ONBOARDING_KEY, 'true');
  } else {
    localStorage.removeItem(ONBOARDING_KEY);
  }

  // Legacy key used by older pages
  if (onboardingComplete) {
    localStorage.setItem('onboarding_complete', 'true');
  }

  return { token, userId, email, workspaceId, onboardingComplete, displayName };
}

/**
 * F-0282 — reads back what `persistBrandSession` stored. Mirrors `getCreatorSession` below.
 * Returns `undefined` when no brand display name was ever persisted (e.g. an older session
 * predating this fix, or a genuinely blank name) — callers must not fabricate a placeholder.
 */
export function getBrandDisplayName(): string | undefined {
  return localStorage.getItem('brand_display_name') || undefined;
}

export interface BrandUserSeed {
  id: string;
  email: string;
  displayName?: string | null;
}

/**
 * F-0320 — building a real brand `User` for the shared auth store, mirroring
 * `buildCreatorUser` (src/lib/creator-identity.ts, CR-06). F-0282 made `persistBrandSession`
 * keep the backend's real `displayName` and exposed `getBrandDisplayName()` to read it back,
 * but nothing in the brand login/register flow ever called `useAuthStore().login()`/`setUser()`
 * at all — only the creator flow does — so a live brand session's `user` stayed `null` forever
 * and the dashboard greeting kept rendering its 'there' fallback regardless of what the backend
 * actually knew. `brand-login.tsx`/`brand-register.tsx` call this (with the identity
 * `persistBrandSession` already wrote to localStorage) and pass the result to `login()`.
 *
 * Nothing here invents a name: if the backend genuinely sent no displayName, this renders an
 * honest empty string, matching `buildCreatorUser`'s contract — consumers must treat '' as "not
 * loaded", never fabricate a placeholder here.
 */
export function buildBrandUser(seed: BrandUserSeed): User {
  const now = new Date();
  return {
    id: seed.id,
    email: seed.email,
    userType: 'BRAND',
    status: 'ACTIVE',
    // The brand login/register endpoints don't report verification state on the TokenPair;
    // these are structural requirements of the `User` type, not claims about the account.
    emailVerified: false,
    phoneVerified: false,
    displayName: seed.displayName?.trim() || '',
    createdAt: now,
    updatedAt: now,
  };
}

// ---------------------------------------------------------------------------
// Creator session
// ---------------------------------------------------------------------------

export interface CreatorSession {
  userId: string;
  email: string;
  displayName?: string;
  onboardingComplete: boolean;
}

const CREATOR_ONBOARDING_KEY = 'creator_onboarding_completed';

/**
 * CR-06 — persists the identity fields from a creator login/register.
 *
 * There was no creator equivalent of `persistBrandSession`: the creator flow
 * stored the bare token and threw the rest of the `TokenPair` away. The auth
 * store was then only populated in mock mode, so on a live build `user` stayed
 * `null` forever and every `user?.x || 'demo default'` in the creator shell
 * rendered somebody else's identity.
 *
 * Same rule as the brand helper: the refresh token is NEVER written to
 * JS-readable storage (Kabir A1) — it arrives as an HttpOnly cookie.
 */
export function persistCreatorSession(data: BackendTokenPair): CreatorSession {
  const userId = data.user.id;
  const email = data.user.email;
  const displayName = data.user.displayName;
  const onboardingComplete = data.onboardingCompleted ?? false;

  localStorage.setItem('creator_user_id', userId);
  localStorage.setItem('creator_email', email);
  if (displayName) localStorage.setItem('creator_display_name', displayName);

  if (onboardingComplete) {
    localStorage.setItem(CREATOR_ONBOARDING_KEY, 'true');
  } else {
    localStorage.removeItem(CREATOR_ONBOARDING_KEY);
  }

  return { userId, email, displayName, onboardingComplete };
}

/**
 * Reads back what `persistCreatorSession` stored. Returns `null` unless a real
 * identity is present — callers must render a neutral placeholder in that case,
 * never a stand-in user.
 */
export function getCreatorSession(): CreatorSession | null {
  const userId = localStorage.getItem('creator_user_id');
  const email = localStorage.getItem('creator_email');
  if (!userId || !email) return null;
  return {
    userId,
    email,
    displayName: localStorage.getItem('creator_display_name') || undefined,
    onboardingComplete: localStorage.getItem(CREATOR_ONBOARDING_KEY) === 'true',
  };
}

export function clearCreatorSession(): void {
  // F-0551 — belt and suspenders, same spirit as the two removeItem calls right below: the
  // primary clear path is `api.auth.logout('creator')` -> `HttpClient.clearToken`, but that call
  // is gated behind `isApiLive()` at both real call sites (see the F-0459 residual note a few
  // lines down), so this must not depend on it running. Clearing here too means a skipped
  // `logout()` call can never leave a previous creator's live-mode access token reachable in
  // memory for the rest of this page's life.
  clearMemoryAccessToken('creator');
  localStorage.removeItem('creator_token');
  // F-0459 review residual: api.auth.logout('creator') is the only code that clears
  // creator_token out of sessionStorage (via http.clearToken), and both real logout call
  // sites gate that call behind isApiLive() — so in mock/demo mode the whole call, and with
  // it the sessionStorage clear, never runs. A remember-me-off session (creator_token in
  // sessionStorage per CR-121) survived a mock-mode logout: clearCreatorSession() only ever
  // touched localStorage, and F-0459's fixed auth guard now reads sessionStorage too, so the
  // leftover token kept the "logged out" tab looking logged in until the tab actually closed.
  sessionStorage.removeItem('creator_token');
  localStorage.removeItem('creator_user_id');
  localStorage.removeItem('creator_email');
  localStorage.removeItem('creator_display_name');
  localStorage.removeItem(CREATOR_ONBOARDING_KEY);
  // F-0165 — this five-key clear left the `meta_connection` mirror (api.ts's
  // META_CONNECTION_KEY, same literal 'meta_connection') behind. On a shared browser, the next
  // creator to log in was seeded with the PREVIOUS creator's Meta connection state (accountType,
  // granted scopes) until the live GET /meta/oauth/status re-verification on mount succeeded —
  // and useMetaConnection deliberately keeps last-known state if that call fails, so a failure
  // showed creator B creator A's "Connected" status under a generic "showing last known state"
  // caption. Same family as CR-32, which already reads this same stale mirror elsewhere.
  localStorage.removeItem('meta_connection');
}

export function getBrandOnboardingComplete(): boolean {
  return (
    localStorage.getItem(ONBOARDING_KEY) === 'true' ||
    localStorage.getItem('onboarding_complete') === 'true'
  );
}


export function hasBrandToken(): boolean {
  return !!localStorage.getItem('brand_token');
}
