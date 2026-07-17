# Feature: TrendSpark (Trend Nudge Engine)

**Business Purpose** — An anti-spam nudge engine that suggests timely campaigns to brands based on current Indian cultural/festival/sports trends. When a live trend overlaps a brand's themes, TrendSpark surfaces a soft, well-phrased suggestion (post your own content, or buy a catalog video) — and stays silent otherwise. It drives campaign creation without nagging.

**Who uses it** — Brands (see nudges on the dashboard). Fed by an external n8n pipeline.

## User Roles
Brand.

## Permissions
Nudge is workspace-scoped (`requireBrandWorkspace`). Below threshold / no trend / no profile → silence (204).

## Business Flow
```
n8n (06:00 IST) pulls trends → tags themes + campaign_type → writes trends rows (Java read-only)
Brand dashboard load → GET /brand/trendspark/nudge → pick best trend by theme overlap
  → if score ≥ threshold → OWN_CONTENT vs SNAPSBY decision → optional top-3 catalog videos
  → AI phrasing (or templated fallback) → nudge_log → card (200) ; else 204
```

## Frontend
- **Component**: `components/trendspark/TrendSparkNudgeCard` (dismissible inline card, renders nothing on 204/error).
- **Hook**: `hooks/trendspark/useTrendSparkNudge` (react-query, 204→null).

## Backend
- **Controller**: `TrendSparkController` (`/brand/trendspark`).
- **Services**: `service/trendspark/*` (`ThemeMatchService`, `ContentGapService`, `BrandOwnContentService`, `CatalogMatchService`, `TrendSparkNudgeService`).
- **AI client**: `integration/ai/TrendSparkAiClient` (phrasing only, fail-open).

## Database
`trends` (V51, n8n-owned), `snapsby_catalog_video` (V51, seeded), `nudge_log` (V51, the flywheel), `brand_profiles` (V11, `theme_tags` + `last_posted_at`). See [../database.md](../database.md).

## APIs
`GET /brand/trendspark/nudge` (200/204), `POST /brand/trendspark/nudge/{id}/click`, `.../purchase`.

## AI
`TrendSparkAiClient` phrases the nudge copy (never invents facts; price always from `SnapsbyCatalogVideo.priceInr`; hallucinated video ids dropped). Fail-open to a deterministic templated fallback (`messageSource=FALLBACK`). See [../ai.md](../ai.md).

## Notifications
The nudge itself is the in-dashboard surface; `nudge_log` tracks impression→click→purchase.

## Dependencies
- **Depends on**: n8n trend pipeline, catalog seed, Meta insights (own-content gap signal), TrendSpark AI.
- **Depended on by**: campaign creation funnel.

## Connected Files
`TrendSparkController`, `service/trendspark/*`, `TrendSparkAiClient`, `domain/entity/{Trend,SnapsbyCatalogVideo,NudgeLog,BrandProfile}`; `trendspark/` (n8n workflow, theme taxonomy); frontend `TrendSparkNudgeCard`.

## Execution Flow
```
GET /brand/trendspark/nudge → TrendRepository.findActive → ThemeMatchService.score (overlap count vs vocab)
  → if score >= 2 → ContentGapService.decide (OWN_CONTENT | SNAPSBY, using BrandOwnContentService gap signal)
  → CatalogMatchService top-3 (SNAPSBY) → callAiSafely (AI or fallback) → NudgeLog → card ; else 204
```

## Error Handling
Fail-closed to silence (204) on any missing signal; AI failure → templated fallback; callbacks scoped via `findByIdAndWorkspaceId` (`NUDGE_NOT_FOUND` 404).

## Security
Workspace-scoped; Java is read-only for `trends`; prices always from the persisted catalog row (AI can't set price).

## Performance
On-demand (no scheduled Java job); theme taxonomy loaded once at `@PostConstruct`.

## Testing
TrendSpark service tests. Regression risks: threshold/silence, fallback path, catalog price source.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~78% (built + verified)
- **Known issues**: not yet production-authorized (pending live gate per project notes); depends on the external n8n pipeline populating `trends`; theme matching is keyword-overlap, not NLP.
- **Last verified**: 2026-07-15
