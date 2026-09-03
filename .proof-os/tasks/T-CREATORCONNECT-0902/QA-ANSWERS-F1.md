# QA answers — F1 (Priya, CTO). T-CREATORCONNECT-0902, branch `fix/f0390-money-flags-build-pipeline`, 2026-09-03.

## F1 — Instagram creators tab + handle lookup (Business Discovery)

### Q1.1 [IS IT WORKING]
> Show me one real end-to-end run of the lookup path: brand types `@handle` (`handleLookup`) → `api.externalCreators.lookup` → `ExternalCreatorService.lookup` → `InstagramInsightsClient.businessDiscovery` returning a non-null `business_discovery` and a row landing in `external_creators`. The only test on this path is `lookup_metaUnconfigured_returns503` — what proves the *happy* path against a live `instagram_basic` token, and which token did it use?

VERDICT: PARTIAL

The chain is wired end-to-end in code and I can trace every hop: `src/components/brand/discover/creator-discovery.tsx:2104-2121` → `src/lib/api.ts:1913-1918` → `influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:212-271` → `influora-api/src/main/java/com/influora/integration/meta/client/InstagramInsightsClient.java:115-128` → `MetaGraphApiClient.java:92-94`, with the row written at `ExternalCreatorService.java:262-264`.

**Nothing proves the happy path.** `ExternalCreatorServiceTest.java` contains exactly four tests (L94, L119, L138, L157); the only lookup test is `lookup_metaUnconfigured_returns503` (L157-158). There is no recorded-fixture test, no captured `business_discovery` JSON, and no run log anywhere in the repo or in `wiki/decisions/2026-09-02-*`. I cannot name the token that was used because no such run is evidenced — asserting one would be a guess.

So: unproven, not disproven. Business Discovery also cannot be exercised without a FACEBOOK_LOGIN token carrying a connected IG business account (`ExternalCreatorService.java:277-305`); no environment in-repo is shown to hold one.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Business Discovery happy path has zero test or recorded-run evidence","where":"influora-api/src/test/java/com/influora/service/ExternalCreatorServiceTest.java:157","issue":"The only lookup test is the Meta-unconfigured 503 case. No test, fixture, or captured run proves a non-null business_discovery response is parsed into BusinessDiscoveryResponse and persisted at ExternalCreatorService.java:262-264. A field-name or shape mismatch in the DTO would ship undetected.","fix":"Add a WireMock/recorded-fixture test that feeds a captured Graph business_discovery body through InstagramInsightsClient.businessDiscovery and asserts the resulting external_creators row (ig_account_id, followers, media_count) before App Review."}
```

### Q1.2 [HOW]
> `firstUsableBrandToken` filters by `revokedFalse` + expiry only — not by `authPath`. If the workspace's first usable row is an INSTAGRAM_LOGIN token, its id + token go to `businessDiscovery`, which uses the 4-arg `get` hard-wired to FACEBOOK_LOGIN. What happens on that host mismatch, and does the creator-token fallback ever get reached once a brand token exists?

VERDICT: GAP

**The premise is unreachable today, but undefended.** `authPath` defaults to `FACEBOOK_LOGIN` (`MetaOAuthToken.java:54`) and the *only* `.authPath(...)` call in the codebase is `MetaTokenStorage.java:321`, inside `storeCreatorToken`, which deliberately omits `workspaceId` (L320). The brand-owned writer `storeToken` (`MetaTokenStorage.java:104-145`) never sets `authPath`. So no workspace-scoped INSTAGRAM_LOGIN row can exist. `MetaOAuthController.callback` is creator-only (`MetaOAuthController.java:127`).

**If one ever did:** an IG-Login token sent to `graph.facebook.com` is rejected by Meta → `RestClientResponseException` → `translate` (`MetaGraphApiClient.java:143-161`) → `MetaTokenExpiredException`/`MetaApiException` → caught at `ExternalCreatorService.java:240-247` → **503 `INSTAGRAM_LOOKUP_UNAVAILABLE`**, not a 500.

**The fallback:** `resolveBusinessDiscoveryCaller` has no early return — L279-288 returns only when the brand token has a non-blank `igBusinessAccountId` *and* `getValidToken` resolves; otherwise it falls through to L290-296. But the guard tests *presence*, not `authPath`, so an INSTAGRAM_LOGIN brand row would be selected and 503, never falling back to a usable creator token.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Low","title":"firstUsableBrandToken does not filter authPath, so a future INSTAGRAM_LOGIN brand row would 503 instead of falling back","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:175-179","issue":"Business Discovery and /me/accounts are FACEBOOK_LOGIN-only (InstagramInsightsClient.java:112-113, MetaGraphApiClient.java:92-94), but firstUsableBrandToken selects on revoked+expiry alone. Unreachable today only because no writer sets authPath on a workspace-scoped row; a future brand IG-Login flow silently breaks lookup and Marketplace enrichment with a misleading 503.","fix":"Add .filter(t -> t.getAuthPath() == MetaAuthPath.FACEBOOK_LOGIN) to firstUsableBrandToken, mirroring the creator-side filter at ExternalCreatorService.java:293, so an unusable row falls through to the creator fallback."}
```

### Q1.3 [WHY NOT THIS WAY]
> The fallback borrows *any creator's* personal FACEBOOK_LOGIN token to run a brand's lookup, and the rate-limit key becomes that creator's `igBusinessAccountId`. Why not a single Influora-owned IG Business account token instead? What did Meta platform-terms review say?

VERDICT: GAP

**No platform-terms review exists.** I grepped `wiki/` and the task directory for platform-terms/borrow language: the only hits are the question itself and `TASKS.md:20-22`, which *mandates* this design ("else any valid creator FACEBOOK_LOGIN token"). So the design is CEO-contract-derived, not terms-reviewed. `wiki/decisions/campaigns/2026-09-02-meta-api-migration-announcement.md:18` is the only Platform Terms citation in the repo and is about endorsement, not token reuse.

**No system caller exists to use instead:** `MetaApiProperties.java` has no Influora-owned IG account property (grep for system/owned returns nothing), so "why not" has no built alternative to compare against.

**The concrete cost is real and cross-tenant.** `MetaGraphApiClient.get` keys throttling on the borrowed creator's `igBusinessAccountId` (`MetaGraphApiClient.java:110-117`), and `MetricsPollingJob` enforces on and marks limited against that *same* key (`MetricsPollingJob.java:204, 253`). Brand Discover traffic can therefore throttle an unrelated creator's own metrics ingestion, and brand feature availability depends on whether some arbitrary creator's token is alive.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Borrowed creator token makes brand lookup traffic throttle that creator's own metrics polling","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:290-305","issue":"resolveBusinessDiscoveryCaller picks an arbitrary creator's FACEBOOK_LOGIN token; MetaGraphApiClient keys rate limiting on that creator's igBusinessAccountId (MetaGraphApiClient.java:110), the same key MetricsPollingJob throttles on (MetricsPollingJob.java:204) and marks limited (L253). Brand Discover volume can silently stall an unrelated creator's analytics ingestion, and no Meta platform-terms review of cross-user token reuse exists in wiki/.","fix":"Introduce an Influora-owned IG Business system caller (new influora.meta.system-ig-* properties + stored token) as the preferred caller ahead of the creator fallback, and escalate the cross-user-token question to Swapnil for a platform-terms ruling before App Review."}
```

### Q1.4 [WHEN WILL IT BREAK]
> Rename collision on `uk_external_creators_ig_account`, and two brands racing the same never-seen handle on `uk_external_creators_username`. 500 to the brand, or handled?

VERDICT: DEFECT

**Not a 500 — a 409, but an unrecoverable one.** Both collisions surface as `DataIntegrityViolationException` at flush/commit and are caught by `GlobalExceptionHandler.handleDataIntegrityViolation` (`GlobalExceptionHandler.java:110-116`) → HTTP 409 `DATA_INTEGRITY_VIOLATION`.

*Rename case:* `lookup` resolves only by username (`ExternalCreatorService.java:254-261`), so it builds row B, then `applyIgAccountId(bd.id())` (L262) writes the id already held by row A (migration L43). Save at L264 fails at commit.

*Race case:* both callers miss at L254, both insert, the loser violates `uk_external_creators_username` (migration L42). There is no `DataIntegrityViolationException` catch or retry anywhere in `lookup`.

**What the brand sees:** `handleLookup` catches `ApiError`, the code is not `INSTAGRAM_LOOKUP_UNAVAILABLE`, so `creator-discovery.tsx:2189-2198` renders the raw message "The request could not be completed due to a data conflict". The renamed creator is then permanently un-lookable — row A keeps the stale handle and every retry fails identically.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Handle rename permanently breaks lookup with an unrecoverable 409; concurrent first-lookup race loses one caller","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:254-264","issue":"lookup resolves by ig_username only, then applies bd.id(). If another row already holds that ig_account_id (creator renamed on Instagram) the save violates uk_external_creators_ig_account; two brands looking up the same new handle concurrently violate uk_external_creators_username. Both become a 409 DATA_INTEGRITY_VIOLATION rendered to the brand as 'data conflict', with no retry and no reconciliation - the renamed creator can never be looked up again.","fix":"In lookup, resolve by externalCreatorRepository.findByIgAccountId(bd.id()) (ExternalCreatorRepository.java:22) before falling back to findByIgUsernameIgnoreCase, and update ig_username on that row (rename reconciliation). Wrap the save in a catch of DataIntegrityViolationException that re-reads by ig_account_id then ig_username and returns the winning row."}
```

### Q1.5 [WHAT TO ADD]
> `fetchList` sends only `page`/`limit`; `q`/`minFollowers`/`maxFollowers` are never sent, and TASKS.md L157 promises follower filters. Where are the filters, and what should happen to never-enriched rows (followers NULL) once any filter is applied?

VERDICT: GAP

**The filters do not exist on the Instagram tab.** `fetchList` sends only `{ page, limit }` (`creator-discovery.tsx:2057`), and I read the whole tab (`creator-discovery.tsx:2022-2300`): it renders a lookup form, a requests panel, the grid and a Load-more button — no search box, no follower slider. The `followerRange` at L668-669 belongs to the Influora tab, not this one. The backend accepts all three (`ExternalCreatorController.java:40-42`). Correction to the question: `ExternalCreatorSpecs` is 48 lines; `withFilters` is at `ExternalCreatorSpecs.java:20-47`, not 677-691.

**NULL semantics, once filters ship:** `cb.greaterThanOrEqualTo(root.get("followers"), min)` (L34) and `lessThanOrEqualTo` (L38) render `followers >= ?`, which is UNKNOWN for NULL, so every never-enriched row silently disappears — that is every ADMIN_IMPORT stub and every META_MARKETPLACE row, since `upsertFromMarketplace` passes `null` for followers (`ExternalCreatorService.java:192-200`). Those rows must stay visible as "followers unknown", consistent with the engagement rule (TASKS.md:158).

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Instagram tab ships no q/follower filters (contract TASKS.md:157) and the backend filter would silently hide every un-enriched row","where":"src/components/brand/discover/creator-discovery.tsx:2057","issue":"fetchList sends only page and limit; no filter control exists anywhere in InstagramCreatorsTab (2022-2300), so the contracted follower filters are absent. When added, ExternalCreatorSpecs.java:33-39 emits followers >= ? / <= ?, which is UNKNOWN for NULL - every ADMIN_IMPORT stub and every META_MARKETPLACE row (followers always null, ExternalCreatorService.java:192-200) would vanish with no explanation.","fix":"Add the search + follower-range controls to InstagramCreatorsTab and pass q/minFollowers/maxFollowers through fetchList; in ExternalCreatorSpecs wrap the follower predicates as cb.or(cb.isNull(followers), cb.ge(...)) or gate them behind an explicit 'only creators with known followers' toggle, and mark unknown-follower cards the way engagement renders '-' rather than 0."}
```
