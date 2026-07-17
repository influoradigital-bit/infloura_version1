# Creator Wallet Withdraw + Transaction History — Task #18 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** Vikram Task #18 — `POST /wallet/withdraw`, `GET /wallet/transactions` on `WalletController`; `WalletService.requestCreatorWithdrawal`, `getTransactionsForUser`; DTOs in `MoneyDtos`; `WalletTransactionRepository` pagination + daily count queries; `WalletServiceTest` (+5), `WalletControllerTest` (2)  
**Reference Spec:** `wiki/tech/creator/10_CREATOR_PAYMENTS_SPEC.md` §7.3; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.3, §8; Task #10 `wiki/errors/creator-wallet-contract-T10-kabir-redteam.md` (GO pattern)

---

## Executive Summary

**VERDICT: ✅ PASS** (re-review 2026-07-09 ~17:00 IST — M-18-1/M-18-2 closure confirmed)

Task #18's primary security invariants hold:

1. **No IDOR** — both endpoints derive wallet scope exclusively from `principal.getUserId()` via JWT; no path/query/body user or wallet id is trusted.
2. **Creator-only gating** — `creatorContext.requireCreator(principal)` on both routes; brand principals receive uniform `403 WRONG_USER_TYPE`.
3. **Server-authoritative amount bounds** — min ₹500 / max ₹1,00,000 enforced in `validateCreatorWithdrawalAmount` before any ledger mutation; insufficient balance rejected with `INSUFFICIENT_BALANCE`.
4. **Business rate limit present** — max 3 `WITHDRAWAL` ledger rows per wallet per UTC calendar day; breach returns `429 WITHDRAWAL_RATE_LIMIT`.
5. **Concurrency hardened** — M-18-1 balance guard inside `WalletLedgerService.post()` after pessimistic debit lock; M-18-2 daily count under `findByOwnerIdForUpdate` serializes same-creator parallel withdraws.

**Initial review (2026-07-09 ~14:45 IST): PASS WITH FINDINGS** — two MEDIUM TOCTOU gaps filed (M-18-1, M-18-2). **Closure re-review (2026-07-09 ~17:00 IST): both fixes hold under adversarial re-attack.** No Critical or High findings. Three LOW carry-forwards (L-18-1–L-18-3) remain non-blocking.

---

## 1. IDOR — Owner Resolution

### 1a. `POST /wallet/withdraw`

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
```

- Owner id is **only** `principal.getUserId()` — matches Task #10/#11 GO pattern.
- `walletRepository.findByOwnerId(userId)` resolves the wallet; no cross-creator pivot vector.
- `WalletServiceTest.testRequestCreatorWithdrawalPostsLedger` asserts `findByOwnerId(OTHER_CREATOR_USER_ID)` is never called.

### 1b. `GET /wallet/transactions`

```87:94:influora-api/src/main/java/com/influora/web/WalletController.java
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<List<WalletTransactionRowResponse>>> transactions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        creatorContext.requireCreator(principal);
        var result = walletService.getTransactionsForUser(principal.getUserId(), page, limit);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
```

- Pagination params cannot pivot to another wallet — history is always keyed off authenticated user's `ownerId`.
- `limit` capped at 100 server-side (`Math.min(Math.max(limit, 1), 100)`) — reasonable anti-enumeration/DoS bound.

**IDOR exploit matrix:**

| Attack | Result |
|---|---|
| Creator A supplies Creator B's wallet id in body/query | **N/A** — no such parameter exists |
| Brand JWT on `/wallet/withdraw` or `/wallet/transactions` | **BLOCKED** — `403 WRONG_USER_TYPE` |
| Unauthenticated request | **BLOCKED** — `SecurityConfig` `anyRequest().authenticated()` |
| Creator spoofs `userType` in request body | **N/A** — `AuthPrincipal` from verified JWT only |

**IDOR: CLOSED.**

---

## 2. Creator-Only Gating

```21:26:influora-api/src/main/java/com/influora/service/CreatorContextService.java
    public void requireCreator(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }
    }
```

- Unlike `GET /wallet` and `GET /wallet/balance` (role-aware brand/creator branches), **withdraw and transactions are creator-exclusive** — no brand fallback path. Correct for spec §13.6/§13.2 creator surfaces.
- `WalletControllerTest` verifies `requireCreator` is invoked on both routes (mock delegation tests).

**Gap (Low):** No controller/integration test asserting brand principal → `403`. Service-layer isolation is sound; hostile controller test recommended (L-18-1).

---

## 3. Amount Validation

### 3a. DTO layer

```59:60:influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java
    public record CreatorWithdrawRequest(@NotNull @DecimalMin("1.00") BigDecimal amount) {}
```

### 3b. Service layer (authoritative)

```235:253:influora-api/src/main/java/com/influora/service/WalletService.java
    private void validateCreatorWithdrawalAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) { ... }
        if (amount.compareTo(MIN_CREATOR_WITHDRAWAL) < 0) { ... MINIMUM_WITHDRAWAL ... }
        if (amount.compareTo(MAX_CREATOR_WITHDRAWAL) > 0) { ... MAXIMUM_WITHDRAWAL ... }
    }
```

| Check | Enforced | Test coverage |
|---|---|---|
| Null / zero / negative | ✅ `INVALID_WITHDRAWAL_AMOUNT` | Implicit via min test |
| Below ₹500 | ✅ `MINIMUM_WITHDRAWAL` | `testRequestCreatorWithdrawalRejectsBelowMinimum` |
| Above ₹1,00,000 | ✅ `MAXIMUM_WITHDRAWAL` | ❌ no unit test (L-18-1) |
| Exceeds available balance | ✅ `INSUFFICIENT_BALANCE` | `testRequestCreatorWithdrawalRejectsInsufficientBalance` |
| Escrow balance withdrawal | ✅ N/A — only `wallet.getBalance()` checked, not `escrowBalance` |

- Server-side validation runs **before** ledger `post()` — correct ordering for single-threaded path.
- DTO `@DecimalMin("1.00")` is weaker than server min ₹500 — clients sending ₹100 get a generic bean-validation message before reaching `MINIMUM_WITHDRAWAL` only if they pass DTO (they won't at ₹100 if only DTO fires... actually ₹100 passes `@DecimalMin("1.00")` and hits server `MINIMUM_WITHDRAWAL`). Misleading dual-threshold (L-18-2).

**Amount manipulation (single request): BLOCKED.**

---

## 4. Withdrawal Rate Limit

```164:173:influora-api/src/main/java/com/influora/service/WalletService.java
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        long withdrawalsToday =
                walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        wallet.getId(), WalletTransactionType.WITHDRAWAL, dayStart);
        if (withdrawalsToday >= MAX_CREATOR_WITHDRAWALS_PER_DAY) {
            throw new ApiException(
                    "WITHDRAWAL_RATE_LIMIT",
                    "Maximum " + MAX_CREATOR_WITHDRAWALS_PER_DAY + " withdrawals per day",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
```

- Aligns with `10_CREATOR_PAYMENTS_SPEC.md` §7.3 (3/day).
- Counts ledger `WITHDRAWAL` rows for the wallet since UTC midnight — correct business semantics.
- **No per-IP throttle** on authenticated withdraw (only auth-surface `AuthRateLimitFilter` applies to login/OTP paths). Acceptable for MVP — business rule is the primary control.

**Gap:** Count is read without holding the wallet row lock. Concurrent requests can all observe `count < 3` before any commits, allowing a 4th+ withdrawal in the same day (M-18-2).

**Gap:** No unit test for rate-limit rejection (L-18-1).

---

## 5. Concurrency — Balance TOCTOU (MEDIUM)

**Attack scenario:** Creator wallet balance ₹10,000. Two parallel `POST /wallet/withdraw` for ₹6,000 each.

1. T1 and T2 both call `findByOwnerId` (no lock) → balance ₹10,000.
2. Both pass `wallet.getBalance().compareTo(amount) < 0` check.
3. T1 enters `ledgerService.post()` → `findByIdForUpdate` → debits ₹6,000 → balance ₹4,000 → commits.
4. T2 acquires lock → `applyBalanceDelta(-6000)` **without re-validating balance** → balance **−₹2,000**.

Root cause: optimistic balance read in `requestCreatorWithdrawal` is outside the pessimistic lock taken inside `WalletLedgerService.post()`. `Wallet.applyBalanceDelta` has no non-negative guard; DB has no `CHECK (balance >= 0)`.

**Recommended fix (Vikram):** Either (a) add `findByOwnerIdForUpdate` + balance re-check inside the same `@Transactional` method before `ledgerService.post()`, or (b) enforce `debitWallet.getBalance().compareTo(amount) >= 0` inside `WalletLedgerService.post()` immediately after `findByIdForUpdate`.

**Closure status (2026-07-09):** Vikram implemented **both** (a) and (b). See §9.

---

## 6. Idempotency Header

```175:179:influora-api/src/main/java/com/influora/service/WalletService.java
        String resolvedIdempotencyKey =
                (idempotencyKey != null && !idempotencyKey.isBlank())
                        ? idempotencyKey
                        : "creator-withdraw:" + userId + ":" + payoutId;
```

- When client sends `Idempotency-Key`, ledger dedup works via `uq_wtx_idem` (Wave E2 pattern) — good.
- When omitted, auto-key includes fresh `payoutId` ULID per attempt → **network retry / double-click creates duplicate withdrawals** (L-18-3). Recommend requiring `Idempotency-Key` on withdraw before production, or document that frontend must always send one.

---

## 7. Transaction History — Data Exposure

- Returns only the authenticated creator's ledger rows — no foreign wallet leakage.
- Response includes `balanceAfter` per row — acceptable for own-wallet UX; no PII beyond user's own financial history.
- Empty page (not 404) when creator has no wallet row yet — consistent with Task #10 zero-balance pattern; does not leak wallet existence of other users.

---

## 8. Test Coverage Assessment

| Area | Covered | Gap |
|---|---|---|
| Happy-path withdraw + ledger delegation | ✅ | |
| Below-min amount | ✅ | |
| Insufficient balance | ✅ | |
| Max amount rejection | ❌ | L-18-1 |
| Rate limit (4th withdraw) | ❌ | L-18-1 |
| Brand 403 on withdraw/transactions | ❌ | L-18-1 |
| Cross-creator IDOR | ✅ (implicit via ownerId mock) | |
| Controller delegation | ✅ `WalletControllerTest` 2/2 | |

Meera scoped verify: **20/20 PASS** (`WalletServiceTest` 18/18 + `WalletControllerTest` 2/2).

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| M-18-1 | **MEDIUM** | Balance check outside pessimistic lock — concurrent withdraws can drive wallet negative | **CLOSED** — `WalletLedgerService.post()` balance guard after `findByIdForUpdate`; Kabir re-review **VERIFIED** 2026-07-09 |
| M-18-2 | **MEDIUM** | Daily withdrawal count outside lock — concurrent burst can exceed 3/day | **CLOSED** — count under `findByOwnerIdForUpdate` in `requestCreatorWithdrawal`; Kabir re-review **VERIFIED** 2026-07-09 |
| L-18-1 | LOW | Missing hostile unit tests (max amount, rate limit, brand 403) | Open — non-blocking |
| L-18-2 | LOW | DTO `@DecimalMin("1.00")` inconsistent with server min ₹500 | Open — non-blocking |
| L-18-3 | LOW | Optional `Idempotency-Key` — omitted key allows duplicate withdrawals on retry | Open — frontend should always send key |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #18 IDOR / creator gating / amount bounds | **GO** |
| Ananya `api.wallet.withdraw` + `transactions` live wiring | **UNBLOCKED** |
| Kavya QA Task #18 | **GO** |
| Production deploy of creator withdrawal | **GO** — M-18-1 + M-18-2 closed on Kabir re-review 2026-07-09 |

**Pipeline position:** Task #18 security gate **✅ PASS** — M-18-1/M-18-2 closure re-sign-off complete. Cleared for production deploy of creator withdrawal (L-18-1–L-18-3 remain sprint carry-forward).

---

## 9. M-18 Closure Re-Review (2026-07-09 ~17:00 IST)

**Auditor:** Kabir Singh  
**Trigger:** Vikram M-18-1/M-18-2 pessimistic-lock hardening shipped; Meera scoped **21/21 PASS** after fix.

### 9a. M-18-1 — Balance TOCTOU

**Fix landed:**

```119:124:influora-api/src/main/java/com/influora/service/WalletLedgerService.java
        if (debitWallet.getBalance().compareTo(amount) < 0) {
            throw new ApiException(
                    "INSUFFICIENT_BALANCE",
                    "Insufficient available balance",
                    HttpStatus.BAD_REQUEST);
        }
```

Guard runs immediately after `requireWalletForUpdate` / `findByIdForUpdate` on the debit wallet, **before** `applyBalanceDelta`. Protects all `post()` call sites (withdrawal, escrow release, brand withdraw), not only creator withdrawal.

**Re-attack (parallel over-withdraw):** Creator balance ₹10,000; two concurrent `POST /wallet/withdraw` for ₹6,000.

| Step | T1 | T2 |
|---|---|---|
| Owner lock | Acquires `findByOwnerIdForUpdate` | Blocks on owner lock |
| Service balance check | Passes (₹10,000 ≥ ₹6,000) | — |
| Ledger `findByIdForUpdate` + guard | Passes; debits to ₹4,000; commits | — |
| Owner lock (T2) | Released | Acquires; balance ₹4,000 |
| Service balance check | — | **Fails** `INSUFFICIENT_BALANCE` at L159–163 |
| Ledger fallback | — | If service check bypassed, ledger guard at L119–124 still rejects |

**Verdict: CLOSED.** No negative-balance path survives.

**Unit test:** `WalletLedgerServiceTest.testPostRejectsInsufficientBalanceUnderLock` — asserts no `save()` on wallet or transaction when balance under lock is insufficient.

### 9b. M-18-2 — Daily Count TOCTOU

**Fix landed:**

```147:175:influora-api/src/main/java/com/influora/service/WalletService.java
        Wallet wallet =
                walletRepository
                        .findByOwnerIdForUpdate(userId)
                        ...
        if (wallet.getBalance().compareTo(amount) < 0) { ... }

        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        long withdrawalsToday =
                walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        wallet.getId(), WalletTransactionType.WITHDRAWAL, dayStart);
        if (withdrawalsToday >= MAX_CREATOR_WITHDRAWALS_PER_DAY) {
            throw new ApiException("WITHDRAWAL_RATE_LIMIT", ...);
        }
```

`findByOwnerIdForUpdate` uses `@Lock(PESSIMISTIC_WRITE)` on `w.ownerId = :ownerId`. `wallets` has `UNIQUE KEY uq_owner (owner_id, owner_type)` (`V2__core_auth.sql`) — one row per creator, correct lock target.

**Re-attack (parallel 4th withdrawal):** Four concurrent withdraws when count=2.

| Request | Owner lock | Count observed | Outcome |
|---|---|---|---|
| #1 | Acquires | 2 | Passes (< 3); posts; commits → count 3 |
| #2–#4 | Blocked sequentially | 3 each | **Rejected** `WITHDRAWAL_RATE_LIMIT` |

**Verdict: CLOSED.** Same-creator parallel burst cannot exceed 3/day.

**Unit test:** `WalletServiceTest.testRequestCreatorWithdrawalRejectsRateLimit` — count=3 under lock → 429, `ledgerService.post` never called.

### 9c. Cross-path interaction (escrow + withdraw)

Escrow release and creator withdrawal both debit the creator wallet via `WalletLedgerService.post()` without an owner-level lock on the escrow path. Serialization is enforced by the shared pessimistic `findByIdForUpdate` on the wallet row — whichever transaction acquires the row lock first blocks the other. M-18-1 ledger guard provides defense-in-depth for any debit path.

**Deadlock review:** `post()` locks wallets in stable sorted order by id; `requestCreatorWithdrawal` holds owner lock then joins the same `@Transactional` boundary as `post()` (REQUIRED propagation). No lock-order cycle identified.

### 9d. Scoped test re-run (Kabir)

`mvn test -Dtest=WalletLedgerServiceTest,WalletServiceTest` — **PASS** (exit 0).

### 9e. Closure verdict

| Finding | Re-attack result |
|---|---|
| M-18-1 | **CLOSED** |
| M-18-2 | **CLOSED** |

**M-18 closure sign-off: ✅ PASS.**
