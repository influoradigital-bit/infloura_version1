# R2-A4-creator — answers

### 1. `CreatorOnboardingController.connectSocial` accepts a `CreatorSocialRequest` — does creator-onboarding.tsx send a platform handle for every network the wizard lets the creator enter, or only the ones the client happens to validate?
The premise does not hold: the wizard never sends a handle for any network, and the request DTO has no handle field at all — it carries only platform and oauthCode. The only two networks offered are Instagram, which is redirected into the real Meta OAuth flow instead of this endpoint, and YouTube, which only raises a coming-soon toast. The endpoint has zero live callers and the service rejects every call it could receive.
`influora-api/src/main/java/com/influora/service/CreatorOnboardingService.java:85` "SOCIAL_OAUTH_NOT_IMPLEMENTED"

### 2. `CreatorOnboardingService.saveProfile` persists a `CreatorProfileRequest` — which fields does the onboarding form collect that the request body never carries, and are they silently lost?
None of the step-2 fields are lost: profileData holds displayName, bio, verticals, languages, city, rateMin and rateMax, and all seven are passed into the POST body. What is lost is step 1: the connected-socials state the wizard tracks is never part of any profile write, so the handle a creator connected is only persisted by the separate Meta OAuth path. The DTO itself is a field-for-field match.
`influora-api/src/main/java/com/influora/web/dto/onboarding/OnboardingDtos.java:91` "public record CreatorProfileRequest("

### 3. Does `CreatorOnboardingController.complete` verify server-side that `socials`, `profile`, `kyc`, and `payout` were all actually saved, or does it flip a completion flag regardless of what preceded it?
It flips the flag unconditionally. The method calls requireCreator, loads the user, and sets onboardingCompleted to true; there is no read of CreatorProfile fields, no KYC status check and no bank-account check anywhere in the body.
`influora-api/src/main/java/com/influora/service/CreatorOnboardingService.java:128` "user.setOnboardingCompleted(true);"

### 4. If a creator calls `/onboarding/creator/complete` having skipped `/onboarding/creator/kyc`, what does the server do — reject, or mark onboarding COMPLETE with no KYC on file?
It marks onboarding complete with no KYC on file. The complete path only resolves the user row and saves it; the identity-KYC columns written by submitKyc are never consulted. This is intentional per the wizard comment that KYC is deferred to first withdrawal, but it means the completed flag asserts nothing about KYC.
`influora-api/src/main/java/com/influora/service/CreatorOnboardingService.java:120` "creatorContext.requireCreator(principal);"

### 5. Does `CreatorKycRequest` get parsed and stored fields validated (PAN/Aadhaar format), or does `submitKyc` accept and persist whatever string the client sends?
Both are format-validated at the DTO boundary by bean validation, and the controller annotates the body with Valid so the constraints actually run. PAN must match the five-letters four-digits one-letter pattern and aadhaarLast4 must be exactly four digits. Only selfieUrl is unvalidated beyond non-blank and a length cap.
`influora-api/src/main/java/com/influora/web/dto/onboarding/OnboardingDtos.java:104` "Invalid PAN format"

### 6. Does `CreatorPayoutRequest`/`savePayout` write to the same `CreatorBankAccount` table `WalletController.addPayoutMethod` writes to, or a separate row the wallet screen never reads?
Same table, same service, same encryption path — savePayout calls creatorBankAccountService.addInstrument exactly as the wallet controller does, so a row created during onboarding is visible in the wallet payout-methods list. One field does diverge: accountName is accepted on the request but has no column and is not persisted. In practice this is moot because the onboarding payout endpoint has no frontend caller left.
`influora-api/src/main/java/com/influora/web/WalletController.java:213` "creatorBankAccountService.addInstrument("

### 7. If a creator abandons the onboarding wizard mid-step and returns later, is the last completed step read back from the server, or does the wizard restart from step one and silently overwrite what was saved?
The wizard restarts at step one every time; currentStep is plain local state initialised to 1 and there is no GET of onboarding progress anywhere in the page. The only persistence honoured across a reload is the Meta connection marker read from local storage, which only re-flags Instagram as connected. Re-walking step 2 re-POSTs the profile and overwrites whatever was saved before.
`src/pages/creator-onboarding.tsx:80` "React.useState(1)"

### 8. Does the onboarding wizard's step indicator reflect a server-computed progress value, or a client-only counter that can drift from what is actually persisted?
Client-only. Progress is computed purely as the local step index divided by the static step count, so the bar can read 100 percent while the profile POST failed and nothing was written. Nothing in the page reads a server-side progress field.
`src/pages/creator-onboarding.tsx:97` "const progress = (currentStep / STEPS.length) * 100;"

### 9. What does creator-onboarding.tsx render when `saveProfile` or `submitKyc` returns a 4xx validation error — is it mapped to the offending field, or does the wizard advance as if it succeeded?
It does not advance — the step change is inside the try after the await, so a 4xx keeps the creator on step 2 — but the error is surfaced only as a destructive toast carrying the raw server message. There is no per-field mapping: the offending field is never highlighted and no inline message is attached to any input.
`src/pages/creator-onboarding.tsx:200` "Couldn"

### 10. Is there a creator onboarding field the server validates as required that the onboarding form never actually collects, leaving `complete` permanently unreachable for some creators?
Yes, in effect: rateMax is NotNull on the request and the service rejects rateMin greater than rateMax, but the client gate only requires displayName, at least one vertical, and rateMin. A creator who fills a rate floor and leaves the ceiling blank sends rateMax as zero and gets a 400 with no field-level explanation, blocking step 2 until they guess which control is at fault.
`src/pages/creator-onboarding.tsx:191` "rateMax: Number(profileData.rateMax) || 0,"

### 11. `MeCreatorProfileController`'s `PatchMapping` — which fields does creator-settings.tsx send in its patch body that the DTO silently drops before it reaches the service?
None are dropped, because the settings page sends exactly one field. The only patch call on that page is the phone dialog, which posts a single-key body, and phone is a declared member of the patch record. Every other editable creator attribute on that record — display name, bio, avatar, rates, discoverable — is never touched from creator-settings.tsx at all, so the gap is unsent fields rather than dropped ones.
`src/pages/creator-settings.tsx:187` "api.creatorProfile.patchMe({ phone: stripped })"

### 12. Does the profile GET response include every field the settings form pre-fills, or does the form fabricate defaults for fields the server never returned?
The settings page reads exactly one field out of the self response, the phone number, and stores it in local state; nothing else on that screen is pre-filled from this GET. The response record itself carries far more than the page consumes, so the risk here is unused server data rather than fabricated defaults. On a failed load the phone row falls back to the add-a-number prompt rather than showing an error, which does mean a fetch failure is indistinguishable from no number on file.
`src/pages/creator-settings.tsx:147` "setSavedPhone(profile.phone);"

### 13. `CreatorController.search` and `.featured` — do their query/response DTOs match what creator-discovery-style pages actually render, or are there filters the UI offers that the backend search never applies?
They match now. Every enabled control on the discovery screen is mapped to a query param, including language, engagement-rate bounds, verified-only and sort order, each with an undefined sentinel so an untouched control sends no filter. The controller declares a request param for each of those names, so the selected value does reach the server rather than re-filtering the already-returned page.
`src/components/brand/discover/creator-discovery.tsx:667` "languages: selectedLanguages.length ? selectedLanguages : undefined,"

### 14. `CreatorController.invite` at `/{creatorId}/invite` — does any brand-side UI actually call this path, or is it a caller-less endpoint?
It has real callers on three separate surfaces: the discovery grid invite dialog, the brand creator profile page, and the campaign form path that carries a creatorId query param through campaign creation. The premise that it is caller-less is false.
`src/components/brand/discover/creator-discovery.tsx:563` "await api.creators.invite("

### 15. `CreatorController.save` (`/{creatorId}/save`) — is the "saved" state read back anywhere the creator or brand can see it, or does the button toggle with no visible persisted effect?
It is read back, but only on one surface. The discovery search response carries a per-creator saved flag, and the grid seeds its saved set from it on every page load, so a bookmark survives a reload there. The brand creator profile page is the counterexample: its bookmark button is pure local state with no API call and no read-back, so on that screen the toggle is cosmetic.
`src/components/brand/discover/creator-discovery.tsx:688` "if (saved.length) setSavedCreators("

### 16. Does `/{username}/similar` return creators the client actually renders distinctly from `/featured`, or do both surfaces silently show the same fabricated/mock list on an empty result?
They are distinct and neither fabricates. Similar creators are rendered in their own section on the brand creator profile, and that section is conditionally mounted only when the array is non-empty, so an empty result renders nothing rather than a placeholder. Featured is consumed separately by the discovery grid; in mock mode the similar wrapper returns an explicitly empty array rather than borrowing the featured list.
`src/pages/brand-creator-profile.tsx:1128` "{similarCreators.length > 0 && ("

### 17. `PortfolioPatchRequest` fields versus creator-portfolio-editor.tsx's form state — which editable fields in the editor never make it into the PATCH body?
The save handler sends six keys — bio, niches, visibility, customLinks, rateCard and coverUrl — and those are exactly the six the editor actually exposes controls for. The record declares six more the editor never edits at all: username, displayName, city, avatarUrl, languages and pinnedPosts. So nothing editable is dropped; the pinned-posts row in the editor is a visibility toggle over a count, not an editor for the posts themselves.
`src/pages/creator-portfolio-editor.tsx:136` "await api.portfolio.update({"

### 18. `PortfolioController.syncPlatforms` — does it re-pull real metrics from each connected platform integration, or does it just recompute derived fields from already-stored numbers with nothing actually re-fetched?
It genuinely re-pulls, but only for Instagram. It resolves the creator-scoped Meta token, calls the Graph API for a fresh profile snapshot, writes a new creator metrics row and upserts platform stats from it. If there is no connected account or the token is expired it throws a typed conflict rather than returning a fabricated synced timestamp; no other platform is refreshed because none has a token pipeline.
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:295` "instagramInsightsClient.getProfile(igBusinessAccountId, accessToken)"

### 19. After `/me/portfolio/sync` returns, does creator-portfolio-editor.tsx refetch `/me/portfolio` to show the synced numbers, or does it keep rendering the pre-sync state until a manual reload?
It refetches. On a successful sync the handler immediately awaits both the portfolio page and the analytics endpoint and replaces local state with the results before showing the success toast, so the displayed follower counts are the post-sync server values rather than stale client numbers.
`src/pages/creator-portfolio-editor.tsx:178` "await Promise.all([api.portfolio.getMine(), api.portfolio.analytics()])"

### 20. `uploadCover` returns a `CoverUploadResponse` URL — is that URL persisted onto the portfolio record before the response is returned, or can a returned URL 404 if the write happens out of order?
The write happens first. The method streams the object to storage, applies the object key to the profile, saves the profile, and only then resolves the key into a presigned URL for the response — all inside one transactional method. There is no window where a URL is handed back before the record is updated.
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:474` "profile.applyCoverImageUrl(key);"

### 21. Does `getPublic(username)` return the same field set `getMine` returns, or are there fields visible on the owner's edit screen that silently never reach the public portfolio page?
Deliberately different, and correctly so: both call the same assembler but with a public flag that gates whole sections against the creator's own visibility settings. Trust badges, past collabs, the content portfolio and custom links are each replaced with an empty list on the public view when the corresponding toggle is off, while the owner view always sees them. Nothing is silently lost — the omission is the creator's own setting.
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:552` "publicView && !settings.getVisibility().pastCollabs()"

### 22. `recordPublicView` is wrapped in try/catch specifically so a failed view write can't 500 the page — does `PortfolioService#analytics` (`/me/portfolio/analytics`) actually reflect those recorded views, or can the counter silently stop incrementing while the page keeps rendering fine?
Analytics does read the same rows: page views are counted from portfolio events of type VIEW keyed by the creator profile id, which is exactly what the recorder writes. But the risk in the question is real — the controller only logs a warning when the view write throws, so a persistent failure on that write leaves the page serving normally while the analytics counter quietly flatlines, with nothing surfaced to the creator.
`influora-api/src/main/java/com/influora/web/PortfolioController.java:49` "Failed to record portfolio view for username={}"

### 23. `PortfolioContactRequest`/`contact` — is the submitted message actually delivered anywhere (email, inbox row, notification), or does the endpoint 200 with no observable effect and the creator will never see?
It is delivered. The handler validates name, email and message, then publishes a portfolio contact event onto the application event bus, which the async after-commit notification listener consumes — this replaced an earlier version that built the event object and never published it. The return value is a simple acknowledgement, but a real notification path now exists behind it.
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:512` "eventPublisher.publishEvent(event);"

### 24. Is there a cap on portfolio contact submissions per visitor that's enforced server-side, or can the public contact form be spammed with no rate limit at all?
There is no server-side cap. The rate-limit filter enumerates a specific bucket for every throttled path — tracking, client errors, deliverable writes, review writes, dispute open, discovery invite, campaign apply, wallet withdraw — and the public portfolio contact path appears in none of them, while the security config explicitly permits it unauthenticated. The only per-request guards are the field validations and a 2000-character message cap. The editor even labels this control as anti-spam-protected, which the backend does not back up.
`src/pages/creator-portfolio-editor.tsx:429` "Visitors can email you (anti-spam-protected)"

### 25. Which platforms does creator-settings-connected-accounts.test.tsx exercise that have no corresponding backend integration under `integration/meta` or elsewhere — i.e., a "Connect" button wired to nothing real?
None. The card renders exactly two rows, Instagram and Facebook Page, and both are surfaces of a single Meta OAuth grant with one connect action behind them; the test asserts two connected badges from that one shared state. YouTube, TikTok and Twitter appear nowhere on this card, which is why the onboarding wizard has to fall back to a coming-soon toast for them instead.
`src/components/creator/connected-accounts.tsx:43` "scopes in one authorize call. We surface Instagram and Facebook Page as two"

### 26. When a creator disconnects a platform, is the stored access token actually revoked/deleted server-side, or does the UI just hide the card while the token and cached metrics remain live in the database?
The token row is genuinely marked revoked server-side, creator-scoped, with an audit entry — it is not a UI-only hide. Two caveats matter: the revocation is a flag on the retained row rather than a delete, and it is local only, with no call to Meta to invalidate the grant on their side. The cached platform stats row is also left untouched, so the follower and engagement numbers a brand sees survive the disconnect unchanged.
`influora-api/src/main/java/com/influora/integration/meta/oauth/MetaTokenStorage.java:380` "t.revoke();"

### 27. Is there a background job (see `job/` and `MetricsPollingJob.java`) that refreshes creator platform metrics on a schedule, and does its failure for one creator silently skip them forever with no retry or alert?
Yes, a six-hourly cron job iterates connected tokens with a per-creator try/catch so one failure never aborts the batch. Failures are not permanent — an expired token or rate limit just returns false for that creator and the next cycle retries — but they are also not surfaced anywhere: the only record is a server log line, with no creator-facing notification and no alert.
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:257` "token expired for creator {}, needs re-auth"

### 28. If Meta/Instagram token refresh fails, does the creator ever see a "reconnect required" state, or does the UI keep showing stale follower/engagement numbers as if they were current?
The creator does get a reconnect prompt on the surfaces that ask the status endpoint: an expired token makes the status service return the fully disconnected shape, so the settings card flips to a Connect call to action, and the portfolio sync maps the expired code to an explicit reconnect message. What does not change is the brand-facing number — the cached platform stats row keeps serving the last known followers with no staleness marker, so elsewhere the stale figure still reads as current.
`influora-api/src/main/java/com/influora/service/MetaConnectionService.java:64` "tokenRow.get().getExpiresAt().isBefore(Instant.now())"

### 29. Do the metrics shown on `creator-analytics.tsx` come from the same verified numbers `CreatorDeliverableController.verify` computes, or from an independently cached value that can diverge from what verification just proved?
They are independent. The analytics page reads profile-level creator metric snapshots written by the polling job and the portfolio sync, taking the most recent row across platforms, while verification writes per-milestone deliverable metric rows that the verification state response reads back. Nothing reconciles the two, so a just-verified deliverable can show numbers the analytics dashboard does not reflect until the next poll.
`influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java:125` "CreatorMetric mostRecent = latest.get(0);"

### 30. Is the "connected since" / "last synced" timestamp shown in the UI backed by a real server field, or is it computed/faked client-side from whatever data happens to be present?
It depends on the screen, and one of them is fabricated. The public portfolio renders a real server field carried on the stats payload. The creator profile page, by contrast, stamps its own clock into local state the moment the sync call resolves and renders that as the last-synced time, so it shows nothing at all after a reload and never reflects what the server actually recorded. The connected-since value the status endpoint returns is real but is not rendered anywhere in the connected-accounts card.
`src/pages/creator-profile.tsx:158` "setLastSynced((prev) => ({ ...prev, [platform]: new Date() }))"

### 31. `CreatorCampaignController.list` — which filter/sort controls does creator-campaigns.tsx render whose selected value is never included in the request query params?
The search box. Niche, platform, budget floor and budget ceiling all become query params, but the controller declares no text-search param, so the search field is applied purely client-side over the rows already fetched into state. There is no sort control on this page at all. The page at least labels this honestly rather than presenting it as a catalog-wide search.
`src/pages/creator-campaigns.tsx:129` "const filteredCampaigns = React.useMemo(() => {"

### 32. `CreatorCampaignController.apply` (`/{campaignId}/apply`) — does the request body carry every field the application form collects (pitch, rate, availability), or are some silently dropped before the POST?
The premise overstates the form. The apply request record has exactly one field, an optional message capped at 2000 characters, and the dialog collects exactly that one optional message and sends it. There is no rate field and no availability field anywhere in the dialog, so nothing is dropped — the capability simply does not exist on either side.
`src/pages/creator-campaign-detail.tsx:125` "message: applyMessage.trim() || undefined,"

### 33. After a successful apply, does the campaign card's state update to "applied" from the server response, or does the client optimistically flip it with no confirmation the write actually took?
The flip happens after the await resolves, so it is not blind optimism — a 409 or 500 takes the catch branch and the state is untouched. But it is a hardcoded literal rather than a read of the response: the apply response carries a real status field and an applied-at timestamp, and neither is consumed. On the revive path the server can legitimately return a different status, and the card would still say applied.
`src/pages/creator-campaign-detail.tsx:128` "applicationStatus: 'APPLIED'"

### 34. `CreatorApplicationController.list` and `/{dealId}/history` — does creator-applications.tsx render every status the backend can return, or are there statuses (e.g. withdrawn, expired) the switch/case silently falls through and renders as nothing?
Nothing falls through to blank. The bucket map covers twelve of the thirteen collaboration statuses, and any status not in the map resolves to the closed bucket rather than disappearing. The one omission is INVITED, and that is safe here because the list query filters to application-sourced rows only, so an invitation can never reach this page. There is no distinct withdrawn or expired status in the enum at all — a withdrawal is stored as CANCELLED.
`src/lib/application-status.ts:190` "return STATUS_BUCKETS[status] ?? 'closed';"

### 35. Can a creator apply twice to the same campaign, and if the server rejects the duplicate, does the UI surface that as an error or does the button just silently do nothing on the second click?
The server rejects it two ways: a revive-or-refuse check throws a conflict for a live prior application, and a unique constraint on campaign plus creator catches the race, both with the same already-applied code. The client surfaces it — the catch branch raises a destructive toast carrying the server message rather than failing silently. A genuinely withdrawn application is deliberately revivable rather than blocked forever.
`influora-api/src/main/java/com/influora/service/CreatorCampaignService.java:267` "You have already applied to this campaign"

### 36. Does the campaign detail page (`creator-campaign-detail.tsx`) show budget/reward figures pulled from the same response the apply POST validates against, or can the displayed number and the enforced number diverge?
They cannot diverge, because the apply path enforces no amount at all. It validates campaign visibility, that the status is active, and that the application deadline has not passed, then creates the collaboration with the campaign currency and the free-text message. No budget or rate figure is submitted or checked, so the displayed budget is purely informational until negotiation.
`influora-api/src/main/java/com/influora/service/CreatorCampaignService.java:212` "campaign.getStatus() != CampaignStatus.ACTIVE"

### 37. Is there a caller for every `CreatorCampaignController` endpoint, and is there a route in `creator-campaigns.tsx`/`creator-campaign-detail.tsx` that calls an endpoint that doesn't exist under this controller?
All three routes have callers and there are no orphans in either direction. The client module exposes exactly browse, get by id and apply, each annotated with the controller line it maps to, and the two pages call only those. Nothing in either page calls a creator-campaign path the controller does not declare.
`src/lib/api.ts:5235` "CreatorCampaignApplyResponse"

### 38. Where in the creator app does an invitation (brand-initiated, via `CreatorController.invite` or `DealController.create`) actually surface to the invited creator — is there a distinct "invitations" list, or do invites only ever appear if the creator happens to open `creator-deals.tsx`?
There is no separate invitations screen. An invite creates a collaboration in INVITED status, and the deals list is the only place it appears: for a creator principal the New filter resolves to exactly the invited status server-side, so the New chip on the deals page is the de facto invitations inbox. My Applications cannot show it because that list is restricted to application-sourced rows.
`influora-api/src/main/java/com/influora/service/DealService.java:2144` "List.of(CollaborationStatus.INVITED)"

### 39. Does the invited creator ever see who invited them and on what terms before a `Deal` row exists, or does the first visible artifact already assume acceptance is imminent?
The premise inverts the order: the deal row is created by the invite itself, so there is no pre-deal artifact at all. Worse, an invite sent with no priced offer produces an INVITED row with zero messages, because the proposal message is only written by the create-proposal and counter paths. That left the creator on an empty thread with no way to act until a fallback response card was added, gated on there being no events and the proposal still being answerable.
`src/pages/creator-chat-bare-invite.test.tsx:15` "showBareInviteResponse"

### 40. If a creator ignores an invitation past some expiry, is there a status transition to EXPIRED that any code path actually triggers, or does the row sit indefinitely reachable for accept/reject regardless of age?
There is no expiry anywhere. The collaboration status enum has no EXPIRED member, the entity has no expiry column, and no scheduled job ages invitations out. The client type even carries an offer-expiry field, but the mapper sets it to undefined and says plainly that the deals payload does not carry one — so the countdown a creator might expect is not backed by anything and the row stays answerable forever.
`src/lib/creator-deal-mappers.ts:218` "expiresAt: undefined,"


### 41. `DealController.counter` — is there any server-enforced maximum number of counter rounds, or can `counter` be called an unbounded number of times against the same deal (confirm against `DealService.counter`, lines around the counter-cap comments)?
There is no cap. The only gate is a status check that the deal is still negotiable, then an amount validation and an idempotency wrapper; nothing counts rounds, and no round counter exists on the collaboration entity. The one accidental brake is the idempotency fallback key, which is derived from deal id plus amount, so two counters at the identical amount on the same deal collapse into one no-op unless the client supplies its own key.
`influora-api/src/main/java/com/influora/service/DealService.java:572` "deal-counter:"

### 42. The code comment in `DealService` says a brand counter publishes no notification event ("no equivalent notification event exists for a brand counter") — does the creator's `creator-chat.tsx` poll or otherwise discover a brand's counter-offer any other way, or can it sit unseen until a manual refresh?
It is discovered. The missing piece is only the notification event; the counter is still pushed onto the deal message stream for both the superseded card and the new proposal, regardless of who countered. On top of that the chat refetches messages and deal state on stream reconnect and on tab foregrounding, so a brand counter reaches an open room without a manual reload. What the creator does not get is an out-of-room notification.
`influora-api/src/main/java/com/influora/service/DealService.java:1314` "publishToStream(collaboration.getId(), toMessageResponse(newProposal));"

### 43. `DealService#counter`'s amount validation is described as "deliberately narrower" than `validateProposalAmount` — what specific bound does the narrower check skip, and can a creator counter to a price the original proposal validation would have rejected?
The skipped bound is the campaign budget ceiling. The proposal validator rejects anything above the campaign budget maximum; the counter validator keeps only the positive-amount check and the budget floor. So yes, a creator can counter to a figure the opening-proposal validation would have refused, deliberately, and the javadoc concedes the resulting funding-coverage gap on the pool escrow path where the hold is the campaign budget maximum itself.
`influora-api/src/main/java/com/influora/service/DealService.java:1523` "campaign.getBudgetMax() != null && amount.compareTo(campaign.getBudgetMax()) > 0"

### 44. When a counter supersedes the prior proposal (`settleLatestProposal(..., "countered")`), is the previous proposal's card actually removed/disabled in creator-chat.tsx, or can a stale "Accept" button on the superseded offer still be clicked?
The stale card genuinely goes inert. The server settles the superseded card before persisting the new one and republishes it first, and the chat only renders the accept, counter and decline row when the card metadata status is pending. This matters because the accept endpoint carries no proposal id, so a clickable stale card would have accepted whatever the current offer is.
`src/pages/creator-chat.tsx:2458` "event.metadata?.status === 'pending' && canRespondToProposal"

### 45. Does accepting a counter-offer (`DealController.accept`) require the Idempotency-Key header, and if a client retries an accept without one after a timeout, can the same deal be accepted twice with two downstream effects?
The header is optional, but omitting it is safe here: the service falls back to a deterministic key built from the deal id, so a retry resolves to the same idempotency record and the already-completed branch returns the refreshed deal rather than re-running the accept. A status guard rejecting a non-acceptable deal is the second line of defence. This is unlike counter, where the fallback key includes the amount and therefore is not stable across genuinely distinct actions.
`influora-api/src/main/java/com/influora/service/DealService.java:342` "deal-accept:"


### 46. `DealController.reject` takes an optional `RejectRequest` body — is a rejection reason actually captured and shown to the other party, or does the UI collect a reason that never leaves the browser?
The server side works: a supplied reason is sanitised, written into a system message naming the actor, and pushed onto the stream where the counterparty sees it. The break is on the client — the creator decline handler passes undefined for the reason and the decline button opens no prompt, so the creator never has a reason to send and the counterparty always sees the generic default text.
`src/pages/creator-chat.tsx:1419` "await api.deals.reject(selectedDeal.id, undefined,"
### 47. Do deliverables carried forward from a superseded proposal (per the "carry forward deliverables ... when the counter does not revise them" comment) retain their original due dates, or can a stale deadline survive a price-only counter and go unenforced?
Neither, and the real gap is the opposite of the one asked about. A carried-forward slot has only a type and a quantity, so there is no per-deliverable due date to retain. The deadline lives at proposal level, and unlike deliverables it is not backfilled from the superseded card: the new proposal is persisted with whatever the counter body carried, so a price-only counter that omits the deadline drops the previously agreed date entirely rather than preserving a stale one.
`influora-api/src/main/java/com/influora/service/DealService.java:1304` "body.deadline());"

### 48. Is deal-room role authorization for `accept`/`counter`/`reject` scoped to a creator's own membership the same way `sendMessage`/`listMessages` are, or is there a narrower/looser check on one of the three that the others don't share?
All three start from the same ownership resolver the message routes use, which for a creator resolves the collaboration by deal id and the caller's own user id. The three mutations then add a check the read paths do not have: for a brand principal they demand deal-manager scope before proceeding. Reject is the one with a slightly different shape internally, re-loading the row with a pessimistic lock inside the transaction, but it is not looser.
`influora-api/src/main/java/com/influora/service/DealService.java:1373` "findByIdAndCreatorId(dealId, principal.getUserId())"

### 49. `streamMessages` uses SSE with `Last-Event-ID` replay — if a creator's browser reconnects after the `EMITTER_TIMEOUT_MS` window, are missed messages actually redelivered, or silently lost with the UI showing no gap indicator?
The server-side replay buffer exists but this client never uses it: the fetch-based transport sends no Last-Event-ID header at all, so the buffer is dead code for the creator chat. Nothing is silently lost anyway, because the reconnect handler refetches the whole thread and the deal, and foregrounding a hidden tab does the same unconditionally. Even for a client that did send the header, the buffer is bounded to two hundred events or five minutes, well short of the thirty-minute emitter timeout.
`src/pages/creator-chat.tsx:966` "the gap is unrecoverable from the transport (no Last-Event-ID replay)"

### 50. Does `markRead` get called automatically when a creator opens a deal thread, or does the unread badge in `creator-layout.tsx` require an action the UI never actually triggers?
It fires automatically. Selecting a deal runs an effect that posts the mark-read call whenever the live API is on and the selected deal has a non-zero unread count, so no explicit user action is needed. Note that the two badges are different things: the sidebar Deals badge is driven by the creator unread-count hook over the notifications endpoints, not by this call.
`src/pages/creator-chat.tsx:894` "api.messages.markRead("

### 51. `CreatorDeliverableController.upload` accepts `files`, `thumbnail`, `caption`, `hashtags`, `creatorNotes` — does the deliverable submission UI collect and send all five, or are some (e.g. `hashtags`) rendered as an input with no value ever reaching the multipart request?
Only two of the five ever reach the request. The submission dialog's data shape carries a deliverable id, one File and one caption string, and there is no thumbnail picker, no hashtag chips input and no separate creator-notes field anywhere in it — the single textarea is labelled to absorb all three by asking for caption, hashtags or notes in one free-text box. The chat handler then passes an options object containing caption alone, so the api client's hashtags and creatorNotes appends never run and the server's optional parts always arrive null.
`src/pages/creator-chat.tsx:1514` "{ caption: data.caption }"

### 52. After `upload` succeeds, does `submit` (`/{deliverableId}/submit`) require the uploaded content to exist server-side, or can a creator submit for review with nothing actually uploaded?
It is genuinely required. After the ownership lookup, the state gate and the cancelled-collaboration guard, submitForReview checks the persisted files JSON and throws a 400 with code NO_CONTENT when the deliverable has no stored file. The status response reinforces it client-side by setting canSubmit only when the status permits and the file list is non-empty, so the button is also disabled rather than merely failing.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:343` "if (!hasUploadedFiles(deliverable))"

### 53. Is there a declared revision-request status the brand can set that no creator-facing code path lets the creator act on — i.e., a "changes requested" state that's stored and displayed but has no resubmission flow wired to it?
Not for REVISION_REQUESTED — that gap was closed. The status is accepted by both creator gates, so a revised file can be uploaded and resubmitted, and the deal room renders a dedicated card for every row in that state that opens the revision dialog. The status that truly has no creator path is REJECTED, but that is deliberate: the entity comment marks it terminal and no creator gate admits it, so a rejected slot is meant to be dead rather than accidentally orphaned.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:827` "status == DeliverableStatus.REVISION_REQUESTED"

### 54. `DeliverableStatusResponse` — does `creator-deliverables`-equivalent UI (via `creator-deals.tsx`/deal room) render every status value the enum defines, or does an unhandled status fall through to a blank/default card?
The main deliverables tab renders no server status at all. Its item list is fabricated client-side from two integer counters on the deal, generating placeholder rows titled by index and assigning each one approved or pending purely by comparing its index against the done count, so eight of the ten enum values can never appear there. Real statuses only surface in the two supplementary blocks below it, which filter the live list for REVISION_REQUESTED and for the post-approval lifecycle rows; anything else, including SUBMITTED and REJECTED, is invisible on that screen.
`src/pages/creator-chat.tsx:1940` "i < selectedDeal.deliverablesDone ? 'approved' : 'pending'"

### 55. `reportMetrics` (`/{deliverableId}/metrics`) — are the numbers a creator self-reports here ever cross-checked against `verify`'s Meta-sourced numbers, or does a self-reported inflated number stand unchallenged if verification never runs?
The premise does not hold: verification cannot fail to run, because reportMetrics runs it itself. After the cheap validations it calls the verification service inline and then rejects the whole request unless the outcome is one that permits manual fallback, so a VERIFIED deliverable, an unconnected account and a bad post URL all return 409 rather than accepting the creator's numbers. Manual entry is reachable only when Meta genuinely failed for a connected account, which is the one case where there are no platform numbers to contradict.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:527` "verificationService.verify(deliverable)"

### 56. `markPosted` — does its request body's post URL get validated as belonging to the platform/account the deal actually requires, or is any URL string accepted and stored as proof?
It is validated hard, but only as a URL, never as this deal's URL. The layered checks cap length, force https, block loopback, private, link-local and metadata hosts, reject raw markup characters, and require the host to match a fixed platform allow-list. Nothing compares the host against the deliverable's own type or the creator's connected account, so a creator with an Instagram reel slot can mark it posted with any YouTube link, or with a stranger's Instagram permalink, and the row stores it as proof.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:668` "ALLOWED_POST_URL_HOSTS.stream()"

### 57. `verify` is described as running "the same verification the 6h batch job runs, on request" — does a failed on-demand verify actually surface the specific failure reason to the creator, or just a generic "verification failed" with the real cause only in server logs?
The specific reason does reach the browser, but raw. The response carries the outcome enum name, and the panel prints it verbatim inside parentheses in the alert body, so a creator sees strings like FALLBACK_TOKEN_EXPIRED or FALLBACK_UNRECOGNIZED_URL with no human translation. Worse, that branch is titled as still verifying and tells the creator it often resolves on its own and to try again shortly, which is wrong for the permanently-failing outcomes in the same enum such as the unsupported-YouTube and unrecognized-URL cases, where retrying can never succeed.
`src/components/creator/deal-room/deliverable-lifecycle-panel.tsx:227` "We couldn’t read this post from Instagram yet ({verification.outcome})"

### 58. The verify endpoint mentions "whether a manual fallback is permitted (only when Meta genuinely failed for a connected account)" — is that permission flag actually read and enforced by the UI before allowing manual proof upload, or is the manual-proof control always shown regardless?
It is read and enforced, and the enforcement is doubled. The panel reads the flag off the verification response, defaults it to false when no verification has run, and renders the manual metric inputs only inside a branch guarded on both a present verification and that flag. Even if that guard were bypassed, reportMetrics re-runs verification server-side and refuses manual numbers unless the outcome permits fallback, so the flag is advisory on the client and authoritative on the server.
`src/components/creator/deal-room/deliverable-lifecycle-panel.tsx:239` "verification && manualAllowed"

### 59. `listBulk` (`/creator/deliverables/bulk`) powers the dashboard rollup — if one `collaboration_id` in the batch errors server-side, does the whole bulk call fail for every deal, or does the dashboard silently show zero pending deliverables for the creator with no error surfaced?
There is no per-id failure mode at all: the service resolves the caller's owned ids in one query, seeds the result map with those, and fills it from a single deliverable query, so an id the creator does not own is simply absent rather than an error. The real exposure is the second half of the question — if the whole call throws for any reason, the dashboard swallows it into an empty object, so every deal contributes zero and the pending-deliverables tile reads zero with nothing telling the creator the number is not real.
`src/pages/creator-dashboard.tsx:99` ".catch(() => ({})"

### 60. Is there a client-side file-size/type check before `upload` that the server also enforces, or can a creator's browser block an oversized file while a direct API call would have been silently accepted (or vice versa — server rejects what the UI already let through)?
Both halves of the mismatch are real, in the harmful direction. The dialog checks only the declared MIME against a hardcoded list that includes application/pdf, and applies no size check whatsoever, while the server allows only image and video prefixes and additionally sniffs the magic bytes and requires the declared type to match — so a PDF passes the browser gate and comes back a 400 after the whole upload. The rejection is also silent on the way in: an unlisted type causes a bare return with no toast and no message, so the file simply never attaches.
`src/components/creator/deal-room/deliverable-submission.tsx:89` "'application/pdf'"

### 61. Does `CreatorDeliverableService#verifyNow` write its verified metrics to the same fields the brand's deliverable/analytics views read, or to a creator-only copy that a brand's screen never sees updated?
Same row, not a copy. Verification upserts the single DeliverableMetric keyed by the deliverable's milestone id and stamps it with a verified source, and the brand-facing campaign analytics service reads that same table by collaboration id, so a creator's on-demand verify immediately changes what the brand sees. The one structural constraint is the milestone link: a deliverable with no milestone id falls back before any write, so those slots are invisible to both sides rather than divergent between them.
`influora-api/src/main/java/com/influora/service/DeliverableMetricService.java:175` "deliverableMetricRepository.findByCollaborationIdIn(collaborationIds)"

### 62. If Meta verification returns a lower view/engagement count than the creator's self-reported `reportMetrics` value, which number does the payout/commission calculation actually use?
Neither, because no money path reads either number. Affiliate commission is the redemption's order amount times the resolved rate and never consults a metric row, and escrow release is driven by milestone amounts and status. Metrics touch money only as a gate, not as a multiplier: the ON_VERIFIED_METRICS release condition requires the deliverable to be in the VERIFIED state, which is a boolean about whether Meta confirmed the post, not a comparison of two counts. The situation the question describes therefore cannot arise.
`influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java:388` "redemption.getOrderAmount().multiply(commissionRate)"

### 63. Is there a maximum number of on-demand `verify` calls a creator can make per deliverable, and if so, is it enforced server-side or only suggested by a disabled button the API doesn't actually gate?
There is no cap of either kind. The auth rate-limit filter has a deliverable bucket, but its pattern covers only upload, submit and metrics — verify, mark-posted and proof are all outside it — and verifyNow itself keeps no attempt counter on the row. The button is disabled only while a call is in flight, so a creator can re-verify indefinitely. The sole brake is indirect: the pre-flight Meta usage tracker returns a rate-limited fallback once the account nears its Graph quota, which protects Meta rather than this endpoint.
`influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:88` "^/creator/deliverables/[^/]+/(upload|submit|metrics)$"

### 64. Does a deliverable ever reach a terminal VERIFIED state through any path other than `verify` or the batch job — e.g., can a brand or admin force-verify, and if so does that path apply the identical checks?
No other path exists. The entity's verify transition is invoked from exactly one call site, inside the private persist step of the verification service, which is reachable only after a real Instagram media match and insights fetch. Both entry points, the on-demand endpoint and the six-hourly job, go through the same public verify method, so they cannot diverge. Every other mention of the VERIFIED constant in the backend is a read — completeness sets, escrow release conditions, cleanup filters — never a write, and no admin controller can force it.
`influora-api/src/main/java/com/influora/service/verification/DeliverableVerificationService.java:300` "deliverable.applyVerify()"

### 65. `WalletController.withdraw` takes only `amount` — how does `requestCreatorWithdrawal` know which bank account (of possibly several `CreatorBankAccount` rows) to pay out to, and can a creator withdraw before ever adding a payout method?
The destination is resolved server-side, never chosen by the client: the service loads the caller's single primary bank row and passes it to the RazorpayX fund-account resolver, so the account the creator marked primary is the only possible destination. A creator with no rows at all, or rows but none primary, gets a 409 with code BANK_ACCOUNT_NOT_FOUND before anything is debited. A second precondition sits right after it, requiring identity KYC to have been submitted, so the money-out path has two setup gates rather than one.
`influora-api/src/main/java/com/influora/service/WalletService.java:293` "findByCreatorUserIdAndPrimaryTrue(userId)"

### 66. `MIN_CREATOR_WITHDRAWAL`/`MAX_CREATOR_WITHDRAWAL` are enforced in `WalletService` — does creator-wallet.tsx read and display these same bounds, or can the withdraw form submit an amount the UI never warned was out of range?
Half of it is mirrored, and the mirror is a copy rather than a read. The page hardcodes a minimum of five hundred with a comment admitting it must be kept in step with the server constant by hand, so the two can drift silently. The upper bound has no client counterpart at all, and neither does the three-per-day cap, so an amount above one lakh or a fourth withdrawal in a day submits with no warning and comes back as a server error the creator sees only after pressing the button.
`src/pages/creator-wallet.tsx:124` "const MIN_WITHDRAWAL_INR = 500"

### 67. Does `withdraw` require an `Idempotency-Key` the way `topup` does (compare the `@RequestHeader` `required` flag on both), and per creator-wallet-withdraw-idempotency.test.tsx, what actually happens if the header is omitted and the request is retried after a client timeout?
The two controller declarations genuinely differ — top-up marks the header required, withdraw marks it optional — but the difference is cosmetic, because the service rejects a null or blank key with a 400 before touching the wallet, so an omitted header can never reach the ledger and therefore can never double-debit. The client never omits it anyway: the page mints one key per logical submission, holds it in state and reuses it across retries of that same submission, which is exactly what the test file pins.
`influora-api/src/main/java/com/influora/web/WalletController.java:120` "required = false"

### 68. `GET /wallet/payouts` is explicitly creator-only and 403s a brand principal — does creator-wallet-payouts.test.tsx (or the equivalent page) distinguish that 403 from "no payouts yet," or does the UI render an empty state for both?
They are distinguished. Any rejection, a 403 included, sets a dedicated error flag and clears the rows, and the Payouts tab branches on that flag first, rendering a could-not-load card with a retry button and firing a toast carrying the server message, before it ever reaches the no-payouts-yet card. Only a successful call with an empty array produces the empty state, so a failed money read is never disguised as an honest zero.
`src/pages/creator-wallet.tsx:530` "setPayoutsError(true)"

### 69. Does the balance shown on `creator-wallet.tsx` (`/wallet/balance`) ever include escrowed-but-unreleased funds as spendable, or is the distinction between available and pending balance enforced server-side and reflected correctly in the UI?
The separation is real on both sides. Available balance is the wallet row's own balance column, secured funds are a separate sum over the creator's funded milestones, and pending payouts is a third sum over payout rows not yet confirmed at the bank. The withdrawal guard compares the requested amount against the wallet balance alone, so escrowed money is not spendable, and the page renders the three server fields as-is under three distinct labels with no client arithmetic mixing them.
`influora-api/src/main/java/com/influora/service/WalletService.java:216` "sumAmountByCreatorUserIdAndConfirmedAtIsNull(userId)"

### 70. When a withdrawal is requested, is a `Payout` row created synchronously before the response returns, or can the wallet UI show a success toast for a withdrawal that never actually got a corresponding ledger/payout entry?
Synchronously, in one transaction. The processing step posts the ledger debit, resolves a real fund account, calls RazorpayX, then persists a queued payout row, and only then builds the response from the gateway's payout id — so a success toast implies all four happened. The javadoc records that this was once exactly the failure the question describes: the method used to return a fabricated payout id derived from the ledger reference with no gateway call and no payout row at all.
`influora-api/src/main/java/com/influora/service/WalletService.java:401` "payoutRepository.save("

### 71. `WalletTransactionType.WITHDRAWAL` entries in `/wallet/transactions` — do they reconcile 1:1 with rows in `/wallet/payouts`, or can a transaction exist with no matching payout (money debited from the ledger with nothing actually disbursed)?
For a creator wallet they do pair up. Only two code paths write a WITHDRAWAL against a creator wallet, the self-service withdrawal and the admin manual-payout record, and each saves a payout row in the same transaction as the debit. The third writer takes a workspace id and creates no payout row, but that debits a brand wallet, which never appears in a creator's history. What does not pair is the reversal: a bounced payout is re-credited as a separate entry typed PAYOUT while the original debit stands, so the two tabs deliberately tell different stories.
`influora-api/src/main/java/com/influora/service/PayoutReconciliationService.java:530` "re-credited to creator wallet"

### 72. Does creator-wallet.money-buckets.test.tsx's bucket logic (available/pending/lifetime) match how `WalletService` actually computes those figures, or does the frontend derive its own bucket totals from raw transactions that can drift from the server's number?
The premise names a bucket that does not exist: there is no lifetime figure on this screen, because no lifetime-earnings endpoint exists and the page explicitly stopped fabricating a total-earned number. The three that do exist are assigned straight from the summary response fields with no client derivation, and a failed fetch resets them to an all-null constant that renders as a dash rather than a confident zero, so the frontend cannot drift from the server.
`src/pages/creator-wallet.tsx:454` "escrowLocked: remote.escrowLocked ?? 0"

### 73. If a withdrawal fails at the payment-processor step after the wallet has already been debited, is there a compensating credit-back path, or does the creator's balance stay silently short with only a support ticket as recourse?
There is a real compensating path, and more than one trigger for it. A verified reversal, rejection or cancellation webhook re-credits the amount from the clearing wallet back to the creator's wallet, keyed so the re-credit itself is idempotent, and a separate orphaned-debit sweeper catches the case where the gateway confirmation was lost entirely, retrying first and reversing the debit only if the retry also fails. The debit is never rewritten — the credit is appended alongside it, which is why the payouts tab reads the gateway's terminal status rather than the ledger.
`influora-api/src/main/java/com/influora/service/PayoutReconciliationService.java:522` "ledgerService.post("

### 74. Does the `period` filter on `/wallet/transactions` ("this-month"/"last-month"/"3-months"/"all") match exactly what the History tab's dropdown sends, or can the UI send a value `resolvePeriodRange` doesn't recognize and silently get back the unfiltered "all" result?
The four dropdown values match the resolver's three named cases plus its default exactly, and the selection is now both passed to the fetch and in the effect's dependency list, so changing it re-queries. The silent-fallback risk the question describes is real in shape — an unknown value returns a null range and therefore unfiltered rows with no error — but no control on this page can produce one, since the select is a closed list of those same four literals.
`src/pages/creator-wallet.tsx:918` "3 Months"

### 75. `WalletController.addPayoutMethod` — are `accountOrVpa`/`ifsc` actually encrypted before persistence as the comment claims, and does `BankAccountResponse` ever leak the plaintext value back to the client on any response path (including error responses)?
The encryption is real, not aspirational: the service runs both values through the AES-GCM cipher before building the row, and the entity has no plaintext columns to write them to. The response record has exactly five fields — id, type, display mask, primary flag and usable flag — and no code path decrypts into it, so neither the create nor the list response can echo the input. The error path is clean too, because the validation handler emits only a field name and a message, never the rejected value.
`influora-api/src/main/java/com/influora/service/payout/CreatorBankAccountService.java:65` "cipher.encrypt(accountOrVpa.trim())"

### 76. `setPrimaryPayoutMethod` — does the server enforce exactly one primary account, or can two methods both be marked primary through a race between two rapid requests?
It is enforced twice over. The method takes a pessimistic lock on every one of the creator's rows before it reads anything, so a second concurrent call blocks until the first commits and then re-reads current state rather than racing a stale view; it clears the flag on the previous primary and sets it on the target inside that same transaction. Behind that sits a unique partial index on the primary marker, which turns the one window the lock cannot cover — two inserts when no rows exist yet to lock — into a clean 409 rather than two primaries.
`influora-api/src/main/java/com/influora/service/payout/CreatorBankAccountService.java:131` "lockAllForCreatorUpdate(profile.getUserId())"

### 77. If a creator deletes/replaces their only bank account, does the wallet UI block a subsequent withdraw attempt, or does `withdraw` succeed server-side with no destination account to actually pay out to?
The delete case cannot arise — the controller exposes only a list, a create and a set-primary route, so there is no way to remove an instrument through the API at all. Replacement is the live scenario, and it is guarded: a newly added instrument is non-primary unless it is the first, and the fund-account resolver called inside the withdrawal transaction rejects any account still inside its twenty-four hour cool-down, throwing before RazorpayX is contacted so the ledger debit in the same transaction rolls back. A creator with no primary row is stopped even earlier, by the 409 in the withdrawal service.
`influora-api/src/main/java/com/influora/service/payout/RazorpayFundAccountService.java:71` "BANK_COOLDOWN_ACTIVE"

### 78. `MeTaxIdentityController.submit` — there is no corresponding GET; how does creator-settings.tsx know whether a tax identity was already submitted and what value to show, or does the form always render blank even after a successful prior submission?
It always renders blank. The controller exposes a single POST, no read route exists anywhere, and the form initialises both fields to empty strings unconditionally. The saved GSTIN and masked PAN it displays come only from the in-memory response of the submission just made, so closing the dialog and reopening it later shows an empty form again with no sign anything was ever stored, and the settings row's description is a fixed string inviting the creator to add details they may already have added.
`src/components/creator/TaxIdentityForm.tsx:72` "defaultValues: { gstin: '', pan: '' }"

### 79. Is GSTIN/PAN format validated server-side in `TaxIdentitySubmitRequest`, client-side in the settings form, both, or neither — and if only client-side, can a malformed value be submitted directly against the API?
Both, and deliberately so. The request record carries only length constraints, with a comment explaining that annotation-level pattern checks would run before the service can trim and upper-case the input; the service then normalises and applies its own compiled GSTIN and PAN regexes, returning typed 400s. The form's zod schema uses character-for-character the same two patterns plus an at-least-one rule, so a direct API call with a malformed value is rejected on the identical grounds the browser would have used.
`influora-api/src/main/java/com/influora/service/CreatorTaxIdentityService.java:74` "PAN_PATTERN.matcher(pan).matches()"

### 80. Does a submitted tax identity actually get attached to the invoices `CreatorInvoicingController` generates, or can a commission/campaign invoice PDF be generated with tax fields blank despite a valid submission on file?
The submit writes onto the creator profile, and the campaign service invoice reads the GSTIN straight off that same profile at issue time, so a submission made first does reach the document. The ordering is the exposure: the read is an unguarded snapshot with no null check and no backfill, and invoices are issued automatically at escrow release, so any release that happens before the creator submits produces a permanently blank GSTIN on that invoice. PAN is never copied onto the campaign invoice at all; only the commission document gets the whole profile handed to its renderer.
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:222` "creatorGstin(creator.getGstin())"

### 81. `CreatorCouponController.list` is explicitly read-only, with creation living on `CampaignTrackingController` — does creator-coupons.tsx ever call a creation/edit endpoint that doesn't exist on this read-only controller?
No. The page imports a single hook and renders what it returns, the hook makes exactly one call, and the client module for creator coupons exposes only a list function with no create, update or delete sibling. There is no dead button here either: the page offers copy and share affordances over the returned code and tracking links, never a create control, so the read-only contract is respected on both sides.
`src/hooks/creator/useCreatorCoupons.ts:32` "await api.creatorCoupons.list()"

### 82. `CreatorCouponListItem` — does it include a usage count or redemption total, and if so, is that number live from the tracking service or a stale snapshot taken at coupon-creation time?
It carries a usage count and a usage limit, and the count is live rather than a snapshot: it is read off the coupon row at list time, and the redemption writer increments that same column on every successful redemption inside the redemption transaction. What it does not carry is a redemption total in money — the entity's own comment records that the coupon table has no revenue column at all, so per-coupon revenue is deliberately absent rather than stale, and only the per-order amounts on the redemption rows could reconstruct it.
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:92` "coupon.incrementUsageCount()"

### 83. `CreatorAffiliateEarningController.list`'s SETTLED-vs-pending summary — does creator-affiliate-earnings.tsx sum the individual rows itself, or trust the server summary, and can the two disagree when a page boundary splits a settlement?
The client trusts the server and does no arithmetic of its own — the summary cards read four precomputed fields straight off the response. They cannot disagree at a page boundary because the summary is deliberately computed from the creator's full history, not from the sliced page: the service fetches every earning, slices only the rows it serialises, and passes the unsliced list to the summary builder. Loading more pages replaces the summary with an identical recomputation rather than accumulating one.
`influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java:233` "buildSummary(earnings, redemptionsById)"

### 84. With `page`/`limit` both optional and defaulting to "page 0, the service's own default size" — does creator-affiliate-earnings.tsx send 1-indexed or 0-indexed page numbers, and could that off-by-one silently duplicate or skip a page of earnings in the UI?
Both sides are zero-indexed and they agree. The hook's first fetch asks explicitly for page zero, then sets its cursor to one and increments after each successful load-more, which lines up exactly with the server treating a non-negative page number as a zero-based offset multiplied by the page size. The server also widens that multiply to a long before computing the slice, so an absurd page number degrades to an empty page rather than throwing, and the client's own has-more flag comes from the server rather than being inferred.
`src/hooks/creator/useAffiliateEarnings.ts:55` "api.affiliateEarnings.get(0, PAGE_SIZE)"

### 85. Is there a cap on coupon discount or commission rate that's stored on the campaign/coupon record but never checked when `AffiliateEarningsService` computes a creator's commission row?
The one stored limit, the coupon's usage limit, is enforced — the redemption validator rejects a code at or over it before writing. What has no cap anywhere is the money: the commission rate is read off the campaign and multiplied by the order amount with no upper-bound check at compute time and no validation at write time, and no request DTO in the codebase even exposes that field, so it is reachable only through the database. The percentage discount branch is similarly unclamped, unlike the fixed branch right below it, which does floor the discount at the order amount.
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:153` "multiply(coupon.getDiscountValue())"

### 86. When a coupon is deactivated by the brand mid-campaign, do already-accrued but unsettled affiliate earnings for that coupon still get created, or does deactivation silently orphan pending commission that was already promised?
The premise does not hold: coupons have no deactivation. The entity's fields are id, workspace, campaign, creator, code, discount type and value, usage limit, usage count, expiry and creation time — there is no active flag, no status, and no endpoint that would set one. A coupon stops working only by passing its expiry or exhausting its usage limit, both checked at redemption time, and neither touches earnings already recorded, which stay pending until the settlement batch picks them up.
`influora-api/src/main/java/com/influora/domain/entity/CouponCode.java:58` "private Integer usageLimit;"

### 87. `CreatorReviewController.list` (`/received`) — does `creator-received-reviews.tsx` render a review whose rating is present but whose text body is null, or does a missing text field break the render entirely?
A null body renders fine: the paragraph is behind a truthiness guard, so it is simply omitted while the star row, the reviewer-type badge and the date all still render from the other fields. The more interesting gap on this card is the reviewer's name — the mapper explicitly blanks it on every live row because the server response carries only a reviewer user id, so a real creator sees an unattributed review while the demo build shows a brand name.
`src/components/creator/creator-received-reviews.tsx:150` "{r.text && <p"

### 88. `POST /creator/reviews` — can a creator review a brand for a deal that never reached a completed/eligible state, or does the server enforce the same completed-deal precondition the UI's "leave a review" button visibility implies?
The server enforces it independently of the button. The shared create path first resolves the collaboration scoped to the caller's own creator id, then rejects with a 409 unless the collaboration status is COMPLETED, so a hand-crafted request against an in-progress deal fails on the same rule the UI implies. Ownership and state are two separate checks, so another creator's completed deal is a not-found rather than a conflict.
`influora-api/src/main/java/com/influora/service/ReviewService.java:115` "CollaborationStatus.COMPLETED"

### 89. `/{reviewId}/flag` — once a creator flags a review, does anything actually happen server-side (a moderation queue row, a status change), or does the endpoint 200 with no observable effect and the flagged review stays displayed unchanged forever?
Something real happens. A sanitised non-blank reason is required, a duplicate flag by the same user is rejected as a 409, and a content-flag row of type REVIEW is persisted with a two-hundred-character preview of the review text, the reason and the flagging user, and its status is returned to the client. Those rows are the same ones the admin moderation service reads, so the flag lands in a queue a human can work. What the endpoint does not do is change the review itself, so the flagged review does stay visible until a moderator acts.
`influora-api/src/main/java/com/influora/service/ReviewService.java:183` "existsByContentIdAndFlaggedByUserId(review.getId(), flaggedByUserId)"

### 90. Is there a one-review-per-deal constraint enforced server-side, and if a creator's client retries a slow POST, can two review rows be created for the same deal?
The constraint is per collaboration and per reviewer type, so each side may review once, and it is enforced twice. A pre-check rejects an existing review with a 409, and because a pre-check alone loses a genuine race, the save is wrapped so a database uniqueness violation is translated into the same 409 rather than a 500. A retried slow POST therefore either replays into that conflict or was the only write, never two rows.
`influora-api/src/main/java/com/influora/service/ReviewService.java:122` "existsByCollaborationIdAndReviewerType("

### 91. `CreatorDisputeController.list` and `/eligible-deals` — does creator-disputes.tsx only let a creator open a dispute against deals actually present in `/eligible-deals`, or can the "open dispute" form be reached for a deal the eligibility endpoint would have excluded?
The form's only deal control is a select whose options are mapped one-for-one from the eligible-deals response, and the whole form is replaced by an explanatory empty state when that list comes back empty, so on this page an ineligible deal is unreachable. The other entry point, the deal room overflow menu, is gated separately on the deal's funded flag rather than on this endpoint — but both converge on the same server checks, which reject a deal with no funded unreleased escrow and a deal that already has an active dispute.
`src/pages/creator-disputes.tsx:258` "eligibleDeals.map((deal)"

### 92. `DealController.openDispute` (`POST /deal/{dealId}/disputes`) — per creator-chat.dispute-entry.test.tsx, does the entry point in the chat UI send the same `dealId` scoping `CreatorDisputeController.list` uses to show a creator only their own disputes, or could a mismatch expose/hide the wrong set?
No mismatch is possible, because the path id is never trusted as identity on either side. The chat sends the selected deal's id through the creator-role client, and the service resolves the collaboration from the authenticated principal and that id together, so a foreign id is a not-found rather than a cross-tenant write. The list endpoint scopes by the same principal. The one thing worth naming is that the client duplicates this call for each role purely so the correct role's token is attached.
`src/lib/api.ts:5676` "role: 'creator', body: { reason }"

### 93. Once a dispute is opened, does the escrow hold on that deal's funds actually change state (per `EscrowService`), or does opening a dispute have zero effect on money movement while the UI implies funds are now frozen?
The freeze is real and deliberately ordered ahead of the dispute row. The service refuses outright when there is no funded unreleased escrow to freeze, then freezes the unreleased holds before persisting the dispute, so a crash between the two can never leave an open dispute over releasable money; the collaboration is then transitioned to DISPUTED. The UI's claim that funds are frozen is therefore accurate rather than decorative.
`influora-api/src/main/java/com/influora/service/DisputeService.java:141` "freezeUnreleasedForDispute(collaboration.getId())"

### 94. Is there a dispute status that `AdminDisputeController` can set that `CreatorDisputeController.list` never renders a label for, leaving the creator's dispute list showing a stale or blank status after admin action?
No. The lifecycle enum has exactly five values — open, under review, and the three terminal resolutions — and the creator page's label map declares all five, including creator-facing wording for each resolution outcome. Nothing an admin can set falls through to a blank badge, and there is no sixth status hiding in the admin surface, since the resolve endpoint rejects any resolution that is not one of the terminal three.
`src/pages/creator-disputes.tsx:34` "RESOLVED_SPLIT: 'Resolved — split'"

### 95. When a dispute resolves in the creator's favor, is a corresponding wallet/ledger entry created automatically, or does resolution only update the dispute row with the actual payout requiring a separate manual step nothing in the UI prompts for?
It is automatic and it happens first. Resolving in the creator's favour calls the escrow release primitive for that collaboration before the dispute row is flipped to its terminal status, so a failure in the money movement rolls the whole transaction back and the dispute is never left resolved with funds unmoved. There is even a guard for the silent version of that bug: if the collaboration had frozen holds before the call and the settlement moved none, the resolve refuses rather than logging a movement that never happened.
`influora-api/src/main/java/com/influora/service/DisputeService.java:250` "adminReleaseForDispute(dispute.getCollaborationId())"

### 96. `CreatorAnalyticsController.metrics`/`.scores`/`.demographics`/`.media` — does creator-analytics.tsx call all four, and if `.demographics` or `.media` fails independently, does the page show a partial dashboard with a clear per-section error, or does one failed call blank the entire page?
All four are called, each through its own hook with its own loading, error and refresh handles, so no failure can blank the page — the sections that loaded still render. The error surfacing is coarser than per-section though: the first three collapse into one page-level banner that concatenates whichever messages are set with a separator and offers a single retry that refires all three, so the creator reads which call failed only from the message text. Media is excluded from that banner and handled beside its own section, and a not-found score is deliberately treated as an empty state rather than an error.
`src/pages/creator-analytics.tsx:112` "Boolean(metricsError || scoresError || demographicsError)"

### 97. Are the `.scores` values (quality/performance score) computed from the same verified deliverable data `CreatorDeliverableService#verifyNow` produces, or from a separately maintained score that can go stale relative to what's actually been verified?
Separately maintained, and on a different clock and a different table. The endpoint returns the most recent stored score row, and the only writer is a nightly job that runs at four in the morning UTC and derives quality, consistency and posting frequency from the creator's account-level metrics and recent media — never from the deliverable metric rows verification writes. So a deliverable verified this afternoon changes nothing on the scores card until the next nightly run, and a creator whose job has never run gets an explicit not-found the page renders as an empty state.
`influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java:135` "0 0 4 * * *"

### 98. `CreatorCopilotController.suggestion/today` — is "today" computed server-side per creator timezone, or does a creator in a different timezone than the server ever get shown yesterday's or tomorrow's suggestion mislabeled as today's?
It is a fixed UTC day boundary with no per-creator timezone anywhere in the service — the day window is built by taking the current instant, converting it to a UTC local date and going back to that date's start of day. For this product's Indian creators that is a five-and-a-half-hour offset, so between local midnight and half past five in the morning the server is still on the previous UTC day and returns yesterday's row under a card headed as today's idea. The client's cache key is derived per day too, so it agrees with the wrong day rather than correcting it.
`influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorNudgeService.java:270` "atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC)"

### 99. `.../{id}/dismiss` and `.../{id}/acted` — are these mutually exclusive server-side (can a suggestion be both dismissed and marked acted), and does creator-copilot.tsx prevent firing both against the same id, or can a double-click send both and leave the row in an inconsistent state?
Server-side they are not exclusive: each stamps its own set-once timestamp column on the row and neither reads the other, so a direct API caller can leave a suggestion both dismissed and acted. The UI cannot produce that, for two reasons: both buttons share one busy flag that disables them while either call is in flight, and the optimistic session marker flips the card to its collapsed next-one-tomorrow body immediately, unmounting both controls before the request even returns.
`src/components/creator/copilot/DailySuggestionCard.tsx:44` "dismissState === 'submitting' || actedState === 'submitting'"

### 100. Does an "acted" suggestion actually trigger the action it recommended (e.g. navigate to and pre-fill a real flow), or does clicking "act on this" only record the acted-on event with no functional follow-through?
It records the event and nothing else. The control is labelled as marking the idea done, and its handler awaits the acted call and then falls through to the same collapsed card the dismiss path produces — there is no navigation, no draft creation, no pre-filled composer, and the suggestion payload itself carries only a theme, a headline and a content idea with no target route or campaign to act on. So the button is an honest completion checkbox rather than a broken call to action, but a creator expecting it to open something will get nothing.
`src/components/creator/copilot/DailySuggestionCard.tsx:61` "await onMarkActed(suggestion.id)"
