/**
 * CR-121 — "Remember me" token storage.
 *
 * The checkbox on creator-login.tsx previously had no state or effect at all. The fix routes
 * `remember` through to `HttpClient.setToken`, which picks `localStorage` (survives closing the
 * browser) vs `sessionStorage` (cleared on tab/window close) for the ACCESS token. This pins the
 * storage-selection logic directly — the part most likely to silently regress, since every other
 * existing call site (brand login, register, the 401 token-refresh path) calls `setToken` with no
 * third argument and must keep behaving exactly as before.
 *
 * Run: npx vitest run src/lib/__tests__/remember-me-token-storage.test.ts
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { api } from '@/lib/api';

describe('remember-me token storage (CR-121)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('remember=true (or omitted, the default) writes to localStorage, not sessionStorage', () => {
    api.auth.setToken('creator', 'tok-remembered', true);
    expect(localStorage.getItem('creator_token')).toBe('tok-remembered');
    expect(sessionStorage.getItem('creator_token')).toBeNull();
  });

  it('remember=false writes to sessionStorage, not localStorage', () => {
    api.auth.setToken('creator', 'tok-session-only', false);
    expect(sessionStorage.getItem('creator_token')).toBe('tok-session-only');
    expect(localStorage.getItem('creator_token')).toBeNull();
  });

  it('a later call with no `remember` argument (the token-refresh path) preserves the prior preference — session-only login is not silently upgraded to persistent', () => {
    api.auth.setToken('creator', 'tok-1', false);
    expect(sessionStorage.getItem('creator_token')).toBe('tok-1');

    // Simulates fetchWithAuthRetry's silent renewal call: setToken(role, newToken), no 3rd arg.
    api.auth.setToken('creator', 'tok-2-refreshed');

    expect(sessionStorage.getItem('creator_token')).toBe('tok-2-refreshed');
    expect(localStorage.getItem('creator_token')).toBeNull();
  });

  it('every pre-existing call site (no remember argument at all) keeps defaulting to localStorage — unchanged prior behavior', () => {
    api.auth.setToken('brand', 'brand-tok');
    expect(localStorage.getItem('brand_token')).toBe('brand-tok');
    expect(sessionStorage.getItem('brand_token')).toBeNull();
  });

  it('brand and creator roles do not share or leak each other\'s remember preference', () => {
    api.auth.setToken('creator', 'creator-tok', false);
    api.auth.setToken('brand', 'brand-tok', true);

    expect(sessionStorage.getItem('creator_token')).toBe('creator-tok');
    expect(localStorage.getItem('brand_token')).toBe('brand-tok');
    expect(localStorage.getItem('creator_token')).toBeNull();
    expect(sessionStorage.getItem('brand_token')).toBeNull();
  });
});
