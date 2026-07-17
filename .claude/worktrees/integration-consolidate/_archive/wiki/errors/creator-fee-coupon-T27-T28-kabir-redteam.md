# Creator Platform-Fee + Coupon-Read — Tasks #27 V2 / #28 V3 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~20:15 IST)  
**Verdict:** ✅ **PASS WITH FINDINGS** — no Critical or High findings; pipeline **GO** for Meera M2  
**Scope:** CEO §P1-K3 lightweight IDOR/scoping batch — `GET /creator/platform-fee` (Task #27 V2) + `GET /creator/coupons` (Task #28 V3)  
**Reference:** Kavya `wiki/errors/creator-platform-fee-T27-kavya-qa.md`; `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §P1-K3; `wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md` §7A; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.3; Kabir Task #11 `creator-context-service-T11-kabir-redteam.md`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/CreatorPlatformFeeController.java`
- `influora-api/src/main/java/com/influora/service/CreatorPlatformFeeService.java`
- `influora-api/src/main/java/com/influora/web/dto/creatorplatformfee/CreatorPlatformFeeDtos.java`
- `influora-api/src/main/java/com/influora/service/PlatformFeeService.java` — `resolveCreatorFeeBps()` cross-check
- `influora-api/src/main/java/com/influora/web/CreatorCouponController.java`
- `influora-api/src/main/java/com/influora/service/CreatorCouponService.java`
- `influora-api/src/main/java/com/influora/web/dto/creatorcoupon/CreatorCouponDtos.java`
- `influora-api/src/main/java/com/influora/repository/CouponCodeRepository.java` — `findByCreatorIdOrderByCreatedAtDesc`
- `influora-api/src/main/java/com/influora/repository/UtmCampaignRepository.java` — tracking URL scoping
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/src/test/java/com/influora/service/CreatorPlatformFeeServiceTest.java` (2 tests)
- `influora-api/src/test/java/com/influora/web/CreatorPlatformFeeControllerTest.java` (1 test)
- `influora-api/src/test/java/com/influora/service/CreatorCouponServiceTest.java` (4 tests)
- `influora-api/src/test/java/com/influora/web/CreatorCouponControllerTest.java` (1 test)

**Kavya input:** Task #27 QA doc ✅ present and APPROVED. Task #28 QA doc (`creator-coupons-T28-kavya-qa.md`) **not found** — T28 reviewed directly from controller/service/tests per CEO retry fallback.

---

## Executive Summary

Both read-only creator surfaces pass the §P1-K3 lightweight security gate. Identity is resolved exclusively via `CreatorContextService` from the JWT-derived `AuthPrincipal`; neither endpoint accepts a client-supplied creator id in path, query, or body. No mutation verbs ship on either controller. **IDOR via resource-id manipulation is not applicable** on Task #27 (global config, no resource id) and **blocked by design** on Task #28 (repository query keyed to JWT-resolved `CreatorProfile#getId()`).

**Task #27 — PASS.** Global fee transparency; response carries only `feeBps` / `feePercent` / `source`; same `PlatformFeeService.resolveCreatorFeeBps()` path as V1 release deduction. Brand/admin JWTs fail creator gate. No cross-creator fee leakage possible in V2 (`source` always `GLOBAL_DEFAULT`).

**Task #28 — PASS.** Coupon list scoped to authenticated creator profile; UTM tracking URL enrichment double-scoped by `(campaignId, creatorProfileId)` from trusted profile resolution. Unit test `testCrossCreatorIsolation` explicitly verifies repository is never queried with another creator's profile id.

**Load-bearing posture:** These are **read-only, non-money-path** surfaces (money path K2 remains on Task #26 `PlatformFeeService.deductAtRelease`). No sprint gate block.

**Low carry-forward only** — missing negative auth integration tests (consistent with peer creator read endpoints), T28 Kavya doc gap, future per-creator fee override wave must not introduce IDOR when `source` diverges from `GLOBAL_DEFAULT`.

**Test execution:** `mvn` unavailable in Kabir shell — logic verified by code review + Kavya/Vikram-authored unit tests. Meera gate required.

---

## 1. Shared Identity Model — `CreatorContextService`

Both services call `requireCreatorProfile(principal)` before any data access:

```28:38:influora-api/src/main/java/com/influora/service/CreatorContextService.java
    public CreatorProfile requireCreatorProfile(AuthPrincipal principal) {
        requireCreator(principal);
        return creatorProfileRepository
                .findByUserId(principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND",
                                        "Creator profile not found",
                                        HttpStatus.NOT_FOUND));
    }
```

| Check | Task #27 | Task #28 |
|-------|----------|----------|
| `CreatorContextService` in service layer (not controller bypass) | ✅ `CreatorPlatformFeeService.getCurrentFee` | ✅ `CreatorCouponService.list` |
| No `@PathVariable` / `@RequestParam` creator id | ✅ zero params | ✅ zero params |
| Principal from `@AuthenticationPrincipal` only | ✅ | ✅ |
| Brand JWT → 403 `WRONG_USER_TYPE` | ✅ `requireCreator` | ✅ `requireCreator` |
| Missing profile → 404 `CREATOR_PROFILE_NOT_FOUND` | ✅ | ✅ |
| Unauthenticated → 401 at filter chain | ✅ `anyRequest().authenticated()` | ✅ |

**Kabir Task #11 baseline holds:** no caller feeds a client-supplied id into `CreatorContextService`. Both new controllers match the established peer pattern (`CreatorReviewController`, `CreatorDeliverableController`).

---

## 2. Task #27 — `GET /creator/platform-fee`

### 2a. Request surface

```
GET /api/v1/creator/platform-fee
Authorization: Bearer <creator-jwt>
```

No path variables. No query parameters. No request body. **IDOR attack surface: none.**

### 2b. Data flow

```29:38:influora-api/src/main/java/com/influora/service/CreatorPlatformFeeService.java
    @Transactional(readOnly = true)
    public PlatformFeeResponse getCurrentFee(AuthPrincipal principal) {
        creatorContext.requireCreatorProfile(principal);
        int feeBps = platformFeeService.resolveCreatorFeeBps();
        // ... feePercent derivation ...
        return new PlatformFeeResponse(feeBps, feePercent, SOURCE_GLOBAL_DEFAULT);
    }
```

- `requireCreatorProfile` return value is used only as an **existence gate** — profile fields are **not** mapped to the response. ✅ PII minimization.
- Fee lookup calls `resolveCreatorFeeBps()` with **no** `principal.getUserId()` or `profile.getId()` — correct for V2 global config; identical to V1 `deductAtRelease()` fee resolution. UI cannot diverge from actual deductions.
- `@Transactional(readOnly = true)` — no write side effects.

### 2c. Hostile-path matrix

| Attack | Expected | Result |
|--------|----------|--------|
| No JWT | 401 | **BLOCKED** — `SecurityConfig` L191–192 |
| Brand JWT | 403 `WRONG_USER_TYPE` | **BLOCKED** |
| Admin JWT (no creator role) | 403 `WRONG_USER_TYPE` | **BLOCKED** — `/creator/**` falls through to generic `authenticated()`, creator gate in service |
| Creator JWT, no profile row | 404 `CREATOR_PROFILE_NOT_FOUND` | **BLOCKED** — no fee leaked |
| Spoof `?creatorId=` or path id | Ignored — no such input surface | **N/A** |
| Read another creator's negotiated rate | V2 returns global config only | **N/A** — per-creator overrides not implemented |
| Mutate fee via this endpoint | No write verbs | **BLOCKED** — GET only; admin mutation on `PlatformFeeAdminController` (`ROLE_ADMIN`) |

### 2d. Response minimization

`PlatformFeeResponse(int feeBps, double feePercent, String source)` — no `userId`, `creatorId`, `email`, admin audit fields. ✅

---

## 3. Task #28 — `GET /creator/coupons`

### 3a. Request surface

```
GET /api/v1/creator/coupons
Authorization: Bearer <creator-jwt>
```

No path variables. No query parameters. No request body. **Cannot supply another creator's id via the HTTP contract.**

### 3b. Scoping chain (IDOR probe)

```49:76:influora-api/src/main/java/com/influora/service/CreatorCouponService.java
    @Transactional(readOnly = true)
    public List<CreatorCouponListItem> list(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        List<CouponCode> coupons =
                couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(profile.getId());
        // ... enrichment from campaign/workspace ids ON coupons already scoped to profile ...
        String creatorProfileId = profile.getId();
        return coupons.stream()
                .map(coupon -> toListItem(coupon, ..., creatorProfileId))
                .toList();
    }
```

1. JWT → `AuthPrincipal` (immutable, HMAC-verified at filter).
2. `requireCreatorProfile` → `CreatorProfile` via `findByUserId(principal.getUserId())`.
3. Repository query uses **`profile.getId()`** — never a request parameter.
4. UTM tracking URL lookup: `findByCampaignIdAndCreatorProfileId(campaignId, creatorProfileId)` — second scoping dimension prevents attaching another creator's tracking link to a coupon row that happens to share a campaign.

**Repository contract** (`CouponCodeRepository` javadoc): `creatorId` is `CreatorProfile#getId()`; callers MUST resolve via `CreatorContextService` first. Implementation complies.

### 3c. Cross-creator isolation (unit-tested)

`CreatorCouponServiceTest.testCrossCreatorIsolation`:
- Mocks `requireCreatorProfile` → Creator A.
- Stubs repo for profile A → coupon A; profile B → coupon B.
- Asserts result contains only `CODE-A`.
- Verifies `findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_A)` called; **never** profile B.

**IDOR via JWT swap:** Creator B's JWT resolves to profile B at step 2 — query never uses profile A. **BLOCKED.**

### 3d. Enrichment leak probe

| Enrichment step | Data source | Cross-tenant risk |
|-----------------|-------------|-------------------|
| `campaignRepository.findAllById(campaignIds)` | IDs from creator's own coupon rows | **LOW** — IDs derived from already-scoped coupons |
| `workspaceRepository.findAllById(workspaceIds)` | IDs from creator's own coupon rows | **LOW** — same |
| `utmCampaignRepository.findByCampaignIdAndCreatorProfileId` | JWT-resolved `creatorProfileId` | **BLOCKED** — cannot fetch another creator's UTM row |

`workspaceId` is **not** exposed in `CreatorCouponListItem` DTO — correct minimization. `campaignId` is exposed but only for campaigns the creator is already assigned to via their coupon rows.

### 3e. Hostile-path matrix

| Attack | Expected | Result |
|--------|----------|--------|
| No JWT | 401 | **BLOCKED** |
| Brand JWT | 403 `WRONG_USER_TYPE` | **BLOCKED** |
| Creator A JWT → enumerate Creator B coupons | Query scoped to A's profile id | **BLOCKED** |
| `GET /creator/coupons?creatorId=<victim>` | Param not bound — ignored by Spring | **BLOCKED** (no effect) |
| `GET /creator/coupons/{id}` | No such route | **N/A** |
| POST/PUT/DELETE on `/creator/coupons` | No such mappings | **BLOCKED** — read-only |
| Brand `CampaignTrackingController` coupon write path | Separate brand workspace gate | **No conflict** — creation stays brand-scoped; creator read is self-scoped |

### 3f. Sensitive field exposure (intentional)

Response includes `code`, `discountValue`, `usageCount`, `trackingUrl` — these are **the creator's own** affiliate assets. Cross-creator leakage is the threat model; self-read is by design for Ananya A3 wire.

---

## 4. Read-Only Posture

| Endpoint | HTTP verbs | Service txn | Mutation risk |
|----------|------------|-------------|---------------|
| `/creator/platform-fee` | GET only | `readOnly = true` | ✅ None |
| `/creator/coupons` | GET only | `readOnly = true` | ✅ None |

Brand-driven coupon **creation** remains on `CampaignTrackingController` (`POST /campaigns/{id}/coupons`) with brand workspace scoping — correctly separated from creator self-read.

---

## 5. Findings Register

### No Critical / High findings

Pipeline **not blocked**.

### Low — non-blocking

| ID | Task | Severity | Finding | Recommendation |
|----|------|----------|---------|----------------|
| L-T27-K1 | #27 | LOW | No negative unit tests for `WRONG_USER_TYPE` / `CREATOR_PROFILE_NOT_FOUND` / null principal on platform-fee | Optional; `CreatorContextServiceTest` covers gate. Add `@WebMvcTest` 401/403 in follow-up if desired. |
| L-T27-K2 | #27 | LOW | No `@WebMvcTest` security integration for unauthenticated 401 | Consistent with #28, #29 peer read endpoints. |
| L-T28-K1 | #28 | LOW | Kavya QA doc `creator-coupons-T28-kavya-qa.md` missing | Kavya retry or accept Kabir direct review for this gate; Meera still runs tests. |
| L-T28-K2 | #28 | LOW | No negative auth tests on coupon controller/service | Same gap as L-T27-K1. |
| L-T28-K3 | #28 | LOW | Unbounded list — no pagination on `findByCreatorIdOrderByCreatedAtDesc` | Acceptable for MVP; revisit if creators accumulate hundreds of campaign coupons (DoS/read amplification). |
| L-T27-K3 | #27 | INFO | Future per-creator fee overrides (`source` ≠ `GLOBAL_DEFAULT`) | **Must** resolve fee from JWT-scoped profile in same PR as override feature — never from path/query `creatorId`. Flag for Priya architecture review when V3+ fee wave ships. |

### Closed (this batch)

| ID | Finding | Status |
|----|---------|--------|
| K3-IDOR-27 | Path-param creator id leakage on platform-fee | ✅ **N/A** — no resource id in contract |
| K3-IDOR-28 | Cross-creator coupon enumeration | ✅ **CLOSED** — `findByCreatorId` keyed to JWT profile |
| K3-CTX-27/28 | `CreatorContextService` bypass | ✅ **CLOSED** — both services gate before data access |
| K3-READ-27/28 | Mutation on read surfaces | ✅ **CLOSED** — GET + `readOnly` txn |

---

## 6. Hostile Replay Checklist (Kabir)

| Scenario | T27 | T28 |
|----------|-----|-----|
| Unauthenticated GET | ✅ 401 | ✅ 401 |
| Brand JWT GET | ✅ 403 | ✅ 403 |
| Creator JWT, incomplete onboarding (no profile) | ✅ 404 | ✅ 404 |
| Creator A JWT cannot read Creator B data | ✅ N/A (global) | ✅ BLOCKED |
| Spoof creator id in URL/query/body | ✅ N/A | ✅ N/A / ignored |
| Response leaks `userId` / `email` | ✅ absent | ✅ absent |
| Write verb on endpoint | ✅ none | ✅ none |
| Tracking URL from another creator's UTM row | ✅ N/A | ✅ BLOCKED (dual-key lookup) |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|-----------|----------|
| Task #27 V2 — platform-fee read | **GO** |
| Task #28 V3 — coupon-read | **GO** |
| Critical / High findings | **NONE** |
| Meera M2 `mvn test` gate | **PENDING** — not a security block |
| Ananya A2 (wallet fee UI) | **UNBLOCKED** — T27 cleared |
| Ananya A3 (coupons wire) | **UNBLOCKED** — T28 cleared |

**Pipeline position:** Kabir K3 batch **✅ PASS WITH FINDINGS** — routes to **Meera M2**. No escalation to Priya/Swapnil.

---

## Kabir Sign-Off

- [x] Task #27 — `CreatorContextService` gate; no path-param IDOR; no PII; read-only; aligns with V1 `resolveCreatorFeeBps()`
- [x] Task #28 — `CreatorContextService` → `profile.getId()` → `findByCreatorId`; UTM enrichment dual-scoped; cross-creator isolation unit-tested
- [x] Both endpoints — authenticated only; brand/admin rejected at creator gate; no mutation verbs
- [x] No Critical or High findings — sprint gate **GO**
- [x] T28 reviewed directly (Kavya doc absent) — code + tests sufficient for K3 lightweight scope

**Kabir verdict: ✅ PASS WITH FINDINGS.** Unblocks Meera M2 build gate and Ananya A2/A3 frontend wire.

---

**Document Control:** Created 2026-07-09 by Kabir (Tasks #27 + #28 K3 batch). Kavya: `creator-platform-fee-T27-kavya-qa.md`. Next: Meera scoped `mvn test` — T27: 3/3, T28: 5/5.

**Commands for Meera:**
```bash
cd influora-api && mvn test -Dtest=CreatorPlatformFeeServiceTest,CreatorPlatformFeeControllerTest,CreatorCouponServiceTest,CreatorCouponControllerTest
```
