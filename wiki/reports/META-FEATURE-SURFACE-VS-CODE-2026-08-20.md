# Meta platform feature surface vs. Influora code — 2026-08-20

Task: T-META-0820 · verb CHECK · dispatched by Swapnil.
Evidence classes: (a) live authenticated Meta Graph API calls via the Meta DevTools MCP
(app 850102124044922); (b) `regex` sweeps + file reads over `influora-api/src` and `src`.
Sibling git worktrees under `.claude/worktrees/**` were excluded — they duplicate every
Meta file and inflate any grep run from the repo root.

## 1 · Our Meta app, as Meta reports it (live, not inferred)

| Fact | Value |
|---|---|
| App | `App-influora` — `850102124044922`, category BUSINESS, created 2025-11-30 |
| Status | **`dev_mode`, `is_live: false`** — real users cannot grant anything yet |
| Platform version | Meta latest `v26.0`; our code pins `v25.0` (`MetaApiProperties.graphApiVersion`) |
| Deprecations flagged | none |
| Webhook subscriptions | **0** |
| Second app | `Conversions API Application` — `146672604305308` (not referenced anywhere in code) |

Permissions Meta has granted:

| Permission | Grant | Used by our code? |
|---|---|---|
| `openid` | DEVOPS_APPROVED | no |
| `public_profile` | DEVOPS_APPROVED | implicit |
| `email` | DEVOPS_APPROVED | no |
| `pages_show_list` | DEVOPS_APPROVED | yes — `FacebookPageClient.resolveConnectedInstagram` |
| `business_management` | DEVOPS_APPROVED | **no** (granted but never requested in `REQUIRED_SCOPES`) |
| `instagram_basic` | DEVOPS_APPROVED | yes |
| `instagram_manage_insights` | DEVOPS_APPROVED | yes |
| `pages_read_engagement` | **REJECTED** | `FacebookPageClient.getPage()` needs it — see §5 |

## 2 · Meta's complete product surface (developers.facebook.com, retrieved 2026-08-20)

### Instagram Platform — API with Facebook Login
Business Discovery · **Creator Marketplace API** · Copyright Detection · Hashtag Search ·
Mentions · Product Tagging · Upcoming Events · Collaboration Invites · Publish Content ·
Comment Moderation · Private Replies · Insights · Sharing to Feed · Sharing to Stories ·
oEmbed · Embed Button · Self Messaging · Webhooks

### Instagram Platform — API with Instagram Login (no Facebook Page required)
Comment moderation · Content publishing · Media insights · Mentions · Messaging
(Conversations API, welcome-message ads, media upload).
Scopes: `instagram_business_basic`, `instagram_business_content_publish`,
`instagram_business_manage_comments`, `instagram_business_manage_messages`.
Documented limitation: **cannot access ads or tagging**.

### Facebook
Facebook Creator Discovery API · Facebook Login / Login for Business · Pages API ·
Sharing to Reels & Stories · Facebook SDK · App Events

### Ads and monetization
Marketing API (incl. Partnership Ads API) · Conversions API · App Ads

### Business messaging
WhatsApp Business Platform · Messenger Business Platform (incl. Instagram Messaging)

### Other
Threads API

## 3 · Creator Marketplace, in depth

Two separate products, both GA since October 2025.

**Instagram Creator Marketplace API**

- Permissions: `instagram_creator_marketplace_discovery` + `instagram_basic` +
  `pages_manage_metadata` + `pages_show_list` + `business_management`.
- Advanced Access requires App Review. Standard Access returns **test data only**.
- Token: a **Page access token** for a Page linked to the brand's IG business account.
- Brand must be eligible and must accept the IG Creator Marketplace Terms of Service.
- Rate limits: 1000 requests per user per hour; app ceiling = 1000 x effective daily users.
- `GET /{IG_USER_ID}/creator_marketplace_creators` — discovery + creator insights.
  Filters: `creator_countries`, `creator_states` (US only), `creator_min/max_followers`
  (bucketed 0 / 10k / 25k / 50k / 75k / 100k / 250k / 1M), `creator_age_bucket`,
  `creator_gender`, `creator_interests` (20 enums incl. BEAUTY, FASHION,
  FITNESS_AND_WORKOUTS, FOOD_AND_DRINK), `creator_min/max_engaged_accounts`,
  `major_audience_age_bucket|gender|countries|states`, free-text `query`,
  `similar_to_creators`, `custom_audience_id`.
  Fields: `id`, `username`, `country`, `gender`, `past_brand_partnership_partners`,
  `branded_content_media` (30 most recent branded posts), `recent_media` (top 30),
  `past_partnership_ads_media` (last 12 months, no insights).
  Creator insight metrics: `total_followers`, `creator_engaged_accounts`, `creator_reach`,
  `reels_interaction_rate`, `reels_hook_rate`; breakdowns by follow_type, gender, age,
  top_countries, top_cities.
  Media insight metrics: likes, comments, views, shares, plus `tagged_brand`.
- `GET /{IG_USER_ID}/creator_marketplace_brand_info` — custom audiences for filtering.
  Additionally needs `ads_management`.

**Facebook Creator Discovery API**

- Permissions: `facebook_creator_marketplace_discovery` + `pages_show_list`, on a
  **Business-type app**, both at Advanced Access. Standard Access returns mocked creator data.
- Token: Page access token. Endpoints `/creator_marketplace/creators` and
  `/creator_marketplace/content`.
- Filters incl. semantic `query`, `creator_categories`, `creator_interests`,
  `creator_countries`, `creator_languages`, `past_partnerships`, `sort_by`.
- Returns `creator_email` where available.
- Rate limits: 2000 per user per hour, 10000 per app per hour.

## 4 · What we actually have in code

### Built and wired

| Capability | Where |
|---|---|
| Meta OAuth (Facebook Login path) | `integration/meta/oauth/MetaOAuthService.java`, `web/MetaOAuthController.java` — `/meta/oauth/{authorize,callback,status,disconnect}` |
| Business Login for Instagram (no FB Page) | `MetaOAuthService` (`instagram_business_basic`, `instagram_business_manage_insights`), `MetaApiProperties.instagramAppId/Secret`, `domain/entity/MetaAuthPath.java` |
| CSRF state for the OAuth handshake | `MetaOAuthStateStore.java` |
| AES-256 encrypted token storage | `MetaTokenStorage.java`, `MetaOAuthToken.java`, `MetaOAuthTokenRepository.java` |
| Long-lived token refresh | `job/MetaTokenRefreshService.java` — cron `0 30 2 * * *` |
| Stale token cleanup | `job/StaleTokenCleanupJob.java` |
| Rate-limit tracking | `integration/meta/service/MetaRateLimitTracker.java` (alert 80 / throttle 90) |
| Graph client + typed errors | `MetaGraphApiClient.java`; `MetaApiException`, `MetaRateLimitException`, `MetaTokenExpiredException`, `MetaPermissionDeniedException` |
| IG profile + recent media | `InstagramInsightsClient.getProfile / getMedia` |
| IG media insights | `reach, likes, comments, saved, shares, views, total_interactions` |
| IG account insights | `reach, views, total_interactions, accounts_engaged, profile_links_taps` |
| IG audience demographics | `audience_city, audience_country, audience_gender_age, audience_locale`; `job/AudienceDemographicsJob.java` cron `0 30 3 * * SUN` |
| Metrics polling | `job/MetricsPollingJob.java` cron every 6h; `InstagramMetricsFetcher`; `CreatorMetric` / `MediaMetric` |
| FB Pages list to linked IG business account | `FacebookPageClient.resolveConnectedInstagram` (`/me/accounts`) |
| Granted-scope introspection | `FacebookPageClient.fetchPermissions` (`GET /me/permissions`) |
| Caption sync for the creator co-pilot | `job/CreatorCaptionSyncJob.java` |
| Deliverable verification from an IG post URL | `service/verification/DeliverableVerificationService.java`, `PostUrlIdentifier.java` |
| Connect / disconnect UI | `src/components/creator/connected-accounts.tsx`, `copilot/IGConnectPrompt.tsx`, `copilot/BusinessAccountRequired.tsx`, `api.metaOAuth.*` in `src/lib/api.ts` |

### Not present — zero references in `influora-api/src` or `src`

Instagram Creator Marketplace API · Facebook Creator Discovery API · Business Discovery ·
Branded Content / Partnership Ads · Content Publishing · Comment Moderation ·
Private Replies · Mentions · Hashtag Search · Product Tagging · Copyright Detection ·
Upcoming Events · Collaboration Invites · Sharing to Feed/Stories/Reels · oEmbed ·
Embed Button · Instagram Messaging / Messenger / WhatsApp · Marketing API ·
Conversions API · Threads API · Meta Webhooks.

The single `threads.net` hit is a host allow-list entry in
`CreatorDeliverableService.java:103`, not the Threads API. Every `mentions` hit is English
prose in blog and campaign copy.

## 5 · Defects this check surfaced

- **D1 — `FacebookPageClient.getPage()` is unreachable in production.** It needs
  `pages_read_engagement`; Meta has that permission at `grant_status: REJECTED`,
  `access_level: none`. `MetaOAuthService.REQUIRED_SCOPES` correctly stopped requesting it
  (CR-115), so the method is dead code that will 403 for any caller who wires it up.
- **D2 — the app is `dev_mode` / `is_live: false`.** Only users holding a role on the app can
  complete the OAuth flow. Every Meta-connect path is untestable by a real creator today.
- **D3 — `business_management` is granted and unused.** It is one of the five Creator
  Marketplace prerequisites and costs nothing to keep.
- **D4 — zero webhook subscriptions.** Every metric is pull-only (6h poll, weekly, daily
  crons). No real-time signal for comments, mentions, or story insights.
- **D5 — Graph version drift.** Pinned `v25.0`, Meta latest is `v26.0`. No deprecation is
  flagged yet, so this is a watch item, not a break.

## 6 · Gap to shipping Creator Marketplace

Already held: `instagram_basic`, `pages_show_list`, `business_management`.
Still needed: `instagram_creator_marketplace_discovery` (App Review, Advanced Access),
`pages_manage_metadata`, and `ads_management` for the brand-info / custom-audience call.
Plus: app moved to Live mode, brand eligibility check, IG Creator Marketplace ToS
acceptance, and a Page-access-token flow — our code currently stores only user tokens.
Facebook Creator Discovery is a separate submission again
(`facebook_creator_marketplace_discovery`, Business-type app).
