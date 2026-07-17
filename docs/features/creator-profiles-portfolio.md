# Feature: Creator Profiles & Portfolio

**Business Purpose** — A creator's identity and shop window. The **profile** holds discovery/matching data (categories, rates, followers, verification) and tax identity; the **portfolio** is a public `@username` page showcasing completed collaborations, reviews, stats, and badges to win new brand deals.

**Who uses it** — Creators (edit own), brands and the public (view portfolios), admins (moderation).

## User Roles
Creator (edit self), Brand/Guest (view public portfolio), Admin (moderate/suspend).

## Permissions
Self-edit only (`requireCreatorProfile`). Public portfolio is unauthenticated. Suspension/moderation is admin-only.

## Business Flow
```
Creator edits profile (username, bio, categories, rates, avatar/cover) → completeness score
Public portfolio (@username): completed collaborations + reviews + computed stats/badges + rate card
Admin: review application (APPROVED default) / suspend / reinstate
```

## Frontend
- **Pages**: `creator-profile`, `creator-portfolio-editor`, `creator-portfolio-public`; admin `admin/components/users/CreatorProfile`.
- **Components**: `3d/PortfolioCanvas`, `creator/TaxIdentityForm`.
- **Route**: `/:handle` catch-all serves `@username` portfolios.

## Backend
- **Controllers**: `MeCreatorProfileController` (`/me/creator-profile`), `PortfolioController`.
- **Services**: `CreatorProfileService`, `service/portfolio/PortfolioService`, `CreatorInvoiceCodeService` (tax code).

## Database
`creator_profiles` (V6, +V32 username/portfolio, +V38 moderation, +V20260715120000 tax identity), `content_flags` (moderation). See [../database.md](../database.md).

## APIs
`GET/PATCH /me/creator-profile`, `GET /portfolio/{username}` (public), `POST /portfolio/{username}/contact` (public), `GET/PATCH /me/portfolio`, `POST /me/portfolio/{sync,cover}`, `GET /me/portfolio/analytics`.

## AI
Not directly (content may be scored by brand-safety indirectly).

## Notifications
`PortfolioContactEvent` on public contact form (**note: no listener currently**, see [../known-limitations.md](../known-limitations.md)).

## Dependencies
- **Depends on**: collaborations/reviews (portfolio content), R2 (cover), Meta (audience cities).
- **Depended on by**: discovery (profile data), invoicing (tax identity/`creator_invoice_code`).

## Connected Files
`MeCreatorProfileController`, `PortfolioController`, `CreatorProfileService`, `PortfolioService`, `domain/entity/CreatorProfile`, `ContentFlag`; frontend portfolio pages.

## Execution Flow
```
Public portfolio: GET /portfolio/{username} → PortfolioService (completed collabs, reviews, PortfolioStats
  [real on-time rate from deliverable deadlines], badges, top cities, rate card, visibility gating)
Edit: PATCH /me/creator-profile → username normalize/uniqueness + rate-range check + completeness score
```

## Error Handling
`INVALID_USERNAME` (400), `USERNAME_TAKEN` (409), `INVALID_RATE_RANGE` (400). Cover upload: 10MB cap, MIME sniff, malware scan.

## Security
Self-scoped edits; public portfolio exposes only creator-approved data (visibility settings). Cover stored as R2 key, returned as presigned GET. Note the recurring scoping trap: `collaborations.creator_id → users.id` (not `creator_profiles.id`).

## Performance
Portfolio stats computed on read (bounded to 12 completed collaborations); `@Cacheable` invoice code lookups.

## Testing
Profile/portfolio service tests. Regression risks: username uniqueness, completeness scoring, on-time-rate computation.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~82%
- **Known issues**: `PortfolioContactEvent` has no listener; some creator profile surfaces mock-backed.
- **Last verified**: 2026-07-15
