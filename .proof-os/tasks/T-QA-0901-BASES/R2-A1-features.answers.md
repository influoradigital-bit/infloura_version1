# Priya -> Swapnil · Round 2 · Area 1 of 5 · FEATURES BASE · 100 answers

## Request arguments — does the call send what the server reads

### 1. For each param CreatorController.search reads, which are never sent by any frontend caller?
The controller reads sixteen params: q, platforms, city, verticals, categories, languages, minFollowers, maxFollowers, minRate, maxRate, minEngagementRate, maxEngagementRate, isVerified, page, limit and sortBy. The only builder on the client is creatorSearchQuery, and it emits exactly ten of them — q, city, page, limit, minFollowers, maxFollowers, minRate, maxRate, platforms and verticals. Six params are therefore never sent by any caller: categories, languages, minEngagementRate, maxEngagementRate, isVerified and sortBy. There is a single call site for the endpoint, so no other layer supplies them.
`src/lib/api.ts:1578` "if (params.verticals?.length) q.verticals"
`influora-api/src/main/java/com/influora/web/CreatorController.java:48` "@RequestParam(required = false) String languages,"

### 2. Does the campaign create request send every field CampaignWriteRequest validates?
CampaignWriteRequest carries only three bean-validated components: title, budget and timeline. campaignToPayload emits all three, so a well-formed form submit satisfies the contract. The weak spot is timeline, which is emitted only when the caller actually passed a timeline object; when it is absent the key is undefined, JSON.stringify drops it, and the server answers a 400 rather than the client catching it first. Nothing on the client mirrors the min-5-character title rule either, so a short title is also a server-side 400.
`src/lib/api.ts:1414` "budget: payload.budget,"
`influora-api/src/main/java/com/influora/web/dto/campaign/CampaignDtos.java:75` "@Valid @NotNull BudgetDto budget,"

### 3. Does the campaign PATCH send a partial body, and does the server distinguish absent from null?
The PATCH reuses the very same campaignToPayload builder as create, so it always assembles the full seventeen-key object; it becomes partial only incidentally, because JSON.stringify strips the keys whose value is undefined. The server cannot tell the two apart: CampaignPatchRequest is a Java record, so an omitted key and an explicit JSON null both deserialize to null, and every branch in CampaignService.update is a plain null check. The consequence is that a caller can never clear a field by sending null — only the nested hype block is merged field-by-field, while the list fields are full-replace-if-present.
`src/lib/api.ts:1493` "body: campaignToPayload(payload),"
`influora-api/src/main/java/com/influora/service/CampaignService.java:229` "if (req.budget() != null) {"

### 4. Does the deal counter-offer call send usage rights, and does the server persist what it sends?
CounterRequest has a usageRights component and the api layer declares it on the counter payload, and DealService writes it onto the collaboration when non-blank, so the round trip works. The break is at the call sites: of the four screens that counter, only the brand chat maps a real usage-rights value onto the field. The deal-room dashboard and the brand campaign detail page send amount and message only, and the creator chat deliberately sends deadline but not usageRights. So for three of four entry points the field the server persists is simply never populated.
`src/pages/brand-chat.tsx:1594` "usageRights: data.usageRightsDuration.replace(/-/g,"
`influora-api/src/main/java/com/influora/service/DealService.java:1238` "collaboration.setUsageRights(TextSanitizer.sanitizePlainText(body.usageRights()));"

### 5. Does the deliverable submit call send every field the server requires, or does it rely on defaults?
The server requires nothing at all: the controller declares the body as not required and does not even annotate it with Valid, and SubmitRequest carries no constraint annotations on finalCaption, hashtags or notes. The service substitutes an all-null request when the body is missing, and the real precondition — at least one uploaded file — is enforced imperatively, not by validation. The single UI caller sends finalCaption and conditionally notes; hashtags is declared in the client signature but never populated by any screen.
`influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java:98` "@RequestBody(required = false) SubmitRequest body) {"
`src/pages/creator-chat.tsx:1530` "finalCaption: data.caption,"

### 6. Does the escrow fund call send an amount, and does the server trust it or re-derive it?
No amount is sent and none could be trusted if it were: EscrowFundRequest has only campaignId and milestoneId, and the controller computes the figure itself from persisted state before calling the service. The client mirrors that exactly, posting only campaignId and a nullable milestoneId alongside the mandatory Idempotency-Key header. One thing the server does hardcode is the currency, which is passed as a literal into initiateFund rather than read from the campaign.
`influora-api/src/main/java/com/influora/web/EscrowController.java:83` "escrowService.deriveFundAmount(workspace.getId(), body.campaignId(), body.milestoneId());"
`src/lib/api.ts:3279` "body: { campaignId, milestoneId: milestoneId ?? null },"

### 7. Does the withdrawal call send the Idempotency-Key header on every code path, including retries?
Yes, and correctly. The api wrapper takes the key as a required positional argument, so it cannot be omitted at the type level, and the one UI caller mints the key once per logical submission into React state and reuses that same value on every retry of that submission. The key is cleared only when the dialog closes, when the amount changes, or after a success — all of which are genuinely new submissions. The controller header is declared optional, but the service rejects a missing key, so the client discipline is what keeps the path safe.
`src/pages/creator-wallet.tsx:593` "const idempotencyKey = withdrawIdempotencyKey ?? safeRandomUUID();"

### 8. Does the topup call reuse an Idempotency-Key across retries, or mint a new one each attempt?
It reuses. The brand wallet holds topUpIdempotencyKey in state and only mints a fresh UUID when that slot is empty, which is the same shape as the withdraw path. The one place this discipline is not followed anywhere in the money-in surface is the Meera workspace demo escrow button, which derives its key from a timestamp and therefore produces a brand-new key on every click.
`src/pages/brand-wallet.tsx:602` "const idempotencyKey = topUpIdempotencyKey ?? safeRandomUUID();"
`src/components/feature/meera/MeeraWorkspace.tsx:76` "const idempotencyKey = `${MEERA_DEMO_CAMPAIGN_ID}-${Date.now()}`"

### 9. Which frontend calls send a pagination cursor or page number that the server ignores?
There is no cursor anywhere in this client — the whole surface is page-and-limit. The inverse problem exists instead: the deals list endpoint accepts only a status param and returns a bare List, so the client sends no page at all and cannot. The one place a page number is effectively wasted is creator discovery, where page and limit are sent honestly but four of the user's filters are then applied client-side to that single page, so page two is filtered against a different subset than page one.
`influora-api/src/main/java/com/influora/web/DealController.java:68` "@RequestParam(required = false, defaultValue = "

### 10. Which frontend calls send a filter the server has no parameter for, so it is silently dropped?
None are dropped by the server; they are dropped by the client before the request is built, which is worse because the server does have the parameters. Creator discovery keeps selectedLanguages, engagementRange, verifiedOnly and sortBy in state and renders chips for them, but the search call omits all four even though the controller declares languages, minEngagementRate, maxEngagementRate, isVerified and sortBy. Those filters are then re-applied in memory over the twenty rows already fetched.
`src/components/brand/discover/creator-discovery.tsx:429` "const [selectedLanguages, setSelectedLanguages] = React.useState<string[]>([]);"
`src/components/brand/discover/creator-discovery.tsx:790` "if (verifiedOnly) {"

### 11. Are query params URL-encoded before being sent, and where is that done?
Encoding happens in one place — the HttpClient builds a URL object and writes each param through searchParams.set, which percent-encodes both key and value, and the same three lines are duplicated in request, requestWithMeta and requestOrNull. Path segments are encoded separately with explicit encodeURIComponent calls at the individual endpoint wrappers. The one exception is the report export wrapper, which concatenates the query string by hand rather than going through searchParams; it is safe today only because format is a two-value literal union.
`src/lib/api.ts:538` "url.searchParams.set(k, String(v));"
`src/lib/api.ts:3606` "/export?format=${format}`,"

### 12. Does any call send a number where the server expects a string, or vice versa?
Every query param is coerced to a string on the way out by String(v), which is the normal HTTP contract and Spring re-binds them to Long and BigDecimal, so no defect there. The one genuine crossing is the hype block's liveUntil: the frontend type models it as a Date, the wire value is a full ISO-8601 string produced by fmtIso, and the server keeps it as a raw String rather than an Instant precisely because the persistence mapper has no JSR-310 module. It round-trips only because mapCampaignFromApi converts it back to a Date on read.
`src/lib/api.ts:1383` "const fmtIso = (d: Date | string) =>"
`influora-api/src/main/java/com/influora/web/dto/campaign/CampaignDtos.java:58` "String liveUntil) {}"

### 13. Does any call send a date in a format the server does not parse?
No, and both risky spots are handled deliberately. Campaign dates are LocalDate server-side and the client builds yyyy-MM-dd from local calendar components rather than toISOString, specifically to avoid the UTC roll-back that had been storing the wrong day. Analytics windows are Instants server-side and the hook sends toISOString output, which Instant.parse accepts; a malformed value gets a clean INVALID_DATE_RANGE 400 rather than a stack trace.
`src/lib/api.ts:1377` "return `${y}-${mo}-${day}`;"
`influora-api/src/main/java/com/influora/web/AnalyticsController.java:113` "return Instant.parse(value);"

### 14. Does the campaign export call send the format param, and what happens on an unsupported value?
It sends it, but as a hand-built query string appended to the path rather than through the query option, and the client type restricts it to csv or pdf so no other value can be produced from this wrapper. Server-side the value is trimmed and lowercased and switched on; anything else throws INVALID_FORMAT as a 400. The endpoint returns raw bytes rather than the JSON envelope, so it uses a separate downloadBlob path and the plan gate returns a 402 that the caller has to surface as an upgrade prompt.
`influora-api/src/main/java/com/influora/service/ReportExportService.java:65` "String normalizedFormat = format == null ?"

### 15. Does the conversion webhook registration send the secret, or expect the server to generate it?
The server generates it. The generate route takes no body at all — the workspace is resolved from the principal and the service mints the plaintext, which is returned exactly once and never retrievable again. The client wrapper posts with no body and simply reads back a secret field. Calling it a second time rotates rather than reveals, and the only other operation is a DELETE that revokes.
`influora-api/src/main/java/com/influora/web/ConversionWebhookSecretController.java:53` "String plaintext = secretService.generate(workspace.getId());"
`src/lib/api.ts:4357` "? http.request<ConversionWebhookSecretResult>('POST', '/webhook-secret/generate')"

### 16. Which POST bodies are built by hand rather than from a shared type?
Most of them. The deal counter and deal create payloads are declared as inline anonymous object types in the api module rather than importing the Java-mirroring DTO shape, the notification preference body is an inline object literal, and the escrow fund body is assembled at the call site. The worst case is the portfolio editor, which hand-writes a fixed six-key literal instead of sending a diff of the Partial type the wrapper accepts, so six other PATCH-able fields are unreachable from the UI.
`src/lib/api.ts:1948` "payload: {"
`src/pages/creator-portfolio-editor.tsx:137` "bio: page.bio,"

### 17. Does the invite call send the campaign id, and is it validated against the caller workspace?
Yes on both counts. InviteRequest declares campaignId as NotBlank, the client sends it in the body, and CreatorDiscoveryService resolves the workspace from the principal and then loads the campaign with a workspace-scoped finder. A campaign id belonging to another workspace produces a scoped CAMPAIGN_NOT_FOUND 404 rather than leaking existence or allowing a cross-tenant invite.
`influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java:458` ".findByIdAndWorkspaceId(campaignId, workspace.getId())"

### 18. Does the review submit call send a rating within the range the server validates?
The server validates stars as NotNull with Min 1 and Max 5, and both review controllers apply Valid. The client payload type declares stars as a plain number, so the type system does not enforce the range, but the star input can only emit one through five and the panel refuses to submit below one. The two agree in practice; the range is enforced twice rather than shared.
`influora-api/src/main/java/com/influora/web/dto/review/ReviewDtos.java:16` "@NotNull @Min(1) @Max(5) Integer stars,"
`src/components/shared/collaboration-reviews-panel.tsx:176` "if (draft.stars < 1) {"

### 19. Does the dispute open call send evidence attachments, and does the server store them?
No, and there is nowhere to put them. The open route lives on the deal controller and its body is OpenDisputeRequest, a single NotBlank reason capped at 2000 characters. The service constructs the dispute from that reason alone, the entity has no attachment or evidence column, and all three UI call sites send only a reason string. Evidence therefore has to be pasted into the reason text or sent out of band.
`influora-api/src/main/java/com/influora/web/dto/dispute/DisputeDtos.java:18` "public record OpenDisputeRequest(@NotBlank @Size(max = 2000) String reason) {}"

### 20. Does the portfolio save call send the full portfolio or a diff?
Neither. The wrapper accepts a Partial of the portfolio page and the server PATCH DTO exposes twelve optional fields, but the editor always sends the same hardcoded six keys — bio, niches, visibility, customLinks, rateCard and coverUrl — regardless of what the user actually changed. Because the record cannot distinguish absent from null, the six that are always sent are always rewritten, and username, displayName, city, languages, avatarUrl and pinnedPosts can never be changed from this screen at all.
`src/pages/creator-portfolio-editor.tsx:136` "await api.portfolio.update({"
`influora-api/src/main/java/com/influora/web/dto/portfolio/PortfolioDtos.java:119` "public record PortfolioPatchRequest("

## Response handling — does the client read what the server returns

### 21. Which response fields does the server send that no frontend type declares?
On the discovery contract the answer is one: username. The creator response record declares it and the mapper populates it, but no interface in the shared types file mentions it anywhere, so the creator's handle arrives on every search row and is invisible to the type system. The other systematic case is the notification envelope, whose page and size fields are simply absent from the wire interface the client declares for that endpoint.
`influora-api/src/main/java/com/influora/web/dto/creator/CreatorDtos.java:24` "String username,"
`src/lib/api.ts:3352` "interface NotificationListWire {"

### 22. Which fields does a frontend type declare that the server never sends?
Two on the discovery path. The portfolio item type declares a metrics object, while the server's portfolio item record has only id, title, description, thumbnail, media url and platform, and the mapper even hard-nulls the description. The second is city, which the frontend mapper reads defensively even though the server never emits that key on this endpoint. On the safety review the mismatch runs the other way, which question 38 covers.
`src/lib/types.ts:645` "metrics?: {"
`influora-api/src/main/java/com/influora/service/CreatorMapper.java:80` "post.id(), post.caption(), null, post.thumbnailUrl()"

### 23. Does the creator search mapper read every field the DTO returns?
Structurally yes, because it spreads the whole row and only overrides four keys, so nothing is dropped at runtime. Two defects hide behind that. The declared return type is the profile interface, which has no username, so the field is unreachable to callers even though it is present in the object. And the city fallback is dead code: the server mapper puts the city value into the record component named location, so the city key never exists on this response and the coalesce always takes the left branch. Only three of the four list fields get an empty-array default; languages and contentStyles get none.
`src/lib/api.ts:1585` "location: row.location ?? (row as { city?: string }).city,"
`influora-api/src/main/java/com/influora/service/CreatorMapper.java:57` "profile.getCity(),"

### 24. Does the campaign detail page read the response envelope meta, or only data?
Only data. All four parallel fetches on that page go through the plain request method, which returns the envelope's data and discards meta by design; the meta-preserving variant exists and is documented as being for list endpoints that need hasMore or total, and none of the campaign detail calls use it. That is defensible for a single-entity read, but the deals list it fires alongside would benefit and cannot, since that endpoint returns no meta at all.
`src/lib/api.ts:1476` "const row = await http.request<CampaignApiRow>("
`src/lib/api.ts:598` "Same as {@link request} but preserves"

### 25. Does the deal list handle the pagination meta, or assume a single page?
It assumes a single page, and so does the server. The controller takes only a status param and returns a bare list with no meta, and the service loads every collaboration and filters in memory, so the result set is unbounded. The client calls it through the plain request method with no page or limit, and the deal room dashboard simply maps whatever array comes back. The campaigns list and creator search both use the meta-preserving variant, so this is an inconsistency within the same client rather than a platform limit.
`influora-api/src/main/java/com/influora/web/DealController.java:66` "public ResponseEntity<ApiResponse<List<DealResponse>>> list("
`src/components/brand/deals/deal-room-dashboard.tsx:271` "setLiveDeals(Array.isArray(remote) ? remote.map(mapDealToRoom) : []);"

### 26. Does the wallet response parse the currency, or assume INR?
It assumes. The summary response that both wallet pages call has four components and none of them is a currency; the balance response does carry one, and nothing calls it. The brand page carries a currency on its mock constant but never threads it into the formatter, so the formatter's default always applies, and the creator page hardcodes the currency inside its formatter with no parameter at all. The only place a server currency is honoured on either page is the individual transaction row.
`influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java:79` "Integer runwayDays) {}"
`src/pages/creator-wallet.tsx:206` "currency: 'INR',"

### 27. Are money amounts parsed as numbers or strings, and is precision lost?
They are plain JSON numbers. There is no Jackson configuration anywhere in the backend that serializes BigDecimal as a string, so every money field crosses as an IEEE double and the client types it as number. Precision is not lost at the magnitudes in play, but nullability is: BigDecimal is a boxed type and the deal response carries no non-null include, so a deal with no agreed amount puts a null on the wire while the client type asserts a plain number. The same shape repeats on the contract total. The admin fee types are the one place the codebase hedges, declaring a number-or-string union.
`influora-api/src/main/java/com/influora/web/dto/deal/DealDtos.java:51` "BigDecimal dealValue,"
`src/lib/api.ts:1881` "dealValue: number;"

### 28. Does any component call toFixed or similar on a value that can be null?
Most of the roughly eighty call sites are guarded, often explicitly against null rather than falsiness. Two admin panels are not. The finance console calls toFixed on reputation figures that are BigDecimal-backed and typed as plain numbers, guarding only the containing object. The billing console does the same on a churn percentage, guarding the metrics object rather than the nullable field inside it. Both would throw on a null the server is free to send.
`src/admin/components/finance/FinanceConsole.tsx:396` "{reputation.overall.toFixed(1)}</dd>"
`src/admin/components/billing/BillingConsole.tsx:511` "value={metrics ?"

### 29. Does the score panel handle a null score distinctly from a zero score?
Yes, and this is one of the cleaner pieces of wiring. The score badge takes a number, null or undefined and returns a not-yet-scored chip on a null check rather than a falsy check, so a genuine zero falls through and renders as zero percent. The hook behind it also distinguishes a score-not-found 404 from a real error, so an unscored creator is never presented as a failed fetch.
`src/components/brand/discover/creator-discovery.tsx:346` "if (value == null) {"
`src/hooks/analytics/useCreatorScores.ts:57` "err.status === 404 || err.code === 'SCORE_NOT_FOUND'"

### 30. Does the analytics response handler cope with an empty metrics array?
Yes at both levels. The creator analytics page computes a no-metrics predicate that includes a zero-length trend array, the trend chart itself renders a dedicated no-trend-data empty state on a zero-length data prop, and the brand campaign detail page guards its deliverables table on a non-zero length. The server-side field is a plain list on the analytics DTO, so an empty campaign yields an empty array rather than a null.
`src/components/analytics/MetricsTrendChart.tsx:88` "{data.length === 0 ? ("
`src/pages/creator-analytics.tsx:120` "metrics.trendData.length === 0;"

### 31. Does the contract response expose the PDF URL, and does the client use it?
The contract response exposes no url at all — it carries an R2 object key, and the client type mirrors that. Downloads go through a separate presign endpoint that mints a fresh link and 404s until both parties have signed, and the components comment explicitly that no url field exists. The one place a real pdfUrl is sent is the invoicing DTO, and that one is declared twice in the client and read by nothing.
`influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java:246` "String pdfR2Key,"
`src/lib/api.ts:3637` "pdfUrl: string;"

### 32. Does the deliverable status response carry the deadline, and is it read?
The creator-side status response carries it as a LocalDate and the client type declares it with an accurate comment about the null case, but no component reads it — every deadline reference on the creator surface belongs to a campaign card, a counter-proposal input or a fabricated fallback. The brand-side review DTO has no deadline component at all, so the reviewing brand cannot see one, and the contract panel papers over the gap by inventing a date two weeks out and, elsewhere, by falling back to a hardcoded literal date.
`src/lib/api.ts:5150` "deadline?: string | null;"
`src/components/creator/deal-room/creator-deal-contract-tab.tsx:92` "deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000)"

### 33. Does the notification list read the read/unread flag correctly?
The flag itself, yes: the wire field is isRead and an explicit mapper renames it to read, so nothing is inverted or lost. The count is where it breaks. The server sends an authoritative unread total on the envelope and the api wrapper returns it, but the hook destructures only the items and then recomputes the count by filtering the current page, so the bell badge undercounts as soon as unread notifications exceed one page. The mark-read calls also discard the server's fresh count.
`src/lib/api.ts:3380` "read: n.isRead,"
`src/hooks/useNotifications.ts:139` "const unreadCount = notifications.filter((n) => !n.read).length;"

### 34. Does the messages stream handler parse every event type the server emits?
Yes on both streams. The deal-message registry emits exactly one named event plus a bare comment heartbeat on connect, and the client skips anything whose name is not that one. The Meera edge emits seven named events and the hook has a case for all seven. The one contract that is only half-implemented is the event id: the registry stamps an id on every frame and the controller accepts a Last-Event-ID header for replay, and the client's frame parser handles only the event and data fields, so it never sends the header and the server's replay buffer is dead weight.
`src/lib/api.ts:2217` "frame.event !== 'deal-message') continue;"
`influora-api/src/main/java/com/influora/service/DealMessageStreamRegistry.java:187` "private void replayMissedEvents("

### 35. Does the stream handler reconnect after a dropped connection?
The deal stream does. Every way a connection can end, including a clean end-of-body, schedules a reconnect with exponential backoff and jitter across the top half of the window, and only a 401, 403 or 404 verdict stops retrying. Because the transport carries no replay, the handler fires a reconnect callback so the room refetches rather than silently keeping the hole. The Meera stream deliberately does not reconnect, because its stream token is single-use and a retry would both 401 and risk double-spending credits; a heartbeat timeout only warns and hands recovery to the caller.
`src/lib/api.ts:2245` "scheduleReconnect();"
`src/hooks/useMeeraStream.ts:27` "fetch() never auto-reconnects (unlike EventSource)"

### 36. What does the client do with an event type it does not recognise?
Both clients ignore it silently. The deal stream continues the frame loop on any event name other than its one, with a comment naming heartbeats and other events as the expected case. The Meera hook falls into a default branch that returns false and logs only under a development build, deliberately so that frame payloads never reach an end user's console. Neither surfaces anything to the UI, which is right for a heartbeat and wrong for a genuinely new server event, since a protocol addition would be invisible.
`src/hooks/useMeeraStream.ts:220` "[useMeeraStream] Generic message:"

### 37. Does the Meera stream client handle a truncated or malformed chunk?
Truncation is handled: the reader appends decoded chunks to a buffer with streaming decode, so a split multi-byte character survives, and it only cuts a frame when it finds the blank-line separator. A malformed JSON payload is swallowed by a parse helper that warns and returns null, so the handler is skipped and the stream continues. Two gaps remain — a trailing partial frame left in the buffer when the body ends is never flushed or reported, and that warn is unconditional rather than development-gated, unlike the equivalent in the deal stream.
`src/hooks/useMeeraStream.ts:322` "buffer += decoder.decode(value, { stream: true });"
`src/hooks/useMeeraStream.ts:69` "[useMeeraStream] Malformed event data:"

### 38. Does any response handler assume an array where the server can send null?
Yes, in two places. The safety review record is annotated non-null-include and its checks list is therefore omitted from the JSON entirely when null, yet the client types it as a required array and the review card maps over it with no guard, while its sibling nullable fields on the same record are correctly typed nullable. The second is the discovery module's featured and suggestion mappers, which map directly over the response arrays with none of the empty-array defence used elsewhere in the same file.
`influora-api/src/main/java/com/influora/web/dto/deliverable/DeliverableSafetyDtos.java:55` "List<SafetyCheck> checks, BigDecimal score"
`src/lib/api.ts:1719` "creators: section.creators.map(mapCreatorFromApi),"

### 39. Where does the client cache a response, and can it serve a stale value after a mutation?
Effectively nowhere. The Zustand store has no TTL, no keys and no invalidation, and its auth slice persists literally nothing. The campaign slice looks like a stale-data vector but is write-only — the add method is its only consumer anywhere and nothing ever reads the list back, so no screen can serve from it. The two sessionStorage stores are demo-only and hard-gated off in live mode. Staleness in this app is therefore always per-component state, not a cache.
`src/lib/store.ts:49` "partialize: () => ({}),"
`src/lib/creator-contract-store.ts:53` "if (isApiLive()) return undefined;"

### 40. After a successful mutation, which screens refetch and which keep stale data on screen?
The campaign detail page and the deal room dashboard both refetch, the first by bumping a reload token that sits in its load effect's dependency array and the second by awaiting a paired deals and messages reload. Two keep stale data. Approving a deliverable is the act that releases escrow server-side, yet the contracts screen writes one local status string and never refetches, so the milestone list and the escrow state derived from it stay pre-release until a remount. The saved-creator toggle on discovery is optimistic only and discards the server's authoritative saved flag.
`src/components/brand/contracts/contracts-and-deliverables.tsx:812` "withDeliverableStatus(prev, selectedContract?.id, selectedDeliverable.id, 'approved'),"
`src/components/brand/deals/deal-room-dashboard.tsx:453` "await Promise.all([loadDeals(), loadMessages(selectedDeal.id)]);"

## Render wiring — does the screen actually show it

### 41. Which fields fetched by the discover page are never rendered?
The page itself is a five-line shim over the discovery component, and the mapper spreads the whole row through, so everything the DTO sends arrives in state. Six declared fields never reach the DOM on that screen: userId, coverImageUrl, currency, portfolioItems, languages and contentStyles. Every occurrence of those names in the component sits inside its mock fixture block rather than in JSX. Three per-platform fields are also dropped, namely handle, isVerified and profileUrl, of which only the platform code and a follower tooltip survive.
`src/lib/types.ts:528` "languages?: string[];"
`src/components/brand/discover/creator-discovery.tsx:1366` "{formatFollowers(p.followers)} followers"

### 42. Does the creator profile page render the languages field anywhere?
Yes. The brand-facing creator profile maps languages with an empty-array default on read and renders them joined by commas next to a Languages icon. That is the only screen that shows the field, so the same data is visible on the profile and invisible on the grid the brand searches from.
`src/pages/brand-creator-profile.tsx:630` "{creator.languages.join(', ')}"

### 43. Does the creator card show city, and does it fall back gracefully when city is null?
It shows it and it does not fall back. The mapper coerces city into location only when location is absent, and both can be undefined; the card then renders a pin icon followed by the bare value with no conditional wrapper and no placeholder. A creator with no city gets an orphaned map pin next to empty space in both the grid and the list layout. The neighbouring failure on the same card is the rate, rendered through a nullish default of zero so an unpriced creator reads as a confident zero.
`src/components/brand/discover/creator-discovery.tsx:1312` "{creator.location}</span>"
`src/components/brand/discover/creator-discovery.tsx:1348` "{formatINR(creator.averageRate ?? 0)}"

### 44. Does the campaign detail page render the deliverable requirements?
Yes, guarded on a non-empty array and sourced from the live campaign response rather than a placeholder. There is no empty state: when the array is empty the whole block including its heading disappears, so a campaign with no requirements looks the same as one whose requirements failed to map.
`src/pages/brand-campaign-detail.tsx:1997` "{campaign.requirements.length > 0 && ("

### 45. Does the deal room render the milestone list, and what shows when there are none?
Two tabs render milestones and they behave oppositely. The contract tab has a real empty state saying there are no milestones on the contract. The payments tab has none: it maps unconditionally over a row list which, when the server sent no milestones, is replaced by a fabricated schedule derived from the deal value with invented captions. The only disclosure is a footnote saying the figures are estimated from the deal value.
`src/components/brand/deal-room/deal-contract-tab.tsx:331` "No milestones on this contract."
`src/components/brand/deal-room/deal-payments-tab.tsx:301` "Estimated from the deal value"

### 46. Is the escrow status visible to the brand anywhere outside the payments tab?
Yes, in three places. The campaign detail page states that the selected bid amount will be locked in escrow before the brand confirms, the Hype campaign creation page shows an escrow-required line computed from rate times slots, and the brand chat branches on the deal record's escrowFunded flag. Escrow state is therefore surfaced on the paths where a brand is about to commit money, not only in the wallet.
`src/pages/brand-chat.tsx:2140` "{selectedDeal.escrowFunded ? ("

### 47. Does the wallet page render the escrow balance separately from the available balance?
Yes, as two distinct labelled figures on the brand wallet: an available balance that can be masked behind a visibility toggle, and an escrow-locked figure beside it. The caveat already noted in the source is that the escrow item count rendered next to the figure comes from a different fetch than the figure itself, so the two can disagree when one call fails.
`src/pages/brand-wallet.tsx:1075` "{formatCurrency(wallet.escrowLocked)}"

### 48. Does the creator wallet show pending versus available earnings distinctly?
Yes, three separate labelled figures for available balance, in escrow and pending payouts, each with its own tooltip, and the escrow figure repeats inside the withdraw dialog. This is also the only page in the app that distinguishes unknown from zero, because its formatter renders an em dash for null instead of a fabricated zero.
`src/pages/creator-wallet.tsx:768` "{formatEarning(earnings.pendingPayouts)}"
`src/pages/creator-wallet.tsx:115` "function formatEarning(amount: number | null): string {"

### 49. Does the invoice list render the document series, and are all four series reachable?
No series field is rendered anywhere, because none exists on the client at all: the frontend invoice types carry only a free-text invoiceNumber and every row renders just that string. The backend defines four statutory series, subscription, commission-brand, commission-creator and campaign-service. Reachability is split by role and no single view shows all four, since the brand billing screen reaches three and the creator wallet reaches two, leaving the subscription series unreachable for creators. The commission leg field is fetched and never rendered.
`influora-api/src/main/java/com/influora/domain/enums/InvoiceNumberSeriesType.java:8` "one platform-wide series."
`src/pages/brand-billing-settings.tsx:163` "{invoice.invoiceNumber}</p>"

### 50. Does the analytics page render a distinct state for "no data yet" versus "failed to load"?
Yes on both sides, and this is one of the better-wired areas. The brand analytics page branches on a roster error into an alert and separately on an empty roster into an empty state. The creator analytics page renders a load-error alert and a no-metrics alert that is explicitly guarded to be mutually exclusive with it, and it deliberately routes a score not-found 404 to the empty state rather than the error state.
`src/pages/creator-analytics.tsx:197` "{hasNoMetrics && !hasLoadError && ("
`src/pages/brand-analytics.tsx:164` "if (roster.length === 0) {"

### 51. Which pages render a loading skeleton, and which render nothing while loading?
Eight pages mount a skeleton: brand campaign detail, brand creator analytics, brand wallet, creator analytics, creator applications, creator campaign detail, creator campaigns and creator dashboard. Three render nothing at all. Brand onboarding has no skeleton, spinner or pulse anywhere in the file yet awaits three network calls; brand settings has one spinner and it belongs to a submit button, so the mount fetch shows an empty or stale form; campaign tracking forwards its loading flag to children without rendering any affordance itself.
`src/pages/creator-campaigns.tsx:309` "<Skeleton key={i}"
`src/pages/brand-campaign-tracking.tsx:59` "loading={trackingLinks.loading || coupons.loading}"

### 52. Which pages show an error toast versus an inline error versus failing silently?
Discovery uses a destructive toast and, on a first-page failure, also clears the grid, which was a deliberate fix for a failed search looking identical to no matches. The campaigns list uses an inline error strip with a Retry button. Both analytics pages use inline alerts. The wallet pages use inline state that renders an em dash. Brand onboarding is the silent case, with no loading or error affordance on mount at all.
`src/components/brand/campaigns/campaigns-list.tsx:707` "Retry"
`src/pages/creator-analytics.tsx:176` "{hasLoadError && ("

### 53. Is there any screen where a failed fetch is indistinguishable from an empty result?
Yes, several. The deal payments tab is the worst: a missing milestone list is replaced by a synthetic schedule rather than an empty or error state, so a failed or empty read renders as plausible data. Discovery is the second: a failed search deliberately empties the grid and lands in the same empty branch, so once the toast auto-dismisses the two states are visually identical. The creator dashboard is the third, because its empty predicate is only not-loading and zero deals and does not exclude the error case, so a failed deals fetch renders the celebratory empty state and an error alert at once.
`src/components/brand/discover/creator-discovery.tsx:651` "if (!append) setApiCreators([]);"
`src/pages/creator-dashboard.tsx:366` "const isEmptyCreator = !loading && deals.length === 0;"

### 54. Does the notification bell reflect unread count in real time or only on reload?
Neither strictly: it polls. The notifications hook sets a sixty-second interval refresh and there is no EventSource anywhere in the notification path, so worst-case staleness is a minute. The bell and the notifications page share the same hook, and opening the popover triggers an extra refresh. In mock mode the interval is skipped entirely, which makes it reload-only there.
`src/hooks/useNotifications.ts:29` "const REFRESH_INTERVAL_MS = 60_000;"
`src/hooks/useNotifications.ts:236` "const id = setInterval(() => {"

### 55. Does the contract page show which party has signed and which has not?
Partly. The contracts view derives per-party flags from the real brandSignedAt and creatorSignedAt timestamps rather than from the status string, and renders an explicit awaiting-creator-signature card when the brand has signed and the creator has not. The inverse case has no equivalent card, so when the creator signed first and the brand has not, the only signal is the presence of the Sign button and the state is never stated in words.
`src/components/brand/contracts/contracts-and-deliverables.tsx:559` "brandSigned: rec.brandSignedAt != null,"
`src/components/brand/contracts/contracts-and-deliverables.tsx:1401` ">Awaiting Creator Signature</p>"

### 56. Does the dispute view show the evidence the other party submitted?
No, and there is nothing to show. The dispute row type carries only reason and resolutionNotes, with no evidence or attachment field, and the dispute pages render the single opener-supplied reason plus any admin resolution note. The marketing page for secure payments promises that both sides submit their evidence in the deal room, which the product does not implement at any layer.
`src/lib/api.ts:5441` "reason?: string;"
`src/pages/brand-disputes.tsx:132` "{dispute.reason}</p>"

### 57. Does the review page show reviews received as well as reviews given?
Received yes, given no. Both review pages are shims over one shared panel with two tabs, a rate-your-counterparty form and a reviews-about-you list, and there is no list-given endpoint in the API surface at all. The brand side of the received list is not implemented server-side either, and the panel says so in an alert before falling back to illustrative demo data.
`src/components/shared/collaboration-reviews-panel.tsx:374` "{receivedReviews.map((review) =>"
`src/components/shared/collaboration-reviews-panel.tsx:338` ">Read API not yet available</AlertTitle>"

### 58. Does the public portfolio render the same data the authenticated view does?
It renders more. Both sides share one portfolio page shape, but the public view renders the actual rate-card rows with a min and max per line, while the editor only forwards rateCard through on save and exposes a visibility dropdown, never rendering or editing a single row. The same asymmetry applies to languages and audience cities. The practical result is that a creator cannot see or change the rates strangers read on their own public page.
`src/pages/creator-portfolio-public.tsx:542` "{page.rateCard.map((row) => ("
`src/pages/creator-portfolio-editor.tsx:443` "value={page.visibility.rateCard}"

### 59. Which admin-visible fields leak into a brand or creator view?
None. No file under the brand or creator surface imports from the admin types module; only the admin console and admin login reach into that tree, and the two remaining textual mentions are comments. The admin creator record carries email, phone, isSuspended and a moderation quality score, none of which appear on the brand-facing creator profile type. The one name collision worth watching is qualityScore, which exists on both but sits on different interfaces fed by different endpoints.
`src/admin/types/admin.types.ts:209` "isSuspended: boolean;"

### 60. Which screens render a currency symbol hardcoded rather than from the response?
The shared formatter hardcodes the currency regardless of anything on the payload, so every caller of it is effectively hardcoded. On top of that the brand pipeline value formatter, the creator campaigns budget label and the brand dashboard amount formatter each write the rupee glyph as a literal, and the campaign state machine does the same over a real escrow figure. This matters because the dispute row type already admits a non-rupee currency, so the mislabelling is reachable rather than theoretical.
`src/lib/utils.ts:23` "currency: 'INR',"
`src/pages/creator-campaigns.tsx:275` "₹{budgetRange[0].toLocaleString('en-IN')}"

## Empty, error and edge states

### 61. What does discover render when the server returns zero creators?
A three-way ternary picks a loading branch, an empty branch, or the grid. The empty branch renders a headline saying no creators match your filters, a sub-line suggesting the brand widen the search, and a Clear all filters button that is itself gated on a non-zero active-filter count. The defect is that only the button is gated: a brand who set no filters at all, or a genuinely empty catalogue, is still told to widen filters that were never applied.
`src/components/brand/discover/creator-discovery.tsx:1248` ") : filteredCreators.length === 0 ? ("
`src/components/brand/discover/creator-discovery.tsx:1255` "Try widening your search or clearing a few filters."

### 62. What does the campaign list render for a brand with no campaigns?
The campaigns list renders a proper empty card with a heading and a Create Campaign call to action, and unlike discovery it branches its sub-copy on whether a search query is active, so no-results and no-campaigns-yet read differently. Load failure is a separate branch with the server message and a Retry button, and the loading branch is explicitly guarded on there being no load error.
`src/components/brand/campaigns/campaigns-list.tsx:737` ">No campaigns found</h3>"
`src/components/brand/campaigns/campaigns-list.tsx:747` "Create Campaign"

### 63. What does the creator dashboard render on day one with no deals?
It renders the best empty state in the app: a heading saying no deals yet and that this is normal, an explanatory line, and two real calls to action for exploring campaigns and completing the profile. The error alert is kept separate. The flaw is that the empty predicate is only not-loading plus zero deals and does not exclude the error case, so a failed deals fetch shows the reassuring empty state and a could-not-refresh alert simultaneously.
`src/pages/creator-dashboard.tsx:604` ">No deals yet — that&apos;s normal</h2>"
`src/pages/creator-dashboard.tsx:612` "Explore campaigns"

### 64. What happens on the deal room when the collaboration was cancelled mid-view?
Nothing tells the user. The selected deal is an immutable snapshot captured on click, and the SSE transport only refetches the deal list when an incoming message is of kind proposal or system. The list refresh never re-points the selected deal at the refreshed row, and the deep-link effect early-returns when the ids already match. So a counterparty cancellation that carries no accompanying message frame leaves the open pane rendering a stale status with live action buttons; the cancel is discoverable only by clicking Accept and receiving a 409. Cancelled is also collapsed into the same visual bucket as disputed.
`src/components/brand/deals/deal-room-dashboard.tsx:358` "if (incoming.kind === 'proposal' || incoming.kind === 'system') {"
`src/components/brand/deals/deal-room-dashboard.tsx:289` "if (selectedDeal?.id === deepLinkDealId) return;"

### 65. What does the UI do on a 401 mid-session?
The HTTP client refreshes once and retries once; if the refresh also fails it clears the role's token and returns the original 401. After that, nothing happens. Route guards read the token from storage only during render, there is no storage event listener, no global 401 handler and no navigate-to-login outside explicit logout, so the user stays on a dead page issuing failing requests until a full route remount or reload. This is the largest error-handling gap in the client.
`src/lib/api.ts:518` "this.clearToken(role);"
`src/App.tsx:86` "const isAuthenticated = localStorage.getItem('brand_token');"

### 66. What does the UI do on a 403 from a role-gated endpoint?
It is handled per surface, never globally, and the refresh-and-retry path deliberately excludes 403 because refreshing cannot change permission. Three screens special-case it with useful copy: the campaigns list explains that only an Owner or Admin can delete and suggests pausing instead, the campaign detail page repeats that predicate, and brand settings says only owners and admins can change those settings. Billing additionally mirrors the rule client-side to hide the control up front. Every other 403 in the app falls through to a generic error message.
`src/components/brand/campaigns/campaigns-list.tsx:443` "Only the workspace Owner or Admin can delete a campaign."
`src/lib/api.ts:491` "Note the retry is 401-only by design."

### 67. What does the UI do on a 409 conflict from a status transition?
The brand chat has the richest handling: it switches on the error code and returns both a human sentence and a stale flag that drives a Refresh affordance, covering the deal-not-acceptable and cannot-accept-own-offer cases. Campaign editing has its own predicate for the active-not-editable 409, and the admin fee console handles an optimistic-lock conflict. The gap is that the server also emits two generic 409 codes from the exception handler, for data-integrity violations and concurrent modification, and no frontend file handles either, so those surface as raw server English.
`src/pages/brand-chat.tsx:628` "if (err instanceof ApiError && err.status === 409) {"
`influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java:113` "@ExceptionHandler(OptimisticLockingFailureException.class)"

### 68. What does the UI do on a 500, and is the error body surfaced or swallowed?
Surfaced, but there is almost nothing in it. The client throws an ApiError built from the envelope's code and message with the HTTP status attached, so nothing is swallowed. The server's own 500 handler answers a fixed INTERNAL_ERROR code and a fixed sentence, so what reaches the user is always the same generic string. A non-JSON 5xx from a gateway is classified separately as server-unavailable with retry-friendly copy.
`influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java:212` "An unexpected error occurred"
`src/lib/api.ts:586` "const unavailable = res.status === 0 || res.status === 502"

### 69. Is there a global error boundary, and what does it render?
Yes, one boundary mounted inside the router and keyed on the pathname so navigating away clears the crash. It renders a heading saying something went wrong, a reassurance that the rest of the app still works, and two buttons for try again and reload. It also deduplicates and posts the crash to the client-error endpoint. Its limit is that it catches render errors only, so unhandled promise rejections from async handlers never reach it.
`src/components/ErrorBoundary.tsx:146` ">Something went wrong</h1>"
`src/App.tsx:177` "return <ErrorBoundary resetKey={location.pathname}>{children}</ErrorBoundary>;"

### 70. Does a network timeout produce a distinguishable state from a server error?
No, because there is no timeout at all. The main request path passes no signal and constructs no AbortController; the only AbortController in the api module belongs to the deal-message SSE stream. A hung connection therefore spins forever with the submit button left disabled and no timeout copy anywhere. The closest thing is the non-JSON 502/503/504 branch, which only fires when a proxy actually answers.
`src/lib/api.ts:2111` "const controller = new AbortController();"

### 71. Which forms lose user input when a submit fails?
None of the three main ones. The campaign form, brand onboarding and creator onboarding all keep their state above the try and catch, and no failure path resets it; the message composers in the deal room and brand messages explicitly restore the draft text so nothing is silently lost. The one real hazard is brand onboarding step two, where a successful register followed by a failed company save shows a could-not-save-company-details message even though the account was already created, which reads as a total failure when it is a partial one.
`src/components/brand/deals/deal-room-dashboard.tsx:429` "setMessageInput(content); // restore so the user can retry"
`src/pages/brand-onboarding.tsx:100` "setError(err instanceof ApiError ? err.message : 'Could not save company details');"

### 72. Which forms allow a double submit, and is that guarded client-side or server-side?
The campaign form and the Hype campaign form both disable their submit buttons on a submitting flag, and the creator dispute form uses a composite predicate that includes it. Brand onboarding step two does not: the parent holds a submitting flag but passes it only to step three, while the step-two footer receives only the logo-upload flag, even though the footer component accepts and honours a submitting prop. Since the submit fires both register and save-company, a double click issues a second register before the first token exists.
`src/components/brand/onboarding/onboarding-steps.tsx:1100` "<StepFooter onBack={onBack} disabled={isUploadingLogo} />"
`src/components/brand/onboarding/onboarding-steps.tsx:187` "disabled={isSubmitting || disabled}"

### 73. Is there any optimistic update that is never rolled back on failure?
Most optimistic paths do roll back, including the saved-creator toggle and the notification read markers. The concrete failure is the deal room's accept handler, which dismisses the proposal dialog before the request and never reopens it, so on failure the dialog is gone and the error lands in a different region of the page. The counter and reject handlers in the same file get this right by closing inside the try after the await. The campaign detail accept path has the same shape, closing the dialog and nulling the selected bid unconditionally after the try and catch.
`src/components/brand/deals/deal-room-dashboard.tsx:442` "setShowProposalDialog(false);"
`src/pages/brand-campaign-detail.tsx:751` "setSelectedBid(null);"

### 74. Which mutations show no confirmation at all when they succeed?
The campaign detail bid accept and reject bump a reload token and say nothing, so failure is louder than success on that screen. Template delete on the new-campaign page removes the row with no message. Creator deal decline does the same. The most misleading case is the save-creator control on the brand creator profile, which only flips local state and never calls the toggle endpoint at all, so it silently resets on every navigation while the equivalent control on discovery persists.
`src/pages/brand-campaign-detail.tsx:737` "setReloadToken((k) => k + 1);"
`src/pages/brand-creator-profile.tsx:587` "onClick={() => setIsSaved(!isSaved)}"

### 75. Which destructive actions have no confirm step?
Campaign delete from both the list and the detail page, account deletion and Meta disconnect all use an alert dialog. Three do not. Template delete is a single click straight into a server DELETE. Creator deal decline is a single click that permanently rejects a brand's offer, and that page imports no alert dialog at all. Logout from all devices is styled destructive and fires immediately.
`src/pages/brand-new-campaign.tsx:312` "onClick={() => void handleDeleteTemplate(t)}"
`src/pages/creator-deals.tsx:673` "Decline"

## Cross-layer contract drift

### 76. Where does a frontend enum disagree with the backend enum it mirrors?
The five headline enums — campaign status, collaboration status, deliverable status, contract status and verification status — match value for value. Three others do not. The shared wallet transaction union declares PAYMENT, REFUND and FEE, none of which the Java enum has, and omits its ESCROW_REFUND, PLATFORM_FEE, PAYOUT and ADJUSTMENT. The transaction status union has CANCELLED where the server has REVERSED. The dispute status union invents MEDIATION, RESOLVED, ESCALATED and CLOSED while the server has three resolution outcomes. Platform is a fourth case in a different way: there is no Java enum for it at all, only a String column.
`src/lib/types.ts:70` "| 'PAYMENT'"
`influora-api/src/main/java/com/influora/domain/enums/WalletTransactionType.java:8` "ESCROW_REFUND,"

### 77. Where does a frontend status label differ from the status the server sends?
The server computes a statusLabel on every creator-application response, and three of its values contradict the client's map: it labels terms-agreed as in-negotiation where the client says accepted, cancelled as closed where the client uses a configurable decline wording, and invited as applied where the client says invited. Both sides document the conflict in their own comments. The saving grace is that nothing renders the server field — the client derives every label locally — so the drift is latent rather than visible.
`influora-api/src/main/java/com/influora/service/CreatorApplicationMapper.java:55` "case IN_NEGOTIATION, TERMS_AGREED ->"
`src/lib/application-status.ts:130` "TERMS_AGREED: 'Accepted',"

### 78. Which status values can the server send that the UI has no label for?
For everything actually rendered, none. All thirteen collaboration statuses are covered by the application-status map plus a special case for cancelled, all ten deliverable statuses by the deliverable viewer, and all five contract statuses by two maps kept in agreement by a cross-surface test. Dispute is the one that would break, and it is saved by a second definition: the shared types union has no resolved-brand, resolved-creator or resolved-split value, but the dispute pages carry their own local label map that does, and the api module declares a separate lifecycle union that matches the server.
`src/lib/types.ts:76` "'MEDIATION' | 'RESOLVED' | 'ESCALATED' | 'CLOSED';"
`src/pages/brand-disputes.tsx:16` "RESOLVED_BRAND: 'Resolved — in your favor',"

### 79. Are there two frontend API layers, and do both attach auth and base URL identically?
There are three. The main client and the Meera client duplicate the same base-URL expression with the same localhost fallback, so their base URLs agree, but their auth does not: the main client is role-parameterised and reads localStorage then sessionStorage, while the Meera client hardcodes the brand token key and reads localStorage only, and it has no refresh-and-retry at all, so an expired token there is a hard failure. The third layer is the admin service, which ignores the environment variable entirely in favour of a hardcoded relative path, sends no credentials, has no refresh retry, and has no mock mode.
`src/lib/meera-api.ts:392` "return localStorage.getItem('brand_token');"
`src/admin/services/api-contracts.ts:67` "const API_BASE = '/api/v1/admin';"

### 80. Which endpoints are declared in one API layer and called from the other?
The escrow fund route is declared in both, and the Meera copy says so in its own comment, noting that the escrow hook reaches the endpoint through it rather than through the main client; the two also type the response differently, since the main client marks the Razorpay order id required while the Java record is non-null-include and omits it whenever no order was created. Two more plain endpoints live only in the Meera layer despite belonging to other domains: the single escrow-hold status read and a workspace interactions route. The Meera layer also imports the main layer's money gate and error helpers rather than owning them.
`src/lib/meera-api.ts:567` "This is the SECOND route to POST /wallet/escrow/fund"
`src/lib/meera-api.ts:788` "'POST', '/workspaces/me/meera/interactions/option-tapped',"

### 81. Where does a shared type live in two places and disagree?
Three cases. A platform-stats interface is exported from both the shared types module and the admin types module with zero fields in common, each mapping to a different Java record, so importing the wrong one typechecks against nothing. The wallet transaction type union is stale in the shared types file while the api module declares the correct eight-value union inline. And the shared Notification interface disagrees with the wire on three of eight field names, surviving only because it has no importers, while the api module carries a correct third copy with a mapper.
`src/admin/types/admin.types.ts:271` "avgReach: number;"
`src/lib/api.ts:2900` "'ESCROW_REFUND' | 'PLATFORM_FEE' | 'PAYOUT' | 'ADJUSTMENT';"

### 82. Are backend DTO field names snake_case or camelCase, and is the mapping consistent?
Browser-facing DTOs are camelCase and consistently so: there is no naming-strategy configuration in the application config, so Jackson's default holds, and Java records serialize by component name. Snake_case appears only where the counterparty is not the browser — the Meera context DTOs that talk to the Python service, and the integration DTOs for the AI service and Meta — each opting in with explicit property annotations. The copilot DTO comment states the rule outright. So the frontend is correct to assume camelCase everywhere it reads.
`influora-api/src/main/java/com/influora/web/dto/creatorcopilot/CreatorCopilotDtos.java:7` "needed) since this is a browser-facing contract, unlike"

### 83. Which nullable backend fields are typed non-null on the frontend?
The strongest case is the admin creator's Instagram handle: the service mapper writes an explicit null when no Instagram account is connected, on both the list row and the detail row, and both admin types declare it as a plain string. Bio and location are the same shape — nullable columns passed straight through with no coalescing, typed as plain strings, unlike the engagement rate on the same mapper which is deliberately coalesced to zero. On the discovery path the engagement rate has no null guard either and is typed non-null.
`influora-api/src/main/java/com/influora/service/admin/AdminCreatorService.java:565` "instagram != null ? instagram.getHandle() : null,"
`src/admin/types/admin.types.ts:249` "instagramHandle: string;"

### 84. Which required backend fields are typed optional on the frontend?
The deal create payload marks deliverables optional while the Java record annotates the same list as non-empty and valid, so a create call without deliverables typechecks cleanly and always 400s. The broader case is campaign create, whose client signature takes a partial campaign, which makes every required field — title, budget and timeline — optional at the type level, so an empty object compiles.
`src/lib/api.ts:1986` "deliverables?: Array<{ type: string; qty: number }>;"
`influora-api/src/main/java/com/influora/web/dto/deal/DealDtos.java:82` "@Valid List<DeliverableSlot> deliverables,"

### 85. Does any frontend type assert a field the Java record never sends?
Yes, and it is rendered. The dispute row type declares resolutionNotes and resolvedAt, and the Java list-item record has eight components and neither of them, while the client's own comment above the type claims the DTO matches field for field. Both fields feed a visible resolution block on the brand and creator dispute pages, so that block is permanently dead in live mode and appears only against mock fixtures. The repository has a DTO-drift CI check, and this pair is not in its list. A second, milder case is the portfolio item metrics object, which the server's six-component record never sends.
`src/lib/api.ts:5442` "resolutionNotes?: string;"
`influora-api/src/main/java/com/influora/web/dto/dispute/DisputeDtos.java:68` "String reason) {}"

## Runtime and configuration

### 86. Which features change behaviour based on VITE_API_MODE, and what breaks when it is unset?
Everything does. The flag is read once into a mode constant with a strict equality against live, and one predicate derived from it gates every endpoint wrapper in the main client, so unset means the whole app serves fixtures. Unset fails safe for a real production build, because the Vite config throws before emitting, and it fails closed at first login because mock authentication is refused in a production bundle. The hazard is any other build mode: the config guard is gated on the production mode string, so a staging build or a docker build that overrides the argument ships a fully mocked app whose read-only screens all render polished demo data.
`src/lib/api.ts:56` "import.meta.env?.VITE_API_MODE === 'live' ? 'live' : 'mock';"
`vite.config.ts:39` "if (env.VITE_API_MODE !== 'live') {"

### 87. Which screens render mock data in a way indistinguishable from real data?
The demo banner is mounted once app-wide inside the router and returns null in live mode, and almost every screen gates its fixtures on the same predicate. One page does not. The brand creator analytics page imports the demo creator fixtures and has no live check anywhere in the file, so on live production the header renders a hardcoded name, city and verified tick from a fixture, directly above real metric tiles, with the banner correctly suppressed. For any creator id not in the fixture set the header degrades to the raw id string.
`src/pages/brand-creator-analytics.tsx:70` "const creator = demoCreators.find((c) => c.id === creatorId);"
`src/components/DemoModeBanner.tsx:16` "if (isApiLive()) return null;"

### 88. Which env vars does the frontend read that no build pipeline supplies?
Four. The two admin websocket variables, which enable the live-pulse socket and set its url, appear in no Dockerfile argument, no workflow build-arg and no production env file, so that socket is permanently off in every published image. The two walkthrough video urls are the same, and they exist only as commented-out lines in the sample env file, so the walkthrough is silently unreachable in every image. That is the same shape of defect the money flags had before they were declared. Separately, two declared variables are read by nothing, and three read variables escape the environment interface through optional chaining or a cast, so a typo in them would be invisible.
`src/admin/services/websocket.ts:166` "return env.VITE_ADMIN_WS_ENABLED === 'true';"
`src/lib/walkthrough-video.ts:25` "? env.VITE_WALKTHROUGH_VIDEO_BRAND_URL"

### 89. Which backend config keys are read by code but have no placeholder in application.yml?
Twenty-five of the fifty-two annotated keys. All carry inline defaults so nothing fails to boot, but none is settable from a deploy without editing Java. The most consequential is the environment key the boot-time secrets validator uses to decide whether to fail closed on a default database credential; it appears in the config file only inside a comment, so it silently resolves to dev. The deliverable cleanup job's dry-run switch is the same and is therefore permanently dry. The remaining bulk is eighteen rate-limit windows plus two voice timeouts, which sit beside a voice base url that is present, so the block exists and the siblings were simply never added.
`influora-api/src/main/java/com/influora/config/SecretsStartupValidator.java:174` "private String env;"
`influora-api/src/main/java/com/influora/job/DeliverableCleanupJob.java:77` "private boolean dryRun;"

### 90. Is the API base URL configurable, and what is the fallback?
For the two main layers yes, through one environment variable, with an identical localhost fallback duplicated rather than shared even though the Meera layer already imports from the main one. The Meera stream has a second variable with an internal-hostname fallback. The admin layer is not configurable at all: its base is a hardcoded relative path that works in development only because of a Vite proxy. All of it is baked at build time, not run time, and the Dockerfile argument defaults to localhost, so a build with no build-arg produces an image pointing at localhost, caught only by a production-mode config guard.
`Dockerfile:55` "ARG VITE_API_BASE_URL=http://localhost:8080/api/v1"
`src/lib/meera-api.ts:35` "import.meta.env?.VITE_MEERA_STREAM_URL || 'https://ai.influora.internal';"

### 91. What happens to every call if the token refresh fails?
The refresh helper fails closed to null on any of four conditions, the retry wrapper then clears that role's token and returns the original 401, and the caller throws a generic error. Nothing is wired to that clearing — it is a storage write with no subscriber — so the user stays on the screen and only discovers the dead session on the next navigation, when a route guard re-reads storage. The Meera and admin layers have no refresh at all. A latent bug sits alongside: the guards read localStorage only, while a login with remember-me off writes to sessionStorage, so that creator authenticates successfully and is then bounced by the guard.
`src/lib/api.ts:366` "=== 'false' ? sessionStorage : localStorage;"
`src/App.tsx:137` "const isAuthenticated = localStorage.getItem('creator_token');"

### 92. Are there any hardcoded IDs, workspaces or user references in shipped frontend code?
There are no UUID literals, and nearly all synthetic ids sit behind the live-mode gate — mock tokens and user ids in the api module, a workspace id in the members mock, a campaign id in the Meera mock, and fixture ids in the campaigns list. Three are not safely gated. The demo access panel carries a full fabricated identity including a plausible email address. The demo data module's creator identities leak into live rendering through the brand creator analytics page. And the Meera workspace hardcodes a demo campaign id that it then posts to the real escrow fund endpoint. A naming inconsistency also runs through the fixtures, with underscore ids in one module and hyphen ids in another despite a comment claiming they are consistent.
`src/components/feature/meera/MeeraWorkspace.tsx:21` "const MEERA_DEMO_CAMPAIGN_ID = 'meera_demo_campaign'"
`src/components/shared/demo-access-panel.tsx:25` "user: { id: 'u_1', email: 'growth@glownaturals.in', displayName: 'Glow Naturals', userType: 'BRAND' },"

## The endpoints with no caller

### 93. GET /contracts/unsigned — what was it built for and what would consume it?
It was built as the creator's sign-these inbox: the controller rejects any non-creator principal with a WRONG_USER_TYPE 403 and returns the same contract response list the general contracts route returns, filtered to rows the creator has not yet signed. There is zero frontend caller in either api layer; the contracts client exposes only list, get, generate, sign and the pdf link. The consumer that should have used it is the creator dashboard's awaiting-signature count, which instead derives the number by filtering the deals list, on the strength of a stale comment claiming the endpoint does not exist.
`influora-api/src/main/java/com/influora/web/ContractController.java:59` "public ApiResponse<List<ContractResponse>> listUnsigned("
`src/pages/creator-dashboard.tsx:151` "const awaitingSignature = dealRows.filter((d) => d.contractStatus === 'PENDING_SIGNATURES').length;"

### 94. GET /creators/search — how does it differ from GET /creators, and which is the intended one?
They call the identical service method with identical arguments and differ only in what they unwrap. The plain route returns a bare array of creator responses with pagination meta on the envelope; the facets route wraps the same array in an object alongside a filters block carrying the applied filters plus category and follower-range facet counts. The frontend calls the plain route and there is no caller anywhere for the facets route, so the facet counts a filter sidebar would need are computed server-side and thrown away. Spring's literal-over-template precedence means the route is genuinely reachable, just uncalled.
`influora-api/src/main/java/com/influora/web/dto/creator/DiscoveryDtos.java:25` "SearchFiltersMeta filters) {}"
`src/lib/api.ts:1670` "'GET', '/creators', {"

### 95. POST /wallet/escrow/payout — how does it differ from escrow release, and is it reachable at all?
It is not on the wallet controller at all; it is the queuePayout method on the escrow controller, whose class mapping happens to be the wallet escrow prefix. Release moves platform-internal ledger money from the brand's escrow to the creator's wallet, whereas this queues a real RazorpayX bank disbursement and is therefore restricted to Owner and Admin inside the payout service. It is reachable by an authenticated brand Owner or Admin with a direct HTTP call, since the security config has no matcher for that path and falls through to the authenticated catch-all, but it is unreachable from the product because neither api layer declares a client method for it.
`influora-api/src/main/java/com/influora/web/EscrowController.java:139` "payoutService.queuePayout(principal, workspace.getId(), body.milestoneId())"
`influora-api/src/main/java/com/influora/service/PayoutService.java:203` "brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);"

### 96. PUT /deliverables/{id}/metrics — how does it differ from the creator metrics route the UI uses?
It is keyed on a milestone id, takes three aggregate Longs for reach, impressions and engagements plus a link and a proof key, and writes a metric row. The route the UI actually uses is a POST under the creator deliverables prefix, keyed on a deliverable id, taking seven raw social counters, which derives the same aggregates, writes the same metric row, and additionally advances the deliverable's own status. So the PUT route is a duplicate writer into the same table, not a dead feature; the client issues exactly one PUT anywhere and it is the unrelated primary payout-method call.
`influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java:26` "Long reach, Long impressions, Long engagements, String link, String proofScreenshotR2Key) {}"
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:570` "deliverableMetricRepository.save(metric);"

### 97. GET /wallet/balance — what does it return that GET /wallet does not?
Two things: the wallet row's own id, which is the only place a client could ever learn it, and a currency string. What it lacks is pendingPayouts. The two balance numbers are the same figures under different names, and the source states that alias mapping explicitly. There is no caller for the balance route anywhere in the client; both wallet pages read the summary route, which is exactly why they have no currency to render and fall back to a hardcoded rupee.
`influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java:44` "String walletId,"
`influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java:55` "availableBalance} ≡ {@code balance}"

### 98. POST /workspace/members/accept and the invite routes — is workspace invitation usable end to end?
No. Of the seven routes on the member controller only two have frontend callers, list and invite; accept, deactivate, list-invites, revoke-invite and switch have none. There is no accept page, no accept client method and no route in the router matching an invite. The invite email names the workspace and role but the registry entry uses the three-argument spec, which leaves the call-to-action label and url null, so no button is rendered and the raw token never appears; the token surfaces only in a dev log line. A brand can send an invite and nobody can accept one.
`influora-api/src/main/java/com/influora/web/WorkspaceMemberController.java:62` "workspaceMemberService.acceptInvite(principal, body.inviteToken());"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:462` "Workspace invite for {} — POST /workspace/members/accept token: {}"

### 99. POST /notifications/unsubscribe — is email unsubscribe reachable by a recipient?
Not through that route, but yes through a different one. The POST route is authenticated and one-way, able to unsubscribe but never re-subscribe, and it has no frontend caller because the settings screens use the two-way preferences route instead, of which it is a strict subset. Recipients are served by the unsubscribe-link GET, which is explicitly permitAll in the security config and is injected as a footer link into every email except the OTP and password-reset templates. The gap is that the link is only built when a user id is present, so the invite-a-new-user email ships with no unsubscribe footer at all.
`influora-api/src/main/java/com/influora/config/SecurityConfig.java:146` ".requestMatchers(HttpMethod.GET,"
`influora-api/src/main/java/com/influora/integration/msg91/Msg91EmailClient.java:149` "String unsubscribeUrl = userId != null ? buildUnsubscribeUrl(userId, templateKey) : null;"

### 100. GET /users/{id} and PATCH /users/{id} — what uses them, and are the /me routes their replacement?
Those two routes do not exist. The user controller is thirty-eight lines and declares no path variable at all; its only mappings are a principal-scoped GET and PATCH on the me suffix, so there is no identifier-addressed user route and therefore no direct object reference to abuse. Nothing in the client calls anything under that prefix, so the whole controller is unreachable from the app. The separate me controller family does have callers for password change, account deletion and the creator profile, but the creator-profile route is creator-only and returns a different record, so the user profile payload of email, user type, status, verification flags, timezone and created-at has no replacement and a brand user has no way to change their own display name or timezone.
`influora-api/src/main/java/com/influora/web/UserController.java:33` "public ResponseEntity<ApiResponse<UserProfileDto>> updateMe("
`src/lib/api.ts:3246` "'PATCH', '/me/creator-profile',"
