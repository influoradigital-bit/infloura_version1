/**
 * FE <-> BE contract fidelity for creator discovery (Ananya, F-0408/F-0431/F-0435/F-0464).
 *
 * F-0408/F-0431 — verified against the real code before writing this: `creatorSearchQuery`
 * (src/lib/api.ts) already sends sortBy/languages/minEngagementRate/maxEngagementRate/isVerified
 * (creator-discovery.tsx already passes all of them, tagged F-0405/F-0431/F-0408 at the call
 * site) and `categories` is deliberately folded into `verticals` — CreatorDiscoveryService's
 * `mergeCategoryFilters` unions both params server-side, so sending the selection via one of them
 * already covers the other. These findings were true against an older revision of this file and
 * are already fixed; this test pins the CURRENT (fixed) contract down so it cannot regress.
 *
 * F-0435 — `mapCreatorFromApi` used to fall back to a `city` field for `location` when
 * `row.location` was falsy. `CreatorController.search`/`get` both return
 * `CreatorDtos.CreatorResponse`, which has a `location` field and NO `city` field at all, so the
 * fallback could never legitimately fire — it was dead code that would have silently reactivated
 * itself the moment any future response happened to carry a `city` key for an unrelated reason.
 * Removed.
 *
 * F-0464 — `CreatorProfileSelfResponse.engagementRate` (GET /me/creator-profile) was typed as a
 * plain non-null `number`, but the server column (`CreatorProfile.engagementRate`, a
 * `BigDecimal`) is nullable and is never initialised at profile creation — only
 * `applyAggregatedStats` sets it, once a platform actually syncs. A brand-new creator who has
 * not connected a platform yet gets `null` here on the real server. Fixed to `number | null`.
 *
 * Run: npx vitest run --config vitest.live.config.ts src/lib/__tests__/creator-discovery-dto-fidelity.live.test.ts
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { CreatorProfileSelfResponse } from '@/lib/api';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('creator discovery query + dto fidelity', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let api: typeof import('@/lib/api').api;

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    ({ api } = await import('@/lib/api'));
    // Non-JWT mock-mode token: skips the proactive refresh check entirely (decodeJwtExpSeconds
    // returns null for it), so only the ONE fetch call under test happens.
    api.auth.setToken('brand', 'mock_brand_token');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('F-0408/F-0431 — sortBy and every documented discovery filter reach the outgoing query string', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: [], meta: { page: 1, limit: 20, total: 0, hasMore: false } }),
    );

    await api.creators.search({
      q: 'fashion',
      city: 'Mumbai',
      platforms: ['INSTAGRAM'],
      verticals: ['beauty'],
      languages: ['hindi'],
      minFollowers: 1000,
      maxFollowers: 500000,
      minRate: 1000,
      maxRate: 50000,
      minEngagementRate: 1,
      maxEngagementRate: 10,
      isVerified: true,
      sortBy: 'engagement',
      page: 2,
      limit: 20,
    });

    const call = fetchMock.mock.calls.find(([u]) => String(u).includes('/creators'));
    expect(call, 'no request to /creators was made').toBeTruthy();
    const url = new URL(String(call![0]));

    expect(url.searchParams.get('sortBy')).toBe('engagement');
    expect(url.searchParams.get('isVerified')).toBe('true');
    expect(url.searchParams.get('languages')).toBe('hindi');
    expect(url.searchParams.get('minEngagementRate')).toBe('1');
    expect(url.searchParams.get('maxEngagementRate')).toBe('10');
    // The server unions `verticals` + `categories` into one filter (mergeCategoryFilters), so
    // the client only ever needs to send one of the two names — this is that name.
    expect(url.searchParams.get('verticals')).toBe('beauty');
  });

  it('F-0435 — a creator row with no `location` never picks up a stray `city` field (dead fallback removed)', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          success: true,
          data: [
            {
              id: 'c1',
              userId: 'u1',
              displayName: 'Test Creator',
              // `location` genuinely absent, as a server that never set it would send.
              // `city` is NOT a field CreatorDtos.CreatorResponse has — this simulates the old
              // dead fallback's cast finding a stray value and must be ignored.
              city: 'ShouldNeverAppear',
              categories: [],
              platforms: [],
              totalFollowers: 0,
              engagementRate: 0,
              isVerified: false,
              portfolioItems: [],
            },
          ],
          meta: { page: 1, limit: 20, total: 1, hasMore: false },
        },
      ),
    );

    const result = await api.creators.search({});

    expect(result.creators).toHaveLength(1);
    expect(result.creators[0].location).not.toBe('ShouldNeverAppear');
    expect(result.creators[0].location).toBeUndefined();
  });

  it('F-0464 — CreatorProfileSelfResponse.engagementRate is honestly nullable (server column is nullable, never initialised at profile creation)', () => {
    // Compiles only because the type is `number | null`, not a plain `number` — a creator who
    // never synced a platform gets exactly this shape back from the real server.
    const neverSynced: CreatorProfileSelfResponse = {
      id: 'cr_new',
      userId: 'u_new',
      displayName: 'Brand New Creator',
      username: null,
      bio: null,
      avatarUrl: null,
      coverImageUrl: null,
      city: null,
      phone: null,
      categories: [],
      languages: [],
      contentStyles: [],
      platforms: [],
      rateMin: null,
      rateMax: null,
      currency: 'INR',
      discoverable: false,
      verified: false,
      totalFollowers: 0,
      engagementRate: null,
      onboardingComplete: false,
      profileCompleteness: 10,
    };

    expect(neverSynced.engagementRate).toBeNull();
  });
});
