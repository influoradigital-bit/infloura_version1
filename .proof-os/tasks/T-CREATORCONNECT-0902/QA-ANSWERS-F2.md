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
