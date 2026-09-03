/**
 * useDailySuggestion — F-0480 regression gate: the backend, not localStorage, decides
 * whether Instagram is connected.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The hook gated the whole Co-pilot surface on `api.metaOAuth.getLocalConnectionState()` —
 * a localStorage mirror that only the OAuth callback page in the SAME browser ever set to
 * `connected: true`, and that every logout wipes (auth-session.ts F-0165). So a creator who
 * connected Instagram on their phone, or on Settings and then logged out and back in, opened
 * Co-pilot and was told to "Connect Instagram" indefinitely: the backend held a live token,
 * the mirror said false, and nothing in this hook ever asked the backend. Settings had been
 * fixed for the same class of bug (CR-107, `useMetaConnection`); Co-pilot had not.
 *
 * Reported live 2026-09-02: "we connected account from dashboard also here but still asking
 * connect account, refresh" (screenshot of /creator/copilot showing IGConnectPrompt).
 *
 * Run: npx vitest run src/hooks/useDailySuggestion.test.ts
 */

import * as React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useDailySuggestion } from './useDailySuggestion';
import { api } from '@/lib/api';

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client }, children);
}

const READY_SUGGESTION = {
  status: 'ready' as const,
  suggestion: {
    id: 'sug_1',
    theme: 'Skincare',
    headline: 'Try a GRWM reel',
    contentIdea: 'Get ready with me using the new serum',
    expiresAt: '2099-01-01T00:00:00.000Z',
  },
};

describe('useDailySuggestion — F-0480: connection is verified against the backend', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('empty localStorage mirror + backend connected:true → NOT idle; the suggestion is fetched', async () => {
    // The exact live state: token row exists server-side, this browser has no mirror
    // (other device, or logged out and back in).
    expect(api.metaOAuth.getLocalConnectionState().connected).toBe(false);
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({
      connected: true,
      grantedScopes: ['instagram_basic', 'instagram_manage_insights', 'pages_show_list'],
    });
    const getToday = vi
      .spyOn(api.creatorCopilot, 'getTodaySuggestion')
      .mockResolvedValue(READY_SUGGESTION as never);

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    // Before the fix this resolved to 'idle' synchronously and stayed there forever.
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.suggestion?.id).toBe('sug_1');
    expect(getToday).toHaveBeenCalledTimes(1);

    // The verified answer is written back to the mirror so every other reader agrees.
    expect(api.metaOAuth.getLocalConnectionState().connected).toBe(true);
  });

  it('while the first verification is in flight with an unconnected seed → loading + verifyingConnection, never idle', async () => {
    let resolveStatus: (v: { connected: boolean; grantedScopes: string[] }) => void = () => {};
    vi.spyOn(api.metaOAuth, 'status').mockReturnValue(
      new Promise((res) => {
        resolveStatus = res;
      }),
    );
    vi.spyOn(api.creatorCopilot, 'getTodaySuggestion').mockResolvedValue(READY_SUGGESTION as never);

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    // No flash of the connect prompt (which the section renders for 'idle').
    expect(result.current.status).toBe('loading');
    expect(result.current.verifyingConnection).toBe(true);

    resolveStatus({ connected: true, grantedScopes: [] });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.verifyingConnection).toBe(false);
  });

  it('stale mirror connected:true + backend connected:false (revoked) → idle, mirror corrected', async () => {
    api.metaOAuth.setLocalConnectionState(true, ['instagram_basic'], 'business');
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({ connected: false, grantedScopes: [] });
    const getToday = vi.spyOn(api.creatorCopilot, 'getTodaySuggestion').mockResolvedValue(READY_SUGGESTION as never);

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('idle'));
    expect(result.current.requiresBusinessAccount).toBe(false);
    expect(api.metaOAuth.getLocalConnectionState().connected).toBe(false);
    // The seed briefly enabled the query before the backend answered — that's acceptable
    // (the old behaviour) — but it must not be re-fetched once the truth says disconnected.
    expect(getToday.mock.calls.length).toBeLessThanOrEqual(1);
  });

  it('backend status call fails → falls back to the last-known mirror instead of spinning forever', async () => {
    vi.spyOn(api.metaOAuth, 'status').mockRejectedValue(new Error('network'));
    vi.spyOn(api.creatorCopilot, 'getTodaySuggestion').mockResolvedValue(READY_SUGGESTION as never);

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.verifyingConnection).toBe(false));
    expect(result.current.status).toBe('idle');
  });

  it('personal account: backend connected:true is still NOT a usable connection (CR-105 rule preserved)', async () => {
    api.metaOAuth.setLocalConnectionState(false, [], 'personal');
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({ connected: true, grantedScopes: ['instagram_basic'] });
    const getToday = vi.spyOn(api.creatorCopilot, 'getTodaySuggestion').mockResolvedValue(READY_SUGGESTION as never);

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.verifyingConnection).toBe(false));
    expect(result.current.status).toBe('idle');
    expect(result.current.requiresBusinessAccount).toBe(true);
    expect(getToday).not.toHaveBeenCalled();
  });
});
