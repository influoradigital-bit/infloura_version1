/**
 * T-TSOFF-0920 — "TrendSpark off" is honest on the CREATOR daily-idea surface.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * With trend ingest off (`influora.trend-ingest.enabled=false`, the beta default) the `trends`
 * table is permanently empty, so `CreatorNudgeService.getSuggestion` could only ever return
 * `no_suggestion_today` — the SAME wire value it returns on a normal working day when nothing
 * scored above threshold. The SPA could not tell the two apart, and it showed: a creator with
 * Instagram connected was told "No new idea today — check back tomorrow" forever, and a creator
 * without it was told "Get your first daily idea / Connect Instagram" — a control that could not
 * possibly deliver what it offered.
 *
 * The fix is one server-authoritative switch (`GET /config/public` → `trendsEnabled`, derived
 * from `TrendIngestProperties.canProduceTrends()`), so this file asserts the hook's behaviour on
 * BOTH sides of it. Every "off" case is paired with the same scenario "on", because a gate that
 * only ever proves the off-state would also pass if it hid the feature permanently.
 *
 * Run: npx vitest run src/hooks/useDailySuggestion.trends-off.test.ts
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

function pinFlag(trendsEnabled: boolean) {
  return vi
    .spyOn(api.config, 'public')
    .mockResolvedValue({ requireEmailOtp: false, trendsEnabled });
}

/** A fully connected, fully tagged creator — the best case the feature has. */
function connectedCreator() {
  vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({
    connected: true,
    grantedScopes: ['instagram_basic', 'instagram_manage_insights', 'pages_show_list'],
  });
  return vi.spyOn(api.creatorCopilot, 'getTodaySuggestion').mockResolvedValue({
    status: 'ready',
    suggestion: {
      id: 'sug_1',
      theme: 'Skincare',
      headline: 'Try a GRWM reel',
      contentIdea: 'Get ready with me using the new serum',
      expiresAt: '2099-01-01T00:00:00.000Z',
    },
  } as never);
}

describe('useDailySuggestion — T-TSOFF-0920 server switch', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('OFF + connected creator: status is "disabled", never "dismissed"', async () => {
    pinFlag(false);
    const getToday = connectedCreator();

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('disabled'));
    // 'dismissed' is what the component renders as "check back tomorrow" — the false promise.
    expect(result.current.status).not.toBe('dismissed');
    expect(result.current.suggestion).toBeNull();
    // ...and the endpoint (which now answers 404 TRENDS_DISABLED) is never asked.
    expect(getToday).not.toHaveBeenCalled();
  });

  it('OFF + creator who has NOT connected Instagram: "disabled" outranks "idle"', async () => {
    pinFlag(false);
    const status = vi.spyOn(api.metaOAuth, 'status');

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('disabled'));
    // 'idle' is what renders IGConnectPrompt — the dead control.
    expect(result.current.status).not.toBe('idle');
    // Nothing is probed at all: a disabled surface costs zero requests.
    expect(status).not.toHaveBeenCalled();
  });

  it('OFF is never reported as an error, so no retry affordance is offered', async () => {
    pinFlag(false);
    connectedCreator();

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('disabled'));
    expect(result.current.status).not.toBe('error');
    expect(result.current.error).toBeNull();
  });

  it('ON: the identical connected creator gets the real suggestion (the gate is not a mute button)', async () => {
    pinFlag(true);
    const getToday = connectedCreator();

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.suggestion?.id).toBe('sug_1');
    expect(getToday).toHaveBeenCalledTimes(1);
  });

  it('ON + not connected: the connect prompt path is restored ("idle", not "disabled")', async () => {
    pinFlag(true);
    vi.spyOn(api.metaOAuth, 'status').mockResolvedValue({ connected: false, grantedScopes: [] });

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('idle'));
  });

  it('a /config/public read that fails is treated as OFF, never as ON', async () => {
    // `api.config.public` catches internally and returns trendsEnabled:false; this asserts the
    // hook honours that fallback rather than defaulting a missing answer to "show the feature".
    vi.spyOn(api.config, 'public').mockRejectedValue(new Error('network'));
    const getToday = connectedCreator();

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('disabled'));
    expect(getToday).not.toHaveBeenCalled();
  });

  it('a backend that omits trendsEnabled entirely is treated as OFF', async () => {
    // Frontend deployed ahead of the API: the field is absent from the JSON.
    vi.spyOn(api.config, 'public').mockResolvedValue({
      requireEmailOtp: false,
    } as never);
    connectedCreator();

    const { result } = renderHook(() => useDailySuggestion(), { wrapper });

    await waitFor(() => expect(result.current.status).toBe('disabled'));
  });
});
