/**
 * useMetaConnection — CR-105-followup regression gate.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Priya's independent review of CR-105 (accountType threading through the OAuth callback)
 * passed the callback artifact itself but found a HIGH residual one hop downstream: this
 * hook's `refresh()` reconciled `connected` straight from `GET /meta/oauth/status`, which
 * reports `connected: true` on the mere existence of a non-revoked token row — including for
 * a personal Instagram account, which `CreatorMetaOAuthService.connect()` deliberately stores
 * a token for anyway before branching on business/personal. So a personal-account creator who
 * lands on `connected: false` right after the OAuth callback (correctly, per CR-105) got
 * silently flipped back to `connected: true` the moment this hook's periodic
 * status-reconciliation ran (mount, or any tab-focus) — undoing CR-105's fix and re-trapping
 * them in the connect-prompt loop it exists to close, since `useDailySuggestion`'s
 * `requiresBusinessAccount` is `!connectionState.connected && accountType === 'personal'`.
 *
 * No prior test exercised this hook's reconciliation logic at all — `connected-accounts
 * .test.tsx` mocks `useMetaConnection` wholesale, so nothing crossed this exact seam.
 *
 * Run: npx vitest run src/hooks/creator/useMetaConnection.test.ts
 */

import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useMetaConnection } from './useMetaConnection';
import { api } from '@/lib/api';

describe('useMetaConnection — CR-105-followup: a personal account is never reconciled back to connected', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('status() reporting connected:true for a personal-account token row does NOT flip the reconciled state to connected', async () => {
    // Seed exactly what the OAuth callback correctly wrote for a personal account (CR-105):
    // connected:false, accountType:'personal'.
    api.metaOAuth.setLocalConnectionState(false, [], 'personal');

    // The backend status endpoint reports connected:true anyway — a real token row exists,
    // MetaConnectionService.getStatus doesn't distinguish personal from business.
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({
      connected: true,
      grantedScopes: ['instagram_basic'],
    });

    const { result } = renderHook(() => useMetaConnection());

    await waitFor(() => expect(result.current.loading).toBe(false));

    // This is the exact regression: without the fix, `connected` here would be `true`.
    expect(result.current.data.connected).toBe(false);
    expect(result.current.data.accountType).toBe('personal');

    // The mirror written back to localStorage must agree — a second reader (e.g. a fresh
    // mount of this same hook elsewhere) must not see a different answer.
    const mirrored = api.metaOAuth.getLocalConnectionState();
    expect(mirrored.connected).toBe(false);
    expect(mirrored.accountType).toBe('personal');
  });

  it('a business account with the same status() response reconciles to connected:true (the fix is scoped to personal only)', async () => {
    api.metaOAuth.setLocalConnectionState(false, [], 'business');
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({
      connected: true,
      grantedScopes: ['instagram_basic'],
    });

    const { result } = renderHook(() => useMetaConnection());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.data.connected).toBe(true);
    expect(result.current.data.accountType).toBe('business');
  });

  it('a real disconnect (status():connected:false) still clears accountType, regardless of account type', async () => {
    api.metaOAuth.setLocalConnectionState(true, ['instagram_basic'], 'personal');
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({ connected: false, grantedScopes: [] });

    const { result } = renderHook(() => useMetaConnection());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.data.connected).toBe(false);
    expect(result.current.data.accountType).toBeNull();
  });
});
