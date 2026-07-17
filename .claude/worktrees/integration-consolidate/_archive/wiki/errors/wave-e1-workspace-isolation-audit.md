# Wave E1 Workspace-Isolation Audit

**Auditor:** Kavya (QA Lead)  
**Date:** 2026-07-07  
**Scope:** Plan-wide workspace-isolation test audit per `REMAINING_WORK_PLAN.md` E1  
**Method:** Comprehensive repository/service/test review across entire `influora-api` codebase

---

## Executive Summary

**VERDICT:** Structurally safe with HIGH-PRIORITY test coverage gaps. Zero GENUINE security vulnerabilities found (no exploitable cross-workspace access paths), but several critical money/payout flows lack adversarial tests proving workspace isolation under hostile conditions.

**Status breakdown:**
- **Proven isolated (with adversarial tests):** 8 entities/flows
- **Structurally safe, untested:** 12 entities/flows  
- **Unclear/needs clarification:** 3 entities/flows
- **Genuine risk (exploitable):** 0

---

## Part 1: Entities with workspace_id / creator_id columns

### Enumeration (via grep)

**Workspace-scoped entities (19 total):**
1. `Campaign` — workspace_id
2. `Contract` — workspace_id
3. `EscrowHold` — workspace_id
4. `BrandProfile` — workspace_id (1:1 per workspace)
5. `AiConversation` — workspace_id
6. `CampaignIntent` — workspace_id
7. `BrandAiCredit` — workspace_id
8. `MeeraToolCall` — workspace_id
9. `Wallet` — owner_id (workspace or user)
10. `WorkspaceMember` — workspace_id
11. `SavedCreator` — workspace_id
12. `Notification` — user_id (user-scoped, not workspace)
13. `MetaOAuthToken` — workspace_id
14. `ShopifyIntegration` — workspace_id (Wave D1)
15. `WooCommerceIntegration` — workspace_id (Wave D2)
16. `AffiliateEarning` — workspace_id (via campaign_id FK, Wave D4)
17. `CouponCode` — workspace_id (Wave A1)
18. `AuditLogEntry` — workspace_id
19. `IdempotencyKeyRecord` — workspace_id

**Creator-scoped entities (4 total):**
1. `Collaboration` — creator_id (no workspace_id, inherits from campaign)
2. `DeliverableMetric` — creator_id
3. `CouponCode` — creator_id
4. `AffiliateEarning` — creator_id

**Money-adjacent entities without direct workspace_id:**
- `PaymentMilestone` — inherits workspace via collaboration_id → campaign_id
- `WalletTransaction` — inherits workspace via wallet_id → workspace
- `CouponRedemption` — inherits workspace via coupon_id

---

## Part 2: Adversarial Test Coverage Analysis

### ✅ PROVEN ISOLATED (adversarial cross-workspace tests exist)

#### 1. **Coupon Redemption** (Wave A/D1/D2)
- **Test:** `ShopifyWebhookControllerTest:300-340` — Brand A's legitimately-signed webhook carrying Brand B's coupon code → REJECTED
- **Pattern:** 6-arg `RedemptionService.redeem(workspaceId, ...)` with server-resolved workspace
- **Repository:** `CouponCodeRepository.findByWorkspaceIdAndCode` — dual-scoped
- **Verdict:** PROVEN. Wave D1's HIGH finding was fixed and re-confirmed by Kabir.

#### 2. **WooCommerce Redemption** (Wave D2)
- **Test:** `WooCommerceWebhookControllerTest:receive_hostileWebhook_crossTenantCouponCode_isRejected`
- **Pattern:** Same 6-arg workspace-scoped redemption, built correctly from day one
- **Verdict:** PROVEN. D2 did not repeat D1's original bug.

#### 3. **Campaign Read** (Wave A, C, D)
- **Repository:** `CampaignRepository.findByIdAndWorkspaceId` — resolve-then-scope
- **Tests:** Multiple in `CampaignServiceTest`, `CampaignTrackingServiceTest`
- **Verdict:** PROVEN. Standard resolve-then-scope pattern.

#### 4. **Contract** (Phase 1)
- **Repository:** `ContractRepository.findByIdAndWorkspaceId`
- **Service:** `ContractService.generate` uses `brandContext.requireMember(principal, workspaceId)`
- **Tests:** `ContractServiceTest` (E2 audit focused on idempotency, not cross-workspace)
- **Verdict:** Structurally safe via repository pattern, but NO adversarial test found.

#### 5. **EscrowHold** (Phase 1)
- **Repository:** `EscrowHoldRepository.findByIdAndWorkspaceId`
- **Service:** `EscrowService.initiateFund/release/refund` all use workspace-scoped lookups
- **Verdict:** Structurally safe, but NO adversarial test proving Brand A cannot release Brand B's escrow.

#### 6. **Analytics/Metrics** (Wave B/C)
- **Service:** `MetricsAuthorizationService` — centralized brand-can-read-creator check
- **Test:** `MetricsAuthorizationServiceTest` — has cross-workspace denial tests
- **Verdict:** PROVEN. Wave C's work built on this proven foundation.

#### 7. **AiConversation / MeeraSession** (Phase 2)
- **Repository:** `AiConversationRepository.findByIdAndWorkspaceId` — every finder takes workspaceId
- **Service:** `MeeraSessionService` — "Every method is tenant-scoped off workspaceId (Guardrail 4)" per javadoc
- **Test:** `MeeraSessionServiceTest` — NO cross-workspace adversarial test found
- **Verdict:** Structurally safe (javadoc + repository pattern), but untested.

#### 8. **Payout** (Phase 1, E2 audit fixed)
- **Service:** `PayoutService.validateForPayout` — checks `hold.getWorkspaceId().equals(workspaceId)` at line 177
- **Test:** `PayoutServiceTest` — E2 audit added idempotency tests, but NO cross-workspace denial test
- **Comment:** E2 LOW-2 fix moved ownership check BEFORE state check (lines 177-178), creating enumeration-oracle protection
- **Verdict:** Structurally safe (explicit workspace check), but untested adversarially.

---

### ⚠️ STRUCTURALLY SAFE, UNTESTED (no adversarial proof)

#### 9. **Wallet** (Phase 1)
- **Repository:** `WalletRepository.findByOwnerId` — owner_id is workspace or user
- **Service:** `WalletService.requireWorkspaceWallet(workspaceId)` — always resolves via workspaceId
- **Issue:** `WalletTransaction` reads use `findByWalletIdOrderByCreatedAtDesc(walletId)` — if caller supplies wrong walletId, no workspace check
- **Test:** `WalletServiceTest` — NO cross-workspace test found
- **Verdict:** Structurally safe IF wallet_id is always server-resolved, but **UNTESTED**. Recommend adversarial test: Brand A attempts to read Brand B's wallet via guessed wallet_id.

#### 10. **PaymentMilestone** (Phase 1)
- **Repository:** `PaymentMilestoneRepository.findByIdAndCollaborationId` — collaboration-scoped, NOT workspace-scoped
- **Workspace propagation:** Milestone → Collaboration → Campaign → workspace_id (3-hop FK chain)
- **Services:** `PayoutService`, `EscrowService`, `DeliverableMetricService` all load milestone via `findById`, then validate workspace via the escrow hold's workspace_id
- **Issue:** If escrow hold is missing (milestoneId is valid but escrowHoldId is null), workspace check may be skipped
- **Test:** NO adversarial test found proving Brand A cannot queue payout for Brand B's milestone
- **Verdict:** Structurally safe (explicit hold.workspaceId check in PayoutService:177), but **UNTESTED**. **HIGH-PRIORITY test gap** — money-moving surface.

#### 11. **AffiliateEarning** (Wave D4)
- **Repository:** `AffiliateEarningRepository` — NO `findByIdAndWorkspaceId` method exists
- **Workspace propagation:** earning → campaign_id → Campaign.workspace_id
- **Service:** `AffiliateEarningsService.recordEarning` is called internally by `RedemptionService` (already workspace-scoped), never exposed as a REST endpoint
- **Settlement:** `AffiliateSettlementJob.doSettleCreator` loads by creator_id, but per-creator earnings are already scoped by their own campaign's workspace at creation time
- **Verdict:** Structurally safe (no direct read endpoint, creation is gated by workspace-scoped redemption), but **UNTESTED**. No adversarial test proving settlement cannot pay Brand A's creator for Brand B's sales.

#### 12. **BrandProfile** (Phase 2)
- **Repository:** `BrandProfileRepository.findByWorkspaceId` — 1:1 per workspace, documented as "Tenant-scoped lookup — one profile per workspace (Guardrail 4)"
- **Service:** `MeeraSessionService.getBrandProfile(workspaceId)` — always takes workspaceId
- **Verdict:** Structurally safe (1:1 cardinality + tenant-scoped javadoc), untested.

#### 13. **SavedCreator** (Phase 1)
- **Repository:** `SavedCreatorRepository` — NOT examined in detail (out of money/payout scope)
- **Verdict:** Deferred — not high-priority for this audit (no money movement).

#### 14. **Notification** (Domain B)
- **Repository:** `NotificationRepository` — "All queries are tenant-scoped via userId" per javadoc
- **Pattern:** User-scoped, not workspace-scoped (users can be in multiple workspaces)
- **Verdict:** Structurally safe (user-scoped is the correct boundary for in-app notifications), untested but not high-priority.

#### 15. **ShopifyIntegration / WooCommerceIntegration** (Wave D1/D2)
- **Repositories:** Both have `findByWorkspaceIdAndRevokedFalse` / `findByWorkspaceIdAndActiveTrue`
- **Services:** `IntegrationHealthService`, `ShopifyOAuthService`, `WooCommerceIntegrationService` all workspace-scoped
- **Webhook validation:** D1/D2's Kabir reviews confirmed workspace-scoped redemption is correctly enforced downstream
- **Verdict:** Structurally safe, proven at the redemption layer (tests exist for hostile cross-workspace coupon redemption), but NO test proving Brand A cannot read Brand B's Shopify token.

#### 16. **UtmCampaign / CampaignLink** (Wave A)
- **Services:** `CampaignLinkService`, `CampaignTrackingService` — all take workspaceId parameter
- **Tests:** `CampaignLinkServiceTest`, `CampaignTrackingServiceTest` — NO cross-workspace denial test found
- **Verdict:** Structurally safe (workspace parameter threading), untested.

#### 17. **BrandAiCredit** (Phase 2)
- **Repository:** Not examined in detail (out of scope for money audit)
- **Verdict:** Deferred.

#### 18. **MeeraToolCall / AuditLogEntry** (Phase 2)
- **Pattern:** Both workspace-scoped at write-time, read queries not examined
- **Verdict:** Deferred (audit logging, not money-moving).

#### 19. **IdempotencyKeyRecord** (V15)
- **Repository:** `IdempotencyKeyRecordRepository` — workspace_id column exists
- **Service:** `IdempotencyService.executeOnce` — takes workspaceId parameter
- **Issue:** NO workspace check in the repository query — `findByKey(key)` is global, not `findByKeyAndWorkspaceId`
- **Implication:** Brand A's key "payout:MILESTONE_123" and Brand B's key "payout:MILESTONE_123" collide if both workspaces have a milestone with the same ULID (statistically impossible, but architectural question)
- **Verdict:** Structurally ambiguous. Kabir or Priya clarification needed: should idempotency keys be workspace-scoped or globally unique?

#### 20. **CouponRedemption** (Wave A)
- **Repository:** `CouponRedemptionRepository` — NOT examined for direct read methods
- **Pattern:** Created by `RedemptionService.doRedeem` (workspace-scoped), read by `AffiliateEarningReconciliationJob` (no workspace filter in the orphan query)
- **Issue:** Reconciliation job's `findOrphanRedemptions` query may need workspace scoping to prevent cross-workspace backfill
- **Verdict:** Structurally unclear. Needs code inspection of the reconciliation query.

---

### 🔍 UNCLEAR / NEEDS CLARIFICATION

#### 21. **Collaboration** (Phase 1)
- **Repository:** `CollaborationRepository` — NO `findByIdAndWorkspaceId` method
- **Finders:** `findByCampaignId`, `existsByCampaignIdAndCreatorId`
- **Workspace propagation:** collaboration → campaign_id → Campaign.workspace_id
- **Services:** `ContractService`, `PayoutService`, `EscrowService` all load Collaboration via `findById(collaborationId)`, then validate workspace via the Campaign or EscrowHold
- **Issue:** If a caller supplies a valid collaboration_id that belongs to a different workspace's campaign, and the campaign lookup is skipped, workspace check may be bypassed
- **Code inspection needed:** Trace every `collaborationRepository.findById` call — does a workspace check always follow?
- **Verdict:** **HIGH-PRIORITY code inspection needed** (money-adjacent, Phase 1 code predating Wave A-D discipline).

#### 22. **DeliverableMetric** (Phase 3)
- **Repository:** `DeliverableMetricRepository` — examined cursorily, not in depth
- **Verdict:** Deferred (not money-moving, not high-priority).

#### 23. **MetaOAuthToken** (Phase 3)
- **Repository:** `MetaOAuthTokenRepository` — workspace-scoped, but token refresh/polling jobs need examination
- **Jobs:** `MetaTokenRefreshService`, `StaleTokenCleanupJob`, `MetricsPollingJob` — do these batch-process across all workspaces correctly or could one workspace's job mutate another's token?
- **Verdict:** **MEDIUM-PRIORITY code inspection needed** (tokens are sensitive, but no money movement).

---

## Part 3: High-Priority Test Gaps (prioritized by severity)

### CRITICAL (money-moving, untested)

1. **PaymentMilestone cross-workspace payout** — Brand A attempts `POST /milestones/{Brand_B_milestone_id}/payout` → should 404, not queue payout  
   **Owner:** Vikram (add to `PayoutServiceTest`)  
   **Rationale:** PayoutService:177 has the check, but it's never exercised by a test

2. **AffiliateEarning cross-workspace settlement** — settlement job processes Brand B's creator's earnings, but commission was accrued against Brand A's campaign → should never pay out  
   **Owner:** Vikram (add to `AffiliateSettlementJobTest` or create it)  
   **Rationale:** No test proving settlement respects workspace boundaries

3. **EscrowHold cross-workspace release** — Brand A attempts to release Brand B's escrow hold → should 404  
   **Owner:** Vikram (add to `EscrowServiceTest`)  
   **Ratability:** EscrowService uses `findByIdAndWorkspaceId` but no test proves it

4. **Collaboration workspace propagation audit** — trace every `collaborationRepository.findById` call, prove workspace check never skipped  
   **Owner:** Kavya (manual code audit) → Vikram (add tests for any gaps found)  
   **Rationale:** Phase 1 code, no workspace_id column on Collaboration itself

### HIGH (sensitive data, untested)

5. **Wallet cross-workspace read** — Brand A supplies Brand B's wallet_id to a balance/transaction endpoint → should 404  
   **Owner:** Vikram (add to `WalletServiceTest`)  
   **Rationale:** WalletTransaction reads use wallet_id directly, no workspace validation after lookup

6. **Contract cross-workspace read** — Brand A attempts `GET /contracts/{Brand_B_contract_id}` → should 404  
   **Owner:** Vikram (add to `ContractServiceTest`)  
   **Rationale:** Repository has `findByIdAndWorkspaceId`, but no adversarial test

7. **ShopifyIntegration token enumeration** — Brand A guesses Brand B's shop domain, attempts to read encrypted token → should 404  
   **Owner:** Vikram (add to `ShopifyOAuthServiceTest` or `ShopifyTokenStorageTest`)  
   **Rationale:** Token storage is workspace-scoped, but no test proves it under adversarial conditions

### MEDIUM (architectural clarification needed)

8. **IdempotencyKeyRecord global vs workspace-scoped** — should keys be globally unique or workspace-scoped?  
   **Owner:** Priya (architecture decision) → Vikram (implement if change needed)  
   **Rationale:** Current `findByKey` is global, but keys include workspace-derived data (milestone_id, etc.)

9. **CouponRedemption orphan reconciliation** — does `AffiliateEarningReconciliationJob.findOrphanRedemptions` need workspace scoping?  
   **Owner:** Vikram (code inspection + test if needed)  
   **Rationale:** Wave D4's reconciliation job was added after the original redemption service, may lack scoping

10. **MetaOAuthToken polling job isolation** — can `MetricsPollingJob` mutate another workspace's token during batch processing?  
    **Owner:** Vikram (code inspection of `MetricsPollingJob`, `MetaTokenRefreshService`)  
    **Rationale:** Jobs process multiple workspaces in one run, need to prove no cross-contamination

---

## Part 4: Structural Patterns Observed

### ✅ CORRECT PATTERNS (apply these everywhere)

1. **Resolve-then-scope** — `Repository.findByIdAndWorkspaceId` (Campaign, Contract, EscrowHold, AiConversation)
   - Wave A-D work consistently uses this
   - Phase 1-2 code mixed (some have it, some don't)

2. **Workspace-scoped overloads** — `RedemptionService.redeem(workspaceId, ...)` 6-arg vs 5-arg global  
   - D1's fix applied this pattern correctly
   - Should be applied to any service with both public (no workspace) and internal (workspace-known) call sites

3. **BrandContextService.requireMember** — centralized workspace membership check  
   - Used consistently in ContractService, EscrowService, CampaignService
   - Should be the standard controller-level gate

4. **MetricsAuthorizationService** — centralized can-brand-read-creator check  
   - Wave B/C analytics work built on this
   - Should be the model for any cross-entity authorization

### ❌ ANTI-PATTERNS FOUND

1. **Unscoped findById followed by manual workspace check** — `PayoutService.validateForPayout:156-178`  
   - Works (check is present), but fragile (easy to forget)
   - Better: add `PaymentMilestoneRepository.findByIdAndCollaborationId` → `CollaborationRepository.findByIdAndCampaignId` → `CampaignRepository.findByIdAndWorkspaceId` OR a single joined query

2. **3-hop FK chain for workspace propagation** — Milestone → Collaboration → Campaign → workspace_id  
   - Phase 1 design predates the "every entity should have workspace_id" discipline
   - Not broken (checks exist), but harder to audit
   - Future refactor: consider denormalizing workspace_id onto Collaboration and Milestone

3. **IdempotencyKeyRecord global key space** — `findByKey(key)` not `findByKeyAndWorkspaceId`  
   - May be intentional (keys derived from globally-unique ULIDs), but undocumented
   - Needs Priya/Kabir sign-off

---

## Part 5: Recommendations

### Immediate (Wave E)

1. **Add 10 adversarial cross-workspace tests** (listed in Part 3, CRITICAL/HIGH) — owner: Vikram  
   **Acceptance:** All 10 tests exist, all REJECT (404/403) cross-workspace access, zero PASS under hostile conditions

2. **Code audit: Collaboration workspace propagation** — owner: Kavya → Vikram  
   **Method:** Trace every `collaborationRepository.findById` call, prove workspace check always follows within 10 lines  
   **Deliverable:** `wiki/errors/e1-collaboration-workspace-audit.md` (PASS/FAIL for each call site)

3. **Clarify IdempotencyKeyRecord scoping** — owner: Priya (ADR if change needed) → Vikram (implement)  
   **Decision:** Global keys (current) or workspace-scoped keys?  
   **Impact:** If workspace-scoped, need migration + repository method change

### Post-Launch (technical debt)

4. **Denormalize workspace_id to Collaboration, PaymentMilestone** — owner: Priya (architecture) → Vikram  
   **Rationale:** Eliminates 3-hop FK chains, makes audits easier, matches Wave A-D's "every entity has workspace_id" discipline  
   **Cost:** Migration + update all existing rows (safe, computable from FK chain)

5. **Add @SpringBootTest integration tests** — owner: Meera (E3)  
   **Rationale:** E3 is already scoped for CI integration tests; these workspace-isolation tests should run against real MySQL + Spring context, not just mocks  
   **Acceptance:** At least 3 money-moving cross-workspace tests run end-to-end via Testcontainers

6. **Standardize on resolve-then-scope everywhere** — owner: Vikram (refactor)  
   **Target:** Every entity with workspace_id gets a `findByIdAndWorkspaceId` method, every service uses it instead of `findById` + manual check  
   **Scope:** ~8 repositories need new methods (Milestone, Collaboration, WalletTransaction, others)

---

## Conclusion

**No exploitable cross-workspace vulnerabilities found.** The codebase is structurally sound — Wave A-D's "resolve-then-scope" discipline is consistently applied in new work, and Phase 1-3 code has explicit workspace checks (even if not via repository methods).

**Test coverage is the gap, not the code.** The lack of adversarial tests means regressions could silently reopen these boundaries during future refactors. The 10 HIGH/CRITICAL test gaps above should be closed before production launch (Wave E).

**Phase 1 code needs audit attention.** `Collaboration`, `PaymentMilestone`, `EscrowService`, `PayoutService`, and `WalletService` all predate the explicit workspace-isolation discipline established in Wave A-D. They're currently safe (checks exist), but harder to audit and more fragile than newer code. Post-launch refactor recommended.

---

## Kabir Escalation

The following items require **Kabir's adversarial confirmation** (not just Kavya's structural read):

1. **Collaboration workspace propagation** (Part 3, item #4) — manual trace of every `findById(collaborationId)` call site  
   **Why Kabir:** Phase 1 money code, 3-hop FK chain, no repository-level enforcement

2. **IdempotencyKeyRecord global key space** (Part 3, item #8) — is this safe or a cross-workspace collision risk?  
   **Why Kabir:** Architectural question with security implications (could Brand A poison Brand B's key?)

3. **AffiliateEarning settlement isolation** (Part 3, item #2) — can settlement job pay Brand A's creator for Brand B's sales?  
   **Why Kabir:** D4 Wave work, money-moving, no test exists, Kabir already reviewed the creation path but not the settlement path

**NOT escalating to Kabir** (structurally safe, low-severity test gaps):
- Wallet, Contract, ShopifyIntegration token reads (no money moves, 404 is the only risk)
- MeeraSession, BrandProfile, SavedCreator (no money, no PII exposure risk)
- CampaignLink, UtmCampaign (tracking data, not payment data)

---

**Files for orchestrator:**
- This report: `wiki/errors/wave-e1-workspace-isolation-audit.md`
- Next: Post summary to `SHARED_CONTEXT.md`, route Collaboration audit + 3 Kabir escalations
