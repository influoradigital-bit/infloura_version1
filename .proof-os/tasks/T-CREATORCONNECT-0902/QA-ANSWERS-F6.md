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
