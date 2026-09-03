# R2-A3 — Brand Base Answers

### 1. When a brand edits company profile fields on brand-settings.tsx and saves, does every changed field actually reach a PATCH/PUT request body, or does the form silently drop fields the backend Workspace entity has no column for?
Every field the Workspace Information card edits (name, email, phone, websiteUrl) reaches the PATCH body, and the F-0462 fix additionally carries industry, companySize, description and logoUrl forward from the last loaded record because PATCH /workspaces/me is full-replace and omitting them cleared them server-side. The card only surfaces four editable inputs, so there is no UI control whose value has no backing column — the drop risk was the inverse (silently wiping unsurfaced columns) and it is closed.
`src/pages/brand-settings.tsx:202` "api.workspaces.updateMe"
`influora-api/src/main/java/com/influora/web/WorkspaceController.java:71` "body.name()"

### 2. WorkspaceMemberController.invite (POST /workspace/members/invite) — does the invited email actually receive a message, or does the row get created with no notification/email ever dispatched?
A message is dispatched. inviteMember calls queueInviteEmail on every path, which either writes an EmailOutbox row for an invitee who already has a users row, or, when there is no account yet, sends directly through Msg91EmailClient keyed by the raw address. The direct branch is not retried by EmailWorker, so a hard send failure only produces a log.error, but the invite is not undiscoverable by design.
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:182` "queueInviteEmail(invite, rawToken"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:508` "msg91EmailClient.sendTemplateEmail"

### 3. WorkspaceMemberController.accept — is the invite token single-use and expiry-checked, or can the same invite link be replayed after acceptance to re-add a removed member?
It is both single-use and expiry-checked. acceptInvite rejects an already-ACCEPTED invite with 409, a REVOKED one with 410, and an expired one with 410 after lazily flipping the status, and it additionally requires the caller's own account email to match the invited address. A removed member cannot replay the old link because the invite row is already in ACCEPTED state.
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:208` "MemberInviteStatus.ACCEPTED"

### 4. revoke — if a member's session/JWT was already minted before revoke, does any subsequent request from them actually get rejected?
The premise is slightly off: revokeInvite only voids a PENDING invite that was never accepted, so there is no session to reject. For an accepted member who is later deactivated, endpoints that go through requireMember reject on the very next request because it filters on the active flag, but endpoints that only call requireBrandWorkspace do not re-check membership, and JwtAuthenticationFilter is a pure token parse that never touches the database — so a stale access token keeps working on those for its remaining lifetime.
`influora-api/src/main/java/com/influora/service/BrandContextService.java:72` "JwtAuthenticationFilter is a pure token parse and never"
`influora-api/src/main/java/com/influora/service/BrandContextService.java:87` "findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId, principal.getUserId())"

### 5. DeleteMapping("/{memberId}") — does removing a member also invalidate their currently-issued refresh tokens?
No. deactivateMember flips the member row inactive and saves it, and nothing in that method touches RefreshTokenRepository — revokeAllForUser is only called from logout and password reset. The removed user can therefore keep rotating their refresh token indefinitely; the AuthService refresh gate only rejects on user status SUSPENDED/DEACTIVATED and on workspace suspension, not on lost workspace membership, and it will simply re-resolve them to whatever other active membership they have (or to null).
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:289` "target.deactivate()"
`influora-api/src/main/java/com/influora/service/AuthService.java:457` "UserStatus.DEACTIVATED"

### 6. switch — does every subsequent brand-scoped call resolve against the newly switched workspace, or can stale JWT claims route a request to the previous workspace?
switchWorkspace confirms active membership in the target, then mints a fresh access token stamped with that workspaceId, and requireBrandWorkspace reads exactly that claim. So correctness depends entirely on the client replacing its stored token: any request still sent with the pre-switch access token resolves to the old workspace, because the resolver trusts the claim and only falls back to a lookup when the claim is blank.
`influora-api/src/main/java/com/influora/service/BrandContextService.java:45` "principal.getWorkspaceId()"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:402` "jwtService.createAccessToken"

### 7. MemberRole enum — is there any backend route that lets a non-OWNER member change billing/plan, and does the frontend hide that control for non-owners?
The gate is real on both sides, and it is OWNER-or-ADMIN rather than OWNER-only. BillingController.checkout and cancel both run requireMember followed by requireRole with OWNER and ADMIN, so a MANAGER/MEMBER/VIEWER gets 403; the page mirrors that with useBrandBillingAccess and disables both the Upgrade and Cancel controls with a stated reason rather than letting the click surface a raw 403.
`influora-api/src/main/java/com/influora/web/BillingController.java:178` "MemberRole.OWNER, MemberRole.ADMIN"
`src/pages/brand-billing-settings.tsx:252` "useBrandBillingAccess"

### 8. brand-onboarding.tsx posts to OnboardingController — does every field the onboarding wizard collects get persisted, or are some collected in UI state and never sent?
The premise's "use-case" field does not exist: OnboardingData's step-2 shape is companyName, companySlug, workspaceType, companySize, industry, websiteUrl, description and the logo upload, and handleCompanySaveAndNext sends all of them to saveBrandCompany, which maps them straight onto the Workspace row. The historical drops in this wizard (phone, acceptedTerms) were in the register call, not the company call, and both are fixed and now sent.
`src/pages/brand-onboarding.tsx:88` "api.onboarding.saveBrandCompany"
`src/components/brand/onboarding/onboarding-steps.tsx:51` "export interface OnboardingData"

### 9. brand-verification.tsx — is there a real backend verification status that other brand-only actions are gated on, or is the badge decorative?
It is real and server-set: AdminBrandService.applyKycDecision writes VERIFIED or REJECTED onto the Workspace row, and CampaignValidator.validateStatusForWorkspace refuses to move a campaign to ACTIVE unless the workspace is VERIFIED. The gate is narrower than the question assumes, though — it covers publishing a live campaign, not escrow funding or creator invites, which have no verification check of their own.
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:64` "workspace.getVerificationStatus() != VerificationStatus.VERIFIED"
`influora-api/src/main/java/com/influora/service/admin/AdminBrandService.java:277` "workspace.applyKycDecision(toStatus"

### 10. BrandEmailOtpService — after OTP is verified once, is the OTP code invalidated server-side?
It is not invalidated. verifyOtp sets the verified flag and saves, but never deletes the row, clears the hash, or refuses an already-verified challenge, so the same code replays successfully against the same address until the 300-second TTL lapses or the third attempt is consumed. It cannot be used to verify a different email, however: the challenge is looked up by normalized email, so a replay only re-verifies the address the code was issued for.
`influora-api/src/main/java/com/influora/service/BrandEmailOtpService.java:148` "challenge.setVerified(true)"

### 11. Does OnboardingService's next-action resolution match what brand-onboarding.next-action-live.test.tsx asserts against the live backend?
False premise on both halves. OnboardingService has no next-action resolution — its brand surface is saveBrandCompany, completeBrand, submitBrandKyc, getBrandOnboardingStatus and dismissBrandKycPrompt. And the test is not live: despite the filename it imports YoureInStep directly and renders it in jsdom with a vi.fn stub for onComplete, so it pins that the step-3 cards are real buttons carrying a destination, and touches no backend at all.
`src/pages/brand-onboarding.next-action-live.test.tsx:28` "import { YoureInStep }"

### 12. If a brand abandons onboarding partway and later resumes, does the resume path reconstruct state from the persisted Workspace row?
No — it restarts with a blank form. The page seeds its state from initialData, which is all empty strings, and only the step index is resumed (jumping to step 2 when a brand token exists); nothing fetches GET /workspaces/me to rehydrate the company fields. Since saveBrandCompany then calls applyCompanyDetails as a full overwrite, re-submitting step 2 replaces the previously-saved industry, size, website, description and logo with whatever the blank-then-refilled form now holds.
`src/pages/brand-onboarding.tsx:38` "React.useState<OnboardingData>(initialData)"
`influora-api/src/main/java/com/influora/service/OnboardingService.java:62` "workspace.applyCompanyDetails"

### 13. CampaignController.create — for every field brand-new-campaign.tsx renders, is there a corresponding field in CampaignWriteRequest, or does at least one UI control's value never leave the browser?
Every control in the wizard reaches the body. The real form is campaign-form.tsx, and its handleSubmit payload carries title, description, objectives, campaignType, status, budget, timeline, platforms, contentTypes, requirements, hashtags, brandGuidelines, isPrivate, maxCollaborators and targetAudience — a superset match against CampaignFormData, which has no other members. The inverse gap exists instead: CampaignWriteRequest has an applicationDeadline field that this wizard never renders and never sends, so a deadline can only be set through another path.
`src/components/brand/campaigns/campaign-form.tsx:512` "targetAudience: formData.targetAudience"
`influora-api/src/main/java/com/influora/web/dto/campaign/CampaignDtos.java:85` "TargetAudienceDto targetAudience"

### 14. CampaignValidator — does it enforce the same budget-minimum / deadline-in-future rules the frontend validates?
Partly. The budget floor is real server-side: validateBudget rejects a min at or below zero and a max below min, so a crafted negative or zero budget is refused. The deadline rule is not: validateTimeline only asserts endDate after startDate and applicationDeadline before startDate, and nothing compares any date to today — so a request that skips the client can create a campaign whose entire timeline is in the past, while the form's own calendar disables past dates.
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:35` "budget.min().compareTo(BigDecimal.ZERO) <= 0"
`src/components/brand/campaigns/campaign-form.tsx:975` "date < new Date()"

### 15. CampaignController.update — once a campaign has funded escrow or an accepted deal, does the backend block a budget-changing patch?
Not reliably. update gates only on status: ensureEditable blocks COMPLETED/CANCELLED outright and blocks non-status fields while ACTIVE, and nothing anywhere in the method consults escrow holds, milestones or accepted collaborations. A brand whose campaign has money already committed can send a status-only patch to PAUSED, then a second patch changing the budget, then resume — the budget moves under the existing deals with no rejection.
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:102` "current == CampaignStatus.ACTIVE && !statusOnlyPatch"
`influora-api/src/main/java/com/influora/service/CampaignService.java:230` "if (req.budget() != null)"

### 16. CampaignController.delete — does deleting a campaign with an active Collaboration/deal get rejected, or does it orphan deal/escrow rows?
It is rejected, though indirectly. ensureDeletable permits deletion only from DRAFT, so any campaign that ever went ACTIVE — the precondition for a deal existing against it — can never be deleted at all. The delete itself is a hard repository delete with no cascade handling, so that status gate is the only thing standing between this route and orphaned rows.
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:112` "current != CampaignStatus.DRAFT"
`influora-api/src/main/java/com/influora/service/CampaignService.java:351` "campaignRepository.delete(campaign)"

### 17. CampaignController.duplicate — does the duplicate get a genuinely new id and reset status, or does it copy state that should never carry over?
It mints a fresh ULID and forces the copy to DRAFT, and it copies no escrow, milestone or collaboration reference — those live in separate tables keyed by the source campaign id, so nothing financial follows the copy. One state field does carry over that arguably should not: the hype config JSON is copied verbatim, which for a HYPE campaign brings the source's filled-slot count and live-until window into a brand-new draft.
`influora-api/src/main/java/com/influora/domain/entity/Campaign.java:449` "status(CampaignStatus.DRAFT)"
`influora-api/src/main/java/com/influora/domain/entity/Campaign.java:467` "hypeConfigJson(hypeConfigJson)"

### 18. brand-edit-campaign.tsx — for an active campaign, does the backend enforce which fields are still editable, or is the active edit gate frontend-only?
The backend enforces it. The ACTIVE-aware overload of ensureEditable throws CAMPAIGN_ACTIVE_NOT_EDITABLE whenever a patch on an ACTIVE campaign touches any field other than status, and it is called from CampaignService.update before any field is applied — so a direct PATCH bypassing the disabled UI is refused with 409, not accepted.
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:104` "CAMPAIGN_ACTIVE_NOT_EDITABLE"

### 19. CampaignTemplateService/CampaignTemplateController — do all template fields populate the new campaign's persisted row, or does the template only pre-fill the form?
The template only pre-fills the form, by design: there is no apply-template route and the template id is never sent to the create call. templateToInitialValues maps nine fields onto CampaignFormData, and anything the template carries beyond those nine (or that the mapper skips because the value is empty) never reaches the payload. Since the mapped values then flow through normal form state, fields the brand never touches are still submitted — the loss risk sits in the mapper, not at submit.
`src/pages/brand-new-campaign.tsx:24` "no apply-template REST route"
`src/pages/brand-new-campaign.tsx:28` "function templateToInitialValues"

### 20. CampaignController.list status filter — do all status values the frontend chips offer map to real backend enum values?
Yes. The filter is typed as CampaignStatus or the sentinel ALL, and the four concrete options rendered are ACTIVE, DRAFT, PAUSED and COMPLETED, every one of which is a real CampaignStatus constant. No chip sends a string the enum cannot parse, so none of them can return an empty list for that reason; the gap is the reverse — PENDING_APPROVAL and CANCELLED are reachable statuses with no chip to filter by.
`src/components/brand/campaigns/campaigns-list.tsx:233` "type StatusFilter = CampaignStatus"
`src/components/brand/campaigns/campaigns-list.tsx:634` "Completed</SelectItem>"

### 21. Is there a campaign status the state machine can enter that the status-badge switch has no case for?
Yes — PENDING_APPROVAL. It is a real CampaignStatus constant, but the status-to-stage map has no key for it in either casing, so statusToStage falls through to its default. The badge is not blank, though: the default is the draft stage, so a campaign awaiting approval renders with a Draft-coloured badge indistinguishable from an actual draft.
`src/lib/stage-colors.ts:113` "STATUS_TO_STAGE[status.toUpperCase()]"
`influora-api/src/main/java/com/influora/domain/enums/CampaignStatus.java:5` "PENDING_APPROVAL"

### 22. brand-campaign-tracking.tsx / CampaignTrackingController — is click/conversion tracking data written by a real webhook/pixel path?
Yes, both stages are server-written. Clicks land through CampaignLinkService.recordClick from the public redirect, and redemptions/conversions arrive at the redemption webhook endpoint, which rejects any payload whose HMAC signature is missing or does not verify against the resolved workspace's secret. The page itself is explicit that it renders only the two funnel stages the API actually provides.
`influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java:264` "public void recordClick"
`src/pages/brand-campaign-tracking.tsx:32` "revenue and coupon-redemption totals"

### 23. DeliverableMetricService.getCampaignAnalytics — does brand-campaign-detail.tsx display the creator-reported provenance, or present self-reported numbers as verified?
The provenance is displayed, and it is finer-grained than a static banner. The aggregate block labels the numbers as creator-reported and not platform-verified whenever any row is self-reported, the deliverables tab carries per-row provenance driven by the real DeliverableMetric source, and the reported-versus-total counter is shown so partial coverage is visible rather than implied.
`src/pages/brand-campaign-detail.tsx:1780` "Creator-reported, not platform-verified"
`influora-api/src/main/java/com/influora/service/DeliverableMetricService.java:147` "waiting for creator to report performance"

### 24. When a campaign's budget is exhausted, does any backend transition move it to completed/budget-exhausted?
No. The only writer of campaign status is CampaignService.update taking the status off a client PATCH; neither EscrowService, PayoutService nor any job references CampaignStatus at all, and there is no budget-exhausted constant in the enum to move to. A fully-paid-out campaign stays ACTIVE until a human pauses or completes it, and no server-side signal marks that spend is done.
`influora-api/src/main/java/com/influora/service/CampaignService.java:243` "req.status() != null ? req.status()"

### 25. CreatorController.search — do all the filter parameters brand-discover.tsx sends actually get applied server-side?
All of them are applied. The page sends city as a comma-joined list, verticals, min/max followers and min/max engagement rate, and search builds a JPA Specification combining nameSearch, singleCity, followersBetween, engagementBetween, verifiedOnly, rateOverlap, hasPlatforms, hasCategories and hasLanguages. The multi-city case is handled rather than silently mismatched: singleCity splits on a comma before delegating to cityIn, so a two-city selection is a real IN predicate.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:177` "CreatorProfileSpecifications.engagementBetween(minEngagementRate, maxEngagementRate)"
`influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java:105` "city.contains("

### 26. CreatorController.save — is the shortlist state persisted per-workspace, or per-user so a teammate cannot see it?
Per-workspace. toggleSaved resolves the caller's workspace and looks the row up by workspace id plus creator profile id, and the row it writes is keyed by workspace id — the acting user's id is never stored on it. Any teammate in the same workspace therefore sees the same shortlist, and the search mapper resolves the saved flag from the same workspace-keyed lookup.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:437` "findByWorkspaceIdAndCreatorProfileId(workspace.getId(), profile.getId())"

### 27. CreatorController.suggestions — does this endpoint receive the campaign's actual targeting criteria, or a generic body making suggestions identical for every campaign?
It receives the real criteria. The campaign wizard fires it at the Review step with the campaign's own objectives as campaignGoals, a description derived from the targetAudience block, the budget maximum and the selected platforms, and suggest infers categories from those goals and audience before filtering candidates by category, platform and rate overlap. Suggestions therefore differ per campaign, though the fallback branch drops the platform and rate filters when the first query returns nothing.
`src/components/brand/campaigns/campaign-form.tsx:242` "campaignGoals: formData.objectives.join"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:379` "inferCategories(request.campaignGoals(), request.targetAudience())"

### 28. CreatorDiscoveryFacetsCache — when a creator's profile data changes, does the facet cache invalidate?
No. The cache is purely time-based: a single Cacheable entry under a fixed literal key with a five-minute Redis TTL, and a repo-wide search finds no CacheEvict or CachePut against that cache name anywhere. A follower-count or category change is invisible to the sidebar counts for up to five minutes. The blast radius is limited, though — only the facet counts are cached; the result list itself is re-queried live on every search, so filters do not surface stale creators, only stale counts.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryFacetsCache.java:61` "@Cacheable(cacheNames = RedisCacheConfig.DISCOVERY_FACETS_CACHE"

### 29. CreatorController.similar — is similarity computed from real profile/engagement data, or a fixed/random fallback?
It is computed from real data, with no random or hardcoded fallback. getSimilar reads the source creator's own follower count to build a 0.6x–1.4x band, excludes the source, pulls candidates ordered by real engagement rate, then ranks them with scoreSimilarity against the source's stored categories. When the band matches nobody the list simply comes back short or empty rather than being padded with filler.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:346` "source.getTotalFollowers() * 0.6"

### 30. brand-creator-profile.tsx — does every stat come from a field the backend actually returns, or is at least one number fabricated client-side?
Every displayed number is either a real response field or an explicit null. The mapper takes totalFollowers and engagementRate straight off the row, and for the fields the DTO genuinely lacks — per-post average likes, comments and views, review count, website, completion rate, on-time delivery, repeat clients — it sets null with a comment saying so, and the UI renders an em dash. The prior defect (hardcoded zeros for completed campaigns and rating) was fixed by adding the real fields to the DTO.
`src/pages/brand-creator-profile.tsx:345` "totalFollowers: row.totalFollowers"
`src/pages/brand-creator-profile.tsx:349` "avgLikes: null"

### 31. CreatorController.featured — is featured driven by a real curation flag, or a static mock list?
It is driven by a real curated table first: getFeatured queries FeaturedCreator rows that are active as of now, optionally filtered by category, and only when that query returns nothing does it fall back to buildAlgorithmicFeatured. That fallback is computed, not mocked — rising stars by follower band ordered by engagement, editors picks by the verified flag, a fitness/health category slice, and top creators by follower count. Neither branch varies with the requesting brand's industry or campaign context.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:306` "featuredCreatorRepository.findActiveFeatured"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:310` "buildAlgorithmicFeatured"

### 32. When a brand's plan has a tracked-creator limit, is that limit checked before a creator is saved/shortlisted?
It is not. toggleSaved resolves the workspace, checks the profile is discoverable, and writes the row — it never touches UsageCounterService, Plan or any limit. The TRACKED_CREATOR metric appears in exactly two places in the whole backend: the enum constant, and the read in getUsage. So the usage meter on the billing page reports a number that nothing ever increments and that blocks nothing.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:432` "public SaveResponse toggleSaved"
`influora-api/src/main/java/com/influora/web/BillingController.java:118` "UsageMetric.TRACKED_CREATOR"

### 33. CreatorController.invite — does every field the invite modal collects reach CreatorDiscoveryService.invite, or are fields silently dropped?
InviteRequest carries only campaignId and an optional message — it has no rate, deadline, usage-rights or deliverables field at all. The modal collects all of those, but it does not send them here: it branches, and when the brand priced the offer it calls POST /deals instead, which does carry amount, deliverables, deadline and usage rights. The unpriced branch reaches invite and legitimately has no terms to drop; the historical bug was that this branch always fired and discarded the collected terms, and that is fixed.
`influora-api/src/main/java/com/influora/web/dto/creator/CreatorDtos.java:61` "record InviteRequest(@NotBlank String campaignId, String message)"
`src/components/brand/discover/creator-discovery.tsx:540` "no terms, no notification"

### 34. Once a brand sends an invite via CreatorController.invite, does the creator actually receive a notification/inbox entry?
There is a discovery path, but no notification. invite writes the Collaboration row and then records the invitation on the deal-room timeline as a system DealMessage plus, optionally, the brand's note as a brand-authored message — so the creator sees it when they open the deal room or applications list. No event is published and no notification row is created anywhere in CreatorDiscoveryService, which is exactly what the frontend comment on the branch states, in contrast to the priced POST /deals path that fires ProposalSentEvent.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:546` "Brand invited this creator to the campaign"
`influora-api/src/main/java/com/influora/service/DealService.java:309` "eventPublisher.publishEvent"

### 35. DealController.create — is a deal proposal's price re-validated against the campaign's persisted budget server-side?
Yes, in two layers. validateProposalAmount rejects a non-positive amount, an amount above the campaign's stored budget maximum and one below its minimum, so a compromised client cannot propose over budget. Beyond that, F-0399 added a campaign-wide commitment check at accept time, because per-offer ceilings alone let N separate offers each sit under the maximum while jointly committing many times the campaign budget.
`influora-api/src/main/java/com/influora/service/DealService.java:1523` "amount.compareTo(campaign.getBudgetMax()) > 0"
`influora-api/src/main/java/com/influora/service/DealService.java:1081` "commitment happens at ACCEPT"

### 36. accept and reject take an optional Idempotency-Key — if the header is omitted, can a double-click accept the same deal twice?
No. When the header is absent, resolveIdempotencyKey substitutes a deterministic fallback built from the operation and the deal id, and the call still runs through idempotencyService.executeOnce under the acting party's scope. A second concurrent or repeat click raises AlreadyInProgress or AlreadyCompleted, which is caught and answered with the refreshed deal — so the downstream effects run exactly once whether or not the client sends a key.
`influora-api/src/main/java/com/influora/service/DealService.java:342` "deal-accept:"
`influora-api/src/main/java/com/influora/service/DealService.java:2180` "if (header != null && !header.isBlank())"

### 37. DealController.counter — does the deal transition to a state that blocks the creator from accepting the pre-counter terms?
The pre-counter terms become unreachable, but not through the status. doCounter overwrites the agreed rate, moves the deal to IN_NEGOTIATION and then settles the superseded proposal card as countered so its buttons go inert. The load-bearing part is that accept carries no proposal id at all — it always accepts whatever the current agreed rate is — so even a creator clicking a stale card agrees to the countered amount rather than the original one, and doCounter and doAccept both serialize through the same row.
`influora-api/src/main/java/com/influora/service/DealService.java:1256` "transitionTo(CollaborationStatus.IN_NEGOTIATION)"
`influora-api/src/main/java/com/influora/service/DealService.java:1263` "carries no proposal"

### 38. DealService.reject accepts an optional RejectRequest body — is the rejection reason persisted and shown back to the creator?
Yes. The reason is defaulted when absent, sanitized, and written into the deal thread as a system message prefixed with the actor label, then published to the live stream so the other party sees it without a refresh. It is stored as a DealMessage rather than as a column on the collaboration row, which means it is visible in the conversation but is not queryable as a structured rejection-reason field.
`influora-api/src/main/java/com/influora/web/dto/deal/DealDtos.java:112` "record RejectRequest"
`influora-api/src/main/java/com/influora/service/DealService.java:444` "appendSystemMessage(collaboration.getId(), actorLabel"

### 39. The deal message SSE stream replays missed messages via Last-Event-ID — if the client never sets it, are gap messages lost until a manual refresh?
The client never sets it: the backend reads and honours the header, but the frontend transport is fetch-based SSE that sends no Last-Event-ID, and api.ts says so plainly. The gap is closed a different way — the stream helper exposes onReconnect precisely so the room refetches the thread, and brand-chat additionally resyncs on visibilitychange. A tab closed and reopened remounts and calls GET /deals/{dealId}/messages from scratch, so the messages are not lost from the visible thread; they are recovered by refetch, not by replay.
`src/lib/api.ts:2283` "The stream carries no"
`influora-api/src/main/java/com/influora/web/DealController.java:145` "Last-Event-ID"

### 40. DealController.sendMessage — is there a server-side character/attachment limit matching what the composer enforces client-side?
The premise is inverted. The server enforces a hard limit — content is NotBlank with a 5000-character maximum — while the composer's Textarea carries no maxLength at all, so the client is the looser of the two and an over-long message is rejected by bean validation rather than prevented in the UI. Attachments are moot: the paperclip control is deliberately inert and labelled as not available, so there is no attachment upload path to size-limit.
`influora-api/src/main/java/com/influora/web/dto/deal/DealDtos.java:126` "@NotBlank @Size(max = 5000) String content"
`src/pages/brand-messages.tsx:1006` "Attach file — not available yet"

### 41. DealController.markRead — does marking a deal's messages read update the unread badge count returned elsewhere?
For the deal surfaces, yes and by construction: markRead appends the caller's user id to each message's readBy list, and the unread count on DealResponse is computed by counting messages whose readBy does not contain that same user id — one source of truth, no drift. The notification bell is a genuinely separate system, counting Notification rows via its own unread total, so those two numbers are independent by design and marking a thread read does not decrement the bell.
`influora-api/src/main/java/com/influora/service/DealService.java:1934` "parseReadBy(m.getReadByJson()).contains(userId)"
`src/hooks/useNotifications.ts:163` "serverUnreadCount ??"

### 42. DealController.listDeliverables — is deliverable data now actually written by the creator-submission path?
Yes, the persistence gap named in the comment was on the read side, not the write side. The creator submission path saves Deliverable rows in several places including submitForReview, and listDeliverables reads exactly those rows for the collaboration, ordered by slot index, mapping them through the same toListItem the creator side uses. So a deal that visibly shows submitted deliverables has rows for this endpoint to return.
`influora-api/src/main/java/com/influora/service/DealService.java:1046` "findByCollaborationIdOrderBySlotIndexAsc"
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:360` "deliverableRepository.save(deliverable)"

### 43. Does the real brand-campaign-detail.tsx wire Generate Contract to contractsGenerate with the actual deal's real id?
It does. handleGenerateContract reads the deal row the drawer was opened for and passes its id as collaborationId, which is the same identity DealResponse returns, alongside the drafted milestones. There is a guard that returns early when no target is held, so an empty id cannot be sent, and the page refetches afterwards so the row's real contract id replaces the button.
`src/pages/brand-campaign-detail.tsx:832` "collaborationId: target.id"

### 44. Is there a deal status reachable in the backend state machine that no brand-side code path can ever produce?
The two named in the question do not exist — CollaborationStatus has no EXPIRED or WITHDRAWN; withdrawal lands on CANCELLED. The status that is genuinely dead is SHORTLISTED: it is a declared constant and is read in several allow-lists and label maps, but no transition anywhere in the backend ever writes it, so no button on either side can produce it.
`influora-api/src/main/java/com/influora/domain/enums/CollaborationStatus.java:6` "SHORTLISTED"
`influora-api/src/main/java/com/influora/service/CollaborationLifecycleService.java:62` "CollaborationStatus.SHORTLISTED"

### 45. ContractController.generate — does contract generation pull the deal's actual agreed price at generation time, or can terms be stale after a counter-offer?
Milestone amounts come from the request, but they are bound to the deal's live state at generation time: each must be positive, and their sum is rejected when it exceeds the collaboration's current agreed rate — the same field a counter-offer overwrites. So post-counter stale terms above the new price are refused. The binding is one-directional, though: a total lower than the agreed rate is deliberately allowed, so a contract can under-state the negotiated figure.
`influora-api/src/main/java/com/influora/service/ContractService.java:277` "totalAmount.compareTo(collaboration.getAgreedRate()) > 0"

### 46. Is there any remaining code path that lets a brand-authenticated principal record a signature attributed to the creator?
No. recordSignature now hard-codes the brand role, and an explicit role of CREATOR is rejected with 403 CREATOR_SIGNATURE_NOT_RELAYABLE rather than being silently downgraded; anything other than BRAND is a 400. The only other writer of a creator signature is recordSignatureForCreator, reachable solely from the creator branch of the sign endpoint, and no admin controller or Meera tool references either method — the tool executors touch contracts only in unrelated comments.
`influora-api/src/main/java/com/influora/service/ContractService.java:613` "CREATOR_SIGNATURE_NOT_RELAYABLE"

### 47. ContractSignRequest.name() — is the typed legal signer name rendered into the generated PDF, or does the PDF show a placeholder/blank name?
It is persisted but never rendered. The name reaches the entity and is stored on brandSignerName / creatorSignerName, yet ContractPdfService contains no reference to either getter: its Signatures section labels each line with the brandName and creatorName the caller resolved (workspace and creator display names) and prints only a timestamp or the not-yet-signed placeholder. The legally-typed name the UI collects therefore never appears on the document.
`influora-api/src/main/java/com/influora/service/ContractPdfService.java:183` "Brand (" 
`influora-api/src/main/java/com/influora/domain/entity/Contract.java:189` "this.brandSignerName = signerName"

### 48. ContractController.pdfDownloadUrl 404s with CONTRACT_PDF_NOT_READY until both parties sign — does the contracts page handle that clearly?
It handles it explicitly. handleDownloadPDF awaits the call, refuses to open a window on an empty URL, and in the catch branches on the error code to say the PDF is generated once both parties have signed, falling back to the server's own message otherwise. There is no blank href or unhandled rejection — the link is a button that reports the awaiting-signature state as a toast.
`src/components/brand/contracts/contracts-and-deliverables.tsx:915` "err.code === 'CONTRACT_PDF_NOT_READY'"

### 49. ContractController.list filters by dealId — does listForBrand scope by workspace ownership, or return contracts across workspaces?
It scopes by workspace. listForBrand first asserts the caller is an active member of that workspace, then queries by workspace id and collaboration id together when a dealId is supplied, or by workspace id alone when it is not. Passing another workspace's dealId therefore returns an empty list rather than that workspace's contracts. This is the C-1 fix — the parameter used to be accepted and silently dropped on the brand branch.
`influora-api/src/main/java/com/influora/service/ContractService.java:956` "findByWorkspaceIdAndCollaborationId(workspaceId, dealId)"

### 50. Once a contract reaches ACTIVE, does the backend prevent a second contract for the same deal?
Yes, and it is race-safe. generate takes a pessimistic write lock on the collaboration row, then refuses when any non-CANCELLED contract already exists for it, so a signed or ACTIVE contract blocks a second generate and two concurrent calls serialize on the lock rather than both inserting. A CANCELLED contract deliberately does not block a fresh one, which is the intended re-negotiation escape hatch.
`influora-api/src/main/java/com/influora/service/ContractService.java:229` "existsByCollaborationIdAndStatusNot"
`influora-api/src/main/java/com/influora/service/ContractService.java:232` "CONTRACT_ALREADY_EXISTS"

### 51. Does the downloaded PDF match the on-screen summary, or can the two diverge if the campaign/deal was edited after generation?
The money cannot diverge: the PDF is rendered once and stored in object storage, and the download endpoint only presigns that existing key — it never re-renders — while the on-screen summary reads the same immutable Contract row and its milestones, which have no update route. What can diverge is the descriptive text: party names and campaign title are baked into the PDF at render time and re-resolved live for the screen, so a later workspace rename or campaign retitle shows differently in the two places.
`influora-api/src/main/java/com/influora/service/ContractService.java:1031` "r2StorageService.presignGet(contract.getPdfR2Key())"

### 52. Does the real UI show the contract-generation success toast only after the backend call resolves?
Only after. handleGenerateContract awaits the generate call and the toast is the next statement in the try block, so a rejected POST skips it entirely and lands in the catch, which distinguishes a 403 from other failures and says so. It then triggers a refetch so the real contract id replaces the button — nothing is rendered optimistically ahead of the server's confirmation.
`src/pages/brand-campaign-detail.tsx:840` "title: 'Contract sent'"

### 53. EscrowController.fund re-derives the amount server-side — does the wallet fund-confirmation control display that same derived amount, or a client-computed estimate?
Before the click it shows a client estimate: the wallet page passes the campaign's budget.max off the campaigns list as displayAmount, and the button label renders that hint. Once initiateFund returns, serverAmount takes over the label and the only copy that claims money is secured is gated on the server-confirmed FUNDED status, so the estimate can never be what is debited — but for a milestone-scoped hold the pre-click figure is the whole campaign budget and can legitimately differ from what the server derives.
`src/pages/brand-wallet.tsx:1479` "?.budget?.max"
`src/components/feature/meera/FundEscrowButton.tsx:288` "const amount = serverAmount ?? displayAmount"

### 54. Does the fund button generate a fresh Idempotency-Key per click and reuse it correctly on retry, or can a network retry double-fund?
It is correct. The hook mints a key only on a genuine idle-to-initiating transition, guarded by a null check on the ref, so the automatic retry after an inline top-up reuses the original key; reset clears both refs so a deliberate fresh attempt gets a new one. A second click cannot even reach the handler because the button is disabled for every in-flight status, and the server independently replays an existing hold on a repeated key.
`src/hooks/useEscrowFund.ts:203` "if (!idempotencyKeyRef.current)"
`src/components/feature/meera/FundEscrowButton.tsx:300` "disabled={paymentsBlocked || disabled || isLoading || isSuccess}"

### 55. Does the release action know whether to send milestoneId or escrowHoldId for a milestone-less Meera hold?
The question rests on a choice the frontend does not actually have. The only release caller in the tree is the deal room payments tab, and it goes through an api wrapper whose body is hardcoded to milestoneId with no escrowHoldId parameter at all. So the both-or-neither 400 is unreachable, but the consequence is worse: a Meera-funded campaign-level hold, which by construction has no PaymentMilestone row, has no brand-side control anywhere that can release it.
`src/lib/api.ts:3471` "body: { milestoneId }"
`src/components/brand/deal-room/deal-payments-tab.tsx:110` "api.payments.releasePayout(milestoneId)"

### 56. Is the documented no-frontend-caller policy on the refund route actually true?
It is true. A search for the route string, and for any wrapper naming it, across the whole frontend and the admin console returns zero call sites — the only occurrences of the path are the controller javadoc itself and the deal room comment describing it. The brand-reachable refund really does run through dispute resolution into adminRefundForDispute, exactly as the policy text says.
`influora-api/src/main/java/com/influora/web/EscrowController.java:129` "DELIBERATELY HAS NO FRONTEND CALLER"

### 57. Does the wallet escrow-items panel render only fields the escrow status response actually carries?
Every rendered value maps to a real response field — hold id, campaign id, milestone id, amount, currency, status and fundedAt — and nothing is fabricated client-side. The cost is the opposite defect: because the DTO carries ids and no display names, the panel prints the raw campaign identifier as the row title and the raw milestone identifier beneath it, so a brand sees an opaque ULID where the mock dataset showed a campaign name and creator.
`src/pages/brand-wallet.tsx:1546` "Campaign {row.campaignId}"
`influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java:200` "String escrowHoldId"

### 58. Does GST invoice generation happen inside the release transaction, or can a release succeed while its invoice silently fails?
It is deliberately outside the release transaction — createAtRelease runs REQUIRES_NEW and the call is wrapped so no invoicing fault can roll back money that already moved. The old backfill gap is closed on the backend: a failure now writes a durable failure-marker row in the same transaction as the release, which a retry job picks up. Nothing on the wallet page surfaces that marker, so the gap is now operationally recoverable but still invisible to the brand.
`influora-api/src/main/java/com/influora/service/EscrowService.java:1216` "campaignServiceInvoiceService.createAtRelease"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1230` "recordInvoiceCreationFailure"

### 59. Does the brand UI show a queued payout's real settlement status, or just report paid on a successful queue call?
Neither — the premise assumes a caller that does not exist. The payout route is live on the controller, but no file under the frontend references it, so no brand screen ever queues a payout and none can display its settlement state. What the deal room does show after a release is a toast asserting the funds are on their way to the creator, which is the ledger movement, not a bank settlement.
`influora-api/src/main/java/com/influora/web/EscrowController.java:150` "ApiResponse<PayoutResponse> queuePayout"

### 60. On release, does money actually leave the platform account for the creator's bank, or is it only a ledger row?
Only a ledger row. The single registered escrow backend posts brand wallet to platform clearing wallet to creator wallet through the internal ledger service, with the platform fee split deducted in between; no gateway transfer is invoked anywhere in the release path. The real bank leg lives in the separate payout service against RazorpayX, and per the previous answer nothing in the brand product calls it, so release ends with the creator's Influora wallet credited and no money off the platform.
`influora-api/src/main/java/com/influora/service/escrow/LedgerEscrowBackend.java:15` "platform clearing wallet"
`influora-api/src/main/java/com/influora/service/escrow/LedgerEscrowBackend.java:32` "public class LedgerEscrowBackend implements EscrowBackend"

### 61. Is TDS computed and withheld anywhere in the escrow-release or payout path?
No. The only occurrence of the term anywhere in the service layer is a comment on the wallet top-up path noting that PAN and GSTIN are captured for later reconciliation; there is no withholding calculation, no tax ledger leg and no tax line in the release or payout amount derivation. The creator is credited the gross milestone amount less only the platform commission split, so the released figure never has tax subtracted.
`influora-api/src/main/java/com/influora/service/WalletTopUpService.java:144` "capture PAN/GSTIN for later TDS reconciliation"

### 62. Can the brand-visible escrow status diverge from the gateway-side payment state, and is reconciliation visible to the frontend?
It cannot diverge in the way the question fears, because funding no longer touches the gateway at all: the method refuses unless the workspace wallet balance already covers the amount and then applies the funding straight from that balance, so a FUNDED hold always sits on money the wallet ledger already holds. The gateway risk moved upstream to the top-up leg and downstream to payouts, and the payout reconciliation service has no brand-facing surface — nothing in the escrow status response or the wallet page exposes a reconciliation state.
`influora-api/src/main/java/com/influora/service/EscrowService.java:250` "if (wallet.getBalance().compareTo(amount) < 0)"
`influora-api/src/main/java/com/influora/service/EscrowService.java:305` "applyFunding(hold, null)"

### 63. For a multi-milestone campaign, can a second fund call re-derive an amount that double-counts an already-funded milestone?
No. The derivation is strictly per-target: given a milestoneId it returns that one milestone's persisted amount and never sums siblings, so a second call for a different milestone charges only that milestone. The campaign-level branch, which does use the whole budget, is additionally refused outright when the campaign has any PENDING milestone, so the one shape that could double-count is rejected with a conflict rather than derived.
`influora-api/src/main/java/com/influora/service/EscrowService.java:330` "return milestone.getAmount()"
`influora-api/src/main/java/com/influora/service/EscrowService.java:227` "CAMPAIGN_HAS_UNFUNDED_MILESTONES"

### 64. After approval, does escrow release for that deliverable's milestone happen automatically?
It is attempted automatically, in the same transaction as the status flip, and a genuine failure rolls the approval back. But the attempt can legitimately no-op — an unfunded milestone, an unmet release condition, a dispute freeze — and the response therefore carries a released flag plus a held-reason code. The brand is told: the viewer branches on that flag and shows a destructive toast naming the reason rather than a success message, so approval never silently reads as payment.
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:160` "escrowService.tryReleaseOnApproval(workspace.getId(), deliverable.getMilestoneId())"
`src/components/brand/deliverables/DeliverableViewer.tsx:277` "Approved — but payment was NOT released"

### 65. Is the revision feedback text actually delivered to the creator's deliverable view?
Yes. The brand's text is sanitized and written to the deliverable's review-notes column by applyRevision, the creator status DTO carries that same column back, and the creator chat surface reads it off the status response to render the requested change. The wiring is end to end, not a 200 into a void.
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:295` "deliverable.applyRevision(sanitizedFeedback)"
`src/pages/creator-chat.tsx:1586` "status.reviewNotes?.trim()"

### 66. Does anything on the brand side actually call the reject route today?
The premise is out of date. The deliverable viewer has a live reject handler that posts to the route through an api client method added specifically to close that orphan, alongside approve and revise. The route no longer has zero callers.
`src/components/brand/deliverables/DeliverableViewer.tsx:334` "api.deliverables.reject(deliverable.id, feedback)"

### 67. Can a deliverable be approved twice?
No. Every review action is gated on a helper that admits only the SUBMITTED and RESUBMITTED states, so an already-APPROVED deliverable fails the check and the second attempt returns a 409 conflict before any side effect runs. The same gate covers revise and reject, so none of the three can be replayed onto a resolved deliverable.
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:388` "status == DeliverableStatus.SUBMITTED || status == DeliverableStatus.RESUBMITTED"
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:115` "Cannot approve deliverable in current state"

### 68. Does the UI make the safety verdict's non-blocking nature clear?
Yes, explicitly and in words. The card labels itself advisory in the header and prints a line under the verdict stating outright that it does not affect the brand's ability to approve or request changes, and no approve or reject control anywhere reads the verdict as a precondition. The presentation matches the service contract rather than implying a gate.
`src/components/brand/deliverables/DeliverableSafetyReviewCard.tsx:141` "Advisory only — this does not affect your ability to approve or request changes."

### 69. Is there a server-side cap on revision rounds?
There is none. The entity increments the revision counter unconditionally on every request, and the only gate on the revise path is the same review-state helper, which is true for both SUBMITTED and RESUBMITTED — so revise, resubmit, revise loops without limit. The creator surfaces were deliberately changed to stop claiming a maximum, because the number they used to show was a display constant nothing enforced.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:1133` "this platform has NO revision cap, so the wire carries none."
`influora-api/src/main/java/com/influora/domain/entity/Deliverable.java:254` "this.revisionCount = this.revisionCount + 1"

### 70. Does the detail response include retrievable asset links for what the creator submitted?
Yes. The detail builder maps the stored files list into file details, and each stored value is run through a resolver that converts an object key into a time-limited presigned GET, falling back to the stored value verbatim when object storage is unavailable. A deliverable with no stored files yields an empty list rather than a broken link, so the brand sees nothing to review only when nothing was actually attached.
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:400` "files.stream().map(this::toFileDetail).toList()"
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:425` "private String resolveDownloadUrl(String stored)"

### 71. Does rejecting a deliverable block its milestone from being released?
The two systems are linked, and rejection blocks release. Rejection flips the deliverable to REJECTED and hands the collaboration to the lifecycle recompute, and the release gate independently requires every deliverable on the collaboration to sit in the satisfying set for the milestone's release condition — a set containing only approved, posted, metrics-reported and verified. REJECTED is in none of them, so a subsequent release call fails the condition check rather than paying out.
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:345` "collaborationLifecycleService.onDeliverableReviewed"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1362` "allMatch(satisfying::contains)"

### 72. Does anything actually consume the shipment-created event, or is it published into a void?
It has a real consumer. The notification listener has an async after-commit handler for the event that fires a creator-facing notification titled for a shipped product, with the tracking URL in the body and a deep link to the creator shipments route, plus a templated email. The publish itself is wrapped so a notification failure cannot undo the shipped status, but the creator does not have to poll to learn about it.
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:216` "public void on(ShipmentCreatedEvent event)"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:219` "Product shipped!"

### 73. Does the brand UI distinguish the synthetic awaiting-address response from a real persisted status?
Partly, and the part that matters is handled. The brand chat treats the synthetic value as no shipment at all — it nulls the stored address so the ship control is gated on a real address being on file rather than on a truthy placeholder, which is exactly the trap the question describes. What it does not do is separate the two pre-ship states in its display mapper, where the synthetic value and the persisted address-provided value both collapse to the same badge.
`src/pages/brand-chat.tsx:1077` "setShippingAddress(null)"
`src/pages/brand-chat.tsx:604` "function mapShipmentApiStatusToUiStatus(status: ShipmentApiStatus): ShipmentStatus"

### 74. Does creator confirmation of receipt trigger a downstream state change, or just sit in the row?
It has a consumer, and a blocking one. Confirmation writes the condition and note onto the shipment row, appends a thread message both parties see, and publishes a received event; on the creator side the deliverable submit control stays disabled with an explicit tooltip until the shipment reads as received, so the confirmation is what unblocks submission rather than a dead field.
`influora-api/src/main/java/com/influora/service/ShipmentService.java:228` "shipment.confirmReceipt(condition, note, Instant.now())"
`src/pages/creator-chat.tsx:2087` "Confirm shipment receipt before submitting"

### 75. Is there a brand-side control to mark a shipment lost or undelivered?
No, and the state machine has no state for it. The enum runs awaiting-address, address-provided, shipped, received, damaged, and the only non-happy branch is damaged, which only the creator can set through the receipt-confirmation condition. A brand whose parcel never arrives has no transition to record it and no route to call, so the flow only moves forward.
`influora-api/src/main/java/com/influora/domain/enums/ShipmentStatus.java:19` "RECEIVED,"
`influora-api/src/main/java/com/influora/service/ShipmentService.java:228` "shipment.confirmReceipt(condition, note, Instant.now())"

### 76. Does the brand disputes page expose any action button, or is it purely read-only?
Purely read-only, and honestly so. The whole page has exactly two controls: a retry button that re-runs the list fetch, and a per-card link into the deal room. There is no respond, add-evidence or resolve control anywhere on it — no dead handler and no button pointed at a route brand principals cannot call. The actual opening of a dispute lives in the deal room, not here.
`src/pages/brand-disputes.tsx:83` "onClick={() => void refresh()}"
`src/pages/brand-disputes.tsx:147` "View deal room"

### 77. Once a dispute is opened, is the associated hold actually frozen against release?
Yes. The release path calls the dispute guard before any state check or money movement, and that guard refuses if the collaboration is in the disputed status or if any dispute row for it sits in an active status, throwing a 409 that names the reason. The same guard is applied on the refund path, so an open dispute freezes movement in both directions rather than only blocking refunds.
`influora-api/src/main/java/com/influora/service/EscrowService.java:815` "assertEscrowNotBlockedByDispute(collaboration)"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1600` "Secured funds cannot be released or refunded while a dispute is active on this collaboration"

### 78. Does every value in the frontend dispute lifecycle type correspond to a status the backend can set?
It is an exact one-to-one match. The frontend union lists open, under-review and the three resolved variants, and the backend enum declares the same five constants in the same order with helpers partitioning them into active and resolved. There is no frontend-only label and no backend status the frontend cannot name.
`src/lib/api.ts:5590` "RESOLVED_BRAND"
`influora-api/src/main/java/com/influora/domain/enums/DisputeStatus.java:9` "RESOLVED_SPLIT;"

### 79. Does a dispute-driven refund show as a distinct line in the brand's transaction history?
Yes, distinctly. The refund posting is written with its own dedicated ledger transaction type rather than a generic debit, and the wallet page's mapper has an explicit case translating that type into the refund display kind, which drives its own icon, colour and sign in the transaction list. The money does not vanish from the visible ledger, and it is not disguised as an ordinary debit.
`influora-api/src/main/java/com/influora/service/escrow/LedgerEscrowBackend.java:112` "WalletTransactionType.ESCROW_REFUND"
`src/pages/brand-wallet.tsx:291` "case 'ESCROW_REFUND':"

### 80. Is there a time limit on opening a dispute, and does the UI show the window before the attempt?
There is no clock-based limit. The constraint is state-based: the open path refuses unless the collaboration still has funded, unreleased escrow, which in practice means the window closes at release rather than after a fixed number of days. And the brand does not discover it through a rejection — the deal room only offers the dispute item when escrow is funded, and otherwise prints an explanatory line in its place saying a dispute can only be raised once funds are secured.
`influora-api/src/main/java/com/influora/service/DisputeService.java:114` "escrowService.hasFundedUnreleasedEscrow(collaboration.getId())"
`src/pages/brand-chat.tsx:2152` "A dispute can only be raised once the funds are secured"

### 81. For a terminal dispute, does the UI differentiate which party the resolution favoured?
In words yes, in colour no. The card's label table has a separate string for each of the three resolved variants, so the text reads as brand favour, creator favour or split. But the badge class comes from a shared status-to-stage map that has no key for any of the resolved dispute values, so all three fall through to the default draft styling — the same neutral badge an unmapped status gets, giving three different outcomes one identical colour.
`src/pages/brand-disputes.tsx:17` "Resolved — creator's favor"
`src/lib/stage-colors.ts:113` "STATUS_TO_STAGE[status.toUpperCase()] ?? 'draft'"

### 82. Is the GST split computed from the brand workspace's real registered GSTIN, or a fixed default?
It is computed from the real pair. The utility compares the two-digit state code of the supplier and customer GSTINs and splits into CGST plus SGST only when they match, and the billing caller passes the company GSTIN as supplier and the workspace's own stored GSTIN as customer. The caveat is the fallback: when either GSTIN is missing or malformed no intra-state match can be established and the whole tax is treated as IGST — the failure mode a placeholder company GSTIN once caused on every invoice, which is why a boot-time validator now refuses to start outside dev with the placeholder in place.
`influora-api/src/main/java/com/influora/service/GstSplitUtil.java:30` "boolean intraState = supplierState != null && supplierState.equals(customerState)"
`influora-api/src/main/java/com/influora/service/billing/InvoiceService.java:257` "GstSplitUtil.compute(companyTaxProperties.getGstin(), workspace.getGstin(), totalGst)"

### 83. Can an invoice be generated with a missing or placeholder HSN/SAC code?
No — the failure is loud rather than blank. The resolver looks the code up by its applies-to key and throws a 500 when no row is configured, and the invoice builder calls it inline while assembling the row, so an unconfigured code aborts invoice creation entirely instead of persisting a document with an empty field. The blast radius lands in the release path's catch, which records a retryable failure marker rather than shipping a non-compliant PDF.
`influora-api/src/main/java/com/influora/service/HsnSacCodeService.java:33` "HSN_SAC_CODE_NOT_CONFIGURED"
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:224` "hsnSacCodeService.resolveCode(HsnSacAppliesTo.CREATOR_SERVICE)"

### 84. Can a failed invoice generation consume a number and leave a silent gap in the series?
Not through a rollback. The number is drawn by row-locking the per-series, per-financial-year sequence and consuming the next value inside the caller's own transaction, which is the same transaction that persists the invoice row — so if that transaction rolls back, the consumed number rolls back with it. The documented atomicity contract is explicit that callers must number inside the same boundary as the posting and the row, precisely so a retry cannot double-issue a statutory number.
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:79` "int seq = sequence.consumeNext()"
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:25` "prevents a retry from double-issuing a statutory number"

### 85. Is the subscription invoice PDF an immutable stored copy, or regenerated per request?
Regenerated every time, and it can drift. The handler loads the invoice, then re-resolves the subscription, the plan and the workspace live and hands all four to the renderer, so nothing but the invoice row's own amounts is frozen. A workspace that renames itself, changes its registered GSTIN or address, or a plan whose display name changes, produces a materially different document on the second download of the same invoice id.
`influora-api/src/main/java/com/influora/service/billing/InvoiceService.java:128` "return invoicePdfService.render(invoice, subscription, plan, workspace)"

### 86. Are the platform's commission invoice and the creator-service invoice conflated in the billing UI?
No, they are three clearly separated cards. The billing settings page renders the subscription invoices, a card headed for creator service invoices describing them as what creators issued the brand, and a distinct card headed for platform commission invoices describing them as Influora's own commission with the applied fee percentage rendered per row. The brand can tell exactly what each document bills them for.
`src/pages/brand-billing-settings.tsx:143` "Creator Service Invoices"
`src/pages/brand-billing-settings.tsx:192` "Platform Commission Invoices"

### 87. Would a brand with a real invoice gap see anything hinting a document is missing?
No. The brand-facing read is a bare repository listing of the invoice rows that exist, ordered by issue date, with no join against the failure-marker table and no count reconciled against releases. Nothing under the web layer or the frontend references that marker table at all, so the retry job can be sitting on an unresolved failure while the brand simply sees one fewer row than they had releases, with no indicator anywhere.
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:575` "findByBrandWorkspaceIdOrderByIssuedAtDesc(brandWorkspaceId)"

### 88. Is the invoice line amount ever cross-checked against the ledger entry for the same event?
It is not, and the two figures are not even the same quantity by design. The invoice takes its gross amount straight off the escrow hold, while the ledger credit leg it references is the net figure after the creator-side commission split. The leg id is stored on the invoice, but its own javadoc says that is for traceability only — no code loads that transaction to compare amounts, so a divergence after any later adjustment would go unnoticed.
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:158` "BigDecimal grossAmount = hold.getAmount()"
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:148` "the creator-side credit leg id of the release posting — traceability only"

### 89. Is the tracked-creator usage counter ever incremented, or does it stay at zero forever?
It stays at zero forever. Across the whole backend the tracked-creator metric appears in exactly two places: its declaration in the metric enum, and the read in the usage endpoint. There is no increment call in the creator save handler, in the discovery service, or anywhere else, so the usage meter reports zero tracked creators no matter how many a brand shortlists — and the limit shown beside it can never be approached, let alone hit.
`influora-api/src/main/java/com/influora/domain/enums/UsageMetric.java:4` "TRACKED_CREATOR,"
`influora-api/src/main/java/com/influora/web/BillingController.java:118` "getUsageForCurrentPeriod(workspaceId, UsageMetric.TRACKED_CREATOR)"

### 90. Can a brand on a plan with exports disabled still call the export endpoint successfully?
No. The export route carries a plan-requirement annotation naming the export feature, and the interceptor that reads that annotation resolves the workspace's active plan and evaluates the export-enabled flag on it, rejecting the request before the handler runs. The flag shown in the usage summary and the flag the gate enforces are the same accessor on the same plan row.
`influora-api/src/main/java/com/influora/web/ReportExportController.java:36` "@RequiresPlan(feature = PlanFeature.EXPORT)"
`influora-api/src/main/java/com/influora/security/PlanGateInterceptor.java:47` "case EXPORT -> plan.isExportEnabled()"

### 91. Is the seat cap enforced on invite, or only displayed?
Enforced, and on both halves of the flow. The invite path calls the seat-limit check before creating the row, and the accept path calls it again so a stale invite cannot push a workspace over the cap after the fact. The check counts active members plus outstanding pending invites against the plan's seat limit and throws an upgrade-required error at the boundary, so pending invites cannot be used to overshoot.
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:168` "enforceSeatLimit(workspaceId)"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:420` "if (activeMembers + pendingInvites >= plan.getSeatLimit())"

### 92. Are AI credits actually decremented by the feature that consumes them?
Yes. The Meera session service charges a per-turn cost through the credit service on every send, keyed by the message id so a retried turn is not double-charged, and that call goes down to a conditional decrement on the credit row. The number the billing page shows is read straight off that same row rather than from the plan allotment, so the meter reflects real consumption.
`influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java:220` "creditService.tryConsumeForTurn(workspaceId, TURN_CREDIT_COST, messageId)"
`influora-api/src/main/java/com/influora/web/BillingController.java:127` "aiCreditsRemaining = aiCredit.getCreditsRemaining()"

### 93. Can a double-click on upgrade trigger the already-subscribed error, and does the UI surface it?
Within one tab it cannot: the button is disabled the moment the first click sets the busy flag, and the flag is only cleared in the failure branch because the success path navigates away to the hosted checkout. A genuine race across tabs is arbitrated server-side by the already-subscribed guard plus the idempotency reservation around the gateway call. If either does reject, the page renders the server's own error text in the toast rather than a generic failure.
`src/pages/brand-billing-settings.tsx:563` "disabled={checkoutBusy || !canManageBilling}"
`src/pages/brand-billing-settings.tsx:267` "err instanceof ApiError ? err.message"

### 94. After cancellation, does the workspace actually lose paid entitlements at period end?
Yes, through a two-step that closes. Cancel deliberately leaves the row active and only sets the cancel-at-period-end flag, so access continues for the paid period the brand already bought. The renewal job then routes exactly those rows to its cancellation branch once the period end has lapsed, finalizing them to cancelled — and plan resolution only honours a subscription whose status is active, falling back to Free otherwise, so entitlements drop the moment that flip happens.
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:356` "subscription.setCancelAtPeriodEnd(true)"
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:167` ".filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)"

### 95. Can any authenticated brand pull another brand's contracted creator's private metrics by id?
No, but the gate is not the one the question assumes. Authorization does not look for a collaboration at all — it requires an active, non-revoked Meta OAuth connection between the calling workspace and that creator profile, and throws a forbidden error naming the creator when none exists. A brand that never connected that creator, or that has since revoked, is refused regardless of any past deal, so the resolve-then-scope shape holds even though the predicate is the Meta link rather than a contract.
`influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java:67` "findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, creatorProfileId)"
`influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java:72` "This workspace is not authorized to view metrics for that creator"

### 96. Does the per-creator analytics page carry the creator-reported disclosure?
The premise does not apply to that page. Its numbers come from the creator-metrics and creator-scores hooks, which read the analytics routes gated on the Meta OAuth link above — platform-pulled Meta data, not the self-declared figures the campaign analytics javadoc warns about. The page correctly carries no self-reported caveat, and the disclosure that does exist lives where the self-reported numbers actually are, on the campaign detail analytics tab.
`src/pages/brand-creator-analytics.tsx:18` "import { useCreatorMetrics } from '@/hooks/analytics/useCreatorMetrics';"
`influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java:51` "has an active (non-revoked) Meta OAuth connection to"

### 97. When no metrics have been reported yet, does the panel say so or render an indistinguishable zero?
It says so. With no reported analytics the tab renders a dedicated dashed empty card headed that no analytics have been reported yet, explaining the numbers appear once collaborators submit. And inside the populated view the engagement rate is null-checked and falls back to an em dash rather than a numeral, so a genuinely absent value never masquerades as a reported zero.
`src/pages/brand-campaign-detail.tsx:1834` "No analytics reported yet"
`src/pages/brand-campaign-detail.tsx:1799` "completedAnalytics.derivedEngagementRate != null"

### 98. Does the notification bell still break on the envelope shape the controller javadoc warns about?
No — that javadoc note is stale. The hook no longer uses a raw fetch; it calls the shared notifications client, which goes through the request helper that unwraps the response envelope and then maps the inner payload into items plus an unread count. What remains genuinely mismatched is the mark-read route, which still returns an unenveloped body, so its unread count arrives undefined — and the hook is written knowing that, updating optimistically and letting the next poll supply the authoritative number.
`src/hooks/useNotifications.ts:182` "const { items, unreadCount: serverUnread } = await notificationsApi.list(role)"
`src/lib/api.ts:3574` "http.request<NotificationListWire>('GET', '/notifications', { role })"

### 99. Does toggling a notification preference off actually stop the emails for that event type?
For everything routed through the notification service, yes: it checks the global wildcard unsubscribe first, then the per-event-type row, before queueing anything. But at least one brand-relevant email skips that path entirely — the workspace member invite writes its own outbox row directly with its own template key and idempotency key, never consulting the preference repository, so a member who unsubscribed from that event type would still be emailed the invite.
`influora-api/src/main/java/com/influora/service/notification/NotificationService.java:177` "emailPreferenceRepository.findByUserIdAndEventType(userId, eventType)"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:466` "brand.workspace_invite"

### 100. Does the on-behalf token carry write scope for every action the Meera executors perform?
No, and deliberately not. The minted scope covers show creators, calculate budget, create campaign and get campaign performance — the two money tools, confirm launch and request payment, are excluded on purpose as defence in depth on the money path. The resolver enforces the claim per tool, so a brand-facing session offered those tools gets a scope-insufficient 403 mid-conversation; the gap is a pending security sign-off, not an oversight, but it is real from the user's point of view.
`influora-api/src/main/java/com/influora/service/meera/OnBehalfTokenService.java:69` "show_creators calculate_budget create_campaign get_campaign_performance"
`influora-api/src/main/java/com/influora/security/OnBehalfAuthResolver.java:185` "ON_BEHALF_SCOPE_INSUFFICIENT"
