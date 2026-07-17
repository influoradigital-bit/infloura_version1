# QA Review: GET /creator/platform-fee — Task #27 V2 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~20:00 IST)  
**Verdict:** ✅ **APPROVED** — routed to **Kabir K3** (batch with Task #28 coupon-read per CEO §P1-K3) → Meera M2 build → Priya sign-off on fee-transparency slice  
**Scope:** Vikram Task #27 V2 — `GET /api/v1/creator/platform-fee` read-only global fee transparency  
**Reference:** `TASK_INBOX.md` Task #27; `wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md` §7 / §7A; `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §P0-V2 / §P1-K3; Task #26 V1 QA (`wiki/errors/creator-platform-fee-T26-kavya-qa.md`)  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/CreatorPlatformFeeController.java`
- `influora-api/src/main/java/com/influora/service/CreatorPlatformFeeService.java`
- `influora-api/src/main/java/com/influora/web/dto/creatorplatformfee/CreatorPlatformFeeDtos.java`
- `influora-api/src/main/java/com/influora/service/PlatformFeeService.java` — `resolveCreatorFeeBps()` cross-check
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/src/test/java/com/influora/service/CreatorPlatformFeeServiceTest.java` (2 tests)
- `influora-api/src/test/java/com/influora/web/CreatorPlatformFeeControllerTest.java` (1 test)

---

## Executive Summary

Task #27 V2 **passes QA**. `GET /api/v1/creator/platform-fee` is a read-only transparency endpoint: Spring Security requires authentication (`anyRequest().authenticated()`), `CreatorPlatformFeeService` gates via `CreatorContextService.requireCreatorProfile(principal)` (creator `UserType` + profile row), resolves the take rate from `PlatformFeeService.resolveCreatorFeeBps()` (same DB singleton path as V1 release deduction), and returns only `feeBps`, `feePercent`, and `source` — no path parameters, no creator identifiers, no PII.

Response shape matches `10_CREATOR_PAYMENTS_SPEC.md` §7A exactly (`feeBps` / `feePercent` / `source: "GLOBAL_DEFAULT"`), wrapped in the standard `ApiResponse` envelope. Per-plan and per-creator override resolution is intentionally deferred (`source` is always `GLOBAL_DEFAULT` until a later wave).

**3 unit tests** authored (2 service + 1 controller delegation). **`mvn` not on PATH** in this QA environment — Meera must confirm **3/3 PASS**.

**Kabir K3 (lightweight, batch with #28):** re-verify `CreatorContextService` scoping, confirm brand JWT cannot read creator fee surface, confirm no IDOR via path/query (none exist), confirm response cannot leak another creator's negotiated rate (not implemented yet — global only).

---

## Task #27 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `GET /api/v1/creator/platform-fee` ships | ✅ PASS | `CreatorPlatformFeeController` `@RequestMapping("/creator/platform-fee")` + `server.servlet.context-path=/api/v1` |
| 200 for authenticated creator | ✅ PASS | Controller `@AuthenticationPrincipal` + service `requireCreatorProfile`; controller test asserts 200 + envelope |
| Global config only (DB-backed) | ✅ PASS | `platformFeeService.resolveCreatorFeeBps()` → `PlatformFeeConfig.defaultFeeBps` (Task #26 path) |
| No PII in response | ✅ PASS | `PlatformFeeResponse(int feeBps, double feePercent, String source)` only; profile return value discarded |
| Response `feeBps` / `feePercent` / `source` | ✅ PASS | Matches spec §7A example; `SOURCE_GLOBAL_DEFAULT = "GLOBAL_DEFAULT"` |
| Unit tests 3/3 | ⚠️ AUTHORED | Not executed here (L-T27-5) |
| Kavya Kv1 | ✅ THIS DOC | |
| Kabir K3 | ⏳ **NEXT** | Batch with Task #28 per CEO §P1-K3 |
| Meera M2 | ⏳ QUEUED | After Kabir |

---

## Hostile-Path Checklist (Task #27)

| # | Requirement | Result | Evidence |
|---|-------------|--------|----------|
| H-1 | **Auth required** (no anonymous read) | ✅ PASS | `SecurityConfig` L191–192 `anyRequest().authenticated()` — no `permitAll` for `/creator/**`. Unauthenticated → 401 at filter chain before controller. |
| H-2 | **Creator role gate** (brand/admin rejected) | ✅ PASS | `CreatorContextService.requireCreatorProfile` → `requireCreator`: `principal == null \|\| userType != CREATOR` → `WRONG_USER_TYPE` 403. Same pattern as `CreatorCouponController`, `CreatorReviewController`. |
| H-3 | **No path-param IDOR** | ✅ PASS (N/A) | Zero `@PathVariable` / `@RequestParam`. Fee is global — identical for every authenticated creator. No `creatorId` in URL to spoof. Identity resolved only from JWT via `CreatorContextService`. |
| H-4 | **No PII in response** | ✅ PASS | DTO is 3 scalar fee fields. `requireCreatorProfile` result not mapped to response. Test `testResponseContainsNoPii` asserts only fee fields populated. |
| H-5 | **Read-only** (no mutation) | ✅ PASS | `GET` only; no `POST`/`PUT`/`DELETE`. Service `@Transactional(readOnly = true)`. |
| H-6 | **Matches payments spec §7A** | ✅ PASS | Spec: `{ feeBps: 1500, feePercent: 15.0, source: "GLOBAL_DEFAULT" }` — field names, types, and `source` enum match. |
| H-7 | **Same fee source as release deduction** | ✅ PASS | V2 calls `resolveCreatorFeeBps()` — identical method V1 `deductAtRelease()` uses at release time. UI will not show a stale/hardcoded % divergent from actual deductions. |
| H-8 | **§7.5 read posture** (creators READ, never mutate) | ✅ PASS | No write endpoints on creator surface; admin mutation remains on `PlatformFeeAdminController` (`ROLE_ADMIN`). |

---

## Spec §7 / §7A Contract Cross-Check

| Spec (`10_CREATOR_PAYMENTS_SPEC.md`) | Implementation | Match |
|--------------------------------------|----------------|-------|
| `GET /api/v1/creator/platform-fee` | `GET /creator/platform-fee` + context-path `/api/v1` | ✅ |
| JWT required | `SecurityConfig` + `@AuthenticationPrincipal` | ✅ |
| `{ feeBps, feePercent, source }` | `PlatformFeeResponse` record | ✅ |
| `source: "GLOBAL_DEFAULT"` | `CreatorPlatformFeeService.SOURCE_GLOBAL_DEFAULT` | ✅ |
| Read-only transparency for wallet UI | Documented in DTO javadoc for Ananya A2 | ✅ |
| Per-plan / per-creator overrides (future) | Not in V2 — `source` hardcoded `GLOBAL_DEFAULT`; javadoc acknowledges deferral | ✅ (scoped) |

### `feePercent` derivation

```33:36:influora-api/src/main/java/com/influora/service/CreatorPlatformFeeService.java
        double feePercent =
                BigDecimal.valueOf(feeBps)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                        .doubleValue();
```

| `feeBps` | Expected `feePercent` | Verified |
|----------|----------------------|----------|
| 1500 | 15.0 | ✅ test + spec default |
| 1200 | 12.0 | ✅ `testResponseContainsNoPii` |
| 1255 | 12.55 | ✅ HALF_UP math (code trace) |
| 0 | 0.0 | ✅ code trace |

---

## Auth & IDOR Analysis

### Request surface

```
GET /api/v1/creator/platform-fee
Authorization: Bearer <creator-jwt>
```

No query params. No body. No resource id in path — **IDOR via path manipulation is not applicable.**

### Identity flow

1. `JwtAuthenticationFilter` validates Bearer token → `AuthPrincipal` on security context.
2. Controller passes `principal` to service.
3. `creatorContext.requireCreatorProfile(principal)`:
   - Rejects null / non-`CREATOR` → 403 `WRONG_USER_TYPE`
   - Rejects missing profile row → 404 `CREATOR_PROFILE_NOT_FOUND`
4. Fee resolved globally — **no `principal.getUserId()` passed to fee lookup** (correct for V2 global config).

### Cross-role matrix (code trace)

| Caller | Filter chain | Service gate | Fee returned |
|--------|--------------|--------------|--------------|
| No JWT | 401 | — | — |
| Brand JWT | 200 auth | 403 `WRONG_USER_TYPE` | — |
| Creator JWT (no profile) | 200 auth | 404 `CREATOR_PROFILE_NOT_FOUND` | — |
| Creator JWT (valid) | 200 auth | pass | Global `feeBps`/`feePercent`/`source` |
| Admin JWT (no creator role) | 200 auth* | 403 `WRONG_USER_TYPE` | — |

\*Admin routes use separate `/admin/**` matcher with `ROLE_ADMIN`; `/creator/platform-fee` falls through to generic `authenticated()` — admin JWT would authenticate but fail creator gate. Correct.

---

## PII / Data Minimization

| Field | Classification | In response? |
|-------|----------------|--------------|
| `feeBps` | Platform config (non-PII) | ✅ |
| `feePercent` | Derived display (non-PII) | ✅ |
| `source` | Config provenance enum (non-PII) | ✅ |
| `userId` / `creatorId` / `displayName` | PII | ❌ absent |
| `email` / `phone` | PII | ❌ absent |
| Admin change-log / reason | Internal audit | ❌ absent (correct — admin-only) |

`ApiResponse` envelope adds `success`, `timestamp` — no user identifiers. ✅

---

## Test Execution

| Test Class | Test | Authored | Kavya Re-run | Notes |
|------------|------|----------|--------------|-------|
| `CreatorPlatformFeeServiceTest` | `testGetCurrentFeeReturnsGlobalConfig` | ✅ | ❌ `mvn` unavailable | Gates via `CreatorContextService`; 1500→15.0 |
| `CreatorPlatformFeeServiceTest` | `testResponseContainsNoPii` | ✅ | ❌ | 1200→12.0; only fee fields |
| `CreatorPlatformFeeControllerTest` | `testGetCurrentFee` | ✅ | ❌ | Delegation + 200 envelope |
| **Total** | | **3** | — | Meera gate required |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=CreatorPlatformFeeServiceTest,CreatorPlatformFeeControllerTest
```

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| Follows TECH-STACK.md | ✅ — extends existing creator controller/service pattern; no new abstractions |
| No `console.log` / debug code | ✅ |
| Error handling | ✅ — delegated to `CreatorContextService` + `PlatformFeeService.requireConfig()` |
| Comments explain WHY | ✅ — V2 scope, PII exclusion, Ananya A2 wiring note |
| Matches peer endpoints (`CreatorCouponController`) | ✅ — same auth principal + context-service-in-service-layer shape |
| No hardcoded fee % in Java | ✅ — reads `resolveCreatorFeeBps()` from DB |

---

## Findings (Non-Blocking)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| L-T27-1 | **Low** | No negative unit tests for `WRONG_USER_TYPE` / `CREATOR_PROFILE_NOT_FOUND` / null principal | Optional follow-up; `CreatorContextService` is already tested elsewhere. Kabir K3 may spot-check. |
| L-T27-2 | **Low** | No `@WebMvcTest` security integration test for 401 without JWT | Consistent with other creator read endpoints (#28, #29). Filter-chain behavior verified via `SecurityConfig` code trace. |
| L-T27-3 | **Info** | `source` always `GLOBAL_DEFAULT` — per-plan/per-creator resolution not wired | Expected V2 scope per DTO javadoc. Future wave must extend `PlatformFeeService` resolution + `source` enum without breaking A2 contract. |
| L-T27-4 | **Info** | `requireCreatorProfile` return value unused (profile existence gate only) | Acceptable — stricter than `requireCreator()` alone; ensures incomplete onboarding cannot read fee. |
| L-T27-5 | **Process** | `mvn` unavailable in Kavya QA environment | Meera confirms 3/3 green before Priya sign-off. |

**No blocking defects. No security escalation to Kabir beyond scheduled K3.**

---

## Routing

| Next gate | Owner | Notes |
|-----------|-------|-------|
| **Kabir K3** | Kabir | Lightweight — batch with Task #28 `GET /creator/coupons` per `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §P1-K3. Scope: `CreatorContextService` scoping on both, no IDOR, no cross-creator data leak. **Not** LOAD-BEARING (read-only; money path is K2 on #26). |
| Meera M2 | Meera | `mvn test` scoped 3/3 + regression after Kabir |
| Ananya A2 | Ananya | **UNBLOCKED** — wire `creator-wallet.tsx` fee line from this endpoint |
| Priya | Priya | Fee-transparency slice sign-off after Meera |

---

## Kavya Sign-Off

**Task #27 V2: APPROVED.**

Auth required ✅ · No path-param IDOR ✅ · No PII in response ✅ · Spec §7A contract match ✅ · Aligns with V1 `resolveCreatorFeeBps()` ✅

— **Kavya Patel**, QA Lead, 2026-07-09
