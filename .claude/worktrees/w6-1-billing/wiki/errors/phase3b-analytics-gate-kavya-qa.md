# QA Review: Subscription Billing Phase 3b (Task 22) — Plan-Gate Filter + Analytics Cap
**Date:** 2026-07-14  
**Reviewer:** Kavya (QA Lead)  
**Task:** `wiki/processes/subscription-billing-task-breakdown.md` Task 22  
**Files Reviewed:** `PlanGateFilter.java`, `AnalyticsUsageCapInterceptor.java`, `PlanGateWebConfig.java`, `SecurityConfig.java` (filter registration), `RequiresPlan.java`, `PlanGateInterceptor.java`, `V57__free_plan_fee_bps_null.sql`, `BrandCampaignFeeService.java` (MP-2), 3 test files

---

## VERDICT: ✅ PASS WITH ONE FLAGGED PRODUCT-BEHAVIOR GAP

**Overall:** Code is correct, secure, and well-tested. Zero critical/high security issues. One non-blocking product-behavior gap (Flag #1) requires **explicit sign-off** from Priya/Rohan on whether the simplified view-counting is acceptable-as-shipped or needs the dedup fix. Flag #2 (CreatorAnalyticsController scope decision) verified independently — Vikram's correction is sound.

**Routing:** Meera for local verification → Flag #1 decision (Priya/Rohan economics call) → close Task 22 if approved-as-is, or route back to Vikram for per-creator dedup if not.

---

## ✅ VERIFIED CLEAN (All Critical Checks)

### 1. Build Clean
`mvn -o compile`: **BUILD SUCCESS**, zero errors. Exit 0.

### 2. Core Security — Filter Ordering & Fail-Closed Semantics
**`PlanGateFilter` registration verified in `SecurityConfig.java`:** registered `addFilterAfter(planGateFilter, JwtAuthenticationFilter.class)` — correct ordering, runs after JWT so `AuthPrincipal` is already resolved.

**Fail-closed on resolution failure (lines 74-86):** catches `RuntimeException`, logs debug, **leaves attribute UNSET** — downstream gates (`AnalyticsUsageCapInterceptor.preHandle` line 61-67) treat unset as fail-closed (403 or step-aside, never allow-through). Contrast with `BrandCampaignFeeService`'s fail-open (MP-2) correctly scoped to *amounts*, not *access*. ✅

### 3. Bypass Check #1 — No Alternate Analytics Endpoints Found
**Grepped entire `web/` tree** for any OTHER controller exposing creator metrics/scores/demographics/media data:
- `AnalyticsController` (`/analytics/creators/**`) — ✅ gated via `AnalyticsUsageCapInterceptor`
- `CreatorAnalyticsController` (`/creator/analytics/me/**`) — ✅ correctly UNGATED (see Flag #2 resolution below)
- `CampaignController./campaigns/{id}/analytics` — ✅ confirmed UNTOUCHED (verified via git diff, no edits to that method in recent commits, path pattern `/campaigns/**` textually disjoint from `/analytics/creators/**`)
- **No other endpoints found** exposing the same data sources (`MediaMetric`, `CreatorScore`, `AudienceDemographics`)

**Conclusion:** Only one gated path exists for brand-facing creator deep-dive analytics. No bypass via alternate controller. ✅

### 4. Bypass Check #2 — Path Pattern Coverage
**Interceptor registration:** `.addPathPatterns("/analytics/creators/**")` (line 40, `PlanGateWebConfig.java`)

**Spring's `/**` pattern semantics:** matches `/analytics/creators/{creatorId}/metrics`, `/analytics/creators/{creatorId}/scores`, `/analytics/creators/{creatorId}/demographics`, `/analytics/creators/{creatorId}/media` (all 4 sub-endpoints under `AnalyticsController`). 

**URL-encoding/case/trailing-slash bypass risk:** Spring MVC's `PathPattern` normalizes URL-encoded paths BEFORE matching (e.g. `/analytics/creators%2F{id}` decodes to `/analytics/creators/{id}` before the pattern sees it), and the `/**` wildcard is greedy/normalized. No known bypass via encoding tricks. ✅

### 5. Increment-After-Check Ordering (Lines 86-100, `AnalyticsUsageCapInterceptor.java`)
**Check at line 70-83:** `if (used >= monthlyLimit) throw UPGRADE_REQUIRED`  
**Increment at line 100:** `usageCounterService.incrementUsage(...)` — **AFTER the check passes**.

**Test verification:** `PlanGateWiringTest.testFreeWorkspaceSecondViewGenuinelyRejected` (lines 89-111) — confirms `verify(usageCounterService, never()).incrementUsage(...)` when the request is rejected. ✅

### 6. MP-1 Wiring Tests — Real Objects, Not Mocked Logic
Read `PlanGateWiringTest.java` (183 lines) — drives **REAL `PlanGateFilter` + REAL `AnalyticsUsageCapInterceptor`** objects via `MockHttpServletRequest` (spring-test), mocking only collaborators one layer down (`BrandContextService`, `SubscriptionService`, `UsageCounterService`). **Not** unit tests of limit-comparison math with the filter/interceptor themselves mocked.

**4 test cases:**
1. Free 2nd view → 402, quota NOT consumed ✅
2. Free 1st view → succeeds, quota incremented exactly once ✅
3. Pro 100th view → succeeds (unlimited), usage-check never called ✅
4. CREATOR principal → interceptor steps aside, counter never touched ✅

Per Priya's MP-1 standing rule (`wiki/tech/security.md`), these are genuine wiring tests asserting the real gate logic fires. ✅

### 7. V57 Migration — Safe NULL, No NPE Risk
**Migration:** `UPDATE plans SET fee_bps = NULL WHERE code = 'FREE';`

**Verified safe:** `BrandCampaignFeeService.tryResolvePlanFeeBps` line 116 — `plan.getCode() == PlanCode.PRO && plan.getFeeBps() != null` — short-circuits on the **code check** before ever calling `getFeeBps()` for a FREE-code plan. NULLing Free's `fee_bps` introduces zero NPE risk. ✅

**Purpose:** cleans up decorative/dead seed data per Priya's Phase 3a sign-off (latent debt item). Free's fee resolution always goes through the admin-editable global `PlatformFeeConfig`, not the Plan row — this migration makes the data honestly reflect that contract. ✅

### 8. MP-2 Follow-Up — Log Prefix Added
**`BrandCampaignFeeService` line 135:** `"ALERT: FEE_RESOLUTION_FAILOPEN"` prefix added per Priya's MP-2 requirement (interim solution, pending Micrometer/Actuator dependency approval). ✅

**Verified:** no Micrometer infra exists in pom.xml (grepped — confirmed absent). Vikram correctly flags this as an interim solution, not the ideal one. Non-blocking for this pass. ✅

---

## ⚑ FLAG #1 — View-Counting Granularity (Product-Behavior Gap, Needs Sign-Off)

**What Vikram built:** 1 view = 1 endpoint call. Viewing one creator's full profile (metrics + scores + demographics + media = 4 separate GETs to `AnalyticsController`) burns **4 of Free's 1 allowed view/month**, not 1.

**What the plan language suggests:** `SUBSCRIPTION-BILLING-PLAN.md` §1.4/§2 — "1 creator-analytics view/month", "1 creator-analytics deep-dive/month" — reads as **1 creator lookup** (viewing one creator's full profile = 1 "view").

**Impact:** A Free-tier brand thinking they get "1 free look at a creator" gets ~0.25 of one if they hit all 4 sub-endpoints (or exactly 1 sub-endpoint if they only view metrics OR scores OR demographics OR media in isolation — unlikely UX pattern).

**Why it's built this way:** Vikram chose the simpler implementation (per-endpoint increment, no request coalescing) over the complex one (per-creator-per-billing-cycle dedup, e.g. a short-lived grant token checked/set before increment). Flagged explicitly per the task brief's "don't silently under-scope without flagging it" instruction.

**Is this a bug or a feature?** Depends on business intent:
- If "1 view/month" means "engage with creator analytics 1 time" (generous read) → the simpler implementation **under-delivers** what was promised.
- If "1 view/month" means "1 API call to analytics" (literal read) → the implementation is correct-as-specified, just more restrictive than a brand might expect.

**My ruling:** This is a **product-behavior gap**, not a security/correctness bug. Code does exactly what it says it does (1 call = 1 increment). But it may not deliver the user experience the plan copy implies. **Needs explicit sign-off** from Priya/Rohan (economics/product) on whether this ships as-is (with clearer copy like "1 analytics API call/month" if needed) or gets the dedup fix before ship.

**If approved-as-is:** close Task 22, flag a fast-follow to clarify plan copy.  
**If not approved:** route back to Vikram for per-creator-lookup dedup logic (medium complexity, ~4-6 hour add).

---

## ✅ FLAG #2 — CreatorAnalyticsController Scope Decision VERIFIED CORRECT

**Vikram's claim:** `CreatorAnalyticsController` (`/creator/analytics/me/**`) should NOT be gated because a BRAND principal cannot reach it at all — `CreatorContextService.requireCreator` throws `WRONG_USER_TYPE` (403) before any data is read.

**I verified this independently (NOT trusting Vikram's claim alone):**
1. Read `CreatorContextService.requireCreator` (lines 21-26) — genuinely throws `WRONG_USER_TYPE` (403) when `principal.getUserType() != UserType.CREATOR`.
2. Read `CreatorAnalyticsController` (lines 37-59) — every method delegates to `CreatorAnalyticsService`, which calls `creatorContext.requireCreatorProfile(principal)` (grepped service layer, confirmed).
3. `CreatorContextService.requireCreatorProfile` (line 28-29) calls `requireCreator` FIRST, before any DB lookup.

**Conclusion:** A BRAND-authenticated JWT (the only user type that gets a `workspaceId` and thus the only type `PlanGateFilter` resolves a Plan for) **genuinely cannot reach this controller's data layer**. No bypass exists. Gating it anyway would actively regress the product: a CREATOR principal has no `workspaceId`, so a workspace-keyed cap would default every creator to the Free-plan limit and cap them at 1 view/month of their OWN analytics — nonsensical.

**Vikram's correction is sound.** The original audit assumption (`SUBSCRIPTION-BILLING-PLAN.md` §1.1, Task 15/22 row: "a Free brand could bypass the analytics cap by hitting this instead") is **stale/wrong** as currently implemented. ✅

**Test coverage:** `PlanGateWiringTest.testCreatorSelfServiceRequestIsNeverGated` (lines 154-181) explicitly documents this decision with a passing test. ✅

---

## NON-BLOCKING (Informational)

### Test Count Reconciliation
**Vikram claimed:** 12 new tests (979 baseline + 12 = 991).  
**Maven output:** `Tests run: 991, Failures: 0, Errors: 1` (the 1 error is the pre-existing Docker-gated `DatabaseConstraintIntegrationTest` — baseline match, not a regression).  
**Actual new test methods counted:**
- `PlanGateFilterTest`: 4 tests
- `PlanGateInterceptorTest`: 4 tests (grepped file, confirmed)
- `PlanGateWiringTest`: 4 tests
- **Total: 12** ✅

Matches claim. No discrepancy.

### @RequiresPlan Mechanism — Built But Unused
**Grepped `web/` for `@RequiresPlan`:** zero usages (no export/template endpoints exist yet in this codebase, confirmed).

**Sanity check:** read `PlanGateInterceptor.java` (59 lines) — compiles, has reasonable fail-closed logic (`PLAN_NOT_RESOLVED` throws 403 if attribute missing), structurally sound. Test file (`PlanGateInterceptorTest.java`, grepped — exists) confirms basic exercise. 

**Ruling:** Dead code, but not broken code. Ready for whichever future endpoint adds export/template features. No need to over-scrutinize it now. ✅

---

## FINAL CHECKLIST

| Item | Status |
|------|--------|
| Build clean (`mvn -o compile`) | ✅ PASS |
| Filter ordering correct (after JWT) | ✅ VERIFIED |
| Fail-closed on resolution failure | ✅ VERIFIED |
| No bypass via alternate analytics endpoints | ✅ VERIFIED |
| Path pattern covers all 4 sub-endpoints | ✅ VERIFIED |
| Increment-after-check ordering correct | ✅ VERIFIED |
| Quota never consumed on rejected request | ✅ VERIFIED (test) |
| CampaignController.analytics untouched | ✅ VERIFIED (git diff) |
| MP-1 wiring tests drive real objects | ✅ VERIFIED |
| V57 migration safe (no NPE risk) | ✅ VERIFIED |
| MP-2 log prefix added | ✅ VERIFIED |
| CreatorAnalyticsController scope decision | ✅ VERIFIED CORRECT |
| Flag #1 product-behavior gap | ⚠️ NEEDS SIGN-OFF |
| Flag #2 corrected audit assumption | ✅ VERIFIED CORRECT |

---

## NEXT STEPS

1. **Meera:** local verification (`mvn -o test` full suite, confirm 991/0F/1E baseline match).
2. **Priya/Rohan:** weigh in on **Flag #1** — is per-endpoint-call counting acceptable-as-shipped (with clearer plan copy if needed), or does this need the per-creator-lookup dedup fix before ship?
   - **If approved-as-is:** close Task 22, optionally flag a fast-follow to clarify plan copy ("1 analytics API call/month" vs "1 creator view/month").
   - **If not approved:** route back to Vikram for the dedup fix (~4-6 hours), re-submit to me (Kavya) for QA re-check.
3. **No Kabir gate needed** (confirmed per Arjun's note — this is access-control/quota, not direct money-movement, so it doesn't trigger the mandatory Kabir red-team rule).

---

**Approved for next gate:** Meera local verification.  
**Blocked on:** Flag #1 product-behavior sign-off (Priya/Rohan).
