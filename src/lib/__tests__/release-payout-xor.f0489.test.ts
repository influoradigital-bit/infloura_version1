/**
 * F-0489 (unreachable-endpoint) — api.payments.releasePayout wire-shape spec.
 *
 * SYMPTOM: `POST /wallet/escrow/release` (EscrowController — already correct, not touched here)
 * requires exactly one of `milestoneId` / `escrowHoldId`. `api.payments.releasePayout` hardcoded
 * `{ milestoneId }`, so there was no way to send `escrowHoldId` at all — and a Meera-funded
 * campaign-level hold has no milestone row by construction (see `deal-payments-tab.tsx`'s
 * "Funding WITHOUT a milestoneId" comment), so it could never be released from any caller.
 *
 * Deliberately does NOT `vi.mock('@/lib/api', ...)` — that hoists file-wide in vitest and would
 * replace the very implementation under test with a stub. Stubs `fetch` instead (same technique
 * as `src/lib/payments-gate.test.tsx`) so the real `HttpClient.request` runs and the request body
 * that actually reaches the wire can be inspected.
 *
 * Run: npx vitest run src/lib/__tests__/release-payout-xor.f0489.test.ts
 */

import { describe, it, expect, vi, afterEach } from 'vitest';

describe('api.payments.releasePayout — F-0489 XOR request shape', () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  function stubFetchOk() {
    fetchSpy = vi.fn(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({ success: true, data: { escrowHoldId: 'esc_1', status: 'RELEASED' } }),
          { status: 200 },
        ),
      ),
    );
    vi.stubGlobal('fetch', fetchSpy);
  }

  async function loadLiveApi() {
    vi.resetModules();
    vi.stubEnv('VITE_API_MODE', 'live');
    vi.stubEnv('VITE_PAYMENTS_IN_ENABLED', 'true');
    vi.stubEnv('VITE_PAYOUTS_ENABLED', 'true');
    return import('@/lib/api');
  }

  function sentBody(): Record<string, unknown> {
    const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    return JSON.parse(init.body as string);
  }

  it('the escrowHoldId variant sends escrowHoldId and NOT milestoneId', async () => {
    stubFetchOk();
    const { api } = await loadLiveApi();

    await api.payments.releasePayout({ escrowHoldId: 'esc_campaign_pool_1' });

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const body = sentBody();
    expect(body).toEqual({ escrowHoldId: 'esc_campaign_pool_1' });
    expect('milestoneId' in body).toBe(false);
  });

  it('the existing bare-string call still sends milestoneId only — unchanged', async () => {
    stubFetchOk();
    const { api } = await loadLiveApi();

    await api.payments.releasePayout('ms_1');

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const body = sentBody();
    expect(body).toEqual({ milestoneId: 'ms_1' });
    expect('escrowHoldId' in body).toBe(false);
  });

  it('hits the real POST /wallet/escrow/release path', async () => {
    stubFetchOk();
    const { api } = await loadLiveApi();

    await api.payments.releasePayout({ escrowHoldId: 'esc_2' });

    const [url] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/wallet/escrow/release');
  });

  it('refuses an escrowHoldId request with an empty id rather than silently sending neither', async () => {
    stubFetchOk();
    const { api } = await loadLiveApi();

    expect(() => api.payments.releasePayout({ escrowHoldId: '' })).toThrow();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  /**
   * F-0652 (frontend regression) — the backend now REQUIRES `Idempotency-Key` on
   * POST /wallet/escrow/release (EscrowController, already correct). Before this fix,
   * releasePayout sent no such header at all, so every real call from either caller
   * (deal-payments-tab.tsx, brand-wallet.tsx) would 400 with MISSING_HEADER.
   */
  function sentHeaders(): Record<string, string> {
    const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    return init.headers as Record<string, string>;
  }

  it('sends a genuine, non-empty Idempotency-Key header for the milestoneId variant', async () => {
    stubFetchOk();
    const { api } = await loadLiveApi();

    await api.payments.releasePayout('ms_1');

    const headers = sentHeaders();
    expect(headers['Idempotency-Key']).toBeTruthy();
    expect(typeof headers['Idempotency-Key']).toBe('string');
    expect(headers['Idempotency-Key'].length).toBeGreaterThan(0);
  });

  it('sends a genuine, non-empty Idempotency-Key header for the escrowHoldId variant', async () => {
    stubFetchOk();
    const { api } = await loadLiveApi();

    await api.payments.releasePayout({ escrowHoldId: 'esc_3' });

    const headers = sentHeaders();
    expect(headers['Idempotency-Key']).toBeTruthy();
  });

  it('mints a DIFFERENT key on each separate call (fresh key per user action)', async () => {
    stubFetchOk();
    const { api } = await loadLiveApi();

    await api.payments.releasePayout('ms_1');
    await api.payments.releasePayout('ms_1');

    expect(fetchSpy).toHaveBeenCalledTimes(2);
    const [, firstInit] = fetchSpy.mock.calls[0] as [string, RequestInit];
    const [, secondInit] = fetchSpy.mock.calls[1] as [string, RequestInit];
    const firstKey = (firstInit.headers as Record<string, string>)['Idempotency-Key'];
    const secondKey = (secondInit.headers as Record<string, string>)['Idempotency-Key'];

    expect(firstKey).toBeTruthy();
    expect(secondKey).toBeTruthy();
    expect(firstKey).not.toBe(secondKey);
  });
});
