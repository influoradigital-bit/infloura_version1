# T-CREATORCONNECT-0902 — Meta-sourced creators → "Connect this creator" → admin invite → brand notified on join

Opened 2026-09-02 by Swapnil (CEO). Owner: vikram (backend) + ananya (frontend). Reviewer: kavya. Build: meera.

## The flow Swapnil asked for (verbatim intent)

1. Discover shows creators sourced from Meta (Instagram) who are NOT Influora members.
2. Each such card carries an **"Unverified with Influora"** badge and a CTA **"Connect this creator"**.
3. Clicking the CTA sends an **enquiry to the admin team**.
4. Admin team contacts the creator and **invites them to join** Influora.
5. When the creator joins, the brand gets an **email: "creator joined — create a campaign"**, and the
   card flips to **"Verified with Influora"** with a create-campaign link.

## Constraints that shape the design (verified, see wiki/decisions/2026-09-02-what-we-need.md)

- Real Meta Creator Marketplace data needs `instagram_creator_marketplace_discovery` Advanced Access.
  We cannot even submit App Review until stuck submission 887626066959194 is cancelled. So:
  **the Marketplace client is built behind a flag, and the flow must work today on Business
  Discovery (`instagram_basic`, held) + admin-imported handles.** Nothing may fake creator data.
- Business Discovery needs ONE connected Facebook-Login IG business token to call through. Use the
  requesting brand's workspace token if present, else any valid creator FACEBOOK_LOGIN token, else
  return 503 `INSTAGRAM_LOOKUP_UNAVAILABLE` — never a mock.
- Meta is OFF unless META_APP_ID/SECRET are set (`MetaApiProperties.isConfigured()`). Every
  Meta-touching path must degrade to "unavailable", not crash, when off.
- Notifications are email-only via MSG91 (`EmailTemplateRegistry` + `NotificationService.notify`).
  There is no admin in-app notification model; admin gets an email + a console page.
- Java records for DTOs; FE types must mirror them field-for-field (memory: a TS field the Java
  record never sends renders the empty state forever).

## Data model — migration `V20260902120000__external_creators_connection_requests.sql`

```sql
CREATE TABLE external_creators (
  id                         VARCHAR(26)  PRIMARY KEY,
  source                     VARCHAR(32)  NOT NULL,   -- META_MARKETPLACE | BUSINESS_DISCOVERY | ADMIN_IMPORT
  ig_account_id              VARCHAR(64)  NULL,
  ig_username                VARCHAR(80)  NOT NULL,
  display_name               VARCHAR(100) NULL,
  bio                        TEXT         NULL,
  profile_picture_url        VARCHAR(500) NULL,
  followers                  BIGINT       NULL,
  media_count                BIGINT       NULL,
  engagement_rate            DECIMAL(5,2) NULL,
  country                    VARCHAR(64)  NULL,
  categories                 JSON         NULL,
  email                      VARCHAR(255) NULL,       -- admin-supplied; Business Discovery never returns it
  status                     VARCHAR(32)  NOT NULL,   -- UNVERIFIED | INVITED | JOINED
  linked_creator_profile_id  VARCHAR(26)  NULL,
  invited_at                 TIMESTAMP    NULL,
  joined_at                  TIMESTAMP    NULL,
  last_synced_at             TIMESTAMP    NULL,
  created_at                 TIMESTAMP    NOT NULL,
  updated_at                 TIMESTAMP    NOT NULL,
  UNIQUE KEY uk_external_creators_username (ig_username),
  UNIQUE KEY uk_external_creators_ig_account (ig_account_id)
);

CREATE TABLE creator_connection_requests (
  id                    VARCHAR(26)   PRIMARY KEY,
  workspace_id          VARCHAR(26)   NOT NULL,
  requested_by_user_id  VARCHAR(26)   NOT NULL,
  external_creator_id   VARCHAR(26)   NOT NULL,
  message               VARCHAR(1000) NULL,
  status                VARCHAR(32)   NOT NULL,   -- PENDING | CONTACTED | JOINED | DECLINED
  admin_notes           VARCHAR(1000) NULL,
  handled_by            VARCHAR(26)   NULL,
  handled_at            TIMESTAMP     NULL,
  joined_notified_at    TIMESTAMP     NULL,
  created_at            TIMESTAMP     NOT NULL,
  updated_at            TIMESTAMP     NOT NULL,
  UNIQUE KEY uk_ccr_workspace_creator (workspace_id, external_creator_id),
  KEY ix_ccr_status (status),
  CONSTRAINT fk_ccr_external_creator FOREIGN KEY (external_creator_id) REFERENCES external_creators(id)
);
```
Match the dialect/quoting of the neighbouring V2026* migrations exactly (check whether they are MySQL or Postgres flavoured before writing). Usernames stored lower-cased, no leading `@`.

## Backend API contract (all under context-path `/api/v1`, `ApiResponse` envelope like `CreatorController`)

### Brand-facing — `ExternalCreatorController` at `/creators/external`
Brand principal required (`BrandContextService.requireBrandWorkspace`).

| Method | Path | Body / params | Returns |
|---|---|---|---|
| GET | `/creators/external` | `q?`, `minFollowers?`, `maxFollowers?`, `page=1`, `limit=20` | `ApiResponse<List<ExternalCreatorResponse>>` + PageMeta. Reads `external_creators` (all statuses). When `influora.meta.creator-marketplace.enabled=true` AND the workspace has a usable page token, ALSO query `CreatorMarketplaceClient`, upsert results as `META_MARKETPLACE`, then return from the table. |
| GET | `/creators/external/lookup?username=` | | `ApiResponse<ExternalCreatorResponse>`. Runs Business Discovery (`/{ig-user-id}?fields=business_discovery.username({u}){id,username,name,biography,profile_picture_url,followers_count,media_count}`), upserts as `BUSINESS_DISCOVERY`, returns row. 404 if Meta says no such professional account. 503 `INSTAGRAM_LOOKUP_UNAVAILABLE` if Meta off / no token. Never invents data. |
| POST | `/creators/external/{id}/connect` | `{ message?: string }` (≤1000 chars, sanitised) | `ApiResponse<ConnectionRequestResponse>`. Creates `PENDING` request. Idempotent: an existing non-DECLINED request for (workspace, creator) is returned unchanged with 200. If external creator is already `JOINED`, return 409 `CREATOR_ALREADY_ON_INFLUORA` with `linkedCreatorProfileId` in the error details. Publishes `CreatorConnectionRequestedEvent` (AFTER_COMMIT → admin email). |
| GET | `/creators/external/connection-requests` | | `ApiResponse<List<ConnectionRequestResponse>>` for this workspace. |

```java
record ExternalCreatorResponse(
    String id, String source, String igUsername, String displayName, String bio,
    String avatarUrl, Long followers, Long mediaCount, BigDecimal engagementRate, // nullable — NEVER coerce to 0
    String country, List<String> categories,
    String status,                      // UNVERIFIED | INVITED | JOINED
    boolean verifiedWithInfluora,       // status == JOINED && linkedCreatorProfileId != null
    String linkedCreatorProfileId,      // nullable
    String connectionStatus,            // this workspace's request: null | PENDING | CONTACTED | JOINED | DECLINED
    String connectionRequestId,         // nullable
    Instant lastSyncedAt) {}

record ConnectionRequestResponse(
    String id, String externalCreatorId, String igUsername, String displayName, String avatarUrl,
    String message, String status, Instant createdAt, Instant handledAt, Instant updatedAt,
    String linkedCreatorProfileId) {}
```

### Admin-facing — `AdminCreatorConnectionController` at `/admin/creator-connections`
Follow `AdminCreatorController` + `AdminContextService` exactly (admin auth is enforced in the service layer there — copy that discipline; audit-log every mutation via `AdminAuditLogService`).

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/admin/creator-connections` | `status?`, `search?`, `page=1`, `pageSize=20` | `PagedConnectionsDto { items: AdminConnectionDto[], page, pageSize, total }` |
| GET | `/admin/creator-connections/{id}` | | `AdminConnectionDto` |
| POST | `/admin/creator-connections/{id}/contacted` | `{ notes?: string }` | `AdminConnectionDto` (status → CONTACTED) |
| POST | `/admin/creator-connections/{id}/decline` | `{ notes?: string }` | `AdminConnectionDto` (status → DECLINED) |
| POST | `/admin/creator-connections/{id}/invite` | `{ email: string (required, valid), notes?: string }` | `AdminConnectionDto`. Sets `external_creators.email`, status → INVITED, `invited_at`; request → CONTACTED; sends `creator.join_invitation` email with `signup_url = {frontend-base}/creator/register?ref=influora-invite&handle={igUsername}`. Re-invite allowed (resend). |
| GET | `/admin/external-creators` | `status?`, `q?`, `page`, `pageSize` | paged `AdminExternalCreatorDto[]` |
| POST | `/admin/external-creators/import` | `{ usernames: string[] (≤50) }` | `{ imported: n, enriched: n, skipped: string[] }`. For each handle: try Business Discovery enrichment (any valid platform token); on failure still create an `ADMIN_IMPORT` stub with username only. Existing handles are re-synced, not duplicated. |

```java
record AdminConnectionDto(
    String id, String status, String message, String adminNotes,
    String workspaceId, String brandName, String requestedByUserId, String requestedByEmail,
    String externalCreatorId, String igUsername, String displayName, String avatarUrl,
    Long followers, String creatorEmail, String creatorStatus, String linkedCreatorProfileId,
    Instant createdAt, Instant handledAt, Instant joinedNotifiedAt) {}
```

### The JOINED hook — `ExternalCreatorLinkService`
`void onCreatorIdentified(String creatorProfileId, String igUsername, String igAccountId)`; either arg may be null. Called from:
1. `MetaTokenStorage.storeCreatorToken(...)` (all three overloads funnel to one place) — after the token row is saved, using the resolved IG business account id + username (`FacebookPageClient.resolveConnectedInstagram` returns both; on the INSTAGRAM_LOGIN path use whatever id/username the exchange returns).
2. Creator onboarding / profile update whenever `CreatorProfile.username` or the Instagram handle field is set (find where the creator's Instagram handle is persisted — `PlatformStat`/connected-accounts; match on it, not just the Influora username).

Match order: `ig_account_id` exact, else `ig_username` case-insensitive. On match, inside one transaction: external → `JOINED`, `linked_creator_profile_id`, `joined_at`; every `PENDING`/`CONTACTED` request → `JOINED`; publish one `ConnectedCreatorJoinedEvent(userId=requestedByUserId, workspaceId, entityId=requestId, creatorName, igUsername, creatorProfileId)` per request. Listener (AFTER_COMMIT, in `NotificationListener`) calls `notificationService.notify(...)` with template `brand.connected_creator_joined`, link `/brand/campaigns/new?creatorId={creatorProfileId}`, and stamps `joined_notified_at`. Idempotent on re-fire (skip if already stamped).

### Meta Creator Marketplace client (flagged, thin)
- `integration/meta/client/CreatorMarketplaceClient` — `GET /{igUserId}/creator_marketplace_creators` with `fields=id,username,is_account_verified,biography,country,profile_picture_url,has_brand_partnership_experience,past_brand_partnership_partners,insights` and filter params `query, creator_countries, creator_min_followers, creator_max_followers, creator_interests`. DTO `CreatorMarketplaceCreatorsResponse`. Uses a **Page access token**: obtain via `GET /me/accounts?fields=id,access_token,instagram_business_account{id}` from the workspace's FACEBOOK_LOGIN user token at call time (no new storage). Wrapped by `MetaGraphApiClient.get` so rate-limit tracking applies.
- Property `influora.meta.creator-marketplace.enabled: ${META_CREATOR_MARKETPLACE_ENABLED:false}` in `application.yml` (a `${VAR}` placeholder is mandatory — memory: env names without one bind to nothing). Add the same var, default `false`, to `deploy/utho/generate-env.sh` and both docker-compose files' env lists.
- Log clearly at INFO when the flag is on but no page token is available, and fall back to the table.

### Emails — add to `EmailTemplateRegistry`
| key | to | subject | body | CTA |
|---|---|---|---|---|
| `admin.creator_connection_requested` | `influora.admin.notification-email` (`${ADMIN_NOTIFICATION_EMAIL:}`; blank → log WARN and skip) | "Creator connection request: @{{ig_username}}" | "{{brand_name}} wants to work with @{{ig_username}} ({{followers}} followers). Message: {{message}}" | "Open in admin" → `admin_url` |
| `creator.join_invitation` | external creator email | "{{brand_name}} wants to work with you on Influora" | "A brand on Influora asked to collaborate with @{{ig_username}}. Join Influora to see the opportunity, get paid through escrow, and manage the deal." | "Join Influora" → `signup_url` |
| `brand.connected_creator_joined` | requesting brand user | "@{{ig_username}} joined Influora" | "The creator you asked to connect with is now verified on Influora. Create a campaign to start working together." | "Create a campaign" → `campaign_url` |
Also send the existing in-app notification (NotificationService.notify already does both) for the brand-facing one.

## Frontend contract

### `src/lib/api.ts` — new `api.externalCreators` + `api.admin.creatorConnections`
Types `ExternalCreator`, `ConnectionRequest`, `AdminConnection`, `AdminExternalCreator` mirror the Java records **field-for-field, same nullability**. Endpoints exactly as above. Add them to `src/lib/__tests__/api-contract.test.ts` if that test enumerates paths.

### Discover — `src/components/brand/discover/creator-discovery.tsx`
- Source toggle above the grid: **"Influora creators"** (existing behaviour, untouched) | **"Instagram creators"**.
- Instagram tab: handle lookup input ("Search an Instagram handle, e.g. @foodie.mumbai") → `lookup` → card; below it the paged list from `GET /creators/external` with the existing follower filters where they apply. Distinct loading / empty / 503-unavailable states with honest copy ("Instagram lookup isn't connected yet" — never a mock creator; F-0259/F-0260 gates apply).
- Card (reuse the existing creator-card visual language): avatar, `@igUsername`, display name, followers, engagement (absent → "—", never 0), country, and the badge:
  - `UNVERIFIED`/`INVITED` → amber `ShieldAlert` badge **"Unverified with Influora"**
  - `JOINED` → green `ShieldCheck` badge **"Verified with Influora"**
- CTA by `connectionStatus`:
  - `null` → primary button **"Connect this creator"** → dialog: creator summary + optional message (≤1000) + "Send request" → POST → toast "Request sent. The Influora team will reach out to @handle and email you when they join." Card flips to "Request sent".
  - `PENDING` → disabled **"Request sent"**; `CONTACTED` → disabled **"Team reached out"**; `DECLINED` → muted "Not available"
  - `JOINED` (or `verifiedWithInfluora`) → **"Create campaign"** → `/brand/campaigns/new?creatorId={linkedCreatorProfileId}` and a secondary "View profile" → existing creator profile route.
- Real click handlers wired to real API (memory F-0341: a div styled as a card passes every static check; write a click test).
- Brand-side "My connection requests" list: a compact panel/tab on the Instagram tab showing the workspace's requests and statuses (`GET /creators/external/connection-requests`).

### Campaign create — `src/pages/brand-new-campaign.tsx`
Read `?creatorId=`. Show a dismissible banner "Creating this campaign for @handle — they'll be invited when you publish" (resolve the handle via the existing public-profile call). After successful create, call the existing `api.creators.invite(creatorId, { campaignId })` path the Discover page already uses, then toast. If the existing create flow makes that impossible without deep changes, fall back to: after create, navigate to Discover with the creator pre-filtered and the invite CTA visible — and say so in the handoff.

### Admin console — `src/admin/pages/CreatorConnectionsPage.tsx`, route `creator-connections`, nav item **"Creator connections"** (`UserPlus` icon) in `AdminLayout`
- Table: brand, creator (@handle + avatar + followers), message (truncated, expand), status chip, requested date, actions.
- Actions: **Mark contacted** (notes), **Invite** (email input required + notes → POST invite → success toast "Invitation sent to …"), **Decline** (notes). Buttons disabled when the state makes them meaningless (e.g. DECLINED/JOINED).
- Filters: status, search. Pagination like other admin tables (follow `useFlagQueue.ts` hook pattern).
- Secondary section **"Import Instagram creators"**: textarea of handles (one per line, ≤50) → POST import → result summary. Plus the external creators table with status.

## done_when
- `gates/build.mvn.sh` (or the project's maven build) exits 0 with tests; `gates/frontend.sh` (tsc + vite build + vitest) exits 0.
- Backend unit tests: connect is idempotent; connect on JOINED creator → 409; `onCreatorIdentified` flips request(s) to JOINED and publishes one event per request; invite requires a valid email; lookup with Meta off → 503 not mock.
- FE tests: Instagram tab renders "Unverified with Influora" badge and the CTA calls the API on click; JOINED renders "Verified with Influora" + create-campaign link; 503 renders the unavailable state.
- Handoff block in SHARED_CONTEXT.md: `FROM → TO | TASK | FILES | STATUS | NEXT`.

## Out of scope (say so, do not build)
Sending proposals through Meta; Partnership Ads; Hashtag search; WhatsApp; anything requiring Advanced Access to behave. The Marketplace client ships dark.
