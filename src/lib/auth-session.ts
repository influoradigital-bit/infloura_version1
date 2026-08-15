/**
 * Maps backend TokenPair (§4) to client session + localStorage keys used by the UI.
 */
export interface BackendTokenPair {
  user: {
    id: string;
    email: string;
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
}

const ONBOARDING_KEY = 'brand_onboarding_complete';

export function persistBrandSession(data: BackendTokenPair): BrandSession {
  const token = data.accessToken;
  const userId = data.user.id;
  const email = data.user.email;
  const workspaceId = data.workspace?.id;
  const onboardingComplete = data.onboardingCompleted ?? false;

  localStorage.setItem('brand_token', token);
  // Kabir A1 — the refresh token is NEVER stored in JS-readable storage. It is delivered by the
  // backend as an HttpOnly cookie (see AuthCookieService) and this SPA never sees it. Storing it in
  // localStorage would turn any XSS into durable account takeover.
  localStorage.setItem('brand_user_id', userId);
  localStorage.setItem('brand_email', email);
  if (workspaceId) localStorage.setItem('brand_workspace_id', workspaceId);
  if (data.workspace?.name) localStorage.setItem('brand_company', data.workspace.name);

  if (onboardingComplete) {
    localStorage.setItem(ONBOARDING_KEY, 'true');
  } else {
    localStorage.removeItem(ONBOARDING_KEY);
  }

  // Legacy key used by older pages
  if (onboardingComplete) {
    localStorage.setItem('onboarding_complete', 'true');
  }

  return { token, userId, email, workspaceId, onboardingComplete };
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
  localStorage.removeItem('creator_token');
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
