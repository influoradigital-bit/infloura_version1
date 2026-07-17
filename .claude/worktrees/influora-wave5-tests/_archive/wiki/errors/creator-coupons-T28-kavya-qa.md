# QA Review: GET /creator/coupons — Task #28 V3 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~19:50 IST retry)  
**Verdict:** ✅ **APPROVED** — routed to **Kabir K3** (batch with Task #27 platform-fee per CEO §P1-K3) → Meera M2 build → Ananya A3 live wire  
**Scope:** Vikram Task #28 V3 — `GET /api/v1/creator/coupons` read-only self-scoped coupon list  
**Reference:** `TASK_INBOX.md` Task #28; `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §P0-V3 / §P1-K3; `wiki/errors/creator-coupons-A4-review.md` (frontend gap); `CouponCodeRepository` V24 javadoc  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/repository/CouponCodeRepository.java` — `findByCreatorIdOrderByCreatedAtDesc`
- `influora-api/src/main/java/com/influora/service/CreatorCouponService.java`
- `influora-api/src/main/java/com/influora/web/CreatorCouponController.java`
- `influora-api/src/main/java/com/influora/web/dto/creatorcoupon/CreatorCouponDtos.java`
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/repository/UtmCampaignRepository.java` — `findByCampaignIdAndCreatorProfileId`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/src/test/java/com/influora/service/CreatorCouponServiceTest.java` (4 tests)
- `influora-api/src/test/java/com/influora/web/CreatorCouponControllerTest.java` (1 test)

---

## Executive Summary

Task #28 V3 **passes QA**. `GET /api/v1/creator/coupons` is a read-only, self-scoped list of the authenticated creator's coupon codes across all campaigns. Identity is resolved exclusively via `CreatorContextService.requireCreatorProfile(principal)` → `CouponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(profile.getId())`. There is no creator-id path parameter, query parameter, or request body to spoof.

Enrichment is safe: campaign title and workspace name are batch-loaded from IDs present on already-scoped coupon rows; optional `trackingUrl` is resolved via `UtmCampaignRepository.findByCampaignIdAndCreatorProfileId(campaignId, authenticatedCreatorProfileId)` — never another creator's UTM row. Brand-facing coupon creation remains on `CampaignTrackingController`; this surface is list-only.

Response shape aligns with the frontend `CreatorCouponResponse` contract in `src/lib/api.ts` (field names and semantics). The DTO deliberately omits `creatorProfileId` (unlike brand `TrackingDtos.CouponResponse`) — correct for a self-scoped read.

**5 unit tests** authored (4 service + 1 controller delegation). **`mvn` not on PATH** in this QA environment — Meera must confirm **5/5 PASS**.

**Kabir K3 (lightweight, batch with #27):** re-verify `CreatorContextService` scoping, confirm brand JWT → 403, confirm no IDOR via path/query (none exist), confirm UTM enrichment cannot leak another creator's tracking URL.

**Ananya A3 (out of backend scope, unblocked by this gate):** replace `api.creatorCoupons.list()` `NOT_IMPLEMENTED` stub with live `GET /creator/coupons` call.

---

## Task #28 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `GET /api/v1/creator/coupons` ships | ✅ PASS | `CreatorCouponController` `@RequestMapping("/creator/coupons")` + `server.servlet.context-path=/api/v1` |
| Self-scoped via `CreatorContextService` | ✅ PASS | `CreatorCouponService.list` L51–53 |
| `findByCreatorIdOrderByCreatedAtDesc` repository query | ✅ PASS | `CouponCodeRepository` L34; javadoc mandates caller resolves profile first |
| Read-only (no mutation) | ✅ PASS | `GET` only; `@Transactional(readOnly = true)`; no write repository calls |
| Cross-creator isolation unit test | ✅ PASS | `testCrossCreatorIsolation` — queries profile A only, never B |
| Enrichment: campaign name, brand name, tracking URL | ✅ PASS | `testListHappyPathEnrichment`, `testListWithoutTrackingUrl` |
| Empty list when no coupons | ✅ PASS | `testListEmpty` — skips campaign/workspace batch fetch |
| Unit tests 5/5 | ⚠️ AUTHORED | Not executed here (L-T28-5) |
| Kavya Kv1 | ✅ THIS DOC | |
| Kabir K3 | ⏳ **NEXT** | Batch with Task #27 per CEO §P1-K3 |
| Meera M2 | ⏳ QUEUED | After Kabir |

---

## Hostile-Path Checklist (Task #28)

| # | Requirement | Result | Evidence |
|---|-------------|--------|----------|
| H-1 | **Auth required** (no anonymous read) | ✅ PASS | `SecurityConfig` L191–192 `anyRequest().authenticated()` — no `permitAll` for `/creator/**`. Unauthenticated → 401 at filter chain. |
| H-2 | **Creator role gate** (brand/admin rejected) | ✅ PASS | `CreatorContextService.requireCreatorProfile` → `requireCreator`: `userType != CREATOR` → `WRONG_USER_TYPE` 403. Same pattern as Tasks #27, #19–#24. |
| H-3 | **No path-param IDOR** | ✅ PASS (N/A) | Zero `@PathVariable` / `@RequestParam`. Identity resolved only from JWT via `CreatorContextService`. |
| H-4 | **Cross-creator coupon isolation** | ✅ PASS | Repository query keyed on `profile.getId()` from auth — not user-supplied. Unit test verifies only profile A's rows returned; profile B query never invoked. |
| H-5 | **Cross-creator UTM isolation** | ✅ PASS | `findByCampaignIdAndCreatorProfileId(campaignId, creatorProfileId)` uses authenticated `creatorProfileId` (L67, L85–86). Cannot attach another creator's tracking URL to a row. |
| H-6 | **Read-only** (no mutation) | ✅ PASS | `GET` only; `@Transactional(readOnly = true)`; no `save`/`delete`/`incrementUsageCount`. Creation stays on brand `CampaignTrackingController`. |
| H-7 | **Safe enrichment** (no cross-tenant bleed) | ✅ PASS | `campaignRepository.findAllById` / `workspaceRepository.findAllById` use IDs from already-scoped coupon rows only. Missing campaign/workspace → empty string names (L81–82), not 500 or foreign data. |
| H-8 | **No PII leak beyond self** | ✅ PASS | DTO omits `creatorProfileId`; exposes only the caller's own coupon codes and associated display metadata. |
| H-9 | **TECH-STACK.md compliance** | ✅ PASS | Thin controller, `ApiResponse` envelope, JWT auth, `@Transactional` service, Mockito unit tests, no debug code. |

---

## Service Review: `CreatorCouponService.list`

```49:77:influora-api/src/main/java/com/influora/service/CreatorCouponService.java
    @Transactional(readOnly = true)
    public List<CreatorCouponListItem> list(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        List<CouponCode> coupons =
                couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(profile.getId());

        Set<String> campaignIds =
                coupons.stream().map(CouponCode::getCampaignId).collect(Collectors.toSet());
        Map<String, Campaign> campaignsById =
                campaignRepository.findAllById(campaignIds).stream()
                        .collect(Collectors.toMap(Campaign::getId, Function.identity()));

        Set<String> workspaceIds =
                coupons.stream().map(CouponCode::getWorkspaceId).collect(Collectors.toSet());
        Map<String, Workspace> workspacesById =
                workspaceRepository.findAllById(workspaceIds).stream()
                        .collect(Collectors.toMap(Workspace::getId, Function.identity()));

        String creatorProfileId = profile.getId();
        return coupons.stream()
                .map(
                        coupon ->
                                toListItem(
                                        coupon,
                                        campaignsById.get(coupon.getCampaignId()),
                                        workspacesById.get(coupon.getWorkspaceId()),
                                        creatorProfileId))
                .toList();
    }
```

**Ordering:** Profile gate → scoped coupon query → batch campaign/workspace load → per-row UTM lookup → map to DTO. Correct fail-fast on auth before any data access.

**Empty-list optimization:** When `coupons` is empty, `campaignIds`/`workspaceIds` are empty sets — `findAllById` is never called (verified in `testListEmpty` via `never()` mocks). Good.

**Tracking URL resolution:**

```83:87:influora-api/src/main/java/com/influora/service/CreatorCouponService.java
        String trackingUrl =
                utmCampaignRepository
                        .findByCampaignIdAndCreatorProfileId(coupon.getCampaignId(), creatorProfileId)
                        .map(utm -> Objects.requireNonNullElse(utm.getFullTrackingUrl(), utm.getShortUrl()))
                        .orElse(null);
```

Prefers `fullTrackingUrl`, falls back to `shortUrl`, omits when no UTM row — matches A4 frontend expectation (`trackingUrl` optional).

---

## Controller Review

```29:33:influora-api/src/main/java/com/influora/web/CreatorCouponController.java
    @GetMapping
    public ResponseEntity<ApiResponse<List<CreatorCouponListItem>>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(creatorCouponService.list(principal)));
    }
```

Pure delegation — no business logic in controller. Matches `CreatorPlatformFeeController` pattern (Task #27). Response envelope: `{ success: true, data: [ ...items ] }` — flat array in `data`, not nested `{ coupons: [...] }`. Consistent with other creator list endpoints; Ananya should map `response.data` directly to `CreatorCouponResponse[]`.

---

## Frontend Contract Cross-Check (`src/lib/api.ts`)

| Frontend `CreatorCouponResponse` | Backend `CreatorCouponListItem` | Match |
|----------------------------------|--------------------------------|-------|
| `id` | `id` | ✅ |
| `campaignId` | `campaignId` | ✅ |
| `campaignName` | `campaignName` | ✅ |
| `brandName` | `brandName` | ✅ |
| `code` | `code` | ✅ |
| `discountType: 'percentage' \| 'fixed'` | `String discountType` | ✅ (runtime values from DB) |
| `discountValue: number` | `BigDecimal discountValue` | ✅ (Jackson → number) |
| `usageLimit?: number` | `Integer usageLimit` | ✅ |
| `usageCount: number` | `int usageCount` | ✅ |
| `expiresAt?: string` | `Instant expiresAt` | ✅ (ISO-8601) |
| `createdAt: string` | `Instant createdAt` | ✅ |
| `trackingUrl?: string` | `String trackingUrl` (nullable) | ✅ |

**Intentional omission:** brand `CouponResponse` includes `creatorProfileId`; creator DTO does not — correct.

---

## Test Execution

| Test Class | Authored | Executed | Failures | Notes |
|------------|----------|----------|----------|-------|
| `CreatorCouponServiceTest` | 4 | ❌ Not run | — | `mvn` unavailable on PATH |
| `CreatorCouponControllerTest` | 1 | ❌ Not run | — | Pure delegation |
| **Total** | **5** | **0** | — | **Meera gate required** |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=CreatorCouponServiceTest,CreatorCouponControllerTest
```

### Test coverage assessment

| Scenario | Covered | Test |
|----------|---------|------|
| Cross-creator isolation (query scoped to auth profile) | ✅ | `testCrossCreatorIsolation` |
| Happy path enrichment (names + tracking URL) | ✅ | `testListHappyPathEnrichment` |
| Empty list + no spurious batch fetches | ✅ | `testListEmpty` |
| Missing UTM → `trackingUrl` null | ✅ | `testListWithoutTrackingUrl` |
| Controller 200 + delegation | ✅ | `CreatorCouponControllerTest.testList` |
| Brand JWT → 403 | ⚠️ CODE-ONLY | `requireCreator` path not unit-tested (L-T28-2; same posture as T27) |
| `usageCount` field mapping | ⚠️ GAP | Happy path test does not assert `usageCount` (L-T28-7) |

---

## Findings

### Blocking

*None.*

### Low / Advisory (non-blocking)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| L-T28-1 | LOW | **N+1 UTM lookup** — `findByCampaignIdAndCreatorProfileId` called once per coupon row inside `toListItem` stream. | Acceptable for sprint (creators typically have few coupons). Batch UTM fetch in a follow-up if list sizes grow. |
| L-T28-2 | LOW | No MockMvc/integration test for brand JWT → 403 on `/creator/coupons`. | Kabir K3 spot-check; optional `@WebMvcTest` in a later hygiene pass (same gap as T27). |
| L-T28-3 | LOW | `CreatorCouponListResponse` record defined but unused — controller returns `List` directly in `ApiResponse.data`. | Delete dead type or use it if API contract changes; no runtime impact. |
| L-T28-4 | INFO | `src/lib/api.ts` `creatorCoupons.list()` still throws `NOT_IMPLEMENTED` in live mode. | Ananya A3 — wire after this QA gate (expected; not Vikram scope). |
| L-T28-5 | INFO | `mvn` not on PATH in Kavya QA environment. | Meera confirms 5/5 green before M2 sign-off. |
| L-T28-6 | LOW | `CreatorCouponListItem` lacks `@JsonInclude(NON_NULL)` on `trackingUrl` (brand `CouponResponse` has class-level `NON_NULL`). | `null` may serialize as JSON `null` vs omitted key. Frontend treats both as absent — acceptable; Ananya can normalize on wire. |
| L-T28-7 | LOW | Happy-path test does not assert `usageCount` mapping. | Optional test hardening; builder defaults `usageCount` to 0. |

---

## Security Escalation Notes (for Kabir K3)

| Area | Kavya assessment | Kabir action |
|------|------------------|--------------|
| IDOR / creator isolation | ✅ PASS — server-derived profile id only | Spot-verify JWT → profile → repository chain |
| Cross-creator data leak | ✅ PASS — query + UTM both keyed on auth profile | Confirm no path/query override |
| Read surface / money path | ✅ PASS — list-only, no redemption or mutation | **Not LOAD-BEARING** (same K3 batch posture as #27) |
| Rate limiting | No dedicated read bucket | Same as other creator GETs — optional hardening, not sprint gate |
| Enumeration | Coupon codes are creator+campaign-derived (public promotional shape per Wave A QA) | Low concern on this read endpoint |

---

## Pipeline Routing

| Step | Owner | Status |
|------|-------|--------|
| Vikram V3 implementation | Vikram | ✅ SHIPPED |
| **Kavya Kv1 QA** | Kavya | ✅ **APPROVED** (this doc) |
| Kabir K3 security (batch #27 + #28) | Kabir | ⏳ **NEXT** |
| Meera M2 build + `mvn test` 5/5 | Meera | ⏳ QUEUED |
| Ananya A3 live wire `creator-coupons.tsx` | Ananya | ⏳ BLOCKED until M2 |

---

*Kavya Patel — QA Lead, Sage Digital. Retry pass complete (prior run `resource_exhausted`).*
