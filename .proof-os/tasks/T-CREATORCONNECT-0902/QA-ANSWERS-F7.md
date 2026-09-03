# F7 — Meta Creator Marketplace client behind a flag + config/deploy

Answered by Priya (CTO), 2026-09-03, branch `fix/f0390-money-flags-build-pipeline`. No source files edited.

### Q7.1 [IS IT WORKING]
> Prove the flag is actually wired end-to-end: `META_CREATOR_MARKETPLACE_ENABLED` → `application.yml:390-391` → `MetaApiProperties.CreatorMarketplace.enabled` → `ExternalCreatorService.list` L105-107 → `CreatorMarketplaceClient.search`. Show me the test or boot log where `enabled=true` produced the `GET /{igUserId}/creator_marketplace_creators` request.

VERDICT: PARTIAL

The **binding chain is real and complete**, and I verified the binder half of it empirically (see Q7.4 probe, which bound the literal key `influora.meta.creator-marketplace.enabled` into this exact class shape):

- `application.yml:390-391` — `creator-marketplace.enabled: ${META_CREATOR_MARKETPLACE_ENABLED:false}`. A real `${VAR}` placeholder is present, so the env name is not fiction (the failure mode in memory *env-var-names-may-bind-to-nothing*).
- `config/MetaApiProperties.java:14` prefix `influora.meta`; nested class L59-69; getter/setter L71-77; registered at `InfluoraApiApplication.java:42` in the `@EnableConfigurationProperties` list.
- Caller gate `service/ExternalCreatorService.java:105-107` → `tryEnrichFromMarketplace` L128-173 → `CreatorMarketplaceClient.search` L40-59 → `MetaGraphApiClient.get` L57-58.

**No execution evidence exists.** `ExternalCreatorServiceTest.java:66` constructs `new MetaApiProperties()`, so `enabled` is the L60 default `false` in every test; the `creatorMarketplaceClient` mock (L57) is never stubbed and never `verify`-ed. It is the only test file in `influora-api/src/test/java` that names CreatorMarketplace at all. No boot log, no fixture, no captured request. The flag is wired; the branch behind it has never run once. Gap filed at Q7.5.

FINDING: none

### Q7.2 [HOW]
> `upsertFromMarketplace` stores only `biography`, `profile_picture_url`, `country` and never calls `applyIgAccountId(creator.id())`, dropping `id`, `is_account_verified`, `has_brand_partnership_experience`, `past_brand_partnership_partners` and `insights`. Walk me through why the IG id — the one field that survives a handle rename — is discarded, and where the "insights" the FIELDS list requests were meant to go.

VERDICT: DEFECT

There is no reason in the code or javadoc. `upsertFromMarketplace` (`ExternalCreatorService.java:181-205`) calls exactly one mutator — `applySync(null, biography, profilePictureUrl, null, null, null, country)` (L196-203) — and never `applyIgAccountId`. That method exists (`domain/entity/ExternalCreator.java:206`) and *is* called on both other paths: Business Discovery at `ExternalCreatorService.java:262` and the join hook at `ExternalCreatorLinkService.java:104`. So the Marketplace path is the sole outlier.

The data is present, not absent: `CreatorMarketplaceCreatorsResponse.Creator` declares `id` (L20), `isAccountVerified` (L22) and `insights` (L31, typed `Object`) — the client requests all of them in `FIELDS` (`CreatorMarketplaceClient.java:22-24`), Jackson parses them, and nothing reads them. `applySync`'s semantics are null-means-keep (L195-197), so passing nulls is a deliberate "don't clear", which confirms the missing `applyIgAccountId` is an omission rather than a guard.

Consequence: a META_MARKETPLACE row has `ig_account_id` NULL, so the strong exact-id match in the JOINED hook can never fire for it and every such row falls to the case-insensitive username match — the weak path that dies on a handle rename and is the one Q5.4 shows is spoofable. `insights` had no destination column designed for it.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Marketplace upsert discards the IG account id, leaving META_MARKETPLACE rows matchable only by username","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:196","issue":"upsertFromMarketplace calls applySync only and never applyIgAccountId(creator.id()), although the DTO carries id (CreatorMarketplaceCreatorsResponse.java:20) and the other two upsert paths do call it (ExternalCreatorService.java:262, ExternalCreatorLinkService.java:104). Every Marketplace-sourced row therefore has ig_account_id NULL and can only ever be joined by the weak case-insensitive username match, which a handle rename breaks and an impostor can claim. is_account_verified and insights are parsed and dropped with no destination.","fix":"Call external.applyIgAccountId(creator.id()) in upsertFromMarketplace before save (guarding the existing uk_external_creators_ig_account collision the same way the Business Discovery path must), and either persist is_account_verified or drop it and insights from the FIELDS list so the request stops asking for data nothing stores."}
```

### Q7.3 [WHY NOT THIS WAY]
> When the flag is on, every page of `GET /creators/external` makes two Graph calls per request inside a read-write `@Transactional list` that holds a DB connection for the Meta round-trip. Why not cache the Page token per workspace and run enrichment out of band?

VERDICT: ACCEPTED-RISK

The description is accurate, and the call ordering makes it worse than it reads. `list` is `@Transactional` (`ExternalCreatorService.java:100`) and calls `tryEnrichFromMarketplace` at L106 — before any of its own reads. Inside it, `firstUsableBrandToken` (L130 → L176) issues a repository read that acquires the Hikari connection, and the transaction does not close until L124. The two Graph round-trips — `facebookPageClient.resolvePageAccessToken` (L145, `/me/accounts`) and `creatorMarketplaceClient.search` (L153-157) — both run while that connection is held. There is no page-token cache and no out-of-band enrichment anywhere in the file.

There is no defence of this in the code. The honest answer is that it was built to the letter of TASKS.md:138 ("obtain via `GET /me/accounts` … at call time (no new storage)") and the latency cost was not weighed.

I am accepting it **only because the branch is unreachable**: the property defaults false (`application.yml:391`, `MetaApiProperties.java:60`) and `deploy/utho/generate-env.sh:135` writes `false`. It is not acceptable at flip time — a slow Meta response would exhaust the shared pool and take down the whole API, not just the Discover tab. I am making the caching + async move a **precondition on enabling the flag**, not a follow-up.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Marketplace enrichment holds a DB connection across two synchronous Graph calls inside @Transactional list","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:100-157","issue":"list() is @Transactional; firstUsableBrandToken (L130) acquires the Hikari connection, then resolvePageAccessToken (L145) and creatorMarketplaceClient.search (L153) each make a Meta round-trip while it is held, on every page of the brand Discover tab. A slow Meta response exhausts the shared pool and degrades the entire API, not just this endpoint. Unreachable today only because the flag defaults false.","fix":"Precondition on flipping META_CREATOR_MARKETPLACE_ENABLED: cache the resolved Page access token per workspace (it does not rotate independently of the user token) and move upsertFromMarketplace out of the request transaction into an async job, so list() only ever reads external_creators."}
```

### Q7.4 [WHEN WILL IT BREAK]
> Both compose files pass `META_CREATOR_MARKETPLACE_ENABLED` / `ADMIN_NOTIFICATION_EMAIL` with no `:-` default, unlike `CREATOR_COPILOT_ENABLED: ${CREATOR_COPILOT_ENABLED:-false}`. On a live `.env` that predates this task compose injects an empty string, and `${VAR:false}` only defaults when the var is *unset*. What does Spring's binder do with `""` for the primitive `boolean enabled` — boot failure, or silently `false` — and was this verified on the actual image?

VERDICT: DEFECT

**Boot failure.** Verified, not reasoned about — I ran a probe against the exact artifacts the image builds from (spring-boot 3.3.5, spring-core 6.1.14 per `influora-api/pom.xml:10`), with the env var exported empty:

```
getenv(MCME) = []
A) ${META_CREATOR_MARKETPLACE_ENABLED:false} -> []
A2) ${ADMIN_NOTIFICATION_EMAIL:}             -> []
B) bind ""      -> THREW BindException: Failed to bind properties under
                   'influora.meta.creator-marketplace.enabled' to boolean
                   caused by ConversionFailedException: ... for value [null]
                   caused by IllegalArgumentException: A null value cannot be
                   assigned to a primitive type
C) bind "false" -> enabled=false
D) absent       -> enabled=false
```

Line A is the Tester's premise proven: a set-but-empty var is **set** to Spring (`SystemEnvironmentPropertySource` returns `""`, non-null), so `PropertyPlaceholderHelper` never reaches the `:false` default. Line B is the consequence: `StringToBooleanConverter` maps blank → null, and null cannot go into the primitive at `MetaApiProperties.java:60`. At startup this is a `ConfigurationPropertiesBindException` on the bean registered at `InfluoraApiApplication.java:42` — the API container never comes up. Not silently false.

Compose map-form `${VAR}` always sets the variable (empty on miss): `deploy/hostinger/docker-compose.hostinger.yml:191-192`, `deploy/utho/docker-compose.utho.yml:225-226`. The neighbours get it right — `CREATOR_COPILOT_ENABLED: ${CREATOR_COPILOT_ENABLED:-false}` at hostinger:154 / utho:175. `deploy/utho/generate-env.sh:135` writes `=false`, so a regenerated Utho `.env` is safe; the hand-maintained live Hostinger `.env` is not.

`ADMIN_NOTIFICATION_EMAIL` is harmless — String target, `""` binds cleanly and equals the intended blank default at `application.yml:282`; the listener WARNs and skips. Remaining step: reproduce on the built image.

FINDING:
```json
{"tester":"Priya","type":"Build","severity":"Critical","title":"Empty META_CREATOR_MARKETPLACE_ENABLED from compose fails ConfigurationProperties binding — API will not boot on the live Hostinger VPS","where":"deploy/hostinger/docker-compose.hostinger.yml:191","issue":"Compose map-form ${META_CREATOR_MARKETPLACE_ENABLED} with no :- default always SETS the container var, empty when the .env lacks it. Spring treats set-but-empty as set, so ${META_CREATOR_MARKETPLACE_ENABLED:false} at application.yml:391 resolves to \"\" and never uses the default; binding \"\" to the primitive boolean at MetaApiProperties.java:60 throws BindException -> ConversionFailedException -> 'A null value cannot be assigned to a primitive type'. Verified on spring-boot 3.3.5 / spring-core 6.1.14 (influora-api/pom.xml:10) under JDK 21. The whole API fails to start on the next redeploy of the live Hostinger VPS, whose .env predates this task. Same bare form at deploy/utho/docker-compose.utho.yml:225.","fix":"Use META_CREATOR_MARKETPLACE_ENABLED: ${META_CREATOR_MARKETPLACE_ENABLED:-false} and ADMIN_NOTIFICATION_EMAIL: ${ADMIN_NOTIFICATION_EMAIL:-} in BOTH compose files, matching CREATOR_COPILOT_ENABLED at hostinger:154 / utho:175. Belt-and-braces: make CreatorMarketplace.enabled a Boolean with a null/blank-coalescing setter, the pattern MetaApiProperties already uses at L157, L174, L182 and L190 — that convention would have absorbed this on its own."}
```

### Q7.5 [WHAT TO ADD]
> The migration has no index on `creator_connection_requests(workspace_id)`, no FK from `linked_creator_profile_id`, and `findByIgUsernameIgnoreCase` wraps a column that is already `utf8mb4_unicode_ci` in `LOWER()`. What is the plan for the dormant client to be exercised before App Review, and which schema gaps ship as-is?

VERDICT: GAP

Taking the three claims separately — one is not a gap, two are.

1. **workspace_id index — not a gap, ships as-is.** `uk_ccr_workspace_creator (workspace_id, external_creator_id)` (migration L61) makes `workspace_id` the leftmost prefix, so `findByWorkspaceIdOrderByCreatedAtDesc` (`CreatorConnectionRequestRepository.java:18`) is index-served in InnoDB. The only residual is a filesort on `created_at DESC` within one workspace's rows. No migration warranted.
2. **`LOWER()` defeating the unique key — real, and on the hot path.** `findByIgUsernameIgnoreCase` (`ExternalCreatorRepository.java:24`) makes Spring Data derive `lower(e.igUsername) = lower(?1)`; a function on the column disqualifies `uk_external_creators_username` (migration L42) and forces a full scan. `utf8mb4_unicode_ci` (L45) is already case-insensitive, so `IgnoreCase` buys nothing. Called from `upsertFromMarketplace` L188 and the join hook. Filed below.
3. **No FK on `linked_creator_profile_id`** (migration L35, vs the `fk_ccr_external_creator` style at L63) — real but **ships as-is**: it is a soft link written by a hook that must never fail its caller, and a hard FK there would convert a hook failure into a 500 on the Meta OAuth callback.

**Plan for the dormant client before App Review:** a recorded-fixture test — a captured `creator_marketplace_creators` JSON body (including a permission-error body) asserted through `CreatorMarketplaceClient.search` and `upsertFromMarketplace`, verifying the query string built at L42-56 and that `ig_account_id` is persisted once Q7.2 is fixed. That test is the only thing that will exercise this code before the scope clears review.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"findByIgUsernameIgnoreCase forces LOWER() on ig_username, defeating uk_external_creators_username","where":"influora-api/src/main/java/com/influora/repository/ExternalCreatorRepository.java:24","issue":"Spring Data derives lower(e.igUsername) = lower(?1) from the IgnoreCase keyword; a function on the column makes the unique index at migration V20260902120000 L42 unusable, so every lookup full-scans external_creators. The column is already utf8mb4_unicode_ci (migration L45), i.e. case-insensitive by collation, so IgnoreCase adds nothing. Hit on the Marketplace upsert path (ExternalCreatorService.java:188) and the join hook.","fix":"Rename to findByIgUsername (usernames are already normalised lower-case, no leading @, per migration L24 and normalizeUsername) so the equality predicate hits uk_external_creators_username directly; the ci collation preserves the case-insensitive semantics."}
```
