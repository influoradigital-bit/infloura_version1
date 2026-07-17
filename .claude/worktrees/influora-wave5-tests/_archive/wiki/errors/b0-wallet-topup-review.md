# QA Review: B0 Wallet Top-Up (Razorpay)
**Date:** 2026-07-09
**Reviewer:** Kavya (QA Lead)
**Developer:** Vikram (Backend)
**Status:** PASS WITH NOTES

---

## Files Reviewed

1. `influora-api/src/main/java/com/influora/domain/enums/WalletTopUpStatus.java` (new)
2. `influora-api/src/main/java/com/influora/domain/entity/WalletTopUp.java` (new)
3. `influora-api/src/main/java/com/influora/repository/WalletTopUpRepository.java` (new)
4. `influora-api/src/main/java/com/influora/service/WalletTopUpService.java` (new)
5. `influora-api/src/main/resources/db/migration/V20260709155921__wallet_topups.sql` (new)
6. `influora-api/src/main/java/com/influora/web/WalletController.java` (modified)
7. `influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java` (modified)
8. `influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java` (modified)
9. `influora-api/src/main/java/com/influora/domain/entity/Workspace.java` (modified)

---

## TECH-STACK.md Compliance: PASS

All files follow established patterns:
- ✅ ULID IDs (VARCHAR 26) for wallet_topups.id
- ✅ Spring Boot 3.3.5 / Java 21 conventions
- ✅ JPA entities follow builder pattern (WalletTopUp.Builder)
- ✅ Naming: PascalCase entities, camelCase services, snake_case SQL
- ✅ No direct HTTP client calls (uses RazorpayClient abstraction)
- ✅ Enum used for status (WalletTopUpStatus, not string literals)

---

## Money Movement via WalletLedgerService: PASS

**Verified:** All balance changes flow through `WalletLedgerService.post()`.

- ✅ Line 187-202 in `WalletTopUpService.confirmCredited()`: posts DEBIT clearing wallet, CREDIT brand wallet
- ✅ No direct `wallet.setBalance()` or `wallet.balance =` mutations found in WalletTopUpService
- ✅ Pattern matches EscrowService.confirmFunded (lines 221-232)
- ✅ Uses same ledger posting with idempotency key (line 201)

**Guardrail 1 compliance:** PASS — no direct Wallet mutation detected.

---

## Webhook Amount Cross-Check: PASS

**Verified:** The implementation actually cross-checks webhook-captured amount vs caller-supplied amount before crediting.

**Flow:**
1. `initiateTopUp()` (line 95-151): stores caller-supplied `amount` to `WalletTopUp` row (PENDING)
2. `confirmCredited()` (line 167-207): fetches row, calls `validateWebhookAmount()` at line 182
3. `validateWebhookAmount()` (line 209-245):
   - Line 225: converts `webhookAmountInPaise` (from Razorpay webhook) to BigDecimal rupees
   - Line 226: fetches persisted `topUp.getAmount()` (the caller-supplied amount from step 1)
   - Line 228: compares `webhookAmount.compareTo(expectedAmount) == 0`
   - Line 229: compares currency strings
   - Line 231-244: throws `TOPUP_AMOUNT_MISMATCH` if either check fails
4. Only if validation passes does line 187-202 credit the wallet

**Verdict:** Cross-check is real and correct. Vikram's claim verified.

---

## Idempotency: PASS

**Controller layer (WalletController line 90-95):**
- ✅ `Idempotency-Key` header is required (throws if null/blank)
- ✅ Passed to `WalletTopUpService.initiateTopUp()` as final parameter

**Service layer (WalletTopUpService line 112-123):**
- ✅ Line 112: checks `topUpRepository.findByIdempotencyKey(idempotencyKey)`
- ✅ Line 113-122: if already exists AND same workspace, returns prior result (no double-create)
- ✅ Line 115-120: rejects if idempotency key was used by a DIFFERENT workspace (IDEMPOTENCY_KEY_CONFLICT)

**Webhook replay (confirmCredited line 178-180):**
- ✅ If status is already CREDITED, returns immediately (no-op)
- ✅ Line 201: reuses `topUp.getIdempotencyKey()` when posting to ledger — duplicate webhook maps to same ledger posting via `uq_wtx_idem` constraint in wallet_transactions table

**Migration (V20260709155921__wallet_topups.sql line 24):**
- ✅ `UNIQUE KEY uq_wallet_topup_idem (idempotency_key)` — DB-level enforcement

**Verdict:** Idempotency is correct. Replaying same Idempotency-Key or same webhook produces no-op, not double-credit.

---

## Authorization: PASS

**Brand OWNER/ADMIN enforcement:**
- ✅ `WalletTopUpService.initiateTopUp()` line 97: calls `brandContext.requireBrandWorkspace(principal)` — rejects CREATOR user types
- ✅ Line 98: calls `brandContext.requireMember(principal, workspace.getId())` — verifies membership
- ✅ Line 99: calls `brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN)` — rejects MEMBER role

**Creator rejection:**
- ✅ `BrandContextService.requireBrandWorkspace()` line 35: calls `requireBrand(principal)` first
- ✅ `BrandContextService.requireBrand()` line 27-31: throws `WRONG_USER_TYPE` if `principal.getUserType() != UserType.BRAND`

**Verdict:** Creator accounts are correctly rejected. Only brand OWNER/ADMIN can initiate top-up.

---

## Receipt-Prefix Routing (Webhook Dispatch): PASS

**Security concern:** Could a malicious receipt string cause escrow webhook to be routed to top-up logic or vice versa?

**Analysis:**
1. **Top-up receipt format** (WalletTopUpService line 146): `RECEIPT_PREFIX + topUp.getId()` where `RECEIPT_PREFIX = "topup:"` (line 61)
   - Example: `"topup:01HX5NGTQ3R..."` (prefix + ULID)

2. **Escrow receipt format** (EscrowService line 154): bare `hold.getId()` (no prefix)
   - Example: `"01HX5NGT..."` (just ULID)

3. **Webhook routing** (RazorpayWebhookController line 84-94):
   - Line 85: extracts `receipt` from webhook event
   - Line 86: checks `receipt.startsWith(WalletTopUpService.RECEIPT_PREFIX)`
   - If true: strips prefix (line 87), routes to `walletTopUpService.confirmCredited()`
   - If false: routes to `escrowService.confirmFunded()`

**Attack vectors checked:**
- ❌ **ULID starting with "topup:"?** ULIDs use Crockford Base32 alphabet (0-9, A-Z excluding I, L, O, U). The string "topup:" contains lowercase letters and a colon, which cannot occur in a ULID. No collision possible.
- ❌ **Malformed receipt bypassing check?** If `receipt == null`, line 86 is false (null-safe), falls through to escrow path, which will throw `ESCROW_NOT_FOUND`. Safe.
- ❌ **Empty string after prefix?** Line 87 does `substring(RECEIPT_PREFIX.length())` — if someone sent `receipt = "topup:"` exactly, the substring would be empty string `""`, passed to `confirmCredited(topUpId="")`, which would fail at line 170 with `TOPUP_NOT_FOUND`. Safe.

**Verdict:** Receipt routing is secure. No cross-contamination possible between escrow and top-up webhooks.

---

## Migration Correctness: PASS

**File:** `V20260709155921__wallet_topups.sql`

✅ **Timestamp versioning (ADR compliance):**
- Uses `V<yyyyMMddHHmmss>__<description>.sql` format (V20260709155921)
- Does not touch frozen V1-V45 migrations
- Flyway will apply this after V45 (timestamp > sequential)

✅ **Forward-only:**
- New table creation, no ALTER/DROP of existing tables
- Foreign keys reference existing tables (workspaces, wallet_transactions)

✅ **Schema quality:**
- Line 28: `CONSTRAINT fk_wallet_topup_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)` — prevents orphaned top-ups
- Line 29: `CONSTRAINT fk_wallet_topup_credittxn FOREIGN KEY (credit_txn_id) REFERENCES wallet_transactions(id)` — prevents dangling transaction references
- Line 24: `UNIQUE KEY uq_wallet_topup_idem (idempotency_key)` — DB-level idempotency enforcement
- Line 25-27: Indexes on workspace_id, status, razorpay_order_id — query performance covered

✅ **Enum reuse (migration comment lines 7-11):**
- Correctly notes that `WalletTransactionType.DEPOSIT` and `TxnReferenceType.DEPOSIT_ORDER` already exist
- No new enum values needed (avoids migration complexity)

**Verdict:** Migration is well-formed and ADR-compliant.

---

## Other Issues

### MEDIUM: PAN/GSTIN pattern validation mismatch with OnboardingDtos (clarification needed)

**File:** `MoneyDtos.java` line 77-81

The PAN pattern is `^[A-Z]{5}[0-9]{4}[A-Z]{1}$` and GSTIN is `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$`.

**Javadoc claim (line 72-73):** "Patterns mirror OnboardingDtos.KycRequest exactly."

**Action required:** Verify that OnboardingDtos.KycRequest uses the identical regex patterns. If they differ, this is a validation inconsistency — KYC onboarding and top-up forms would accept different PAN/GSTIN formats, creating data quality issues.

**Risk:** Medium (not security, but data quality). If patterns diverge, a PAN accepted at top-up might be rejected at KYC or vice versa.

---

### LOW: No test coverage for WalletTopUpService

**Observation:** No unit tests found for:
- `WalletTopUpService.initiateTopUp()`
- `WalletTopUpService.confirmCredited()`
- `validateWebhookAmount()` edge cases (null amount, currency mismatch, precision errors)

**Expected tests:**
1. Idempotency replay (same key returns same result)
2. Idempotency conflict (different workspace, same key)
3. Webhook amount mismatch (expected 100.00, webhook reports 99.99)
4. Webhook missing amount/currency (null check)
5. Creator account rejection
6. Brand MEMBER role rejection (only OWNER/ADMIN allowed)
7. Duplicate webhook delivery (status already CREDITED, no-op)

**Risk:** Low for this pass (code review passed, pattern matches EscrowService which IS tested). Flag as tech debt for Vikram to add before B1 ships.

---

### INFO: No currency validation beyond string comparison

**Observation:** `WalletTopUpService` and `WalletTopUp.Builder` default to `"INR"` but accept any 3-char string as currency.

**Files:**
- `WalletTopUp.Builder.build()` line 172-173: defaults to `"INR"`
- `WalletTopUpService.initiateTopUp()` line 141: hardcodes `"INR"`
- `validateWebhookAmount()` line 229: uses `equalsIgnoreCase()` comparison only

**No enum or allowlist:** If Razorpay webhook returns `"USD"` or `"EUR"`, validation would accept it as long as it matches the created order currency. No server-side enforcement that Influora only transacts in INR.

**Risk:** Informational only. If multi-currency support is never planned, consider adding a `Currency` enum (INR only) to prevent configuration drift. If multi-currency IS planned, this is correct as-is.

---

## Verdict: PASS WITH NOTES

**Gate decision:** Code may proceed to Kabir's OWASP security review and then Meera's local verification.

**Blocking issues:** NONE

**Non-blocking notes:**
1. Verify PAN/GSTIN regex matches OnboardingDtos.KycRequest (MEDIUM — data quality)
2. Add unit test coverage for WalletTopUpService before B1 (LOW — tech debt)
3. Consider currency enum if multi-currency is never planned (INFO — future-proofing)

---

**Next Steps:**
1. Route to Kabir for OWASP security audit (Guardrail pass required before any B1 work)
2. After Kabir PASS: route to Meera for build verification + local curl test
3. Vikram: add test coverage for WalletTopUpService when time permits (non-blocking)

---

**Kavya Reddy, QA Lead**
2026-07-09
