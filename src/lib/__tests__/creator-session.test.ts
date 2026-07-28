/**
 * CR-06 / CR-32 — a creator logout must leave nothing of that creator behind.
 *
 * CR-06 removed the `'Creator Account'` / `'@priya_sharma'` literals because shipping one
 * user's identity as another user's fallback is an identity-leak pattern. CR-32 is the same
 * leak through a different door: `creator-settings.tsx`'s logout removed only
 * `creator_token`, so `creator_user_id` / `creator_email` / `creator_display_name` survived.
 * `persistCreatorSession` writes the display name only when the login response carries one,
 * so the next creator to sign in on that browser *without* one inherited the previous
 * creator's name in the shell.
 *
 * The property that actually protects against this is the one asserted below: whatever
 * `persistCreatorSession` writes, `clearCreatorSession` removes. A future field added to one
 * and forgotten in the other reopens CR-32 on a new key, and this fails.
 *
 * Run: npx vitest run src/lib/__tests__/creator-session.test.ts
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  persistCreatorSession,
  getCreatorSession,
  clearCreatorSession,
  type BackendTokenPair,
} from '@/lib/auth-session';

function tokenPair(overrides: Partial<BackendTokenPair['user']> = {}): BackendTokenPair {
  return {
    user: { id: 'cr_1', email: 'tejas@example.com', displayName: 'Tejas Creater', ...overrides },
    accessToken: 'access.token.value',
    onboardingCompleted: true,
  };
}

describe('creator session lifecycle', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('clears every key a login writes, plus the token (CR-32)', () => {
    localStorage.setItem('creator_token', 'access.token.value');
    persistCreatorSession(tokenPair());

    // Sanity: the keys really are there, so the assertion below cannot pass vacuously.
    const written = Object.keys(localStorage).filter((k) => k.startsWith('creator_'));
    expect(written.length).toBeGreaterThan(0);

    clearCreatorSession();

    expect(Object.keys(localStorage).filter((k) => k.startsWith('creator_'))).toEqual([]);
    expect(getCreatorSession()).toBeNull();
    // Named explicitly: this is what `creator-settings.tsx` used to do by hand, and the
    // reason its logout is now a single `clearCreatorSession()` call rather than a
    // `removeItem` that only ever covered the token.
    expect(localStorage.getItem('creator_token')).toBeNull();
  });

  it('does not let a previous creator’s display name survive into the next session', () => {
    persistCreatorSession(tokenPair());
    expect(localStorage.getItem('creator_display_name')).toBe('Tejas Creater');

    clearCreatorSession();
    // The next creator has no display name on their token pair — the exact case where the
    // stale key used to show through, because persistCreatorSession only writes this key
    // `if (displayName)` and would leave the old value untouched.
    persistCreatorSession(tokenPair({ id: 'cr_2', email: 'other@example.com', displayName: undefined }));

    expect(getCreatorSession()).toMatchObject({ userId: 'cr_2', email: 'other@example.com' });
    expect(getCreatorSession()?.displayName).toBeUndefined();
    expect(localStorage.getItem('creator_display_name')).toBeNull();
  });

  it('clears the onboarding flag when the server says onboarding is incomplete', () => {
    persistCreatorSession(tokenPair());
    expect(localStorage.getItem('creator_onboarding_completed')).toBe('true');

    // Why this matters: `creator-login.tsx` routes on
    // `result.onboardingComplete || localStorage.getItem('creator_onboarding_completed')`.
    // That `||` is only safe because persist actively REMOVES the flag on false — otherwise
    // a shared browser would send an un-onboarded creator straight past onboarding.
    persistCreatorSession({ ...tokenPair({ id: 'cr_2' }), onboardingCompleted: false });
    expect(localStorage.getItem('creator_onboarding_completed')).toBeNull();
  });
});
