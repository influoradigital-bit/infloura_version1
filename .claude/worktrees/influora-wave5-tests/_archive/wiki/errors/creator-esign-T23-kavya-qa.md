# QA Review: Creator E-Sign Backend Slice — Task #23 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~17:05 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (creator contract surface + H-1 regression) → Meera build  
**Scope:** Vikram Task #23 — creator e-sign backend slice  
**Reference:** `wiki/tech/creator/CREATOR_EXEC_PLAN_FINAL.md` Week 3; `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` V-6; Task #10 H-1 `findByIdAndCreatorId` (closed); `07_CREATOR_CONTRACTS_SPEC.md`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/ContractController.java`
- `influora-api/src/main/java/com/influora/service/ContractService.java`
- `influora-api/src/main/java/com/influora/repository/ContractRepository.java`
- `influora-api/src/main/java/com/influora/service/notification/event/ContractReadyForEscrowEvent.java`
- `influora-api/src/main/java/com/influora/service/notification/NotificationListener.java`
- `influora-api/src/test/java/com/influora/service/ContractServiceTest.java` (16 tests)
- `src/lib/api.ts` — `contracts.list` / `contracts.sign` (contract cross-check)

---

## Executive Summary

Task #23 **passes QA**. Vikram shipped the creator e-sign backend slice with correct isolation, idempotency, unsigned-list semantics, and notification-only escrow prompting (no auto-debit — MF-1 wallet consent preserved).

**Shipped endpoints (creator JWT):**
| Method | Path | Service |
|--------|------|---------|
| `GET` | `/contracts` | `listForCreator` — optional `?dealId=` filters to one collaboration |
| `GET` | `/contracts/unsigned` | `listUnsignedForCreator` |
| `POST` | `/contracts/{id}/sign` | `recordSignatureForCreator` — body ignored on creator branch |

Creator isolation uses `ContractRepository.findByIdAndCreatorId` join-through (`collaboration.creatorId = principal.getUserId()`), matching the H-1 fix from Task #10. Cross-creator probes return uniform `CONTRACT_NOT_FOUND` 404 — no existence leak.

Idempotency reuses the shared `contract-sign:{contractId}:CREATOR` key via `IdempotencyService.executeOnce`, aligned with the brand relay path so concurrent/replayed creator signs cannot double-fire PDF delivery or escrow prompts.

On dual signature, `promptEscrowFundingIfNeeded` checks `EscrowHoldRepository.existsByCollaborationIdAndStatus(..., FUNDED)` and publishes `ContractReadyForEscrowEvent` only when no funded hold exists. `NotificationListener` routes brand to `/brand/wallet/escrow?contractId=…` — **notification only**; actual debit remains `EscrowService.initiateFund` (brand-initiated).

**16 scoped unit tests** in `ContractServiceTest` cover creator read/sign/list-unsigned, cross-creator IDOR rejection, idempotent retry, and escrow prompt on dual signature. Vikram reports **16/16 PASS**; `mvn` not on PATH in this QA shell — Meera to re-confirm.

---

## Task #23 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| Creator can list contracts (`GET /contracts`, optional `?dealId=`) | ✅ PASS | `ContractController.list` L46–55 → `listForCreator`; `findByCreatorId` / `findByCollaborationIdAndCreatorId` |
| Creator unsigned list (`GET /contracts/unsigned`) | ✅ PASS | `ContractController.listUnsigned` L58–66 → `findUnsignedByCreatorId` (`creatorSignedAt IS NULL`) |
| Creator sign (`POST /contracts/{id}/sign`) | ✅ PASS | `ContractController.sign` L83–84 → `recordSignatureForCreator` |
| Cross-creator IDOR blocked | ✅ PASS | `requireContractForCreator` → `findByIdAndCreatorId`; tests `testGetForCreatorRejectsOtherCreatorContract`, `testCreatorSignRejectsCrossCreatorIdor`, `testGetPdfDownloadUrlForCreatorRejectsCrossCreatorIdor` |
| Idempotency on creator sign | ✅ PASS | `executeOnce` + `doRecordSignature` already-signed guard; test `testRetriedCreatorSignatureForCreatorIsNoOp` |
| Escrow prompt on dual signature (no auto-debit) | ✅ PASS | `promptEscrowFundingIfNeeded` L363–396; `ContractReadyForEscrowEvent`; test `testDualSignaturePublishesEscrowFundingPrompt` |
| Unit tests 16/16 | ⚠️ AUTHORED | Vikram 16/16; Meera re-verify (L-23-7) |
| TECH-STACK.md compliance | ✅ PASS | Thin controller, `ApiException` codes, transactional service, `CreatorContextService`, no debug code |
| H-1 regression (creator contract paths) | ✅ PASS | All creator read/sign/PDF paths use `findByIdAndCreatorId` — never raw `findById` |

---

## Test Execution

| Test Class | Authored | Executed (QA) | Failures | Notes |
|------------|----------|---------------|----------|-------|
| `ContractServiceTest` | 16 | ❌ Not run | — | `mvn` unavailable in QA env; Vikram reports 16/16 PASS |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=ContractServiceTest
```

**Recommended regression (contract + wallet creator paths):**
```bash
cd influora-api && mvn test -Dtest=ContractServiceTest,ContractPdfServiceTest
```

---

## Endpoint Review

### `GET /contracts` (creator)

```46:55:influora-api/src/main/java/com/influora/web/ContractController.java
    @GetMapping
    public ApiResponse<List<ContractResponse>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String dealId) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.listForCreator(principal, dealId));
        }
        throw new ApiException(
                "WRONG_USER_TYPE", "Contract list is available to creator accounts only", HttpStatus.FORBIDDEN);
    }
```

- ✅ Creator-only gate at controller + `creatorContext.requireCreator` in service.
- ✅ `dealId` maps to `collaborationId` in repository — consistent with deal-room naming elsewhere.
- ✅ Foreign `dealId` returns empty list (not 404) — acceptable; no existence oracle.

### `GET /contracts/unsigned`

```44:49:influora-api/src/main/java/com/influora/repository/ContractRepository.java
    @Query(
            "SELECT c FROM Contract c WHERE c.creatorSignedAt IS NULL AND c.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId) "
                    + "ORDER BY c.createdAt DESC")
    List<Contract> findUnsignedByCreatorId(@Param("creatorUserId") String creatorUserId);
```

- ✅ Scoped to authenticated creator via subquery on `Collaboration.creatorId`.
- ✅ Filter `creatorSignedAt IS NULL` — contracts awaiting creator signature, including those where brand has not yet signed (product-intended for e-sign inbox).
- ℹ️ Does not exclude `CANCELLED` contracts if `creatorSignedAt` is still null — carry-forward L-23-2 if cancellation flow lands.

### `POST /contracts/{id}/sign` (creator branch)

```297:316:influora-api/src/main/java/com/influora/service/ContractService.java
    @Transactional
    public ContractResponse recordSignatureForCreator(AuthPrincipal principal, String contractId) {
        creatorContext.requireCreator(principal);
        Contract contract = requireContractForCreator(contractId, principal.getUserId());

        String signKey = "contract-sign:" + contractId + ":CREATOR";
        try {
            return idempotencyService.executeOnce(
                    signKey,
                    contract.getWorkspaceId(),
                    "contract.sign",
                    () -> doRecordSignature(contract, false));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            Contract refreshed = requireContractForCreator(contractId, principal.getUserId());
            ...
        }
    }
```

- ✅ Request body ignored — creators cannot self-attribute as BRAND.
- ✅ Shared idempotency key with brand `role=CREATOR` relay path prevents duplicate side effects across both signing mechanisms.
- ✅ Race loser re-reads via scoped `requireContractForCreator` — no stale cross-tenant state.

### Escrow funding prompt (dual signature)

```358:396:influora-api/src/main/java/com/influora/service/ContractService.java
    private void promptEscrowFundingIfNeeded(Contract contract) {
        try {
            if (escrowHoldRepository.existsByCollaborationIdAndStatus(
                    contract.getCollaborationId(), EscrowStatus.FUNDED)) {
                return;
            }
            ...
            eventPublisher.publishEvent(
                    new ContractReadyForEscrowEvent(
                            brandRecipientUserId,
                            contract.getWorkspaceId(),
                            contract.getId(),
                            campaignTitle));
        } catch (Exception e) {
            log.error(
                    "Escrow funding prompt failed for contract {} — signatures were still recorded",
                    contract.getId(),
                    e);
        }
    }
```

- ✅ No call to `EscrowService.initiateFund` — MF-1 wallet consent preserved.
- ✅ Failures swallowed — signature transaction not rolled back.
- ✅ `NotificationListener.on(ContractReadyForEscrowEvent)` deep-links brand to escrow fund UI.

---

## Security Checklist (Basic — Kabir Deep Review)

| Check | Result | Notes |
|-------|--------|-------|
| No hardcoded secrets | ✅ PASS | — |
| Creator identity from JWT only | ✅ PASS | `principal.getUserId()` — never path-param user id |
| Cross-creator IDOR | ✅ CLOSED | `findByIdAndCreatorId` on read/sign/PDF |
| Enumeration oracle | ✅ PASS | Uniform `CONTRACT_NOT_FOUND` 404 |
| Idempotency / TOCTOU | ✅ PASS | `executeOnce` + already-signed guard |
| Auto-debit on sign | ✅ PASS (absent) | Event notification only |
| Sensitive data logged | ✅ PASS | Error logs use contract id only |

**Kabir focus:** Re-verify H-1 on all creator contract paths; confirm idempotency key scope (`workspaceId` from owned contract) cannot be abused cross-tenant.

---

## Findings (Non-Blocking)

| ID | Severity | Finding | Action |
|----|----------|---------|--------|
| L-23-1 | Low | No `ContractControllerTest` for creator HTTP paths (`list`, `listUnsigned`, `sign` branch) | Optional follow-up; service layer well-covered |
| L-23-2 | Low | `findUnsignedByCreatorId` does not filter `CANCELLED` / terminal statuses | Monitor when cancellation flow ships |
| L-23-3 | Low | No unit test asserting escrow prompt **suppressed** when `FUNDED` hold exists | Add in follow-up PR |
| L-23-4 | Low | No unit test for `listForCreator` with `dealId` filter or foreign-deal empty list | Add in follow-up PR |
| L-23-5 | Low | `testCreatorSignsOwnContractAndPromptsEscrow` stubs empty workspace owner — does not assert `ContractReadyForEscrowEvent` publish (covered by `testDualSignaturePublishesEscrowFundingPrompt` on brand path) | Acceptable coverage split |
| L-23-6 | Info | `src/lib/api.ts` has no `contracts.listUnsigned` helper yet | Ananya Task A-3 (e-sign UI wire) — not a backend blocker |
| L-23-7 | Info | `mvn` unavailable in QA shell — tests not executed here | Meera gate |

**No Critical / High / Medium blockers.** Sprint gate **GO** for Kabir → Meera.

---

## api.ts Contract Cross-Check

| Backend | `api.ts` | Status |
|---------|----------|--------|
| `GET /contracts?dealId=` | `contracts.list(role, dealId?)` | ✅ Aligned |
| `GET /contracts/{id}` | `contracts.get(role, id)` | ✅ Aligned (pre-existing) |
| `POST /contracts/{id}/sign` | `contracts.sign(role, id, signature)` | ⚠️ Creator branch ignores body — OK for live wire |
| `GET /contracts/unsigned` | — | ❌ Missing — Ananya to add in e-sign UI slice |

---

## Routing

| Next | Owner | Notes |
|------|-------|-------|
| Security review | **Kabir** | Creator contract IDOR + idempotency + escrow notification surface |
| Build verify | **Meera** | `ContractServiceTest` 16/16 + frontend build |
| E-sign UI wire | **Ananya** | Add `listUnsigned` to `api.ts`; wire creator contract panel |
| CTO sign-off | **Priya** | After Kabir + Meera PASS |

---

## Sign-Off

**Kavya Patel — QA Lead**  
**Verdict: ✅ APPROVED**  
**Date:** 2026-07-09 (~17:05 IST)
