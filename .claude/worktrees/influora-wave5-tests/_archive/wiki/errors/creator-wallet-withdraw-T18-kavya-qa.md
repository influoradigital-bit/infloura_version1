# QA Review: Creator Wallet Withdrawal + Transaction History — Task #18 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~15:45 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (withdrawal security gate) → Ananya frontend wiring → Meera re-verify after Ananya  
**Scope:** Vikram Task #18 — `POST /wallet/withdraw` + `GET /wallet/transactions` backend  
**Reference:** `TASK_INBOX.md` Task #18; Kabir Task #10 PASS (`wiki/errors/creator-wallet-contract-T10-kabir-redteam.md`)  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/web/WalletController.java`
- `influora-api/src/main/java/com/influora/service/WalletService.java`
- `influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java` (`CreatorWithdrawRequest/Response`, `WalletTransactionRowResponse`)
- `influora-api/src/main/java/com/influora/repository/WalletTransactionRepository.java`
- `influora-api/src/test/java/com/influora/service/WalletServiceTest.java` (18 tests)
- `influora-api/src/test/java/com/influora/web/WalletControllerTest.java` (2 tests)
- `src/lib/api.ts` (contract cross-check — still fail-closed; Ananya follow-up)
- `src/pages/creator-wallet.tsx` (gap-state cross-check)

---

## Executive Summary

Creator wallet withdrawal and transaction history backend **passes QA**. Both endpoints are creator-only via `CreatorContextService.requireCreator`, resolve the wallet exclusively from `principal.getUserId()` (no path-param owner id), post withdrawals through `WalletLedgerService` double-entry to the platform clearing wallet, and enforce server-side limits (min ₹500, max ₹1,00,000, 3 withdrawals/day). Transaction history is paginated with clamped `page`/`limit` and returns an honest empty page when no wallet row exists.

**20 unit tests** authored (18 `WalletServiceTest` + 2 `WalletControllerTest`). Meera gate **20/20 PASS** per `TASK_INBOX.md` (2026-07-09 ~13:39 IST). Kavya could not re-execute `mvn` in this environment (`mvn` not on PATH); code review + Meera prior run accepted.

**Escalated to Kabir:** withdrawal rate-limit TOCTOU under concurrent requests, idempotency replay semantics, brand/creator isolation red-team on new endpoints.

Frontend `api.ts` and `creator-wallet.tsx` still fail-closed / gap-banner — **expected** until Ananya wires live paths (not a backend blocker).

---

## Task #18 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `POST /wallet/withdraw` — creator-only | ✅ PASS | `WalletController.withdraw` L76 → `creatorContext.requireCreator(principal)`; brand JWT gets `WRONG_USER_TYPE` 403 |
| Owner id from JWT only | ✅ PASS | `walletService.requestCreatorWithdrawal(principal.getUserId(), …)` L78–79; no request param for user/wallet id |
| Ledger double-entry | ✅ PASS | `ledgerService.post(walletId, clearingWalletId, …)` L183–193; test `testRequestCreatorWithdrawalPostsLedger` |
| Min ₹500 / max ₹1,00,000 | ✅ PASS (partial test) | `validateCreatorWithdrawalAmount` L235–254; below-min test L356–367; max not unit-tested (L-1) |
| 3 withdrawals/day rate limit | ✅ PASS (code only) | `countByWalletIdAndTypeAndCreatedAtAfter` L165–173 → `WITHDRAWAL_RATE_LIMIT` 429; no unit test (L-1) |
| `GET /wallet/transactions` — paginated | ✅ PASS | `getTransactionsForUser` L203–233; `PageMeta` envelope via `ApiResponse.ok(items, meta)` L94 |
| Creator wallet scoping | ✅ PASS | `findByOwnerId(userId)` only; empty page when no wallet L228–232 |
| Unit tests | ✅ PASS | `WalletServiceTest` 18 cases; `WalletControllerTest` 2 delegation cases |
| TECH-STACK.md compliance | ✅ PASS | Thin controller, `ApiException` codes, JWT auth, ledger as source of truth (Guardrail 1), no debug code |
| No fabricated contracts | ✅ PASS | `api.ts` still `NOT_IMPLEMENTED` in live — honest gap until Ananya wires |

---

## Test Execution

| Test Class | Authored | Meera Executed | Kavya Re-run | Notes |
|------------|----------|----------------|--------------|-------|
| `WalletServiceTest` | 18 | ✅ 18/18 | ❌ `mvn` unavailable | Includes 5 Task #18 cases |
| `WalletControllerTest` | 2 | ✅ 2/2 | ❌ `mvn` unavailable | Delegation + `requireCreator` verify |
| **Total** | **20** | **20/20 PASS** | — | Meera gate accepted |

**Command for re-verify after Ananya frontend wiring:**
```bash
cd influora-api && mvn test -Dtest=WalletServiceTest,WalletControllerTest
```

---

## Access Isolation Review

### Withdrawal path

```71:80:influora-api/src/main/java/com/influora/web/WalletController.java
    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<CreatorWithdrawResponse>> withdraw(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreatorWithdrawRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        creatorContext.requireCreator(principal);
        CreatorWithdrawResponse response =
                walletService.requestCreatorWithdrawal(
                        principal.getUserId(), body.amount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
```

- No `{walletId}` or `{userId}` path param — IDOR surface closed (extends Kabir Task #10 GO pattern).
- `WalletService.requestCreatorWithdrawal` loads wallet via `findByOwnerId(userId)` only L147–149.
- Test asserts `never().findByOwnerId(OTHER_CREATOR_USER_ID)` L352.

### Transaction history path

```87:95:influora-api/src/main/java/com/influora/web/WalletController.java
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<List<WalletTransactionRowResponse>>> transactions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        creatorContext.requireCreator(principal);
        var result = walletService.getTransactionsForUser(principal.getUserId(), page, limit);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
    }
```

- Pagination clamped: `safePage = max(page, 1)`, `safeLimit = min(max(limit, 1), 100)` L204–205.
- Repository query scoped to resolved `wallet.getId()` only L212–217.

---

## Functional Review

### Withdrawal happy path

1. `requireCreator` gate.
2. Amount validated (positive, min/max).
3. Wallet loaded by owner id; balance check before ledger post.
4. Daily withdrawal count checked (`WITHDRAWAL` type since UTC day start).
5. Idempotency key resolved: header if present, else `creator-withdraw:{userId}:{payoutId}` L176–179.
6. Double-entry post to platform clearing wallet; returns `payoutId` from debit leg reference id L195.

### Edge cases

| Scenario | Behavior | Verdict |
|----------|----------|---------|
| Amount below ₹500 | `MINIMUM_WITHDRAWAL` 400 | ✅ tested |
| Amount above ₹1,00,000 | `MAXIMUM_WITHDRAWAL` 400 | ✅ code; not tested (L-1) |
| Insufficient balance | `INSUFFICIENT_BALANCE` 400 | ✅ tested |
| No wallet row yet | `INSUFFICIENT_BALANCE` 400 | ✅ code; semantically odd (L-2) |
| 4th withdrawal same day | `WITHDRAWAL_RATE_LIMIT` 429 | ✅ code; not tested (L-1) |
| Creator with no wallet — transactions | Empty list + `total: 0` | ✅ tested |
| `page=0` or negative | Clamped to 1 | ✅ code |
| `limit=500` | Clamped to 100 | ✅ code |
| Brand JWT on withdraw/transactions | `WRONG_USER_TYPE` 403 | ✅ via `requireCreator` (not controller-tested) |

### API contract cross-check (backend → future frontend)

| Backend | Frontend (`api.ts`) today | Match |
|---------|---------------------------|-------|
| `POST /wallet/withdraw` body `{ amount }` | `NOT_IMPLEMENTED` in live | ⏳ Ananya wires |
| Response `{ payoutId }` | Mock `{ payoutId: 'po_new' }` | ✅ shape aligned |
| `GET /wallet/transactions?page&limit` | `NOT_IMPLEMENTED` in live | ⏳ Ananya wires |
| `WalletTransactionRowResponse.direction` | `WalletTransactionRow` **missing** `direction` | ⚠️ L-3 — Ananya must add |
| `PageMeta` on envelope | `http.request` supports meta | ✅ pattern exists |

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| Follows TECH-STACK.md | ✅ |
| No console.logs / debug code | ✅ |
| Error handling (`ApiException` with codes) | ✅ |
| Ledger-only balance mutation | ✅ — `never().save(wallet)` in deposit/withdraw tests |
| Comments explain WHY | ✅ — §7.3 limits, Kabir GO pattern refs |
| Idempotency wired | ✅ — header passthrough + ledger dedup |

---

## Security Review (Basic — escalate deep review to Kabir)

| Check | Result | Notes |
|-------|--------|-------|
| No hardcoded secrets | ✅ | |
| No client-supplied owner/wallet id | ✅ | JWT `principal.getUserId()` only |
| Creator-only mutation surface | ✅ | `requireCreator` on both endpoints |
| Balance check before debit | ✅ | L157–162 |
| Idempotency-Key header support | ✅ | Optional; auto-generated fallback |
| Rate limit present | ✅ | 3/day per wallet |
| **Escalation to Kabir** | **RESOLVED** | M-18-1/M-18-2 closed — Kabir re-sign-off **PASS** 2026-07-09 (`wiki/errors/creator-wallet-T18-kabir-redteam.md` §9) |

---

## Findings

### Non-blocking (carry-forward)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| L-1 | LOW | No unit tests for `MAXIMUM_WITHDRAWAL`, `WITHDRAWAL_RATE_LIMIT`, or no-wallet withdraw | Add 3 hostile cases in `WalletServiceTest` |
| L-2 | LOW | No wallet row throws `INSUFFICIENT_BALANCE` instead of `WALLET_NOT_FOUND` | Consider distinct code for clearer UI messaging |
| L-3 | LOW | Frontend `WalletTransactionRow` missing `direction` field present on backend DTO | Ananya adds when wiring `api.wallet.transactions` |
| L-4 | LOW | `CreatorWithdrawRequest` `@DecimalMin("1.00")` disagrees with server min ₹500 | Align to `@DecimalMin("500.00")` or drop bean min |
| L-5 | LOW | `WalletControllerTest` does not assert brand JWT rejection | Add negative test via `requireCreator` throw |

### Security carry-forward (Kabir)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| M-1 | MEDIUM | Daily withdrawal count + balance check are read-then-write without serializable isolation — concurrent requests may exceed 3/day or overdraw before ledger lock | **RESOLVED** — Kabir M-18-1/M-18-2 closure **PASS** 2026-07-09 |

### Blockers

None.

---

## Test Coverage Summary

| Area | Coverage | Notes |
|------|----------|-------|
| Happy-path withdraw + ledger delegation | ✅ | `testRequestCreatorWithdrawalPostsLedger` |
| Below-minimum rejection | ✅ | `testRequestCreatorWithdrawalRejectsBelowMinimum` |
| Insufficient balance | ✅ | `testRequestCreatorWithdrawalRejectsInsufficientBalance` |
| Transaction pagination + scoping | ✅ | 2 tests |
| Controller delegation | ✅ | 2 tests |
| Max amount / rate limit / brand rejection | Partial | Rate limit ✅ (`testRequestCreatorWithdrawalRejectsRateLimit`); max amount + brand 403 still L-1/L-5 |
| Integration / E2E | ❌ | Out of scope for unit gate |

---

## Routing

| Next gate | Owner | Action |
|-----------|-------|--------|
| Security | **Kabir** | ~~Withdrawal rate limit TOCTOU~~ **CLOSED** — M-18 re-sign-off PASS 2026-07-09 |
| Frontend wiring | **Ananya** | Wire `api.wallet.withdraw` + `transactions`; remove `withdrawLiveBlocked`; add `direction` to row type |
| Build confirm | **Meera** | Re-run scoped tests after Ananya + any Kabir fixes |
| Sign-off | **Priya** | Payments slice credit after frontend wiring |

---

**Kavya sign-off:** ✅ **APPROVED** for Kabir security gate → Ananya frontend wiring → Meera re-verify.
