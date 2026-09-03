# T-CREATORCONNECT-0902 — QA questions (Tester → CTO)

35 questions, 5 per feature, grounded in the code as of 2026-09-03 on branch `fix/f0390-money-flags-build-pipeline`. Not answered here — answer with evidence (test, log line, curl, or a line citation), not prose.

Path shorthand: `api/` = `influora-api/src/main/java/com/influora/`, `fe/` = `src/`.

## F1 — Instagram creators tab + handle lookup (Business Discovery)

### Q1.1 [IS IT WORKING]
Show me one real end-to-end run of the lookup path: a brand types `@handle` in `fe/components/brand/discover/creator-discovery.tsx` (`handleLookup`, ~L2100) → `api.externalCreators.lookup` (`fe/lib/api.ts:1911`) → `ExternalCreatorService.lookup` (`api/service/ExternalCreatorService.java:212-271`) → `InstagramInsightsClient.businessDiscovery` (`api/integration/meta/client/InstagramInsightsClient.java:115`) returning a non-null `business_discovery` and a row landing in `external_creators`. The only test on this path is `lookup_metaUnconfigured_returns503` — what proves the *happy* path against a live `instagram_basic` token, and which token (`resolveBusinessDiscoveryCaller` L277-305) did it use?

### Q1.2 [HOW]
`firstUsableBrandToken` (`ExternalCreatorService.java:175-179`) filters by `revokedFalse` + expiry only — not by `authPath`. If the workspace's first usable row is an INSTAGRAM_LOGIN token, `resolveBusinessDiscoveryCaller` hands its `igBusinessAccountId` + access token to `businessDiscovery`, which calls the 4-arg `MetaGraphApiClient.get` hard-wired to `MetaAuthPath.FACEBOOK_LOGIN` (`MetaGraphApiClient.java:92-94`). What happens on that host mismatch — a caught `MetaApiException` → 503, or something else — and does the creator-token fallback at L290-296 (which *does* filter `FACEBOOK_LOGIN`) ever get reached once a brand token exists?

### Q1.3 [WHY NOT THIS WAY]
The fallback at `ExternalCreatorService.java:290-304` borrows *any creator's* personal FACEBOOK_LOGIN token to run a brand's lookup, and the rate-limit key becomes that creator's `igBusinessAccountId` (`MetaGraphApiClient.java:110`). Why not a single Influora-owned IG Business account token (a system caller) instead of spending an arbitrary creator's per-account Graph quota and tying brand feature availability to whether *some* creator happens to have connected? What did Meta platform-terms review say about this?

### Q1.4 [WHEN WILL IT BREAK]
Admin imports `@oldhandle` (row A, `ig_account_id` NULL), the creator renames on Instagram to `@newhandle`, a brand looks up `@newhandle`: `lookup` creates row B by username (L252-261) then `applyIgAccountId(bd.id())` (L262) sets the same IG id row A would get on its next enrichment — and if A was already enriched, row B's save violates `uk_external_creators_ig_account` (migration L43). Is that a 500 to the brand or handled? Same question for two brands looking up the same never-seen handle concurrently and racing on `uk_external_creators_username` (L42).

### Q1.5 [WHAT TO ADD]
`InstagramCreatorsTab.fetchList` calls `api.externalCreators.list({ page, limit })` only (`creator-discovery.tsx:~2056`) — the `q`, `minFollowers`, `maxFollowers` params the backend accepts (`ExternalCreatorController.java:40-42`, `ExternalCreatorSpecs.java:677-691`) are never sent, and TASKS.md L157 says "with the existing follower filters where they apply". Where are the filters, and given `ExternalCreatorSpecs` compares `followers >= min` (SQL NULL → excluded), what should happen to the never-enriched rows (followers NULL) the moment any filter is applied?

## F2 — Badge + honest states

### Q2.1 [IS IT WORKING]
Show me the exact rendering path for the three badge/CTA states with real server data, not the vitest fixtures: `toResponse` (`ExternalCreatorService.java:413-435`) → `ExternalCreator` type (`fe/lib/api.ts:1825-1847`) → `ExternalCreatorBadge` (`creator-discovery.tsx:1904-1920`) → `ExternalCreatorCard` footer (`~1996-2018`). In particular, a captured JSON body from `GET /creators/external` on a running API showing `engagementRate: null` and the card rendering "—" (`externalEngagement`, L1926).

### Q2.2 [HOW]
`ExternalCreatorCard` computes `verified = creator.status === 'JOINED' || creator.verifiedWithInfluora` (`creator-discovery.tsx:~1953`) while the server defines `verifiedWithInfluora = JOINED && linkedCreatorProfileId != null` (`ExternalCreatorService.java:414`). For a row with `status=JOINED` and `linked_creator_profile_id=NULL` the card renders "Verified with Influora", a "View profile" link to `/brand/creators/` (empty id, ~L1999) and a disabled "Create campaign". Walk me through how that state arises (or prove it cannot) and why the FE ORs the two instead of trusting the server flag alone.

### Q2.3 [WHY NOT THIS WAY]
`GlobalExceptionHandler.handleCreatorAlreadyOnInfluora` (`api/common/GlobalExceptionHandler.java:69-77`) puts `linkedCreatorProfileId` on the wire specifically so the FE can offer the create-campaign link on 409, and `api.ts:1918-1922` says callers should. But `submitConnect` (`creator-discovery.tsx:~2140-2160`) catches every `ApiError` into a generic destructive toast and `ApiError.details` is typed only for `InsufficientFundsDetails` (`api.ts:273`). Why ship the 409 payload nobody reads instead of either wiring the FE branch or dropping the field?

### Q2.4 [WHEN WILL IT BREAK]
Lookup and list have different unavailable semantics: `fetchList` treats *only* `err.status === 503` as "unavailable" (`creator-discovery.tsx:~2062`), but `GET /creators/external` never returns 503 — `list()` reads the table and swallows Marketplace failures (`ExternalCreatorService.java:100-125, 164-172`). So `data-testid="instagram-unavailable"` (the state the vitest at `creator-discovery.instagram.test.tsx:164` asserts) is only reachable in a mocked test. What does a brand see when the API is actually down (network error, 502 from nginx) — and is a 502 from the reverse proxy an `ApiError` with `status` at all?

### Q2.5 [WHAT TO ADD]
`ExternalCreatorBadge` collapses `UNVERIFIED` and `INVITED` into the same amber badge (`creator-discovery.tsx:1904-1920`) and the brand-side request list shows only `connectionRequestStatusLabel` (L1930-1942). A brand whose request is `CONTACTED` sees "Team reached out" but nothing distinguishes "admin emailed an invite 3 weeks ago, no reply" from "admin phoned yesterday". What `invitedAt`/`handledAt` surfacing is missing before a brand can decide whether to wait or move on, given `ConnectionRequestResponse` already carries `handledAt` (`ExternalCreatorDtos.java:47`)?

## F3 — "Connect this creator" request (dialog → POST /connect, idempotency, 409, admin email)

### Q3.1 [IS IT WORKING]
Show me the admin email actually arriving: `connect` publishes `CreatorConnectionRequestedEvent` (`ExternalCreatorService.java:363-373`) → `NotificationListener.on(CreatorConnectionRequestedEvent)` (`api/service/notification/NotificationListener.java:635-662`) → `msg91EmailClient.sendTemplateEmail(adminNotificationEmail, "admin.creator_connection_requested", …)`. With `ADMIN_NOTIFICATION_EMAIL` blank in `deploy/utho/generate-env.sh:141` the listener WARNs and skips (L638-644). On which environment has this email been received, and what was `admin_url` resolved to (`webBaseUrl + "/admin/creator-connections"`, L651) — does that route exist unauthenticated or does it bounce to admin login?

### Q3.2 [HOW]
Reopen after DECLINED: `existing.reopen(message)` (`CreatorConnectionRequest.java:189-197`) resets status/notes but keeps the original `requestedByUserId`, while the event is published with `principal.getUserId()` (`ExternalCreatorService.java:365-366`). If a *different* member of the same workspace re-requests, whose inbox gets the `brand.connected_creator_joined` email later (`ExternalCreatorLinkService.java:126-133` uses `request.getRequestedByUserId()`) — and what if that original user has since been removed from the workspace (`emailOf`, `NotificationListener.java:126-131`)?

### Q3.3 [WHY NOT THIS WAY]
The admin notification bypasses `NotificationService`/`EmailOutbox` and calls `Msg91EmailClient` directly from an `@Async` listener (`NotificationListener.java:652-656`), so a transient SMTP failure is a single `log.error` and the request is lost to admins forever — the console page is the only recovery. Why not queue it through the outbox (with retries/backoff that `EmailWorker` already gives every other template) using a synthetic admin recipient, the way `creator.not_connected` gets special-cased for a non-user recipient?

### Q3.4 [WHEN WILL IT BREAK]
Two clicks / two tabs POST `/creators/external/{id}/connect` concurrently for the same (workspace, creator): both `findByWorkspaceIdAndExternalCreatorId` return empty (`ExternalCreatorService.java:335-337`), both build a new row (L351-358), and the second `save` hits `uk_ccr_workspace_creator` (migration L61). Unlike `CreatorDiscoveryService.invite` (L492-498) there is no `DataIntegrityViolationException` catch here — is that a 500 to the brand and a duplicate admin email from the first, and does the "Request sent" FE state survive the 500?

### Q3.5 [WHAT TO ADD]
`connect` accepts any `id` that exists in `external_creators` regardless of `source`/`status`, and there is no per-workspace cap: a brand can create one request per external creator across the whole table (`list` returns *all* statuses, `ExternalCreatorService.java:111`), each firing an admin email. What rate/volume guard (per-workspace daily cap, or requiring the creator to have been looked up by this workspace) is missing before an admin inbox can be flooded by one brand clicking through 20 pages of imported handles?

## F4 — Admin Creator connections page

### Q4.1 [IS IT WORKING]
Show me a real admin invite round-trip: `CreatorConnectionsPage` action dialog (`fe/admin/pages/CreatorConnectionsPage.tsx:270-273`) → `creatorConnectionsApi.invite` (`fe/admin/services/api-contracts.ts:726`) → `AdminCreatorConnectionService.invite` (`api/service/admin/AdminCreatorConnectionService.java:321-369`) → `sendJoinInvitationEmail` (L371-390) → a `creator.join_invitation` email in a real inbox whose "Join Influora" CTA opens `{webBaseUrl}/creator/register?ref=influora-invite&handle=…`. Include the `AdminAuditLogService` rows (L348-365) for that run.

### Q4.2 [HOW]
`sendJoinInvitationEmail` runs *inside* the `@Transactional invite` before commit (L367) and hard-codes `brand_name` to `"A brand"` (L373) even though the request row carries `workspaceId` and `toDtos` already resolves `brandName` (L636). So the subject the creator receives is literally "A brand wants to work with you on Influora". Walk me through why the email is sent pre-commit (what happens if the commit then fails on `external_creators.email` length 255, L357-358) and why the workspace name was dropped when the contract at TASKS.md L146 templates `{{brand_name}}`.

### Q4.3 [WHY NOT THIS WAY]
`importHandles` (`AdminCreatorConnectionService.java:428-511`) runs up to 50 Business Discovery calls *inside one DB transaction* using whichever token `resolveAnyBusinessDiscoveryCaller` finds first (L517-532), and only `MetaApiException` is caught per handle (L486-488). Why not enrich asynchronously (stub rows first, enrichment as a job) so an admin paste doesn't hold a connection through 50 Graph round-trips, a single `MetaRateLimitException` (a `MetaApiException` subclass, so caught) doesn't silently leave 49 stubs un-enriched with no retry, and the rate cost doesn't land on one arbitrary creator's account?

### Q4.4 [WHEN WILL IT BREAK]
`importHandles` normalizes with `normalizeUsername` (L685-688) but never applies `ExternalCreatorService.USERNAME_PATTERN` (`ExternalCreatorService.java:58`) or the 80-char column limit. An admin pastes `foo bar`, `foo)`, or an 81-char line: the first two are interpolated raw into `business_discovery.username(...)` (`InstagramInsightsClient.java:117-122`) and, when Meta rejects them, still persisted as ADMIN_IMPORT stubs with garbage handles; the third throws at flush and rolls back the *entire* batch with a 500. What does the admin see in each case, and how do they delete a garbage stub given there is no delete endpoint (`AdminExternalCreatorController.java`)?

### Q4.5 [WHAT TO ADD]
`AdminConnection.brandName` and `requestedByEmail` are typed non-null `string` (`fe/admin/types/admin.types.ts:617-619`) while `toDtos` emits `null` when the workspace or user row is gone (`AdminCreatorConnectionService.java:636-638`) — the exact memory-flagged FE-asserts-a-field-the-record-may-not-send class. Beyond fixing the type, what is missing for this page to be operable at volume: the `search` filter loads *every* matching workspace and external creator into memory to build `IN` lists (L566-577), the external-creators table is pinned to `page: 1, pageSize: 20` with no pager (`CreatorConnectionsPage.tsx:266`), and the only FE test is the invite-email-validation one (`CreatorConnectionsPage.test.tsx:91`).

## F5 — Join detection hook + brand email

### Q5.1 [IS IT WORKING]
Show me the full live path for the one call site that carries both id and username: creator completes FACEBOOK_LOGIN → `CreatorMetaOAuthService` L138-148 passes `igAccount.username()` (from `ACCOUNTS_FIELDS = instagram_business_account{id,username,followers_count}`, `FacebookPageClient.java:23-24`) → `MetaTokenStorage.storeCreatorToken` 8-arg (`MetaTokenStorage.java:285-346`) → `ExternalCreatorLinkService.onCreatorIdentified` (`ExternalCreatorLinkService.java:64-144`) → `ConnectedCreatorJoinedEvent` → `NotificationListener.on(ConnectedCreatorJoinedEvent)` (L673-701) → in-app row + `brand.connected_creator_joined` email → `joined_notified_at` stamped. Which environment has produced a row with `joined_notified_at IS NOT NULL`?

### Q5.2 [HOW]
`MetaTokenRefreshService` routes every ~55-day creator refresh through the same 8-arg `storeCreatorToken` with `igUsername=null` but `igBusinessAccountId` present (`MetaTokenStorage.java:277-284` javadoc, `MetaTokenRefreshService.java:190`), so `onCreatorIdentified` re-fires on every refresh, matches by id, and `markJoined` re-stamps `joined_at`/`updated_at` (`ExternalCreator.java:515-520`). Walk me through what else moves on each refresh: does the row bubble back to the top of the `updatedAt DESC` Discover list (`ExternalCreatorService.java:115`), and what guarantees no second `ConnectedCreatorJoinedEvent` for a request that was reopened after DECLINED post-join?

### Q5.3 [WHY NOT THIS WAY]
`PortfolioService.upsertPlatformStat` calls the hook with `metric.getUsername()` and `igAccountId=null` (`PortfolioService.java:367-369`) even though `syncPlatforms` runs off the creator's stored Meta token, whose `igBusinessAccountId` is on the same `MetaOAuthToken` row (`metaOAuthTokenRepository` is injected at L105). Why fall back to the weaker case-insensitive username match at this site (`ExternalCreatorLinkService.java:79-83`) when the exact `ig_account_id` match was one repository call away — and why does `CreatorProfileService.applyUsername` (L175-180) feed an *Influora* username into a matcher that expects an *Instagram* handle at all?

### Q5.4 [WHEN WILL IT BREAK]
Identity spoof via the username path: any creator can PATCH their Influora username (`CreatorProfileService.applyUsername`, L164-181; `UsernameUtils.VALID` allows `[a-z0-9_]`) to equal a targeted external handle — say a brand requested `@foodiemumbai` — and `onCreatorIdentified` will mark that row JOINED, link *their* profile id, flip the brand's request to JOINED and email the brand "@foodiemumbai joined Influora — create a campaign" with a `creatorId` pointing at the impostor. Conversely, a real handle containing a dot (`foodie.mumbai`) can *never* match through this path because `VALID` rejects dots. What prevents the first, and what is the intended match path for the second?

### Q5.5 [WHAT TO ADD]
The invite email's `signup_url` carries `?ref=influora-invite&handle={igUsername}` (`AdminCreatorConnectionService.java:375-377`), but `fe/pages/creator-register.tsx` never reads `searchParams` (no `useSearchParams`/`handle` usage), so the handle is dropped on arrival and the JOINED flip depends entirely on the creator later connecting Meta or claiming a matching username. Also, `onCreatorIdentified`'s `catch (Exception)` (`ExternalCreatorLinkService.java:135-143`) cannot catch a `uk_external_creators_ig_account` violation raised at *flush/commit* by the surrounding `@Transactional` proxy, so the "never fail the caller" promise is untested for that case. What is missing: an invite-token-based claim at registration, and a test that a flush-time constraint violation inside the hook does not 500 the Meta OAuth callback?

## F6 — Create campaign handoff (`?creatorId=` banner + post-create invite)

### Q6.1 [IS IT WORKING]
Show me the exact live path from the JOINED card's "Create campaign" link (`creator-discovery.tsx:~2003`, `/brand/campaigns/new?creatorId=…`) → `CampaignForm` reading `searchParams.get('creatorId')` (`fe/components/brand/campaigns/campaign-form.tsx:274`) → `api.campaigns.create` → `api.creators.invite(creatorIdParam, saved.id)` (L524-526) → `CreatorDiscoveryService.invite` (`api/service/CreatorDiscoveryService.java:452-505`) creating an INVITED `Collaboration`. No vitest covers `campaign-form.tsx` L520-543; what proves the second request fires, with the campaign id the server returned rather than a stale form id?

### Q6.2 [HOW]
Both banners promise "they'll be invited when you publish" (`brand-new-campaign.tsx:113`, `campaign-form.tsx:664-666`), but the code invites on *any* successful `api.campaigns.create` (L518-526) including a DRAFT save, and `CreatorDiscoveryService.invite` has no campaign-status guard (L452-505). Walk me through what the creator sees when invited to a DRAFT campaign — which creator-side list surfaces a DRAFT collaboration, and what happens to that INVITED row if the brand deletes the draft?

### Q6.3 [WHY NOT THIS WAY]
The handoff is two non-atomic requests from the browser (create, then invite) with the intent living only in the URL; if the invite 404s or the tab closes between them, nothing records that this campaign was created *for* that creator (`campaign-form.tsx:533-542` just toasts). Why not carry `creatorId` in the `CampaignCreateRequest` payload and have the server create the collaboration in the same transaction — or at minimum persist the intent on the `creator_connection_requests` row so the admin/brand can see "campaign created, invite failed"?

### Q6.4 [WHEN WILL IT BREAK]
`CreatorDiscoveryService.invite` goes through `requireDiscoverableProfile` (L925-934), which 404s for a profile that is not yet `discoverable` or is suspended. A creator who just joined via the admin invite and has not finished onboarding is exactly that case, so the first thing the brand does after the "creator joined" email is hit "Campaign created, but the invite failed" (L535) — and the fallback copy says "invite this creator from Discover", where the Influora tab only lists discoverable creators too. Separately, choosing HYPE navigates to `/brand/campaigns/new/hype` (`brand-new-campaign.tsx:174-177`) and drops `?creatorId=` entirely. What happens in each case?

### Q6.5 [WHAT TO ADD]
`creatorHandle` is resolved from `profile.username` (`campaign-form.tsx:281-285`) — the Influora username — while every upstream surface (Discover card, both emails) speaks in `@igUsername`; the banner can therefore show a different `@handle` than the email that brought the brand here. What is missing so the handoff carries the external creator context (ig handle, `connectionRequestId`) through to the form, and where is the click test for the `?creatorId=` banner + post-create invite that memory F-0341 says a static check cannot replace?

## F7 — Meta Creator Marketplace client behind a flag + config/deploy

### Q7.1 [IS IT WORKING]
Prove the flag is actually wired end-to-end: `META_CREATOR_MARKETPLACE_ENABLED` → `application.yml:390-391` → `MetaApiProperties.CreatorMarketplace.enabled` (`api/config/MetaApiProperties.java:57-77`, prefix `influora.meta`) → `ExternalCreatorService.list` L105-107 → `tryEnrichFromMarketplace` → `CreatorMarketplaceClient.search` (`api/integration/meta/client/CreatorMarketplaceClient.java:40-59`). `ExternalCreatorServiceTest` only mocks the client in the constructor (L57, L77) and never exercises the enabled branch; show me the test or boot log where `enabled=true` produced the `GET /{igUserId}/creator_marketplace_creators` request (even if Meta answered with a permission error).

### Q7.2 [HOW]
`upsertFromMarketplace` (`ExternalCreatorService.java:181-205`) stores only `biography`, `profile_picture_url`, `country` and never calls `applyIgAccountId(creator.id())`, dropping `id`, `is_account_verified`, `has_brand_partnership_experience`, `past_brand_partnership_partners` and `insights` (`CreatorMarketplaceCreatorsResponse.java:85-97`). So a META_MARKETPLACE row has no `ig_account_id` and the JOINED hook can only ever match it by username. Walk me through why the IG id — the one field that survives a handle rename — is discarded, and where the "insights" the FIELDS list requests were meant to go.

### Q7.3 [WHY NOT THIS WAY]
When the flag is on, every page of `GET /creators/external` makes two Graph calls per request — `/me/accounts` via `resolvePageAccessToken` (`FacebookPageClient.java:81-96`) then the marketplace search — inside a read-write `@Transactional list` (`ExternalCreatorService.java:100`) that holds a DB connection for the Meta round-trip. Why not resolve and cache the Page token per workspace (it does not rotate independently of the user token) and run enrichment out of band, so a slow Meta response cannot exhaust the connection pool on the brand's Discover tab?

### Q7.4 [WHEN WILL IT BREAK]
Both compose files pass `META_CREATOR_MARKETPLACE_ENABLED: ${META_CREATOR_MARKETPLACE_ENABLED}` and `ADMIN_NOTIFICATION_EMAIL: ${ADMIN_NOTIFICATION_EMAIL}` with **no** `:-` default (`deploy/hostinger/docker-compose.hostinger.yml:191-192`, `deploy/utho/docker-compose.utho.yml:225-226`), unlike `CREATOR_COPILOT_ENABLED: ${CREATOR_COPILOT_ENABLED:-false}` at hostinger L154. On the live Hostinger VPS whose `.env` predates this task, compose will warn and inject an *empty string*, and `${META_CREATOR_MARKETPLACE_ENABLED:false}` in `application.yml:391` only defaults when the var is *unset*. What does Spring's binder do with `""` for the primitive `boolean enabled` (`MetaApiProperties.java:60`) — boot failure, or silently `false` — and was this verified on the actual image rather than reasoned about?

### Q7.5 [WHAT TO ADD]
The migration `V20260902120000__external_creators_connection_requests.sql` has no index on `creator_connection_requests(workspace_id)` (every Discover list call runs `findByWorkspaceIdOrderByCreatedAtDesc`, `ExternalCreatorService.java:404-411`, and only the composite unique key covers it as a prefix), no FK from `linked_creator_profile_id` to `creator_profiles`, and `findByIgUsernameIgnoreCase` (`ExternalCreatorRepository.java:651`) wraps a column that is already `utf8mb4_unicode_ci` in `LOWER()`, defeating `uk_external_creators_username`. What is the plan for the dormant `CreatorMarketplaceClient` to be exercised at all before App Review (a recorded-fixture test against a captured Marketplace response), and which of these schema gaps ship as-is?
