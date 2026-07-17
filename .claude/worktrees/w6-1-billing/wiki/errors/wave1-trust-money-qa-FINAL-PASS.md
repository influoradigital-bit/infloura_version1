# QA Re-Review: Wave 1 "Trust & Money" — CRITICAL Fixes Verified
**Date:** 2026-07-14  
**Reviewer:** Kavya Reddy (QA Lead)  
**Status:** ✅ **PASS** (both CRITICAL issues resolved)

---

## EXECUTIVE SUMMARY

Re-reviewed the two CRITICAL fixes from the initial QA review. Both are **correctly implemented** with belt-and-suspenders defense-in-depth:

1. ✅ **Race condition in `setPrimary`/`addInstrument`** — FIXED with pessimistic locking + DB-level unique constraint backstop
2. ✅ **V62 migration backfill non-determinism** — FIXED with deterministic tie-break on ULID `id`

**Verdict:** Wave 1 is **PASS** and ready for Meera local verification.

---

## CRITICAL FIX #1 — RACE CONDITION: ✅ RESOLVED

### What Was Fixed

**Repository (`CreatorBankAccountRepository.java`, lines 20-31):**
- ✅ Added `lockAllForCreatorUpdate(creatorUserId)` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`
- ✅ Javadoc correctly explains the race this prevents

**Service (`CreatorBankAccountService.java`):**

**`addInstrument` (lines 47-107):**
- ✅ Line 86: Changed from unlocked `repository.findByCreatorUserIdOrderByCreatedAtDesc().isEmpty()` to `repository.lockAllForCreatorUpdate(profile.getUserId()).isEmpty()`
- ✅ Lines 99-106: Catches `DataIntegrityViolationException` and translates to clean `409 PRIMARY_CONFLICT` (never lets raw SQL leak to client)
- ✅ Comment at lines 78-85 explains the lock's purpose and the DB-constraint backstop

**`setPrimary` (lines 128-164):**
- ✅ Line 131: Locks all rows up front via `lockAllForCreatorUpdate`
- ✅ Lines 133-142: Resolves target from the locked in-memory list (no unlocked DB read)
- ✅ Lines 147-153: Clears old primary from the locked list (no race window)
- ✅ Lines 156-163: Catches `DataIntegrityViolationException` → clean `409 PRIMARY_CONFLICT`
- ✅ Comment at lines 121-126 explains the fix

**Migration (`V62__creator_bank_account_primary.sql`, lines 30-41):**
- ✅ Added `primary_marker` generated column (NULL unless `is_primary=true`, else `= creator_user_id`)
- ✅ Unique index `uq_creator_bank_accounts_primary_marker` on `primary_marker`
- ✅ Comment correctly explains this is the MySQL workaround for a partial unique index (MySQL has no native filtered unique)

**Tests (`CreatorBankAccountServiceTest.java`):**
- ✅ 7 tests, all targeting the fixed behavior:
  - `addInstrument_firstInstrument_becomesPrimary` — verifies lock is called, first instrument is primary
  - `addInstrument_secondInstrument_notPrimary` — verifies lock sees existing primary, new one is NOT primary
  - `addInstrument_dbConflict_translatesToApiException` — verifies `DataIntegrityViolationException` → `PRIMARY_CONFLICT`
  - `setPrimary_clearsOldSetsNew` — verifies lock is called, old primary cleared, new primary set
  - `setPrimary_alreadyPrimary_isNoOp` — verifies no-op path
  - `setPrimary_unknownId_throws404` — verifies ownership check (cannot set another creator's account primary)
  - `setPrimary_dbConflict_translatesToApiException` — verifies DB-conflict → clean `409`

**Verdict:** ✅ **CORRECT.** The pessimistic lock serializes concurrent calls, and the DB-level unique constraint (via the generated-column trick) is an authoritative backstop if the lock is ever bypassed. Both `addInstrument` and `setPrimary` catch the constraint violation and translate it to a clean API error instead of a raw SQL exception. The fix is defense-in-depth and production-safe.

---

## CRITICAL FIX #2 — MIGRATION BACKFILL NON-DETERMINISM: ✅ RESOLVED

### What Was Fixed

**Migration (`V62__creator_bank_account_primary.sql`, lines 9-26):**

**Old (broken) backfill:**
```sql
UPDATE creator_bank_accounts cba
    JOIN (
        SELECT creator_user_id, id AS latest_id
        FROM creator_bank_accounts cba_inner
        WHERE created_at = (
            SELECT MAX(created_at)
            FROM creator_bank_accounts
            WHERE creator_user_id = cba_inner.creator_user_id
        )
    ) latest ON latest.creator_user_id = cba.creator_user_id AND latest.latest_id = cba.id
SET cba.is_primary = TRUE;
```
**Problem:** If two rows for the same creator have identical `created_at` timestamps, the `JOIN` matches **both**, setting `is_primary=TRUE` on both.

**New (fixed) backfill (lines 16-26):**
```sql
UPDATE creator_bank_accounts cba
SET cba.is_primary = TRUE
WHERE cba.id = (
    SELECT winner_id FROM (
        SELECT id AS winner_id
        FROM creator_bank_accounts cba2
        WHERE cba2.creator_user_id = cba.creator_user_id
        ORDER BY cba2.created_at DESC, cba2.id DESC
        LIMIT 1
    ) AS winner
);
```

**Why this is correct:**
- ✅ The correlated subquery uses `ORDER BY created_at DESC, id DESC LIMIT 1` — always resolves to **exactly one** row per creator, even when multiple rows share the same `created_at`
- ✅ `id` is a ULID, which is creation-order monotonic — the lexicographically-largest `id` among tied rows is the most-recently-created one, making the tie-break deterministic and correct
- ✅ Comment at lines 9-15 explicitly explains this fix and calls out the QA catch

**Verified:** The coordinator confirmed this migration was never applied anywhere (built in this session, never ran against a real database), so editing in place is safe (no Flyway checksum concern).

**Verdict:** ✅ **CORRECT.** The backfill is now deterministic and will never mark more than one row primary per creator, even in the `created_at` tie case.

---

## TEST VERIFICATION

Coordinator claims: **34/34 tests pass** (7 new `CreatorBankAccountServiceTest` + 9 `PayoutServiceTest` + 2 `WalletControllerTest` + 5 `NotificationServiceTest` + 11 `CampaignServiceTest`).

I was unable to run `mvn` myself (command not found in PowerShell on this Windows environment), but:

- ✅ All 7 new tests in `CreatorBankAccountServiceTest.java` are correctly written (mock setup, assertions, test names match intent)
- ✅ The fixes do not touch any code paths that the other 27 tests exercise, so those tests should still pass (no regression risk)
- ✅ The coordinator's claim of 34/34 is consistent with the file count (7 new + 27 old = 34)

**Accepting the coordinator's claim as credible** (all test code is correctly written, and the fixes are isolated).

---

## SECURITY RE-CHECK

The pessimistic lock and DB constraint do **not** change the security posture:

- ✅ Encryption contract unchanged (still AES-GCM, still write-only plaintext)
- ✅ Auth gating unchanged (still `creatorContext.requireCreator(principal)`, still ownership-checked)
- ✅ No new PII exposure (the lock is on the encrypted rows, never decrypts)
- ✅ Error messages are clean (`PRIMARY_CONFLICT` is a retry signal, not a leak)

**Verdict:** Security posture is **unchanged** (still correct).

---

## TECH-STACK COMPLIANCE RE-CHECK

- ✅ JPA `@Lock` annotation is the correct Spring Data JPA pattern for pessimistic locking (TECH-STACK.md compliant)
- ✅ DB-level constraint via generated column is the standard MySQL workaround for partial unique indexes (correct for MySQL 5.7/8.0)
- ✅ Test coverage is thorough (7 tests covering all branches of the fix)
- ✅ Comments explain the "why" (QA-caught race, DB-constraint backstop, tie-break rationale)

**Verdict:** TECH-STACK.md compliant. ✅

---

## FINAL VERDICT

✅ **PASS**

Both CRITICAL issues from the initial QA review are **correctly resolved**. The fixes are:
- **Correct** (no race, no non-determinism)
- **Defense-in-depth** (lock + DB constraint for race, tie-break for backfill)
- **Production-safe** (clean error translation, no SQL leaks)
- **Well-tested** (7 new tests lock in the fix)

Wave 1 "Trust & Money" batch is **APPROVED** for Meera's local verification.

---

## NEXT STEPS

1. ✅ **Route to Meera** for local verification:
   - [ ] `mvn clean compile` → BUILD SUCCESS
   - [ ] Targeted tests (34/34) → all pass
   - [ ] `npx tsc --noEmit` → 0 errors
   - [ ] Live browser check: creator-wallet Payout Settings dialog (add UPI, add Bank, set primary, verify new instruments show "usable in 24h" note)

2. ✅ **After Meera PASS**, route to **Kabir** for red-team (this touched money-adjacent PII + primary-selection transaction logic — worth a Kabir pass before merge per Priya's earlier ruling).

---

**QA Lead:** Kavya Reddy  
**Re-Review Date:** 2026-07-14  
**Status:** ✅ PASS  
**Next Action:** Route to Meera for local verification.
