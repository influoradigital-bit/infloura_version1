/**
 * F-0459 review residual — closes the leak a fresh-context CTO review found in the F-0459 fix
 * itself: reading sessionStorage in the auth guard (F-0459's own change) exposed a pre-existing
 * gap in clearCreatorSession, which only ever cleared localStorage. A remember-me-off session
 * (creator_token in sessionStorage, per CR-121) survived logout whenever the real
 * api.auth.logout('creator') call was skipped — both real call sites gate it behind
 * isApiLive(), so this fires on every mock/demo-mode logout.
 *
 * This test seeds sessionStorage directly (the shape a remember-me-off login produces) and
 * asserts clearCreatorSession alone — with no api.auth.logout call at all — clears it. That is
 * deliberate: the fix must not depend on the logout call happening, because the whole point of
 * the leak was that call being skipped.
 */
import { describe, it, expect, beforeEach } from 'vitest';

import { clearCreatorSession } from '@/lib/auth-session';

describe('clearCreatorSession — sessionStorage (F-0459 residual)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('clears creator_token from sessionStorage, not just localStorage', () => {
    sessionStorage.setItem('creator_token', 'remember-me-off-token');

    clearCreatorSession();

    expect(sessionStorage.getItem('creator_token')).toBeNull();
  });

  it('also still clears creator_token from localStorage (remember-me-on case, unchanged)', () => {
    localStorage.setItem('creator_token', 'remember-me-on-token');

    clearCreatorSession();

    expect(localStorage.getItem('creator_token')).toBeNull();
  });
});
