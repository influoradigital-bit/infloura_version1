/**
 * Maps backend TokenPair (§4) to client session + localStorage keys used by the UI.
 */
import type { Role } from './api';

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

export function getBrandOnboardingComplete(): boolean {
  return (
    localStorage.getItem(ONBOARDING_KEY) === 'true' ||
    localStorage.getItem('onboarding_complete') === 'true'
  );
}

export function clearBrandSession(): void {
  localStorage.removeItem('brand_token');
  localStorage.removeItem('brand_refresh_token');
  localStorage.removeItem('brand_user_id');
  localStorage.removeItem('brand_email');
  localStorage.removeItem('brand_workspace_id');
  localStorage.removeItem('brand_company');
  localStorage.removeItem(ONBOARDING_KEY);
  localStorage.removeItem('onboarding_complete');
}

export function hasBrandToken(): boolean {
  return !!localStorage.getItem('brand_token');
}
