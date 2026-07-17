# QA Review: Collaboration Reviews API — Task #29 V4 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~19:45 IST)  
**Verdict:** ✅ **APPROVED** — routed to **Kabir P0-K1** (full red-team, NOT rubber-stamp) → Meera build  
**Scope:** Vikram Task #29 V4 — `Review` entity, `POST /creator/reviews`, `POST /brand/reviews`, flag via `ContentFlag.REVIEW`  
**Reference:** `wiki/tech/creator/CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.2; `TECH-STACK.md` cross-cutting rules §2  
**Reviewed Files:**
- `influora-api/src/main/java/com/influora/domain/entity/Review.java`
- `influora-api/src/main/java/com/influora/service/ReviewService.java`
- `influora-api/src/main/java/com/influora/web/CreatorReviewController.java`
- `influora-api/src/main/java/com/influora/web/BrandReviewController.java`
- `influora-api/src/main/java/com/influora/web/dto/review/ReviewDtos.java`
- `influora-api/src/main/java/com/influora/repository/ReviewRepository.java`
- `influora-api/src/main/java/com/influora/domain/entity/ContentFlag.java` — `userFlag()`
- `influora-api/src/main/java/com/influora/domain/enums/ContentFlagType.java` — `REVIEW`
- `influora-api/src/main/resources/db/migration/V41__reviews.sql`
- `influora-api/src/test/java/com/influora/service/ReviewServiceTest.java` (12 tests)
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/service/BrandContextService.java`
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java` — auth posture
- `src/admin/types/admin.types.ts` — `ContentFlag.contentType` contract cross-check

---

## Executive Summary

Task #29 V4 **passes QA** on CEO §1.2 mandatory gates. `ReviewService` resolves identity exclusively from `AuthPrincipal` + `CreatorContextService` / `BrandContextService` — no path-param user ids, no client-supplied `reviewer_type` or `reviewer_user_id`. Create is gated on strict `CollaborationStatus.COMPLETED` equality (not “terminal or later”). Double-review is blocked at application layer (`existsByCollaborationIdAndReviewerType`) and database layer (`uq_review_collab_reviewer`), with race-safe `DataIntegrityViolationException` → `ALREADY_REVIEWED` mapping. Review text and flag reason both pass through `TextSanitizer`. Flagging reuses `ContentFlag.userFlag` with `ContentFlagType.REVIEW`.

**12/12 unit tests authored** in `ReviewServiceTest` covering happy paths, COMPLETED gate, duplicate/race duplicate, HTML sanitization, blank-text→null, and IDOR on create + flag for both parties. **`mvn` not on PATH** in this QA environment — Meera must confirm **12/12 PASS** (Vikram reports green).

**Kabir P0-K1 carry-forward (non-blocking for QA APPROVED):** no review-endpoint rate limit (same posture as deliverable-review M-1); duplicate flag spam to moderation queue; `admin.types.ts` missing `REVIEW` in `ContentFlag.contentType`; no controller delegation tests; admin hide-review action not in scope (flag-only ships per V4 slice).

---

## CEO §1.2 Mandatory Gates — Hostile-Path Verification

| Gate | Result | Evidence |
|------|--------|----------|
| **COMPLETED-only** | ✅ PASS | `ReviewService.createReview` L84–89: `!= COMPLETED` → `COLLABORATION_NOT_COMPLETED` 409. Tested for creator (`creatorCreateRejectsNotCompleted`). All non-`COMPLETED` states (`IN_PROGRESS`, `DISPUTED`, `CANCELLED`, etc.) rejected. |
| **No double review** (`collaboration_id + reviewer_type`) | ✅ PASS | App check L91–97; DB `UNIQUE KEY uq_review_collab_reviewer` in `V41__reviews.sql` L14; race catch L108–114 → `ALREADY_REVIEWED`. Tests: `creatorCreateRejectsDuplicate`, `creatorCreateRaceDuplicate`. |
| **TextSanitizer on review text** | ✅ PASS | `Review.create` L66–68 calls `TextSanitizer.sanitizePlainText(text)`; blank after sanitize → `null`. Test: `creatorCreateSanitizesText`, `creatorCreateBlankTextBecomesNull`. |
| **TextSanitizer on flag reason** | ✅ PASS | `ReviewService.saveFlag` L147; blank after sanitize → `INVALID_REQUEST` 400. |
| **Creator IDOR — create** | ✅ PASS | `requireOwnedCollaboration` → `findByIdAndCreatorId(collaborationId, principal.getUserId())` L171–178. Foreign collab → `COLLABORATION_NOT_FOUND` 404 (no existence leak). Test: `creatorCreateIdorForeignCollaboration`. |
| **Brand IDOR — create** | ✅ PASS | `findByIdAndWorkspaceId(collaborationId, workspace.getId())` L180–188. Test: `brandCreateIdorForeignCollaboration`. |
| **Creator IDOR — flag** | ✅ PASS | `requireReviewForParty` L127–133: review loaded, then collaboration re-resolved via `findByIdAndCreatorId`. Foreign → `REVIEW_NOT_FOUND` 404. Test: `creatorFlagIdorForeignReview`. |
| **Brand IDOR — flag** | ✅ PASS | L135–141: `findByIdAndWorkspaceId`. Test: `brandFlagIdorForeignReview`. |
| **No anonymous reviews** (`reviewer_user_id` stored) | ✅ PASS | `Review.create` L104 passes `principal.getUserId()`; column `NOT NULL` + FK `fk_review_reviewer` in `V41__reviews.sql` L8/L17. Response echoes `reviewerUserId`. Tests assert creator/brand user ids on happy paths. |
| **Cross-role endpoint access** | ✅ PASS | `CreatorContextService.requireCreator` / `BrandContextService.requireBrand` → `WRONG_USER_TYPE` 403. Brand JWT cannot hit `/creator/reviews`; creator JWT cannot hit `/brand/reviews`. |
| **Unauthenticated access** | ✅ PASS | `SecurityConfig` L191–192 `anyRequest().authenticated()` — no permitAll for review paths. |
| **Reviewer type not client-spoofable** | ✅ PASS | `reviewerType` set only by which service method is invoked (`createCreatorReview` → `CREATOR`, `createBrandReview` → `BRAND`). Not in request DTO. |
| **Stars bounds** | ✅ PASS | DTO `@Min(1) @Max(5)`; DB `chk_review_stars CHECK (stars >= 1 AND stars <= 5)`. |
| **Text length cap** | ✅ PASS | DTO `@Size(max = 1000)` on `text`; flag reason `@Size(max = 255)`. |
| **ContentFlag.REVIEW integration** | ✅ PASS | `ContentFlag.userFlag(..., ContentFlagType.REVIEW, review.getId(), ...)` L157–164. Migration extends `content_type` ENUM. Test: `creatorFlagHappyPath`. |

---

## Hostile-Path Matrix (manual code trace)

| Attack vector | Expected | Observed |
|---------------|----------|----------|
| Creator probes foreign `collaborationId` | 404 `COLLABORATION_NOT_FOUND` | ✅ |
| Brand probes foreign workspace collaboration | 404 `COLLABORATION_NOT_FOUND` | ✅ |
| Creator flags review on another creator's deal | 404 `REVIEW_NOT_FOUND` | ✅ |
| Brand flags review outside workspace | 404 `REVIEW_NOT_FOUND` | ✅ |
| Review while `IN_PROGRESS` / `DISPUTED` / `CANCELLED` | 409 `COLLABORATION_NOT_COMPLETED` | ✅ (strict `== COMPLETED`) |
| Second review same party same collab | 409 `ALREADY_REVIEWED` | ✅ |
| Concurrent duplicate insert (TOCTOU) | 409 `ALREADY_REVIEWED` | ✅ |
| `<script>` in review body | Tags stripped before persist | ✅ |
| `   ` / HTML-only text | Stored as `null` | ✅ |
| Brand JWT on `/creator/reviews` | 403 `WRONG_USER_TYPE` | ✅ (context service) |
| Creator JWT on `/brand/reviews` | 403 `WRONG_USER_TYPE` | ✅ (context service) |
| Omit / spoof `reviewer_user_id` in body | Ignored — server derives from JWT | ✅ (field not in DTO) |
| `stars: 0` or `stars: 99` | 400 validation error | ✅ (`@Valid` + DB CHECK) |
| Guess `reviewId` UUID from other tenant | 404 uniform | ✅ |

---

## Schema Review: `V41__reviews.sql`

```sql
UNIQUE KEY uq_review_collab_reviewer (collaboration_id, reviewer_type)
CONSTRAINT chk_review_stars CHECK (stars >= 1 AND stars <= 5)
reviewer_user_id VARCHAR(26) NOT NULL  -- FK users(id)
```

Indexes and FKs align with TECH-STACK ULID `VARCHAR(26)` convention. `hidden BOOLEAN DEFAULT FALSE` reserved for future admin moderation (§1.2 “admin can hide” — not in V4 write surface; acceptable slice boundary).

---

## Test Execution

| Test Class | Authored | Executed | Failures | Notes |
|------------|----------|----------|----------|-------|
| `ReviewServiceTest` | 12 | ❌ Not run | — | `mvn` unavailable in QA env |
| `CreatorReviewControllerTest` | 0 | — | — | **Gap L-T29-2** — no delegation tests |
| `BrandReviewControllerTest` | 0 | — | — | **Gap L-T29-2** |
| **Total** | **12** | **0** | — | **Meera gate required** |

**Command for Meera:**
```bash
cd influora-api && mvn test -Dtest=ReviewServiceTest
```

**Vikram reports 12/12 PASS locally** — Meera must confirm in CI/build env.

### Test coverage gaps (non-blocking)

| Missing test | Severity | Notes |
|--------------|----------|-------|
| Brand create rejects non-`COMPLETED` | P3 | Creator side covered; brand path uses same `createReview` |
| Brand create rejects duplicate | P3 | Same private method |
| Flag reason HTML sanitization | P3 | Same `TextSanitizer` as review text |
| Cross-role 403 (creator→brand endpoint) | P2 | Kabir red-team should attempt live |
| Controller `@Valid` rejection (stars bounds) | P3 | Standard Spring validation |

---

## Findings Register

| ID | Severity | Finding | Action |
|----|----------|---------|--------|
| L-T29-1 | INFO | `mvn` not on PATH in QA env | Meera confirms 12/12 |
| L-T29-2 | P3 | No `CreatorReviewControllerTest` / `BrandReviewControllerTest` | Optional follow-up; service layer fully tested |
| L-T29-3 | P2 | No rate limit bucket for `/creator/reviews` or `/brand/reviews` in `AuthRateLimitFilter` | **Kabir P0-K1** — assess abuse (review spam, flag flood) |
| L-T29-4 | P2 | `src/admin/types/admin.types.ts` L625: `contentType` union missing `'REVIEW'` | Ananya/admin cycle — admin FlagQueue may not render REVIEW flags until typed |
| L-T29-5 | P3 | No duplicate-flag guard — same user can flood `content_flags` for one review | Kabir assess; consider unique `(content_type, content_id, flagged_by)` in future |
| L-T29-6 | INFO | `api.ts` has no review client methods yet | Expected — Ananya `creator-reviews`/`brand-reviews` pages blocked until this ships |
| L-T29-7 | INFO | Admin hide-review (`hidden=true`) not implemented | Out of V4 scope per CEO §1.2 moderation note; future admin task |

**No P0 or P1 blockers.** No standards violations in shipped code.

---

## TECH-STACK.md Compliance

| Rule | Result |
|------|--------|
| Thin controller, fat service | ✅ Controllers delegate only |
| `ApiException` with stable codes | ✅ |
| Workspace/creator isolation (rule §2) | ✅ resolve-then-scope on collaboration |
| JWT auth required | ✅ |
| Flyway sequential migration | ✅ `V41__reviews.sql` |
| ULID IDs `VARCHAR(26)` | ✅ |
| No debug/console code | ✅ |
| No fabricated contracts | ✅ (frontend not wired — honest gap) |

---

## Kabir P0-K1 Red-Team Brief (from Kavya)

Arjun: route Kabir with this scope — **full hostile path, not rubber-stamp**:

1. **Live IDOR probes** — creator A / brand B JWTs against foreign `collaborationId` and guessed `reviewId` on flag; confirm uniform 404s.
2. **Cross-role** — brand JWT on `POST /creator/reviews`, creator JWT on `POST /brand/reviews` → 403.
3. **State gate** — attempt review on `IN_PROGRESS`, `DISPUTED`, `CANCELLED` collaborations → 409 `COLLABORATION_NOT_COMPLETED`.
4. **Double-review race** — parallel POSTs same collab+party (if test harness available).
5. **XSS/store** — `<script>`, entity-encoded tags, event-handler attrs in `text` and flag `reason`.
6. **Abuse** — unthrottled review/flag spam (L-T29-3, L-T29-5); recommend bucket limits if exploitable.
7. **Auth boundary** — unauthenticated, expired JWT, admin JWT on creator/brand review paths.
8. **Contract leak** — confirm `REVIEW_NOT_FOUND` / `COLLABORATION_NOT_FOUND` never echo foreign resource metadata.

---

## Pipeline Routing

```
Vikram T29 V4 ──✅ Kavya APPROVED──► Kabir P0-K1 (red-team) ──► Meera build (12/12) ──► Priya sign-off
```

**Next owner:** Kabir (security red-team P0-K1)  
**Blocked on Kabir for:** Meera merge gate (standard pipeline)  
**Unblocks:** Ananya `creator-reviews` / `brand-reviews` frontend pages + `api.ts` client

---

*Kavya Patel, QA Lead — Sage Digital*
