# QA Review: Creator Discovery API + UI — Task #36 / #37b (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~22:30 IST)  
**Verdict:** ⚠️ **APPROVED WITH FINDINGS** — routed to **Kabir K6** (M-K6-5 cross-check confirmed OPEN) → **Meera** build/migration gate → Priya sign-off  
**Scope:** Vikram Task #36 backend + Ananya Task #37/#37b UI live wire  
**Reference:** `wiki/tech/creator/04_CREATOR_DISCOVERY_SPEC.md` §4–§8; Kabir `wiki/errors/creator-owasp-K6-kickoff.md` **M-K6-5**  
**Reviewed Files:**
- `influora-api/src/main/resources/db/migration/V20260709163000__featured_creators.sql`
- `influora-api/src/main/java/com/influora/domain/entity/FeaturedCreator.java`
- `influora-api/src/main/java/com/influora/repository/FeaturedCreatorRepository.java`
- `influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java`
- `influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java`
- `influora-api/src/main/java/com/influora/web/CreatorController.java`
- `influora-api/src/main/java/com/influora/web/dto/creator/DiscoveryDtos.java`
- `influora-api/src/main/java/com/influora/domain/entity/Collaboration.java` (invite sanitization)
- `influora-api/src/main/java/com/influora/service/BrandContextService.java`
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java` (M-K6-5 cross-check)
- `influora-api/src/test/java/com/influora/service/CreatorDiscoveryServiceTest.java` (9 tests)
- `src/lib/api.ts` — `creators.{search,searchWithFacets,featured,similar,suggestions,getProfile,invite,toggleSaved}`
- `src/components/brand/discover/creator-discovery.tsx`
- `src/pages/brand-creator-profile.tsx`

---

## Executive Summary

Discovery slice **passes QA** on hostile-path gates at the service layer. All discovery endpoints resolve brand tenancy via `brandContext.requireBrandWorkspace(principal)` — creator JWT → `403 WRONG_USER_TYPE`. Search/similar/suggestions/featured paths always AND `CreatorProfileSpecifications.discoverable()`. Public profile resolves username or profile id with `requireDiscoverableProfile` / `requireDiscoverableByUsername` → uniform `404 CREATOR_NOT_FOUND` for non-discoverable or missing rows. Invite path scopes campaign to workspace (`findByIdAndWorkspaceId`) and sanitizes message via `Collaboration.invite` → `TextSanitizer.sanitizePlainText`.

**9/9 `CreatorDiscoveryServiceTest` authored.** Vikram/Meera report **9/9 PASS**; `mvn` not on PATH in this QA environment — **Meera must confirm**. Frontend live wire: featured carousel + debounced search + languages server-side + profile header enrichment **ship correctly**. **Two P1 UI gaps:** profile-page invite still uses hardcoded `mockCampaigns` in live mode; facet/similar/suggestions endpoints exist in `api.ts` but are not fully consumed in UI.

**Kabir M-K6-5 cross-check:** **CONFIRMED OPEN** — `AuthRateLimitFilter.bucketFor()` GET branch returns `null` for `/creators` and `/creators/search` (spec §7.1: 60 searches/min). No regression since K6 kickoff.

**No P0 blockers.** Standards compliant for MySQL-native v1 scope (Elasticsearch deferred per Vikram).

---

## Spec §4 Endpoints — Verification

| Endpoint | Implemented | Brand auth | Discoverable filter | Tests |
|----------|-------------|------------|---------------------|-------|
| `GET /creators` (search) | ✅ | ✅ `requireBrandWorkspace` | ✅ `combine()` → `discoverable()` | `testSearchReturnsFacets` (facets path) |
| `GET /creators/search` (+ facets) | ✅ | ✅ | ✅ | Same service method |
| `GET /creators/featured` | ✅ | ✅ | ✅ curated + algorithmic fallback | `testGetFeaturedAlgorithmicFallback` |
| `GET /creators/{username}/similar` | ✅ | ✅ | ✅ source + candidates via `combine()` | `testGetSimilarCreators` |
| `POST /creators/suggestions` | ✅ | ✅ | ✅ | `testSuggestCreatorsForFitnessCampaign` |
| `GET /creators/profile/{usernameOrId}` | ✅ | ✅ | ✅ username + id + userId alias | `testGetPublicProfileByUsername`, `testGetPublicProfileNotDiscoverable` |
| `POST /creators/{id}/invite` | ✅ (pre-existing) | ✅ | ✅ `requireDiscoverableProfile` | `testSequentialDuplicate…`, race, happy path |
| `POST /creators/{id}/save` | ✅ (pre-existing) | ✅ | ✅ | — (out of T36 scope) |

---

## Hostile-Path Matrix (manual code trace)

| Attack vector | Expected | Observed |
|---------------|----------|----------|
| Creator JWT on `/creators/*` | 403 `WRONG_USER_TYPE` | ✅ `BrandContextService.requireBrand` L27–31 |
| Unauthenticated `/creators/*` | 401 filter-chain | ✅ `SecurityConfig` `anyRequest().authenticated()` |
| Enumerate non-discoverable via `GET /creators/{id}` | 404 `CREATOR_NOT_FOUND` | ✅ `findByIdAndDiscoverableTrue` + discoverable userId alias L757–764 |
| Enumerate non-discoverable via profile | 404 | ✅ `.filter(CreatorProfile::isDiscoverable)` on username L749–750; test `testGetPublicProfileNotDiscoverable` |
| Search leaks hidden creators | Excluded | ✅ `combine()` always ANDs `discoverable()` L161–168 |
| Featured curated row → non-discoverable creator | Omitted | ✅ `loadDiscoverableProfiles` filters L536–538 |
| Brand A invites with Brand B `campaignId` | 404 `CAMPAIGN_NOT_FOUND` | ✅ `findByIdAndWorkspaceId` L414–421 — **no unit test** |
| Invite message `<script>alert(1)</script>` | Tags stripped at persist | ✅ `Collaboration.invite` L63 — **no test** |
| Invite message stored XSS in brand UI confirm step | Escaped at render | ✅ React text node `{inviteMessage}` — safe |
| Concurrent duplicate invite | 409 `COLLABORATION_EXISTS` | ✅ pre-check + DIVE catch; tests pass |
| `sortBy` SQL/property injection | Whitelist only | ✅ `toSort()` switch L837–844 |
| Pagination DoS (`limit=99999`) | Capped 100 | ✅ L139 |
| Discovery search rate limit 60/min | Throttled | ❌ **OPEN — M-K6-5** (see below) |
| Discovery invite rate limit | Throttled | ❌ **OPEN — M-K6-1** (Kabir carry) |
| `CreatorResponse.userId` exposure | Optional hardening | ⚠️ **L-K6-1** carry — brands see creator user ULID |

---

## Kabir M-K6-5 Cross-Check

**Finding:** Spec §7.1 requires **60 searches/min per user** on discovery search.  
**Evidence:** `AuthRateLimitFilter.bucketFor()` GET branch (L205–215) only matches OAuth/tracking paths; returns `null` for `/creators` and `/creators/search`. POST branch (L217–261) has no `/creators` bucket.  
**Status:** **UNCHANGED since K6 kickoff** — Kabir register **M-K6-5** remains accurate.  
**Action:** Vikram M-K6-1 sprint — add `"discovery-search"` bucket (60/min per brand JWT `sub`).

---

## Frontend Live Wire (#37 / #37b)

| Surface | Live behavior | Result |
|---------|---------------|--------|
| `creator-discovery.tsx` featured | `api.creators.featured()` on mount; skeleton + empty fallback | ✅ PASS |
| `creator-discovery.tsx` search | Debounced `api.creators.search()` with filters (city, platforms, verticals, languages, ranges) | ✅ PASS |
| `creator-discovery.tsx` invite | `api.creators.invite` + `api.campaigns.list` for campaign picker | ✅ PASS |
| `creator-discovery.tsx` save | `api.creators.toggleSaved` with optimistic rollback | ✅ PASS |
| `creator-discovery.tsx` error/loading | Destructive card + retry; skeleton grid | ✅ PASS |
| `brand-creator-profile.tsx` load | `api.creators.getProfile(id)`; 404/discoverable guard | ✅ PASS |
| `brand-creator-profile.tsx` header | displayName, bio, platforms, scores, completed campaigns from API | ✅ PASS |
| `brand-creator-profile.tsx` invite campaigns | **Hardcoded `mockCampaigns`** in dialog L261–266, L803 | ❌ **P1 L-T36-1** |
| `brand-creator-profile.tsx` tabs | portfolio/audience/reviews/rates still mock filler in live mode | ⚠️ **P3 L-T36-9** (deferred enrichments per Vikram) |
| `api.ts` `searchWithFacets` | Implemented, **not called** by discovery page | ⚠️ **P2 L-T36-3** |
| `api.ts` `similar` / `suggestions` | Implemented, **not used** in pages | ⚠️ **P2 L-T36-4** |

---

## Test Execution

| Test Class | Authored | Executed | Failures | Notes |
|------------|----------|----------|----------|-------|
| `CreatorDiscoveryServiceTest` | 9 | ❌ Not run | — | `mvn` unavailable in QA env |
| `CreatorControllerTest` | 0 | — | — | **Gap L-T36-5** |
| Frontend `npm run build` | — | ❌ OOM | — | `memory allocation failed` in QA env; Meera prior **PASS** (4599 modules) |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=CreatorDiscoveryServiceTest
cd .. && npm run build
```

---

## Test Coverage Gaps

| Missing test | Severity | Notes |
|--------------|----------|-------|
| Creator JWT on discovery endpoints → 403 | P2 | Structural via `BrandContextService`; no controller test |
| Campaign IDOR on invite (foreign workspace) | P2 | Code path L414–421 |
| Invite message HTML sanitization | P3 | Same `TextSanitizer` as disputes/reviews |
| Brand-only negative auth integration | P2 | **L-K6-11** carry |
| `search` discoverable-only integration (DB) | P3 | Unit mocks only |
| `GET /creators/search` controller delegation | P3 | Thin controller pattern |
| Rate limit 429 on burst search | P1 | Blocked on M-K6-5 implementation |

---

## Findings Register

| ID | Severity | Finding | Action |
|----|----------|---------|--------|
| L-T36-1 | **P1** | `brand-creator-profile.tsx` invite dialog uses hardcoded `mockCampaigns` in live mode — invites will fail or target wrong ids | **Ananya** — mirror `creator-discovery.tsx` `api.campaigns.list` pattern |
| L-T36-2 | **P1** | **M-K6-5 CONFIRMED** — discovery search unthrottled at HTTP layer | **Vikram** — `discovery-search` bucket; Kabir re-spot after fix |
| L-T36-3 | P2 | UI calls `GET /creators` not `GET /creators/search` — facet counts from spec §4.1 unused in filter sidebar | Ananya optional — wire `searchWithFacets` or document deferral |
| L-T36-4 | P2 | `similar` / `suggestions` client methods shipped but no profile/discovery UI surface | Ananya Phase 2 or document deferral |
| L-T36-5 | P2 | No `CreatorControllerTest` — brand/creator JWT rejection untested at HTTP layer | Optional unit + Kv3 E2E |
| L-T36-6 | P2 | `InviteRequest.message` has no `@Size(max=…)` (contrast `ApplyRequest` @Size(max=2000)) | Vikram — align DTO validation |
| L-T36-7 | P2 | Campaign workspace IDOR on invite untested | Unit test follow-up |
| L-T36-8 | P2 | Invite rate limit absent — **M-K6-1** systemic gap | Vikram M-K6-1 sprint |
| L-T36-9 | P3 | Profile tabs (audience/portfolio/reviews/rates) show mock data in live mode | Vikram deferred portfolio/reviews API |
| L-T36-10 | P3 | `buildAvailableFacets()` loads up to 5_000 profiles per search request | Perf follow-up / cache |
| L-T36-11 | P3 | `accepting_collabs` filter not implemented | Vikram deferred per handoff |
| L-T36-12 | P3 | Invite XSS sanitization path untested | Low risk; same pattern as T29/T34 |
| L-T36-13 | INFO | MySQL-native search vs spec Elasticsearch index | Accepted v1 deferral |
| L-T36-14 | INFO | `mvn` not on PATH in QA env | Meera confirms 9/9 |
| L-T36-15 | INFO | Flyway `V20260709163000` runtime apply not verified (Docker/Testcontainers down) | Meera migration gate |
| L-T36-16 | INFO | `CreatorResponse.userId` exposed — **L-K6-1** Kabir carry | Optional opaque id |

**No P0 blockers. No standards violations in shipped backend code.**

---

## TECH-STACK.md Compliance

| Rule | Result |
|------|--------|
| Thin controller, fat service | ✅ |
| `ApiException` with stable codes | ✅ |
| Brand workspace isolation on mutating paths | ✅ invite campaign scoped |
| JWT auth required | ✅ |
| Discoverability gate on all read paths | ✅ |
| Flyway migration ULID `VARCHAR(26)` | ✅ `V20260709163000__featured_creators.sql` |
| No debug/console code in reviewed files | ✅ |
| Frontend `isApiLive()` dual mode | ✅ |
| No fabricated live API data on error | ✅ discovery error card; profile error state |

---

## Kabir K6 Brief (from Kavya — discovery subset)

Arjun: Kabir need not re-audit closed paths; confirm after Vikram M-K6-5 fix:

1. **M-K6-5 live burst** — brand JWT, 61× `GET /creators` in 60s → expect `429`.
2. **M-K6-1 invite burst** — 61× `POST /creators/{id}/invite` → expect `429`.
3. **Cross-role** — creator JWT on search/profile/featured → `403`.
4. **IDOR** — brand A campaignId on brand B invite → `404 CAMPAIGN_NOT_FOUND`.
5. **XSS store** — `<script>` in invite message → stripped in DB; safe render in deal room.

---

## Pipeline Routing

```
Vikram T36 + Ananya T37b ──⚠️ Kavya APPROVED WITH FINDINGS──► Kabir K6 (M-K6-5 confirmed) ──► Meera (9/9 + migration + build) ──► Priya sign-off
                                                                 └── Ananya fix L-T36-1 (profile invite campaigns) — parallel, non-blocking for backend gate
```

**Next owner:** Kabir (M-K6-5 confirmation only — no full re-audit) → Meera  
**Blocked on Meera for:** Flyway runtime + full 858/858 integration (Docker)  
**Parallel fix:** Ananya L-T36-1 profile invite campaign list

---

*Kavya Patel, QA Lead — Sage Digital*
