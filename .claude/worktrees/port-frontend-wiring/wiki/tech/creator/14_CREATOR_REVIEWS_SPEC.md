# Creator Reviews Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya  
> **Policy:** `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.2 (Swapnil-approved 2026-07-09)  
> **Shipped:** Task #29 V4 (backend), Task #33 A4 (frontend write path)

---

## 1. Policy Summary (CEO §1.2)

| Rule | Implementation |
|------|----------------|
| Both parties may rate each other | Same `reviews` table; `reviewer_type` = `CREATOR` or `BRAND` |
| **Only after `COMPLETED`** | Strict equality gate in `ReviewService` — not “terminal or later” |
| Rating shape | 1–5 stars + optional text (max 1000 chars) |
| **No anonymous reviews** | `reviewer_user_id` stored and returned; visible to reviewed party |
| **No double review** | Unique `(collaboration_id, reviewer_type)` — app check + DB constraint + race catch |
| Text sanitization | `TextSanitizer.sanitizePlainText` on review body and flag reason |
| Moderation | Reuse admin `ContentFlag` with `ContentFlagType.REVIEW` — no new moderation entity |
| Admin hide | `reviews.hidden` column reserved; admin write surface deferred |

---

## 2. Review Flow Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COLLABORATION REVIEW FLOW (V4)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Collaboration reaches COMPLETED                                             │
│         │                                                                    │
│         ├──────────────────────┬──────────────────────┐                     │
│         ▼                      ▼                      ▼                     │
│  Creator rates Brand     Brand rates Creator    Either party flags           │
│  POST /creator/reviews   POST /brand/reviews    POST /*/reviews/{id}/flag   │
│         │                      │                      │                     │
│         ▼                      ▼                      ▼                     │
│  reviews row             reviews row             content_flags row          │
│  reviewer_type=CREATOR   reviewer_type=BRAND     content_type=REVIEW        │
│                                                                              │
│  Admin moderation queue (existing FlagQueue) ──► hide review (future)       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**V4 scope:** write-only (create + flag). **Not shipped:** `GET` received-reviews list, admin hide endpoint, review rate limits.

---

## 3. Database Schema

### 3.1 Migration

File: `influora-api/src/main/resources/db/migration/V43__reviews.sql`

```sql
CREATE TABLE reviews (
  id                  VARCHAR(26) PRIMARY KEY,
  collaboration_id    VARCHAR(26) NOT NULL,
  reviewer_type       ENUM('CREATOR','BRAND') NOT NULL,
  reviewer_user_id    VARCHAR(26) NOT NULL,
  stars               TINYINT NOT NULL,
  review_text         TEXT,
  hidden              BOOLEAN NOT NULL DEFAULT FALSE,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_review_collab_reviewer (collaboration_id, reviewer_type),
  CONSTRAINT chk_review_stars CHECK (stars >= 1 AND stars <= 5)
);
```

`content_flags.content_type` ENUM extended with `REVIEW` in the same migration.

### 3.2 Entity (shipped)

```java
@Entity
@Table(name = "reviews")
public class Review {
    @Id private String id;                          // ULID
    private String collaborationId;
    @Enumerated(EnumType.STRING) private ReviewerType reviewerType;
    private String reviewerUserId;                    // JWT-derived; NOT NULL
    private int stars;                                // 1–5
    private String reviewText;                        // sanitized; nullable
    private boolean hidden;                           // admin moderation (future)
    private Instant createdAt;
    private Instant updatedAt;
}
```

**Prod note:** Hibernate maps plain `int stars` → `INTEGER`; migration uses `TINYINT`. Add `@Column(columnDefinition = "TINYINT")` on `Review.stars` (or forward-only migration widening column) before `ddl-auto=validate` prod boot.

### 3.3 ReviewerType Enum

```java
public enum ReviewerType {
    CREATOR,  // creator rates the brand
    BRAND     // brand rates the creator
}
```

---

## 4. State Gates

### 4.1 Collaboration status gate

| `Collaboration.status` | Can create review? |
|------------------------|-------------------|
| `COMPLETED` | ✅ Yes |
| `IN_PROGRESS`, `DISPUTED`, `CANCELLED`, `REVIEW_PENDING`, etc. | ❌ `409 COLLABORATION_NOT_COMPLETED` |

Gate location: `ReviewService.createReview()` — strict `!= COMPLETED` check.

**Rationale:** Prevents leverage/retaliation during active negotiation or deliverable dispute (CEO §1.2).

### 4.2 Double-review gate

| Condition | Result |
|-----------|--------|
| First review for `(collaboration_id, reviewer_type)` | ✅ `201 Created` |
| Duplicate (same party, same collab) | `409 ALREADY_REVIEWED` |
| Concurrent duplicate (TOCTOU) | `409 ALREADY_REVIEWED` via `DataIntegrityViolationException` |

Creator and brand each get **one** review slot per collaboration (different `reviewer_type` values).

### 4.3 Identity / tenancy gates

| Endpoint | Identity resolution |
|----------|---------------------|
| `POST /creator/reviews` | `CreatorContextService.requireCreatorProfile` → `findByIdAndCreatorId(collaborationId, principal.getUserId())` |
| `POST /brand/reviews` | `BrandContextService.requireBrandWorkspace` → `findByIdAndWorkspaceId(collaborationId, workspace.getId())` |
| `POST /creator/reviews/{id}/flag` | Load review → re-scope collaboration via creator join |
| `POST /brand/reviews/{id}/flag` | Load review → re-scope collaboration via brand workspace join |

Foreign probes → uniform `404 COLLABORATION_NOT_FOUND` or `404 REVIEW_NOT_FOUND` (no existence oracle).

Cross-role access → `403 WRONG_USER_TYPE`.

---

## 5. API Endpoints (V4 — shipped)

Base path: `/api/v1` (Spring `context-path`).

### 5.1 Create review — creator

```
POST /api/v1/creator/reviews
Authorization: Bearer <creator-jwt>

Request:
{
  "collaborationId": "01H...",
  "stars": 5,
  "text": "Great brand to work with — clear brief and fast approvals."
}

Response 201:
{
  "success": true,
  "data": {
    "id": "01H...",
    "collaborationId": "01H...",
    "reviewerType": "CREATOR",
    "reviewerUserId": "01H...",
    "stars": 5,
    "text": "Great brand to work with — clear brief and fast approvals.",
    "createdAt": "2026-07-09T14:30:00Z"
  }
}
```

**Controller:** `CreatorReviewController`  
**Service:** `ReviewService.createCreatorReview()`

### 5.2 Create review — brand

```
POST /api/v1/brand/reviews
Authorization: Bearer <brand-jwt>

Request/response: same shape as §5.1; `reviewerType` = `BRAND`.
```

**Controller:** `BrandReviewController`  
**Service:** `ReviewService.createBrandReview()`

### 5.3 Flag review for moderation

```
POST /api/v1/creator/reviews/{reviewId}/flag
POST /api/v1/brand/reviews/{reviewId}/flag
Authorization: Bearer <party-jwt>

Request:
{
  "reason": "Inaccurate claims about deliverable quality."
}

Response 201:
{
  "success": true,
  "data": {
    "flagId": "01H...",
    "status": "PENDING"
  }
}
```

Creates `ContentFlag` via `ContentFlag.userFlag()`:
- `contentType` = `REVIEW`
- `contentId` = `review.id`
- `contentPreview` = first 200 chars of sanitized `review_text`
- `reason` = sanitized flag reason (required; blank → `400 INVALID_REQUEST`)

---

## 6. DTO Validation

| Field | Create | Flag |
|-------|--------|------|
| `collaborationId` | `@NotBlank` | — |
| `stars` | `@NotNull @Min(1) @Max(5)` | — |
| `text` | `@Size(max = 1000)` optional | — |
| `reason` | — | `@NotBlank @Size(max = 255)` |

`reviewer_type` and `reviewer_user_id` are **never** in request bodies — server-derived only.

---

## 7. ContentFlag Moderation Integration

### 7.1 Flag creation path

```java
ContentFlag flag = ContentFlag.userFlag(
    Ulids.newUlid(),
    ContentFlagType.REVIEW,
    review.getId(),
    preview,      // truncated review_text
    reason);
contentFlagRepository.save(flag);
```

### 7.2 Admin queue

Flagged reviews appear in the existing admin `FlagQueue` moderation surface. `content_type = REVIEW` must be added to `src/admin/types/admin.types.ts` `ContentFlag.contentType` union (carry-forward L-T29-2).

### 7.3 Admin hide (deferred — not V4)

CEO §1.2: admin can hide a flagged review pending review. V4 ships `hidden` column default `false` only. Future admin endpoint:

```
POST /api/v1/admin/reviews/{id}/hide
```

Sets `reviews.hidden = true`; list endpoints must filter `hidden = false` for party-facing reads.

---

## 8. Error Codes

| Code | HTTP | When |
|------|------|------|
| `COLLABORATION_NOT_COMPLETED` | 409 | Status ≠ `COMPLETED` |
| `ALREADY_REVIEWED` | 409 | Duplicate or race duplicate |
| `COLLABORATION_NOT_FOUND` | 404 | Foreign collaboration (create) |
| `REVIEW_NOT_FOUND` | 404 | Foreign review (flag) |
| `WRONG_USER_TYPE` | 403 | Cross-role endpoint access |
| `CREATOR_PROFILE_NOT_FOUND` | 404 | Creator without profile |
| `INVALID_REQUEST` | 400 | Blank flag reason after sanitize |

---

## 9. Frontend (Task #33 A4 — shipped)

| Route | Component | Live behavior |
|-------|-----------|---------------|
| `/creator/reviews` | `creator-reviews.tsx` + `CollaborationReviewsPanel` | Rate tab: `deals.list('creator', 'completed')` + `POST /creator/reviews` |
| `/brand/reviews` | `brand-reviews.tsx` + shared panel | Rate tab: `deals.list('brand', 'completed')` + `POST /brand/reviews` |

**Received-reviews tab:** honest `NOT_IMPLEMENTED` in live mode — `api.*Reviews.listReceived()` rejects with `NOT_IMPLEMENTED`; amber banner cites missing `GET /{role}/reviews/received`.

**Client API (`src/lib/api.ts`):**
- `creatorReviews.create({ collaborationId, stars, text? })`
- `brandReviews.create(...)` — same payload
- `creatorReviews.flag(reviewId, { reason })` / `brandReviews.flag(...)` — wired, no UI in A4

**UX carry-forward (non-blocking):** no nav links to reviews pages (L-T33-1); `reviewedIds` session-only (L-T33-2).

---

## 10. Security Requirements

| Requirement | Status (V4) |
|-------------|-------------|
| IDOR on create/flag | ✅ Closed — join-scoped collaboration |
| COMPLETED-only | ✅ Closed — strict equality |
| Double-review race | ✅ Closed — DB unique + DIVE catch |
| XSS on text/reason | ✅ Closed — `TextSanitizer` |
| Cross-role endpoints | ✅ Closed — context services |
| Rate limits on review/flag POST | ⚠️ **Pre-prod** — M-T29-1: add `creator-review-write` / `brand-review-write` / `review-flag` buckets |
| Duplicate flag spam | ⚠️ **Pre-prod** — M-T29-2: consider unique constraint or idempotency |

Reference: `wiki/errors/creator-reviews-T29-kabir-redteam.md`

---

## 11. Tests (shipped)

| Class | Count | Coverage |
|-------|-------|----------|
| `ReviewServiceTest` | 12/12 | Happy paths, COMPLETED gate, duplicate/race, sanitize, IDOR create+flag (both parties) |

**Meera gate command:**
```bash
cd influora-api && mvn test -Dtest=ReviewServiceTest
```

---

## 12. Future Waves (out of V4 scope)

| Item | Priority | Notes |
|------|----------|-------|
| `GET /creator/reviews/received` | P1 | List reviews about authenticated creator; `hidden = false` filter |
| `GET /brand/reviews/received` | P1 | List reviews about brand workspace |
| `GET /creator/reviews/pending` | P2 | Completed collabs not yet reviewed by caller |
| Admin hide endpoint | P1 | `reviews.hidden = true` |
| Review rate limits | P0 pre-prod | Extend `AuthRateLimitFilter` (Task #25 pattern) |
| Duplicate-flag guard | P1 | Per-user flagger id + unique constraint |
| Reject self-flag | P3 | Optional: `review.reviewerType != partyType` |

---

## 13. Definition of Done (V4)

- [x] `Review` entity + `V43__reviews.sql` migration
- [x] `POST /creator/reviews` + `POST /brand/reviews`
- [x] `POST /*/reviews/{id}/flag` via `ContentFlag.REVIEW`
- [x] COMPLETED gate + double-review prevention + `TextSanitizer`
- [x] IDOR tests (12/12 `ReviewServiceTest`)
- [x] Frontend write path (`creator-reviews` / `brand-reviews` pages)
- [x] Kavya APPROVED + Kabir PASS WITH FINDINGS + Meera 12/12
- [ ] Pre-prod: review rate limits (M-T29-1)
- [ ] Pre-prod: `Review.stars` column Hibernate alignment
- [ ] Future: received-reviews GET + admin hide

---

*Priya Sharma (CTO) — spec authored 2026-07-09. Aligned to shipped Task #29 V4 code.*
