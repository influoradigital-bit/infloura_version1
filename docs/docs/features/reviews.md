# Feature: Reviews

**Business Purpose** — After a completed collaboration, each side rates the other (1–5 stars + text). Brand→creator reviews feed the creator's public portfolio and average rating (trust signal for discovery); creator→brand reviews inform other creators. Reviews can be flagged for moderation.

**Who uses it** — Brands and creators (rate each other), admins (moderate flagged reviews).

## User Roles
Brand (review creator), Creator (review brand). One review per side per collaboration.

## Permissions
Only participants of a **COMPLETED** collaboration may review. Ownership scoped by role.

## Business Flow
```
Collaboration COMPLETED → each party posts one review (stars + text) → visible on profiles/portfolio
  → either party can flag a review → admin moderation queue (ContentFlag)
```

## Frontend
- **Pages**: `brand-reviews`, `creator-reviews`.
- **Components**: `shared/review-card`, `shared/collaboration-reviews-panel`, `shared/star-rating-input`.

## Backend
- **Controllers**: `BrandReviewController` (`/brand/reviews`), `CreatorReviewController` (`/creator/reviews`).
- **Service**: `ReviewService`.

## Database
`reviews` (V43; `reviewer_type`, `stars` CHECK 1–5, `UNIQUE(collaboration_id, reviewer_type)`), `content_flags` (V43 widens to include REVIEW). See [../database.md](../database.md).

## APIs
`POST /brand/reviews`, `GET /brand/reviews/received`, `POST /brand/reviews/{id}/flag`; mirrored `/creator/reviews`.

## AI
Not involved.

## Notifications
None specific (surfaced in profile/portfolio).

## Dependencies
- **Depends on**: collaborations (must be COMPLETED), moderation (flags).
- **Depended on by**: portfolio (testimonials/avg rating), discovery (avg rating).

## Connected Files
`BrandReviewController`, `CreatorReviewController`, `ReviewService`, `domain/entity/{Review,ContentFlag}`, `web/dto/review/*`.

## Execution Flow
```
Create: POST /brand/reviews → ReviewService.createReview (resolve owned collaboration, must be COMPLETED,
  one review per side) → persist → surfaces on creator portfolio
Flag: POST /{role}/reviews/{id}/flag → ContentFlag.userFlag(REVIEW, ...) → admin queue
```

## Error Handling
`COLLABORATION_NOT_FOUND` (404), `COLLABORATION_NOT_COMPLETED` (409), `ALREADY_REVIEWED` (409, DB-race safe), `ALREADY_FLAGGED` (409), `INVALID_REQUEST` (400, missing reason).

## Security
Reviewer identity always stored (no anonymous); principal-scoped list queries prevent cross-tenant leakage; text sanitized.

## Performance
Small scoped queries; average rating computed from BRAND reviews (null if none — not fabricated).

## Testing
Review + collaboration-reviews-panel tests. Regression risks: completed-gate, one-per-side uniqueness.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~85%
- **Known issues**: none material.
- **Last verified**: 2026-07-15
