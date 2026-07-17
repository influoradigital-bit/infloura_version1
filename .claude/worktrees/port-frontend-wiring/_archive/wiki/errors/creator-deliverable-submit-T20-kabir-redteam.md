# Creator Deliverable Submit — Task #20 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09  
**Scope:** `CreatorDeliverableService.submitForReview()`, `CreatorDeliverableController.submit()`, `Deliverable.applySubmit()`, `SubmitRequest` / `SubmitResponse`, `DeliverableRepository.findByIdAndCreatorUserId`, cross-check against Task #19 upload IDOR posture, Task #7/T9 M-2 (`TextSanitizer`), Task #19 M-19-2 (upload rate limit), frontend egress (`creator-chat.tsx`, `api.creatorDeliverables.submit`)  
**Reference Spec:** `wiki/tech/creator/09_CREATOR_DELIVERABLES_SPEC.md` §4.4; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6.1–6.2; Kavya `wiki/errors/creator-deliverable-submit-T20-kavya-qa.md`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java` — `submitForReview`, `canSubmit`, `hasUploadedFiles`, `requireOwnedDeliverable`
- `influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java` — `POST /{deliverableId}/submit`
- `influora-api/src/main/java/com/influora/domain/entity/Deliverable.java` — `applySubmit()`
- `influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java` — `SubmitRequest`, `SubmitResponse`
- `influora-api/src/main/java/com/influora/repository/DeliverableRepository.java` — `findByIdAndCreatorUserId`
- `influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java` (+6 submit)
- `influora-api/src/test/java/com/influora/web/CreatorDeliverableControllerTest.java` (+1 submit)
- `src/lib/api.ts` — `creatorDeliverables.submit`
- `src/pages/creator-chat.tsx` — upload + conditional submit render path

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Task #20's submit surface inherits Task #19's access-isolation architecture and adds correct state-machine fail-closed behavior. No new Critical or High findings. No IDOR regression on the submit path.

**Closed / PASS:**

1. **IDOR — CLOSED** — `submitForReview` resolves ownership exclusively via `findByIdAndCreatorUserId(deliverableId, principal.getUserId())` after `CreatorContextService.requireCreatorProfile`. Foreign deliverable probes return uniform `404 DELIVERABLE_NOT_FOUND` (`testSubmitForeignDeliverable`). No path-param or body-supplied creator id is trusted.
2. **State transition abuse — CLOSED** — `canSubmit` allows only `DRAFT` and `REVISION_REQUESTED`; empty `files_json` rejected with `400 NO_CONTENT`; terminal / in-review states return `409 INVALID_STATE`; second submit on `SUBMITTED` fails closed. No client-controlled status field. `revisionCount` is server-owned — client cannot force `SUBMITTED` vs `RESUBMITTED` without a prior brand revise increment.
3. **Cross-tenant enumeration — no new vector** — submit shares the same scoped query as upload/list/status; foreign id → `404`, not `403`.

**Carry-forward (pre-prod, non-blocking sprint gate):**

- **M-2 (MEDIUM → ACTIVE, extended):** `finalCaption`, `notes`, and `hashtags[]` are persisted raw without `TextSanitizer` or `@Size` bounds. Fields are returned via `GET /creator/deliverables/{id}/status` and will surface on brand review UI. React text interpolation in `creator-chat.tsx` mitigates DOM XSS today; spec §6.2 server-side rejection still violated — same debt as Task #7/T9.
- **M-19-2 (MEDIUM, unchanged):** No per-creator rate limit on deliverable mutations. `AuthRateLimitFilter.bucketFor()` returns `null` for `/creator/deliverables/**` — submit POST is unthrottled alongside upload. Spec §6.1 "File upload 10/min" not enforced; submit should share a `"creator-deliverable-write"` bucket when M-19-2 lands.

**New LOW (non-blocking):**

- **L-20-1:** `SubmitRequest` lacks `@Valid` / `@Size` — unbounded `TEXT` column writes possible from authenticated creator.
- **L-20-2:** Concurrent double-submit TOCTOU — no `@Version` optimistic lock; two parallel requests from `DRAFT` can both pass `canSubmit` before either commits (status ends `SUBMITTED`, duplicate `submittedAt` refresh only — annoyance, not privilege escalation).

Meera scoped gate **21/21** (26/26 with MIME/multipart regression) already **PASS**. **Does not block** Ananya Task #20b or Priya integration sign-off. **Blocks production deploy of brand deliverable review UI** until shared `TextSanitizer` lands (M-2). Upload prod **NO-GO** unchanged (M-19-2/3/4 from Task #19).

---

## 1. IDOR — `submitForReview`

### 1a. Gate chain (unchanged from Task #19)

```131:134:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    public SubmitResponse submitForReview(
            AuthPrincipal principal, String deliverableId, SubmitRequest request) {
        creatorContext.requireCreatorProfile(principal);
        Deliverable deliverable = requireOwnedDeliverable(principal, deliverableId);
```

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

Ownership join-through: `deliverables.collaboration_id` → `collaborations.creator_id` (user id). Consistent with Task #10 H-1 / Task #19 PASS.

### 1b. IDOR exploit matrix (submit path)

| Attack | Result |
|---|---|
| Creator A submits Creator B's `deliverableId` | **BLOCKED** — `404 DELIVERABLE_NOT_FOUND` |
| Brand JWT on submit | **BLOCKED** — `403 WRONG_USER_TYPE` at `requireCreatorProfile` |
| Unauthenticated POST | **BLOCKED** — `SecurityConfig` `anyRequest().authenticated()` |
| Spoof creator id in `SubmitRequest` body | **N/A** — record has no identity field; DTO is caption/hashtags/notes only |
| List foreign collaboration deliverables then submit | **BLOCKED** — list gated by `findByIdAndCreatorId`; submit re-checks per-row |

**IDOR on submit: CLOSED. No regression from Task #19.**

---

## 2. State Transition Abuse

### 2a. `canSubmit` + ordering

```136:147:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
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
```

```219:225:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
    private static boolean canSubmit(DeliverableStatus status) {
        return status == DeliverableStatus.DRAFT || status == DeliverableStatus.REVISION_REQUESTED;
    }

    private static boolean hasUploadedFiles(Deliverable deliverable) {
        return !readFilesJson(deliverable.getFilesJson()).isEmpty();
    }
```

Fail-fast order: **ownership → state → content → mutate → save**. Correct.

### 2b. State machine probe matrix

| From status | `files_json` | Submit result | To status |
|---|---|---|---|
| `DRAFT` | non-empty | ✅ 200 | `SUBMITTED` (`revisionCount == 0`) or `RESUBMITTED` (`revisionCount > 0`) |
| `REVISION_REQUESTED` | non-empty | ✅ 200 | `SUBMITTED` or `RESUBMITTED` per `revisionCount` |
| `DRAFT` | `[]` / null / malformed | ❌ `400 NO_CONTENT` | — (no save) |
| `PENDING` | any | ❌ `409 INVALID_STATE` | — |
| `SUBMITTED` / `RESUBMITTED` | non-empty | ❌ `409 INVALID_STATE` | — (`testSubmitInvalidState`) |
| `APPROVED` / `POSTED` / `VERIFIED` | any | ❌ `409 INVALID_STATE` | — (structural — `canSubmit` false) |

### 2c. Abuse scenarios probed

| Scenario | Verdict |
|---|---|
| Submit without upload (skip content gate) | **BLOCKED** — `NO_CONTENT` |
| Double-submit same deliverable | **BLOCKED** — second call `INVALID_STATE` on `SUBMITTED` |
| Re-submit after brand approval without revise flow | **BLOCKED** — `APPROVED` ∉ `canSubmit` |
| Force `RESUBMITTED` on first submission | **BLOCKED** — `revisionCount` server-owned; client cannot set it |
| Mass-assign `status` via request body | **N/A** — `SubmitRequest` has no status field; `applySubmit` sets status from service logic only |
| Omit body to preserve upload-time caption | **ALLOWED (by design)** — null-coalesce to empty `SubmitRequest`; partial update in `applySubmit` |
| Malformed `files_json` treated as empty | **SAFE** — `readFilesJson` swallows parse error → `NO_CONTENT` (fail-closed) |

### 2d. Concurrent submit TOCTOU (L-20-2)

`Deliverable` entity has no `@Version` column. Two concurrent `POST .../submit` requests on the same `DRAFT` row can both read `canSubmit == true`, both pass `hasUploadedFiles`, both `save()` — last writer wins; both may return `200`. End state remains `SUBMITTED` (no financial or cross-tenant impact). Brand notification duplication is deferred (L-20-7). **Severity: LOW** — recommend optimistic locking or `UPDATE ... WHERE status IN ('DRAFT','REVISION_REQUESTED')` row-count check before prod scale.

**State transition abuse: PASS (fail-closed). TOCTOU filed LOW.**

---

## 3. XSS — `finalCaption` / `notes` / `hashtags` (M-2 carry-forward)

### 3a. Ingress — no sanitization

```49:50:influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java
    /** {@code POST /creator/deliverables/{id}/submit} — optional caption/hashtags/notes on lean row. */
    public record SubmitRequest(String finalCaption, List<String> hashtags, String notes) {}
```

```154:158:influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
        deliverable.applySubmit(
                body.finalCaption(),
                JsonLists.toJson(body.hashtags()),
                body.notes(),
                newStatus);
```

- No `@Valid`, no `@Size`, no `TextSanitizer` — raw strings persisted to `caption`, `hashtags_json`, `creator_notes` (`TEXT` / `JSON` columns).
- `JsonLists.toJson(hashtags)` uses Jackson serialization — **no JSON injection** into `hashtags_json`; individual hashtag strings can still contain HTML/script payloads as JSON string values.
- Upload path (`applyUpload`) stores the same fields raw — submit is a **second ingress** for the same columns.

### 3b. Egress — active render paths

| Surface | Field | Render method | DOM XSS today? |
|---|---|---|---|
| `GET .../status` | `caption`, `creatorNotes`, `hashtags` | JSON API → SPA | Depends on consumer |
| `creator-chat.tsx` L1559 | `event.metadata?.caption` (mock timeline) | `{String(...)}` React text node | **Not exploitable** (escaped) |
| Brand review UI | not shipped | — | **Future HIGH risk** without sanitizer |

Payload probe: `POST .../submit` with `{"finalCaption":"<img src=x onerror=alert(1)>","hashtags":["<script>alert(1)</script>"],"notes":"<svg/onload=alert(1)>"}` → persisted verbatim → returned on `getStatus`. Stored XSS becomes exploitable when brand review renders without encoding or when any consumer uses `dangerouslySetInnerHTML`.

### 3c. M-2 status

Task #7 filed M-2 on `Collaboration.notes`. Task #9 escalated M-2 to **ACTIVE** when notes entered deal timeline. Task #20 **extends M-2** to deliverable `caption` / `creator_notes` / `hashtags_json` on both upload and submit ingress.

**Remediation (shared with Task #9):** `TextSanitizer.sanitizePlainText()` at write time in `applySubmit` and `applyUpload`; `@Size(max=500)` on caption, `@Size(max=2000)` on notes, `@Size(max=40)` per hashtag + max list length; `@Valid` on controller.

**M-2 on submit path: MEDIUM (ACTIVE extension). Prod blocker for brand review surface — not sprint integration gate.**

---

## 4. Rate Limiting (M-19-2 carry-forward)

### 4a. Current posture

```168:220:influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java
    private String bucketFor(HttpServletRequest request) {
        // ...
        if (path.startsWith("/auth/")) {
            return "sensitive";
        }
        // ...
        return null;
    }
```

`POST /creator/deliverables/{id}/submit` → `bucketFor` returns **`null`** — no throttle. Same as upload (`POST .../upload`) and apply (`POST /creator/campaigns/{id}/apply`, M-1).

### 4b. Abuse scenario (submit-specific)

Authenticated creator hammers submit across N deliverable slots in `DRAFT`:
- First submit per row succeeds → `SUBMITTED`.
- Subsequent submits on same row → `409 INVALID_STATE` (cheap).
- Cross-row spam still causes N DB writes + future brand notification noise when L-20-7 lands.

Cost is bounded by deliverable cardinality per collaboration, not request rate — identical class to M-19-2 upload abuse.

### 4c. Spec alignment

`12_CREATOR_SECURITY_SPEC.md` §6.1: **File upload — 10 per minute**. Submit is a lightweight state mutation (no multipart), but belongs in the same **creator-deliverable-write** throttle family when M-19-2 is implemented.

**Recommended fix (batch with M-19-2):**

```java
// AuthRateLimitFilter.bucketFor — authenticated creator deliverable mutations
if (path.matches("/creator/deliverables/[^/]+/(upload|submit)")) {
    return "creator-deliverable-write"; // limit 10 / 60s keyed by principal.getUserId()
}
```

**M-19-2 carry-forward: MEDIUM, OPEN. Submit included in scope when upload rate limit lands.**

---

## 5. Input Validation Gaps (L-20-1)

| Field | DTO constraint | DB column | Risk |
|---|---|---|---|
| `finalCaption` | none | `TEXT` | Unbounded write / storage bloat |
| `notes` | none | `TEXT` | Same |
| `hashtags[]` | none | `JSON` | Unbounded list length + long strings |

Contrast: `ApplyRequest.message` has `@Size(max=2000)`; `SubmitRequest` has **no** jakarta.validation annotations and controller omits `@Valid`.

**Severity: LOW** for authenticated creator self-DoS; becomes **MEDIUM** when combined with M-2 XSS (large payload + script tags).

---

## 6. Test Coverage (security-relevant)

| Hostile path | Test | Status |
|---|---|---|
| Foreign deliverable 404 | `testSubmitForeignDeliverable` | ✅ |
| Empty `files_json` | `testSubmitNoFiles` | ✅ |
| `SUBMITTED` state rejection | `testSubmitInvalidState` | ✅ |
| Happy path + field persistence | `testSubmitHappyPath` | ✅ |
| `RESUBMITTED` when `revisionCount > 0` | `testSubmitResubmitted` | ✅ |
| `REVISION_REQUESTED` allowed | `testSubmitFromRevisionRequested` | ✅ |
| XSS payload persistence | — | ❌ L-20-3 |
| `PENDING` / `APPROVED` rejection | — | ❌ L-20-4 |
| Malformed `files_json` → `NO_CONTENT` | — | ❌ L-20-5 |
| Concurrent double-submit | — | ❌ L-20-6 |

Meera gate **21/21 PASS** — sufficient for sprint integration; hostile XSS/state-matrix tests recommended before prod.

---

## Findings Summary

| ID | Severity | Area | Status |
|---|---|---|---|
| — | — | IDOR on submit (`findByIdAndCreatorUserId`) | **CLOSED** — PASS |
| — | — | State transition abuse (`canSubmit` + `NO_CONTENT`) | **CLOSED** — PASS |
| — | — | Cross-tenant enumeration | **CLOSED** — no new vector |
| M-2 | **MEDIUM (ACTIVE, extended)** | `finalCaption` / `notes` / `hashtags` stored raw — extends Task #7/T9 debt to deliverable submit + upload ingress | **OPEN** — prod blocker for brand review UI |
| M-19-2 | **MEDIUM** | No per-creator rate limit on `/creator/deliverables/**` (upload + submit) | **OPEN** — carry-forward from Task #19 |
| L-20-1 | LOW | `SubmitRequest` missing `@Valid` / `@Size` | Open |
| L-20-2 | LOW | Concurrent submit TOCTOU (no optimistic lock) | Open |
| L-20-3 | LOW | No XSS persistence unit test on submit | Open |
| L-20-4 | LOW | Incomplete terminal-state test matrix (`PENDING`, `APPROVED`) | Open |
| L-20-5 | LOW | Malformed `files_json` hostile test | Open |
| L-20-6 | LOW | No concurrent double-submit test | Open |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task #20 IDOR / state machine / content gate | **GO** |
| Meera scoped unit-test gate (21/21) | **GO** (already PASS) |
| Ananya Task #20b submit UI wiring | **GO** |
| Kavya QA Task #20 | **GO** (already APPROVED) |
| Sprint integration / dev deploy | **GO** |
| Production deploy of brand deliverable review UI | **NO-GO** until M-2 `TextSanitizer` on deliverable text fields |
| Production deploy of deliverable upload | **NO-GO** — M-19-2/3/4 unchanged (Task #19) |

**Pipeline position:** Task #20 security gate **✅ PASS WITH FINDINGS** — cleared for Priya sign-off on submit API integration. Vikram batches M-2 sanitizer + M-19-2 rate limit in pre-prod hardening PR (shared with deal room Task #9).

---

## Kabir Sign-Off

- [x] IDOR on submit re-verified — uniform `404`, collaboration join-through unchanged from Task #19
- [x] State transition abuse probed — fail-closed on invalid states, empty content, double-submit
- [x] XSS on `finalCaption` / `notes` / `hashtags` — M-2 extended; no active DOM XSS in current SPA paths
- [x] Rate limiting — M-19-2 carry-forward; submit unthrottled (same class as upload)
- [x] No Critical or High findings — pipeline **not blocked**
- [ ] M-2 `TextSanitizer` on deliverable ingress — **pre-prod required before brand review prod**
- [ ] M-19-2 creator-deliverable-write bucket — **pre-prod required before upload prod**

**Kabir verdict: ✅ PASS WITH FINDINGS.** Route to Priya for Task #20 integration sign-off. Escalation to Priya/Swapnil: **none** (no Critical/High).

---

**Document Control:** Created 2026-07-09 by Kabir (Task #20). Carry-forward: M-2 TextSanitizer, M-19-2 rate limit. Next: Priya Task #20 sign-off; Vikram shared sanitizer PR.
