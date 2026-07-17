# QA Review: PlatformFeeService — Creator Escrow-Release Fee — Task #26 V1 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~19:50 IST)  
**Verdict:** ✅ **APPROVED** — routed to **Kabir K2 (LOAD-BEARING money path)** → Meera M2 build verify → Priya sign-off on payments slice  
**Scope:** Vikram Task #26 V1 — `PlatformFeeConfig` + `PlatformFeeService.deductAtRelease()` wired in `EscrowService.release()` before creator credit  
**Reference:** `TASK_INBOX.md` Task #26; `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §4 P0-V1; `10_CREATOR_PAYMENTS_SPEC.md` §1A; `KAVYA_QA_TEST_PLAN.md` hostile-path checklist  
**Reviewed Files:**
- `influora-api/src/main/resources/db/migration/V41__platform_fee_config.sql` (seed 1500 bps)
- `influora-api/src/main/java/com/influora/domain/entity/PlatformFeeConfig.java`
- `influora-api/src/main/java/com/influora/repository/PlatformFeeConfigRepository.java`
- `influora-api/src/main/java/com/influora/service/PlatformFeeService.java`
- `influora-api/src/main/java/com/influora/service/PlatformWalletService.java`
- `influora-api/src/main/java/com/influora/service/EscrowService.java` (`release`, `confirmFunded`)
- `influora-api/src/main/java/com/influora/service/WalletLedgerService.java` (idempotency contract cross-check)
- `influora-api/src/test/java/com/influora/service/PlatformFeeServiceTest.java` (6 tests)
- `influora-api/src/test/java/com/influora/service/EscrowServiceReleaseTest.java` (2 tests)
- `influora-api/src/test/java/com/influora/service/EscrowServiceTest.java` (constructor wiring)

---

## Executive Summary

Task #26 V1 **passes QA** on the load-bearing creator platform-fee path. `PlatformFeeService.deductAtRelease()` resolves the take rate exclusively from the DB singleton (`platform_fee_config.default_fee_bps`, seeded **1500** in `V41__platform_fee_config.sql`), posts a `PLATFORM_FEE` double-entry leg from the platform clearing wallet to the dedicated platform revenue wallet via `WalletLedgerService.post()`, and returns the net amount for the subsequent `ESCROW_RELEASE` credit. `EscrowService.release()` calls fee deduction **before** creator credit using server-derived `hold.getAmount()` — never a client-supplied value. `confirmFunded()` does **not** invoke fee deduction.

**8 unit tests** authored (6 `PlatformFeeServiceTest` + 2 `EscrowServiceReleaseTest`). **`mvn` not on PATH** in this QA environment — Meera must confirm **8/8 PASS** on scoped suite.

**Escalated to Kabir K2 (LOAD-BEARING):** concurrent release retry semantics (fee leg + release leg idempotency under race), cross-workspace release isolation on the fee path, missing-config failure mode in a live DB, and ledger traceability audit for `release-fee:{escrowHoldId}` replay.

---

## Hostile-Path Checklist (Task #26)

| # | Requirement | Result | Evidence |
|---|-------------|--------|----------|
| H-1 | Fee only at **release**, not funding | ✅ PASS | `EscrowService.confirmFunded()` posts `ESCROW_HOLD` only — no `platformFeeService` call (L206–217). `EscrowServiceReleaseTest.testConfirmFundedDoesNotDeductPlatformFee` verifies `never().deductAtRelease(...)`. |
| H-2 | **15% from DB config**, not hardcoded in Java | ✅ PASS | `resolveCreatorFeeBps()` → `configRepository.findById("default").getDefaultFeeBps()` — no Java fallback constant. `PlatformFeeServiceTest.testDeductAtReleaseReadsFeeFromDbConfig` stubs **1200 bps** and asserts fee math follows config. Seed **1500** lives in Flyway `V41__platform_fee_config.sql` L13–14 only. |
| H-3 | Platform **revenue wallet** ledger traceability | ✅ PASS | `platformWalletService.requireRevenueWallet()` → `ledgerService.post(clearing, revenue, fee, …, PLATFORM_FEE, MILESTONE, milestoneId, …)`. `PlatformFeeServiceTest.testDeductAtReleaseFeeLandsInPlatformLedger` asserts wallet ids, txn type, and milestone reference. |
| H-4 | **No direct** `Wallet.balance` mutations | ✅ PASS | `PlatformFeeService` never calls `applyBalanceDelta` or `walletRepository.save`. `testDeductAtReleaseNoDirectBalanceMutation` asserts clearing balance unchanged on entity and verifies only `ledgerService.post` is invoked. |
| H-5 | **Idempotency** on `release-fee` key | ✅ PASS (code) / ⚠️ PARTIAL (tests) | Key `"release-fee:" + escrowHoldId` passed to `WalletLedgerService.post()` (L95). Distinct from creator credit key `"release:" + hold.getId()` (EscrowService L291). Ledger enforces `uq_wtx_idem` replay. **No unit test asserts replay returns existing posting without double debit** — see L-T26-1. |

---

## Task #26 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| 15% deducted at release, not funding | ✅ PASS | Hostile H-1 |
| Fee traceable in platform ledger per txn | ✅ PASS | Hostile H-3 — `TxnReferenceType.MILESTONE`, `milestoneId`, `PLATFORM_FEE` type |
| Zero direct `Wallet.balance` mutations outside `WalletLedgerService.post()` | ✅ PASS | Hostile H-4 |
| Config read from DB (no redeploy to change %) | ✅ PASS | Hostile H-2 — singleton `platform_fee_config` |
| Unit tests (8 new/updated) | ⚠️ AUTHORED | 6 + 2; not executed here (L-T26-5) |
| Kavya QA (Kv1) | ✅ THIS DOC | |
| Kabir K2 (LOAD-BEARING) | ⏳ **NEXT** | Mandatory — do not skip |
| Meera M2 build verify | ⏳ QUEUED | After Kabir |

---

## Test Execution

| Test Class | Authored | Kavya Re-run | Notes |
|------------|----------|--------------|-------|
| `PlatformFeeServiceTest` | 6 | ❌ `mvn` unavailable | Fee math, DB config, revenue wallet, zero-fee skip, no balance mutation, `split()` |
| `EscrowServiceReleaseTest` | 2 | ❌ `mvn` unavailable | Release wires fee-before-credit; funding skips fee |
| **Total** | **8** | — | Meera gate required |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=PlatformFeeServiceTest,EscrowServiceReleaseTest
```

---

## Implementation Review

### Release money path (fee → net credit)

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
                        TxnReferenceType.MILESTONE,
                        milestone.getId(),
                        "Milestone release for contract " + milestone.getContractId(),
                        idempotencyKey,
                        null);
```

**Conservation check:** `fee + net = gross` by construction (`split()` L52). Clearing wallet debits `platformFee` then `netAmount` — total outflow equals `hold.getAmount()`. ✅

**Transaction boundary:** `EscrowService.release()` and `PlatformFeeService.deductAtRelease()` both use default `REQUIRED` propagation — fee posting and release posting share one transaction; partial failure rolls back both legs. ✅

**Already-released idempotency:** `hold.getStatus() == RELEASED` returns early (L274–275) **before** `deductAtRelease` — no double fee on status replay. ✅ Code only; no unit test (L-T26-2).

### Fee deduction service

```67:101:influora-api/src/main/java/com/influora/service/PlatformFeeService.java
  @Transactional
  public FeeDeductionResult deductAtRelease(
      Wallet clearingWallet,
      String milestoneId,
      String creatorUserId,
      BigDecimal grossAmount,
      String currency,
      String escrowHoldId) {

    int feeBps = resolveCreatorFeeBps();
    FeeSplit split = split(grossAmount, feeBps);

    WalletLedgerService.LedgerPostingResult feePosting = null;
    if (split.platformFee().signum() > 0) {
      Wallet revenueWallet = platformWalletService.requireRevenueWallet();
      feePosting =
          ledgerService.post(
              clearingWallet.getId(),
              revenueWallet.getId(),
              split.platformFee(),
              currency,
              WalletTransactionType.PLATFORM_FEE,
              TxnReferenceType.MILESTONE,
              milestoneId,
              "Platform fee ("
                  + feeBps
                  + " bps) on milestone release for creator "
                  + creatorUserId,
              "release-fee:" + escrowHoldId,
              null);
    }

    return new FeeDeductionResult(
        grossAmount, feeBps, split.platformFee(), split.netAmount(), feePosting);
  }
```

**Payee resolution:** Creator user id comes from `Collaboration.creatorId` server-side — not from request body. ✅ (existing escrow security model)

**Zero-fee path:** `signum() > 0` guard skips ledger when config is 0 bps — tested. ✅

**Missing config:** `requireConfig()` throws `PLATFORM_FEE_CONFIG_MISSING` 500 if singleton row absent — correct fail-closed; not unit-tested (L-T26-3).

### DB seed

```1:14:influora-api/src/main/resources/db/migration/V41__platform_fee_config.sql
-- Creator-side platform fee config (§1.1 CEO ruling: 15% default at escrow release).
CREATE TABLE platform_fee_config (
  id                VARCHAR(26) PRIMARY KEY,
  default_fee_bps   INT NOT NULL,
  ...
);
INSERT INTO platform_fee_config (id, default_fee_bps, min_fee_bps, max_fee_bps, allow_high_fee, updated_at)
VALUES ('default', 1500, 0, 3000, FALSE, CURRENT_TIMESTAMP);
```

**Note:** TASK_INBOX references `V40__platform_fee_config.sql`; actual Flyway file is **`V41__`** due to concurrent V40 collision (documented in entity javadoc and `V42__platform_fee_config_brand_fee_razorpay.sql` header). Not a runtime defect — doc drift only (L-T26-4).

---

## Functional / Edge Cases

| Scenario | Behavior | Verdict |
|----------|----------|---------|
| Fund escrow via webhook | `ESCROW_HOLD` only; no platform fee | ✅ tested |
| Release funded milestone | Fee deducted, then net `ESCROW_RELEASE` to creator | ✅ tested |
| Config at 1200 bps | Fee math follows DB, not 1500 | ✅ tested |
| Config at 0 bps | No ledger post; net = gross | ✅ tested |
| Hold already `RELEASED` | Early return; no second fee call | ✅ code; not tested (L-T26-2) |
| Retry same release mid-flight | `release-fee:{holdId}` + `release:{holdId}` ledger replay | ✅ code; not tested (L-T26-1) |
| Gross from `hold.getAmount()` | Server-derived, not client body | ✅ |
| Sub-rupee gross with 1500 bps | Fee rounds to ₹0.00; skipped (`signum() > 0`) | ✅ acceptable V1 |
| Per-creator / plan fee override | Not implemented — global default only | ⏳ V1 scope; spec §1A hierarchy deferred |

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| Follows TECH-STACK.md (ledger source of truth, Guardrail 1) | ✅ |
| No console.logs / debug code | ✅ |
| Error handling (`ApiException` with codes) | ✅ — `PLATFORM_FEE_CONFIG_MISSING` |
| No hardcoded fee % in Java production path | ✅ |
| Comments explain WHY | ✅ — CEO ruling, ledger-only mutation, idempotency key purpose |
| Reuses existing wallet/escrow patterns | ✅ |

---

## Security Review (Basic — escalate deep review to Kabir K2)

| Check | Result | Notes |
|-------|--------|-------|
| No hardcoded secrets | ✅ | |
| Amount server-derived from `EscrowHold` | ✅ | Guardrail 1 |
| No client-supplied fee % or gross | ✅ | |
| Distinct clearing vs revenue wallets | ✅ | Traceability |
| Idempotency keys on all ledger postings | ✅ | `release-fee:` + `release:` |
| Auth on release endpoint | ✅ | Existing `brandContext.requireMember` + role check |
| **Escalation to Kabir K2** | **REQUIRED** | LOAD-BEARING money path per CEO doc §4 / process §5 |

### Kabir K2 focus areas (from Kavya)

1. **Concurrent release race** — two threads hit `release()` before `hold.markReleased()` commits; verify `release-fee:` and `release:` keys serialize via `uq_wtx_idem` without double fee or over-credit.
2. **Cross-workspace isolation** — brand A cannot trigger fee deduction on brand B's hold via milestone id guessing (existing escrow workspace check L271–272 — red-team confirm).
3. **Missing `platform_fee_config` row** — boot-time / first-release behavior if migration failed or row deleted.
4. **Ledger audit trail** — `PLATFORM_FEE` group_id pairs queryable per milestone for finance reconciliation.

---

## Test Coverage Summary

| Area | Coverage | Notes |
|------|----------|-------|
| 15% fee math at release | ✅ | `testDeductAtReleaseAppliesFifteenPercent` |
| DB-driven bps (non-1500) | ✅ | `testDeductAtReleaseReadsFeeFromDbConfig` |
| Revenue wallet + txn type | ✅ | `testDeductAtReleaseFeeLandsInPlatformLedger` |
| No direct balance mutation | ✅ | `testDeductAtReleaseNoDirectBalanceMutation` |
| Zero bps skip | ✅ | `testDeductAtReleaseSkipsPostingWhenFeeIsZero` |
| Escrow release wiring order | ✅ | `testReleaseDeductsFeeBeforeCreatorCredit` |
| Funding does not fee | ✅ | `testConfirmFundedDoesNotDeductPlatformFee` |
| `split()` pure math | ✅ | `testSplitMath` |
| Idempotency replay on `release-fee:` | ❌ | L-T26-1 |
| RELEASED hold replay skips fee | ❌ | L-T26-2 |
| Missing config throws | ❌ | L-T26-3 |
| Integration / E2E | ❌ | Out of scope for unit gate |

---

## Findings

### Non-blocking (carry-forward)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| L-T26-1 | LOW | No unit test that a second `deductAtRelease` with the same `escrowHoldId` replays via ledger idempotency (no second `post` side effect) | Add mock verifying `ledgerService.post` called once on replay, or integration test against `WalletLedgerService` |
| L-T26-2 | LOW | No test that `EscrowService.release()` on already-`RELEASED` hold never calls `deductAtRelease` | Add `EscrowServiceReleaseTest` case |
| L-T26-3 | LOW | `PLATFORM_FEE_CONFIG_MISSING` not unit-tested | Add one negative test in `PlatformFeeServiceTest` |
| L-T26-4 | LOW | TASK_INBOX says `V40__platform_fee_config.sql`; shipped file is `V41__` | Update TASK_INBOX filename for audit hygiene |
| L-T26-5 | LOW | `mvn` unavailable in Kavya QA env — 8/8 not re-executed | Meera M2 must run scoped suite |
| L-T26-6 | LOW | Per-creator / plan fee hierarchy (`10_CREATOR_PAYMENTS_SPEC.md` §1A) not in V1 — global default only | Track for V2+; not a V1 blocker |

### Blockers

None.

---

## Routing

| Next gate | Owner | Action |
|-----------|-------|--------|
| Security (LOAD-BEARING) | **Kabir K2** | Red-team money path: concurrent release, idempotency replay, cross-workspace, missing config |
| Build confirm | **Meera M2** | `mvn test -Dtest=PlatformFeeServiceTest,EscrowServiceReleaseTest` + full build |
| Transparency endpoint | **Kavya Kv1 on #27** | Separate gate after V2 ships |
| Sign-off | **Priya** | Payments slice credit after Kabir + Meera |

---

**Kavya sign-off:** ✅ **APPROVED** for **Kabir K2 (LOAD-BEARING)** → Meera M2 → Priya. Do **not** skip Kabir on this slice.
