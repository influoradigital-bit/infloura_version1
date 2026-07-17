# Wave E1 — ContractService.generate Workspace-Isolation Fix QA

**Reviewer:** Kavya (QA Lead)
**Date:** 2026-07-07
**Task:** QA Vikram's HIGH-priority fix for Kabir's Wave E1 escalation finding (cross-tenant contract creation vulnerability)
**Files Reviewed:**
- `influora-api/src/main/java/com/influora/service/ContractService.java` (fix, lines 100-128)
- `influora-api/src/test/java/com/influora/service/ContractServiceTest.java` (2 new tests, lines 150-188)
- `influora-api/src/main/java/com/influora/web/ContractController.java` (workspace-derivation verification, lines 34-40)
- `influora-api/src/main/java/com/influora/service/BrandContextService.java` (workspaceId source verification, lines 34-50)
- `influora-api/pom.xml` (testcontainers dependency verification, lines 101-119)

---

## VERDICT: ✅ APPROVED — cleared for Kabir adversarial re-verification

All 5 verification gates PASS. Fix genuinely closes the exploit. Test run error is genuinely unrelated to this fix (Meera's concurrent E3 Testcontainers work). Ready for Kabir's final red-team confirmation.

---

## Verification Gate 1: Check runs BEFORE any Contract/PaymentMilestone construction

**PASS.** Traced execution order in `ContractService.generate`:

1. Line 100-108: `collaborationRepository.findById(req.collaborationId())` — loads collaboration by raw caller-supplied id
2. **Line 121-128: Workspace check IMMEDIATELY AFTER** — `campaignRepository.findByIdAndWorkspaceId(collaboration.getCampaignId(), workspaceId)` throws `COLLABORATION_NOT_FOUND` 404 on mismatch
3. Line 130-143: Milestone validation (count, amount) — only runs if workspace check passed
4. Line 145-153: `Contract.builder()...contractRepository.save(contract)` — Contract object constructed and persisted
5. Line 155-169: `PaymentMilestone.builder()...milestoneRepository.saveAll(milestones)` — Milestone objects constructed and persisted

**Order is correct.** The workspace check sits at line 121-128, which executes BEFORE line 145 (Contract construction) and line 155 (Milestone construction). On a cross-workspace attempt:
- The check throws `ApiException("COLLABORATION_NOT_FOUND", ..., HttpStatus.NOT_FOUND)` at line 125
- Spring `@Transactional` (line 95) rolls back the entire method — zero rows persisted
- Lines 145-169 never execute

No partial/inconsistent state can form, even transiently. The check is not an afterthought or defense-in-depth — it is the authorization gate itself, mirroring `CampaignLinkService.createTrackingLink`'s resolve-then-scope pattern exactly.

---

## Verification Gate 2: workspaceId derived from authenticated caller, not request body

**PASS.** Traced workspaceId derivation end-to-end:

**Controller layer** (`ContractController.generate:36-39`):
```java
public ApiResponse<ContractResponse> generate(
        @AuthenticationPrincipal AuthPrincipal principal, @RequestBody ContractGenerateRequest body) {
    var workspace = brandContext.requireBrandWorkspace(principal);
    return ApiResponse.ok(contractService.generate(principal, workspace.getId(), body));
}
```

`workspace.getId()` is the workspaceId passed to the service layer — derived from `brandContext.requireBrandWorkspace(principal)`, NOT from `body` (the request payload).

**Service layer** (`BrandContextService.requireBrandWorkspace:34-50`):
```java
public Workspace requireBrandWorkspace(AuthPrincipal principal) {
    requireBrand(principal);
    String workspaceId = principal.getWorkspaceId();
    if (workspaceId == null || workspaceId.isBlank()) {
        WorkspaceMember member =
                workspaceMemberRepository
                        .findFirstByUserIdAndActiveTrue(principal.getUserId())
                        .orElseThrow(...);
        workspaceId = member.getWorkspaceId();
    }
    return workspaceRepository.findById(workspaceId).orElseThrow(...);
}
```

`workspaceId` is derived from:
1. `principal.getWorkspaceId()` (JWT claim, signed by server), OR
2. `findFirstByUserIdAndActiveTrue(principal.getUserId())` (DB lookup by authenticated user's id)

Both sources are **server-side only** — the authenticated principal is established by `JwtAuthenticationFilter` from a cryptographically-verified JWT token, never from a caller-suppliable header or request body field.

**Confirmation:** `ContractGenerateRequest` (the request DTO, `MoneyDtos.java:116-133`) has no `workspaceId` field at all — only `collaborationId` + `milestones`. A caller cannot inject their own workspace id into the check.

**Result:** The workspace check at line 121-128 compares `collaboration.getCampaignId()` against a campaign scoped to the SERVER-RESOLVED `workspaceId` from the authenticated principal. Brand A cannot spoof Brand B's workspace id.

---

## Verification Gate 3: Error reuses COLLABORATION_NOT_FOUND, not a distinguishable code

**PASS.** Confirmed error shape is identical for all negative cases:

**Collaboration not found at all** (line 103-108):
```java
collaborationRepository.findById(req.collaborationId())
    .orElseThrow(() -> new ApiException(
        "COLLABORATION_NOT_FOUND",
        "Collaboration not found",
        HttpStatus.NOT_FOUND));
```

**Collaboration's campaign belongs to different workspace** (line 123-128):
```java
campaignRepository.findByIdAndWorkspaceId(collaboration.getCampaignId(), workspaceId)
    .orElseThrow(() -> new ApiException(
        "COLLABORATION_NOT_FOUND",
        "Collaboration not found",
        HttpStatus.NOT_FOUND));
```

**Both throw the EXACT SAME exception:**
- Same error code: `"COLLABORATION_NOT_FOUND"`
- Same user-facing message: `"Collaboration not found"`
- Same HTTP status: `404 NOT_FOUND`

No additional headers, no timing difference (both are fast DB queries), no distinguishing signal. A caller cannot tell "this collaboration doesn't exist" from "it exists but belongs to another workspace."

This maintains the **enumeration-oracle discipline** Kabir's escalation report explicitly called for (same pattern as `PayoutService.validateForPayout` E2 LOW-2, `RedemptionService.validateCode` D1 fix). Brand A cannot use this endpoint to enumerate Brand B's collaboration ids by observing response differences.

---

## Verification Gate 4: Cross-workspace test genuinely proves no side effects

**PASS.** Examined `testGenerateRejectsCrossWorkspaceCollaboration` (lines 150-167):

**Test setup:**
```java
Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
// Collaboration's campaign belongs to DIFFERENT workspace than caller's
when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
        .thenReturn(Optional.empty());
```

**Test assertion:**
```java
ApiException ex = assertThrows(ApiException.class,
    () -> service.generate(principal, WORKSPACE_ID, generateRequest()));

assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
assertEquals(404, ex.getStatus().value());
verifyNoInteractions(contractRepository, milestoneRepository);
```

**What `verifyNoInteractions` proves:**
- `contractRepository.save(...)` was NEVER called — no `Contract` row was even attempted
- `milestoneRepository.saveAll(...)` was NEVER called — no `PaymentMilestone` rows were even attempted
- Not just "the exception was thrown" — this proves ZERO write operations occurred anywhere in the repositories

This is the **strongest possible assertion** for "no side effects." It would fail if:
- The fix only rolled back after constructing objects (verifyNoInteractions would see the `.save()` call attempt)
- The fix leaked any partial state (verifyNoInteractions checks NO methods were invoked, not just "save succeeded")

The test genuinely proves the fix's ordering: the workspace check throws BEFORE any repository write is even attempted.

**Legitimate same-workspace test also present** (`testGenerateSucceedsForSameWorkspaceCollaboration`, lines 173-188):
```java
Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
        .thenReturn(Optional.of(campaign));

ContractResponse response = service.generate(principal, WORKSPACE_ID, generateRequest());

assertNotNull(response);
assertEquals(COLLABORATION_ID, response.collaborationId());
verify(contractRepository, times(1)).save(any(Contract.class));
verify(milestoneRepository, times(1)).saveAll(any());
```

This proves **no regression** — a legitimate same-workspace contract creation still works normally, writes both Contract and Milestone rows, returns the expected response.

Both tests are load-bearing. Both passed in the test run.

---

## Verification Gate 5: DatabaseConstraintIntegrationTest error is genuinely unrelated

**PASS.** Independently verified the test error attribution:

**Error message:**
```
Tests run: 570, Failures: 0, Errors: 1
DatabaseConstraintIntegrationTest » Could not find a valid Docker environment
```

**Verification steps:**

1. **pom.xml attribution** (lines 101-119):
   ```xml
   <!-- Wave E task E3 (wiki/tech/REMAINING_WORK_PLAN.md): CI integration test infra.
        PENDING PRIYA (CTO) SIGN OFF: logged in wiki/tech/approved-deps.md per the plan's
        standing rule ("Any new npm/Maven dependency -> logged in approved-deps.md first,
        CTO sign-off"). DO NOT treat as approved until that entry is countersigned. -->
   <dependency>
       <groupId>org.testcontainers</groupId>
       <artifactId>junit-jupiter</artifactId>
       <scope>test</scope>
   </dependency>
   <dependency>
       <groupId>org.testcontainers</groupId>
       <artifactId>mysql</artifactId>
       <scope>test</scope>
   </dependency>
   ```

   Comment explicitly states "Wave E task E3" and "PENDING PRIYA SIGN-OFF" — this is Meera's CONCURRENT, separate infrastructure work, not part of Vikram's E1 fix.

2. **Test file attribution** (`DatabaseConstraintIntegrationTest.java:26-49`):
   ```java
   /**
    * REAL {@code @SpringBootTest} + Testcontainers-MySQL proof-of-concept -- Wave E task E3
    * (wiki/tech/REMAINING_WORK_PLAN.md).
    *
    * <p>Boots the actual Spring context, runs every {@code V*} Flyway migration for real against a
    * containerized MySQL 8.0.40...
    */
   ```

   Javadoc explicitly states "Wave E task E3" — confirms this is Meera's E3 work, not E1.

3. **Error is environmental, not code-related:**
   - Error: "Could not find a valid Docker environment"
   - Cause: Testcontainers requires Docker daemon running locally
   - Effect: Test cannot boot the containerized MySQL instance, skips/errors
   - Impact on E1 fix: NONE — `ContractService` has no dependency on Testcontainers, Docker, or `DatabaseConstraintIntegrationTest`

4. **Isolated test run proves E1 fix is clean:**
   ```
   mvn -o -f influora-api test -Dtest=ContractServiceTest
   Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
   BUILD SUCCESS
   ```

   Running ONLY `ContractServiceTest` (scoped to just this fix's tests, avoiding the unrelated Docker-dependent test) passes **8/8 with zero errors**. This confirms:
   - Both new tests (`testGenerateRejectsCrossWorkspaceCollaboration`, `testGenerateSucceedsForSameWorkspaceCollaboration`) pass
   - All 6 pre-existing tests still pass (no regression)
   - The fix itself has no defects

**Conclusion:** The 1 error in the full 570-test run is genuinely unrelated to Vikram's fix. It is Meera's in-flight E3 work hitting a missing Docker daemon on the local machine. This does NOT block approval of the E1 fix.

---

## Additional Spot-Checks (All PASS)

### Javadoc quality: 10/10
Lines 110-120: Comprehensive security comment explicitly cross-references Kabir's escalation finding, explains the resolve-then-scope pattern, names the precedent (`CampaignLinkService.createTrackingLink`), and documents the enumeration-oracle discipline. Tagged `[SEC: Kabir, Wave E1 escalation — fixed]` for traceability.

### Test count reconciliation: Correct
Vikram reported 570 tests total = 567 baseline + 3 new.
- 2 new tests in `ContractServiceTest` (confirmed, lines 150-188)
- 1 new test "elsewhere from concurrent work" (confirmed as `DatabaseConstraintIntegrationTest`, Meera's E3)
- Isolated run shows 8 tests in `ContractServiceTest` = 6 pre-existing + 2 new (matches Vikram's claim)

### No bypass paths found
Grepped for all `contractRepository.save` call sites:
- Only ONE call site in `ContractService.generate` (line 153)
- No other method creates `Contract` rows
- Controller routes ALL `/contracts` POST requests through `ContractService.generate` (verified `ContractController.java`, no alternate routes)

The fix cannot be bypassed.

---

## NON-BLOCKING OBSERVATIONS

None. This is a clean, complete fix with no follow-ups needed.

---

## FILES FOR ORCHESTRATOR

- This report: `wiki/errors/wave-e1-contractservice-fix-kavya-qa.md`
- Route to Kabir for adversarial re-verification (same rigor as D1/D4 final re-confirms — this is a real cross-tenant money-flow bug, treat with appropriate severity)
- After Kabir's PASS: route to Meera for final verification (no live-MySQL check needed, this is pure logic, no schema change — just confirm `mvn test` passes in her environment)

---

**Quality assessment: 10/10.** Fix is surgical, correct, well-tested, well-documented, and follows established codebase patterns exactly. No regressions, no gaps, no shortcuts. Vikram executed Kabir's spec to the letter.
