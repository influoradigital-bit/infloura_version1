# Creator Wallet/Contract/Escrow Paths — Task #10 H-1 Re-Review (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** Targeted re-review of Vikram Task #10 H-1 fix — `ContractRepository.findByIdAndCreatorId` join-through query, creator branches on `WalletController`, `ContractController`, `EscrowController`, and corresponding service-layer ownership helpers  
**Prior Finding:** H-1 in `wiki/errors/creator-context-service-T11-kabir-redteam.md`  
**Reference Spec:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.3, §8 (Red Team Checklist — IDOR on wallet/contract endpoints)

---

## Executive Summary

**VERDICT: PASS**

H-1 is **closed**. Vikram implemented the exact join-through ownership query prescribed in Task #11, and every new creator-facing read path resolves resource ownership via `principal.getUserId()` — never a client-supplied id. Cross-creator contract read, PDF presign minting, wallet balance/summary, and escrow status probes all return uniform `404` on foreign resources with no IDOR oracle.

**Task #10 is cleared for Meera build verify and Ananya `creator-wallet.tsx` wiring.**

No Critical or High findings. Two Low hardening items carried forward from Task #11 (L-1, L-2) plus one new Low (L-10-1, non-blocking).

---

## 1. H-1 Fix Verification — `ContractRepository.findByIdAndCreatorId`

```18:22:influora-api/src/main/java/com/influora/repository/ContractRepository.java
    @Query(
            "SELECT c FROM Contract c WHERE c.id = :id AND c.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId)")
    Optional<Contract> findByIdAndCreatorId(
            @Param("id") String id, @Param("creatorUserId") String creatorUserId);
```

- Matches the prescribed H-1 fix verbatim — join-through `Collaboration.creatorId` because `Contract` has no direct `creator_id` column (same trust-boundary shape as `CollaborationRepository.findByWorkspaceId` and `DealService.requireCreatorCollaboration`).
- `Collaboration.creatorId` stores the authenticated **user id** (see `Collaboration.apply()` / `invite()` factories), and all service paths pass `principal.getUserId()` — not a profile id, not a path param. Correct key alignment.

**Service chokepoint:**

```494:498:influora-api/src/main/java/com/influora/service/ContractService.java
    private Contract requireContractForCreator(String contractId, String creatorUserId) {
        return contractRepository
                .findByIdAndCreatorId(contractId, creatorUserId)
                .orElseThrow(
                        () -> new ApiException("CONTRACT_NOT_FOUND", "Contract not found", HttpStatus.NOT_FOUND));
```

- `getForCreator` and `getPdfDownloadUrlForCreator` both call `creatorContext.requireCreator(principal)` then `requireContractForCreator(contractId, principal.getUserId())` before returning data or minting a presigned URL.
- Uniform `CONTRACT_NOT_FOUND` (404) on miss — no distinct "forbidden" that would leak existence of foreign contracts. Enumeration-oracle discipline preserved.

**Hostile test coverage (service layer):**

| Test | Assertion |
|---|---|
| `ContractServiceTest.testGetForCreatorOwnContract` | Own contract found via scoped lookup; `findByIdAndWorkspaceId` never called |
| `ContractServiceTest.testGetForCreatorRejectsOtherCreatorContract` | Foreign contract → `CONTRACT_NOT_FOUND` 404; `findById` never called |
| `ContractServiceTest.testGetPdfDownloadUrlForCreatorRejectsCrossCreatorIdor` | Foreign PDF mint blocked; `r2StorageService` never touched |

**IDOR exploit from original H-1 scenario (enumerate ULID → read contract + mint PDF): CLOSED.**

---

## 2. `ContractController` Creator Branch

```43:77:influora-api/src/main/java/com/influora/web/ContractController.java
    @GetMapping("/{contractId}")
    public ApiResponse<ContractResponse> get(...) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.getForCreator(principal, contractId));
        }
        ...
    }

    @GetMapping("/{contractId}/pdf-download-url")
    public ApiResponse<ContractPdfDownloadResponse> pdfDownloadUrl(...) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(contractService.getPdfDownloadUrlForCreator(principal, contractId));
        }
        ...
    }
```

- Creator branch routes exclusively through H-1-scoped service methods.
- `POST /contracts/{contractId}/sign` remains **brand-only** (`requireBrandWorkspace` with no creator branch) — correct per Task #11 residual-risk note. A creator-authenticated signing flow is out of scope for Task #10 and would need its own review if added later.
- Brand path unchanged — `requireBrandWorkspace` + `findByIdAndWorkspaceId`. A creator principal cannot reach the brand branch without failing `requireBrandWorkspace` first.

---

## 3. `WalletController` Creator Branch

```33:54:influora-api/src/main/java/com/influora/web/WalletController.java
    @GetMapping("/balance")
    public ApiResponse<WalletBalanceResponse> getBalance(...) {
        if (principal.getUserType() == UserType.CREATOR) {
            creatorContext.requireCreator(principal);
            return ApiResponse.ok(walletService.getBalanceForUser(principal.getUserId()));
        }
        ...
    }

    @GetMapping
    public ApiResponse<WalletSummaryResponse> getSummary(...) {
        if (principal.getUserType() == UserType.CREATOR) {
            creatorContext.requireCreator(principal);
            return ApiResponse.ok(walletService.getSummaryForUser(principal.getUserId()));
        }
        ...
    }
```

- Owner id derived exclusively from `principal.getUserId()` — no path/query/body user id. Matches Task #11 GO pattern.
- `WalletService.getBalanceForUser` / `getSummaryForUser` key off `walletRepository.findByOwnerId(userId)` — 1:1 owner scope, no join needed.
- `getSummaryForUser` pending payouts scoped via `PaymentMilestoneRepository.sumAmountByCreatorIdAndStatus` join-through `Collaboration.creatorId` — cannot leak another creator's milestone totals.

**Hostile tests:** `WalletServiceTest` covers own-wallet read, zero-balance for wallet-less creator, and pending-payout scoping.

---

## 4. `EscrowController` Creator Branch (Read-Only)

```72:80:influora-api/src/main/java/com/influora/web/EscrowController.java
    @GetMapping("/{escrowHoldId}")
    public ApiResponse<EscrowStatusResponse> status(...) {
        if (principal.getUserType() == UserType.CREATOR) {
            return ApiResponse.ok(escrowService.getStatusForCreator(principal, escrowHoldId));
        }
        ...
    }
```

- Creator path is **read-only status** — `fund`, `release`, `refund`, `payout` remain brand-only. Correct privilege separation.
- `EscrowHoldRepository.findByIdAndCreatorId` uses the same collaboration join-through pattern; holds with no `collaboration_id` are excluded (pre-deal campaign escrow invisible to creators — intentional, not an IDOR gap).
- Uniform `ESCROW_NOT_FOUND` 404 on cross-creator probe (`EscrowServiceTest.testGetStatusForCreatorRejectsCrossCreatorIdor`).

---

## 5. Attack Surface Re-Check

| Vector | Result |
|---|---|
| Creator enumerates another creator's `contractId` → GET `/contracts/{id}` | **BLOCKED** — `CONTRACT_NOT_FOUND` 404 |
| Creator enumerates foreign contract → GET `/contracts/{id}/pdf-download-url` | **BLOCKED** — presign never minted |
| Creator calls brand wallet path | **BLOCKED** — `requireBrandWorkspace` rejects creator principal |
| Creator calls brand contract path with forged workspace | **BLOCKED** — `requireBrandWorkspace` / `requireMember` |
| Creator calls escrow fund/release/refund/payout | **BLOCKED** — brand-only endpoints |
| Creator spoofs `userType` in request body | **N/A** — `AuthPrincipal` from verified JWT only |
| `contractRepository.findById` bypass in creator read paths | **NOT PRESENT** — verified in unit tests |

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| H-1 | ~~HIGH~~ | `ContractRepository`/`ContractService` creator-ownership-scoped contract lookup | **RESOLVED** — join-through query + scoped service methods land in Task #10 |
| L-1 | LOW | No reusable nested-resource ownership helper on `CreatorContextService` (parallel to `BrandContextService.requireMember`) | Open — recommend when next creator-resource endpoint ships |
| L-2 | LOW | `CreatorContextServiceTest` lacks explicit multi-profile regression test | Open — non-blocking |
| L-10-1 | LOW | No controller-level tests for `WalletController`/`ContractController`/`EscrowController` creator branches (service tests cover isolation) | Open — non-blocking |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #10 overall (wallet + contract + escrow creator paths) | **GO** — H-1 closed, IDOR vectors blocked |
| Ananya `creator-wallet.tsx` wiring | **UNBLOCKED** |
| Meera build verify on Task #10 slice | **GO** — route scoped `mvn test` on `ContractServiceTest`, `WalletServiceTest`, `EscrowServiceTest` |

**Pipeline position:** Task #10 security gate **PASS**. Proceed to Meera build verify → Ananya wallet UI.
