# Feature: Marketplace / Creator Discovery

**Business Purpose** — Lets brands find the right creators: full-text + faceted search, featured sections, AI-lite suggestions for a campaign, similar-creator recommendations, shortlisting, and inviting to campaigns. This is the top of the funnel for every collaboration.

**Who uses it** — Brands (search/save/invite). Creators are the searchable inventory.

## User Roles
Brand (search/save/invite), Creator (indexed profiles).

## Permissions
All discovery endpoints require a brand workspace (`requireBrandWorkspace`). Only **discoverable** profiles are surfaced. Saved-state is workspace-scoped.

## Business Flow
```
Brand → Discover → search/filter (niche, city, followers, rate, engagement, verified)
  → view profile (scores, avg rating, completed campaigns) → save (shortlist) → invite to campaign
```

## Frontend
- **Pages**: `brand-discover`, `brand-creator-profile`.
- **Components**: `brand/discover/creator-discovery` (search bar, filter sheet, grid/list), `ui/creator-card`, `3d/DiscoverCanvas`.
- **API**: `api.creators.*`.

## Backend
- **Controller**: `CreatorController` (`/creators`).
- **Services**: `CreatorDiscoveryService` (search/facets/invite/save/featured/suggest/similar/public-profile), `CreatorProfileSpecifications` (null-safe JPA criteria), scoring services (`service/scoring/*`).

## Database
`creator_profiles` (V6, +V32), `platform_stats` (V6), `saved_creators` (V6), `featured_creators` (V20260709163000), `creator_scores` (V22), seed creators (V7). See [../database.md](../database.md).

## APIs
`GET /creators`, `/creators/search` (+facets), `/creators/featured`, `POST /creators/suggestions`, `GET /creators/{username}/similar`, `GET /creators/{id}` / `/profile/{usernameOrId}`, `POST /creators/{id}/save`, `POST /creators/{id}/invite`.

## AI
`suggest` is heuristic (keyword niche inference), **not** the LLM. Displayed scores (quality/fake-follower/rate) come from `ScoreCalculationJob` (computed daily); brand-safety score is currently NULL (not wired).

## Notifications
Invite creates a `Collaboration` (INVITED) → `creator.proposal_received`-style notification downstream.

## Dependencies
- **Depends on**: creator profiles/scoring, Meta analytics (feeds scores), campaigns (invite target).
- **Depended on by**: collaborations/deals (invite path).

## Connected Files
`CreatorController`, `CreatorDiscoveryService`, `CreatorProfileSpecifications`, `service/scoring/*`, `domain/entity/{CreatorProfile,SavedCreator,FeaturedCreator,CreatorScore}`; frontend `creator-discovery`.

## Execution Flow
```
Search → api.creators.search → GET /creators/search → CreatorController
  → CreatorDiscoveryService.search (requireBrandWorkspace, Specifications.combine + discoverable(), facets over ≤5000)
  → results with workspace-scoped saved state
Invite → POST /creators/{id}/invite → resolve discoverable profile + owned campaign → Collaboration.invite (dedup)
```

## Error Handling
`CAMPAIGN_NOT_FOUND` (404, foreign campaign), `COLLABORATION_EXISTS` (409, dup invite). Featured falls back to algorithmic sections if no curated rows.

## Security
Only discoverable profiles surfaced; no PII beyond public stats; cross-tenant campaign ids for invite return 404.

## Performance
Facets computed over a bounded ≤5000-profile set; limit clamped ≤100; similar limited ≤20. Scores are precomputed (read-only at query time).

## Testing
Discovery service tests. Regression risks: spec composition, saved-state scoping, invite dedup.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~82%
- **Known issues**: brand-safety score not wired (NULL); `audienceMatch` in quality score hardcoded; suggestions are heuristic, not LLM.
- **Last verified**: 2026-07-15
