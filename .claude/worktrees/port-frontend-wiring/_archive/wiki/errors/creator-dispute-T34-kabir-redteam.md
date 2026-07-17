# Collaboration Disputes API — Task #34 V5 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~20:50 IST)  
**Verdict:** ⚠️ **PASS WITH FINDINGS** — **0 Critical, 1 High, 2 Medium, 4 Low**  
**Scope:** CEO §1.3 load-bearing dispute-freeze race, IDOR on open, admin resolve gating, one-active-dispute enforcement, no auto-refund/clawback on v1 stub  
**Reference:** `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.3; `wiki/tech/creator/15_CREATOR_DISPUTES_SPEC.md` §5.1; Kavya `wiki/errors/creator-dispute-T34-kavya-qa.md` (L-T34-1/2 escalations)  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/DisputeService.java`
- `influora-api/src/main/java/com/influora/service/EscrowService.java` (`hasFundedUnreleasedEscrow`, `freezeUnreleasedForDispute`, `release`, `refund`)
- `influora-api/src/main/java/com/influora/web/AdminDisputeController.java`
- `influora-api/src/main/java/com/influora/web/DealController.java` (`POST /deals/{dealId}/disputes`)
- `influora-api/src/main/java/com/influora/domain/entity/Dispute.java`
- `influora-api/src/main/java/com/influora/domain/entity/EscrowHold.java`
- `influora-api/src/main/resources/db/migration/V45__disputes.sql`
- `influora-api/src/main/java/com/influora/repository/DisputeRepository.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java` — `/admin/**` filter chain
- `influora-api/src/main/java/com/influora/service/admin/AdminContextService.java` — MFA + role tier
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java`
- `influora-api/src/test/java/com/influora/service/DisputeServiceTest.java` (8 tests)
- `influora-api/src/test/java/com/influora/service/EscrowServiceTest.java` (+1 freeze case)

---

## Executive Summary

Task #34 V5 dispute surface is **fail-closed on identity, admin authorization, and v1 money-movement scope** (resolve stub does not touch escrow). IDOR on open, admin-only resolve gating (filter + MFA + role tier), opener-type server derivation, XSS ingress, and no auto-refund/clawback on open/resolve are **CLOSED**.

**One load-bearing money-path gap confirmed:** concurrent `EscrowService.release()` (or `refund()`) can win a lost-update race against `openDispute()` because freeze runs **after** dispute insert, holds have **no row-level lock or `@Version`**, and release/refund **do not check** for an active dispute or `CollaborationStatus.DISPUTED`. CEO §1.3 “unreleased escrow freezes on open” is **not reliable under adversarial concurrency** — **H-T34-1 (HIGH)**.

**Meera M2 unit gate may proceed** (`DisputeServiceTest` + `EscrowServiceTest` scoped). **Production deploy blocked** until H-T34-1 remediated. M-T34-1/M-T34-2 are pre-prod hardening (same posture as T29 M-T29-1/M-T29-2).

---

## 1. LOAD-BEARING — Dispute-Open vs Concurrent Escrow Release (H-T34-1)

### 1a. Observed ordering (`DisputeService.openDispute`)

```59:91:influora-api/src/main/java/com/influora/service/DisputeService.java
    @Transactional
    public DisputeResponse openDispute(...) {
        // ...
        if (!escrowService.hasFundedUnreleasedEscrow(collaboration.getId())) { ... }

        if (disputeRepository.existsByCollaborationIdAndStatusIn(...)) { ... }

        Dispute dispute = Dispute.open(...);
        disputeRepository.save(dispute);          // ← dispute row exists; holds still FUNDED

        escrowService.freezeUnreleasedForDispute(collaboration.getId());  // ← freeze AFTER save
        // ...
    }
```

### 1b. Release path — no dispute guard

```247:281:influora-api/src/main/java/com/influora/service/EscrowService.java
    @Transactional
    public EscrowStatusResponse release(...) {
        // ... workspace/milestone auth ...
        EscrowHold hold = requireHold(milestone.getEscrowHoldId());
        if (hold.getStatus() == EscrowStatus.RELEASED) {
            return toStatusResponse(hold);
        }
        requireStatus(hold, EscrowStatus.FUNDED, "release");  // only checks hold status
        // ... ledger post, markReleased — no active-dispute check ...
    }
```

`refund()` mirrors the same pattern (`requireStatus(hold, EscrowStatus.FUNDED, "refund")` only). `EscrowHold` has **no `@Version`** optimistic lock and repositories use no `PESSIMISTIC_WRITE`.

### 1c. Race exploit matrix

| Concurrent threads | Outcome | CEO §1.3 compliant? |
|---|---|---|
| `openDispute` + `release` both read hold `FUNDED` | Last writer wins — hold may end `RELEASED` with active `OPEN` dispute | ❌ **FAIL** — funds escaped |
| `release` commits before `freezeUnreleasedForDispute` | Dispute opens; freeze is no-op (hold already `RELEASED`) | ❌ **FAIL** |
| `freezeUnreleasedForDispute` commits before `release` | `requireStatus(FUNDED)` → `409 INVALID_ESCROW_STATE` | ✅ Freeze wins |
| Window between `disputeRepository.save` and freeze | Dispute exists; escrow still `FUNDED` — release eligible | ❌ **FAIL** |

**Attack scenario (hostile brand admin):** Creator POSTs `POST /deals/{id}/disputes` while brand workspace OWNER/ADMIN simultaneously POSTs milestone release. Under default `READ_COMMITTED` isolation, release can complete after the dispute row is persisted but before freeze — or both transactions can lost-update the same `escrow_holds` row.

**Severity: HIGH** — direct undermining of CEO §1.3 escrow-freeze guarantee on the money rail. Spec §5.1 explicitly flagged this as Kabir K1 load-bearing scope.

### 1d. Recommended remediation (Vikram)

1. **Reorder + atomic freeze:** Call `freezeUnreleasedForDispute` **before** `disputeRepository.save`, or merge into a single atomic `UPDATE escrow_holds SET status='FROZEN' WHERE collaboration_id=? AND status='FUNDED'` with row-count assertion.
2. **Defense-in-depth on money paths:** `release()` / `refund()` reject when `DisputeRepository.existsByCollaborationIdAndStatusIn(..., {OPEN, UNDER_REVIEW})` or hold status is `FROZEN`.
3. **Row serialization:** Add `@Version` on `EscrowHold` or `SELECT … FOR UPDATE` when transitioning `FUNDED` → `{FROZEN|RELEASED|REFUNDED}`.
4. **Integration test:** Concurrent dispute-open + release threads; assert hold ends `FROZEN` and ledger release does not post.

---

## 2. One Active Dispute — TOCTOU Duplicate Open (M-T34-1)

### 2a. Gap

`V45__disputes.sql` has indexes on `collaboration_id` and `status` but **no partial unique constraint** on active statuses. Unlike `V41__reviews.sql` (`uq_review_collab_reviewer`), duplicate enforcement is application-only:

```74:80:influora-api/src/main/java/com/influora/service/DisputeService.java
        if (disputeRepository.existsByCollaborationIdAndStatusIn(
                collaboration.getId(), ACTIVE_STATUSES)) {
            throw new ApiException("DISPUTE_ALREADY_OPEN", ...);
        }
```

Two concurrent `POST /deals/{id}/disputes` can both pass `exists…` → **two `OPEN` rows** for one collaboration. Escrow freeze is idempotent; impact is admin-case duplication and policy violation, not direct fund theft.

**Severity: MEDIUM** — must fix before production (partial unique index or serializable transaction + `DataIntegrityViolationException` handler, same pattern as `ReviewService`).

---

## 3. IDOR — `POST /deals/{dealId}/disputes` (CLOSED)

### 3a. Gate chain

```141:158:influora-api/src/main/java/com/influora/service/DisputeService.java
    private Collaboration requireOwnedCollaboration(...) {
        if (role == UserType.CREATOR) {
            creatorContext.requireCreator(principal);
            return collaborationRepository
                    .findByIdAndCreatorId(dealId, principal.getUserId())
                    .orElseThrow(() -> new ApiException("DEAL_NOT_FOUND", ..., NOT_FOUND));
        }
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository
                .findByIdAndWorkspaceId(dealId, workspace.getId())
                .orElseThrow(() -> new ApiException("DEAL_NOT_FOUND", ..., NOT_FOUND));
    }
```

`opened_by_type` derived server-side from JWT role (L64–65). Not in `OpenDisputeRequest`.

### 3b. IDOR matrix

| Attack | Result |
|---|---|
| Creator A opens dispute on Creator B's `dealId` | **BLOCKED** — `404 DEAL_NOT_FOUND` |
| Brand A opens on Brand B workspace deal | **BLOCKED** — `404 DEAL_NOT_FOUND` |
| Foreign probe → existence oracle | **BLOCKED** — uniform 404 code/message |
| Admin JWT on deal dispute open | **BLOCKED** — `403 FORBIDDEN` (`requireDealPartyRole`) |
| Client spoofs `opened_by_type` | **N/A** — not in DTO |

**IDOR on dispute open: CLOSED.**

---

## 4. Admin-Only Resolve Gating (CLOSED)

### 4a. Filter chain

`SecurityConfig` L120–121: `/admin/**` → `hasAuthority("ROLE_ADMIN")`. Creator/brand JWTs lack `ROLE_ADMIN` → **403 at filter** before controller.

### 4b. Service layer

```106:110:influora-api/src/main/java/com/influora/service/DisputeService.java
        var admin =
                adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
```

`SUPPORT` excluded (`INSUFFICIENT_ROLE`). MFA enforced for `SUPER_ADMIN`/`ADMIN` per `AdminContextService` P0 item 4.

### 4c. Admin hostile-path matrix (code trace)

| Attack | Expected | Observed |
|---|---|---|
| Creator/brand JWT on `POST /admin/disputes/{id}/resolve` | 403 | ✅ Filter-chain `ROLE_ADMIN` |
| SUPPORT admin JWT | 403 `INSUFFICIENT_ROLE` | ✅ `requireRoleWithMfaSatisfied` allow-list |
| SUPER_ADMIN/ADMIN + MFA | 200 | ✅ Unit happy path |
| Resolve already-resolved dispute | 409 `DISPUTE_NOT_ACTIVE` | ✅ L129–134 — **no test** |
| Guess foreign `disputeId` | 404 `DISPUTE_NOT_FOUND` | ✅ L119–127 — **no test** |
| Resolve with `OPEN`/`UNDER_REVIEW` as resolution | 400 `INVALID_RESOLUTION` | ✅ Tested |

**Admin resolve authorization: CLOSED.** Resolve-by-`disputeId` without workspace scoping is **by design** (platform admin arbitration).

---

## 5. v1 Stub — No Auto-Refund / Clawback (CLOSED)

| Path | Money movement | Result |
|---|---|---|
| `openDispute` | `freezeUnreleasedForDispute` only (`FUNDED` → `FROZEN`) | ✅ No refund/release |
| `resolveDispute` | Status + notes + admin id only (L136–137) | ✅ No escrow/ledger calls |
| Released holds on open | Untouched per CEO §1.3 | ✅ `findFundedHoldsForCollaboration` filters `FUNDED` only |

**v1 money-movement boundary: CLOSED** (subject to H-T34-1 race undermining freeze reliability).

---

## 6. XSS / Stored Content (CLOSED)

`Dispute.open` L72–76 and `Dispute.resolve` L101–103 call `TextSanitizer.sanitizePlainText`. DTO `@NotBlank @Size(max=2000)` on reason. Same pattern as T29/T22 — **no test in dispute suite** (L-T34-4).

**XSS ingress: CLOSED** (untested, low residual risk).

---

## 7. Rate Limiting — Dispute Open Spam (M-T34-2)

`AuthRateLimitFilter.bucketFor()` returns `null` for `POST /deals/{dealId}/disputes` — no bucket. One-active-dispute limits repeat opens per collaboration after first success, but a party can spam attempts across **many owned deals** with funded escrow, triggering repeated freeze calls and `DISPUTED` transitions. Same abuse class as T29 M-T29-1 / deliverable M-21-1.

**Severity: MEDIUM** — recommend `"dispute-open"` bucket (e.g. 5/min per JWT `sub`) before production.

---

## 8. Test Coverage Gaps (LOW)

| ID | Finding |
|---|---|
| L-T34-1 | No concurrent open + release integration test (H-T34-1 proof) |
| L-T34-2 | No `AdminDisputeControllerTest` — filter-chain 403 paths |
| L-T34-3 | No `resolveDispute` tests for `DISPUTE_NOT_ACTIVE`, `DISPUTE_NOT_FOUND`, SUPPORT rejection |
| L-T34-4 | No `DealController.openDispute` delegation test; reason/notes sanitizer untested |

---

## Findings Register

| ID | Severity | Finding | Action |
|----|----------|---------|--------|
| **H-T34-1** | **HIGH** | Freeze-on-open vs concurrent `release`/`refund` lost-update race — escrow can escape before/at freeze | **Vikram** — atomic freeze + release dispute guard + row lock; integration test |
| **M-T34-1** | **MEDIUM** | One-active-dispute TOCTOU — no DB partial unique (Kavya L-T34-1 confirmed) | **Vikram** — partial unique index or serializable + DIVE handler |
| **M-T34-2** | **MEDIUM** | No rate-limit bucket for dispute open (Kavya L-T34-5 confirmed) | **Vikram** — `AuthRateLimitFilter` `"dispute-open"` bucket |
| L-T34-1 | LOW | No concurrent race integration test | Vikram + Meera CI |
| L-T34-2 | LOW | No `AdminDisputeControllerTest` | Optional |
| L-T34-3 | LOW | Admin hostile-path unit gaps | Optional |
| L-T34-4 | LOW | Sanitizer/controller delegation untested | Optional |
| L-T34-5 | INFO | `mvn` N/A in red-team env — Meera confirms 9/9 scoped PASS | Meera gate |

---

## Pipeline Routing

```
Kavya APPROVED WITH FINDINGS ──► Kabir K1 PASS WITH FINDINGS (0C/1H/2M/4L)
                                      │
                    ┌─────────────────┴─────────────────┐
                    ▼                                   ▼
         Meera M2 unit gate OK                   Vikram: H-T34-1 fix
         (9/9 scoped tests)                      before PRODUCTION deploy
                    │                                   │
                    └──────────────► Priya sign-off (conditional on H-T34-1)
```

**Production deploy:** **BLOCKED** until H-T34-1 remediated.  
**Meera M2:** **UNBLOCKED** — proceed with scoped `mvn test`.  
**Next owner:** Vikram (H-T34-1 atomic freeze) → Kabir re-spot-check → Meera full gate → Priya.

---

*Kabir Singh, Offensive Security / Red-Team Lead — Sage Digital*

---

# H-T34-1 Re-Spot — Hotfix Closure (Kabir K1b)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~21:00 IST)  
**Verdict:** ✅ **H-T34-1 CLOSED** — **0 Critical, 0 High** (M-T34-1/M-T34-2 unchanged pre-prod)  
**Trigger:** Vikram H-T34-1 hotfix after K1 `PASS WITH FINDINGS`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/DisputeService.java` — freeze-before-save reorder (L82–93)
- `influora-api/src/main/java/com/influora/service/EscrowService.java` — `assertEscrowNotBlockedByDispute`, `requireHoldForUpdate`, guarded `release`/`refund`, locked `freezeUnreleasedForDispute`
- `influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java` — `findByIdForUpdate` `@Lock(PESSIMISTIC_WRITE)`
- `influora-api/src/test/java/com/influora/service/DisputeServiceTest.java` — `openFreezesEscrowBeforeDisputeSave` (InOrder)
- `influora-api/src/test/java/com/influora/service/DisputeEscrowConcurrencyTest.java` (5 cases)
- `influora-api/src/test/java/com/influora/service/EscrowServiceTest.java`, `EscrowServiceReleaseTest.java` — constructor + lock stubs

---

## Executive Summary

Independent adversarial re-attack of the K1 load-bearing race confirms Vikram's four remediations are **correctly wired**. The original HIGH path — dispute row persisted while holds remain `FUNDED`, concurrent `release`/`refund` with no dispute guard, and lost-update on `escrow_holds` — is **CLOSED**.

**H-T34-1 is remediated.** Production deploy is **no longer blocked by this finding**. M-T34-1 (duplicate active dispute TOCTOU) and M-T34-2 (dispute-open rate limit) remain pre-prod hardening per K1 posture.

---

## 1. Remediation Verification

### 1a. Freeze-before-save (DisputeService.openDispute)

```82:93:influora-api/src/main/java/com/influora/service/DisputeService.java
        // H-T34-1: freeze unreleased escrow BEFORE the dispute row is visible ...
        escrowService.freezeUnreleasedForDispute(collaboration.getId());

        Dispute dispute = Dispute.open(...);
        disputeRepository.save(dispute);
```

**Re-attack:** No window where an `OPEN` dispute row exists while holds are still eligible for `release`/`refund` via the old ordering. `DisputeServiceTest.openFreezesEscrowBeforeDisputeSave` asserts `InOrder`: freeze → save. **CLOSED.**

### 1b. Money-path dispute guards (EscrowService.release / refund)

```285:294:influora-api/src/main/java/com/influora/service/EscrowService.java
        assertEscrowNotBlockedByDispute(collaboration);
        EscrowHold hold = requireHoldForUpdate(milestone.getEscrowHoldId());
        // ...
        requireStatus(hold, EscrowStatus.FUNDED, "release");
```

`assertEscrowNotBlockedByDispute` rejects when `CollaborationStatus.DISPUTED` **or** `DisputeRepository.existsByCollaborationIdAndStatusIn(OPEN, UNDER_REVIEW)` → `409 ESCROW_BLOCKED_BY_DISPUTE`. `refund()` applies the same guard after `requireHoldForUpdate` when `collaborationId` resolves.

**Re-attack matrix (post-fix):**

| Concurrent scenario | Outcome | K1 path closed? |
|---|---|---|
| Dispute `OPEN` + `release`/`refund` | `ESCROW_BLOCKED_BY_DISPUTE` before ledger | ✅ |
| Collab `DISPUTED` + `release` | `ESCROW_BLOCKED_BY_DISPUTE` | ✅ |
| Hold `FROZEN` + `release` | `INVALID_ESCROW_STATE` (no ledger post) | ✅ |
| Hold `FROZEN` + `refund` with active dispute | `ESCROW_BLOCKED_BY_DISPUTE` | ✅ |
| Lost-update: both read `FUNDED` unguarded | `PESSIMISTIC_WRITE` serializes — one transition wins cleanly | ✅ |
| Freeze commits before `release` | `requireStatus(FUNDED)` → `INVALID_ESCROW_STATE` | ✅ |

### 1c. Pessimistic row locks (EscrowHoldRepository.findByIdForUpdate)

All `FUNDED → {FROZEN|RELEASED|REFUNDED}` transitions acquire `findByIdForUpdate` (`PESSIMISTIC_WRITE`). `freezeUnreleasedForDispute` sorts hold ids before locking (deadlock avoidance). **Lost-update on `escrow_holds` status: CLOSED.**

### 1d. Test coverage (DisputeEscrowConcurrencyTest)

| Test | Proves |
|---|---|
| `releaseRejectedWhenHoldFrozen` | FROZEN hold → `INVALID_ESCROW_STATE`, no ledger |
| `releaseRejectedWhenActiveDispute` | Active dispute → `ESCROW_BLOCKED_BY_DISPUTE`, no lock/ledger |
| `releaseRejectedWhenCollaborationDisputed` | `DISPUTED` collab → `ESCROW_BLOCKED_BY_DISPUTE` |
| `refundRejectedWhenActiveDispute` | Refund path parity |
| `concurrentFreezeAndRelease_freezeWins` | Simulated race: freeze wins, hold `FROZEN`, release fails, no ledger |

**Note:** `mvn` N/A in re-spot env; Meera scoped gate authoritative. Tests are mock-based (not Testcontainers) — acceptable for service-layer race proof at M2 bar; full DB integration remains L-T34-1 carry-forward.

---

## 2. Residual Observations (non-blocking)

| ID | Severity | Observation |
|---|---|---|
| L-T34-6 | **LOW** | **First-lock-wins race:** If `release`/`refund` acquires `findByIdForUpdate` *before* `freezeUnreleasedForDispute` in the same `openDispute` transaction (no dispute row / not `DISPUTED` yet), funds may exit and dispute still opens (`freeze` no-ops on non-`FUNDED`). This is fair serialization, not lost-update or dispute-while-FUNDED — **not H-T34-1**. Optional hardening: fail open when `freezeUnreleasedForDispute` returns 0 after `hasFundedUnreleasedEscrow` was true. |
| L-T34-7 | **LOW** | `refund()` checks dispute guard **after** lock vs `release()` **before** lock — behavior equivalent under dispute; minor ordering asymmetry only. |
| M-T34-1 | **MEDIUM** | Unchanged — no DB partial unique on active disputes |
| M-T34-2 | **MEDIUM** | Unchanged — no `dispute-open` rate-limit bucket |

---

## 3. Findings Register (post re-spot)

| ID | Severity | Status |
|----|----------|--------|
| **H-T34-1** | ~~HIGH~~ | ✅ **CLOSED** — freeze-before-save + dispute guards + pessimistic locks + concurrency tests |
| M-T34-1 | MEDIUM | OPEN — pre-prod |
| M-T34-2 | MEDIUM | OPEN — pre-prod |
| L-T34-1 … L-T34-5 | LOW | Carry-forward (L-T34-1 partially addressed by `DisputeEscrowConcurrencyTest`) |
| L-T34-6, L-T34-7 | LOW | New — non-blocking |

---

## 4. Pipeline Routing (updated)

```
K1 PASS WITH FINDINGS (H-T34-1) ──► Vikram hotfix ──► Kabir K1b re-spot ✅ H-T34-1 CLOSED
                                                          │
                    ┌─────────────────────────────────────┴──────────────────────┐
                    ▼                                                            ▼
         Meera scoped gate (17/17 expected)                          Priya prod sign-off
         DisputeServiceTest + Escrow* + ConcurrencyTest              (H-T34-1 unblocked;
                    │                                                M-T34-1/2 pre-prod sprint)
                    └──────────────────────────► PRODUCTION deploy eligible for #34 money path
```

**Production deploy:** **UNBLOCKED** for H-T34-1. Ship with M-T34-1/M-T34-2 on pre-prod backlog.  
**Next owner:** Meera full scoped re-run → Priya conditional #34 prod sign-off.

---

*Kabir Singh, Offensive Security / Red-Team Lead — Sage Digital (H-T34-1 re-spot)*
