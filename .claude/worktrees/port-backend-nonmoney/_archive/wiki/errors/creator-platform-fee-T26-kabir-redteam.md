# PlatformFeeService — Creator Escrow-Release Fee — Task #26 V1 (Kabir K2 Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~19:55 IST)  
**Verdict:** ✅ **PASS WITH FINDINGS** — no Critical/High blockers; route to **Meera M2**  
**Scope:** Vikram Task #26 V1 — `PlatformFeeConfig` + `PlatformFeeService.deductAtRelease()` wired in `EscrowService.release()` before creator credit (LOAD-BEARING money path per `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §4 P0-K2 / TECH-STACK Guardrail 1)  
**Reference:** Kavya `wiki/errors/creator-platform-fee-T26-kavya-qa.md` (APPROVED); `wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md` §1A, §5.0  
**Reviewed Files:**
- `influora-api/src/main/resources/db/migration/V41__platform_fee_config.sql`
- `influora-api/src/main/java/com/influora/domain/entity/PlatformFeeConfig.java`
- `influora-api/src/main/java/com/influora/repository/PlatformFeeConfigRepository.java`
- `influora-api/src/main/java/com/influora/service/PlatformFeeService.java`
- `influora-api/src/main/java/com/influora/service/PlatformWalletService.java`
- `influora-api/src/main/java/com/influora/service/EscrowService.java` (`release`, `confirmFunded`)
- `influora-api/src/main/java/com/influora/service/WalletLedgerService.java` (idempotency + `uq_wtx_idem` contract)
- `influora-api/src/main/java/com/influora/web/EscrowController.java` (release auth surface)
- `influora-api/src/test/java/com/influora/service/PlatformFeeServiceTest.java` (6 tests)
- `influora-api/src/test/java/com/influora/service/EscrowServiceReleaseTest.java` (2 tests)

---

## Executive Summary

Task #26 V1 **passes the K2 LOAD-BEARING money-path gate**. `PlatformFeeService.deductAtRelease()` is the **sole sanctioned new money-path addition** on the creator escrow-release leg: fee bps are read fresh from the DB singleton on every call, gross is server-derived from `EscrowHold.getAmount()`, ledger postings use distinct idempotency keys (`release-fee:{escrowHoldId}` / `release:{escrowHoldId}`), and all balance mutations flow exclusively through `WalletLedgerService.post()`. `confirmFunded()` is **unchanged** — no fee at funding.

**Concurrent double-deduction:** Adversarial replay of parallel `release()` calls on the same FUNDED hold is serialized by ledger idempotency (`uq_wtx_idem` + `DataIntegrityViolationException` catch-and-replay in `WalletLedgerService.post()`), not by an escrow-hold row lock. Re-attack confirms **no double fee debit and no double creator credit** under race.

**No Critical or High findings.** Six LOW carry-forwards (test gaps + one ops-edge config-race) are non-blocking for Meera M2.

**Test execution:** `mvn` unavailable in Kabir shell — logic verified by hostile code review + Kavya-authored 8/8 unit tests. Meera must confirm **8/8 PASS** on scoped suite.

---

## K2 Load-Bearing Checklist (TECH-STACK §5 / CEO §4)

| # | Requirement | Result | Evidence |
|---|-------------|--------|----------|
| K2-1 | Fee deduction is **ONLY** new money-path code | ✅ PASS | `deductAtRelease` is the only new ledger caller on release. `confirmFunded` / `initiateFund` / `refund` untouched — grep confirms single production callsite in `EscrowService.release()` L282–289. |
| K2-2 | **No double-deduction** race on concurrent `release()` | ✅ PASS | Fee key `release-fee:{holdId}` + release key `release:{holdId}`; `WalletLedgerService` L85–94 fast-path + L172–179 `uq_wtx_idem` authoritative serialization. Escrow hold has no `FOR UPDATE` lock, but ledger keys are hold-scoped and deterministic — see §2. |
| K2-3 | Config read **fresh per calculation** (no stale cache) | ✅ PASS | `resolveCreatorFeeBps()` → `configRepository.findById("default")` on every `deductAtRelease` invocation. No `@Cacheable` on `PlatformFeeService` or `PlatformFeeConfigRepository`. Redis cache exists only for admin pulse stats. |
| K2-4 | Idempotency on `release-fee:{escrowHoldId}` | ✅ PASS | Key built server-side from `hold.getId()` L95 — not client-supplied. Distinct from creator credit key `release:{holdId}` EscrowService L291. |
| K2-5 | **No direct** `Wallet.balance` mutations outside `WalletLedgerService.post()` | ✅ PASS | `PlatformFeeService` never calls `applyBalanceDelta` or `walletRepository.save`. Only `WalletLedgerService` L129/L148 mutates balances in the money path. |
| K2-6 | **Funding path unchanged** | ✅ PASS | `confirmFunded()` posts only `ESCROW_HOLD` L206–217; `platformFeeService` never referenced. `EscrowServiceReleaseTest.testConfirmFundedDoesNotDeductPlatformFee` asserts `never().deductAtRelease(...)`. |

---

## 1. Money-Path Isolation — Sole New Ledger Surface

### 1a. Release path (fee → net credit)

```282:303:influora-api/src/main/java/com/influora/service/EscrowService.java
        var feeDeduction =
                platformFeeService.deductAtRelease(
                        clearingWallet,
                        milestone.getId(),
                        payeeUserId,
                        hold.getAmount(),
                        hold.getCurrency(),
                        hold.getId());

        String idempotencyKey = "release:" + hold.getId();
        var posting =
                ledgerService.post(
                        clearingWallet.getId(),
                        payeeWallet.getId(),
                        feeDeduction.netAmount(),
                        hold.getCurrency(),
                        WalletTransactionType.ESCROW_RELEASE,
                        ...
                        idempotencyKey,
                        null);
```

- **Gross amount:** `hold.getAmount()` — persisted escrow row, never request body.
- **Payee:** `collaboration.getCreatorId()` — server-resolved L268; `EscrowReleaseRequest` carries only `milestoneId`.
- **Conservation:** `split()` guarantees `platformFee + netAmount = grossAmount` PlatformFeeService L47–53.
- **Transaction boundary:** `EscrowService.release`, `PlatformFeeService.deductAtRelease`, and `WalletLedgerService.post` share `REQUIRED` propagation — partial failure rolls back both legs.

### 1b. Funding path (unchanged)

```206:217:influora-api/src/main/java/com/influora/service/EscrowService.java
        var posting =
                ledgerService.post(
                        brandWallet.getId(),
                        clearingWallet.getId(),
                        hold.getAmount(),
                        hold.getCurrency(),
                        WalletTransactionType.ESCROW_HOLD,
                        ...
                        hold.getIdempotencyKey(),
                        gatewayRef);
```

No `PlatformFeeService` reference anywhere in `initiateFund` / `confirmFunded` / `refund`.

### 1c. Out-of-scope dual config (pre-existing, not T26 regression)

`AmountDerivationService` (Meera `/internal/meera/*` path) still reads `RazorpayProperties.platformFeePercent` from `application.yml` — **not** `platform_fee_config`. This is outside Task #26's escrow-release scope and does not affect the creator release leg audited here. Tracked as **L-K2-T26-6** for future config-unification hygiene.

**K2-1 / K2-6: CLOSED.**

---

## 2. Concurrent Release Race — Double-Deduction Re-Attack

### 2a. Attack model

| Vector | Preconditions | Expected harm |
|--------|---------------|---------------|
| Double-click / parallel HTTP `POST /wallet/escrow/release` | Same milestone, hold `FUNDED`, two threads before `markReleased` commits | Double `PLATFORM_FEE` debit and/or double `ESCROW_RELEASE` credit |
| Retry after partial failure | Client retries release while first attempt in-flight | Duplicate fee or over-credit |
| RELEASED hold replay | Hold already `RELEASED` | Second fee deduction |

### 2b. Defenses (code-verified)

**Hold status early return (RELEASED replay):**

```274:276:influora-api/src/main/java/com/influora/service/EscrowService.java
        if (hold.getStatus() == EscrowStatus.RELEASED) {
            return toStatusResponse(hold); // idempotent no-op
        }
```

Runs **before** `deductAtRelease` — no second fee on status replay.

**Ledger idempotency (concurrent FUNDED → RELEASED race):**

```85:94:influora-api/src/main/java/com/influora/service/WalletLedgerService.java
        LedgerPostingResult existing = findExistingPosting(idempotencyKey);
        if (existing != null) {
            return existing;
        }
```

```172:179:influora-api/src/main/java/com/influora/service/WalletLedgerService.java
        } catch (DataIntegrityViolationException e) {
            LedgerPostingResult raced = findExistingPosting(idempotencyKey);
            if (raced != null) {
                return raced;
            }
```

`uq_wtx_idem` on `wallet_transactions.idempotency_key` (V8 migration) is the authoritative serialization point when two threads both miss the upfront SELECT.

**Wallet pessimistic lock:** `findByIdForUpdate` on debit/credit wallets L101–104 prevents concurrent postings from corrupting balance projection mid-write.

### 2c. Re-attack outcome

| Scenario | Result |
|----------|--------|
| Two threads, same hold, both pass `FUNDED` check | First thread posts `release-fee:X` + `release:X`; second thread replays both via idempotency — **one fee, one credit** |
| Thread A commits RELEASED; Thread B still in-flight | B's fee/release posts replay; hold save is idempotent state write — **no extra money movement** |
| RELEASED hold retry | Early return L274 — **fee never called** |

**No escrow-hold `FOR UPDATE` lock** — acceptable because money correctness is enforced at the ledger layer (same pattern as escrow fund webhook idempotency). Escrow row TOCTOU does not bypass ledger keys.

**K2-2: CLOSED.**

---

## 3. Config Freshness — Stale Fee % Re-Attack

```38:41:influora-api/src/main/java/com/influora/service/PlatformFeeService.java
  @Transactional(readOnly = true)
  public int resolveCreatorFeeBps() {
    return requireConfig().getDefaultFeeBps();
  }
```

```76:77:influora-api/src/main/java/com/influora/service/PlatformFeeService.java
    int feeBps = resolveCreatorFeeBps();
    FeeSplit split = split(grossAmount, feeBps);
```

- Every `deductAtRelease` issues a fresh `findById("default")` — admin PUT via `PlatformFeeAdminService` is visible on the next release without redeploy.
- No JVM-level or Redis cache on this path.
- No hardcoded 1500 fallback in Java — seed lives only in Flyway `V41` L21–22.
- `effectiveAt` column (V42) is **not consulted** on release — fee changes apply immediately regardless of `effectiveAt`. Spec §1A.3 versioning deferred; **L-K2-T26-5**.

**K2-3: CLOSED** (fresh read). `effectiveAt` honor deferred — LOW carry-forward only.

---

## 4. Idempotency Key Contract

| Leg | Key | Client-controlled? | DB enforcement |
|-----|-----|-------------------|----------------|
| Platform fee | `release-fee:{escrowHoldId}` | ❌ server-derived from `hold.getId()` | `uq_wtx_idem` |
| Creator credit | `release:{escrowHoldId}` | ❌ server-derived | `uq_wtx_idem` |
| Escrow fund | `hold.getIdempotencyKey()` | ✅ header at fund time | `uq_wtx_idem` + `uq_escrow_idem` |

Fee and release keys are **pairwise distinct** — no collision between `PLATFORM_FEE` and `ESCROW_RELEASE` legs on the same hold.

**Missing config fail-closed:**

```103:111:influora-api/src/main/java/com/influora/service/PlatformFeeService.java
  private PlatformFeeConfig requireConfig() {
    return configRepository
        .findById(PlatformFeeConfig.SINGLETON_ID)
        .orElseThrow(
            () ->
                new ApiException(
                    "PLATFORM_FEE_CONFIG_MISSING",
                    ...
                    HttpStatus.INTERNAL_SERVER_ERROR));
  }
```

Migration failure / row deletion → 500 before any ledger mutation. Not unit-tested — **L-K2-T26-3**.

**K2-4: CLOSED.**

---

## 5. Wallet.balance Guardrail — Direct Mutation Re-Attack

| Service | `applyBalanceDelta` | `walletRepository.save` (balance) |
|---------|---------------------|-----------------------------------|
| `PlatformFeeService` | ❌ never | ❌ never |
| `EscrowService` | ❌ never | ❌ never (hold/milestone rows only) |
| `WalletLedgerService.post()` | ✅ sole writer | ✅ inside posting txn |
| `PlatformWalletService` | ❌ | ✅ lazy wallet **creation** only (zero balance) |

`PlatformFeeServiceTest.testDeductAtReleaseNoDirectBalanceMutation` asserts clearing entity balance unchanged after call; only `ledgerService.post` invoked.

**K2-5: CLOSED.**

---

## 6. Authorization & Cross-Workspace Isolation

### 6a. Release endpoint

```82:88:influora-api/src/main/java/com/influora/web/EscrowController.java
    @PostMapping("/release")
    public ApiResponse<EscrowStatusResponse> release(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody EscrowReleaseRequest body) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        return ApiResponse.ok(escrowService.release(principal, workspace.getId(), body.milestoneId()));
    }
```

```245:246:influora-api/src/main/java/com/influora/service/EscrowService.java
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);
```

### 6b. Cross-workspace milestone guessing

| Attack | Result |
|--------|--------|
| Brand A JWT + Brand B `milestoneId` | `hold.getWorkspaceId().equals(workspaceId)` fails L271–272 → `404 ESCROW_NOT_FOUND` — **no fee posted** |
| Creator JWT on `/wallet/escrow/release` | `requireBrandWorkspace` → **403** (brand-only surface) |
| Unauthenticated release | `SecurityConfig` authenticated → **401** |
| Redirect payout to attacker wallet via request body | **N/A** — payee not in DTO |

**AuthZ / IDOR: CLOSED.**

---

## 7. Ledger Traceability & Revenue Wallet Separation

- Fee posts: `clearingWallet` → `platformWalletService.requireRevenueWallet()` (`PLATFORM_REVENUE_WALLET_OWNER_ID` sentinel).
- Distinct from clearing wallet — finance can query `PLATFORM_FEE` rows by `referenceType=MILESTONE`, `referenceId=milestoneId`.
- `group_id` pairs both debit/credit legs per posting — standard ledger audit trail.

**Traceability: CLOSED.**

---

## 8. Adversarial Edge — Admin Fee Change Mid-Release (Ops)

**Scenario:** SUPER_ADMIN updates `default_fee_bps` while two `release()` transactions are in-flight on the same hold.

1. Thread A reads 1200 bps → computes fee ₹1200, net ₹8800.
2. Thread B reads 1500 bps → computes fee ₹1500, net ₹8500.
3. Fee leg: first commit wins (say ₹1200); second replays via idempotency.
4. Release leg: thread with **locally computed** `netAmount` may not match the fee actually posted if configs diverged.

**Outcomes (re-attack):**
- **Over-debit attempt** (net too high vs remaining clearing balance): `INSUFFICIENT_BALANCE` in `WalletLedgerService` L119–124 → whole `@Transactional` release rolls back. Hold stays `FUNDED`; safe retry.
- **Under-debit success** (fee ₹1200 posted, release ₹8500 at 15% math): total outflow ₹9700 on ₹10000 gross → **₹300 stranded in clearing** without ledger attribution.

**Exploitability:** Requires privileged admin fee mutation coincident with duplicate release attempts. External attacker cannot set fee bps. Not a theft vector — worst case creator underpayment or clearing residual. Classified **LOW (L-K2-T26-4)**. V2 hardening: derive `netAmount` from replayed `feePosting.amount()` when idempotency replay detected, or freeze bps in a release intent row.

---

## 9. Test Coverage — Adversarial Gaps

| Area | Code | Adversarial test | Gap ID |
|------|------|------------------|--------|
| 15% fee math | ✅ | ✅ | — |
| DB-driven bps | ✅ | ✅ | — |
| Revenue wallet + `PLATFORM_FEE` type | ✅ | ✅ | — |
| No direct balance mutation | ✅ | ✅ | — |
| Zero bps skip | ✅ | ✅ | — |
| Release wires fee-before-credit | ✅ | ✅ | — |
| Funding skips fee | ✅ | ✅ | — |
| `release-fee:` idempotency replay | ✅ | ❌ | L-K2-T26-1 |
| RELEASED hold skips `deductAtRelease` | ✅ | ❌ | L-K2-T26-2 |
| `PLATFORM_FEE_CONFIG_MISSING` | ✅ | ❌ | L-K2-T26-3 |
| Concurrent release integration | ✅ (ledger) | ❌ | Meera / V2 |

---

## 10. Findings

### Blockers (Critical / High)

**None.**

### Non-blocking carry-forward

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| L-K2-T26-1 | LOW | No unit test proving second `deductAtRelease` with same `escrowHoldId` replays ledger without second balance effect | Mock/integration: assert single `post` side effect on replay |
| L-K2-T26-2 | LOW | No test that `release()` on `RELEASED` hold never calls `deductAtRelease` | Add `EscrowServiceReleaseTest` case |
| L-K2-T26-3 | LOW | `PLATFORM_FEE_CONFIG_MISSING` not unit-tested | One negative test in `PlatformFeeServiceTest` |
| L-K2-T26-4 | LOW | Admin fee-bps change during in-flight concurrent `release()` can strand clearing residual (fee posting replays at amount A, release debits net computed at bps B) | V2: bind net to replayed fee leg or snapshot bps at release start |
| L-K2-T26-5 | LOW | `effectiveAt` on `platform_fee_config` not honored at release — immediate application only | Track for fee-versioning V2 per spec §1A.3 |
| L-K2-T26-6 | LOW | `AmountDerivationService` uses `application.yml` `platform-fee-percent`, not DB singleton — config drift risk on Meera path (pre-existing, out of T26 scope) | Unify config sources in future slice |

---

## 11. Routing

| Next gate | Owner | Action |
|-----------|-------|--------|
| Build confirm | **Meera M2** | `cd influora-api && mvn test -Dtest=PlatformFeeServiceTest,EscrowServiceReleaseTest` + full build |
| Transparency endpoint | **Kavya Kv1 on #27** | Separate gate (V2) |
| Sign-off | **Priya** | Payments slice after Meera M2 |

---

**Kabir K2 sign-off:** ✅ **PASS WITH FINDINGS** — LOAD-BEARING money-path invariants hold. **No pipeline block.** Route to **Meera M2**.
