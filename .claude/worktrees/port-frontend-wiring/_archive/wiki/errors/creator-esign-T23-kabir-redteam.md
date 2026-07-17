# Creator E-Sign Backend — Task #23 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** Vikram Task #23 e-sign backend slice — `ContractController` creator branches (`GET /contracts`, `GET /contracts/unsigned`, `GET /contracts/{id}`, `POST /contracts/{id}/sign`, `GET /contracts/{id}/pdf-download-url`), `ContractService.recordSignatureForCreator`, `listForCreator` / `listUnsignedForCreator`, H-1 `findByIdAndCreatorId` on sign path, idempotency (`executeOnce` + already-signed guard), `ContractReadyForEscrowEvent` / `NotificationListener` (brand notification only — no wallet debit)  
**Reference Spec:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.3, §8; `CREATOR_TASK_ASSIGNMENTS_PRIYA.md` V-6; prior H-1 fix `wiki/errors/creator-wallet-contract-T10-kabir-redteam.md`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/ContractController.java` — creator branches on list, unsigned, get, sign, pdf-download-url
- `influora-api/src/main/java/com/influora/service/ContractService.java` — `recordSignatureForCreator`, `listForCreator`, `listUnsignedForCreator`, `promptEscrowFundingIfNeeded`, `doRecordSignature`
- `influora-api/src/main/java/com/influora/repository/ContractRepository.java` — `findByIdAndCreatorId`, `findByCreatorId`, `findByCollaborationIdAndCreatorId`, `findUnsignedByCreatorId`
- `influora-api/src/main/java/com/influora/service/notification/event/ContractReadyForEscrowEvent.java`
- `influora-api/src/main/java/com/influora/service/notification/NotificationListener.java` — `on(ContractReadyForEscrowEvent)`
- `influora-api/src/main/java/com/influora/service/EscrowService.java` — `initiateFund` (cross-check: no event listener auto-debit)
- `influora-api/src/test/java/com/influora/service/ContractServiceTest.java` (+6 creator e-sign tests, 16/16 total per Meera gate)

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Task #23 closes the creator-authenticated signing gap flagged as residual risk in E2 / Task #10. Cross-creator contract read, sign, list, and PDF presign IDOR vectors remain **blocked** via H-1 join-through `findByIdAndCreatorId` + `principal.getUserId()` only. Signature replay and concurrent double-sign races are **closed** via shared `IdempotencyService.executeOnce` key (`contract-sign:{contractId}:CREATOR`) plus the E2 already-signed short-circuit in `doRecordSignature`. Escrow funding prompt is **notification-only** — no creator path can debit a brand wallet; `EscrowService.initiateFund` remains brand `OWNER`/`ADMIN` gated.

**No Critical or High findings.** Task #23 **does not block** Ananya e-sign UI wire (A-3) or Meera build verify (already **16/16 PASS**).

**Carry-forward (Low, non-blocking sprint):**
- **L-23-1:** No `ContractController` integration tests (extends Task #10 L-10-1).
- **L-23-2:** No hostile test for `listForCreator(principal, foreignDealId)` → empty list (behavior correct; coverage gap).
- **L-23-3:** `POST /contracts/{id}/sign` not in `AuthRateLimitFilter#bucketFor` — authenticated write unthrottled (replay is idempotent; annoyance-only).
- **L-23-4:** No terminal-status guard before signing (`CANCELLED` contract could theoretically be re-signed via `advanceIfFullySigned`; no `CANCELLED` setter exists in codebase today — shared with brand `recordSignature` path).

**Pre-existing (unchanged, not a Task #23 regression):**
- **E2 LOW-4 residual:** Brand elevated members can still relay-record `role=CREATOR` without a creator JWT. Task #23 **mitigates** by giving creators a real authenticated path; it does not remove the brand relay mechanism (product decision).

---

## 1. IDOR — Cross-Creator Contract Read / Sign / PDF

### 1a. Gate chain

```298:316:influora-api/src/main/java/com/influora/service/ContractService.java
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

```589:594:influora-api/src/main/java/com/influora/service/ContractService.java
    private Contract requireContractForCreator(String contractId, String creatorUserId) {
        return contractRepository
                .findByIdAndCreatorId(contractId, creatorUserId)
                .orElseThrow(
                        () -> new ApiException("CONTRACT_NOT_FOUND", "Contract not found", HttpStatus.NOT_FOUND));
    }
```

All creator mutating and high-value read paths (`getForCreator`, `getPdfDownloadUrlForCreator`, `recordSignatureForCreator`) funnel through `requireContractForCreator` after `creatorContext.requireCreator`. List paths scope via `principal.getUserId()` on repository queries — never a request-supplied creator id.

### 1b. IDOR exploit matrix

| Attack | Result |
|---|---|
| Creator A `GET /contracts/{B's contractId}` | **BLOCKED** — `404 CONTRACT_NOT_FOUND` |
| Creator A `POST /contracts/{B's contractId}/sign` | **BLOCKED** — `404 CONTRACT_NOT_FOUND`; `save` never called |
| Creator A `GET /contracts/{B's id}/pdf-download-url` | **BLOCKED** — `404`; `r2StorageService` never touched |
| Creator A `GET /contracts?dealId={B's deal}` | **BLOCKED** — empty list (scoped subquery returns no rows; no existence oracle) |
| Brand JWT on creator-only `GET /contracts/unsigned` | **BLOCKED** — `403 WRONG_USER_TYPE` at controller |
| Creator JWT on `POST /contracts` generate | **BLOCKED** — brand branch requires `requireBrandWorkspace` |
| Spoof creator id in sign body (`ContractSignRequest`) | **N/A** — creator branch ignores body entirely |
| Spoof creator id in path/query | **N/A** — no creator id param on these routes |
| Unauthenticated probe | **BLOCKED** — `SecurityConfig` authenticated |

**H-1 extended to sign + list: CLOSED.** Hostile unit tests: `testGetForCreatorRejectsOtherCreatorContract`, `testCreatorSignRejectsCrossCreatorIdor`, `testGetPdfDownloadUrlForCreatorRejectsCrossCreatorIdor`.

---

## 2. Signature Replay & Concurrent Double-Sign

### 2a. Sequential replay (same creator, same contract)

`doRecordSignature` short-circuits when `creatorSignedAt != null` — returns persisted state without `save`, PDF generation, or escrow prompt:

```324:334:influora-api/src/main/java/com/influora/service/ContractService.java
    private ContractResponse doRecordSignature(Contract contract, boolean isBrand) {
        boolean alreadySignedByThisRole =
                isBrand ? contract.getBrandSignedAt() != null : contract.getCreatorSignedAt() != null;
        if (alreadySignedByThisRole) {
            ...
            return toResponse(contract, existingMilestones);
        }
```

**Test:** `testRetriedCreatorSignatureForCreatorIsNoOp` — verifies `never().save()`, no `eventPublisher` / `escrowHoldRepository` interaction.

### 2b. Concurrent race (two in-flight creator sign requests)

`recordSignatureForCreator` wraps mutation in `idempotencyService.executeOnce("contract-sign:" + contractId + ":CREATOR", ...)`. DB `UNIQUE(idempotency_key)` arbitrates; loser catches `AlreadyInProgressException` / `AlreadyCompletedException` and re-reads scoped contract — same pattern as E2 LOW-3 brand fix.

### 2c. Cross-path race (brand relay `role=CREATOR` vs creator JWT sign)

Both paths share the **identical** idempotency key `contract-sign:{contractId}:CREATOR`. A brand OWNER relay-sign and a creator-authenticated sign cannot both commit — exactly one wins, the other replays. **Signature forgery race between paths: CLOSED.**

### 2d. Replay matrix

| Scenario | PDF re-fired? | Escrow prompt re-fired? | DB double-write? |
|---|---|---|---|
| Retry after successful creator sign | **No** | **No** | **No** |
| Concurrent duplicate POST /sign | **No** (one winner) | **No** | **No** |
| Brand relay + creator sign same moment | **No** (shared key) | **No** | **No** |

---

## 3. Escrow Trigger Abuse

### 3a. Event path — notification only

```363:389:influora-api/src/main/java/com/influora/service/ContractService.java
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
```

```234:246:influora-api/src/main/java/com/influora/service/notification/NotificationListener.java
    public void on(ContractReadyForEscrowEvent event) {
        notificationService.notify(
                event,
                "Fund escrow to start the campaign",
                ...
                "/brand/wallet/escrow?contractId=" + event.entityId(),
                ...
                "brand.fund_escrow",
                ...);
    }
```

- **No** `@EventListener` on `EscrowService` / `WalletLedgerService` for this event.
- `EscrowService.initiateFund` requires `brandContext.requireMember` + `requireRole(OWNER, ADMIN)` — creator principal cannot reach it via this event chain.
- Amount is server-derived in `deriveFundAmount` (MF-1) when brand explicitly calls fund — not on dual-signature.

### 3b. Escrow abuse matrix

| Attack | Result |
|---|---|
| Creator signs → auto wallet debit | **BLOCKED** — event is in-app/email notification only |
| Creator replays sign to spam fund notifications | **BLOCKED** — idempotent no-op after first sign |
| Creator signs foreign contract to trigger brand debit | **BLOCKED** — IDOR on sign |
| Creator manipulates `contractId` in notification deep link | **N/A** — `entityId` server-set from owned contract row |
| Dual signature when FUNDED hold exists | **No prompt** — `existsByCollaborationIdAndStatus(FUNDED)` early return |
| Creator calls `POST /wallet/escrow/fund` directly | **BLOCKED** — brand-only controller path (Task #10 verified) |

**Escrow trigger abuse: CLOSED.**

---

## 4. `ContractController` Creator Branch Isolation

```46:93:influora-api/src/main/java/com/influora/web/ContractController.java
    @GetMapping
    public ApiResponse<List<ContractResponse>> list(...) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.listForCreator(principal, dealId));
        }
        throw new ApiException("WRONG_USER_TYPE", ...);
    }

    @PostMapping("/{contractId}/sign")
    public ApiResponse<ContractResponse> sign(...) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.recordSignatureForCreator(principal, contractId));
        }
        ...
    }
```

- `UserType` gate at controller prevents brand/creator path confusion.
- Creator sign never reads `body.role` — eliminates E2 role-forgery on creator-authenticated path.
- `GET /contracts` without `dealId` returns all creator-owned contracts via `findByCreatorId` — scoped, expected.

---

## 5. Findings Register

| ID | Severity | Finding | Status |
|---|---|---|---|
| H-1 (Task #10) | HIGH | Cross-creator contract read/PDF IDOR | **CLOSED** — extended to sign + list in Task #23 |
| E2 #10 | HIGH | Retried sign re-fired PDF/email | **CLOSED** — already-signed guard (pre-Task #23) |
| E2 LOW-3 | LOW | Concurrent same-role double-sign | **CLOSED** — `executeOnce` (pre-Task #23) |
| E2 LOW-4 | LOW | Brand relay `role=CREATOR` forgery | **Residual** — mitigated by creator JWT path; not removed |
| L-23-1 | LOW | No `ContractControllerTest` | **Open** — service tests only |
| L-23-2 | LOW | No `listForCreator` foreign `dealId` hostile test | **Open** — behavior verified by query shape |
| L-23-3 | LOW | No rate limit on `POST /contracts/{id}/sign` | **Open** — `AuthRateLimitFilter` returns null |
| L-23-4 | LOW | No `ContractStatus` terminal guard before sign | **Open** — theoretical; shared brand path |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #23 IDOR (read / sign / list / PDF) | **GO** |
| Signature replay / concurrent race | **GO** |
| Escrow auto-debit / trigger abuse | **GO** |
| Meera scoped unit-test gate (16/16) | **GO** (Meera confirmed; `mvn` unavailable in Kabir shell) |
| Ananya e-sign UI wire (A-3) | **GO** |
| Kavya QA Task #23 | **GO** (pending — not a security block) |
| Production deploy of creator e-sign | **GO** at security gate (pre-prod debt M-2/M-9-1 on deal room unchanged) |

**Pipeline position:** Task #23 security gate **✅ PASS WITH FINDINGS** — cleared for Ananya A-3 wiring and Priya integration sign-off. No escalation to Priya/Swapnil (no Critical/High).

---

## Kabir Sign-Off

- [x] Cross-creator contract read/sign/PDF IDOR probed — uniform `404`, H-1 join-through on all paths
- [x] Signature replay probed — idempotent no-op; no duplicate PDF or escrow prompt
- [x] Concurrent / cross-path (brand relay vs creator JWT) race probed — shared `contract-sign:{id}:CREATOR` key
- [x] Escrow trigger abuse probed — `ContractReadyForEscrowEvent` is notification-only; no wallet mutation listener
- [x] Creator cannot reach brand fund path — `EscrowService.initiateFund` brand-role gated
- [x] No Critical or High findings — pipeline **not blocked**
- [ ] L-23-3 contract-sign rate limit — optional pre-prod hardening (same class as M-19-2)
- [ ] E2 LOW-4 brand relay-sign removal — product decision; creator JWT path now available

**Kabir verdict: ✅ PASS WITH FINDINGS.** Unblocks Ananya Task A-3 e-sign UI. Escalation to Priya/Swapnil: **none**.

---

**Document Control:** Created 2026-07-09 by Kabir (Task #23). Prior: `creator-wallet-contract-T10-kabir-redteam.md` (H-1). Next: Kavya Task #23 QA; Ananya A-3 frontend wire.
