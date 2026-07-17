# QA Review: Creator Deliverable Submit API — Task #20 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~20:45 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (submit surface + T19 carry-forward) → Meera build  
**Scope:** Vikram Task #20 — `POST /creator/deliverables/{id}/submit`  
**Reference:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.4; Priya `CREATOR_EXEC_PLAN_PRIYA.md` §1.3 lean entity  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `submitForReview`, `canSubmit`, `hasUploadedFiles`
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — `POST /{deliverableId}/submit`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applySubmit()`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `SubmitRequest`, `SubmitResponse`
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` — `findByIdAndCreatorUserId`
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` (17 tests, +6 submit)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` (4 tests, +1 submit)

---

## Executive Summary

Task #20 **passes QA**. `submitForReview` transitions creator-owned deliverables from `DRAFT` or `REVISION_REQUESTED` to `SUBMITTED` (first submission) or `RESUBMITTED` (when `revisionCount > 0`), sets `submitted_at`, and optionally updates `finalCaption`, `hashtags`, and `notes` on the lean row via `Deliverable.applySubmit`. Access isolation matches Tasks #19/#19c: `CreatorContextService.requireCreatorProfile` → `DeliverableRepository.findByIdAndCreatorUserId` (collaboration join-through on `collaborations.creator_id`). No path-param creator id is trusted.

`files_json` validation rejects empty uploads (`NO_CONTENT` 400) and invalid states (`INVALID_STATE` 409). Foreign deliverable → uniform `DELIVERABLE_NOT_FOUND` 404.

**21 scoped unit tests** (17 service + 4 controller) cover happy path, resubmit, revision-requested path, no-files rejection, invalid state, foreign 404, and controller delegation. **`mvn` not on PATH** in this QA environment — Meera must confirm **21/21 PASS**.

**Kabir carry-forward:** IDOR posture unchanged from Task #19 PASS; caption/hashtags/notes XSS extends M-2; no submit rate limit (same posture as M-1/M-19-2).

**Ananya follow-up (out of backend scope):** `creatorDeliverables` in `api.ts` has no `submit` method — legacy `deliverables.submit` uses wrong path/payload.

---

## Task #20 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `POST /creator/deliverables/{id}/submit` | ✅ PASS | `CreatorDeliverableController.submit` L72–78; optional `@RequestBody` |
| Optional `finalCaption`, `hashtags`, `notes` | ✅ PASS | `SubmitRequest` record; `applySubmit` partial-update semantics (null = keep existing) |
| `CreatorContextService` + ownership gate | ✅ PASS | L133–134 `requireCreatorProfile` + `requireOwnedDeliverable` |
| State: `DRAFT` / `REVISION_REQUESTED` → `SUBMITTED` / `RESUBMITTED` | ✅ PASS | `canSubmit` L219–221; `revisionCount > 0` → `RESUBMITTED` L150–153 |
| `files_json` not empty + `canSubmit` | ✅ PASS | `hasUploadedFiles` L223–225; `readFilesJson` empty → `NO_CONTENT` |
| Response: `deliverableId`, `status`, `message` | ✅ PASS | `SubmitResponse` + user-facing messages L161–164 |
| Sets `submitted_at` on submit | ✅ PASS | `Deliverable.applySubmit` L237 |
| Unit tests 17/17 + 4/4 = **21/21** | ⚠️ AUTHORED | +6 submit service, +1 submit controller; not executed here (L-20-1) |
| TECH-STACK.md compliance | ✅ PASS | Thin controller, `ApiException` codes, JWT auth, transactional service, no debug code |

---

## Test Execution

| Test Class | Authored | Executed | Failures | Notes |
|------------|----------|----------|----------|-------|
| `CreatorDeliverableServiceTest` | 17 | ❌ Not run | — | +6 submit tests; `mvn` unavailable |
| `CreatorDeliverableControllerTest` | 4 | ❌ Not run | — | +1 submit delegation test |
| **Total** | **21** | **0** | — | **Meera gate required** |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=CreatorDeliverableServiceTest,CreatorDeliverableControllerTest
```

---

## Service Review: `submitForReview`

```131:166:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    public SubmitResponse submitForReview(
            AuthPrincipal principal, String deliverableId, SubmitRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);

        if (!canSubmit(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot submit deliverable in current state",
                    HttpStatus.CONFLICT);
        }
        if (!hasUploadedFiles(deliverable)) {
            throw new ApiException(
                    "NO_CONTENT",
                    "Upload at least one file before submitting",
                    HttpStatus.BAD_REQUEST);
        }

        SubmitRequest body = request != null ? request : new SubmitRequest(null, null, null);
        DeliverableStatus newStatus =
                deliverable.getRevisionCount() > 0
                        ? DeliverableStatus.RESUBMITTED
                        : DeliverableStatus.SUBMITTED;
        deliverable.applySubmit(
                body.finalCaption(),
                JsonLists.toJson(body.hashtags()),
                body.notes(),
                newStatus);
        deliverableRepository.save(deliverable);
        // ...
    }
```

**Ordering:** Ownership → state gate → content gate → mutate → save. Correct fail-fast sequence.

**Null body:** Controller `@RequestBody(required = false)` + service null-coalesce to empty `SubmitRequest` — preserves upload-time caption/hashtags/notes when submit body omitted. Tested in `testSubmitFromRevisionRequested`.

---

## State Machine Review

| From Status | `canSubmit` | To Status | Condition |
|-------------|-------------|-----------|-----------|
| `DRAFT` | ✅ | `SUBMITTED` | `revisionCount == 0` |
| `DRAFT` | ✅ | `RESUBMITTED` | `revisionCount > 0` |
| `REVISION_REQUESTED` | ✅ | `SUBMITTED` | `revisionCount == 0` |
| `REVISION_REQUESTED` | ✅ | `RESUBMITTED` | `revisionCount > 0` (expected once revise endpoint increments count) |
| `PENDING` | ❌ | — | Must upload first (`applyUpload` → `DRAFT`) |
| `SUBMITTED` / `RESUBMITTED` | ❌ | — | `INVALID_STATE` 409 |
| `APPROVED` / `POSTED` / etc. | ❌ | — | `INVALID_STATE` 409 |

`canSubmit` is symmetric with status endpoint action flag: `canSubmit(status) && !files.isEmpty()` in `toStatusResponse` L417.

**Idempotency:** Second submit on `SUBMITTED` fails closed with `INVALID_STATE` — no double-submit without brand revise flow. No `Idempotency-Key` header (acceptable for mutation-with-state-guard; see L-20-8).

---

## `files_json` Validation Review

```223:225:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static boolean hasUploadedFiles(Deliverable deliverable) {
        return !readFilesJson(deliverable.getFilesJson()).isEmpty();
    }
```

| `files_json` value | Parsed result | Submit allowed? |
|--------------------|---------------|-----------------|
| `null` / blank | `List.of()` | ❌ `NO_CONTENT` |
| `[]` | empty list | ❌ `NO_CONTENT` (tested) |
| `[{"id":"f1",...}]` | non-empty | ✅ |
| Malformed JSON | `List.of()` (swallow parse error) | ❌ `NO_CONTENT` — safe fail-closed |

**Note:** Malformed JSON is treated as no content, not a 500 — acceptable for lean row; recommend hostile test (L-20-3).

---

## Access Isolation Review

### Gate chain (unchanged from Task #19)

1. **JWT required** — `SecurityConfig` `anyRequest().authenticated()` for `/creator/**`
2. **Creator role** — `CreatorContextService.requireCreator` → `WRONG_USER_TYPE` 403 for brand JWT
3. **Creator profile exists** — `requireCreatorProfile` → `CREATOR_PROFILE_NOT_FOUND` 404
4. **Deliverable ownership** — `findByIdAndCreatorUserId(id, principal.getUserId())` join-through `Collaboration.creatorId`

```202:211:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private Deliverable requireOwnedDeliverable(AuthPrincipal principal, String deliverableId) {
        return deliverableRepository
                .findByIdAndCreatorUserId(deliverableId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DELIVERABLE_NOT_FOUND",
                                        "Deliverable not found",
                                        HttpStatus.NOT_FOUND));
    }
```

Foreign creator → uniform 404 (no enumeration). Tested: `testSubmitForeignDeliverable`.

---

## Controller Review

```72:78:influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java
    @PostMapping("/{deliverableId}/submit")
    public ResponseEntity<ApiResponse<SubmitResponse>> submit(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String deliverableId,
            @RequestBody(required = false) SubmitRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(creatorDeliverableService.submitForReview(principal, deliverableId, body)));
    }
```

Thin delegation — no business logic in controller. Returns `200 OK` (spec silent on status code; consistent with other creator mutations).

---

## Entity: `applySubmit`

```222:239:influora-api/src/main/java/com/influora/domain/entity/Deliverable.java
    public void applySubmit(
            String finalCaption,
            String hashtagsJson,
            String notes,
            DeliverableStatus newStatus) {
        if (finalCaption != null) {
            this.caption = finalCaption;
        }
        if (hashtagsJson != null) {
            this.hashtagsJson = hashtagsJson;
        }
        if (notes != null) {
            this.creatorNotes = notes;
        }
        this.status = newStatus;
        this.submittedAt = Instant.now();
        touch();
    }
```

Partial updates preserve upload-time fields when submit body fields are null. `submittedAt` always refreshed — correct for resubmit audit trail.

---

## Spec Alignment Notes

| Spec §4.4 field | Implementation | Verdict |
|-----------------|----------------|---------|
| `finalCaption` | `SubmitRequest.finalCaption` → `caption` | ✅ |
| `hashtags` | `List<String>` → `hashtags_json` | ✅ |
| `notes` | `SubmitRequest.notes` → `creator_notes` | ✅ |
| `versionId` | **Omitted** | ⚠️ Intentional — lean row has no version table; resolves T19 L-4. Document in API contract. |
| Brand notification | **Not implemented** | Deferred — revise/approve slice; L-20-7 |

---

## Unit Test Matrix (Submit)

| Test | Coverage |
|------|----------|
| `testSubmitHappyPath` | DRAFT + files → SUBMITTED; caption/notes persisted; `submittedAt` set |
| `testSubmitResubmitted` | `revisionCount=1` → RESUBMITTED message |
| `testSubmitFromRevisionRequested` | REVISION_REQUESTED allowed; null body OK |
| `testSubmitNoFiles` | `[]` files_json → `NO_CONTENT`; no save |
| `testSubmitInvalidState` | SUBMITTED → `INVALID_STATE` |
| `testSubmitForeignDeliverable` | Empty repo → `DELIVERABLE_NOT_FOUND` |
| `testSubmit` (controller) | Delegation + 200 response |

**Gaps (non-blocking):** PENDING rejection, APPROVED/RESUBMITTED rejection, malformed `files_json`, REVISION_REQUESTED + `revisionCount>0` → RESUBMITTED combo.

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| Follows TECH-STACK.md (thin controller, service transactions) | ✅ |
| No `console.log` / debug code | ✅ |
| Proper error handling via `ApiException` | ✅ |
| Comments explain WHY (lean row, isolation) | ✅ |
| No hardcoded secrets | ✅ |
| Input validation at service layer | ✅ |
| DTO `@Size` / sanitization on text fields | ⚠️ PARTIAL — Kabir M-2 carry-forward |

---

## Findings (Non-Blocking)

### L-20-1: Tests not executed in QA environment
`mvn` unavailable; no Surefire artifacts for submit slice. **Meera must confirm 21/21 PASS.**

### L-20-2: Spec `versionId` omitted (documented deviation)
§4.4 request body includes `versionId` for version-table architecture. Lean entity uses current `files_json` + `version_number` from upload — no version token on submit. Aligns with Priya §1.3; closes T19 L-4.

### L-20-3: Malformed `files_json` hostile path untested
`readFilesJson` swallows parse errors → empty list → `NO_CONTENT`. Safe behavior; add unit test for regression.

### L-20-4: Incomplete terminal-state matrix
Only `SUBMITTED` tested as invalid state. `APPROVED`, `POSTED`, `PENDING` structurally blocked by `canSubmit` — symmetric tests recommended.

### L-20-5: `REVISION_REQUESTED` + `revisionCount>0` untested
Once brand revise endpoint increments `revision_count`, resubmit should land on `RESUBMITTED`. Logic present; combo not unit-tested.

### L-20-6: Frontend `creatorDeliverables.submit` not wired
`api.ts` `creatorDeliverables` group ends at `upload` — no submit client. Legacy `deliverables.submit` points to `/deliverables/${id}/submit` with `{ fileUrls, notes }` — wrong contract. **Ananya Task #20b** follow-up; does not block backend gate.

### L-20-7: Brand notification deferred
Spec pseudocode emits `DELIVERABLE_SUBMITTED` notification — not in this slice. Expected with brand review endpoints.

### L-20-8: Caption/hashtags/notes XSS (pre-prod)
Raw text persisted on submit. Extends Kabir M-2 from Tasks #9/#19. **Escalate** before brand review UI renders submitted content.

### L-20-9: No submit rate limit
No per-creator throttle on submit POST. Same posture as M-1 apply / M-19-2 upload — pre-prod hardening.

---

## Kabir Escalation Items (Security Gate)

1. **IDOR on submit** — confirm uniform `404 DELIVERABLE_NOT_FOUND` for foreign creators (architecture closed in T19; re-verify on submit path).
2. **XSS on `finalCaption` / `notes` / hashtags** — extends M-2; brand deliverable review render path.
3. **Cross-tenant enumeration** — submit + upload share same scoped query; no new vector expected.
4. **Carry-forward T19** — M-19-2 rate limit, M-19-3 buffering, M-19-4 public URLs still prod NO-GO for upload surface.

---

## QA Sign-Off

- [x] `submitForReview` state transitions verified (`DRAFT`/`REVISION_REQUESTED` → `SUBMITTED`/`RESUBMITTED`)
- [x] `files_json` empty/missing rejection verified (code + `testSubmitNoFiles`)
- [x] `CreatorContextService` + `findByIdAndCreatorUserId` scoping verified
- [x] Controller thin delegation verified
- [x] `applySubmit` sets `submitted_at` and optional field updates verified
- [x] Key hostile paths covered in unit tests (foreign, no files, invalid state, resubmit)
- [ ] Scoped `mvn test` **21/21** — **Meera gate**
- [ ] Kabir submit security review — **NEXT GATE**

**Kavya verdict: ✅ APPROVED.** Route to Kabir for deliverable submit red-team (Task #20 security gate) → Meera build verify.

---

**Document Control:** Created 2026-07-09 by Kavya (Task #20). Next: Kabir deliverable submit security review; Ananya `creatorDeliverables.submit` wire.
