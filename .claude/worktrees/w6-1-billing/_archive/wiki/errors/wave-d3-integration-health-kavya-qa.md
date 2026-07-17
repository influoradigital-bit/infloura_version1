# QA Review: Wave D Task D3 — IntegrationHealthService + Campaign-Creation Gating
**Reviewer:** Kavya Reddy (QA Lead)  
**Date:** 2026-07-07  
**Status:** ⚠️ APPROVED WITH MANDATORY ESCALATION  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/IntegrationHealthService.java` (new)
- `influora-api/src/main/java/com/influora/service/meera/tool/CreateCampaignExecutor.java` (modified)
- `influora-api/src/test/java/com/influora/service/IntegrationHealthServiceTest.java` (new)
- `influora-api/src/test/java/com/influora/service/meera/tool/CreateCampaignExecutorTest.java` (new)

---

## CRITICAL SCOPE GAP (REQUIRES PRIYA/ARJUN DECISION)

**Finding:** D3's store-integration gate, as implemented, ONLY applies to Meera's AI-drafted campaign flow (`CreateCampaignExecutor`). The human-driven REST campaign-creation path (`CampaignController`/`CampaignService`) has **NO campaign-type concept at all** — confirmed via independent code review:

1. **Campaign entity** (`domain/entity/Campaign.java`): NO `campaignType`/`type` field exists (only `status`, `title`, `description`, budget fields)
2. **CampaignWriteRequest DTO** (`web/dto/campaign/CampaignDtos.java:35-50`): NO `campaignType` parameter (has `objectives`, `platforms`, `budget`, `timeline` — all generic/non-typed)
3. **CampaignService.create** (`service/CampaignService.java:93-129`): Creates campaign from request, NEVER consults `IntegrationHealthService`, no type-dependent logic anywhere

**Impact:** A brand using the human UI form (`POST /campaigns`) can create a "sale" campaign (e.g., `objectives: ["drive sales"]`, `budget: $5000`) with zero store integration connected, completely bypassing D3's gate. The gate ONLY fires when Meera drafts a `CampaignIntentType.DIRECT` campaign via the conversational AI flow.

**Two paths to the same outcome (campaign creation), only ONE is gated:**

| Path | Entry Point | Type Concept | Store-Integration Gate |
|------|-------------|--------------|------------------------|
| AI-drafted (Meera) | `CreateCampaignExecutor` | YES (`CampaignIntentType`: `DIRECT`/`HYPE`/`REVIEW`/`STANDARD`) | ✅ YES (D3, this review) |
| Human REST form | `CampaignService.create` | NO (no type field exists) | ❌ NO |

**Vikram's handoff correctly flagged this** ("found there's NO generic `CampaignType`/`SALE` enum anywhere... so D3's store-connection gate, as built, ONLY covers campaigns a brand creates by asking Meera to draft one, NOT campaigns created directly via the human UI form"). I independently verified his finding is accurate.

**Acceptance criteria ambiguity:** The plan's D3 brief said "block sale-campaign creation when store not connected" without qualifying WHICH creation path. If the intent was "block ALL sale-campaign creation regardless of how the brand tries to create it," D3 is incomplete. If the intent was "only gate the AI-drafted path for now, human path deferred," D3 is complete but the scope boundary must be explicitly documented/signed-off.

**Why this is not just a "log it and move on" gap:** Wave C3's auth ADR gap and D4's disbursement gap were both handled via explicit sign-off (Kabir/Rohan/Priya made a documented "this boundary is acceptable, production requires X before Y" decision). This gap is the same class — a partial implementation of a stated requirement that could either be (a) correct as scoped, documented incompleteness, or (b) a silent defect that'll surface as "why didn't the gate work" when a brand manually creates a sale campaign with no store.

**QA verdict:** I am NOT rubber-stamping this as "done" until Priya/Arjun make an explicit call:
- **Option A:** Ship D3 as-is, document the human-path gap as a known limitation (e.g., "D3 gates AI-drafted sale campaigns only; human-created campaigns have no type concept today, so no gate applies — follow-up: add campaign-type schema + mirror the gate into CampaignService.create")
- **Option B:** Block D3 until a follow-up task adds campaign-type to the human path and mirrors the gate there (bigger scope, schema change required)

**I cannot make this call as QA** — it's an architectural/product decision on what "done" means for D3.

---

## STANDARD QA GATES (ALL PASS)

### Gate 1: Test Quality (9 new tests)
✅ **PASS** — All 9 tests are load-bearing, zero padding:

**IntegrationHealthServiceTest** (3 tests):
1. `testConnectedWorkspaceReturnsTrue` — workspace with active Shopify integration → true
2. `testDisconnectedWorkspaceReturnsFalse` — workspace with no integration → false
3. `testRevokedOnlyConnectionReturnsFalse` — workspace whose only integration is revoked → false (repository query filters `revoked=false` at DB level)

**CreateCampaignExecutorTest** (6 tests):
1. `testDirectCampaignRejectedWithoutStoreIntegration` — DIRECT campaign + no integration → 409 `NO_STORE_INTEGRATION`, zero DB writes (verified via `never().save()` assertions), `OUTCOME_REJECTED` audit log
2. `testDirectCampaignSucceedsWithStoreIntegration` — DIRECT campaign + active integration → succeeds, draft created
3. `testNonStoreDependentTypesIgnoreIntegrationStatus` (parameterized, 3 runs: HYPE/REVIEW/STANDARD) — non-DIRECT types succeed even with zero integrations, health service NEVER consulted (verified via `never().hasActiveStoreIntegration()`)
4. `testIdempotencyReplaySkipsIntegrationCheck` — replay of prior result never re-checks integration status

All tests would have caught regressions — NOT just happy-path coverage.

### Gate 2: Workspace-Scoping Correctness
✅ **PASS** — `IntegrationHealthService.hasActiveStoreIntegration(workspaceId)` is workspace-scoped by design:
- Calls `ShopifyIntegrationRepository.findByWorkspaceIdAndRevokedFalse(workspaceId)` directly (confirmed via code read, line 49)
- No separate resolve-then-scope step needed — `workspaceId` comes from `CreateCampaignExecutor`'s own authenticated principal (line 152: executor receives `workspaceId` from caller, which is `MeeraInternalController`'s resolved workspace)
- Follows the established `MetricsAuthorizationService` single-purpose-check pattern (javadoc lines 23-27 explicitly cite this precedent)

### Gate 3: No Regressions
✅ **PASS** — Surefire reports confirm:
- `IntegrationHealthServiceTest.xml`: `tests="3" errors="0" failures="0"`
- `CreateCampaignExecutorTest.xml`: `tests="6" errors="0" failures="0"`
- Both test files timestamped 2026-07-07 16:34, matching Vikram's handoff time
- Vikram's handoff claimed 496 total (487 baseline + 9 new) — orchestrator's ground-truth confirmation from SHARED_CONTEXT.md top says 561/561 combined D2+D3, both sets present and passing

Test report timestamps match source file timestamps — genuine new tests, not stale reports.

### Gate 4: Extensibility for WooCommerce (D2)
✅ **PASS** — Javadoc lines 12-21 document the WooCommerce extension point explicitly:
- Current implementation checks only `ShopifyIntegrationRepository` (line 49)
- Javadoc says "When D2 lands, add a `hasActiveWooCommerceConnection(workspaceId)` private check the same shape as `hasActiveShopifyConnection` and OR it into `hasActiveStoreIntegration`; no existing method signature needs to change"
- **Coupling assessment:** This is NOT tight coupling. `IntegrationHealthService` depends on concrete repository interfaces (`ShopifyIntegrationRepository`, future `WooCommerceIntegrationRepository`) but NOT on shared entity classes or a generic "Integration" polymorphic abstraction that doesn't exist yet. When D2 lands, the change is:
  ```java
  public boolean hasActiveStoreIntegration(String workspaceId) {
      return hasActiveShopifyConnection(workspaceId) 
          || hasActiveWooCommerceConnection(workspaceId);  // additive OR
  }
  private boolean hasActiveWooCommerceConnection(String workspaceId) {
      return wooCommerceIntegrationRepository.findByWorkspaceIdAndRevokedFalse(workspaceId).isPresent();
  }
  ```
  This mirrors how `MetricsAuthorizationService` handles platform-specific checks — single-purpose resolver, no abstraction ceremony until a third store type forces consolidation. **No rewrite needed when WooCommerce lands**, just an additive OR clause.

### Gate 5: Error Handling & Audit Logging
✅ **PASS**:
- Rejection before any DB write (line 151-166: check happens BEFORE `campaignIntentRepository.save()`)
- Typed 409 error code `NO_STORE_INTEGRATION` with human-readable message
- Audit log records `OUTCOME_REJECTED` with `NO_STORE_INTEGRATION` code (line 153-161)
- Idempotency replay path skips integration check entirely (line 115-133: `replayIfPresent` returns cached result without re-entering `doExecute`)

---

## TECH-STACK.MD COMPLIANCE
✅ All code follows established patterns:
- Java 21, Spring Boot, JPA repository pattern
- `@Service` + constructor injection (no field injection)
- `@Transactional(readOnly = true)` on read-only query (line 43)
- Javadoc on every non-trivial method + class-level architecture comment
- Test uses Mockito + JUnit 5, `@ExtendWith(MockitoExtension.class)`

---

## SUMMARY

**Technical implementation:** 10/10 — clean, workspace-scoped, extensible, well-tested, zero regressions.

**Scope completeness:** BLOCKED ON ARCHITECTURAL DECISION — D3's gate only applies to one of two campaign-creation paths. The human REST path (`POST /campaigns`) has no campaign-type concept, so no gate can apply there without a schema change + follow-up task.

**Verdict:** APPROVED WITH MANDATORY ESCALATION — code is merge-ready from a quality standpoint, but Priya/Arjun must explicitly sign off on whether the partial-path scope is acceptable as "D3 done" (documented gap) or whether D3 should be marked incomplete until the human path is also gated.

---

## NEXT STEPS

1. **Arjun/Priya:** Make explicit call on D3 scope — is the AI-drafted-path-only gate acceptable as documented, or does D3 require the human path to also be gated before it's "done"?
2. **If Option A (ship as-is):** Document the gap in `REMAINING_WORK_PLAN.md` or D3's own acceptance-criteria section, then route to Meera for build verification
3. **If Option B (block until human path gated):** Scope follow-up task to add `campaign_type` column to `campaigns` table + mirror the gate into `CampaignService.create`

**DO NOT route to Meera or mark D3 "done" until this call is made.**
