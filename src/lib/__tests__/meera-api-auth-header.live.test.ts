/**
 * F-0551 ship-blocker regression — BOTH API layers must send the real access token.
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * When the access token moved to memory-only (CEO ruling F-0551), `brand_token`/`creator_token`
 * stopped holding a credential. They now hold an inert sentinel — `LIVE_SESSION_TOKEN_HINT`
 * ('session-active') — kept ONLY so two out-of-scope hooks that gate on the key's *presence*
 * (`use-creator-identity.ts`, `use-creator-unread-count.ts`) keep working. They never read its
 * value.
 *
 * `src/lib/meera-api.ts` kept its own auth accessor reading that key directly, so every Meera REST
 * call and both voice endpoints would have gone out as `Authorization: Bearer session-active` in
 * live mode — the entire Meera surface anonymous, days after Phase A was signed off. A
 * fresh-context review caught it before it shipped; its probe read:
 *
 *     expected 'Bearer session-active' to be 'Bearer real-bearer-credential'   (brand AND creator)
 *
 * This is the repo's documented "two API layers" trap: `api.ts` and `meera-api.ts` each maintain
 * their own auth accessor, so a change to one silently misses the other. The memory store lives in
 * `auth-session.ts` specifically so BOTH layers resolve the same credential.
 *
 * This test asserts on the OUTGOING HEADER, not on the accessor — an accessor-level test would
 * still pass if a call site stopped using it.
 *
 * Must run under the live config: `vitest.config.ts` excludes *.live.test.ts.
 * Run: npx vitest run --config vitest.live.config.ts src/lib/__tests__/meera-api-auth-header.live.test.ts
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import {
  setMemoryAccessToken,
  clearMemoryAccessToken,
  LIVE_SESSION_TOKEN_HINT,
} from '@/lib/auth-session';

const REAL_BRAND_TOKEN = 'real-brand-bearer-credential';
const REAL_CREATOR_TOKEN = 'real-creator-bearer-credential';

function authHeaderFrom(call: unknown[]): string | undefined {
  const init = call[1] as RequestInit | undefined;
  const headers = (init?.headers ?? {}) as Record<string, string>;
  return headers.Authorization ?? headers.authorization;
}

describe('meera-api auth header (F-0551 ship-blocker)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    clearMemoryAccessToken('brand');
    clearMemoryAccessToken('creator');
    fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ success: true, data: {} }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    clearMemoryAccessToken('brand');
    clearMemoryAccessToken('creator');
  });

  it('sends the REAL brand token, never the storage sentinel', async () => {
    // Exactly the live-mode state persistBrandSession leaves behind: sentinel in storage,
    // real credential in memory.
    localStorage.setItem('brand_token', LIVE_SESSION_TOKEN_HINT);
    setMemoryAccessToken('brand', REAL_BRAND_TOKEN);

    const { meeraApi } = await import('@/lib/meera-api');
    await meeraApi.startSession().catch(() => undefined);

    expect(fetchMock).toHaveBeenCalled();
    const auth = authHeaderFrom(fetchMock.mock.calls[0]);
    expect(
      auth,
      'Meera went out with the inert storage sentinel instead of the real access token — every ' +
        'Meera REST call and both voice endpoints would be anonymous in live mode',
    ).toBe(`Bearer ${REAL_BRAND_TOKEN}`);
    expect(auth).not.toContain(LIVE_SESSION_TOKEN_HINT);
  });

  it('sends the REAL creator token, never the storage sentinel', async () => {
    localStorage.setItem('creator_token', LIVE_SESSION_TOKEN_HINT);
    setMemoryAccessToken('creator', REAL_CREATOR_TOKEN);

    const { meeraApi } = await import('@/lib/meera-api');
    await meeraApi.startSession('creator').catch(() => undefined);

    expect(fetchMock).toHaveBeenCalled();
    const auth = authHeaderFrom(fetchMock.mock.calls[0]);
    expect(auth).toBe(`Bearer ${REAL_CREATOR_TOKEN}`);
    expect(auth).not.toContain(LIVE_SESSION_TOKEN_HINT);
  });

  it('never attaches the sentinel as a credential when memory is empty', async () => {
    // Memory cleared (e.g. a cold load before silent refresh completes) but the presence-hint is
    // still in storage. Sending `Bearer session-active` here would be worse than sending nothing:
    // the server would reject a malformed credential rather than treating the call as anonymous.
    localStorage.setItem('brand_token', LIVE_SESSION_TOKEN_HINT);

    const { meeraApi } = await import('@/lib/meera-api');
    await meeraApi.startSession().catch(() => undefined);

    const auth = fetchMock.mock.calls.length ? authHeaderFrom(fetchMock.mock.calls[0]) : undefined;
    expect(auth ?? '').not.toContain(LIVE_SESSION_TOKEN_HINT);
  });
});
