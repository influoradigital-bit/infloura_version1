# QA Re-Review: B1 (Brand Fee-on-Publish) — Post-Kabir-FAIL Fix Pass
**Date:** 2026-07-09  
**Reviewer:** Kavya (QA Lead)  
**Assignee:** Vikram (Backend)  
**Status:** ✅ **PASS WITH NOTES**

---

## REVIEW SCOPE

Re-review of B1 after Vikram's fixes addressing:
1. **Kavya's original BLOCK:** Missing test coverage for fee-charge behavior  
2. **Kabir's CRITICAL:** Unconditional side effects (invite/bind/credit-reset ran on every confirm_launch, not gated on transitioningToActive)  
3. **Kabir's HIGH:** No pessimistic lock on Campaign row (race conditions possible)

**Files changed this round (in addition to original B1):**
- `ConfirmLaunchExecutor.java` — gated all side effects, added findByIdForUpdate usage, extracted saveInviteOrConflict
- `CampaignRepository.java` — new findByIdForUpdate with @Lock(PESSIMISTIC_WRITE)
- `CampaignService.java` — new private loadOwnedForUpdate used by update()
- `CampaignServiceTest.java` — 5 new tests covering fee-on-publish behavior
- `ConfirmLaunchExecutorTest.java` — NEW FILE, 5 tests covering activation, no-op replay, lock usage, rollback

---

## VERIFICATION RESULTS

### ✅ CRITICAL FIX 1: transitioningToActive NOW GENUINELY GATES ALL SIDE EFFECTS

**VERIFIED LINE-BY-LINE:**
- **Line 258:** `boolean transitioningToActive = campaign.getStatus() != CampaignStatus.ACTIVE;`
- **Line 260-284:** When `!transitioningToActive` (campaign already ACTIVE), method short-circuits:
  - Saves tool_call ledger row with `ALREADY_ACTIVE_NOOP` outcome
  - Returns `new ConfirmLaunchResult(..., 0, true)` (replay=true, creatorsInvited=0)
  - **CRITICALLY:** Returns BEFORE lines 286-312 (status flip, fee charge, invite, bind, credit reset)
  
- **Lines 299, 304, 312 (the three side effects):**
  - `inviteCreators()` — line 299, **UNREACHABLE** when `!transitioningToActive` (blocked by early return at line 283)
  - `bindFundedHoldsToCollaborations()` — line 304, **UNREACHABLE** when `!transitioningToActive`
  - `aiCreditService.applyEscrowFundedReset()` — line 312, **UNREACHABLE** when `!transitioningToActive`

**KABIR'S EXPLOIT PATH NOW STRUCTURALLY CLOSED:**  
A second confirm_launch call on an already-ACTIVE campaign (different tool_use.id → different IdempotencyService key, so no dedupe from that layer) hits the `!transitioningToActive` guard at line 260, exits at line 283, never reaches invite/bind/credit-reset.

**✅ PASS — Kabir fix 1 (unconditional side effects) genuinely resolved.**

---

### ✅ CRITICAL FIX 2: "ALREADY ACTIVE" REPLAY PATH IS A CLEAN NO-OP

**Line 260-284 behavior analysis:**
- `replay: true` in result signals to caller this was a no-op
- `creatorsInvited: 0` correctly reflects no invites happened
- `status: ACTIVE.name()` — truthful, campaign is ACTIVE
- Audit log records `OUTCOME_ALLOWED` with `ALREADY_ACTIVE_NOOP` — honest, not a failure case
- Tool call ledger row written with `EXECUTED` status — prevents infinite re-attempts under same key

**DOES IT SILENTLY SWALLOW A REAL ERROR?**  
No. The only reason to hit this path is if campaign.getStatus() == ACTIVE. That's a legitimate terminal state, not a masked failure. The original confirm_launch succeeded in a prior call (either via CampaignService.update or a previous ConfirmLaunchExecutor.doExecute run). Nothing is being hidden.

**✅ PASS — Repeat-call handling is semantically correct.**

---

### ✅ CRITICAL FIX 3: findByIdForUpdate USED AT BOTH ACTIVATION ENTRY POINTS

**ConfirmLaunchExecutor.doExecute (line 213-214):**
```java
Campaign campaign = campaignRepository
    .findByIdForUpdate(campaignId)  // ← PESSIMISTIC_WRITE lock
    .orElseThrow(...);
```
**Lock held from here through:**
- Status check (line 258)
- Status flip (line 286)
- Fee charge (line 292)
- Invite/bind/credit-reset (lines 299-312)
- Campaign save (line 294)

**CampaignService.update (line 159):**
```java
Campaign campaign = loadOwnedForUpdate(campaignId, workspace.getId());
```
→ Delegates to `findByIdForUpdate` (line 279), same lock.

**Lock held from here through:**
- Status check (line 185-186: `transitioningToActive`)
- applyPatch (line 188-206, mutates status in-memory)
- Fee charge (line 211-213)
- Campaign save (line 215)

**OWNERSHIP CHECK STILL ENFORCED:**  
Both methods check `!campaign.getWorkspaceId().equals(workspaceId)` AFTER the locked load (ConfirmLaunchExecutor line 219-220, CampaignService line 286-287). Mirrors existing 404-for-both-cases discipline (e.g., WalletRepository.findByIdForUpdate → tenant check after).

**✅ PASS — Lock correctly scoped and workspace-ownership preserved.**

---

### ✅ FIX 4: saveInviteOrConflict's DataIntegrityViolationException → 409 MATCHES CODEBASE CONVENTION

**ConfirmLaunchExecutor lines 383-398:**
```java
} catch (DataIntegrityViolationException dup) {
    throw new ApiException(
        "COLLABORATION_EXISTS",
        "A collaboration already exists for this campaign and creator",
        HttpStatus.CONFLICT);
}
```

**Compared against DealService.propose (DealService.java:146-150):**
```java
} catch (DataIntegrityViolationException ex) {
    throw new ApiException(
        "COLLABORATION_EXISTS",
        "A deal already exists for this campaign and creator",
        HttpStatus.CONFLICT);
}
```

**✅ PASS — Exact same code/error code/status. Pattern is established.**

---

### ✅ FIX 5: TEST QUALITY — ASSERTIONS ACTUALLY VERIFY THE RIGHT THINGS

**CampaignServiceTest (5 new tests, lines 157-253):**

1. **testUpdateDraftToActiveChargesFeeOnce (line 158):**
   - ✅ Verifies `brandCampaignFeeService.chargeOnPublish()` called exactly `times(1)`
   - ✅ Verifies `campaignRepository.save()` called (status persisted)
   - ✅ Uses `findByIdForUpdate` mock (line 166)

2. **testUpdatePausedToActiveChargesFee (line 178):**
   - ✅ Verifies fee charged on PAUSED → ACTIVE resume
   - ✅ Uses locked load

3. **testUpdateActiveToActiveDoesNotRechargeFee (line 195):**
   - ✅ Verifies `brandCampaignFeeService.chargeOnPublish()` **never()** called
   - ✅ This is the "no-op update" guard working correctly

4. **testUpdateInsufficientBalanceRollsBack (line 211):**
   - ✅ Mocks fee service to throw 402 INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH
   - ✅ Asserts exception is propagated
   - ✅ Asserts `campaignRepository.save()` **never()** called (rollback verified)

5. **testUpdateGenericFeeChargeExceptionRollsBack (line 239):**
   - ✅ Generic RuntimeException from fee service
   - ✅ Asserts save never called (transactional rollback)

**ConfirmLaunchExecutorTest (5 new tests, lines 110-268):**

1. **testRealTransitionRunsAllSideEffectsExactlyOnce (line 110):**
   - ✅ Asserts `creatorsInvited == 2` (not 0, real invites happened)
   - ✅ Asserts `replay == false` (not a no-op)
   - ✅ Verifies fee service called `times(1)`
   - ✅ Verifies `aiCreditService.applyEscrowFundedReset()` called `times(1)`
   - ✅ Verifies `collaborationRepository.save()` called `times(2)` (2 creators)
   - ✅ **LOCK VERIFICATION (line 139):** `verify(campaignRepository).findByIdForUpdate(CAMPAIGN_ID)`
   - ✅ **ANTI-REGRESSION (line 140):** `verify(campaignRepository, never()).findByIdAndWorkspaceId(...)`

2. **testAlreadyActiveCampaignIsCleanNoOp (line 147):**
   - ✅ Campaign pre-set to ACTIVE status
   - ✅ Asserts `creatorsInvited == 0` (no re-invite)
   - ✅ Asserts `replay == true` (explicitly flags as no-op)
   - ✅ Verifies `brandCampaignFeeService.chargeOnPublish()` **never()** called
   - ✅ Verifies `aiCreditService.applyEscrowFundedReset()` **never()** called
   - ✅ Verifies `collaborationRepository.save()` **never()** called
   - ✅ Verifies `creatorProfileRepository.findAll()` **never()** called (invite logic never reached)
   - ✅ Verifies `escrowHoldRepository.save()` **never()** called (bind logic never reached)
   - ✅ Verifies audit log records `ALREADY_ACTIVE_NOOP` (line 176)
   - **THIS IS THE CRITICAL TEST — it directly proves Kabir's exploit is dead.**

3. **testDuplicateCollaborationConstraintViolationTranslatedTo409 (line 186):**
   - ✅ Mocks `collaborationRepository.save()` to throw `DataIntegrityViolationException`
   - ✅ Asserts exception is caught and re-thrown as `COLLABORATION_EXISTS` 409
   - ✅ Verifies `aiCreditService.applyEscrowFundedReset()` **never()** called (rollback — no partial completion)

4. **testEscrowNotFundedRejectsBeforeAnySideEffect (line 217):**
   - ✅ Mocks empty FUNDED hold list
   - ✅ Asserts 409 ESCROW_NOT_FUNDED
   - ✅ Asserts fee never charged
   - ✅ Asserts campaign never saved

5. **testInsufficientBalanceRollsBackWholeLaunch (line 241):**
   - ✅ Mocks fee service to throw 402
   - ✅ Asserts campaign never saved
   - ✅ Asserts collaborations never saved
   - ✅ Asserts credit reset never called
   - **Proves transactional atomicity.**

**✅ PASS — Test coverage is comprehensive and assertions are precise. Not just "no exception thrown."**

---

### ⚠️ CORRECTNESS QUESTION 7: DOES GATING BREAK LEGITIMATE FIRST-TIME LAUNCH?

**SCENARIO:** A campaign goes ACTIVE via `CampaignService.update` first (e.g., brand manually PATCHes status to ACTIVE via REST API before Meera ever calls confirm_launch). Then a legitimate *first* confirm_launch call arrives. Would it now incorrectly skip invite/bind because status is already ACTIVE?

**TRACE:**
1. Brand calls `PATCH /campaigns/{id}` with `status: ACTIVE`
   - CampaignService.update runs
   - `transitioningToActive = true` (was DRAFT/PAUSED)
   - **Fee charged** (line 211-213)
   - Campaign saved as ACTIVE
   - **BUT:** No invites, no bind, no credit-reset (CampaignService doesn't do those — it's a pure status/metadata PATCH)

2. Later, Meera calls confirm_launch (first time, legitimate)
   - ConfirmLaunchExecutor.doExecute runs
   - Loads campaign via findByIdForUpdate
   - **Line 258:** `transitioningToActive = campaign.getStatus() != CampaignStatus.ACTIVE`
   - **`transitioningToActive` is now FALSE** (campaign is already ACTIVE from step 1)
   - **Early return at line 283** — invite/bind/credit-reset NEVER RUN

**❌ REGRESSION DETECTED — This is a real bug.**

**ROOT CAUSE:**  
The `transitioningToActive` flag is the wrong guard. It's fine for gating the *fee charge* (should only charge once, at the first activation), but it's the wrong condition for gating invite/bind/credit-reset — those should run **exactly once per campaign**, tied to *this specific tool call being the first confirm_launch*, not tied to "is the campaign pre-ACTIVE right now."

**CORRECT GATE SHOULD BE:**  
"Has confirm_launch ever successfully completed for this campaign before?" — i.e., check if `meera_tool_calls` already has a row for `(toolName=confirm_launch, resultRefType=CAMPAIGN, resultRefId=campaignId, status=EXECUTED)` from a *different* idempotency key.

**CURRENT CODE DOES NOT CHECK THIS.**  
The `replayIfPresent()` method only checks if *the exact same idempotency key* has already run. It does NOT check if a *different* confirm_launch call (different tool_use.id → different key) already completed for the same campaign.

**IMPACT:**  
If a brand manually activates a campaign via the REST API before Meera's first confirm_launch, the legitimate confirm_launch will be a silent no-op — creators never invited, escrow never bound, AI credits never reset. The campaign appears ACTIVE but is in an incomplete state.

**❌ BLOCKING REGRESSION — This must be fixed before PASS.**

---

## FINDINGS SUMMARY

### BLOCKING (must fix before ship)

**B1-REGRESSION-1 (CRITICAL):** `transitioningToActive` is the wrong guard for invite/bind/credit-reset.

**FILE:** `ConfirmLaunchExecutor.java`  
**LINES:** 258-312  

**ISSUE:**  
If a campaign reaches ACTIVE status via `CampaignService.update` (legitimate brand REST API call) before Meera's first `confirm_launch`, the `transitioningToActive` flag at line 258 evaluates to `false`, causing the method to short-circuit at line 283. The legitimate first confirm_launch never invites creators, never binds escrow holds, and never resets AI credits — even though this is the first and only time confirm_launch will run for this campaign.

**FIX:**  
Replace the `transitioningToActive` guard with a "has confirm_launch ever succeeded for this campaign" check:
```java
// Check if confirm_launch has EVER succeeded for this campaign (any idempotency key).
boolean firstConfirmLaunchForCampaign = toolCallRepository
    .findByToolNameAndResultRefId(MeeraToolName.confirm_launch, campaignId)
    .stream()
    .noneMatch(call -> call.getStatus() == ToolCallStatus.EXECUTED);
```

Then gate invite/bind/credit-reset on `firstConfirmLaunchForCampaign`, but keep gating the *fee charge* on `transitioningToActive` (since CampaignService.update already charged it if the brand manually activated).

**ALTERNATIVE (simpler):**  
Change the semantic contract: confirm_launch MUST be called before any manual activation. Reject with 409 "CAMPAIGN_ALREADY_ACTIVE_WITHOUT_LAUNCH" if confirm_launch is called on an already-ACTIVE campaign. Then the short-circuit at line 260-283 becomes the correct error path, not a silent success.

**RECOMMENDED:** Alternative (simpler). Add a defensive check:
```java
if (!transitioningToActive) {
    // Campaign is already ACTIVE — this is only allowed if confirm_launch already ran.
    boolean alreadyLaunched = toolCallRepository
        .existsByToolNameAndResultRefIdAndStatus(
            MeeraToolName.confirm_launch, 
            campaignId, 
            ToolCallStatus.EXECUTED);
    if (!alreadyLaunched) {
        throw new ApiException(
            "CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH",
            "Campaign was activated via REST API before confirm_launch — cannot proceed",
            HttpStatus.CONFLICT);
    }
    // Else: confirm_launch already ran under a different key → clean replay.
    [existing lines 261-283]
}
```

---

### NON-BLOCKING NOTES

**NOTE-B1-1 (Medium):** No corresponding `existsByToolNameAndResultRefIdAndStatus` method exists in `MeeraToolCallRepository` yet (would need to be added for the recommended fix above). Current `findByIdempotencyKey` is scoped to the exact key, not the campaign.

**NOTE-B1-2 (Low):** Test `testAlreadyActiveCampaignIsCleanNoOp` (line 147) *assumes* the short-circuit is correct behavior, but per the regression above, it's only correct if confirm_launch already ran once. The test should be split:
- One test: "confirm_launch called twice (different keys) on same campaign → second is a no-op"
- Different test: "confirm_launch called on a manually-activated campaign that never had confirm_launch → 409 error"

**NOTE-B1-3 (Low):** Comment at line 251-257 ("the real DRAFT/PENDING_APPROVAL/PAUSED -> ACTIVE transition") does not list all possible pre-ACTIVE states. `CampaignStatus` enum may have others (COMPLETED, CANCELLED?). Recommend using `!= ACTIVE` instead of listing states.

---

## MVNW / TEST RUN ATTEMPT

**ENVIRONMENT ISSUE:** No `mvnw` (Maven wrapper) in repo. Attempted `mvn` via Bash/PowerShell — `mvn` not in PATH on this machine. Per Meera's prior verification note (B0 pass), she used a local Maven install at `C:\Users\Sage world\tools\apache-maven-3.9.6`. I do not have permission to invoke arbitrary executables outside the repo without explicit user consent.

**DID NOT RUN TESTS MYSELF.**  
Relying on:
1. Line-by-line code trace (completed above)
2. Vikram's claim in SHARED_CONTEXT that tests pass
3. Test *content* review (verified assertions are correct, assuming the code under test executes as written)

**RECOMMENDATION:** Arjun should route to Meera for full test run verification after the regression fix is applied.

---

## VERDICT

**❌ BLOCK — One critical regression found (B1-REGRESSION-1).**

**REASON:**  
The `transitioningToActive` gate at line 258-260 is the wrong condition for gating invite/bind/credit-reset. It only checks "is the campaign not-yet-ACTIVE right now," not "has confirm_launch ever succeeded for this campaign." This breaks the legitimate first confirm_launch call if a brand manually activated the campaign via `PATCH /campaigns/{id}` before Meera called the tool.

**KABIR'S ORIGINAL FINDINGS:** Genuinely fixed (verified by code trace).  
**KAVYA'S ORIGINAL FINDINGS:** Genuinely fixed (test coverage added, assertions correct).  
**NEW FINDING:** Introduced by the fix itself.

---

## NEXT STEPS

1. **Vikram:** Fix B1-REGRESSION-1 (recommend the defensive "CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH" 409 approach, simpler than reworking the gate logic).
2. **Vikram:** Add `MeeraToolCallRepository.existsByToolNameAndResultRefIdAndStatus(...)` if going with the alternative fix.
3. **Vikram:** Split/add test case for "confirm_launch on manually-activated campaign that never had confirm_launch" → should 409, not silently succeed.
4. **Re-submit to Kavya** for final verification.
5. **After Kavya PASS → Meera** for test run + build verification.

---

## FILES REVIEWED THIS PASS

- `ConfirmLaunchExecutor.java` ✅ (except B1-REGRESSION-1)
- `CampaignRepository.java` ✅
- `CampaignService.java` ✅
- `CampaignServiceTest.java` ✅
- `ConfirmLaunchExecutorTest.java` ✅ (test content sound, but see NOTE-B1-2)
- `TECH-STACK.md` ✅ (re-read for compliance check)
- `DealService.java` ✅ (reference for DataIntegrityViolationException pattern)

---
**Kavya Reddy**  
QA Lead, Sage Digital  
2026-07-09
