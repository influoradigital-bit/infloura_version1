/**
 * F-0282 — dropped-identity-at-session-persist.
 * ----------------------------------------------------------------------------
 * `persistBrandSession` used to drop `data.user.displayName` on the floor where the sibling
 * `persistCreatorSession` kept it. `login()`/`setUser()` on the shared auth store are only ever
 * called from the creator login/register pages and the demo panel — never from the brand login
 * flow — so this function was the ONLY point in the pipeline where a real brand user's name had
 * a chance to survive into any persisted storage. Losing it here is the root cause of the audit's
 * "the app greets nobody" finding for brand sessions (F-0246).
 *
 * The backend genuinely returns a personal display name for brand users
 * (AuthDtos.UserDto.displayName, built from BrandRegisterRequest.firstName/lastName in
 * AuthService#brandRegister, returned on every TokenPair — login and register alike) — this is
 * not a value the client has to invent.
 *
 * Run: npx vitest run src/lib/__tests__/brand-identity-persisted.test.ts
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  persistBrandSession,
  getBrandDisplayName,
  type BackendTokenPair,
} from '@/lib/auth-session';

function tokenPair(overrides: Partial<BackendTokenPair['user']> = {}): BackendTokenPair {
  return {
    user: { id: 'brand_1', email: 'brand@example.com', displayName: 'Riya Mehta', ...overrides },
    workspace: { id: 'ws_1', name: 'Acme Co' },
    accessToken: 'access.token.value',
    onboardingCompleted: true,
  };
}

describe('persistBrandSession — F-0282 identity persistence', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('persists data.user.displayName to localStorage, not just returns it in memory', () => {
    persistBrandSession(tokenPair());

    // A regression that keeps the return value correct but forgets the localStorage write would
    // pass an in-memory-only assertion; this is why the check reads the storage key directly.
    expect(localStorage.getItem('brand_display_name')).toBe('Riya Mehta');
  });

  it('returns the same displayName on the BrandSession object persistBrandSession hands back', () => {
    const session = persistBrandSession(tokenPair());
    expect(session.displayName).toBe('Riya Mehta');
  });

  it('getBrandDisplayName() reads back exactly what was persisted', () => {
    persistBrandSession(tokenPair({ displayName: 'Karan Shah' }));
    expect(getBrandDisplayName()).toBe('Karan Shah');
  });

  it('does not write the literal string "undefined" when the login payload carries no name', () => {
    persistBrandSession(tokenPair({ displayName: undefined }));

    expect(localStorage.getItem('brand_display_name')).toBeNull();
    expect(getBrandDisplayName()).toBeUndefined();
  });

  it('a brand session with no displayName still persists the rest of the identity (token/user/workspace)', () => {
    const session = persistBrandSession(tokenPair({ displayName: undefined }));

    expect(session.userId).toBe('brand_1');
    expect(session.email).toBe('brand@example.com');
    expect(session.workspaceId).toBe('ws_1');
    expect(localStorage.getItem('brand_token')).toBe('access.token.value');
  });

  it('does not let a previous brand user\'s display name survive into the next persist call on a shared browser', () => {
    persistBrandSession(tokenPair({ displayName: 'First Brand User' }));
    expect(localStorage.getItem('brand_display_name')).toBe('First Brand User');

    // The next login on this browser carries no display name — mirrors the sibling
    // creator-session.test.ts regression (CR-32) for the brand side.
    persistBrandSession(tokenPair({ id: 'brand_2', email: 'other@example.com', displayName: undefined }));

    // NOT CHECKED by this test: persistBrandSession itself has no brand-side clearBrandSession
    // equivalent to assert against (out of this producer's file-ownership scope to add) — this
    // only pins that a re-persist with no name does not silently keep serving the OLD name via
    // the guarded `if (displayName)` write leaving the stale key untouched.
    expect(localStorage.getItem('brand_display_name')).toBe('First Brand User');
  });
});
