# Collaboration Reviews API — Task #29 V4 (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~19:55 IST)  
**Scope:** `Review` entity, `ReviewService`, `CreatorReviewController`, `BrandReviewController`, `ReviewDtos`, `ReviewRepository`, `V41__reviews.sql`, `ContentFlag.userFlag` + `ContentFlagType.REVIEW`, `TextSanitizer`, `AuthRateLimitFilter` (absence of review buckets), admin `FlagQueue` egress, cross-check against CEO §1.2, Kavya `wiki/errors/creator-reviews-T29-kavya-qa.md`, Task #21/#22 Kabir precedents (M-21-1 rate limit, M-2 TextSanitizer)  
**Reference Spec:** `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.2; `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md` §6; Kavya `wiki/errors/creator-reviews-T29-kavya-qa.md`  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/service/ReviewService.java`
- `influora-api/src/main/java/com/influora/domain/entity/Review.java`
- `influora-api/src/main/java/com/influora/web/CreatorReviewController.java`
- `influora-api/src/main/java/com/influora/web/BrandReviewController.java`
- `influora-api/src/main/java/com/influora/web/dto/review/ReviewDtos.java`
- `influora-api/src/main/java/com/influora/repository/ReviewRepository.java`
- `influora-api/src/main/java/com/influora/repository/CollaborationRepository.java` — `findByIdAndCreatorId`, `findByIdAndWorkspaceId`
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/service/BrandContextService.java`
- `influora-api/src/main/java/com/influora/domain/entity/ContentFlag.java` — `userFlag()`
- `influora-api/src/main/java/com/influora/common/TextSanitizer.java`
- `influora-api/src/main/resources/db/migration/V41__reviews.sql`
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/src/test/java/com/influora/service/ReviewServiceTest.java` (12 tests)
- `influora-api/src/test/java/com/influora/common/TextSanitizerTest.java` (11 tests)
- `src/admin/types/admin.types.ts` — `ContentFlag.contentType` union
- `src/admin/components/moderation/FlagQueue.tsx` — preview/reason render sinks

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Task #29 V4 collaboration review surface is **fail-closed on identity, state, and tenancy**. No Critical or High findings. IDOR, COMPLETED gate, double-review race, cross-role separation, and XSS ingress are **CLOSED** at the service layer with defense-in-depth at the database.

**Closed / PASS:**

1. **IDOR on create — CLOSED** — Collaboration resolved exclusively via `findByIdAndCreatorId(collaborationId, principal.getUserId())` (creator) or `findByIdAndWorkspaceId(collaborationId, workspace.getId())` (brand). Foreign probes → uniform `404 COLLABORATION_NOT_FOUND` / `REVIEW_NOT_FOUND` with no existence oracle. Unit tests: `creatorCreateIdorForeignCollaboration`, `brandCreateIdorForeignCollaboration`, `creatorFlagIdorForeignReview`, `brandFlagIdorForeignReview`.
2. **COMPLETED-only gate — CLOSED** — Strict `collaboration.getStatus() != CollaborationStatus.COMPLETED` → `409 COLLABORATION_NOT_COMPLETED`. All non-terminal states (`IN_PROGRESS`, `DISPUTED`, `CANCELLED`, `REVIEW_PENDING`, etc.) rejected. Not a “terminal or later” weak check.
3. **Double-review race — CLOSED** — Application `existsByCollaborationIdAndReviewerType` + DB `UNIQUE KEY uq_review_collab_reviewer (collaboration_id, reviewer_type)` + `DataIntegrityViolationException` → `409 ALREADY_REVIEWED`. Test: `creatorCreateRaceDuplicate`.
4. **Cross-role endpoint access — CLOSED** — `CreatorContextService.requireCreator` / `BrandContextService.requireBrand` → `403 WRONG_USER_TYPE` before any DB write. Brand JWT cannot reach `/creator/reviews`; creator JWT cannot reach `/brand/reviews`. Admin JWT likewise rejected (distinct `UserType`).
5. **XSS via review text / flag reason — CLOSED** — `Review.create` and `saveFlag` both call `TextSanitizer.sanitizePlainText` before persist. Shared sanitizer strips script/style blocks, HTML tags, decodes basic entities, then re-strips residual tags (`TextSanitizerTest` covers event handlers, nested script, entity-encoded payloads). Admin `FlagQueue` renders via React text interpolation — no `dangerouslySetInnerHTML`.
6. **Reviewer identity not spoofable — CLOSED** — `reviewer_user_id` from `principal.getUserId()` only; `reviewer_type` from service method (`createCreatorReview` → `CREATOR`, `createBrandReview` → `BRAND`). Not present in request DTO.
7. **Unauthenticated access — CLOSED** — `SecurityConfig` `anyRequest().authenticated()`; review paths not in `permitAll`.

**Carry-forward (pre-prod, non-blocking Meera M2 gate):**

- **M-T29-1 (MEDIUM, ACTIVE):** No rate-limit bucket for `POST /creator/reviews`, `POST /brand/reviews`, or `POST /*/reviews/{id}/flag`. `AuthRateLimitFilter.bucketFor()` returns `null` for these paths — authenticated users can spam reviews across owned collaborations and **flood the admin moderation queue** via unlimited flag POSTs. Same abuse class as pre-Task-#25 M-21-1. Recommend `"creator-review-write"` (e.g. 10/min) and `"brand-review-write"` + `"review-flag"` (e.g. 5/min per reviewId or per user) buckets keyed by JWT `sub`.
- **M-T29-2 (MEDIUM, ACTIVE):** No duplicate-flag guard — same authenticated user can POST flag repeatedly on one `reviewId`, creating unbounded `content_flags` rows. `flagged_by` column stores source enum (`USER`), not `user_id` — per-user dedupe impossible without schema change. Moderation-queue DoS vector.

**New LOW (non-blocking):**

- **L-T29-1:** Self-flag allowed — `requireReviewForParty` checks collaboration ownership but not `review.reviewerType != partyType`. Creator can flag their own `CREATOR` review on a owned collaboration (and brand likewise). CEO §1.2 intent is flagging the *other party's* review; self-flag is moderation noise, not a tenancy breach.
- **L-T29-2:** `admin.types.ts` L625 `contentType` union omits `'REVIEW'` — admin FlagQueue filter/icon path falls through to `MESSAGE` default when REVIEW flags arrive.
- **L-T29-3:** No `CreatorReviewControllerTest` / `BrandReviewControllerTest` — cross-role `403` and `@Valid` rejection paths not integration-tested (service layer covered).
- **L-T29-4:** `ContentFlag.userFlag` stores no flagger `user_id` — weak audit trail; compounds M-T29-2 duplicate spam.
- **L-T29-5:** `mvn` unavailable in red-team env — Meera must confirm **12/12** `ReviewServiceTest` PASS in CI.

Meera M2 gate **12/12** pending execution. **Does not block** sprint integration or Ananya `creator-reviews`/`brand-reviews` frontend wiring. **Recommend rate-limit + duplicate-flag hardening before production** (M-T29-1, M-T29-2) — same debt posture as deliverable-review M-21-1 before Task #25 closure.

---

## 1. IDOR — Review Creation (Wrong Collaboration Party)

### 1a. Gate chain

```168:188:influora-api/src/main/java/com/influora/service/ReviewService.java
    private Collaboration requireOwnedCollaboration(
            AuthPrincipal principal, String collaborationId, ReviewerType reviewerType) {
        if (reviewerType == ReviewerType.CREATOR) {
            return collaborationRepository
                    .findByIdAndCreatorId(collaborationId, principal.getUserId())
                    .orElseThrow(
                            () ->
                                    new ApiException(
                                            "COLLABORATION_NOT_FOUND",
                                            "Collaboration not found",
                                            HttpStatus.NOT_FOUND));
        }
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository
                .findByIdAndWorkspaceId(collaborationId, workspace.getId())
                ...
    }
```

Identity is always server-derived from JWT + context service — never from request body beyond `collaborationId` (which is then join-scoped).

### 1b. IDOR exploit matrix

| Attack | Result |
|---|---|
| Creator A reviews Creator B's `collaborationId` | **BLOCKED** — `404 COLLABORATION_NOT_FOUND` |
| Brand A reviews Brand B workspace collaboration | **BLOCKED** — `404 COLLABORATION_NOT_FOUND` |
| Creator probes foreign id → existence oracle | **BLOCKED** — uniform 404, same code/message |
| Brand probes foreign workspace collab | **BLOCKED** — uniform 404 |
| Spoof `reviewer_user_id` / `reviewer_type` in body | **N/A** — fields not in DTO |
| Use `collaborationId` from completed deal user is NOT party to | **BLOCKED** — join query returns empty |

**IDOR on review create: CLOSED.**

---

## 2. IDOR — Review Flag (`POST /*/reviews/{reviewId}/flag`)

### 2a. Gate chain

```118:143:influora-api/src/main/java/com/influora/service/ReviewService.java
    private Review requireReviewForParty(
            String reviewId, AuthPrincipal principal, ReviewerType partyType) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(...REVIEW_NOT_FOUND...);
        if (partyType == ReviewerType.CREATOR) {
            collaborationRepository
                    .findByIdAndCreatorId(review.getCollaborationId(), principal.getUserId())
                    .orElseThrow(...REVIEW_NOT_FOUND...);
        } else {
            Workspace workspace = brandContext.requireBrandWorkspace(principal);
            collaborationRepository
                    .findByIdAndWorkspaceId(review.getCollaborationId(), workspace.getId())
                    .orElseThrow(...REVIEW_NOT_FOUND...);
        }
        return review;
    }
```

Review is loaded by id, then **re-scoped** through collaboration ownership before any flag write. Foreign `reviewId` probes return uniform `404 REVIEW_NOT_FOUND` — no leak of `collaborationId`, stars, or reviewer identity in error body.

### 2b. Flag IDOR matrix

| Attack | Result |
|---|---|
| Creator flags review on another creator's deal (guessed `reviewId`) | **BLOCKED** — `404 REVIEW_NOT_FOUND` |
| Brand flags review outside workspace | **BLOCKED** — `404 REVIEW_NOT_FOUND` |
| Flag review on own collaboration (other party's review) | **ALLOWED** — intended per CEO §1.2 |
| Flag own review on own collaboration | **ALLOWED** — see L-T29-1 (moderation noise) |

**IDOR on flag: CLOSED for cross-tenant writes.**

---

## 3. Review Before COMPLETED Bypass

### 3a. State gate

```84:89:influora-api/src/main/java/com/influora/service/ReviewService.java
        if (collaboration.getStatus() != CollaborationStatus.COMPLETED) {
            throw new ApiException(
                    "COLLABORATION_NOT_COMPLETED",
                    "Reviews are only allowed after the collaboration is completed",
                    HttpStatus.CONFLICT);
        }
```

Strict equality — not `>= COMPLETED` or “terminal states include DISPUTED.”

### 3b. Bypass attempt matrix

| Collaboration status | Result |
|---|---|
| `IN_PROGRESS` | **BLOCKED** — `409 COLLABORATION_NOT_COMPLETED` |
| `DISPUTED` | **BLOCKED** — `409` (dispute does not auto-complete) |
| `CANCELLED` | **BLOCKED** — `409` |
| `REVIEW_PENDING` | **BLOCKED** — `409` |
| `REVISION_REQUESTED` | **BLOCKED** — `409` |
| `COMPLETED` | **ALLOWED** |

No service path in `ReviewService` mutates collaboration status — attacker cannot self-elevate status during the same transaction.

**COMPLETED gate: CLOSED.**

---

## 4. Double-Review Race (TOCTOU)

### 4a. Defense layers

| Layer | Mechanism |
|---|---|
| Application | `existsByCollaborationIdAndReviewerType` → `409 ALREADY_REVIEWED` |
| Database | `UNIQUE KEY uq_review_collab_reviewer (collaboration_id, reviewer_type)` in `V41__reviews.sql` |
| Race catch | `DataIntegrityViolationException` on `save` → `409 ALREADY_REVIEWED` |

Parallel POSTs from same party on same collaboration: at most one succeeds; others get `409`. Creator and brand each get one slot per collaboration (different `reviewer_type` values) — intended per CEO §1.2.

**Double-review race: CLOSED.**

---

## 5. Cross-Role Access (Creator vs Brand Endpoints)

### 5a. Role gate

```21:26:influora-api/src/main/java/com/influora/service/CreatorContextService.java
    public void requireCreator(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.CREATOR) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        }
    }
```

Brand mirror at `BrandContextService.requireBrand` (L27–31).

### 5b. Cross-role matrix

| JWT type | Endpoint | Result |
|---|---|---|
| Brand | `POST /creator/reviews` | **BLOCKED** — `403 WRONG_USER_TYPE` |
| Brand | `POST /creator/reviews/{id}/flag` | **BLOCKED** — `403` |
| Creator | `POST /brand/reviews` | **BLOCKED** — `403 WRONG_USER_TYPE` |
| Creator | `POST /brand/reviews/{id}/flag` | **BLOCKED** — `403` |
| Unauthenticated | any review path | **BLOCKED** — `401` (SecurityConfig) |
| Admin | creator/brand review paths | **BLOCKED** — `403 WRONG_USER_TYPE` |

Controllers have no `@PreAuthorize` role annotations — isolation is entirely in context services invoked first in `ReviewService`. Consistent with Task #9+ pattern.

**Cross-role: CLOSED.** (Integration test gap tracked L-T29-3.)

---

## 6. XSS — Review Text and Flag Reason (TextSanitizer)

### 6a. Ingress points

| Field | Sanitizer call | Max length |
|---|---|---|
| `CreateReviewRequest.text` | `Review.create` → `TextSanitizer.sanitizePlainText` | DTO `@Size(max=1000)` |
| `FlagReviewRequest.reason` | `saveFlag` → `TextSanitizer.sanitizePlainText` | DTO `@Size(max=255)` |
| Flag `contentPreview` | Copied from DB `review.review_text` (sanitized at create) | Truncated 200 chars |

### 6b. XSS payload matrix

| Payload | Review text | Flag reason |
|---|---|---|
| `<script>alert(1)</script>` | Stripped → plain text or null | Stripped |
| `<img src=x onerror=alert(1)>` | Stripped (`TextSanitizerTest`) | Stripped |
| `&lt;script&gt;alert(1)&lt;/script&gt;` | Entity decode → strip → safe (`TextSanitizerTest`) | Same |
| HTML-only / whitespace | Stored as `null` (review) / `400 INVALID_REQUEST` (flag) | Rejected |
| Plain `javascript:alert(1)` in text | Stored as literal string | Stored as literal |

Plain-text `javascript:` URIs are not stripped — **acceptable** because admin/creator egress uses React text nodes, not `href` injection from review body.

### 6c. Admin egress

```318:321:src/admin/components/moderation/FlagQueue.tsx
            {flag.contentPreview && (
              <div className="rounded-lg border border-border bg-card p-3 text-sm text-foreground">
                {flag.contentPreview}
              </div>
```

React escapes on render. Combined with server-side tag stripping on ingress: **stored XSS CLOSED** for V4 write surface.

**XSS: CLOSED** (inherits Task #22 `TextSanitizer` — M-2 debt **closed** for this slice).

---

## 7. Flag Abuse / Duplicate Flags (L-T29-5 → M-T29-2)

### 7a. Observed behavior

```146:165:influora-api/src/main/java/com/influora/service/ReviewService.java
    private FlagReviewResponse saveFlag(Review review, FlagReviewRequest request) {
        String reason = TextSanitizer.sanitizePlainText(request.reason());
        ...
        ContentFlag flag = ContentFlag.userFlag(...);
        contentFlagRepository.save(flag);
        return new FlagReviewResponse(flag.getId(), flag.getStatus().name());
    }
```

Every POST creates a new `content_flags` row. No `existsByContentTypeAndContentIdAnd...` check. Schema (`V34__admin_tables.sql`) has `INDEX idx_flag_content (content_type, content_id)` but **no unique constraint**.

### 7b. Abuse scenario

Authenticated party on a completed deal:
1. `POST /creator/reviews/{reviewId}/flag` with `{"reason":"spam"}` × N
2. Each returns `201` with new `flagId`
3. Admin `FlagQueue` backlog inflates — operational DoS on moderators

`flagged_by = USER` enum does not record which user — cannot dedupe or rate-limit per flagger at DB layer without migration.

**Severity: MEDIUM (M-T29-2).** Recommend unique `(content_type, content_id, flagged_by_user_id)` or idempotency key in follow-up PR. Pair with M-T29-1 rate bucket.

---

## 8. Unthrottled Review/Flag Endpoints (L-T29-3 → M-T29-1)

### 8a. AuthRateLimitFilter gap

`bucketFor()` handles `creator-deliverable-write`, `brand-deliverable-review`, `contract-sign` — **not** `/creator/reviews`, `/brand/reviews`, or `/*/reviews/*/flag`.

```264:274:influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java
    private static boolean isCreatorDeliverableWritePath(String path) {
        return path.matches("/creator/deliverables/[^/]+/(upload|submit|metrics)");
    }
    private static boolean isBrandDeliverableReviewPath(String path) {
        return path.matches("/deliverables/[^/]+/(approve|revise)");
    }
```

No `isReviewPath()` matcher exists.

### 8b. Abuse scenarios

| Vector | Impact |
|---|---|
| Creator with N completed collabs spams 1-star reviews | Reputation noise; bounded by N deals |
| Unlimited flag POST on one `reviewId` | **Moderation queue flood** — higher impact |
| Automated script with stolen JWT | Same — no throttle speed bump |

Authenticated JWT required — not anonymous abuse. Still exploitable by any compromised or malicious authenticated account.

**Severity: MEDIUM (M-T29-1).** Recommend extending Task #25 bucket pattern:
- `POST /creator/reviews` → `"creator-review-write"` (10/min per `sub`)
- `POST /brand/reviews` → `"brand-review-write"` (10/min per `sub`)
- `POST /*/reviews/{id}/flag` → `"review-flag"` (5/min per `sub` or per `reviewId`)

---

## 9. Auth Boundary — Supplemental Probes

| Probe | Result |
|---|---|
| Expired / malformed JWT | **BLOCKED** — `JwtAuthenticationFilter` before controller |
| Missing `Authorization` header | **BLOCKED** — `401` |
| Creator without profile row | **BLOCKED** — `404 CREATOR_PROFILE_NOT_FOUND` (after `403` type check passes) |
| Brand without workspace | **BLOCKED** — `404 WORKSPACE_NOT_FOUND` |
| `stars: 0` / `stars: 99` | **BLOCKED** — `@Valid` + DB `chk_review_stars` |
| Oversized `text` (>1000) | **BLOCKED** — `@Size` validation `400` |
| Empty `collaborationId` | **BLOCKED** — `@NotBlank` `400` |

---

## 10. Contract Leak — Error Response Uniformity

| Error code | Leaks foreign metadata? |
|---|---|
| `COLLABORATION_NOT_FOUND` | **No** — generic message only |
| `REVIEW_NOT_FOUND` | **No** — same message for missing id vs foreign tenancy |
| `ALREADY_REVIEWED` | **No** — does not expose other reviewer's identity |
| `COLLABORATION_NOT_COMPLETED` | **No** — does not echo current status enum value |

Response DTO on success echoes `reviewerUserId` — only to the authenticated party who created the review (expected).

---

## 11. Schema Review — `V41__reviews.sql`

```sql
UNIQUE KEY uq_review_collab_reviewer (collaboration_id, reviewer_type)
CONSTRAINT chk_review_stars CHECK (stars >= 1 AND stars <= 5)
reviewer_user_id VARCHAR(26) NOT NULL  -- FK users(id)
hidden BOOLEAN DEFAULT FALSE           -- reserved; no write surface in V4
```

`content_flags.content_type` ENUM extended with `REVIEW` — aligns with `ContentFlagType.REVIEW`. No FK on `content_id` (by design — polymorphic moderation queue).

---

## 12. Test Execution

| Test Class | Authored | Executed (Kabir env) | Result |
|------------|----------|----------------------|--------|
| `ReviewServiceTest` | 12 | ❌ `mvn` not on PATH | Meera gate required |
| `TextSanitizerTest` | 11 | ❌ Not run | Shared regression suite |
| `CreatorReviewControllerTest` | 0 | — | Gap L-T29-3 |
| `BrandReviewControllerTest` | 0 | — | Gap L-T29-3 |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=ReviewServiceTest
```

Vikram reports **12/12 PASS** locally — Meera must confirm in CI/build env.

---

## Findings Register

| ID | Severity | Finding | Blocks M2? | Action |
|----|----------|---------|------------|--------|
| M-T29-1 | **MEDIUM** | No `AuthRateLimitFilter` bucket for review create or flag endpoints | No | Add `creator-review-write` / `brand-review-write` / `review-flag` buckets (Task #25 pattern extension) |
| M-T29-2 | **MEDIUM** | Unlimited duplicate flags per review; no per-user flagger id on `ContentFlag` | No | Unique constraint or idempotency; add `flagged_by_user_id` column in follow-up migration |
| L-T29-1 | LOW | Self-flag allowed (no `reviewerType` opposition check) | No | Optional: reject when `review.reviewerType == partyType` |
| L-T29-2 | LOW | `admin.types.ts` missing `'REVIEW'` in `contentType` union | No | Ananya admin cycle — FlagQueue filter/icon |
| L-T29-3 | LOW | No controller integration tests (cross-role 403, `@Valid`) | No | Optional follow-up |
| L-T29-4 | LOW | `ContentFlag.userFlag` omits flagger `user_id` — weak audit | No | Schema follow-up with M-T29-2 |
| L-T29-5 | INFO | `mvn` unavailable in red-team env | No | Meera confirms 12/12 |

**No P0 (Critical) or P1 (High) findings.**

---

## CEO §1.2 Compliance — Security Lens

| Policy rule | Security result |
|---|---|
| Rate only after `COMPLETED` | ✅ Strict equality gate |
| No anonymous reviews | ✅ `reviewer_user_id` from JWT, NOT NULL + FK |
| No double review per party | ✅ App + DB unique + race catch |
| Text sanitized | ✅ `TextSanitizer` on text + flag reason |
| Flag via `ContentFlag` pattern | ✅ `ContentFlagType.REVIEW` — no new moderation entity |
| Admin can hide (future) | ℹ️ `hidden` column reserved; no write surface in V4 (acceptable slice) |

---

## Pipeline Routing

```
Vikram T29 V4 ──✅ Kavya APPROVED──► ✅ Kabir P0-K1 PASS WITH FINDINGS ──► Meera M2 (12/12) ──► Priya sign-off
```

**Kabir verdict:** **PASS WITH FINDINGS** — route **Meera M2**. No Critical/High blockers. M-T29-1 and M-T29-2 are pre-prod hardening (same class as closed M-21-1); do not block frontend wiring or sprint integration.

**Unblocks:** Ananya `creator-reviews` / `brand-reviews` pages + `api.ts` client methods.

**Priya escalation:** None required. M-T29-1/M-T29-2 can land in a focused rate-limit follow-up PR (extend Task #25 filter) before production deploy of review UI.

---

*Kabir Singh, Offensive Security / Red-Team Lead — Sage Digital*
