# FEAT-B2 Answers — Campaigns, Deals, Deliverables, Discovery, Profiles, Reviews, Affiliate, Notifications, Analytics, Exports

Answered from the code by Priya Sharma (CTO), 2026-09-03. The feature docs in docs/docs/features are the register of what exists, not evidence of behaviour; where a doc claim contradicts the source, the source wins and the contradiction is named in place.

---

## Campaigns

### 1. WORKS?
Yes. The publish edge is a PATCH into CampaignService.update, which pessimistically locks the row, runs the workspace VERIFIED check, then calls the brand fee service inside the same transaction; that service debits the brand wallet into the platform revenue wallet and immediately mints the brand-leg commission invoice off the successful posting. The campaign is locked afterwards because the ACTIVE-aware editable guard rejects any patch that touches a non-status field. Creator visibility is real and query-level, not cosmetic: the browse specification requires ACTIVE and non-private. The one caveat is the fee base — it is budgetMax alone, so a campaign whose budget was never set fails with a budget-missing conflict rather than publishing free.
`influora-api/src/main/java/com/influora/service/CampaignService.java:348` "brandCampaignFeeService.chargeOnPublish"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:235` "createBrandLegAtPublish"
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:104` "CAMPAIGN_ACTIVE_NOT_EDITABLE"
`influora-api/src/main/java/com/influora/repository/CampaignSpecs.java:47` "CampaignStatus.ACTIVE"

### 2. HOW?
All four steps are inside one transaction and inside the lock. The update method is annotated transactional and its first act after resolving the workspace and role is a locked load through findByIdForUpdate, which is a PESSIMISTIC_WRITE query on the campaign row. The VERIFIED check runs next, then applyPatch mutates the status in the managed entity, then the fee charge runs, then save. The fee service is itself annotated transactional with the default propagation, so it joins the caller's transaction rather than opening its own — nothing is deferred to an after-commit hook or a follow-up job, and a throw anywhere in the sequence rolls the status flip back with it.
`influora-api/src/main/java/com/influora/service/CampaignService.java:241` "loadOwnedForUpdate"
`influora-api/src/main/java/com/influora/repository/CampaignRepository.java:51` "PESSIMISTIC_WRITE"
`influora-api/src/main/java/com/influora/service/CampaignService.java:261` "validateStatusForWorkspace"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:172` "public FeeChargeResult chargeOnPublish"

### 3. WHY NOT AS ADVERTISED?
The doc's premise is stale in the brand's favour. The human create form does send the type now: the type picker passes the chosen value into the form as initialValues, and the api layer translates the frontend vocabulary to the backend enum through an explicit table before POSTing. So a DIRECT campaign is gated — but at create time, not publish time. The store check sits in CampaignService.create, before the DRAFT row is ever built, and there is no equivalent check anywhere in update. So the answer to the question as posed is the reverse of what it assumes: a brand cannot even save the DRAFT without a connected store, and publish-time is not where the conflict surfaces. The residual gap is that the frontend has no store-integration precheck of its own — the form has no reference to store state at all, so the brand discovers the requirement only as a server 409.
`src/pages/brand-new-campaign.tsx:238` "initialValues={templateInitialValues"
`src/lib/api.ts:1345` "CAMPAIGN_TYPE_TO_API"
`influora-api/src/main/java/com/influora/service/CampaignService.java:171` "requiresStoreIntegration"
`influora-api/src/main/java/com/influora/service/CampaignService.java:177` "NO_STORE_INTEGRATION"

### 4. WHEN DOES IT BREAK?
Neither branch of the question happens. The concrete mechanism is the pessimistic row lock plus a pre-patch status snapshot: the second request blocks on findByIdForUpdate, and when it finally reads the row the status is already ACTIVE, so the transitioning flag computed before applyPatch is false and the fee service is never called at all. The second caller therefore gets a 200 with no debit, not a conflict — and if its patch carried any non-status field it gets CAMPAIGN_ACTIVE_NOT_EDITABLE, not CAMPAIGN_NOT_EDITABLE, which is only raised for COMPLETED or CANCELLED. Even if the lock were bypassed, the ledger idempotency key is deterministic per campaign, and the unique constraint on the transaction key is the real serialization point. The genuine hazard on this path is elsewhere: the fee is charged on every DRAFT-to-ACTIVE edge, so a pause-then-republish cycle re-enters chargeOnPublish, and the same campaign-scoped idempotency key then suppresses the second charge silently rather than re-billing.
`influora-api/src/main/java/com/influora/service/CampaignService.java:269` "campaign.getStatus() != CampaignStatus.ACTIVE"
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:75` "CAMPAIGN_NOT_EDITABLE"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:200` "brand-fee-publish:"
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:189` "the real serialization point"

### 5. WHAT IS MISSING?
Funded escrow is enforced on exactly one of the two publish paths, and it is not the schema. The AI path, ConfirmLaunchExecutor, reads every hold for the campaign fresh from the repository, filters to FUNDED, and refuses with ESCROW_NOT_FUNDED when the list is empty — only then does it set ACTIVE and charge the fee. The human path in CampaignService.update has no escrow read whatsoever: its preconditions are workspace VERIFIED, editability, and a wallet balance sufficient for the fee. So a human brand can publish an AI-drafted campaign straight through PATCH and bypass the funded-escrow precondition entirely; the executor even documents this hole, distinguishing a genuine confirm_launch replay from a campaign activated by some other path. There is no column, constraint, or trigger backing the rule.
`influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java:262` "h.getStatus() == EscrowStatus.FUNDED"
`influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java:275` "cannot confirm launch"
`influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java:281` "activated elsewhere"
`influora-api/src/main/java/com/influora/service/CampaignService.java:347` "if (transitioningToActive) {"

---

## Collaborations & Deals

### 6. WORKS?
Partly, and the question's premise about "every workspace member" is wrong. The POST does create the row through the APPLIED factory, and both deal_messages rows are written — a system event row plus the creator's own note — by the timeline recorder that runs immediately after the save. But the notification fans out to exactly one person: notifyApplicationCreated resolves a single billing recipient, and that resolver returns the first ACTIVE OWNER of the workspace and nothing else, so an ADMIN or MANAGER who is not the owner is never told. The last clause fails outright: the brand deal dashboard fetches the deal list once in a mount effect with no interval and no list-level stream, and the only SSE it opens is the message stream for an already-selected deal — a brand sitting on that page will not see a new application until they reload.
`influora-api/src/main/java/com/influora/service/CreatorCampaignService.java:257` "Collaboration.apply("
`influora-api/src/main/java/com/influora/service/CreatorCampaignService.java:273` "recordApplicationOnTimeline"
`influora-api/src/main/java/com/influora/service/BrandContextService.java:111` "findFirstByWorkspaceIdAndRoleAndActiveTrue"
`src/components/brand/deals/deal-room-dashboard.tsx:281` "if (isApiLive()) void loadDeals();"

### 7. HOW?
Neither of the three options the question offers. There is no lastProposedBy column anywhere on the collaboration; doAccept derives the last offer's author from the message table. It computes the acting sender type from the caller's role, then loads the single most recent deal message whose kind is proposal, ordered by creation time descending, and throws CANNOT_ACCEPT_OWN_OFFER only when that row's sender type equals the caller's. Because the repository method filters on the proposal kind, an interleaved system message is invisible to the query — a collaboration in IN_NEGOTIATION whose newest row is a system line still resolves to the last real offer underneath it, so the gate behaves identically. If no proposal row exists at all (a bare invite the creator accepts), the optional is empty and the guard is skipped by design.
`influora-api/src/main/java/com/influora/service/DealService.java:1116` "DealSenderType actingAs = role == UserType.CREATOR"
`influora-api/src/main/java/com/influora/service/DealService.java:1118` "findFirstByCollaborationIdAndKindOrderByCreatedAtDesc"
`influora-api/src/main/java/com/influora/service/DealService.java:1122` "CANNOT_ACCEPT_OWN_OFFER"

### 8. WHY NOT AS ADVERTISED?
The doc is stale — the stubs were removed. toDealResponse now counts the collaboration's real deliverable rows: total is the list size, done is the subset whose status is in the done set, and nextDeadline is the earliest deadline among rows that are neither done nor rejected, converted to an instant at UTC start of day. Its own comment records that the previous behaviour was a hardcoded zero-zero-null and names the consequence that was fixed. What the doc should say instead is a frontend gap: the brand deal-room list maps only the total into a deliverables field and formats nextDeadline into a timeline string — deliverablesDone is never read by the brand dashboard at all, so no "2 of 5 done" is rendered there, correct or otherwise.
`influora-api/src/main/java/com/influora/service/DealService.java:2028` "hardcoded 0/0/null here regardless of real deliverable state"
`influora-api/src/main/java/com/influora/service/DealService.java:2037` "DELIVERABLE_DONE_STATUSES.contains(d.getStatus())"
`src/components/brand/deals/deal-room-dashboard.tsx:134` "deliverables: deal.deliverablesTotal,"

### 9. WHEN DOES IT BREAK?
It splits: the floor is enforced, the ceiling deliberately is not. counter routes through validateCounterAmount, which is explicitly narrower than the create-time validator — it rejects a non-positive amount and rejects anything below budgetMin with AMOUNT_BELOW_BUDGET, and then simply returns. There is no budgetMax comparison in that method, so an above-ceiling counter persists. The ceiling reappears only at accept, where requireWithinRemainingBudget sums the agreed rates of all other committed collaborations on the campaign and throws AMOUNT_EXCEEDS_BUDGET if this offer pushes the total past budgetMax. So the failure mode the question guesses at is real but lands one step earlier than contract generation, and it has a hole: a collaboration with a null agreed rate returns early from that gate and contributes nothing to the committed sum, which the code documents as a known residual.
`influora-api/src/main/java/com/influora/service/DealService.java:1673` "private void validateCounterAmount"
`influora-api/src/main/java/com/influora/service/DealService.java:1680` "AMOUNT_BELOW_BUDGET"
`influora-api/src/main/java/com/influora/service/DealService.java:1764` "if (collaboration.getAgreedRate() == null) {"
`influora-api/src/main/java/com/influora/service/DealService.java:1713` "AMOUNT_EXCEEDS_BUDGET"

### 10. WHAT IS MISSING?
Nothing — the premise is false. DISPUTED is reachable and the entry path is exactly the shape the question hypothesises, just on a plural route: DealController exposes a POST under the deal id for disputes, which lands in DisputeService.open. That method requires funded, unreleased escrow first, rejects a second concurrent dispute, builds the Dispute row through its own factory, freezes the escrow before persisting so a rollback can never leave an open dispute over releasable money, and only then transitions the collaboration to DISPUTED and saves it. Both parties can open one; the opener type is derived from the caller's user type rather than the request body.
`influora-api/src/main/java/com/influora/web/DealController.java:186` "/{dealId}/disputes"
`influora-api/src/main/java/com/influora/service/DisputeService.java:116` "NO_FUNDED_ESCROW"
`influora-api/src/main/java/com/influora/service/DisputeService.java:140` "freezeUnreleasedForDispute"
`influora-api/src/main/java/com/influora/service/DisputeService.java:144` "collaboration.transitionTo(CollaborationStatus.DISPUTED);"

---

## Deliverables

### 11. WORKS?
Yes, for a 400MB file. uploadContent rejects any state that is not PENDING, DRAFT or REVISION_REQUESTED, checks the batch against a 1GB ceiling, then for each file calls streamToR2, which opens the multipart input stream, wraps it in a size-limiting stream and an MD5 digest stream, and hands that straight to the R2 put — nothing accumulates the bytes in a byte array. Spring's multipart limits are configured at 500MB per file and 1GB per request with no in-memory threshold override, so a 400MB part lands on disk and is streamed off it. The R2 object key is persisted into filesJson through applyUpload, which sets the next version number and puts the row back to DRAFT, and the response maps each stored file through resolveDownloadUrl, which is a presigned GET. The one thing worth flagging: a 400MB file is under the 500MB per-file cap but a second one in the same batch would trip the request cap first.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:957` "r2StorageService.putStream(key, digest, size, contentType);"
`influora-api/src/main/resources/application.yml:66` "max-file-size: 500MB"
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:267` "String filesJson = writeFilesJson(uploaded);"
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:1110` "resolveDownloadUrl(file.url())"

### 12. HOW?
PostUrlIdentifier recognises YouTube — it has watch-URL and short-URL patterns alongside the Instagram one — so extract returns a result whose platform is YOUTUBE, not an empty optional. The verification service then switches on that platform and returns FALLBACK_YOUTUBE_UNSUPPORTED with the reason that no YouTube client exists in this codebase. That outcome is not persisted anywhere: fallback only writes a WARN log line and returns the enum, and the job merges it into an in-memory tally that ends up in a single info log at the end of the sweep. So the answer to the last clause is that there is no persistence — no column on the deliverable, no verification-attempt table, no metric row. The deliverable keeps whatever creator-reported numbers it has and will be re-swept, and re-logged, every six hours forever.
`influora-api/src/main/java/com/influora/service/verification/PostUrlIdentifier.java:43` "YOUTUBE_WATCH_URL"
`influora-api/src/main/java/com/influora/service/verification/DeliverableVerificationService.java:152` "Outcome.FALLBACK_YOUTUBE_UNSUPPORTED,"
`influora-api/src/main/java/com/influora/service/verification/DeliverableVerificationService.java:322` "log.warn("
`influora-api/src/main/java/com/influora/job/DeliverableVerificationJob.java:79` "tally.merge(outcome, 1, Integer::sum);"

### 13. WHY NOT AS ADVERTISED?
The doc is accurate on the guarantee but the mechanism is a silent partial no-op, not a rejection and not a second row. There cannot be a second row: milestone_id carries a unique constraint and the service upserts by it. The submit path does no source check at all — it validates ownership, milestone status, non-negative numbers and the proof key, then calls applyReport, and applyReport is where the guard lives: if the stored source is PLATFORM_VERIFIED it skips the three number assignments entirely. Everything else on that call still lands, though — link, proof screenshot key, reporting creator id and reportedAt are written unconditionally — and the method returns 200 with the untouched 5000 echoed back. The creator gets no error and no indication their 50000 was discarded.
`influora-api/src/main/java/com/influora/domain/entity/DeliverableMetric.java:42` "unique = true"
`influora-api/src/main/java/com/influora/domain/entity/DeliverableMetric.java:166` "if (!SOURCE_PLATFORM_VERIFIED.equals(this.source)) {"
`influora-api/src/main/java/com/influora/domain/entity/DeliverableMetric.java:172` "this.link = link;"
`influora-api/src/main/java/com/influora/service/DeliverableMetricService.java:137` "deliverableMetricRepository.save(metric);"

### 14. WHEN DOES IT BREAK?
The race as posed cannot happen: SUBMITTED is not an uploadable state. canUploadNewVersion admits only PENDING, DRAFT and REVISION_REQUESTED, so the creator's concurrent re-upload is refused with INVALID_STATE and the brand's approve wins uncontested. The real exposure is a different pair on the same unlocked row. Deliverable carries no JPA version field and the brand path loads it through a plain findByIdAndWorkspaceId with no pessimistic lock, so two brand members approving the same SUBMITTED deliverable in parallel both read SUBMITTED, both pass canReview, and both proceed into the escrow release attempt — the only thing standing between that and a double payout is EscrowService's own locking and idempotency, not anything in the deliverable layer. The same absence lets a concurrent approve and reject interleave, with last-write-wins deciding the stored status.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:821` "status == DeliverableStatus.PENDING"
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:417` "findByIdAndWorkspaceId(deliverableId, workspace.getId())"
`influora-api/src/main/java/com/influora/domain/entity/Deliverable.java:58` "private int versionNumber;"

### 15. WHAT IS MISSING?
Nothing is missing — the doc is stale. Both the control and the endpoint exist: BrandDeliverableController exposes a reject route under the deliverable id, and the brand's DeliverableViewer calls it with the feedback text. The service requires the same SUBMITTED-or-RESUBMITTED reviewable state as approve, insists on non-blank feedback, then applies the terminal reject and notifies the collaboration lifecycle that the slot is resolved. The difference from a revision request in the money flow is that reject never touches escrow: approve is the only branch that calls the release attempt, so a rejected deliverable ends the loop with its milestone unreleased, whereas REVISION_REQUESTED leaves the slot open and re-uploadable. The rejected deliverable is also excluded from the next-deadline computation, so it stops driving SLA pressure.
`influora-api/src/main/java/com/influora/web/BrandDeliverableController.java:64` "/{deliverableId}/reject"
`src/components/brand/deliverables/DeliverableViewer.tsx:334` "api.deliverables.reject(deliverable.id, feedback)"
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:379` "deliverable.applyReject"
`influora-api/src/main/java/com/influora/service/BrandDeliverableService.java:384` "collaborationLifecycleService.onDeliverableReviewed"

---

## Marketplace / Creator Discovery

### 16. WORKS?
Partly, and two clauses of the premise are wrong about where the data comes from. It is one query: search builds a single specification through combine, which seeds itself with the discoverable predicate (discoverable true and suspended false) and ANDs everything else onto it, then issues one paged findAll. But the follower range is compared against the denormalised totalFollowers column on creator_profiles, not against platform_stats — the stats table is only loaded afterwards to decorate the page. And city is not audience geography at all: cityIn does a case-insensitive LIKE against the creator's own city column, so a Delhi-based creator with a Mumbai-heavy audience is excluded and a Mumbai-based creator with no Mumbai audience is included. Niche is a LIKE against the categories JSON column and verified is the is_verified boolean, both of which do behave as advertised.
`influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java:180` "Specification<CreatorProfile> combined = discoverable();"
`influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java:115` "preds.add(cb.ge(root.get("
`influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java:95` "cb.like(cb.lower(root.get("
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:185` "creatorProfileRepository.findAll(spec, PageRequest.of(safePage - 1, safeLimit, sort));"

### 17. HOW?
It is a page-size limit, not a SQL LIMIT clause written by hand, and it is on a query that ignores the caller's filters entirely. buildAvailableFacets runs its own findAll with only the discoverable specification and a PageRequest of page zero, size five thousand, with unsorted ordering, then loops the returned list in Java to tally category counts and follower buckets. So the cap is real but the facets are global, not facets of the current search — and because the sort is unsorted, which five thousand profiles you get is whatever the database returns, so the counts are not even stable between calls once the discoverable set exceeds the cap. The result page is separately clamped by the limit parameter to at most one hundred.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:777` "PageRequest.of(0, 5_000, Sort.unsorted())"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:776` "CreatorProfileSpecifications.discoverable(),"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:166` "int safeLimit = Math.min(Math.max(limit, 1), 100);"

### 18. WHY NOT AS ADVERTISED?
Half the doc line is stale and half is still true. Brand safety is wired now — ScoreCalculationJob calls BrandSafetyScoreService and writes the column — but only when a master switch is on, and that switch defaults to false, so on a stock deployment the column really is null everywhere. The frontend handles that honestly rather than fabricating: BrandSafetyBadge branches on a null score and renders a "Not yet available" empty state, and the discovery grid's score badge renders "Not yet scored" for a null value. What the question gets wrong is the placement — that badge is mounted on the creator-analytics pages, not on the discovery profile, and the discovery card carries a comment stating brandSafety is null for every creator. The audienceMatch half of the doc is still exactly true: the quality service hardcodes it to a neutral fifty and weights it at fifteen percent of the composite.
`influora-api/src/main/java/com/influora/config/BrandSafetyScoringProperties.java:41` "private boolean enabled = false;"
`src/components/analytics/BrandSafetyBadge.tsx:117` "if (brandSafetyScore === null) {"
`src/components/brand/discover/creator-discovery.tsx:366` "Not yet scored"
`influora-api/src/main/java/com/influora/service/scoring/QualityScoreService.java:88` "double audienceMatchScore = 50.0;"

### 19. WHEN DOES IT BREAK?
It does not break — the dedup is two-layered and the second layer is the database. invite first calls the shared revive-or-refuse helper, which is configured here to throw COLLABORATION_EXISTS when a non-revivable prior row is found; that catches the sequential duplicate. For the genuine concurrent race, where both requests read no prior row, the insert itself is the guard: collaborations carries a unique constraint on the campaign and creator pair, so the loser's save throws a DataIntegrityViolationException that is caught and translated into the same COLLABORATION_EXISTS 409. Only one INVITED row can exist, so the creator's inbox cannot show duplicates. Note the second caller sees a conflict, not an idempotent success — the two team members get different outcomes for the same intent.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:472` "COLLABORATION_EXISTS"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:508` "catch (DataIntegrityViolationException dup)"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:482` "UNIQUE(campaign_id, creator_id) rules out"

### 20. WHAT IS MISSING?
It keyword-matches, and only against a fixed niche vocabulary. inferCategories concatenates the campaign goals and target audience strings, lowercases them, and keeps every entry of a hardcoded keyword list that appears as a substring — no stemming, no synonyms, no embeddings, nothing that reads the brief as language. Those matched keywords become a categories specification, combined with a platform filter and a rate ceiling, and the top thirty candidates by engagement rate are fetched. Ranking is then a fixed additive rubric in Java: a base of 0.5, plus 0.25 for niche overlap, plus 0.15 for engagement at or above four percent, plus 0.1 for a verified badge, minus 0.2 when the estimated cost exceeds the stated budget, sorted and cut to ten. There is no vector search and no LLM anywhere on this path, so the doc's "heuristic, not LLM" is accurate and understated.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:875` "for (String keyword : SUGGESTION_NICHE_KEYWORDS) {"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:401` "Sort.by(Sort.Direction.DESC, "
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:740` "double score = 0.5;"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:761` "score -= 0.2;"

---

## Creator Profiles & Portfolio

### 21. WORKS?
Yes on every clause. applyUsername normalises through UsernameUtils, which slugifies (lowercase, whitespace to hyphen) and then converts hyphens to underscores, caps at thirty characters, and pads anything under three; it then validates the shape and checks uniqueness with a case-insensitive exists query that excludes the profile's own row, throwing USERNAME_TAKEN on collision, before writing the column. Completeness is not stored at all, so there is nothing to recompute — it is derived on every profile read by summing weighted field presence. The portfolio resolves the handle by a case-insensitive username lookup on each request with no cache layer in front of it, so the new handle is live on the next request and the old one 404s.
`influora-api/src/main/java/com/influora/common/UsernameUtils.java:16` "SlugUtils.slugify(raw).replace('-', '_')"
`influora-api/src/main/java/com/influora/service/CreatorProfileService.java:172` "existsByUsernameIgnoreCaseAndIdNot(username, profile.getId())"
`influora-api/src/main/java/com/influora/service/CreatorProfileService.java:267` "calculateCompleteness(profile, platforms),"
`influora-api/src/main/java/com/influora/service/CreatorProfileService.java:155` "findByUsernameIgnoreCase"

### 22. HOW?
There is no caching — assemble runs the whole computation on every call — but the doc's "bounded to 12" attaches to the wrong statistic. The twelve-item limit is on buildCollabs, the list of past-collaboration cards rendered on the page. computeOnTimeRate runs over the entire completed list with no bound, and for each of those collaborations isCollaborationOnTime issues its own deliverable query, so the on-time rate is an unbounded N-plus-one that grows with a creator's whole history, not a bounded twelve-row read. The per-deliverable rule is also more forgiving than the question assumes: a deliverable with no deadline, or one never submitted, counts as on time rather than being excluded, and a collaboration with zero deliverables counts as on time too.
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:605` "completed.stream().limit(12).toList()"
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:661` "long onTimeCount = completed.stream().filter(this::isCollaborationOnTime).count();"
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:667` "findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId());"
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:675` "if (deliverable.getDeadline() == null || deliverable.getSubmittedAt() == null) {"

### 23. WHY NOT AS ADVERTISED?
The doc is stale — the listener exists and is wired. contact validates the name, email shape, message presence and a 2000-character ceiling, then publishes PortfolioContactEvent through the application event publisher, and NotificationListener has an async after-commit handler for exactly that type which calls notify with an in-app title, a route to the creator inbox, and the creator's real email resolved by lookup. So the creator gets both an in-app notification and an email, not a silent drop. What genuinely is missing is durability of the message itself: there is no contact-message table — the guest's text survives only inside the notification payload map and the outgoing email, so nothing on the portfolio side can list or re-read past enquiries.
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:533` "eventPublisher.publishEvent(event);"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:442` "public void on(PortfolioContactEvent event) {"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:448` "emailOf(event.userId())"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:453` "event.message()"

### 24. WHEN DOES IT BREAK?
It returns zero rows, and the database is what guarantees the trap. The collaborations table declares a foreign key from creator_id to users(id), so the column can only ever hold user ids; profile ids and user ids are both 26-character ULIDs from the same generator, so a wrong-key join is shape-valid and silently empty rather than erroring. The portfolio code does not fall into it — assemble passes profile.getUserId(), not the profile id, into the completed-collaborations lookup, and buildCollabs then walks campaign and workspace by their own ids. Note the intermediate table is not strictly needed: creator_profiles already carries the user id, so the correct move is to read that column, not to join through users.
`influora-api/src/main/resources/db/migration/V6__creators_collaborations.sql:71` "FOREIGN KEY (creator_id) REFERENCES users(id)"
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:551` "profile.getUserId(), CollaborationStatus.COMPLETED);"
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:607` "campaignRepository.findById(collab.getCampaignId()).orElse(null);"

### 25. WHAT IS MISSING?
It is optional everywhere and enforced nowhere upstream of money. CreatorTaxIdentityService.submit is the only place a creator supplies GSTIN or PAN, and its TAX_IDENTITY_REQUIRED error is purely intra-request validation — it fires when the caller sends neither field, not when a creator tries to accept a deal or take a payout. Nothing in the deal-accept or payout path reads the tax fields at all. Invoice generation does not depend on the creator having done anything either: the invoice code is machine-assigned lazily by CreatorInvoiceCodeService.resolveOrAssign, which returns the existing code or mints a CRT-prefixed random one in its own transaction, retrying on collision. So an invoice can be numbered for a creator who never submitted a GSTIN or PAN — the statutory identity fields stay null while the code exists.
`influora-api/src/main/java/com/influora/service/CreatorTaxIdentityService.java:66` "TAX_IDENTITY_REQUIRED"
`influora-api/src/main/java/com/influora/service/CreatorTaxIdentityService.java:86` "profile.applyTaxIdentity(gstin, pan, status, null);"
`influora-api/src/main/java/com/influora/service/CreatorInvoiceCodeService.java:90` "if (creator.getCreatorInvoiceCode() != null"
`influora-api/src/main/java/com/influora/service/CreatorInvoiceCodeService.java:96` "creator.applyTaxIdentity(null, null, null, code);"

---

## Reviews

### 26. WORKS?
Mostly yes, with one clause misplaced. createReview gates on COMPLETED, blocks a second review of the same collaboration by the same side with ALREADY_REVIEWED, and backs that with a catch on the unique-constraint violation for the concurrent case, so the double-submit is genuinely closed. The review reaches the creator's public portfolio: assemble loads brand-to-creator reviews keyed by collaboration and buildCollabs attaches each one's stars to its card. But discovery is the wrong word for the rating: the average is computed only on the single-profile read, getPublicProfile, by averaging the creator's BRAND review stars — the search-results mapper builds a CreatorResponse that carries no rating field at all, so a five-star review changes nothing on the search grid.
`influora-api/src/main/java/com/influora/service/ReviewService.java:115` "collaboration.getStatus() != CollaborationStatus.COMPLETED"
`influora-api/src/main/java/com/influora/service/ReviewService.java:121` "existsByCollaborationIdAndReviewerType"
`influora-api/src/main/java/com/influora/service/ReviewService.java:138` "catch (DataIntegrityViolationException dup)"
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:991` "findReceivedByCreatorUserId(creatorUserId, ReviewerType.BRAND)"

### 27. HOW?
Two rows persist. The migration declares the unique key over the collaboration and reviewer-type pair, so one CREATOR row and one BRAND row coexist for the same collaboration by construction. They surface in two different places and never on the same page. The brand's review of the creator is the only one the creator's portfolio uses — the portfolio reads reviews filtered to reviewer type BRAND, and both the average rating and the per-collaboration card stars come from that set. The creator's review of the brand is read by listReceivedByBrand through a workspace-scoped query filtered to reviewer type CREATOR, and is rendered in the shared reviews panel's "Reviews about you" tab, not anywhere on the creator's public page.
`influora-api/src/main/resources/db/migration/V43__reviews.sql:14` "UNIQUE KEY uq_review_collab_reviewer (collaboration_id, reviewer_type),"
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:590` "reviewRepository.findReceivedByCreatorUserId(creatorUserId, ReviewerType.BRAND))"
`influora-api/src/main/java/com/influora/service/ReviewService.java:105` "findReceivedByBrandWorkspaceId(workspace.getId(), ReviewerType.CREATOR)"
`src/components/shared/collaboration-reviews-panel.tsx:217` "Reviews about you"

### 28. WHY NOT AS ADVERTISED?
The doc is accurate here and the frontend honours it. computeAvgRating returns null, not zero, when the review list is empty, and the field is present in the DTO rather than omitted — the record declares avgRating as a nullable BigDecimal, so the JSON carries an explicit null. The api layer types it as number or null and the brand creator-profile page carries the rule in a comment that it is never coerced to a fabricated zero, then renders a "No reviews yet" empty state. The honest gap is a different one: the DTO has no companion review-count field, so the page keeps reviewCount null in live mode and can show an average with no idea how many ratings produced it.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:993` "return null;"
`src/lib/api.ts:1707` "avgRating: number | null;"
`src/pages/brand-creator-profile.tsx:96` "Never coerced to a fabricated 0"
`src/pages/brand-creator-profile.tsx:1080` "No reviews yet"

### 29. WHEN DOES IT BREAK?
The gate is creation-time only, and the review survives the reset. createReview checks the status once, at write; nothing revalidates afterwards. The read side proves it: the query that feeds both the portfolio average and the public-profile average selects reviews by reviewer type and by the collaboration belonging to that creator, with no status predicate on the collaboration at all — its only filter beyond ownership is the hidden flag. So an admin moving the row back to IN_PROGRESS leaves the review visible and still counted, and the same collaboration cannot be reviewed a second time when it completes again, because the uniqueness key is the collaboration and reviewer type, not the completion event.
`influora-api/src/main/java/com/influora/repository/ReviewRepository.java:22` "r.hidden = false"
`influora-api/src/main/java/com/influora/repository/ReviewRepository.java:24` "SELECT c.id FROM Collaboration c WHERE c.creatorId = :creatorUserId"
`influora-api/src/main/java/com/influora/service/ReviewService.java:117` "COLLABORATION_NOT_COMPLETED"

### 30. WHAT IS MISSING?
The queue and the admin UI exist; the effect on the review does not. Flags land as ContentFlag rows of type REVIEW, AdminModerationController serves them under the admin moderation route, the admin console has a Moderation entry backed by the flag-queue hook, and actionFlag accepts REMOVE, REJECT, WARN and ESCALATE with the first two terminal. The break is that REMOVE only calls markActioned on the flag — it never touches the Review row. Review carries a hidden column, the read query filters on it, and the value is set to false at creation and assigned true nowhere in the entire codebase. So a moderated-out review stays on the portfolio and stays in the average; the moderation decision is recorded in the audit log and changes nothing a user can see.
`influora-api/src/main/java/com/influora/service/admin/AdminModerationService.java:195` "flag.markActioned("
`influora-api/src/main/java/com/influora/domain/entity/Review.java:46` "private boolean hidden;"
`influora-api/src/main/java/com/influora/domain/entity/Review.java:72` "review.hidden = false;"
`src/admin/components/AdminLayout.tsx:67` "{ label: 'Moderation', to: '/admin/moderation', icon: ShieldAlert },"

---

## Affiliate Earnings & Coupons

### 31. WORKS?
Partly — every link in the chain exists except the one the question assumes is instant. The Shopify order webhook does reach the service: the controller derives a stable idempotency key from the delivery and calls redeem with the shop's workspace id. Validation is real (expiry and total usage limit, though no per-customer cap — that column does not exist), the discount is computed, and the redemption row is persisted with the order amount and the coupon usage counter bumped. But no affiliate earning is minted there. The write path ends at the audit-log money event; the only caller of recordEarning in the entire main source tree is the hourly reconciliation job, which resolves the campaign commission rate and multiplies it by the order amount. So the earning does appear in the creator's affiliate view — at least thirty minutes and up to ninety minutes later.
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:216` "redemptionService.redeem(workspaceId, discountCode, orderId, orderAmount, null, idempotencyKey);"
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:137` "CODE_LIMIT_REACHED"
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:92` "coupon.incrementUsageCount();"
`influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java:388` "redemption.getOrderAmount().multiply(commissionRate)"

### 32. HOW?
Orphaned means exactly what the question guesses, expressed as a NOT EXISTS rather than a NOT IN: the repository query selects redemptions whose redeemedAt is older than the cutoff and for which no AffiliateEarning row references that redemption id. The cutoff is computed in the job as now minus a thirty-minute constant, and the cron fires at fifteen minutes past every hour. The stated reason for the grace period is not throughput — the class documents it as the window in which a synchronous accrual would have landed, so the job only backfills what that path is presumed to have missed, and it logs every backfill at WARN as a defect signal. That rationale is now false: there is no synchronous accrual left, so the grace period delays every single earning rather than filtering out a rare miss, and the WARN fires on every redemption.
`influora-api/src/main/java/com/influora/repository/CouponRedemptionRepository.java:38` "AND NOT EXISTS (SELECT 1 FROM AffiliateEarning e WHERE e.redemptionId = r.id)"
`influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java:64` "Duration.ofMinutes(30)"
`influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java:82` "0 15 * * * *"
`influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java:105` "affiliateEarningsService.recordEarning(redemption);"

### 33. WHY NOT AS ADVERTISED?
The doc line is right and the code is worse than it reads, because the job's own comments still describe a synchronous path that does not exist — RedemptionWriter.doRedeem saves the redemption, bumps the counter, writes an audit event and returns, with no earnings call anywhere in it. Nothing tells the creator about the lag either: redeem returns the CouponRedemption to a webhook controller answering Shopify, not the creator, and no notification event is published on that path. The UI neither promises instant accrual nor explains the gap — the earnings view's empty state says a commission "will show up here" once a sale is attributed, and a present row is badged "Pending settlement", which describes the monthly payout, not the hourly mint. So the creator sees an empty list for up to an hour with no explanation.
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:107` "return redemption;"
`influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java:128` "synchronous RedemptionService#performRedemption"
`src/components/creator/AffiliateEarningsView.tsx:183` "When a sale is attributed to one of your affiliate campaigns"
`src/components/creator/AffiliateEarningsView.tsx:164` "Pending settlement"

### 34. WHEN DOES IT BREAK?
The premise misnames the key. Deduplication of the webhook is on coupon_redemptions.idempotency_key, not on redemption_id — redeem rejects a blank key, replays any existing redemption carrying that key before touching anything, and otherwise reserves the key through the shared idempotency table so the concurrent loser re-queries the winner's row instead of hitting a raw constraint violation. The key is not the Shopify order id either: the controller hashes the shop domain, the topic and the order id together, so it is already shop-scoped and two different shops cannot collide by construction. The UNIQUE(redemption_id) constraint the question is thinking of lives one layer down, on affiliate_earnings, and its job is to stop the hourly job minting a second earning for the same redemption — the two guards protect different things.
`influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java:177` "CouponRedemption replay = replayIfPresent(idempotencyKey);"
`influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java:191` "idempotencyService.executeOnce("
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:221` "the shop domain, the topic, and"
`influora-api/src/main/java/com/influora/repository/CouponRedemptionRepository.java:33` "UNIQUE(redemption_id)"

### 35. WHAT IS MISSING?
It does more than mark rows and less than pay anyone. The writer marks each earning settled against the batch and then credits the creator's Influora wallet through the ledger, using the same clearing-to-payee posting shape escrow release uses — so an internal balance really does move. What does not happen is any external disbursement: the class states outright that wiring a settled balance into a bank transfer through the existing payout path is a separate, unbuilt decision. There is also no notification and no email — neither the job nor the writer publishes an event of any kind, so a creator learns they were settled only by opening the page. And it is monthly, not on demand: the schedule is five o'clock on the first of each month.
`influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java:74` "earning.markSettled(batch.getId());"
`influora-api/src/main/java/com/influora/job/AffiliateSettlementWriter.java:76` "creditCreatorWallet(earning);"
`influora-api/src/main/java/com/influora/job/AffiliateSettlementJob.java:35` "is a separate,"
`influora-api/src/main/java/com/influora/job/AffiliateSettlementJob.java:131` "0 0 5 1 * *"

---

## Notifications

### 36. WORKS?
Yes, with the recipient caveat from question 6. The apply path publishes ApplicationCreatedEvent, NotificationListener has an async after-commit handler for it, and because the event type is in neither the email-only nor the in-app-only set, notify takes the both-channels branch and calls createInAppNotification, which persists a Notification row against the recipient user and workspace. The brand's shell reads that through the notifications hook and renders an unread count badge on the bell, clamped to nine-plus. The caveat is that only one row is created, for the workspace owner resolved as the billing recipient, so a non-owner member's bell stays empty.
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:307` "public void on(ApplicationCreatedEvent event) {"
`influora-api/src/main/java/com/influora/service/notification/NotificationService.java:85` "createInAppNotification(event, title, body, link);"
`influora-api/src/main/java/com/influora/service/notification/NotificationService.java:114` "notificationRepository.save(notification);"
`src/components/brand/brand-layout.tsx:433` "unreadCount > 9 ? '9+' : unreadCount"

### 37. HOW?
The premise is out of date about the transport: there is no HTTP 200 from MSG91 on this path — the client sends over an SMTP relay and returns a plain boolean, and in an unconfigured dev environment it logs and returns a mocked true. The worker claims up to fifty PENDING rows under a lease in one short transaction, then sends each one outside any transaction, and marks the result in its own per-row transaction: true becomes markSent immediately, anything else — including a thrown exception — becomes markFailed with the message. Nothing waits for confirmation of delivery. There is no MSG91 delivery webhook anywhere in the codebase, so a message that is accepted by the relay and bounces afterwards stays SENT forever and no one is told.
`influora-api/src/main/java/com/influora/service/notification/EmailWorker.java:79` "BATCH_SIZE = 50"
`influora-api/src/main/java/com/influora/service/notification/EmailWorker.java:195` "success = msg91Client.sendTemplateEmail(toEmail, templateKey, templateData, userId);"
`influora-api/src/main/java/com/influora/service/notification/EmailWorker.java:241` "outbox.markSent();"
`influora-api/src/main/java/com/influora/integration/msg91/Msg91EmailClient.java:140` "return true;"

### 38. WHY NOT AS ADVERTISED?
The doc line is simply stale. Handlers do not pass null — the listener calls emailOf on the recipient user id, which looks the address up on the User row and returns null only when the user is missing or the id is blank; that helper is used twenty-six times in the file, and DeliverableSubmittedEvent is one of them. The blank-email guard is real but it is a genuine last resort: it logs a WARN and returns from the email branch only, after createInAppNotification has already run in the both-channels branch, so the bell still lights up even in that case. In other words the doc describes a failure mode that both no longer triggers and would never have suppressed the in-app channel.
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:381` "emailOf(event.userId())"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:146` "userRepository.findById(userId).map(User::getEmail).orElse(null);"
`influora-api/src/main/java/com/influora/service/notification/NotificationService.java:129` "No email address for event: userId={}, eventType={}"
`influora-api/src/main/java/com/influora/service/notification/NotificationService.java:86` "queueEmailIfNotUnsubscribed(event, toEmail, templateKey, templateData, idempotencyKey);"

### 39. WHEN DOES IT BREAK?
It stays in the table forever and nobody is told. markFailed increments the retry count and, at five, sets the status terminal and clears the next-retry timestamp, so the claim query — which selects PENDING rows only — never picks it up again. Nothing deletes it: there is no cleanup job for email_outbox, and no admin service reads it either. The only admin surface touching that table is the custom-email sender, which enqueues new rows and has no retry or requeue path for a failed one. So a password-reset email that exhausts its backoff leaves a FAILED row, a WARN in the logs, and a user staring at a screen that told them to check their inbox — the API response for the originating request already returned success before the worker ever ran.
`influora-api/src/main/java/com/influora/domain/entity/EmailOutbox.java:137` "if (this.retryCount >= MAX_RETRIES) {"
`influora-api/src/main/java/com/influora/domain/entity/EmailOutbox.java:139` "this.nextRetryAt = null;"
`influora-api/src/main/java/com/influora/service/notification/EmailWorker.java:154` "PageRequest.of(0, BATCH_SIZE));"));"
`influora-api/src/main/java/com/influora/service/admin/AdminCustomEmailService.java:195` "Confirmed send. Enqueues one"

### 40. WHAT IS MISSING?
Both halves of the doc line are wrong now. Every event class in the notification event package has a matching handler in NotificationListener — a mechanical comparison of the class names against the handler signatures leaves only the base interface unmatched, so the count of listener-less events is zero. The real residual is the mirror image: ten event types have a handler but are never constructed anywhere in main, including the wallet-low-balance, KYC-approved and KYC-rejected notifications, so those handlers are dead code rather than orphaned events. The frontend claim is stale too: both endpoints exist — the controller maps a read-all POST and both a GET and a POST for preferences — and the api layer calls them, with the hook branching on live mode and falling back to mock rows only when the API is not live. Mark-all-read does not 404.
`influora-api/src/main/java/com/influora/web/NotificationController.java:133` "/read-all"
`influora-api/src/main/java/com/influora/web/NotificationController.java:253` "/preferences"
`src/lib/api.ts:3623` "POST', '/notifications/read-all"
`src/hooks/useNotifications.ts:262` "await notificationsApi.markAllRead(role);"

---

## Analytics & Creator Scoring

### 41. WORKS?
Partly — one of the three metrics is real and two are not. MetricsPollingJob does run on a six-hourly cron and does persist a time-series creator_metrics row per poll, but the builder sets only username, followers, following and media count. The engagement-rate, average-reach-per-post and average-impressions-per-post columns exist on the entity and are written by nothing in main, so they are permanently null on every polled row. Per-post reach does get captured, but into a different table: the poll then calls pollRecentMedia, which is gated behind a media-metrics-enabled flag and writes media_metrics rows. The brand read is genuinely plan-capped: the analytics routes sit behind AnalyticsUsageCapInterceptor.
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:122` "0 0 */6 * * *"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:232` ".followers(profile.followersCount() == null ? 0L : profile.followersCount())"
`influora-api/src/main/java/com/influora/domain/entity/CreatorMetric.java:95` "private Long avgReachPerPost;"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:247` "pollRecentMedia(creatorProfileId, igBusinessAccountId, token.get(), authPath);"

### 42. HOW?
Latest snapshot only, and it appends rather than upserting. scoreOne fetches exactly one CreatorMetric — the newest Instagram row for that creator — and skips the creator entirely when there is none. Recent media is fetched separately with a page limit, but the historical-metrics argument to the fake-follower analyzer is passed as an explicit empty list, and the comment states the growth-spike signal needs at least seven historical points, so it can never fire. The write is a fresh row every run: the builder mints a new ULID id and stamps both time and computedAt with the current instant before saving, so creator_scores accumulates one row per creator per day and readers take the newest by time.
`influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java:135` "0 0 4 * * *"
`influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java:270` "findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc("
`influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java:289` "fakeFollowerDetectionService.analyze(latestMetric, recentMedia, List.of());"
`influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java:303` ".time(Instant.now())"

### 43. WHY NOT AS ADVERTISED?
The DTO shape in the question is wrong twice over, and the omission question has a definite answer: the field is dropped, not sent as null. CreatorScoresResponse is a flat record — audienceMatchScore and brandSafetyScore are siblings of qualityScore, not nested inside it — and it is annotated NON_NULL, so an unscored creator's JSON simply has no brandSafetyScore key at all. The doc's hardcoded fifty is accurate on the write side: the quality service assigns a neutral fifty and the job stores it into audienceMatchScore, so fifty is what every creator returns. On the UI, the brand analytics page passes the score through with an explicit null coalesce into BrandSafetyBadge, which renders its "Not yet available" empty state — so the row is shown as unavailable rather than omitted from the page.
`influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java:101` "JsonInclude.Include.NON_NULL"
`influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java:109` "BigDecimal brandSafetyScore,"
`influora-api/src/main/java/com/influora/job/ScoreCalculationJob.java:310` ".audienceMatchScore(qualityResult.audienceMatch())"
`src/pages/brand-creator-analytics.tsx:183` "brandSafetyScore={scores?.brandSafetyScore ?? null}"

### 44. WHEN DOES IT BREAK?
One unit, and the mid-sequence 402 cannot happen for that creator. The interceptor extracts the creator id from the URI and calls recordCreatorLookup, which dedupes on the workspace, metric, period and creator id together, so the second and third sub-endpoint calls for creator 123 match the already-recorded lookup and consume nothing. The limit is checked before recording, so a rejected request never burns quota, and a Pro plan with a null monthly limit skips the dedup bookkeeping entirely and only increments an observability counter. The concrete failure is therefore a different one: the rejection fires on the FIRST request for a NEW creator id past the cap, returning UPGRADE_REQUIRED with a 402 — and because the extractor falls back to the whole request URI when the path shape does not match, an unexpected route would silently degrade to per-call counting instead of per-creator.
`influora-api/src/main/java/com/influora/security/AnalyticsUsageCapInterceptor.java:100` "usageCounterService.recordCreatorLookup("
`influora-api/src/main/java/com/influora/security/AnalyticsUsageCapInterceptor.java:86` "usageCounterService.incrementUsage(workspaceId, UsageMetric.CREATOR_ANALYTICS_VIEW, 1);"
`influora-api/src/main/java/com/influora/security/AnalyticsUsageCapInterceptor.java:104` "UPGRADE_REQUIRED"
`influora-api/src/main/java/com/influora/security/AnalyticsUsageCapInterceptor.java:125` "matcher.matches() ? matcher.group(1) : uri;"

### 45. WHAT IS MISSING?
Nothing is missing — the doc is stale and the answer to "what would populate it" is the second of the two options the question offers. media_metrics is populated by an extension of MetricsPollingJob, not a separate job: after the profile snapshot is saved, pollRecentMedia fetches recent media with insights, maps each item through MediaMetricMapper and saves the batch, degrading rather than failing when a per-post insights call is missing. It is gated on a media-metrics-enabled property, so an operator can still turn it off. The read surface exists too: AnalyticsController exposes a media route under the creator id, so per-post breakdowns are a separate endpoint from the aggregate metrics and scores endpoints, sharing the same per-creator usage-cap unit.
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:280` "if (!metaProperties.isMediaMetricsEnabled()) {"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:307` "MediaMetricMapper.toMediaMetric("
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:316` "mediaMetricsRepository.saveAll(rows);"
`influora-api/src/main/java/com/influora/web/AnalyticsController.java:101` "/{creatorId}/media"

---

## Reports & Exports

### 46. WORKS?
Yes, with one header detail off. The route carries the plan annotation for the export feature, the service re-checks workspace membership and campaign ownership, then builds the rows from the campaign analytics response, whose deliverable list comes from deliverable_metrics rows fetched by collaboration id. The bytes are returned in the same request — there is no job, no polling, no presigned URL — and the disposition header is an attachment with the service-built filename, which is campaign, then the campaign id, then report.csv, since the filename part resolver returns the id. The detail the question gets wrong is the content type: the controller deliberately sends application/octet-stream rather than text/csv, keeping the real type only on the internal record.
`influora-api/src/main/java/com/influora/web/ReportExportController.java:36` "@RequiresPlan(feature = PlanFeature.EXPORT)"
`influora-api/src/main/java/com/influora/web/ReportExportController.java:47` "MediaType.APPLICATION_OCTET_STREAM"
`influora-api/src/main/java/com/influora/service/ReportExportService.java:129` "return campaign.getId();"
`influora-api/src/main/java/com/influora/service/ReportExportService.java:62` "deliverableMetricService.getCampaignAnalytics(principal, workspace.getId(), campaignId);"

### 47. HOW?
It sums everything and excludes nothing by source, and the SUMMARY row is inserted at index zero, so it is the first data row rather than a final one. The totals come from the analytics response, which sums reach, impressions and engagements over every deliverable_metrics row for the campaign's collaborations with a null-skipping reducer — a PLATFORM_VERIFIED row and a CREATOR_REPORTED row are added together indiscriminately. Each detail row does carry its own real source value, but the summary row's source column is the analytics envelope's, which is hardcoded to the creator-reported constant regardless of what the rows actually contain. A deliverable with no metrics reported yet has no row at all rather than a zero row, so the CSV silently under-represents the campaign rather than showing gaps.
`influora-api/src/main/java/com/influora/service/ReportExportService.java:102` "rows.add(0,"
`influora-api/src/main/java/com/influora/service/DeliverableMetricService.java:205` "metrics.stream().map(getter).filter(v -> v != null).mapToLong(Long::longValue).sum();"
`influora-api/src/main/java/com/influora/service/DeliverableMetricService.java:198` "SOURCE_CREATOR_REPORTED,"
`influora-api/src/main/java/com/influora/service/ReportExportService.java:113` "analytics.source()"

### 48. WHY NOT AS ADVERTISED?
The doc is stale — the frontend caller exists and is a real button. brand-campaign-detail has two click handlers, one for CSV and one for PDF, both calling handleExportReport, which goes through the api layer's reports client to the export route, wraps the response in an object URL, and drives a synthetic anchor download. It also handles the plan gate honestly, mapping a 402 to an upgrade prompt in a toast rather than a generic failure. The remaining inconsistency is elsewhere in the product: the pricing page still lists export reports as a coming-soon Pro item and answers a FAQ question about when it will be available, so the marketing surface contradicts the shipped feature.
`src/pages/brand-campaign-detail.tsx:1070` "void handleExportReport('csv')"
`src/lib/api.ts:3815` "/export?format=${format}"
`src/pages/brand-campaign-detail.tsx:599` "Report export is a Pro feature."
`src/pages/pricing.tsx:66` "Export reports (CSV/PDF)"

### 49. WHEN DOES IT BREAK?
It builds and streams synchronously with no cap anywhere, so 500 deliverables block the request thread for as long as the work takes. There is no row-count limit, no pagination and no background-job branch in either the controller or the service: the analytics call loads every collaboration for the campaign, then every milestone and every metric row for those collaborations in unbounded IN queries, the CSV is assembled into a single String, that String is turned into one byte array, and the whole array is handed to the response body. Nothing enforces a timeout in application code, so the concrete failure mode is memory and the container's own request timeout — the entire report exists twice in heap, as text and as bytes, before a single byte reaches the client.
`influora-api/src/main/java/com/influora/service/DeliverableMetricService.java:175` "deliverableMetricRepository.findByCollaborationIdIn(collaborationIds);"
`influora-api/src/main/java/com/influora/service/ReportExportService.java:115` "String csv = CsvWriter.write(headers, rows);"
`influora-api/src/main/java/com/influora/service/ReportExportService.java:116` "byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);"
`influora-api/src/main/java/com/influora/web/ReportExportController.java:51` ".body(file.bytes());"

### 50. WHAT IS MISSING?
The counter exists and is read, but nothing ever writes it, so a Pro brand can export without limit. UsageMetric.EXPORT appears exactly once in the whole main source tree — BillingController reads it to display exports used for the current period — and there is no matching incrementUsage call on the export path or anywhere else, so that figure is a permanent zero. Quota enforcement, if it were added, would belong where the per-creator analytics cap already lives: an interceptor holding the resolved plan and workspace off the request, calling the usage counter before the handler and throwing the 402 itself. Today the plan annotation is a pure boolean feature check, so the only thing it does is keep Free-tier workspaces out.
`influora-api/src/main/java/com/influora/web/BillingController.java:121` "usageCounterService.getUsageForCurrentPeriod(workspaceId, UsageMetric.EXPORT);"
`influora-api/src/main/java/com/influora/web/ReportExportController.java:37` "/{campaignId}/export"
`influora-api/src/main/java/com/influora/security/AnalyticsUsageCapInterceptor.java:71` "PlanGateFilter.RESOLVED_PLAN_ATTR"
