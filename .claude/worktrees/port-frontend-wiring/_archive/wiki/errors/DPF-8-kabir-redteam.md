# DPF-8 R2 Cleanup Job — Kabir Red-Team Audit

**Target:** `DeliverableCleanupJob` (scheduled R2 lifecycle cleanup)
**Files:** `influora-api/src/main/java/com/influora/job/DeliverableCleanupJob.java`; refs `Deliverable.applyUpload` (L206-219), `CreatorDeliverableService.StoredFile` (L790-798), `EscrowStatus` enum, `DisputeStatus` enum.
**Auditor:** Kabir (offensive / red-team)

## RE-AUDIT VERDICT (2026-07-13): ✅ PASS — all 5 closed, no new issues. Route to Kavya.

Adversarial re-read of the actual patched code (not Vikram's summary). Every finding verified against source; the 9 regression tests match the real behavior.

| # | Finding | Status | Evidence (verified in code) |
|---|---------|--------|------------------------------|
| H-DPF8-1 | FROZEN/PENDING escrow unguarded | ✅ CLOSED | `UNRELEASED_ESCROW_STATUSES = {FUNDED, FROZEN, PENDING}` (job L73-74); `canDelete` calls `existsByCollaborationIdAndStatusIn(collabId, UNRELEASED_ESCROW_STATUSES)` (L255-256). `existsBy…In` returns true if ANY hold matches → a collab with {RELEASED + FROZEN} still blocks (the FROZEN row matches). Only `RELEASED`/`REFUNDED` (settled/terminal) pass. No enum value slips through. |
| H-DPF8-2 | R2 no-op (`objectKey`) + filesJson corruption | ✅ CLOSED | `extractKeys` now reads `file.get("url")` + `file.get("thumbnailUrl")` (L281-282), the real R2 object keys (confirmed `CreatorDeliverableService` L544-552 persists the key into `url`/`thumbnailUrl`, javadoc L788-790 "hold R2 object keys"). `filesJson` cleared ONLY when `result.allSucceeded()` (L218-222); on any per-key throw `allSucceeded=false` → `failed++`, no `save`, filesJson left fully intact — never partially nulled (verified by test `filesJsonNotClearedOnFailedDelete`). |
| H-DPF8-3 | applyUpload → status flipped to DRAFT | ✅ CLOSED | Abandoned path now calls `d.applyMediaCleanup()` (L221). `applyMediaCleanup` (Deliverable L281-284) is exactly `this.filesJson = null; touch();` — never touches `status`/`versionNumber`. Job's only `applyUpload` mention is a javadoc `{@link}` (L54), not a call. Superseded path never mutates the entity at all. REJECTED stays REJECTED (test `cleanupNeverChangesStatus`). |
| M-DPF8-1 | Log-after-delete / dry-run-only intent | ✅ CLOSED | `deleteKeys` emits intent BEFORE the destructive call in BOTH modes: dry-run "DRY-RUN: would delete…" (L308-313) and live "Deleting R2 object…" (L316-320) immediately precede `deleteObject` (L322), with a post-success "Deleted…" line after. |
| M-DPF8-2 | TOCTOU race | ✅ ACCEPTABLE (documented) | Job is NOT `@Transactional` (no job-spanning tx); each `save` is its own tx. `canDelete` is a fresh, uncached DB read re-invoked per-deliverable immediately before the destructive call (L133, L194). Residual window (guard-read → external R2 delete, milliseconds, on a 2 AM batch) is honestly documented in class javadoc L43-50, with pessimistic-lock escalation path noted. Within MEDIUM tolerance and the original "or document the mitigation" allowance. |

**BONUS — new `deleteObject` attack surface:** ✅ CLEAN. `R2StorageService.deleteObject` (L161-166) builds `DeleteObjectRequest` with the fixed configured bucket + the key. No path traversal is reachable: (1) the delete target is read from **this deliverable's own `filesJson`**, so it can only ever delete keys under `deliverables/{thisDeliverableId}/…` — no cross-deliverable/cross-tenant reach; (2) the key is server-generated at upload (`deliverables/%s/v%d/%s-%s` with `deliverable.getId()`, server ULID, and a `sanitizeFileName`d name — L536-539); (3) S3/R2 keys are opaque strings with no filesystem parent semantics, so `../` in a key is a literal object name, not a traversal. No escape from the deliverable prefix.

---

## ~~VERDICT: BLOCK — loop back to Vikram. 3 HIGH + 2 MEDIUM.~~ (superseded — see RE-AUDIT above)

The one invariant this job exists to protect — *never destroy media that is evidence for a dispute or tied to unreleased money* — is **not** upheld by the guard logic. It is only accidentally "safe" today because a field-name bug makes the R2 delete a silent no-op. Fix that bug (which you must, or the feature does nothing) and the guard leaks. Blocking.

---

## HIGH findings (ship-blockers)

### H-DPF8-1 (HIGH) — FROZEN escrow is NOT guarded → disputed money's evidence is deletable
`canDelete` (L178-192):
```java
boolean hasFundedEscrow =
    !escrowHoldRepository
        .findByCollaborationIdAndStatus(deliverable.getCollaborationId(), EscrowStatus.FUNDED)
        .isEmpty();
return !hasFundedEscrow;
```
The guard checks **only** `EscrowStatus.FUNDED`. The enum is:
```java
public enum EscrowStatus { PENDING, FUNDED, RELEASED, REFUNDED, FROZEN }
```
`FROZEN` is precisely the "money held because of a dispute" state — the single most important thing to protect — and it is **invisible to the guard**. A collaboration whose escrow was frozen (dispute in progress, or dispute resolved-but-not-yet-settled) passes `canDelete` and its deliverable media is eligible for deletion. `PENDING` (payment initiated, not yet funded) is likewise unguarded.
This is the exact "must be IMPOSSIBLE" scenario from the task brief, and it is possible.
- **Fix:** guard on the full unreleased set. Use a status-IN query: block if any escrow hold exists in `{FUNDED, FROZEN, PENDING}` (i.e. anything not `RELEASED`/`REFUNDED`). `EscrowHoldRepository` already exposes an `…StatusIn`-style pattern; add `existsByCollaborationIdAndStatusIn(collaborationId, UNRELEASED_STATUSES)`.

### H-DPF8-2 (HIGH) — R2 delete reads a JSON key that is never serialized → delete is a silent no-op AND `filesJson` is nulled anyway → orphaned R2 objects + destroyed DB→key mapping
`deleteVersionMedia` L208:
```java
String key = (String) file.get("objectKey");
```
But the persisted `filesJson` is a serialized `StoredFile` record (service L790-798) whose fields are:
`id, fileType, fileName, url, thumbnailUrl, fileSize, durationSeconds, md5Hash`.
There is **no `objectKey` field** — the object key is stored under `"url"` (and `"thumbnailUrl"`). So `file.get("objectKey")` is always `null`, `keysToDelete` is always empty, and **`r2StorageService.deleteObject` is never called** in either job path.
Two consequences:
1. **The feature does not work** — no R2 object is ever deleted. Superseded-revision and abandoned-draft cleanup are dead code.
2. **Worse than a no-op in the abandoned path:** `cleanupAbandonedDrafts` still runs `d.applyUpload(d.getVersionNumber(), null, …)` (L163) which **nulls `filesJson`** and saves. The R2 bytes remain, but the DB row that recorded *which keys held them* is wiped. Result: permanently orphaned R2 storage that can never be reclaimed, and — critically for this job's mandate — if a dispute later arises, the system no longer knows which R2 objects were the evidence. The guard "passed," yet the evidentiary trail is destroyed at the DB layer.
- **Fix:** read `"url"`/`"thumbnailUrl"` (the real key fields), not `"objectKey"`. And do not clear `filesJson` until the R2 deletes have actually succeeded.

### H-DPF8-3 (HIGH) — abandoned cleanup reuses `applyUpload` → silently resurrects dead deliverables to editable DRAFT
`cleanupAbandonedDrafts` L163 clears files via:
```java
d.applyUpload(d.getVersionNumber(), null, null, null, null);
```
`applyUpload` (Deliverable L206-219) is the *creator-upload* mutator — it does **`this.status = DeliverableStatus.DRAFT`**. So a `REJECTED` (or `SUBMITTED`, `RESUBMITTED`, `REVISION_REQUESTED`) deliverable that gets "cleaned" is flipped back to **DRAFT**, and `canUploadNewVersion(DRAFT) == true` — i.e. a terminated/rejected deliverable becomes editable again. This corrupts the deliverable lifecycle and could let a creator re-open work the brand already rejected. Using an upload method to perform a cleanup is the wrong primitive.
- **Fix:** add a dedicated `Deliverable.applyMediaCleanup()` that nulls `filesJson` and stamps `updatedAt` **without** touching `status` or `versionNumber` (and only after successful R2 delete, per H-DPF8-2).

---

## MEDIUM findings

### M-DPF8-1 (MEDIUM) — deletion is logged AFTER the destructive call, and intent is logged only in dry-run → wrongful deletes are untraceable
`deleteVersionMedia` L226-235: in live mode it calls `r2StorageService.deleteObject(key)` **first**, then `log.info("Deleted …")`. The "would delete / about to delete" line (L219) is emitted **only** in the `dryRun` branch. So in production there is no pre-delete audit record; if the process dies between the R2 delete and the log line, the deletion is unrecoverable *and* unlogged. The task explicitly requires every deletion to be logged **before** it happens.
- **Fix:** emit an INFO "about to delete key … (deliverable, version, reason)" immediately before `deleteObject`, in both dry-run and live mode.

### M-DPF8-2 (MEDIUM) — TOCTOU race: dispute/escrow can change between `canDelete` and the R2 delete
`canDelete` reads dispute + escrow state, then `deleteVersionMedia` performs an **external, non-transactional** R2 API call. There is no row lock (`SELECT … FOR UPDATE`) on the deliverable, dispute, or escrow. A dispute opened (or escrow frozen) concurrently — *after* the guard read but *before* the R2 delete — is not seen, and the object is deleted anyway. The `@Transactional` annotation does not help: R2 is outside the DB transaction, so its side effect cannot be rolled back. Window = job runtime.
- **Fix:** re-check `canDelete` immediately before each `deleteObject`, and/or pessimistically lock the deliverable row for the batch. Accept that R2 deletes are irreversible and gate them as tightly as possible.

---

## What is actually correct (credit where due)
- **Dry-run defaults to `true`** (`@Value("${influora.cleanup.dry-run:true}")`, L47) and genuinely gates the destructive branch (L218) — not just logging. Good.
- **Dispute guard statuses are right:** `ACTIVE_DISPUTE_STATUSES = {OPEN, UNDER_REVIEW}` matches `DisputeStatus.isActive()`. The dispute-existence check itself is correct (modulo the H-DPF8-1 escrow gap and the M-DPF8-2 race).
- **Approved-content scope is conservative:** `cleanupAbandonedDrafts` excludes `APPROVED/POSTED/VERIFIED/METRICS_REPORTED`; `cleanupSupersededRevisions` only ever loops `v1..vN-1`, never the current approved version. `METRICS_REPORTED` falls in neither set → never touched. Intent is sound; the guard/delete *implementation* is what fails.
- Note: `cleanupSupersededRevisions` is additionally a no-op even after H-DPF8-2 is fixed — the lean row's `filesJson` only holds the *current* version's keys (each `applyUpload` overwrites it), so old-version keys `v1..vN-1` are not present to match. If superseded-revision reclamation is actually required, old keys must be tracked somewhere. Flagging as design gap, not a security hole (fail-safe).

---

## Routing
- **BLOCK → loop back to Vikram** with H-DPF8-1, H-DPF8-2, H-DPF8-3 (all must be fixed), plus M-DPF8-1 and M-DPF8-2.
- Re-audit required after fix — do **not** advance to Kavya until the escrow guard covers FROZEN/PENDING, the R2 key field is corrected, and `filesJson`/status handling is decoupled from `applyUpload`.
