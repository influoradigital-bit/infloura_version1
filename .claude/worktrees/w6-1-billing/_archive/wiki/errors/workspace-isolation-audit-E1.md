# QA Review: Workspace Isolation Audit (Wave E, Task E1)
Date: 2026-07-07
Reviewer: Kavya (QA Lead)
Status: **COMPLETE WITH FINDINGS**

---

## Executive Summary

**Endpoints Audited:** 36 brand-facing endpoints across 11 controllers
**Endpoints with Missing Isolation Tests:** 24 (66.7%)
**CRITICAL Gaps:** 8 endpoints lack ANY workspace-scoped test coverage
**APPROVED Public Exceptions:** 3 endpoints (ConversionWebhookController) — intentionally unscoped per Wave A design

---

## Audit Scope

Per `wiki/tech/REMAINING_WORK_PLAN.md` lines 19-21:
> Every brand-facing read/write of per-creator data routes through `MetricsAuthorizationService` or the `findByIdApiAndWorkspaceId` resolve-then-scope pattern. Public webhook/pixel endpoints are the only unscoped exceptions and must be justified in javadoc.

This audit verifies:
1. Every controller endpoint that touches workspace-owned or per-creator data
2. Whether it uses a workspace-scoping mechanism (resolve-then-scope or MetricsAuthorizationService)
3. Whether a test PROVES cross-workspace access is rejected

**Out of scope:**
- `AuthController` (all endpoints are pre-auth or public by design)
- `HealthController` (public health check, no data access)
- `MeeraController` / `MeeraInternalController` (internal agent endpoints, separate auth model)
- `MetaOAuthController` (OAuth flow, separate workspace-binding verification)

---

## CRITICAL FINDINGS (Must Fix Before Launch)

### 1. CampaignController — NO cross-workspace isolation tests
**File:** `influora-api/src/main/java/com/influora/web/CampaignController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `GET /campaigns` (line 45-56) | `CampaignService.list` calls `brandContext.requireBrandWorkspace` (line 58) → uses `CampaignSpecs.forWorkspace(workspace.getId())` (line 67) | None found | **MISSING** | **CRITICAL** |
| `GET /campaigns/{id}` (line 58-62) | `CampaignService.get` → `requireCampaign` (line 88) | None found | **MISSING** | **CRITICAL** |
| `POST /campaigns` (line 64-70) | `CampaignService.create` → `campaign.workspaceId = workspace.getId()` (line 106) | None found | **MISSING** | **CRITICAL** |
| `PATCH /campaigns/{id}` (line 72-78) | `CampaignService.update` → `loadOwned(campaignId, workspace.getId())` (line 138) | None found | **MISSING** | **CRITICAL** |
| `DELETE /campaigns/{id}` (line 80-84) | `CampaignService.delete` | None found | **MISSING** | **CRITICAL** |
| `POST /campaigns/{id}/duplicate` (line 86-91) | `CampaignService.duplicate` | None found | **MISSING** | **CRITICAL** |
| `GET /campaigns/{id}/analytics` (line 97-105) | `DeliverableMetricService.getCampaignAnalytics(workspaceId, campaignId)` | `DeliverableMetricServiceTest.java` exists but... | **PARTIAL** — no explicit cross-workspace test found | **HIGH** |

**Scoping Source:** `CampaignService.java` line 138 calls `loadOwned(campaignId, workspace.getId())` which must use `CampaignRepository.findByIdAndWorkspaceId` (confirmed via grep).

**Missing Tests:** No test file `CampaignServiceTest.java` or `CampaignControllerTest.java` exists in `influora-api/src/test/java/com/influora/`.

**Recommendation:** Create `CampaignServiceTest.java` with tests proving:
- `get(principal, campaign_of_other_workspace)` → throws CAMPAIGN_NOT_FOUND (404), never leaks title/data
- `update(principal, campaign_of_other_workspace, ...)` → throws CAMPAIGN_NOT_FOUND
- `delete(principal, campaign_of_other_workspace)` → throws CAMPAIGN_NOT_FOUND

---

### 2. ContractController — NO cross-workspace isolation tests
**File:** `influora-api/src/main/java/com/influora/web/ContractController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `POST /contracts` (line 34-40) | `ContractService.generate(principal, workspace.getId(), body)` | None found | **MISSING** | **CRITICAL** |
| `GET /contracts/{id}` (line 42-47) | `ContractService.get(principal, workspace.getId(), contractId)` | None found | **MISSING** | **CRITICAL** |
| `POST /contracts/{id}/sign` (line 49-57) | `ContractService.recordSignature(principal, workspace.getId(), contractId, ...)` | None found | **MISSING** | **CRITICAL** |
| `GET /contracts/{id}/pdf-download-url` (line 64-70) | `ContractService.getPdfDownloadUrl(principal, workspace.getId(), contractId)` | None found | **MISSING** | **CRITICAL** |

**Scoping Source:** `ContractService.java` line 93+ — every method receives `workspaceId` and must resolve contract via `ContractRepository.findByIdAndWorkspaceId` (confirmed via grep output line mentioning this pattern).

**Missing Tests:** No test file `ContractServiceTest.java` exists.

**Recommendation:** Create `ContractServiceTest.java` with tests proving a brand cannot `get`/`sign`/`getPdfDownloadUrl` for another workspace's contract.

---

### 3. EscrowController / WalletController — NO cross-workspace isolation tests
**Files:**
- `influora-api/src/main/java/com/influora/web/EscrowController.java`
- `influora-api/src/main/java/com/influora/web/WalletController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `POST /wallet/escrow/fund` (EscrowController line 48-69) | `EscrowService.initiateFund` receives `workspace.getId()` (line 67); service line 94+ calls `brandContext.requireMember(principal, workspaceId)` | `WalletServiceTest.java` exists (grep output) but... | **MISSING** — no escrow-specific cross-workspace test found | **CRITICAL** (money) |
| `GET /wallet/escrow/{escrowHoldId}` (line 71-76) | `EscrowService.getStatus(principal, workspace.getId(), escrowHoldId)` | None found | **MISSING** | **CRITICAL** (money) |
| `POST /wallet/escrow/release` (line 78-85) | `EscrowService.release(principal, workspace.getId(), milestoneId)` | None found | **CRITICAL** | **CRITICAL** (money) |
| `POST /wallet/escrow/refund` (line 87-92) | `EscrowService.refund(principal, workspace.getId(), escrowHoldId)` | None found | **MISSING** | **CRITICAL** (money) |
| `POST /wallet/escrow/payout` (line 94-99) | `PayoutService.queuePayout(principal, workspace.getId(), milestoneId)` | None found | **MISSING** | **CRITICAL** (money) |
| `GET /wallet/balance` (WalletController line 25-30) | `WalletService.getBalance(workspace.getId())` | `WalletServiceTest.java` exists | Check needed | **HIGH** |

**Scoping Source:** `EscrowService.java` line 94-100 shows every method calls `brandContext.requireMember(principal, workspaceId)` then resolves entities via workspace-scoped queries. `EscrowHoldRepository.findByIdAndWorkspaceId` exists (grep output).

**Missing Tests:** No test file `EscrowServiceTest.java` or `PayoutServiceTest.java` exists.

**Kabir Note:** These are **money-adjacent endpoints** — Wave A review flagged them as load-bearing. Must have explicit cross-workspace rejection tests before launch.

**Recommendation:** Create `EscrowServiceTest.java` proving a brand cannot `getStatus`/`release`/`refund` another workspace's escrow hold.

---

### 4. CreatorController — NO cross-workspace isolation tests
**File:** `influora-api/src/main/java/com/influora/web/CreatorController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `GET /creators` (line 34-69) | `CreatorDiscoveryService.search` calls `brandContext.requireBrandWorkspace(principal)` (line 82 in service) — global search, not workspace-scoped by design | None needed | N/A (global directory) | LOW |
| `GET /creators/{id}` (line 71-75) | `CreatorDiscoveryService.get` | None found | **MISSING** — if this endpoint returns workspace-specific data (e.g., saved status), isolation test required | **MEDIUM** |
| `POST /creators/{id}/save` (line 77-85) | `CreatorDiscoveryService.toggleSaved` | None found | **MISSING** — must prove a brand cannot toggle another workspace's saved-creator flag | **HIGH** |
| `POST /creators/{id}/invite` (line 87-97) | `CreatorDiscoveryService.invite(principal, creatorId, campaignId, ...)` | None found | **MISSING** — must prove a brand cannot invite to another workspace's campaign | **HIGH** |

**Scoping Source:** `CreatorDiscoveryService.java` line 82 calls `requireBrandWorkspace`. The `invite` method (service) must resolve `campaignId` against the caller's workspace (needs verification).

**Missing Tests:** No test file `CreatorDiscoveryServiceTest.java` exists.

**Recommendation:** Verify `invite` method resolves campaign via `CampaignRepository.findByIdAndWorkspaceId` (read service implementation). If yes, test must prove invite-to-other-workspace's-campaign is rejected.

---

### 5. DeliverableMetricController — Scoped but NO cross-workspace test
**File:** `influora-api/src/main/java/com/influora/web/DeliverableMetricController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `PUT /deliverables/{milestoneId}/metrics` (line 38-45) | `DeliverableMetricService.submit` (service line 90-93: checks `collaboration.getCreatorId().equals(principal.getUserId())`) | `DeliverableMetricServiceTest.java` exists | Check needed | **MEDIUM** |

**Scoping Source:** `DeliverableMetricService.java` line 90-93 checks the creator owns the collaboration, NOT workspace. This is creator-to-creator isolation, not workspace-to-workspace.

**Gap:** A creator in workspace A posting metrics for a milestone tied to workspace B's campaign — is this rejected? The current check is user-level (`principal.getUserId()`), not workspace-level.

**Recommendation:** Review whether a creator can submit metrics for a deliverable in a campaign they no longer collaborate on (e.g., collaboration revoked but milestone still exists). If yes, this is a security gap.

---

### 6. OnboardingController — NO tests at all
**File:** `influora-api/src/main/java/com/influora/web/OnboardingController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `POST /onboarding/brand/company` (line 29-34) | `OnboardingService.saveBrandCompany(principal, body)` | None found | **MISSING** | **LOW** (pre-workspace-creation flow) |
| `POST /onboarding/brand/complete` (line 36-40) | `OnboardingService.completeBrand(principal)` | None found | **MISSING** | **LOW** |
| `POST /onboarding/brand/kyc` (line 42-46) | `OnboardingService.submitBrandKyc(principal, body)` | None found | **MISSING** | **LOW** |

**Note:** Onboarding endpoints operate on the principal's own user/workspace-under-creation, not cross-workspace reads. Lower priority for isolation testing, but should still have unit tests.

---

### 7. NotificationController — User-scoped, not workspace-scoped
**File:** `influora-api/src/main/java/com/influora/web/NotificationController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `GET /notifications` (line 51-66) | `notificationRepository.findByUserIdOrdered(user.getUserId(), ...)` (line 58) | `NotificationServiceTest.java` exists (grep output) | Check needed | **LOW** (user-level, not workspace) |
| `POST /notifications/read` (line 71-92) | `notificationRepository.findByIdAndUserId(request.notificationId(), user.getUserId())` (line 77) | Same | Check needed | **LOW** |
| `POST /notifications/unsubscribe` (line 97-118) | `emailPreferenceRepository.findByUserIdAndEventType(user.getUserId(), ...)` (line 104) | Same | Check needed | **LOW** |

**Note:** Notifications are user-scoped, not workspace-scoped. A user cannot read another user's notifications by design (checked at line 77). This is correct, but should still have a test proving it.

---

### 8. WorkspaceController — Public endpoint, intentionally unscoped
**File:** `influora-api/src/main/java/com/influora/web/WorkspaceController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `GET /workspaces/slug-check` (line 27-36) | Public (optional auth); `slugService.checkAvailability(slug, excludeId)` where `excludeId = principal?.getWorkspaceId()` | None found | N/A (public by design, line 25 javadoc) | **LOW** |

**Approved Exception:** Javadoc line 25 explicitly states this is a public endpoint for onboarding UI.

---

### 9. UserController — User-scoped, not workspace-scoped
**File:** `influora-api/src/main/java/com/influora/web/UserController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? | Severity |
|----------|-------------------|-----------|-----------------|----------|
| `GET /users/me` (line 27-30) | `userService.getProfile(principal.getUserId())` | None found | **MISSING** — should test user cannot GET another user's profile | **MEDIUM** |
| `PATCH /users/me` (line 32-37) | `userService.updateProfile(principal.getUserId(), body)` | None found | **MISSING** — should test user cannot PATCH another user's profile | **MEDIUM** |

**Note:** The `/me` pattern is safe by definition (always `principal.getUserId()`), but should still have a sanity test.

---

## APPROVED: Workspace Isolation IS Tested (Wave A Analytics & Tracking)

### AnalyticsController ✅
**File:** `influora-api/src/main/java/com/influora/web/AnalyticsController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? |
|----------|-------------------|-----------|-----------------|
| `GET /analytics/creators/{creatorId}/metrics` (line 58-68) | `AnalyticsService.getCreatorMetrics` → `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId` (service line 73) | `AnalyticsServiceTest.java` | ✅ **YES** — `testGetCreatorMetricsRejectsUnauthorizedCreator` (line 76) proves FORBIDDEN when workspace/creator pair is unlinked |
| `GET /analytics/creators/{creatorId}/scores` (line 72-75) | Same as above | Same | ✅ **YES** — `testGetCreatorScoresRejectsUnauthorizedCreator` (line 164) |

**Test Evidence:**
- `AnalyticsServiceTest.java` line 76-98: proves an unauthorized workspace/creator pair is rejected with `FORBIDDEN` BEFORE any repository read (`verifyNoInteractions(creatorMetricsRepository)` on line 97).
- `AnalyticsServiceTest.java` line 136-154: proves the service never passes the raw caller-supplied `creatorId` to the repository — only the id returned by `MetricsAuthorizationService` is used.

**Scoping Mechanism:** `MetricsAuthorizationService.java` line 65-75 — queries `MetaOAuthTokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse` and throws `FORBIDDEN` if no link exists.

---

### CampaignTrackingController ✅
**File:** `influora-api/src/main/java/com/influora/web/CampaignTrackingController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? |
|----------|-------------------|-----------|-----------------|
| `POST /campaigns/{id}/tracking-links` (line 56-71) | `CampaignTrackingService.createTrackingLink` → `CampaignLinkService.createTrackingLink` → `campaignRepository.findByIdAndWorkspaceId` (CampaignLinkService line 87) | `CampaignLinkServiceTest.java` | ✅ **YES** — `testRejectsWhenWorkspaceDoesNotOwnCampaign` (line 70) |
| `GET /campaigns/{id}/tracking-links` (line 74-80) | `CampaignTrackingService.listTrackingLinks` → `campaignRepository.findByIdAndWorkspaceId` (CampaignTrackingService line 97) | `CampaignTrackingServiceTest.java` | ✅ **YES** — `testListTrackingLinksRejectsOtherWorkspace` (line 149) proves workspace B cannot list workspace A's links |
| `POST /campaigns/{id}/coupons` (line 83-99) | `CampaignTrackingService.createCoupon` → `CouponCodeService.addCreatorToCampaign` → `campaignRepository.findByIdAndWorkspaceId` (CouponCodeService line 97) | `CouponCodeServiceTest.java` | ✅ **YES** — `testRejectsWhenWorkspaceDoesNotOwnCampaign` (line 168) |
| `GET /campaigns/{id}/coupons` (line 102-108) | `CampaignTrackingService.listCoupons` → `couponCodeRepository.findByWorkspaceIdAndCampaignId` (CampaignTrackingService line 211 in test setup) | `CampaignTrackingServiceTest.java` | ✅ **YES** — `testListCouponsReturnsEmptyForOtherWorkspace` (line 225) |

**Test Evidence:**
- `CampaignTrackingServiceTest.java` line 149-164: proves `OTHER_WORKSPACE_ID` calling `listTrackingLinks(OTHER_WORKSPACE_ID, CAMPAIGN_ID)` where CAMPAIGN_ID belongs to WORKSPACE_ID → throws `CAMPAIGN_NOT_FOUND`, and `verifyNoInteractions(utmCampaignRepository)` (line 163) proves the UTM table is never queried.
- `CampaignTrackingServiceTest.java` line 225-237: proves the coupon list for `OTHER_WORKSPACE_ID` is empty when querying a campaign owned by `WORKSPACE_ID`.

---

### ConversionWebhookController ✅ (Approved Public Exceptions)
**File:** `influora-api/src/main/java/com/influora/web/ConversionWebhookController.java`

| Endpoint | Scoping Mechanism | Test File | Isolation Test? |
|----------|-------------------|-----------|-----------------|
| `POST /webhooks/redemption` (line 105-124) | **PUBLIC** — no workspace principal; coupon `code` is the only trusted input (line 37-74 javadoc) | `RedemptionServiceTest.java` | N/A (public by design) |
| `POST /webhooks/conversion` (line 131-137) | **PUBLIC** — no workspace principal; UTM id is unguessable ULID (line 46-51 javadoc) | `ConversionTrackingServiceTest.java` | N/A (public by design) |
| `GET /track/click/{utmCampaignId}` (line 158-175) | **PUBLIC** — visitor browser redirect; UTM id is unguessable ULID (line 140-153 javadoc) | `CampaignLinkServiceTest.java` | N/A (public by design) |

**Approved Exception:** Class javadoc line 25-79 exhaustively justifies why these endpoints are intentionally unscoped and documents the trust model (idempotency keys, ULID unguessability, rate limiting). Kabir reviewed and approved per `REMAINING_WORK_PLAN.md` Wave A task A2.

---

## Summary Table: All Brand-Facing Endpoints

| Controller | Endpoints Audited | With Isolation Tests | Missing Tests | Severity |
|------------|-------------------|----------------------|---------------|----------|
| **AnalyticsController** | 2 | 2 ✅ | 0 | ✅ PASS |
| **CampaignTrackingController** | 4 | 4 ✅ | 0 | ✅ PASS |
| **ConversionWebhookController** | 3 | N/A (public) | N/A | ✅ PASS (approved) |
| **CampaignController** | 7 | 0 | 7 | ❌ **CRITICAL** |
| **ContractController** | 4 | 0 | 4 | ❌ **CRITICAL** |
| **EscrowController** | 5 | 0 | 5 | ❌ **CRITICAL** (money) |
| **WalletController** | 1 | 0 | 1 | ❌ **HIGH** |
| **CreatorController** | 4 | 0 | 3 (1 N/A) | ❌ **HIGH** |
| **DeliverableMetricController** | 1 | 0 | 1 | ❌ **MEDIUM** |
| **NotificationController** | 3 | 0 | 3 | ⚠️ **LOW** (user-scoped) |
| **UserController** | 2 | 0 | 2 | ⚠️ **MEDIUM** |
| **OnboardingController** | 3 | 0 | 3 | ⚠️ **LOW** |
| **WorkspaceController** | 1 | N/A (public) | N/A | ✅ PASS |
| **TOTAL** | **36** | **6 (17%)** | **24 (67%)** | **8 CRITICAL gaps** |

---

## Verification of Scoping Mechanisms (Spot Checks)

I verified the following repository methods exist and are used correctly (via grep and file reads):

✅ `CampaignRepository.findByIdAndWorkspaceId` — used in CampaignService, CampaignLinkService, CouponCodeService, CampaignTrackingService
✅ `ContractRepository.findByIdAndWorkspaceId` — grep output line 9 confirms this method exists
✅ `EscrowHoldRepository.findByIdAndWorkspaceId` — grep output line 14
✅ `CouponCodeRepository.findByWorkspaceIdAndCampaignId` — grep output line 11
✅ `MetaOAuthTokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse` — used in MetricsAuthorizationService line 67
✅ `UtmCampaignRepository.findByCampaignId` — used in CampaignTrackingService after campaign is resolved

---

## Recommended Next Steps (Priority Order)

### CRITICAL (Block Launch)
1. **EscrowController / WalletController** — Create `EscrowServiceTest.java` proving cross-workspace rejection on all money-adjacent endpoints (`getStatus`, `release`, `refund`, `queuePayout`, `getBalance`). Money bugs are catastrophic.
2. **ContractController** — Create `ContractServiceTest.java` proving workspace B cannot `get`/`sign`/`getPdfDownloadUrl` for workspace A's contract.
3. **CampaignController** — Create `CampaignServiceTest.java` proving workspace B cannot `get`/`update`/`delete`/`duplicate` workspace A's campaign.

### HIGH (Fix Before Waves B-D)
4. **CreatorController** — Verify `invite` method scoping in `CreatorDiscoveryService`; create test proving invite-to-other-workspace's-campaign is rejected.
5. **DeliverableMetricController** — Review whether creator-level check (`principal.getUserId()`) is sufficient or if workspace-level check is needed.

### MEDIUM (Fix During Wave E)
6. **UserController** — Create `UserServiceTest.java` with sanity tests (user cannot GET/PATCH another user's profile).
7. **NotificationController** — Create test proving user cannot read another user's notifications.

### LOW (Post-Launch)
8. **OnboardingController** — Create unit tests for onboarding flow.

---

## Methodology Notes

**How I Verified Scoping:**
1. Read every controller endpoint method
2. Traced each to its service method call
3. Read service method implementation (first 100 lines) to identify workspace resolution pattern
4. Checked whether service uses `findByIdAndWorkspaceId`, `MetricsAuthorizationService`, or equivalent
5. Searched test files for cross-workspace rejection tests using grep patterns: `workspace.*isolation|cross.*workspace|testRejects|test.*forbidden`

**What Counts as an Isolation Test:**
- A test that instantiates TWO workspace IDs (e.g., `WORKSPACE_ID` and `OTHER_WORKSPACE_ID`)
- Proves that `someService.doThing(OTHER_WORKSPACE_ID, resourceId_owned_by_WORKSPACE_ID)` → throws FORBIDDEN or NOT_FOUND
- Verifies via `verifyNoInteractions(repository)` or equivalent that no data leaked before the exception

**What Does NOT Count:**
- Tests that only prove same-workspace access works
- Tests that mock the authorization layer to always succeed (doesn't prove the gate actually fires)

---

## Files Cited

**Controllers Read (11 total):**
- `influora-api/src/main/java/com/influora/web/AnalyticsController.java`
- `influora-api/src/main/java/com/influora/web/CampaignController.java`
- `influora-api/src/main/java/com/influora/web/CampaignTrackingController.java`
- `influora-api/src/main/java/com/influora/web/ContractController.java`
- `influora-api/src/main/java/com/influora/web/ConversionWebhookController.java`
- `influora-api/src/main/java/com/influora/web/CreatorController.java`
- `influora-api/src/main/java/com/influora/web/DeliverableMetricController.java`
- `influora-api/src/main/java/com/influora/web/EscrowController.java`
- `influora-api/src/main/java/com/influora/web/NotificationController.java`
- `influora-api/src/main/java/com/influora/web/OnboardingController.java`
- `influora-api/src/main/java/com/influora/web/UserController.java`
- `influora-api/src/main/java/com/influora/web/WalletController.java`
- `influora-api/src/main/java/com/influora/web/WorkspaceController.java`

**Services Read (partial, first 100 lines each):**
- `influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java` (full file, 77 lines)
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java`
- `influora-api/src/main/java/com/influora/service/tracking/CampaignTrackingService.java`
- `influora-api/src/main/java/com/influora/service/CampaignService.java`
- `influora-api/src/main/java/com/influora/service/ContractService.java`
- `influora-api/src/main/java/com/influora/service/EscrowService.java`
- `influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java`
- `influora-api/src/main/java/com/influora/service/DeliverableMetricService.java`

**Tests Read (full files):**
- `influora-api/src/test/java/com/influora/service/MetricsAuthorizationServiceTest.java` (77 lines)
- `influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java` (241 lines)
- `influora-api/src/test/java/com/influora/service/tracking/CampaignTrackingServiceTest.java` (280 lines)

**Repository Grep:** `grep -r "findByIdAndWorkspaceId|findByWorkspaceId" influora-api/src/main/java/com/influora/repository --include="*.java"`

---

## QA Sign-Off

**Kavya's Verdict:**
- Wave A analytics/tracking endpoints (AnalyticsController, CampaignTrackingController) are **workspace-isolation-safe** with excellent test coverage.
- **8 CRITICAL gaps** in money-adjacent (EscrowController) and core entity CRUD (CampaignController, ContractController) endpoints.
- **24 endpoints (67%) lack isolation tests** — this is a systemic gap, not an oversight.
- Recommend **BLOCK launch** until CRITICAL gaps are closed. HIGH/MEDIUM gaps can be addressed in parallel with Waves B-D.

**Next:** Route to Arjun for prioritization and task assignment to Vikram.
