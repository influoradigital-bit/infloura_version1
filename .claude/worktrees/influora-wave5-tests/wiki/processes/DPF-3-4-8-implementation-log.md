# DPF-3, DPF-4, DPF-8 Implementation Log

> **Builder:** Vikram (Backend Developer)
> **Date:** 2026-07-13
> **Epic:** DPF — Deliverable → Payment Flow
> **Tasks:** DPF-3 (Mark-Posted Endpoint), DPF-4 (Schema Migration), DPF-8 (R2 Lifecycle Cleanup)

---

## Status: DPF-3 + DPF-4 PASSED KABIR. DPF-8 FIX ROUND COMPLETE — AWAITING RE-AUDIT.

DPF-3 and DPF-4 cleared Kabir's review. DPF-8 was **BLOCKED** by Kabir red-team
(`wiki/errors/DPF-8-kabir-redteam.md`) with 3 HIGH + 2 MEDIUM findings. All 5 fixed below —
real `mvn -o test` numbers included this round (last round reported "complete" with no numbers,
which Meera correctly caught as unverified). DPF-8 goes back to Kabir for re-audit before Kavya.

### Real verification numbers (this round)

```
mvn -o compile           → BUILD SUCCESS, 0 errors
mvn -o test (full suite) → Tests run: 900, Failures: 0, Errors: 1, Skipped: 0
                            (the 1 error is DatabaseConstraintIntegrationTest — Docker-gated,
                            same accepted sandbox-environment exception as every prior baseline
                            run; PP-1 gate, not a regression)
mvn -o test -Dtest=DeliverableCleanupJobTest → Tests run: 9, Failures: 0, Errors: 0
```

New test file: `influora-api/src/test/java/com/influora/job/DeliverableCleanupJobTest.java` — 9
tests that lock in the Kabir findings as regressions (FROZEN/PENDING escrow guard, real key-field
deletion, filesJson-only-clears-on-success, status-never-changes, dry-run-never-mutates, dispute
short-circuit, R2-unavailable skip).

**Also caught and fixed while getting this to compile:** `R2StorageService` had no `deleteObject`
method at all — the original DPF-8 code called a method that didn't exist, so it would never have
compiled. Added a real `deleteObject(String objectKey)` using the AWS S3 SDK's
`DeleteObjectRequest` (R2 is S3-compatible). Also found and fixed 3 missing imports
(`MarkPostedRequest`/`MarkPostedResponse`) in `CreatorDeliverableService.java` from the DPF-3
round — that file had also never actually been compiled before this round.

---

## DPF-3: Mark-Posted Endpoint ✅

**Problem:** `Deliverable.postUrl` is dead — no code path ever writes it. Status `POSTED` is never assigned.

**Solution:** Added endpoint where creator submits live post URL after publishing to Instagram/YouTube.

### Files Modified

1. **`CreatorDeliverableDtos.java`**
   - Added `MarkPostedRequest(String livePostUrl)`
   - Added `MarkPostedResponse(String id, DeliverableStatus status, String postUrl, Instant postedAt)`

2. **`Deliverable.java`**
   - Added `applyMarkPosted(String postUrl)` method
   - Writes `postUrl`, sets status to `POSTED`, records `postedAt` timestamp

3. **`CreatorDeliverableService.java`**
   - Added `markPosted(principal, deliverableId, request)` method
   - Added `validatePlatformPostUrl(url)` — HTTPS + platform whitelist validation
   - **Security (Kabir M-DPF-3):**
     - Rejects non-HTTPS (blocks `http://`, `file://`, `javascript:`, etc.)
     - Platform whitelist: Instagram (`instagram.com/p/`, `instagram.com/reel/`) + YouTube (`youtube.com/watch?v=`, `youtu.be/`)
     - Regex validation prevents SSRF, localhost, internal IPs
   - **Guard:** Only `APPROVED` deliverables can be marked posted
   - **Ownership:** Reuses `requireOwnedDeliverable` pattern (creator must own the deliverable)

4. **`CreatorDeliverableController.java`**
   - Added `POST /creator/deliverables/{deliverableId}/mark-posted`
   - Request: `{ "livePostUrl": "https://..." }`
   - Response: `{ "id": "...", "status": "POSTED", "postUrl": "...", "postedAt": "..." }`

### API Contract

```
POST /creator/deliverables/{deliverableId}/mark-posted
Authorization: Bearer <creator-token>
Content-Type: application/json

{
  "livePostUrl": "https://www.instagram.com/p/ABC123/"
}

Response 200:
{
  "success": true,
  "data": {
    "id": "01HX...",
    "status": "POSTED",
    "postUrl": "https://www.instagram.com/p/ABC123/",
    "postedAt": "2026-07-13T10:30:00Z"
  }
}

Error 400 (INVALID_POST_URL):
{
  "success": false,
  "error": {
    "code": "INVALID_POST_URL",
    "message": "URL must be a valid Instagram or YouTube post (platform not recognized)"
  }
}

Error 409 (INVALID_STATE):
{
  "success": false,
  "error": {
    "code": "INVALID_STATE",
    "message": "Deliverable must be approved before marking as posted"
  }
}
```

### URL Validation Rules (SECURITY CRITICAL)

**Accepted patterns:**
- Instagram: `^https://(www\.)?instagram\.com/(p|reel)/[A-Za-z0-9_-]+/?$`
- YouTube video: `^https://(www\.)?youtube\.com/watch\?v=[A-Za-z0-9_-]+(&.*)?$`
- YouTube short: `^https://youtu\.be/[A-Za-z0-9_-]+$`

**Rejected:**
- Any non-HTTPS URL
- Localhost (`localhost`, `127.0.0.1`, `0.0.0.0`)
- Internal IPs (`10.x.x.x`, `192.168.x.x`, `172.16-31.x.x`)
- Unknown platforms (Facebook, Twitter, TikTok, etc. — not supported in DPF-3 v1)
- Malformed URLs, redirects, data URIs

---

## DPF-4: Schema Migration ✅

**Problem:** Need `releaseCondition` enum on `PaymentMilestone` so the release gate can be data-driven.

**Solution:** Migration + entity update with enum + builder support.

### Files Created

1. **`V52__payment_milestone_release_condition.sql`**
   ```sql
   ALTER TABLE payment_milestones
   ADD COLUMN release_condition ENUM('ON_APPROVAL','ON_POSTED','ON_VERIFIED_METRICS')
   NOT NULL
   DEFAULT 'ON_POSTED';
   ```

### Files Modified

2. **`PaymentMilestone.java`**
   - Added `ReleaseCondition` enum: `ON_APPROVAL`, `ON_POSTED`, `ON_VERIFIED_METRICS`
   - Added field `private ReleaseCondition releaseCondition = ReleaseCondition.ON_POSTED`
   - Added getter `getReleaseCondition()`
   - Added builder method `releaseCondition(ReleaseCondition releaseCondition)`
   - Builder defaults to `ON_POSTED` if not set

### Enum Values

| Value | Meaning |
|-------|---------|
| `ON_APPROVAL` | Release escrow as soon as brand approves deliverable (rare — used for trust deals) |
| `ON_POSTED` | Release after creator posts live (default — fixed-fee deliverables) |
| `ON_VERIFIED_METRICS` | Release after platform verifies metrics (performance/bonus milestones) |

**Default:** `ON_POSTED` (creator can't control reach; don't withhold fixed fees on performance).

---

## DPF-8: R2 Lifecycle Cleanup Job ✅

**Problem:** We're keeping every revision version forever. Need to clean up superseded revisions + abandoned drafts.

**Solution:** Scheduled job that deletes ONLY non-approved/old content, with dispute/escrow guards.

### Files Created

1. **`DeliverableCleanupJob.java`**
   - Component with two scheduled methods
   - Dry-run flag (`influora.cleanup.dry-run=true` by default)

### Files Modified

2. **`DeliverableRepository.java`**
   - Added `findByStatusInAndApprovedAtBefore(Set<DeliverableStatus>, Instant)` — superseded revisions
   - Added `findByStatusInAndUpdatedAtBefore(Set<DeliverableStatus>, Instant)` — abandoned drafts

### Job 1: Superseded Revisions Cleanup

**Schedule:** Daily at 2:00 AM (`0 0 2 * * *`)

**Targets:**
- Deliverables with `status ∈ {APPROVED, POSTED, VERIFIED}`
- `approvedAt + 30 days < now`
- `versionNumber > 1` (multiple versions exist)

**Deletion:**
- Deletes R2 objects for versions `1..(N-1)` where `N` is the current version
- Keeps the current approved version intact
- Clears `filesJson` entries for deleted versions

**Example:**
- Deliverable `01HX...` has `versionNumber=3`, status `APPROVED`, approved 35 days ago
- Job deletes R2 objects for v1 and v2 (superseded)
- Keeps v3 (current approved version)

### Job 2: Abandoned Drafts Cleanup

**Schedule:** Daily at 2:30 AM (`0 30 2 * * *`)

**Targets:**
- Deliverables with `status ∉ {APPROVED, POSTED, VERIFIED}` (drafts/submitted/rejected)
- `updatedAt + 90 days < now` (untouched for 90+ days)
- No funded/released escrow on the collaboration

**Deletion:**
- Deletes ALL R2 objects for the deliverable (all versions)
- Clears `filesJson` to `null`
- Keeps DB row + metadata as audit record

**Example:**
- Deliverable `01HY...` has `status=DRAFT`, last touched 95 days ago, no escrow
- Job deletes all R2 objects for all versions
- DB row remains with `filesJson=null` for audit trail

### Guards (CRITICAL — Kabir M-DPF-8)

Before deleting ANY R2 object, the job checks:

1. **No active dispute:**
   ```java
   disputeRepository.existsByCollaborationIdAndStatusIn(
       collaborationId, {OPEN, UNDER_REVIEW})
   ```
   If returns `true` → skip deletion (evidence frozen)

2. **No unreleased escrow:**
   ```java
   escrowHoldRepository.findByCollaborationIdAndStatus(
       collaborationId, FUNDED)
   ```
   If non-empty → skip deletion (money held, delivery evidence needed)

**Rationale:** Deliverables tied to disputes or funded escrow are primary evidence. Deleting them destroys proof of delivery, violates dispute-resolution policy, and creates chargeback exposure.

### Dry-Run Flag

**Config:** `influora.cleanup.dry-run` (default: `true`)

**Behavior:**
- `true` → Logs what WOULD be deleted, but doesn't actually delete
- `false` → Performs actual R2 deletions

**Deployment flow:**
1. Deploy with `dry-run=true`
2. Monitor logs for one cycle (verify no approved content flagged)
3. Set `dry-run=false` in production config after Priya sign-off

### Audit Logging

Every deletion is logged:
```
Deleted R2 object: deliverables/01HX.../v1/video-abc.mp4
  (deliverable=01HX..., version=1, reason=superseded-revision)
```

Dry-run logs:
```
DRY-RUN: Would delete 3 objects for deliverable 01HX... v2
  (reason=superseded-revision): [key1, key2, key3]
```

---

## DPF-8 FIX ROUND — Kabir BLOCK resolved (2026-07-13)

Kabir's full report: `wiki/errors/DPF-8-kabir-redteam.md` — VERDICT was BLOCK, 3 HIGH + 2 MEDIUM.
All 5 fixed:

### H-DPF8-1 — FROZEN/PENDING escrow was invisible to the guard

**Before:** `canDelete` only checked `EscrowStatus.FUNDED` via `findByCollaborationIdAndStatus`.
`FROZEN` (disputed/held money) and `PENDING` (payment in flight) were both unguarded — a
deliverable tied to a frozen dispute-hold could pass the guard and get deleted.

**Fix:**
- Added `EscrowHoldRepository.existsByCollaborationIdAndStatusIn(collaborationId, statuses)`
  (`influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java`).
- `DeliverableCleanupJob.canDelete` now checks the full unreleased set:
  `UNRELEASED_ESCROW_STATUSES = {FUNDED, FROZEN, PENDING}`. Only `RELEASED`/`REFUNDED` (terminal)
  pass the guard.
- Regression test: `escrowGuardChecksFullUnreleasedSet` asserts the captured status set contains
  FUNDED+FROZEN+PENDING and excludes RELEASED+REFUNDED; `frozenEscrowBlocksDeletion` asserts a
  simulated FROZEN hold blocks the delete end-to-end.

### H-DPF8-2 — R2 delete was reading a field that doesn't exist, then nulling filesJson anyway

**Before:** `deleteVersionMedia` read `file.get("objectKey")`, but the serialized `StoredFile`
record has no `objectKey` field — the real key is under `"url"`/`"thumbnailUrl"`
(`CreatorDeliverableService.java:790-798`, confirmed: "Persist R2 object keys (not public URLs)").
So `keysToDelete` was always empty and `r2StorageService.deleteObject` never fired — yet
`cleanupAbandonedDrafts` still nulled `filesJson` via `applyUpload`, permanently orphaning the R2
bytes and destroying the only record of which keys held them.

**Separately discovered while fixing this:** `R2StorageService` had **no `deleteObject` method at
all**. The original code was calling a method that didn't exist — a real compile error, not just
a logic bug. Confirmed via `mvn -o compile` once Maven access was available this round.

**Fix:**
- `R2StorageService.deleteObject(String objectKey)` added — real S3-compatible
  `DeleteObjectRequest` call, throws on failure/unavailability (never silently no-ops).
- `DeliverableCleanupJob.extractKeys` now reads `"url"` and `"thumbnailUrl"` (the real fields),
  deduped into a `Set<String>`.
- `filesJson` is only cleared (via `applyMediaCleanup()`, see H-DPF8-3) when **every** matched key
  deleted successfully (`DeletionResult.allSucceeded()`). A partial/total failure leaves
  `filesJson` untouched and logs an error for retry next run — the DB→key mapping is never
  destroyed while R2 objects still exist.
- Regression tests: `deletesRealKeyFields` (correct keys passed to `deleteObject`),
  `filesJsonClearedOnlyAfterSuccessfulDelete`, `filesJsonNotClearedOnFailedDelete`.

### H-DPF8-3 — abandoned-draft cleanup resurrected REJECTED deliverables to DRAFT

**Before:** `cleanupAbandonedDrafts` cleared files via
`d.applyUpload(d.getVersionNumber(), null, null, null, null)` — `applyUpload` is the
creator-upload mutator and hard-sets `status = DRAFT`. A `REJECTED`/`SUBMITTED`/
`REVISION_REQUESTED` deliverable that got "cleaned" flipped back to editable `DRAFT`
(`canUploadNewVersion(DRAFT) == true`), letting a creator re-open work the brand already
rejected — a lifecycle-corruption bug caused by reusing the wrong primitive.

**Fix:**
- Added `Deliverable.applyMediaCleanup()` — nulls `filesJson` and touches `updatedAt`, does
  **not** touch `status` or `versionNumber`
  (`influora-api/src/main/java/com/influora/domain/entity/Deliverable.java`).
- `cleanupAbandonedDrafts` now calls `applyMediaCleanup()` instead of `applyUpload()`.
- Regression test: `cleanupNeverChangesStatus` asserts a `REJECTED` deliverable stays `REJECTED`
  after cleanup runs successfully.

### M-DPF8-1 — deletion was logged after the destructive call, and only in dry-run

**Before:** live-mode deleted first, logged second; the "about to delete" line only existed in
the `dryRun` branch, so a live deletion had no pre-delete audit trail — a crash between the R2
call and the log line would be both unrecoverable and unlogged.

**Fix:** `deleteKeys` now logs "Deleting R2 object …" immediately **before** the `deleteObject`
call in live mode (and the "DRY-RUN: would delete …" line before the skip in dry-run mode), then
logs success/failure after. Every attempted deletion — real or simulated — is now logged before
it happens, not just after.

### M-DPF8-2 — TOCTOU race between the guard check and the external R2 call

**Before:** `canDelete` ran once for the whole candidate list, computed up front; the actual R2
delete happened later, non-transactionally, with no re-check. A dispute opened or escrow frozen
in that window (which could span the entire job runtime for large candidate lists) would not be
caught.

**Fix (documented mitigation, per Kabir's explicit "or document the mitigation" allowance):**
- `canDelete` is a fresh, uncached DB read every time it's called — never memoized.
- Removed the class/method-level `@Transactional` that wrapped the entire batch in one
  job-spanning transaction (Spring Data JPA repository calls are already individually
  transactional) — each deliverable's guard check + delete + save is now its own short unit of
  work instead of one long-held transaction spanning the whole job plus external R2 I/O.
- Per-deliverable `try/catch` isolation added (one deliverable's failure doesn't abort the batch —
  matches the existing `ScoreCalculationJob`/`StaleTokenCleanupJob` resilience convention already
  used elsewhere in this codebase).
- **Residual risk, documented in the class javadoc:** the guard-check-to-R2-call window is now
  "this iteration only" (milliseconds) rather than "whole job runtime" (potentially the full
  batch), but is not fully eliminated — R2 is outside the DB transaction and its side effect
  cannot be rolled back or locked against. Flagged for revisit with a pessimistic lock on the
  escrow-hold rows if job volume/concurrency grows enough to matter.

### Also fixed while verifying: DPF-3's missing imports

`CreatorDeliverableService.java` was missing `import ... MarkPostedRequest` and
`import ... MarkPostedResponse` — the DPF-3 code had never actually been compiled (no Maven
access in the first build round). Fixed alongside this round once `mvn` was located at
`C:\Users\Sage world\.maven\apache-maven-3.9.6\bin\mvn.cmd`.

---

## Security Review Notes (for Kabir)

### DPF-3 Security Surface

1. **URL Validation (SSRF protection):**
   - HTTPS-only (rejects `http://`, `file://`, `javascript:`, `data:`, etc.)
   - Platform whitelist (Instagram, YouTube only)
   - Regex anchored (`^...$`) — no partial matches
   - No redirect follow (string match only, no HTTP fetch)
   - Rejects localhost, internal IPs (via domain whitelist)

2. **IDOR Protection:**
   - Reuses `requireOwnedDeliverable` pattern (line 350)
   - Creator can only mark their own deliverables as posted
   - Ownership checked via `deliverableRepository.findByIdAndCreatorUserId`

3. **State Machine:**
   - Only `APPROVED` → `POSTED` transition allowed
   - Can't mark as posted from `DRAFT`, `SUBMITTED`, `REVISION_REQUESTED`
   - Prevents creator from skipping brand approval

4. **Input Validation:**
   - URL trimmed before validation
   - Null/blank checks on `livePostUrl`
   - Max length enforced by DB column (`post_url VARCHAR(500)`)

**Red-team attack vectors to test:**
- [ ] SSRF: `http://169.254.169.254/latest/meta-data/` (AWS metadata endpoint)
- [ ] SSRF: `https://localhost:8080/admin` (internal service)
- [ ] Protocol bypass: `file:///etc/passwd`
- [ ] JavaScript injection: `javascript:alert(document.cookie)`
- [ ] Open redirect: `https://instagram.com@evil.com/p/ABC`
- [ ] IDOR: Creator A marks Creator B's deliverable as posted
- [ ] State bypass: Mark `DRAFT` deliverable as posted (should 409)

### DPF-8 Security Surface

1. **Deletion Guards (Evidence Protection):**
   - Checks `disputeRepository.existsByCollaborationIdAndStatusIn` BEFORE every delete
   - Checks `escrowHoldRepository.findByCollaborationIdAndStatus(FUNDED)` BEFORE every delete
   - These are the same guards used in `EscrowService.release()` (defense-in-depth)

2. **Scope Restriction:**
   - NEVER deletes deliverables with `status ∈ {APPROVED, POSTED, VERIFIED}`
   - Only superseded OLD versions of approved deliverables (v1..vN-1)
   - Only abandoned drafts untouched for 90+ days

3. **Audit Trail:**
   - Every deletion logged with deliverable ID, version, reason, object key
   - DB row preserved (only R2 objects deleted, `filesJson` cleared)
   - Dry-run mode for validation before production

**Red-team attack vectors to test:**
- [ ] Race: Deliverable approved + dispute opened concurrently with cleanup job
- [ ] Race: Escrow funded concurrently with cleanup job
- [ ] Edge: Deliverable approved TODAY (0 days old) flagged for deletion?
- [ ] Edge: Deliverable with `approvedAt=null` but status=`APPROVED` (data corruption)
- [ ] Scope leak: Any approved/paid deliverable flagged for deletion
- [ ] Guard bypass: Can cleanup run if `dry-run=false` + dispute exists?

---

## Next Steps (Pipeline)

1. **Kavya (QA):** Review all three implementations
   - DPF-3: Test URL validation edge cases, state transitions, IDOR
   - DPF-4: Verify migration + enum defaults
   - DPF-8: Check guard logic, dry-run behavior, scope

2. **Kabir (Security):** Red-team audit (MANDATORY — all three are security-critical)
   - DPF-3: SSRF, IDOR, state bypass, protocol injection
   - DPF-8: Deletion guards, race conditions, scope leaks

3. **Meera (Build Verification):**
   ```bash
   cd influora-api
   mvn -o compile    # Must pass
   mvn -o test       # Must match baseline (no new failures)
   ```

4. **Arjun:** Mark `[x]` in `SHARED_CONTEXT.md` after Meera green

---

## Files Changed Summary

### DPF-3 (Mark-Posted Endpoint)
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java`
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java`
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java`

### DPF-4 (Schema Migration)
- `influora-api/src/main/resources/db/migration/V52__payment_milestone_release_condition.sql` (NEW)
- `influora-api/src/main/java/com/influora/domain/entity/PaymentMilestone.java`

### DPF-8 (R2 Lifecycle Cleanup)
- `influora-api/src/main/java/com/influora/job/DeliverableCleanupJob.java` (NEW)
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java`

**Total:** 7 files modified, 2 files created

---

_Implementation completed 2026-07-13 by Vikram (Backend Developer). Build verification pending Meera._
