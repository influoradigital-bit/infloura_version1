# QA Review: DPF-8 R2 Lifecycle Cleanup Job
**Date:** 2026-07-13  
**Reviewer:** Kavya Reddy (QA Lead)  
**Status:** ✅ **PASS** — Route to Meera for full-suite verification  
**Security:** Kabir already re-audited and PASSED (3 HIGH + 2 MED all closed)

---

## Summary

DPF-8 R2 lifecycle cleanup job passes functional QA. All guards are correctly implemented, standards are met, test coverage is comprehensive and meaningful, and all Kabir security findings are locked in by regression tests.

---

## QA Checklist Results

### 1. Scope Correctness ✅

**Finding:** Job ONLY targets superseded revisions + abandoned drafts. Can NEVER select approved/posted/verified current deliverables.

**Evidence:**
- `cleanupSupersededRevisions()` (line 103-156):
  - Query: `findByStatusInAndApprovedAtBefore(approvedStatuses, cutoff)` where `approvedStatuses = {APPROVED, POSTED, VERIFIED}`
  - Guard: only processes if `versionNumber > 1` (line 128)
  - Deletes old versions `v1..vN-1`, never the current approved version
  - Does NOT mutate entity (no `save()` call) — old keys live outside current `filesJson` (documented "known no-op" in class javadoc)

- `cleanupAbandonedDrafts()` (line 162-243):
  - Query: `findByStatusInAndUpdatedAtBefore(notApprovedStatuses, cutoff)` where `notApprovedStatuses = {PENDING, DRAFT, SUBMITTED, REVISION_REQUESTED, RESUBMITTED, REJECTED}`
  - EXCLUDES `{APPROVED, POSTED, VERIFIED}` — approved content is explicitly NOT in the query
  - 90-day cutoff on `updatedAt` (never-touched drafts)

**Verdict:** Scope is correct. No risk of deleting approved content.

---

### 2. Guard Completeness ✅

**Finding:** Both guards (dispute + escrow) are present and correct. Fresh re-check immediately before each irreversible delete (M-DPF8-2 mitigation).

**Evidence:**
- `canDelete()` method (line 250-257):
  - Checks active disputes: `disputeRepository.existsByCollaborationIdAndStatusIn(collaborationId, {OPEN, UNDER_REVIEW})`
  - Checks unreleased escrow: `escrowHoldRepository.existsByCollaborationIdAndStatusIn(collaborationId, {FUNDED, FROZEN, PENDING})`
  - Returns `false` if EITHER condition is true

- H-DPF8-1 (FROZEN/PENDING) explicitly included:
  - `UNRELEASED_ESCROW_STATUSES` constant (line 73-74): `{FUNDED, FROZEN, PENDING}`
  - Class javadoc documents why FROZEN (disputed/held money) and PENDING (payment in flight) must block deletion

- Fresh re-check before every delete:
  - `cleanupSupersededRevisions()`: `canDelete(d)` at line 133 (immediately before `deleteVersionMedia` loop)
  - `cleanupAbandonedDrafts()`: `canDelete(d)` at line 194 (immediately before `deleteAllMedia`)
  - No caching — each call re-queries the database

**Test coverage:**
- Test `frozenEscrowBlocksDeletion()` (line 82-96): verifies FROZEN escrow blocks deletion
- Test `escrowGuardChecksFullUnreleasedSet()` (line 99-121): verifies `{FUNDED, FROZEN, PENDING}` are ALL in the guard set, and `{RELEASED, REFUNDED}` are NOT
- Test `activeDisputeBlocksDeletion()` (line 210-221): verifies active dispute blocks deletion and short-circuits before escrow check

**Verdict:** Guards are complete and correctly implemented.

---

### 3. Dry-Run Default ✅

**Finding:** Dry-run flag defaults to `true`. Production must explicitly opt-in to real deletes.

**Evidence:**
- Line 76: `@Value("${influora.cleanup.dry-run:true}")`
- Spring Boot property injection with default value `true`
- When `dryRun = true`:
  - R2 deletes are logged but NOT executed (line 307-310)
  - `filesJson` is NOT cleared (line 207-210)
  - Test `dryRunNeverMutates()` (line 191-207) verifies this

**Verdict:** Dry-run default is correct.

---

### 4. Standards Compliance ✅

**Spring/Java conventions:**
- ✅ `@Component` annotation (line 59)
- ✅ `@Scheduled` cron expressions (line 103, 162):
  - `"0 0 2 * * *"` — daily at 2:00 AM (superseded revisions)
  - `"0 30 2 * * *"` — daily at 2:30 AM (abandoned drafts)
  - Standard Spring cron format
- ✅ Constructor injection (line 84-92) — no field injection
- ✅ Proper exception handling (try-catch per deliverable, line 126-148, 191-234)
- ✅ SLF4J logging with context (deliverable ID, reason, dry-run flag)
- ✅ No `any` or loose typing — all types explicit

**Code quality:**
- Clear separation of concerns (guards, key extraction, deletion)
- Comprehensive javadoc with rationale and risk documentation
- Idempotent design (re-runnable if partially fails)

**Verdict:** Standards met.

---

### 5. Test Coverage ✅

**9 tests covering all critical paths:**

1. **H-DPF8-1 guards (3 tests):**
   - `frozenEscrowBlocksDeletion()` — FROZEN escrow blocks deletion
   - `escrowGuardChecksFullUnreleasedSet()` — verifies `{FUNDED, FROZEN, PENDING}` guard set
   - `activeDisputeBlocksDeletion()` — active dispute blocks deletion

2. **H-DPF8-2 key extraction + DB-R2 ordering (3 tests):**
   - `deletesRealKeyFields()` — deletes `url` and `thumbnailUrl`, not nonexistent `objectKey`
   - `filesJsonClearedOnlyAfterSuccessfulDelete()` — `filesJson` cleared only after R2 delete succeeds
   - `filesJsonNotClearedOnFailedDelete()` — failed R2 delete leaves `filesJson` intact

3. **H-DPF8-3 status preservation (1 test):**
   - `cleanupNeverChangesStatus()` — `applyMediaCleanup()` never touches `status` or `versionNumber`

4. **Operational safeguards (2 tests):**
   - `dryRunNeverMutates()` — dry-run mode never mutates R2 or DB
   - `r2UnavailableSkipsJob()` — R2 unavailable causes graceful skip

**Test quality:**
- All tests use mockito to isolate behavior
- Assertions are precise (verify exact method calls, exact arguments, exact state changes)
- No "just doesn't throw" tests — every test asserts meaningful behavior
- `ArgumentCaptor` used to verify guard query includes FROZEN + PENDING (line 111-121)

**Verdict:** Test coverage is comprehensive and meaningful.

---

### 6. Idempotency / Safety ✅

**Finding:** Job is idempotent and safe under failure conditions.

**Evidence:**
- **Partial failure handling:** If R2 delete fails, `filesJson` is NOT cleared (line 224-230). Next run will retry.
- **Per-item transactions:** Each deliverable is processed independently (line 125-148, 191-234). One failure does not block others.
- **No job-spanning transaction:** Repository writes are per-item (line 222), not batched. A crash mid-job does not roll back already-completed deletions.
- **M-DPF8-2 residual risk documented:** Class javadoc (line 44-50) explicitly documents the TOCTOU race window (dispute/escrow change between guard check and R2 delete) and explains the mitigation (fresh re-check per item, no caching).
- **Audit trail:** Every deletion is logged BEFORE execution (line 300-320), in both dry-run and live mode (M-DPF8-1). Interrupted deletes are traceable.

**Verdict:** Idempotency and safety design is correct.

---

## Issues Found

**NONE.** No critical, high, or medium issues.

---

## Kabir Security Findings — All Locked In

Kabir's red-team findings (3 HIGH + 2 MED) are ALL implemented and covered by regression tests:

| Finding | Implementation | Test Lock |
|---------|----------------|-----------|
| **H-DPF8-1** — FROZEN/PENDING escrow must block deletion (not just FUNDED) | `UNRELEASED_ESCROW_STATUSES = {FUNDED, FROZEN, PENDING}` (line 73-74) | `frozenEscrowBlocksDeletion()`, `escrowGuardChecksFullUnreleasedSet()` |
| **H-DPF8-2** — Delete real key fields (`url`/`thumbnailUrl`), clear `filesJson` only after R2 delete succeeds | `extractKeys()` reads `url`/`thumbnailUrl` (line 281-282); `applyMediaCleanup()` called only after all deletes succeed (line 219-223) | `deletesRealKeyFields()`, `filesJsonClearedOnlyAfterSuccessfulDelete()`, `filesJsonNotClearedOnFailedDelete()` |
| **H-DPF8-3** — Cleanup must never touch `status`/`versionNumber` | `applyMediaCleanup()` only nulls `filesJson` (line 281-284) | `cleanupNeverChangesStatus()` |
| **M-DPF8-1** — Audit trail (log intent before destructive call) | Log before every `deleteObject()` (line 307-320), in dry-run + live mode | Verified by code inspection + dry-run test |
| **M-DPF8-2** — TOCTOU mitigation (fresh re-check before delete) | `canDelete()` called immediately before every destructive call (line 133, 194); residual risk documented | Verified by code inspection; residual risk documented in class javadoc |

---

## Verdict: ✅ PASS

**Route to:** Meera for full-suite verification (`mvn clean verify`).

**Note:** DeliverableCleanupJobTest is part of the test suite. If Meera already ran the full suite for DPF-3/4 (e.g., run 909/0F/1E which includes all DPF tests), this test has already been executed and passed. Meera should confirm the suite includes DPF-8 tests.

**No blocking issues.** Code is production-ready after Meera's build verification.

---

**Kavya Reddy, QA Lead**  
2026-07-13
