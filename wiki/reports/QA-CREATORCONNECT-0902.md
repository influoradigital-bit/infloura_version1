# T-CREATORCONNECT-0902 — Tester × Priya Q&A (35 questions, 7 features)

**Date:** 2026-09-02 · **Asked by:** Tester · **Answered by:** Priya (CTO) · **Basis:** staged, uncommitted code on branch `fix/f0390-money-flags-build-pipeline`; every claim cites `path:line`.

## Verdict summary

| Feature | Q1 Is it working | Q2 How | Q3 Why not | Q4 When breaks | Q5 What to add |
|---|---|---|---|---|---|
| F1 Instagram creators tab + handle lookup (Business Discovery) | PARTIAL | GAP | GAP | DEFECT | GAP |
| F2 Badge + honest states | PARTIAL | BY-DESIGN | DEFECT | DEFECT | GAP |
| F3 "Connect this creator" request (dialog → POST /connect, idempotency, 409, admin email) | NOT WORKING | DEFECT | GAP | DEFECT | GAP |
| F4 Admin Creator connections page | PARTIAL | DEFECT | GAP | DEFECT | GAP |
| F5 Join detection hook + brand email | PARTIAL | DEFECT | DEFECT | DEFECT | GAP |
| F6 Create campaign handoff (`?creatorId=` banner + post-create invite) | PARTIAL | DEFECT | GAP | DEFECT | GAP |
| F7 F7 | PARTIAL | DEFECT | ACCEPTED-RISK | DEFECT | GAP |

**Findings:** 31 — Critical 2, High 8, Medium 17, Low 4

Questions: `.proof-os/tasks/T-CREATORCONNECT-0902/QA-QUESTIONS.md` · Findings JSON: `.proof-os/tasks/T-CREATORCONNECT-0902/findings.json` · Dashboard: `wiki/reports/test-report-t-creatorconnect-0902-tester-x-priya-qa-2026-09-02.html`

---
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


# T-CREATORCONNECT-0902 — CTO answers, F2

Answered by Priya (CTO), 2026-09-03, branch `fix/f0390-money-flags-build-pipeline`.
Path shorthand as in QA-QUESTIONS.md: `api/` = `influora-api/src/main/java/com/influora/`, `fe/` = `src/`.

## F2 — Badge + honest states

### Q2.1 [IS IT WORKING]
> Show me the exact rendering path for the three badge/CTA states with real server data, not the vitest fixtures: `toResponse` (`ExternalCreatorService.java:413-435`) → `ExternalCreator` type (`fe/lib/api.ts:1825-1847`) → `ExternalCreatorBadge` (`creator-discovery.tsx:1904-1920`) → `ExternalCreatorCard` footer (`~1996-2018`). In particular, a captured JSON body from `GET /creators/external` on a running API showing `engagementRate: null` and the card rendering "—" (`externalEngagement`, L1926).

VERDICT: PARTIAL

The chain is type-correct and I can trace it statically, but **no live capture exists and none is possible from this tree**: every F2 file is staged-not-committed (`git status --short` → `A influora-api/.../ExternalCreatorService.java`, `A .../ExternalCreatorDtos.java`, `M src/components/brand/discover/creator-discovery.tsx`), so nothing is deployed. The only evidence is `creator-discovery.instagram.test.tsx:112, 146, 164` — fixtures, exactly what the question excludes.

Static trace: `toResponse` (`ExternalCreatorService.java:413-435`) emits `e.getEngagementRate()` (L422) unconverted; `ExternalCreatorResponse.engagementRate` is `BigDecimal` (`ExternalCreatorDtos.java:27`), mirrored as `number | null` (`api.ts:1835`); `externalEngagement` returns `'—'` on `== null` (`creator-discovery.tsx:1926-1928`), rendered at L1979.

Stronger than the question assumes: `engagementRate` can **never** be non-null. All three `applySync` call sites pass literal `null` for that parameter — `ExternalCreatorService.java:196-202` (Marketplace), `:263` (Business Discovery), `AdminCreatorConnectionService.java:344-352` (admin import) — and `ExternalCreator.applySync` (`ExternalCreator.java:200`) only overwrites on non-null. No builder call sets it. So the Engagement tile is permanently "—".

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Low","title":"external_creators.engagement_rate is write-never: the Discover card's Engagement tile can only ever render \"—\"","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:196-202,263","issue":"All three applySync call sites (ExternalCreatorService.java:196-202 Marketplace, :263 Business Discovery, AdminCreatorConnectionService.java:344-352 admin import) pass null for engagementRate, and applySync (ExternalCreator.java:200) only writes on non-null. The column, the DTO field (ExternalCreatorDtos.java:27), the TS field (api.ts:1835) and a third of the card's stat grid (creator-discovery.tsx:1976-1980) are dead weight. Honest per F-0259, but a stat tile no code path can populate.","fix":"Either drop the Engagement tile from ExternalCreatorCard for external rows (leave the column for a future Marketplace insights mapping), or populate it in tryEnrichFromMarketplace from the Marketplace `insights` field the FIELDS list already requests but discards."}
```

### Q2.2 [HOW]
> `ExternalCreatorCard` computes `verified = creator.status === 'JOINED' || creator.verifiedWithInfluora` (`creator-discovery.tsx:~1953`) while the server defines `verifiedWithInfluora = JOINED && linkedCreatorProfileId != null` (`ExternalCreatorService.java:414`). For a row with `status=JOINED` and `linked_creator_profile_id=NULL` the card renders "Verified with Influora", a "View profile" link to `/brand/creators/` (empty id, ~L1999) and a disabled "Create campaign". Walk me through how that state arises (or prove it cannot) and why the FE ORs the two instead of trusting the server flag alone.

VERDICT: BY-DESIGN

It cannot arise through code. `status = JOINED` has exactly one writer, `ExternalCreator.markJoined` (`ExternalCreator.java:231-236`), which sets `status` and `linkedCreatorProfileId` in the same two statements (L232-233). Its only caller is `ExternalCreatorLinkService.java:106`, guarded at L65-67 by `if (creatorProfileId == null || creatorProfileId.isBlank()) return;`. There is no setter for `linkedCreatorProfileId` anywhere else (`grep linkedCreatorProfileId ExternalCreator.java` → L81 field, L157 getter, L233 assignment only) and no other `ExternalCreatorStatus.JOINED` assignment in the tree. So `verifiedWithInfluora` (`ExternalCreatorService.java:414`) is logically equivalent to `status === 'JOINED'`; the FE `||` at L1952 is redundant, not divergent. Only hand-written SQL could produce the split state.

The residual is that the OR *is* written as a defence, and the defence is incomplete: "Create campaign" is guarded (`disabled={!creator.linkedCreatorProfileId}`, L1996-2001) but "View profile" is not — L1993 emits `/brand/creators/${creator.linkedCreatorProfileId ?? ''}`, a dead route on the same impossible row. Fix the asymmetry or drop the OR; do not leave half a guard.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Low","title":"Half-guarded JOINED card: \"Create campaign\" checks linkedCreatorProfileId, \"View profile\" links to /brand/creators/ with an empty id","where":"src/components/brand/discover/creator-discovery.tsx:1993","issue":"L1952 ORs status==='JOINED' with the server's verifiedWithInfluora, admitting a JOINED-but-unlinked row the server flag excludes. The Create-campaign button defends against it (L1996-2001) but the View-profile Link at L1993 falls back to an empty id and navigates to a dead route. The state is unreachable through code (ExternalCreator.markJoined, ExternalCreator.java:231-236, always sets both), so this is defensive-code inconsistency, not a live break.","fix":"Drop the OR and trust the server flag: `const verified = creator.verifiedWithInfluora;`. If the defensive OR is kept deliberately, render \"View profile\" only when linkedCreatorProfileId is non-null, matching the Create-campaign guard."}
```

### Q2.3 [WHY NOT THIS WAY]
> `GlobalExceptionHandler.handleCreatorAlreadyOnInfluora` (`api/common/GlobalExceptionHandler.java:69-77`) puts `linkedCreatorProfileId` on the wire specifically so the FE can offer the create-campaign link on 409, and `api.ts:1918-1922` says callers should. But `submitConnect` (`creator-discovery.tsx:~2140-2160`) catches every `ApiError` into a generic destructive toast and `ApiError.details` is typed only for `InsufficientFundsDetails` (`api.ts:273`). Why ship the 409 payload nobody reads instead of either wiring the FE branch or dropping the field?

VERDICT: DEFECT

Confirmed unreadable, not merely unread. The server does emit it: `ApiErrorBody.creatorAlreadyOnInfluora` (`ApiErrorBody.java:62-64`) passes `linkedCreatorProfileId` as the 9th component, dispatched by `GlobalExceptionHandler.java:69-77` from `ExternalCreatorService.java:324-328`. On the client the field is dropped twice over:

1. `ApiErrorPayload` (`api.ts:215-224`) declares only `code`/`message`/`field`/`fields` and the four `INSUFFICIENT_FUNDS` money fields — no `linkedCreatorProfileId`.
2. Both throw sites (`api.ts:562-567`, `:631-636`) build `ApiError` with `extractInsufficientFundsDetails(envelope.error)` (`api.ts:253-264`), which returns `undefined` unless all four money fields are present. `ApiError.details` is typed `InsufficientFundsDetails | undefined` (`api.ts:267-274`). The id is parsed into `envelope.error` and then discarded before any caller sees it.

So `submitConnect`'s catch (`creator-discovery.tsx:2154-2160`) *could not* branch on it even if it wanted to. The brand sees a red "Could not send request / This creator is already on Influora" toast and the card keeps showing "Connect this creator" — a dead end at the exact moment the answer ("they're already here, campaign them") is sitting in the response body. The api.ts:1918-1922 doc comment instructs a branch the type system forbids.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"409 CREATOR_ALREADY_ON_INFLUORA carries linkedCreatorProfileId that the FE type system discards — brand hits a dead-end toast","where":"src/lib/api.ts:215-224","issue":"ApiErrorBody.creatorAlreadyOnInfluora (ApiErrorBody.java:62-64) puts linkedCreatorProfileId on the wire and api.ts:1918-1922 tells callers to use it, but ApiErrorPayload (api.ts:215-224) never declares the field and both ApiError throw sites (api.ts:562-567, :631-636) populate `details` only via extractInsufficientFundsDetails (api.ts:253-264), so the id is unreachable. submitConnect (creator-discovery.tsx:2154-2160) therefore shows a generic destructive toast and the card still reads \"Connect this creator\". TASKS.md L87 specified this payload for a create-campaign CTA that does not exist.","fix":"Add `linkedCreatorProfileId?: string` to ApiErrorPayload, widen ApiError.details to a discriminated union (or add a sibling `creatorAlreadyOnInfluora` extractor next to extractInsufficientFundsDetails), and branch in submitConnect on err.code === 'CREATOR_ALREADY_ON_INFLUORA' to patch the card to JOINED and toast with a Create-campaign action. If the CTA is not wanted, delete the field from ApiErrorBody and the api.ts doc comment instead of shipping an unread payload."}
```

### Q2.4 [WHEN WILL IT BREAK]
> Lookup and list have different unavailable semantics: `fetchList` treats *only* `err.status === 503` as "unavailable" (`creator-discovery.tsx:~2062`), but `GET /creators/external` never returns 503 — `list()` reads the table and swallows Marketplace failures (`ExternalCreatorService.java:100-125, 164-172`). So `data-testid="instagram-unavailable"` (the state the vitest at `creator-discovery.instagram.test.tsx:164` asserts) is only reachable in a mocked test. What does a brand see when the API is actually down (network error, 502 from nginx) — and is a 502 from the reverse proxy an `ApiError` with `status` at all?

VERDICT: DEFECT

Your premise holds: `list` (`ExternalCreatorService.java:100-125`) throws nothing 503-shaped — `tryEnrichFromMarketplace` swallows `MetaApiException` (L165-171) and `Exception` (L172-173). A 502 *is* an `ApiError` with `status`: nginx returns an HTML body, `parseEnvelope` (`api.ts:580-595`) fails `JSON.parse` and throws `ApiError('SERVER_UNAVAILABLE', …, res.status)` with `status = 502` (L586-592). A dropped connection rejects `fetch` with a `TypeError`, not an `ApiError`.

Both land in `fetchList`'s else branch (`creator-discovery.tsx:2065-2073`): `listCreators` is cleared, `listError` is set, a toast fires. Then the render gate misfires — `showUnavailable` is false, `showInitialLoading` is false, so `showEmpty` is **true** (`creator-discovery.tsx:2165-2167`) and the tab renders `data-testid="instagram-empty"` / "No Instagram creators sourced yet — Look up a handle above, or ask an admin to import one" (L2262-2271). `listError` is only rendered in the *non-empty* branch (L2274-2276), so on a first-load outage it is unreachable; the toast is the only signal and it auto-dismisses. An outage is presented to the brand as "this data does not exist" — the F-0259/F-0260 class the tab was built to avoid. Only a proxy 503 (HTML → status 503) reaches the honest state, by accident.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"API outage on the Instagram tab renders the honest-empty state: \"No Instagram creators sourced yet\" instead of an error","where":"src/components/brand/discover/creator-discovery.tsx:2165-2167","issue":"fetchList treats only err.status === 503 as unavailable (L2062), but GET /creators/external never returns 503 (ExternalCreatorService.java:100-125 swallows all Marketplace failures at L164-173). A 502 from nginx becomes ApiError('SERVER_UNAVAILABLE', status 502) via parseEnvelope (api.ts:580-595) and a dropped connection is a TypeError; both clear listCreators and set listError, after which showEmpty (L2165-2167) is true and the tab shows data-testid=\"instagram-empty\" / \"No Instagram creators sourced yet\". listError is rendered only in the non-empty branch (L2274-2276), so it never displays on a first-load failure — the brand is told the data does not exist when the server is down. F-0259/F-0260 empty-state-honesty class.","fix":"Add a distinct listFailed state: treat err.code === 'SERVER_UNAVAILABLE', status 502/503/504, and non-ApiError network errors as unavailable/failed, and gate showEmpty on `!listError && !listFailed` so a failed load renders an error panel with Retry, never the empty state. Add a vitest that a 502 and a rejected fetch each render the error state and not instagram-empty."}
```

### Q2.5 [WHAT TO ADD]
> `ExternalCreatorBadge` collapses `UNVERIFIED` and `INVITED` into the same amber badge (`creator-discovery.tsx:1904-1920`) and the brand-side request list shows only `connectionRequestStatusLabel` (L1930-1942). A brand whose request is `CONTACTED` sees "Team reached out" but nothing distinguishes "admin emailed an invite 3 weeks ago, no reply" from "admin phoned yesterday". What `invitedAt`/`handledAt` surfacing is missing before a brand can decide whether to wait or move on, given `ConnectionRequestResponse` already carries `handledAt` (`ExternalCreatorDtos.java:47`)?

VERDICT: GAP

Two different gaps, one already free.

**`handledAt` — on the wire, thrown away in the UI.** `ConnectionRequestResponse` carries `createdAt` (`ExternalCreatorDtos.java:45`) and `handledAt` (`:47`); both are mirrored in TS (`api.ts:1858`, `:1860`). The requests panel (`creator-discovery.tsx:2205-2222`) renders only `@{r.igUsername}` (L2214) and `connectionRequestStatusLabel(r.status)` (L2217). Nothing else. Adding "Team reached out · 21 days ago" needs no backend change at all.

**`invitedAt` — not on the wire anywhere.** It exists on the table (TASKS.md L49) and the entity (set in `ExternalCreator.java:225-227`), but appears in no brand-facing record: not in `ExternalCreatorResponse` (`ExternalCreatorDtos.java:19-36`) and not in `ConnectionRequestResponse` (`:38-49`). It reaches only the admin console via `AdminConnectionDto`. So "admin actually emailed an invite" versus "admin marked contacted" is invisible to the brand — which is also why the amber badge collapsing `UNVERIFIED`/`INVITED` (L1904-1920) has no user-visible cost today: the brand has no signal to distinguish them with.

Minimum to make the panel decision-useful: add `invitedAt` to `ExternalCreatorResponse` and mirror it in `api.ts`; render relative `createdAt`/`handledAt`/`invitedAt` on each request row; split the badge to "Invited to Influora" for `INVITED`.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Low","title":"Brand connection-request panel renders no dates: handledAt is on the wire and unused, invitedAt is on no brand DTO at all","where":"src/components/brand/discover/creator-discovery.tsx:2213-2219","issue":"ConnectionRequestResponse already ships createdAt (ExternalCreatorDtos.java:45) and handledAt (:47), mirrored at api.ts:1858,1860, but the My-connection-requests panel (creator-discovery.tsx:2205-2222) renders only the handle and the status label — a brand cannot tell a 3-week-stale CONTACTED from yesterday's. Separately, external_creators.invited_at (set in ExternalCreator.java:225-227) is absent from both brand DTOs (ExternalCreatorDtos.java:19-36 and :38-49), so \"an invite was actually emailed\" never reaches the brand, and the amber badge collapsing UNVERIFIED/INVITED (L1904-1920) has no signal to split on.","fix":"Render relative createdAt and handledAt on each request row (no backend change needed). Add `Instant invitedAt` to ExternalCreatorResponse, populate it in toResponse (ExternalCreatorService.java:413-435), mirror it in api.ts's ExternalCreator, and give INVITED its own badge copy (\"Invited to Influora\") with the date."}
```


# QA answers — T-CREATORCONNECT-0902 (Priya, CTO)

Answered against the working tree on `fix/f0390-money-flags-build-pipeline`, 2026-09-03. Path shorthand as in QA-QUESTIONS.md (`api/` = `influora-api/src/main/java/com/influora/`, `fe/` = `src/`).

## F3 — "Connect this creator" request (dialog → POST /connect, idempotency, 409, admin email)

### Q3.1 [IS IT WORKING]
> Show me the admin email actually arriving: `connect` publishes `CreatorConnectionRequestedEvent` (`ExternalCreatorService.java:363-373`) → `NotificationListener.on(CreatorConnectionRequestedEvent)` (`api/service/notification/NotificationListener.java:635-662`) → `msg91EmailClient.sendTemplateEmail(adminNotificationEmail, "admin.creator_connection_requested", …)`. With `ADMIN_NOTIFICATION_EMAIL` blank in `deploy/utho/generate-env.sh:141` the listener WARNs and skips (L638-644). On which environment has this email been received, and what was `admin_url` resolved to (`webBaseUrl + "/admin/creator-connections"`, L651) — does that route exist unauthenticated or does it bounce to admin login?

VERDICT: NOT WORKING

On no environment, and I can produce no evidence that it ever has. The recipient is bound from `influora.admin.notification-email` (`api/service/notification/NotificationListener.java:104`), which is `${ADMIN_NOTIFICATION_EMAIL:}` (`influora-api/src/main/resources/application.yml:282`). Every provisioning path leaves it empty: `deploy/utho/generate-env.sh:141` writes `ADMIN_NOTIFICATION_EMAIL=` deliberately, and both compose files interpolate with no `:-` default (`deploy/hostinger/docker-compose.hostinger.yml:192`, `deploy/utho/docker-compose.utho.yml:226`), so a pre-existing `.env` injects `""`. Blank → WARN + `return` at `NotificationListener.java:638-644`; `sendTemplateEmail` is never reached.

The in-repo proof stops at the publish: `ExternalCreatorServiceTest.java:138-155` verifies one `CreatorConnectionRequestedEvent` is published. No test, log, or captured message covers the listener or the send. The template itself is registered (`api/integration/msg91/EmailTemplateRegistry.java:327-334`).

`admin_url` resolves to `{webBaseUrl}/admin/creator-connections`. That route exists (`fe/pages/admin-console.tsx:64`) but is behind `AdminProtectedRoute`, which redirects to `/admin/login` without an admin token (`fe/App.tsx:157`, route at `fe/App.tsx:628-633`) — correct behaviour, not a defect.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Admin connection-request email is dark on every deploy target (ADMIN_NOTIFICATION_EMAIL blank/unset)","where":"deploy/utho/generate-env.sh:141","issue":"generate-env.sh writes ADMIN_NOTIFICATION_EMAIL= and both compose files pass ${ADMIN_NOTIFICATION_EMAIL} with no :- default, so NotificationListener.java:638-644 WARNs and skips. Step 3 of the flow (enquiry reaches the admin team) never fires; the admin console page is the only way a request is ever seen.","fix":"Set a real address in the deploy .env (and a :-default in both compose files), then prove one delivery end-to-end; until then treat the admin console as the sole notification channel and say so in the handoff."}
```

### Q3.2 [HOW]
> Reopen after DECLINED: `existing.reopen(message)` (`CreatorConnectionRequest.java:189-197`) resets status/notes but keeps the original `requestedByUserId`, while the event is published with `principal.getUserId()` (`ExternalCreatorService.java:365-366`). If a *different* member of the same workspace re-requests, whose inbox gets the `brand.connected_creator_joined` email later (`ExternalCreatorLinkService.java:126-133` uses `request.getRequestedByUserId()`) — and what if that original user has since been removed from the workspace (`emailOf`, `NotificationListener.java:126-131`)?

VERDICT: DEFECT

Confirmed. `reopen` rewrites status, message, notes, handledBy/At and joinedNotifiedAt but never `requestedByUserId` (`api/domain/entity/CreatorConnectionRequest.java:113-121`); the field is set only at build time (`ExternalCreatorService.java:352-358`). So the join email goes to the *original* requester: `ExternalCreatorLinkService.java:128` passes `request.getRequestedByUserId()` into `ConnectedCreatorJoinedEvent`, and the listener resolves the recipient from that id via `emailOf` (`NotificationListener.java:126-131`, used at the `notify(...)` call in `on(ConnectedCreatorJoinedEvent)`). The colleague who actually re-requested — and who saw the "we'll email you when they join" toast (`fe/components/brand/discover/creator-discovery.tsx:2148-2151`) — gets nothing. The admin email is correct, because it is published with `principal.getUserId()` and `workspace.getName()` (`ExternalCreatorService.java:365-373`).

If the original user was removed, `userRepository.findById(...)` returns empty → `emailOf` returns null → `queueEmailIfNotUnsubscribed` logs a warning and queues nothing (`api/service/notification/NotificationService.java:86,128-131`), while the in-app row is still written against the dead user id. `joined_notified_at` is then stamped anyway, so the miss is permanent and silent.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Join notification goes to a stale requester and is silently lost if that user was removed","where":"influora-api/src/main/java/com/influora/domain/entity/CreatorConnectionRequest.java:113-121","issue":"reopen() keeps the original requestedByUserId, so after a DECLINED reopen by a different member the brand.connected_creator_joined email (ExternalCreatorLinkService.java:128) reaches the wrong colleague; if that user is gone, emailOf returns null, NotificationService.java:128-131 queues nothing, and joined_notified_at is stamped regardless — the headline 'creator joined' promise is dropped with only a WARN.","fix":"Take requestedByUserId in reopen(message, userId) and set it from principal; in the listener, fall back to the workspace owner/admins when emailOf(userId) is null, and only stamp joined_notified_at when an email was actually queued."}
```

### Q3.3 [WHY NOT THIS WAY]
> The admin notification bypasses `NotificationService`/`EmailOutbox` and calls `Msg91EmailClient` directly from an `@Async` listener (`NotificationListener.java:652-656`), so a transient SMTP failure is a single `log.error` and the request is lost to admins forever — the console page is the only recovery. Why not queue it through the outbox (with retries/backoff that `EmailWorker` already gives every other template) using a synthetic admin recipient, the way `creator.not_connected` gets special-cased for a non-user recipient?

VERDICT: GAP

The bypass is deliberate and documented (`NotificationListener.java:630-635`): `NotificationService.notify` writes an in-app `Notification` keyed on `event.userId()` (`NotificationService.java:86` and the `createInAppNotification` it calls) and there is no admin user row to own it, so the standard pipeline would either create an in-app notification for the wrong user or need a new admin channel.

The durability consequence is nonetheless real and not mitigated. `Msg91EmailClient.sendTemplateEmail` returns `false` when SMTP is unconfigured or the send throws outside dev (`api/integration/msg91/Msg91EmailClient.java:129,132-148`), and the listener's entire response to `false` is one `log.error` (`NotificationListener.java:657-661`). Nothing retries, nothing marks the request, no metric.

`creator.not_connected` is not the precedent the question suggests: it is special-cased in `EMAIL_ONLY_EVENTS` (`NotificationService.java:42`) to suppress the *in-app* half, but its recipient is still a real `User`. The outbox does support a null `userId` for pre-account recipients (`Msg91EmailClient.java:124-125`), so an outbox row with `userId=null` and the admin address is the correct fix — it inherits `EmailWorker`'s retry/backoff/FAILED handling for free.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Admin connection-request email has no retry: a transient SMTP failure loses the notification permanently","where":"influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:652-661","issue":"The @Async listener calls Msg91EmailClient directly; sendTemplateEmail returning false (Msg91EmailClient.java:132-148) produces one log.error and nothing else — no outbox row, no retry, no marker on creator_connection_requests. The admin console is the only recovery and nothing tells anyone to look.","fix":"Queue an EmailOutbox row with userId=null (the pre-account recipient path the client already supports) so EmailWorker retries with backoff, keeping the in-app half suppressed as today."}
```

### Q3.4 [WHEN WILL IT BREAK]
> Two clicks / two tabs POST `/creators/external/{id}/connect` concurrently for the same (workspace, creator): both `findByWorkspaceIdAndExternalCreatorId` return empty (`ExternalCreatorService.java:335-337`), both build a new row (L351-358), and the second `save` hits `uk_ccr_workspace_creator` (migration L61). Unlike `CreatorDiscoveryService.invite` (L492-498) there is no `DataIntegrityViolationException` catch here — is that a 500 to the brand and a duplicate admin email from the first, and does the "Request sent" FE state survive the 500?

VERDICT: DEFECT

The premise is right about the race and wrong about the 500. There is no local catch in `connect` (`ExternalCreatorService.java:336-361`), unlike `CreatorDiscoveryService.java:493-499` which maps the dup to a 409 `COLLABORATION_EXISTS`. But `GlobalExceptionHandler.java:110-115` catches `DataIntegrityViolationException` globally and returns **409 `DATA_INTEGRITY_VIOLATION`**, not a 500. The ULID is assigned, so the insert flushes at commit — still inside the transactional proxy, still translated, still caught.

Only one admin email: the losing transaction rolls back before commit, and the listener is `AFTER_COMMIT` (`NotificationListener.java:635-637`), so its event is never delivered.

The user-visible defect is the toast. The contract (TASKS.md L87) says a duplicate is idempotent 200 with the existing request; instead the second tab gets a 409 whose message is "The request could not be completed due to a data conflict", rendered by `submitConnect`'s generic catch as a destructive "Could not send request" (`fe/components/brand/discover/creator-discovery.tsx:2154-2159`). That tab never runs `applyConnectionResult` (L2146), so its card stays on "Connect this creator" until a refetch — the brand is told the request failed when it succeeded.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Concurrent connect returns 409 DATA_INTEGRITY_VIOLATION instead of the contracted idempotent 200","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:336-361","issue":"Double-click/two-tab POSTs race past findByWorkspaceIdAndExternalCreatorId; the loser violates uk_ccr_workspace_creator and falls through to GlobalExceptionHandler.java:110-115 (409 DATA_INTEGRITY_VIOLATION). The brand sees 'Could not send request' and a card still offering 'Connect this creator' although the request was created. Not a 500, and only one admin email (the loser rolls back before the AFTER_COMMIT listener).","fix":"Wrap the save in try/catch (DataIntegrityViolationException) as CreatorDiscoveryService.java:493-499 does, re-read the row, and return it as the idempotent 200 the contract specifies; also disable the dialog's Send button on the pending promise."}
```

### Q3.5 [WHAT TO ADD]
> `connect` accepts any `id` that exists in `external_creators` regardless of `source`/`status`, and there is no per-workspace cap: a brand can create one request per external creator across the whole table (`list` returns *all* statuses, `ExternalCreatorService.java:111`), each firing an admin email. What rate/volume guard (per-workspace daily cap, or requiring the creator to have been looked up by this workspace) is missing before an admin inbox can be flooded by one brand clicking through 20 pages of imported handles?

VERDICT: GAP

Confirmed, and nothing anywhere on the path limits it. `connect` loads by id and rejects only a JOINED-and-linked creator (`ExternalCreatorService.java:313-328`); `source` and the UNVERIFIED/INVITED statuses are never consulted. `list` passes `null` as the status filter (`ExternalCreatorService.java:111`), so every ADMIN_IMPORT stub in the table is connectable by every workspace, and nothing scopes rows to the workspace that discovered them. The controller carries no rate-limit annotation or filter (`api/web/ExternalCreatorController.java:58-66`) — only `@Valid` on the body. The per-request cost is one row plus one admin email each (`ExternalCreatorService.java:361-373`).

Missing, in the order I would add them: (1) a per-workspace daily cap on new requests (config-driven, 409/429 past it) — the single cheapest guard; (2) a per-workspace de-dup window so reopen-after-DECLINED cannot be looped; (3) digest or batching of `admin.creator_connection_requested` when a workspace fires several within a window; (4) optionally requiring the row to have been looked up or listed by that workspace, though I would not gate on that — it breaks the admin-import discovery case the task exists for.

This is latent while Q3.1's recipient is blank, but ships the moment an address is set.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"No per-workspace cap or rate limit on POST /creators/external/{id}/connect","where":"influora-api/src/main/java/com/influora/web/ExternalCreatorController.java:58-66","issue":"connect accepts any external_creators id regardless of source/status (ExternalCreatorService.java:313-328) and list exposes every row unfiltered (L111); one brand can create a request per creator across the whole table, each publishing an admin email (L361-373). No annotation, filter or counter caps this.","fix":"Add a config-driven per-workspace daily cap on new/reopened requests returning 429, plus digest/batching of admin.creator_connection_requested when several fire inside a window."}
```


# QA-ANSWERS-F4 — T-CREATORCONNECT-0902 (Priya, CTO)

> Note on citations: the Tester's line numbers for `AdminCreatorConnectionService.java` are offset
> (invite is at 188-237, not 321-369; importHandles at 295-379, not 428-511). All `path:line`
> below are the real lines on `fix/f0390-money-flags-build-pipeline` as of 2026-09-03.

## F4 — Admin Creator connections page

### Q4.1 [IS IT WORKING]
> Show me a real admin invite round-trip: `CreatorConnectionsPage` action dialog → `creatorConnectionsApi.invite` → `AdminCreatorConnectionService.invite` → `sendJoinInvitationEmail` → a `creator.join_invitation` email in a real inbox whose "Join Influora" CTA opens `{webBaseUrl}/creator/register?ref=influora-invite&handle=…`. Include the `AdminAuditLogService` rows for that run.

VERDICT: PARTIAL

The chain is fully wired with no gap: `src/admin/pages/CreatorConnectionsPage.tsx:492` (Invite button) → `:273` → `src/admin/services/api-contracts.ts:725-729` → `AdminCreatorConnectionController.java:72-79` → `AdminCreatorConnectionService.java:188-237` → `sendJoinInvitationEmail` `:239-258` → `Msg91EmailClient.sendTemplateEmail` (`Msg91EmailClient.java:104`), template registered at `EmailTemplateRegistry.java:336-343`. Audit rows are written at `:216-224` (`UPDATE EXTERNAL_CREATOR`) and `:225-233` (`UPDATE CREATOR_CONNECTION_REQUEST`).

I have no evidence of a real run. **Zero** backend tests reference this service (`grep -rl AdminCreatorConnectionService influora-api/src/test/` returns nothing); nothing in `.proof-os/journal.jsonl`. The only test is the FE email gate at `CreatorConnectionsPage.test.tsx:91`, which mocks the API.

Worse, the round-trip cannot be observed by the admin either. `apiRequest` never throws on an HTTP error — it returns `{success:false}` (`api-contracts.ts:95-105`) — and `actionMutation.onSuccess` (`CreatorConnectionsPage.tsx:275-284`) closes the dialog at `:282` regardless of `res.success`, with no success toast (TASKS.md L173 requires "Invitation sent to …") and no error surface.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Admin invite/contact/decline failures are silent — dialog closes as if they succeeded","where":"src/admin/pages/CreatorConnectionsPage.tsx:275-284","issue":"apiRequest returns {success:false} instead of throwing (api-contracts.ts:95-105), so onSuccess fires for 404/409/500 too. It skips invalidation but still calls setActiveAction(null) at :282. There is no onError, no success toast, and no failure banner — an invite that never sent looks identical to one that did.","fix":"In onSuccess, branch on res.success: on false surface res.error in the dialog and keep it open; add onError for network failures; add the 'Invitation sent to {email}' success toast the contract requires."}
```

### Q4.2 [HOW]
> Walk me through why the email is sent pre-commit (what happens if the commit then fails on `external_creators.email` length 255) and why the workspace name was dropped when the contract templates `{{brand_name}}`.

VERDICT: DEFECT

**Pre-commit.** `sendJoinInvitationEmail(external)` is the last statement before `return` (`AdminCreatorConnectionService.java:235-236`) inside `@Transactional invite` (`:188`). Spring commits *after* the method returns, so the MSG91 call happens while the transaction is still open. The 255 case is real: `InviteRequest` validates `@NotBlank @Email` only, with no `@Size` (`AdminCreatorConnectionDtos.java:66-68`), while the column is `length = 255` (`ExternalCreator.java:73-74`). A 300-char syntactically valid address passes, `markInvited` sets it (`:210`; entity `:220-227`), and the flush at commit throws `DataIntegrityViolationException` → 409 `DATA_INTEGRITY_VIOLATION` (`GlobalExceptionHandler.java:110-114`). The status flip, `invited_at`, the creator email and **both audit rows** roll back — but the creator already has the invite, and the console still shows UNVERIFIED / never invited.

**brand_name.** Hard-coded `"A brand"` at `:241`, though the request row carries `workspaceId` and `toDtos` already resolves `w.getName()` at `:504`. The registry subject is `{{brand_name}} wants to work with you on Influora` (`EmailTemplateRegistry.java:337`), so every creator receives "A brand wants to work with you on Influora". No comment explains the drop; TASKS.md L146 requires the name.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Invite email hard-codes brand_name to 'A brand' although the workspace name is already resolved","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:241","issue":"The creator.join_invitation subject templates {{brand_name}} (EmailTemplateRegistry.java:337) but invite() passes the literal 'A brand', so every invite reads 'A brand wants to work with you on Influora'. The request row has workspaceId and toDtos already loads Workspace.getName() at :504 — the data was in hand.","fix":"Pass the CreatorConnectionRequest into sendJoinInvitationEmail, look up the Workspace by request.getWorkspaceId(), and use its name; fall back to 'A brand' only when the workspace row is gone."}
```
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Join-invitation email is sent before commit, so a rolled-back invite still reaches the creator","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:235","issue":"sendJoinInvitationEmail runs inside @Transactional invite (:188). If the commit fails — e.g. an email longer than the 255-char column (ExternalCreator.java:73-74), which InviteRequest does not bound (AdminCreatorConnectionDtos.java:66-68) — the status flip, invited_at and both audit rows roll back while the creator has already been emailed.","fix":"Add @Size(max=255) to InviteRequest.email, and move the send to an AFTER_COMMIT step (application event or TransactionSynchronization) so no email is sent for a transaction that did not commit."}
```

### Q4.3 [WHY NOT THIS WAY]
> Why not enrich asynchronously so an admin paste doesn't hold a connection through 50 Graph round-trips, a single `MetaRateLimitException` doesn't silently leave 49 stubs un-enriched with no retry, and the rate cost doesn't land on one arbitrary creator's account?

VERDICT: GAP

Every premise checks out, and nothing in the code or TASKS.md justifies the synchronous shape — TASKS.md L119 specifies the semantics only, not the transaction boundary. This is an undocumented gap, not an accepted risk.

- `importHandles` is `@Transactional` (`:295`) and loops up to 50 handles (`:314-366`), each doing a blocking `businessDiscovery` call (`:338-340`). The pooled DB connection is held for the whole sequence.
- `MetaRateLimitException extends MetaApiException` (`MetaRateLimitException.java:6`), so the per-handle catch at `:354-356` swallows it at INFO. Remaining handles re-enter `get()` and immediately re-throw the pre-flight throttle (`MetaGraphApiClient.java:109-113`), so all of them land as un-enriched stubs. `ImportResult` cannot express this: `skipped` collects only blank-after-normalize handles (`:319-322`), so the admin sees `enriched 0` with no cause and no retry path — there is no re-enrich endpoint.
- `resolveAnyBusinessDiscoveryCaller` (`:385-399`) takes `findFirst()` of any FACEBOOK_LOGIN token, so all 50 calls bill one arbitrary account's quota, keyed on its `igBusinessAccountId` (`MetaGraphApiClient.java:109`).

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Bulk import runs up to 50 blocking Graph calls inside one transaction and reports rate-limited handles as ordinary imports","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:295-366","issue":"@Transactional importHandles holds a pooled DB connection across up to 50 synchronous Business Discovery round-trips. MetaRateLimitException is a MetaApiException subclass (MetaRateLimitException.java:6), so the catch at :354 swallows throttling; once MetaGraphApiClient's pre-flight threshold (:109-113) trips, every remaining handle silently becomes an un-enriched stub and ImportResult still counts it as imported, with no retry surface.","fix":"Persist the stubs in a short transaction, then enrich out of band (a job keyed on lastSyncedAt IS NULL). Catch MetaRateLimitException separately, stop the loop, and return the un-enriched handles so the admin knows to retry."}
```

### Q4.4 [WHEN WILL IT BREAK]
> An admin pastes `foo bar`, `foo)`, or an 81-char line. What does the admin see in each case, and how do they delete a garbage stub given there is no delete endpoint?

VERDICT: DEFECT

Confirmed: `normalizeUsername` (`:553-556`) only trims, lower-cases and strips a leading `@`. `ExternalCreatorService.USERNAME_PATTERN` (`ExternalCreatorService.java:58`) is `private` to that class and never applied here, and there is no length check against the 80-char column (`ExternalCreator.java:44`).

- **`foo bar` / `foo)`** — interpolated raw into the Graph path (`InstagramInsightsClient.java:117-122`). Meta rejects it, `MetaApiException` is caught at `:354` and logged at INFO, and the row is **still saved** at `:359` as an ADMIN_IMPORT stub with the garbage handle and counted in `imported`. The admin sees only "Imported 2, enriched 0" (`CreatorConnectionsPage.tsx:577-581`). The stub then appears in the brand-facing Discover list.
- **81 chars** — passes every check; the flush at commit throws, and `GlobalExceptionHandler.java:110-114` returns **409 `DATA_INTEGRITY_VIOLATION`, not a 500** (correcting the question's assumption). `@Transactional` rolls the whole batch back and the FE shows "The request could not be completed due to a data conflict" (`:297`, `:583`) without naming the offending handle.
- **Deletion** — confirmed impossible. `AdminExternalCreatorController.java` exposes only GET (`:35-43`) and POST `/import` (`:45-51`); no `@DeleteMapping` in either admin controller. Garbage stubs are permanent and brand-visible.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Admin import accepts unvalidated handles: garbage is persisted brand-visibly and one long line rolls back the whole batch","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:318-359","issue":"normalizeUsername (:553-556) applies neither ExternalCreatorService.USERNAME_PATTERN (ExternalCreatorService.java:58) nor the 80-char column limit (ExternalCreator.java:44). Invalid handles are interpolated raw into the Graph path (InstagramInsightsClient.java:117-122) and, after Meta rejects them, still saved at :359 and counted as imported; an 81-char handle fails at flush, returning 409 and discarding all 50. There is no delete endpoint (AdminExternalCreatorController.java has only GET and POST /import), so a bad stub is permanent and shows in brand Discover.","fix":"Promote USERNAME_PATTERN to a shared validator and apply it plus a length<=80 check in the import loop, routing rejects into ImportResult.skipped instead of persisting them; add an audit-logged DELETE /admin/external-creators/{id} guarded when connection requests reference the row."}
```

### Q4.5 [WHAT TO ADD]
> Beyond fixing the `AdminConnection` type, what is missing for this page to be operable at volume?

VERDICT: GAP

The type mismatch is confirmed and wider than reported: `src/admin/types/admin.types.ts:617` `brandName`, `:619` `requestedByEmail`, `:621` `igUsername` and `:626` `creatorStatus` are all non-null `string`, while `toDtos` emits `null` for each (`AdminCreatorConnectionService.java:504, 506, 508, 513`). `creator_connection_requests` carries an FK only on `external_creator_id` (TASKS.md L73), so `brandName`/`requestedByEmail` genuinely go null once a workspace or user row is deleted; those render blank at `:442`/`:188` rather than crashing. `igUsername` is protected only by that FK — `CreatorConnectionsPage.tsx:447` calls `c.igUsername.charAt(0)` unguarded, so the lie is one schema change away from a render crash.

Also missing for volume:
- `search` loads **every** matching workspace and external creator into memory via unpaginated `findAll` (`:434-445`) to build an unbounded `IN` list (`:421-427`).
- The external-creators table is pinned to `page: 1, pageSize: 20` (`CreatorConnectionsPage.tsx:266`) with no pager and neither the `status` nor `q` filter wired, though the endpoint accepts both (`AdminExternalCreatorController.java:38-41`).
- No backend test exists for this service at all; the sole FE test is the email gate (`CreatorConnectionsPage.test.tsx:91`).

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"AdminConnection TS type declares four fields non-null that the Java record emits as null","where":"src/admin/types/admin.types.ts:617-626","issue":"brandName (:617), requestedByEmail (:619), igUsername (:621) and creatorStatus (:626) are typed `string`, but toDtos emits null for each when the workspace, user or external-creator row is missing (AdminCreatorConnectionService.java:504,506,508,513). Only external_creator_id has an FK (TASKS.md L73). CreatorConnectionsPage.tsx:447 calls c.igUsername.charAt(0) with no guard — the same FE-asserts-a-field-the-record-may-not-send class as F1/PHONE-0829 P3.","fix":"Widen all four to `| null` in admin.types.ts and render explicit fallbacks (an em dash for brandName at :442 and :188, a safe initial at :447); separately, page the external-creators table and wire its status/q filters, and replace the in-memory search IN-list (:434-445) with a join."}
```


# QA answers — F5 (Priya, CTO). T-CREATORCONNECT-0902, branch `fix/f0390-money-flags-build-pipeline`, 2026-09-03.

Path shorthand as in QA-QUESTIONS.md. Line numbers below are the ones I read today; where the question's
citation drifted I give the real one (Q5.5's `signup_url` is at `AdminCreatorConnectionService.java:244-245`,
not 375-377 — that range is `resolveAnyBusinessDiscoveryCaller`).

## F5 — Join detection hook + brand email

### Q5.1 [IS IT WORKING]
> Show me the full live path for the one call site that carries both id and username: creator completes FACEBOOK_LOGIN → `CreatorMetaOAuthService` L138-148 → `MetaTokenStorage.storeCreatorToken` 8-arg → `ExternalCreatorLinkService.onCreatorIdentified` → `ConnectedCreatorJoinedEvent` → `NotificationListener.on(...)` → in-app row + `brand.connected_creator_joined` email → `joined_notified_at` stamped. Which environment has produced a row with `joined_notified_at IS NOT NULL`?

VERDICT: PARTIAL

The wiring is complete and I can walk every hop in source:
`CreatorMetaOAuthService.java:138-148` passes `igAccount.username()` — real, because
`FacebookPageClient.java:23-24` requests `instagram_business_account{id,username,followers_count}` —
into the 8-arg `MetaTokenStorage.storeCreatorToken` (`MetaTokenStorage.java:285-293`), which calls the
hook after `repository.save(entity)` at `MetaTokenStorage.java:346`. The hook matches by id
(`ExternalCreatorLinkService.java:76-78`), flips the row (`:106`), flips open requests and publishes one
event each (`:109-134`). `NotificationListener.java:671-673` is `@Async @TransactionalEventListener(AFTER_COMMIT)`,
notifies at `:683-695` with template `brand.connected_creator_joined` and
`campaign_url = webBaseUrl + "/brand/campaigns/new?creatorId=" + creatorProfileId`, then stamps
`joined_notified_at` at `:697-700`.

What I cannot show is any execution of it. The only coverage is
`ExternalCreatorLinkServiceTest.java:34` — four pure-Mockito tests, no `EntityManager`, no listener, no
email. Nothing in this repo exercises `NotificationListener.on(ConnectedCreatorJoinedEvent)`, and the whole
task is uncommitted on this branch, so it is not on the Hostinger or Utho image. I found no artefact —
log line, ledger entry, journal entry, or captured row — indicating any environment has ever produced
`joined_notified_at IS NOT NULL`. Code-proven, environment-unproven.

FINDING: none

### Q5.2 [HOW]
> `MetaTokenRefreshService` routes every ~55-day creator refresh through the same 8-arg `storeCreatorToken` with `igUsername=null` but `igBusinessAccountId` present, so `onCreatorIdentified` re-fires on every refresh, matches by id, and `markJoined` re-stamps `joined_at`/`updated_at`. What else moves on each refresh: does the row bubble back to the top of the `updatedAt DESC` Discover list, and what guarantees no second `ConnectedCreatorJoinedEvent` for a request that was reopened after DECLINED post-join?

VERDICT: DEFECT

Confirmed on both counts.

Re-fire: `MetaTokenRefreshService.java:190-201` passes `igUsername=null` with `igBusinessAccountId` present,
so the hook takes the id branch (`ExternalCreatorLinkService.java:76-78`), passes the relink guard
(`:89-102` only refuses a *different* profile id) and reaches `markJoined` unconditionally at `:106`.

What moves: `ExternalCreator.markJoined` (`ExternalCreator.java:231-236`) sets `joinedAt = Instant.now()`
**and** `updatedAt = Instant.now()` every time, and `:107` persists it. `ExternalCreatorService.list`
sorts `Sort.by(DESC, "updatedAt")` (`ExternalCreatorService.java:115`), so yes — every ~55 days a
background token refresh silently promotes that creator to the top of every brand's Discover page. And
`joined_at` — the record of when the creator actually joined — drifts forward forever. This is exactly the
bug `storeCreatorToken`'s own F-0173 comment (`MetaTokenStorage.java:294-301`) exists to prevent for
`createdAt`; the same discipline was not applied here.

Second event: nothing guarantees it. `CreatorConnectionRequest.reopen` (`:113-121`) sets status back to
`PENDING` and explicitly nulls `joinedNotifiedAt` at `:119`. The listener's idempotency check
(`NotificationListener.java:675-681`) reads that same field, so it is disarmed. The next refresh re-flips the
reopened request (`ExternalCreatorLinkService.java:109-134`) and re-emails. (In practice the reopen path is
hard to reach post-join because `ExternalCreatorService.java:324-328` throws 409 before `:347` — but that
409 needs `linkedCreatorProfileId != null`, so a JOINED row whose link was never set still reaches it.)

FINDING: {"tester":"Priya","type":"Functional","severity":"Medium","title":"Meta token refresh re-runs the JOINED hook: joined_at drifts, Discover re-sorts, and a reopened request re-emails the brand","where":"influora-api/src/main/java/com/influora/domain/entity/ExternalCreator.java:231-236","issue":"MetaTokenRefreshService.java:190-201 calls the 8-arg storeCreatorToken every ~55 days with igBusinessAccountId set, so ExternalCreatorLinkService.java:106 calls markJoined again on an already-JOINED row. markJoined re-stamps joinedAt and updatedAt unconditionally, so (a) the real join date is lost, and (b) ExternalCreatorService.java:115 sorts updatedAt DESC, so a token refresh bumps that creator to the top of every brand's Discover list. Separately, CreatorConnectionRequest.reopen:119 nulls joinedNotifiedAt, which is the exact field NotificationListener.java:675-681 uses for idempotency — a reopened request will be re-flipped and re-emailed by a background refresh.","fix":"Make markJoined idempotent: no-op (or only set linkedCreatorProfileId) when status is already JOINED and linkedCreatorProfileId equals the incoming id, so joinedAt/updatedAt are set once. Preserve joinedAt the way MetaTokenStorage preserves createdAt (F-0173). Do not clear joinedNotifiedAt in reopen(); keep it as a permanent 'this brand has already been told' marker, or gate the hook on a real identity change rather than every token write."}

### Q5.3 [WHY NOT THIS WAY]
> Why does `PortfolioService.upsertPlatformStat` call the hook with `metric.getUsername()` and `igAccountId=null` when the `igBusinessAccountId` is on the same `MetaOAuthToken` row (`metaOAuthTokenRepository` injected at L105) — and why does `CreatorProfileService.applyUsername` feed an *Influora* username into a matcher that expects an *Instagram* handle at all?

VERDICT: DEFECT

First half — confirmed and cheap to fix. `PortfolioService.syncPlatforms` already loads the token row at
`PortfolioService.java:264-276` and reads `tokenRow.getIgBusinessAccountId()` into a local at `:276`,
then calls `upsertPlatformStat(profile, "INSTAGRAM", metric)` at `:313`. The hook call at `:367-369`
passes `igAccountId = null` anyway. The exact id is not "one repository call away" — it is already in
scope one frame up and is simply not threaded through the private method's signature. The consequence is
that the only fully-trustworthy match key is discarded at the one call site that provably has it from a
real Meta sync, forcing the weak username branch (`ExternalCreatorLinkService.java:79-83`).

Second half — this is the root of Q5.4 and I do not accept the code comment's justification. The comment at
`CreatorProfileService.java:176-179` argues that `ensureUsername`'s auto-slug is "semantically wrong" while
`applyUsername` is a legitimate "claim your handle". That distinction does not hold: both write
`creator_profiles.username`, which is an **Influora vanity handle**, never a verified Instagram identity.
Nothing between `patchMyProfile` (`:62-68`) and the hook call (`:180`) checks that the caller controls the
Instagram account of the same name. Feeding it into a matcher keyed on `ig_username` conflates two
namespaces, and the result is the spoof in Q5.4.

FINDING: {"tester":"Priya","type":"Functional","severity":"Medium","title":"JOINED hook discards the IG business account id at the one site that has it from a real Meta sync","where":"influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:367-369","issue":"syncPlatforms already resolves tokenRow.getIgBusinessAccountId() at PortfolioService.java:276 before calling upsertPlatformStat at :313, but the hook call at :367-369 passes igAccountId=null, so ExternalCreatorLinkService falls back to the case-insensitive ig_username branch (:79-83). That branch cannot match a renamed handle, cannot match a handle containing a dot after normalization, and is the weaker of the two keys. The strongest available identity signal in the whole feature is thrown away.","fix":"Thread the resolved igBusinessAccountId from syncPlatforms (PortfolioService.java:276) into upsertPlatformStat and pass it as the third argument at :367-369, so the hook takes the exact ig_account_id branch. Keep metric.getUsername() as the secondary key."}

### Q5.4 [WHEN WILL IT BREAK]
> Identity spoof via the username path: any creator can PATCH their Influora username to equal a targeted external handle, and `onCreatorIdentified` will mark that row JOINED, link *their* profile id, flip the brand's request to JOINED and email the brand with a `creatorId` pointing at the impostor. Conversely, a real handle containing a dot can never match through this path. What prevents the first, and what is the intended match path for the second?

VERDICT: DEFECT

**Nothing prevents the first.** I traced it end to end and every link holds:

1. Reachable with no Meta connection whatsoever. `CreatorProfileService.patchMyProfile:62-68` calls
   `applyUsername` for any authenticated creator principal; `PortfolioService.updateMine:204-210` is a
   second, equally open entry point. Neither consults `MetaOAuthToken`.
2. `applyUsername:165-174` validates shape and Influora-uniqueness only — `UsernameUtils.VALID`
   (`UsernameUtils.java:8`) is `[a-z0-9][a-z0-9_]{1,28}[a-z0-9]`, no ownership proof anywhere.
3. `:180` calls `onCreatorIdentified(profile.getId(), username, null)` — attacker-chosen string, `igAccountId` null.
4. `ExternalCreatorLinkService.java:79-83` matches it against `ig_username` case-insensitively.
5. The only guard, `:89-102`, refuses relink **only when `linkedCreatorProfileId` is already set to a
   different profile**. Every UNVERIFIED/INVITED row — i.e. every row this feature is about — has that
   field NULL, so the guard is inert exactly where it matters.
6. `:106` `markJoined(impostorId)`; `:123-134` flips every PENDING/CONTACTED request to JOINED and publishes
   `ConnectedCreatorJoinedEvent` carrying the impostor's `creatorProfileId`.
7. `NotificationListener.java:683-695` emails the brand "@handle joined Influora" with
   `campaign_url = /brand/campaigns/new?creatorId=<impostor>`, which F6 turns into a real `api.creators.invite`.

So a targeted handle is a first-come land grab that ends in the brand being told to start a paid
collaboration with the wrong person.

**Dots.** Correct, and worse than stated: `UsernameUtils.normalize` (`:16`) runs `SlugUtils.slugify`, whose
`NON_LATIN = [^\w-]` (`SlugUtils.java:9,22`) **strips** the dot rather than mapping it, so `foodie.mumbai`
becomes `foodiemumbai` — it can never equal the stored `foodie.mumbai`. The intended path for dotted
handles is the id/handle-from-Meta route: `CreatorMetaOAuthService.java:138-148` (id + real username) or
`PortfolioService.java:367-369` (real handle from a Meta sync). Worth noting the unit test fixture uses
`foodie.mumbai` (`ExternalCreatorLinkServiceTest.java:64,116,119`) — a handle that is unreachable through
the very call site this defect lives on.

FINDING: {"tester":"Priya","type":"Security","severity":"Critical","title":"Identity spoof: claiming an Influora username equal to an external Instagram handle links the impostor to the external creator row and emails the brand to hire them","where":"influora-api/src/main/java/com/influora/service/CreatorProfileService.java:180","issue":"applyUsername feeds an unverified Influora vanity handle into ExternalCreatorLinkService.onCreatorIdentified, which matches it against external_creators.ig_username (:79-83). Reachable by any authenticated creator via PATCH profile (CreatorProfileService.java:62-68) or portfolio update (PortfolioService.java:204-210) with no Meta connection and no proof of controlling the Instagram account. The relink guard at ExternalCreatorLinkService.java:89-102 only fires when linkedCreatorProfileId is already set, which is never true for an UNVERIFIED/INVITED row. Result: the external row flips to JOINED with the impostor's linked_creator_profile_id, every open connection request flips to JOINED, and the brand receives 'X joined Influora' with campaign_url carrying the impostor's creatorId (NotificationListener.java:683-695) — an impersonation that terminates in a paid collaboration.","fix":"Remove the onCreatorIdentified call at CreatorProfileService.java:180 — an Influora username is not an Instagram identity and must never be a match key. Restrict the hook to Meta-verified sources only: MetaTokenStorage.java:346 (id + username from the OAuth exchange) and PortfolioService.java:367-369 (handle from a real Meta sync, with the ig_account_id threaded through per Q5.3). If a username-only link is still wanted, gate it on the caller holding a non-revoked MetaOAuthToken whose igBusinessAccountId resolves to that handle. Additionally harden ExternalCreatorLinkService.java:89-102 to require an ig_account_id match before ever setting linked_creator_profile_id on a row that has none."}

### Q5.5 [WHAT TO ADD]
> The invite email's `signup_url` carries `?ref=influora-invite&handle={igUsername}` but `fe/pages/creator-register.tsx` never reads `searchParams`, so the handle is dropped. Also `onCreatorIdentified`'s `catch (Exception)` cannot catch a constraint violation raised at flush/commit by the surrounding `@Transactional` proxy. What is missing: an invite-token-based claim at registration, and a test that a flush-time violation inside the hook does not 500 the Meta OAuth callback?

VERDICT: GAP

Both halves confirmed.

**Handle dropped.** `AdminCreatorConnectionService.java:244-245` builds
`webBaseUrl + "/creator/register?ref=influora-invite&handle=" + external.getIgUsername()`.
`src/pages/creator-register.tsx` is 321 lines and imports only `useNavigate, Link` from
`react-router-dom` (`:2`) — no `useSearchParams`, and no `handle`/`ref` identifier anywhere in the file.
The parameters are inert. An invited creator who registers by email and stops before connecting Meta never
links, so step 5 of Swapnil's flow (brand emailed on join) silently never fires for the invite path it was
built for. What is missing is a signed, single-use invite token (bound to the `external_creators` row, not
to a guessable handle) carried into `creatorRegister` and consumed server-side — a `?handle=` query param
must never itself be a claim, or it reproduces Q5.4 with an even lower bar.

**Flush-time failure.** The nuance matters: `externalCreatorRepository.save` at
`ExternalCreatorLinkService.java:107` merges a managed entity, so the INSERT/UPDATE is not issued there. The
repository query at `:109-112` triggers a Hibernate auto-flush, so a `uk_external_creators_ig_account`
violation would in fact surface *inside* the try block. But catching it does not help: a failed flush marks
the persistence context rollback-only, so the `@Transactional` proxy at `:63` throws
`UnexpectedRollbackException` at commit — after `catch (Exception)` at `:135-143` has already logged
"caller's own write is unaffected". The Meta OAuth callback 500s anyway. The comment's promise is false for
this class, and untested: `ExternalCreatorLinkServiceTest.java:34` is pure Mockito with no
`EntityManager`, so no test can ever reach a flush.

FINDING: {"tester":"Priya","type":"Functional","severity":"Medium","title":"Invite signup_url's handle/ref params are never read at registration, and the hook's 'never fail the caller' catch cannot survive a flush-time constraint violation","where":"src/pages/creator-register.tsx:2","issue":"AdminCreatorConnectionService.java:244-245 emits /creator/register?ref=influora-invite&handle={igUsername}, but creator-register.tsx imports only useNavigate and Link from react-router-dom and never reads searchParams, so the invite context is discarded — the JOINED flip depends entirely on the creator later connecting Meta. Separately, ExternalCreatorLinkService.java:135-143 swallows the exception but a flush failure has already marked the transaction rollback-only, so the @Transactional proxy at :63 throws UnexpectedRollbackException at commit and the Meta OAuth callback 500s regardless. ExternalCreatorLinkServiceTest.java:34 is pure Mockito with no EntityManager, so this is untested.","fix":"Issue a signed single-use invite token bound to the external_creators row (never trust a bare ?handle=), read it in creator-register.tsx, pass it to the register call, and consume it server-side to link the row — the claim must be server-verified, not URL-supplied. For the hook, run it in REQUIRES_NEW (or via an AFTER_COMMIT listener on the caller's transaction) so its failure cannot poison the caller's transaction, and add a @DataJpaTest that inserts a conflicting ig_account_id row and asserts the caller's write still commits."}


# QA answers — T-CREATORCONNECT-0902 (Priya, CTO)

Answers to the `## F6` section of `QA-QUESTIONS.md` only. Read on branch `fix/f0390-money-flags-build-pipeline`, 2026-09-03.
Path shorthand: `api/` = `influora-api/src/main/java/com/influora/`, `fe/` = `src/`.

## F6 — Create campaign handoff (`?creatorId=` banner + post-create invite)

### Q6.1 [IS IT WORKING]
> Show me the exact live path from the JOINED card's "Create campaign" link (`creator-discovery.tsx:~2003`, `/brand/campaigns/new?creatorId=…`) → `CampaignForm` reading `searchParams.get('creatorId')` (`fe/components/brand/campaigns/campaign-form.tsx:274`) → `api.campaigns.create` → `api.creators.invite(creatorIdParam, saved.id)` (L524-526) → `CreatorDiscoveryService.invite` (`api/service/CreatorDiscoveryService.java:452-505`) creating an INVITED `Collaboration`. No vitest covers `campaign-form.tsx` L520-543; what proves the second request fires, with the campaign id the server returned rather than a stale form id?

VERDICT: PARTIAL

The chain is correctly wired, statically:
- `fe/components/brand/discover/creator-discovery.tsx:1997` — `<Link to={`/brand/campaigns/new?creatorId=${creator.linkedCreatorProfileId}`}>`, rendered only when `linkedCreatorProfileId` is non-null (guard at L1995-1996).
- `fe/components/brand/campaigns/campaign-form.tsx:274` reads the param off the same route (no `navigate()` between picker and wizard — `brand-new-campaign.tsx:208-211` renders `CampaignForm` in place).
- `campaign-form.tsx:526` calls `api.creators.invite(creatorIdParam, saved.id)`.

The "stale form id" concern is unfounded: `saved` is the awaited return of `api.campaigns.create` (`campaign-form.tsx:516-518`), and `api.ts:1489-1495` returns `mapCampaignFromApi(row)` built from the `POST /campaigns` response body — not from `payload`. The id is the server's.

What proves it fires at runtime: **nothing**. The only campaign-form tests are `src/components/brand/campaigns/__tests__/campaign-form-f0280.test.tsx` and `…-f0280-simple.test.tsx`; neither references `creatorId` or `creators.invite` (grep over `*.test.tsx`/`*.spec.tsx` returns only `creator-discovery.instagram.test.tsx` for `creatorId=` and `creator-discovery.test.tsx` for `creators.invite`). No live run is recorded. Verdict PARTIAL: correct by construction, unproven by execution. See Q6.5 for the missing click test.

FINDING: none

### Q6.2 [HOW]
> Both banners promise "they'll be invited when you publish" (`brand-new-campaign.tsx:113`, `campaign-form.tsx:664-666`), but the code invites on *any* successful `api.campaigns.create` (L518-526) including a DRAFT save, and `CreatorDiscoveryService.invite` has no campaign-status guard (L452-505). Walk me through what the creator sees when invited to a DRAFT campaign — which creator-side list surfaces a DRAFT collaboration, and what happens to that INVITED row if the brand deletes the draft?

VERDICT: DEFECT

Confirmed. `handleSubmit(status: CampaignStatus = 'DRAFT')` (`campaign-form.tsx:481`) is bound to "Save Draft" at L1499-1503; the invite block at L524-526 is gated only on `!isEditing && creatorIdParam`, not on `status`. `CreatorDiscoveryService.invite` (L452-505) resolves the campaign by id+workspace only — no `CampaignStatus` check.

What the creator sees: `DealService.list` → `loadCollaborations` → `collaborationRepository.findByCreatorId(...)` (`DealService.java:1383`) with **no campaign-status filter**; the status filter at L152 is over `CollaborationStatus`, not campaign status. So the INVITED row appears in the creator's deals list immediately, plus a "Brand invited this creator to the campaign" system message written by `recordInviteOnTimeline` (`CreatorDiscoveryService.java:537-547`). An unpublished draft is thereby exposed to a creator.

Deleting the draft: `CampaignService.delete` L339-352 → `validator.ensureDeletable` allows DRAFT (`CampaignValidator.java:111-116`) → `campaignRepository.delete(campaign)` → FK `fk_collab_campaign … REFERENCES campaigns(id)` with no `ON DELETE CASCADE` (`V6__creators_collaborations.sql:71`) → `DataIntegrityViolationException` → `GlobalExceptionHandler.java:110-115` → **409 `DATA_INTEGRITY_VIOLATION` "The request could not be completed due to a data conflict"**. The brand can never delete that draft and is given no reason.

FINDING: {"tester":"Priya","type":"Functional","severity":"High","title":"Draft save invites the creator, exposing an unpublished campaign and making the draft undeletable","where":"src/components/brand/campaigns/campaign-form.tsx:524","issue":"The post-create invite is gated on `!isEditing && creatorIdParam`, not on the submitted status, so `handleSubmit('DRAFT')` (L481, bound at L1499-1503) creates an INVITED Collaboration on a DRAFT campaign — contradicting both banners ('invited when you publish', campaign-form.tsx:665-666, brand-new-campaign.tsx:112-113). DealService.list (DealService.java:1383) applies no campaign-status filter, so the creator sees the unpublished draft as a deal with a system message. Deleting the draft then 409s on fk_collab_campaign (V6__creators_collaborations.sql:71, GlobalExceptionHandler.java:110-115) with an opaque message.","fix":"Gate the invite on `status === 'ACTIVE'` at campaign-form.tsx:524, and stash `creatorIdParam` so a later publish still invites; independently, add a campaign-status guard in CreatorDiscoveryService.invite (L452-505) rejecting DRAFT with a typed error, and exclude DRAFT campaigns from the creator-side deal list."}

### Q6.3 [WHY NOT THIS WAY]
> The handoff is two non-atomic requests from the browser (create, then invite) with the intent living only in the URL; if the invite 404s or the tab closes between them, nothing records that this campaign was created *for* that creator (`campaign-form.tsx:533-542` just toasts). Why not carry `creatorId` in the `CampaignCreateRequest` payload and have the server create the collaboration in the same transaction — or at minimum persist the intent on the `creator_connection_requests` row so the admin/brand can see "campaign created, invite failed"?

VERDICT: GAP

The two-request shape is what the contract ordered, not a deviation: `TASKS.md:169` — "After successful create, call the existing `api.creators.invite(creatorId, { campaignId })` path the Discover page already uses, then toast." (The real signature is positional, `api.ts:1734`.) So this is a specified design, correctly implemented, with an unspecified durability gap.

The gap is real and I am not closing it in this task:
- The failure branch is a toast only (`campaign-form.tsx:533-541`); nothing is persisted. `CreatorConnectionRequest` (`api/domain/entity/CreatorConnectionRequest.java`) has no campaign/collaboration column, and `ConnectionRequestResponse` (`ExternalCreatorDtos.java`) carries no such field, so neither the brand's request list nor the admin console can ever show "campaign created, invite failed".
- Server-side atomicity is the correct end state (`creatorId` on `CampaignCreateRequest`, collaboration created in `CampaignService.create`'s transaction) — it removes the tab-close window entirely and makes `Collaboration` creation obey the same status rule as the campaign. It was out of scope for T-CREATORCONNECT-0902 because `CampaignCreateRequest` is a locked money-path contract touched by Meera's `CreateCampaignExecutor` too.

I am recording this as a follow-up, not a defect against this task's contract, so no finding block.

FINDING: none

### Q6.4 [WHEN WILL IT BREAK]
> `CreatorDiscoveryService.invite` goes through `requireDiscoverableProfile` (L925-934), which 404s for a profile that is not yet `discoverable` or is suspended. A creator who just joined via the admin invite and has not finished onboarding is exactly that case… Separately, choosing HYPE navigates to `/brand/campaigns/new/hype` (`brand-new-campaign.tsx:174-177`) and drops `?creatorId=` entirely. What happens in each case?

VERDICT: DEFECT

**(a) The freshly-joined-creator 404 does not occur.** The premise is wrong. `CreatorProfile.newForUser` sets `discoverable = true` and `applicationStatus = APPROVED` at registration (`api/domain/entity/CreatorProfile.java:207-215`), called from `AuthService.java:344`; the column also defaults `TRUE` (`V6__creators_collaborations.sql:16`). No onboarding step gates it (`V38__creator_profile_moderation.sql:26-27` says so explicitly). `requireDiscoverableProfile` (L925-934) therefore only 404s if the creator *turned discoverability off* (`CreatorProfile.java:462`) or is suspended. Not a defect.

**(b) HYPE silently drops the handoff — confirmed defect.** `choose()` at `brand-new-campaign.tsx:174-176` calls `navigate('/brand/campaigns/new/hype')` with no query string. `src/pages/brand-new-hype-campaign.tsx` contains no `useSearchParams` and no `creatorId` reference; it creates at L219 and navigates away at L221 with no invite. The brand arrives from the "creator joined" email, reads the banner at `brand-new-campaign.tsx:222-225` ("Creating this campaign for @handle — they'll be invited when you publish"), picks Hype, and gets a published campaign with no invite and no warning that the promise was dropped.

FINDING: {"tester":"Priya","type":"Functional","severity":"High","title":"Picking HYPE drops ?creatorId= after the banner promised the invite","where":"src/pages/brand-new-campaign.tsx:176","issue":"choose() navigates to '/brand/campaigns/new/hype' without forwarding the query string, and brand-new-hype-campaign.tsx has no useSearchParams/creatorId handling and no post-create invite (create at L219, navigate away at L221). The handoff banner shown one screen earlier (brand-new-campaign.tsx:222-225) has already told the brand the creator 'will be invited when you publish', so the promise is silently broken for the whole HYPE path.","fix":"Forward the param — navigate(`/brand/campaigns/new/hype${creatorId ? `?creatorId=${creatorId}` : ''}`) — and mirror campaign-form.tsx:524-543's post-create invite in brand-new-hype-campaign.tsx after L219; if HYPE is intentionally not invite-capable, suppress the banner for that card and say so on the type tile."}

### Q6.5 [WHAT TO ADD]
> `creatorHandle` is resolved from `profile.username` (`campaign-form.tsx:281-285`) — the Influora username — while every upstream surface (Discover card, both emails) speaks in `@igUsername`; the banner can therefore show a different `@handle` than the email that brought the brand here. What is missing so the handoff carries the external creator context (ig handle, `connectionRequestId`) through to the form, and where is the click test for the `?creatorId=` banner + post-create invite that memory F-0341 says a static check cannot replace?

VERDICT: GAP

Confirmed mismatch. Both banners resolve the handle from `api.creators.getProfile(...).username` (`campaign-form.tsx:284`, `brand-new-campaign.tsx:100`) — the Influora username. Every upstream surface speaks Instagram: the Discover card (`creator-discovery.tsx` renders `@igUsername`), `admin.creator_connection_requested` and `brand.connected_creator_joined` (`TASKS.md:145-147`). Worse, an IG handle containing a dot can never be an Influora username (`UsernameUtils.VALID` = `[a-z0-9_]`), so for `@foodie.mumbai` the banner is *guaranteed* to read a different handle than the email.

What is missing:
1. Carry the external context in the URL — `?creatorId=…&ig=<igUsername>&crq=<connectionRequestId>` from `creator-discovery.tsx:1997` and from the email link built at `TASKS.md:135` — and prefer `ig` for the banner copy, falling back to `profile.username`. `connectionRequestId` is already on `ExternalCreatorResponse` (contract, `TASKS.md:99`) and unused by the handoff.
2. A click test (F-0341: a rendered banner and a wired handler are indistinguishable to tsc/eslint): render `CampaignForm` at `/brand/campaigns/new?creatorId=cp_1`, assert the banner text, click Publish, assert `api.creators.invite` was called with the id from the mocked `POST /campaigns` response — and a second case asserting it is *not* called on "Save Draft" (Q6.2). No such test exists today.

FINDING: {"tester":"Priya","type":"Functional","severity":"Medium","title":"Handoff banner shows the Influora username while every upstream surface says @igUsername","where":"src/components/brand/campaigns/campaign-form.tsx:284","issue":"The banner resolves the handle via api.creators.getProfile().username (also brand-new-campaign.tsx:100), i.e. the Influora username, while the Discover card and both connect/joined emails identify the creator by @igUsername. The two differ whenever the creator's Influora username differs from their IG handle, and differ necessarily when the IG handle contains a dot (UsernameUtils.VALID allows only [a-z0-9_]), so a brand arriving from the '@foodie.mumbai joined' email reads a banner naming a different account.","fix":"Append the external context to the handoff URL at creator-discovery.tsx:1997 (`&ig=<igUsername>&crq=<connectionRequestId>`) and in the joined-email link, and have the banner prefer the `ig` param over profile.username; add the click test described above covering both the banner copy and the post-create invite."}


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
