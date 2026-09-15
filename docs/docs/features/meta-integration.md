# Feature: Meta / Instagram Integration

**Business Purpose** — Lets creators connect their Instagram Business/Creator account so Influora can pull profile stats, per-media insights, and audience demographics. This data feeds creator scoring, discovery ranking, deliverable verification, and creator self-analytics — the platform's objective measurement layer.

**Who uses it** — Creators (connect), and the analytics/verification jobs (consume).

## User Roles
Creator (connect/disconnect). Data consumed by scoring/verification/analytics.

## Permissions
OAuth is creator-authenticated. Only the connecting creator's token is used, encrypted at rest.

## Business Flow
```
Creator → /authorize → JSON URL → Facebook OAuth consent → /callback (state consumed)
  → exchange code → short-lived → long-lived token → encrypted, stored
Jobs: refresh near-expiry (daily 02:30), soft-revoke stale (daily 04:00), poll metrics/demographics
```

## Frontend
- **Page**: `creator-meta-callback` (`/creator/settings/meta/callback`, unguarded so Meta's redirect isn't bounced).
- **Component**: `creator/connected-accounts`.
- **API**: `api.metaOAuth.*`.

## Backend
- **Controller**: `MetaOAuthController` (`/meta/oauth`).
- **Services/clients**: `integration/meta/oauth/{MetaOAuthService,MetaTokenStorage,MetaOAuthStateStore}`, `integration/meta/client/{InstagramInsightsClient,FacebookPageClient,MetaGraphApiClient}`, `integration/meta/service/{InstagramMetricsFetcher,MetaRateLimitTracker}`.
- **Jobs**: `MetaTokenRefreshService`, `StaleTokenCleanupJob`.

## Database
`meta_oauth_tokens` (V20; encrypted access token, `expires_at`, scopes, `revoked`, unique per workspace+creator). [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a "See [../database.md]" link — docs/docs/ contains only features/, so that file never existed].

## APIs
`GET /meta/oauth/authorize`, `GET /meta/oauth/callback`.

## AI
Not directly; the media captions pulled here feed the (currently unwired) brand-safety pipeline.

## Notifications
None specific (token issues surface in analytics).

## Dependencies
- **Depends on**: Meta Graph API v25.0.
- **Depended on by**: analytics, scoring, deliverable verification, portfolio (audience cities), TrendSpark own-content signal.

## Connected Files
`MetaOAuthController`, `integration/meta/*`, `job/{MetaTokenRefreshService,StaleTokenCleanupJob}`, `domain/entity/MetaOAuthToken`.

## Execution Flow
```
Connect: /authorize → MetaOAuthStateStore.issue (10-min, single-use, user-bound) → Facebook consent
  → /callback → stateStore.consume → exchangeCodeForToken → exchangeForLongLivedToken → MetaTokenStorage.storeToken (AES-GCM)
Consume: jobs iterate non-revoked/unexpired tokens → MetaRateLimitTracker pre-flight (skip ≥90%) → Graph calls
```

## Error Handling
`META_OAUTH_STATE_INVALID` (400), `META_API_ERROR` (502), `META_RATE_LIMITED` (429), `META_TOKEN_EXPIRED` (401), `META_PERMISSION_DENIED` (403). Per-token try/catch isolation in jobs.

## Security
Token AES-256-GCM encrypted (key must be 32 bytes; bean throws if blank); no refresh token stored (self-refresh by re-exchange); state is CSRF + user-bound; access token never logged; stale tokens soft-revoked (never deleted).

## Performance
`X-Business-Use-Case-Usage` rate-limit tracking; pre-flight defer at 90%; jobs offset schedules.

## Testing
Meta client/OAuth tests. Regression risks: token refresh, rate-limit deferral, encryption round-trip.

## Production Readiness
- **Health**: 6/10 · **Completion**: ~72%
- **Known issues**: [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0514: app-id/app-secret default to blank and only disable OAuth via `MetaApiProperties.isConfigured()` (MetaApiProperties.java:122) — no boot failure there. The separate `token-encryption-key` property (also blank by default in prod, application.yml:451) DOES throw `IllegalStateException` in `MetaTokenStorage.decodeKey` (MetaTokenStorage.java:71-79) if left blank, and that failure DOES stop boot since `MetaTokenStorage` is an eagerly-constructed `@Service` — real, just not caused by the OAuth credentials the original line implied.] per-post `media_metrics` polling IS wired (`MetricsPollingJob`, F-0479 — it maps `InstagramInsightsResponse` to `MediaMetric` and bulk-saves one row per post); [CORRECTED 2026-09-06, doc-stale-doc-claim, F-0516; re-verified 2026-09-13, F-0608: `InstagramMetricsFetcher` IS invoked by the job (MetricsPollingJob.java:90,290 — injected as `metricsFetcher`, called from `pollRecentMedia`) — the "not invoked" clause has since gone stale.] no YouTube equivalent. [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a "See [../known-limitations.md]" link — docs/docs/ contains only features/, so that file never existed.] [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0514 (follow-through): the 2026-09-13 edit of the boot-failure claim above also silently dropped this line's other claim, the quoted "No real Meta app exists yet — placeholders only" — with no marker recording that removal. Recording it now: app-id/app-secret are env-var-driven with blank code-level defaults (`application.yml:419-420`), which is a fact about this repo's config, not about what's actually configured in the deployed environment or the live Meta App Dashboard's review status for app `850102124044922` — that is external state this repo cannot attest to, and this doc will not restate the removed claim without re-verifying it live.]
- **Last verified**: 2026-07-15
