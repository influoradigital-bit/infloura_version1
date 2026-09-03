# 🧪 Test Report: T\-CREATORCONNECT\-0902 Tester x Priya QA

- **Date:** 2026\-09\-02
- **Target:** Discover Instagram tab, /creators/external/\*, /admin/creator\-connections/\*, ExternalCreatorLinkService, campaign ?creatorId= handoff, CreatorMarketplaceClient \+ deploy config
- **Stages run:** Functional \(Tester x Priya Q\&A, 35 questions\), Build \(Meera, prior stage\)
- **Health:** 0%
- **Verdict:** FAIL ❌

## Summary

| Severity | Count |
|----------|-------|
| Critical | 2 |
| High | 8 |
| Medium | 17 |
| Low | 4 |

## Findings by tester

### Priya — Functional

#### [Critical] \[Q5\.4\] Identity spoof: claiming an Influora username equal to an external Instagram handle links the impostor to the external creator row and emails the brand to hire them
- **Where:** influora\-api/src/main/java/com/influora/service/CreatorProfileService\.java:180
- **Issue:** applyUsername feeds an unverified Influora vanity handle into ExternalCreatorLinkService\.onCreatorIdentified, which matches it against external\_creators\.ig\_username \(:79\-83\)\. Reachable by any authenticated creator via PATCH profile \(CreatorProfileService\.java:62\-68\) or portfolio update \(PortfolioService\.java:204\-210\) with no Meta connection and no proof of controlling the Instagram account\. The relink guard at ExternalCreatorLinkService\.java:89\-102 only fires when linkedCreatorProfileId is already set, which is never true for an UNVERIFIED/INVITED row\. Result: the external row flips to JOINED with the impostor's linked\_creator\_profile\_id, every open connection request flips to JOINED, and the brand receives 'X joined Influora' with campaign\_url carrying the impostor's creatorId \(NotificationListener\.java:683\-695\) — an impersonation that terminates in a paid collaboration\.
- **Fix:** Remove the onCreatorIdentified call at CreatorProfileService\.java:180 — an Influora username is not an Instagram identity and must never be a match key\. Restrict the hook to Meta\-verified sources only: MetaTokenStorage\.java:346 \(id \+ username from the OAuth exchange\) and PortfolioService\.java:367\-369 \(handle from a real Meta sync, with the ig\_account\_id threaded through per Q5\.3\)\. If a username\-only link is still wanted, gate it on the caller holding a non\-revoked MetaOAuthToken whose igBusinessAccountId resolves to that handle\. Additionally harden ExternalCreatorLinkService\.java:89\-102 to require an ig\_account\_id match before ever setting linked\_creator\_profile\_id on a row that has none\.

#### [Critical] \[Q7\.4\] Empty META\_CREATOR\_MARKETPLACE\_ENABLED from compose fails ConfigurationProperties binding — API will not boot on the live Hostinger VPS
- **Where:** deploy/hostinger/docker\-compose\.hostinger\.yml:191
- **Issue:** Compose map\-form $\{META\_CREATOR\_MARKETPLACE\_ENABLED\} with no :\- default always SETS the container var, empty when the \.env lacks it\. Spring treats set\-but\-empty as set, so $\{META\_CREATOR\_MARKETPLACE\_ENABLED:false\} at application\.yml:391 resolves to "" and never uses the default; binding "" to the primitive boolean at MetaApiProperties\.java:60 throws BindException \-\> ConversionFailedException \-\> 'A null value cannot be assigned to a primitive type'\. Verified on spring\-boot 3\.3\.5 / spring\-core 6\.1\.14 \(influora\-api/pom\.xml:10\) under JDK 21\. The whole API fails to start on the next redeploy of the live Hostinger VPS, whose \.env predates this task\. Same bare form at deploy/utho/docker\-compose\.utho\.yml:225\.
- **Fix:** Use META\_CREATOR\_MARKETPLACE\_ENABLED: $\{META\_CREATOR\_MARKETPLACE\_ENABLED:\-false\} and ADMIN\_NOTIFICATION\_EMAIL: $\{ADMIN\_NOTIFICATION\_EMAIL:\-\} in BOTH compose files, matching CREATOR\_COPILOT\_ENABLED at hostinger:154 / utho:175\. Belt\-and\-braces: make CreatorMarketplace\.enabled a Boolean with a null/blank\-coalescing setter, the pattern MetaApiProperties already uses at L157, L174, L182 and L190 — that convention would have absorbed this on its own\.

#### [High] \[Q1\.4\] Handle rename permanently breaks lookup with an unrecoverable 409; concurrent first\-lookup race loses one caller
- **Where:** influora\-api/src/main/java/com/influora/service/ExternalCreatorService\.java:254\-264
- **Issue:** lookup resolves by ig\_username only, then applies bd\.id\(\)\. If another row already holds that ig\_account\_id \(creator renamed on Instagram\) the save violates uk\_external\_creators\_ig\_account; two brands looking up the same new handle concurrently violate uk\_external\_creators\_username\. Both become a 409 DATA\_INTEGRITY\_VIOLATION rendered to the brand as 'data conflict', with no retry and no reconciliation \- the renamed creator can never be looked up again\.
- **Fix:** In lookup, resolve by externalCreatorRepository\.findByIgAccountId\(bd\.id\(\)\) \(ExternalCreatorRepository\.java:22\) before falling back to findByIgUsernameIgnoreCase, and update ig\_username on that row \(rename reconciliation\)\. Wrap the save in a catch of DataIntegrityViolationException that re\-reads by ig\_account\_id then ig\_username and returns the winning row\.

#### [High] \[Q2\.4\] API outage on the Instagram tab renders the honest\-empty state: "No Instagram creators sourced yet" instead of an error
- **Where:** src/components/brand/discover/creator\-discovery\.tsx:2165\-2167
- **Issue:** fetchList treats only err\.status === 503 as unavailable \(L2062\), but GET /creators/external never returns 503 \(ExternalCreatorService\.java:100\-125 swallows all Marketplace failures at L164\-173\)\. A 502 from nginx becomes ApiError\('SERVER\_UNAVAILABLE', status 502\) via parseEnvelope \(api\.ts:580\-595\) and a dropped connection is a TypeError; both clear listCreators and set listError, after which showEmpty \(L2165\-2167\) is true and the tab shows data\-testid="instagram\-empty" / "No Instagram creators sourced yet"\. listError is rendered only in the non\-empty branch \(L2274\-2276\), so it never displays on a first\-load failure — the brand is told the data does not exist when the server is down\. F\-0259/F\-0260 empty\-state\-honesty class\.
- **Fix:** Add a distinct listFailed state: treat err\.code === 'SERVER\_UNAVAILABLE', status 502/503/504, and non\-ApiError network errors as unavailable/failed, and gate showEmpty on \`\!listError \&\& \!listFailed\` so a failed load renders an error panel with Retry, never the empty state\. Add a vitest that a 502 and a rejected fetch each render the error state and not instagram\-empty\.

#### [High] \[Q3\.1\] Admin connection\-request email is dark on every deploy target \(ADMIN\_NOTIFICATION\_EMAIL blank/unset\)
- **Where:** deploy/utho/generate\-env\.sh:141
- **Issue:** generate\-env\.sh writes ADMIN\_NOTIFICATION\_EMAIL= and both compose files pass $\{ADMIN\_NOTIFICATION\_EMAIL\} with no :\- default, so NotificationListener\.java:638\-644 WARNs and skips\. Step 3 of the flow \(enquiry reaches the admin team\) never fires; the admin console page is the only way a request is ever seen\.
- **Fix:** Set a real address in the deploy \.env \(and a :\-default in both compose files\), then prove one delivery end\-to\-end; until then treat the admin console as the sole notification channel and say so in the handoff\.

#### [High] \[Q3\.2\] Join notification goes to a stale requester and is silently lost if that user was removed
- **Where:** influora\-api/src/main/java/com/influora/domain/entity/CreatorConnectionRequest\.java:113\-121
- **Issue:** reopen\(\) keeps the original requestedByUserId, so after a DECLINED reopen by a different member the brand\.connected\_creator\_joined email \(ExternalCreatorLinkService\.java:128\) reaches the wrong colleague; if that user is gone, emailOf returns null, NotificationService\.java:128\-131 queues nothing, and joined\_notified\_at is stamped regardless — the headline 'creator joined' promise is dropped with only a WARN\.
- **Fix:** Take requestedByUserId in reopen\(message, userId\) and set it from principal; in the listener, fall back to the workspace owner/admins when emailOf\(userId\) is null, and only stamp joined\_notified\_at when an email was actually queued\.

#### [High] \[Q4\.1\] Admin invite/contact/decline failures are silent — dialog closes as if they succeeded
- **Where:** src/admin/pages/CreatorConnectionsPage\.tsx:275\-284
- **Issue:** apiRequest returns \{success:false\} instead of throwing \(api\-contracts\.ts:95\-105\), so onSuccess fires for 404/409/500 too\. It skips invalidation but still calls setActiveAction\(null\) at :282\. There is no onError, no success toast, and no failure banner — an invite that never sent looks identical to one that did\.
- **Fix:** In onSuccess, branch on res\.success: on false surface res\.error in the dialog and keep it open; add onError for network failures; add the 'Invitation sent to \{email\}' success toast the contract requires\.

#### [High] \[Q4\.4\] Admin import accepts unvalidated handles: garbage is persisted brand\-visibly and one long line rolls back the whole batch
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService\.java:318\-359
- **Issue:** normalizeUsername \(:553\-556\) applies neither ExternalCreatorService\.USERNAME\_PATTERN \(ExternalCreatorService\.java:58\) nor the 80\-char column limit \(ExternalCreator\.java:44\)\. Invalid handles are interpolated raw into the Graph path \(InstagramInsightsClient\.java:117\-122\) and, after Meta rejects them, still saved at :359 and counted as imported; an 81\-char handle fails at flush, returning 409 and discarding all 50\. There is no delete endpoint \(AdminExternalCreatorController\.java has only GET and POST /import\), so a bad stub is permanent and shows in brand Discover\.
- **Fix:** Promote USERNAME\_PATTERN to a shared validator and apply it plus a length\<=80 check in the import loop, routing rejects into ImportResult\.skipped instead of persisting them; add an audit\-logged DELETE /admin/external\-creators/\{id\} guarded when connection requests reference the row\.

#### [High] \[Q6\.2\] Draft save invites the creator, exposing an unpublished campaign and making the draft undeletable
- **Where:** src/components/brand/campaigns/campaign\-form\.tsx:524
- **Issue:** The post\-create invite is gated on \`\!isEditing \&\& creatorIdParam\`, not on the submitted status, so \`handleSubmit\('DRAFT'\)\` \(L481, bound at L1499\-1503\) creates an INVITED Collaboration on a DRAFT campaign — contradicting both banners \('invited when you publish', campaign\-form\.tsx:665\-666, brand\-new\-campaign\.tsx:112\-113\)\. DealService\.list \(DealService\.java:1383\) applies no campaign\-status filter, so the creator sees the unpublished draft as a deal with a system message\. Deleting the draft then 409s on fk\_collab\_campaign \(V6\_\_creators\_collaborations\.sql:71, GlobalExceptionHandler\.java:110\-115\) with an opaque message\.
- **Fix:** Gate the invite on \`status === 'ACTIVE'\` at campaign\-form\.tsx:524, and stash \`creatorIdParam\` so a later publish still invites; independently, add a campaign\-status guard in CreatorDiscoveryService\.invite \(L452\-505\) rejecting DRAFT with a typed error, and exclude DRAFT campaigns from the creator\-side deal list\.

#### [High] \[Q6\.4\] Picking HYPE drops ?creatorId= after the banner promised the invite
- **Where:** src/pages/brand\-new\-campaign\.tsx:176
- **Issue:** choose\(\) navigates to '/brand/campaigns/new/hype' without forwarding the query string, and brand\-new\-hype\-campaign\.tsx has no useSearchParams/creatorId handling and no post\-create invite \(create at L219, navigate away at L221\)\. The handoff banner shown one screen earlier \(brand\-new\-campaign\.tsx:222\-225\) has already told the brand the creator 'will be invited when you publish', so the promise is silently broken for the whole HYPE path\.
- **Fix:** Forward the param — navigate\(\`/brand/campaigns/new/hype$\{creatorId ? \`?creatorId=$\{creatorId\}\` : ''\}\`\) — and mirror campaign\-form\.tsx:524\-543's post\-create invite in brand\-new\-hype\-campaign\.tsx after L219; if HYPE is intentionally not invite\-capable, suppress the banner for that card and say so on the type tile\.

#### [Medium] \[Q1\.1\] Business Discovery happy path has zero test or recorded\-run evidence
- **Where:** influora\-api/src/test/java/com/influora/service/ExternalCreatorServiceTest\.java:157
- **Issue:** The only lookup test is the Meta\-unconfigured 503 case\. No test, fixture, or captured run proves a non\-null business\_discovery response is parsed into BusinessDiscoveryResponse and persisted at ExternalCreatorService\.java:262\-264\. A field\-name or shape mismatch in the DTO would ship undetected\.
- **Fix:** Add a WireMock/recorded\-fixture test that feeds a captured Graph business\_discovery body through InstagramInsightsClient\.businessDiscovery and asserts the resulting external\_creators row \(ig\_account\_id, followers, media\_count\) before App Review\.

#### [Medium] \[Q1\.3\] Borrowed creator token makes brand lookup traffic throttle that creator's own metrics polling
- **Where:** influora\-api/src/main/java/com/influora/service/ExternalCreatorService\.java:290\-305
- **Issue:** resolveBusinessDiscoveryCaller picks an arbitrary creator's FACEBOOK\_LOGIN token; MetaGraphApiClient keys rate limiting on that creator's igBusinessAccountId \(MetaGraphApiClient\.java:110\), the same key MetricsPollingJob throttles on \(MetricsPollingJob\.java:204\) and marks limited \(L253\)\. Brand Discover volume can silently stall an unrelated creator's analytics ingestion, and no Meta platform\-terms review of cross\-user token reuse exists in wiki/\.
- **Fix:** Introduce an Influora\-owned IG Business system caller \(new influora\.meta\.system\-ig\-\* properties \+ stored token\) as the preferred caller ahead of the creator fallback, and escalate the cross\-user\-token question to Swapnil for a platform\-terms ruling before App Review\.

#### [Medium] \[Q1\.5\] Instagram tab ships no q/follower filters \(contract TASKS\.md:157\) and the backend filter would silently hide every un\-enriched row
- **Where:** src/components/brand/discover/creator\-discovery\.tsx:2057
- **Issue:** fetchList sends only page and limit; no filter control exists anywhere in InstagramCreatorsTab \(2022\-2300\), so the contracted follower filters are absent\. When added, ExternalCreatorSpecs\.java:33\-39 emits followers \>= ? / \<= ?, which is UNKNOWN for NULL \- every ADMIN\_IMPORT stub and every META\_MARKETPLACE row \(followers always null, ExternalCreatorService\.java:192\-200\) would vanish with no explanation\.
- **Fix:** Add the search \+ follower\-range controls to InstagramCreatorsTab and pass q/minFollowers/maxFollowers through fetchList; in ExternalCreatorSpecs wrap the follower predicates as cb\.or\(cb\.isNull\(followers\), cb\.ge\(\.\.\.\)\) or gate them behind an explicit 'only creators with known followers' toggle, and mark unknown\-follower cards the way engagement renders '\-' rather than 0\.

#### [Medium] \[Q2\.3\] 409 CREATOR\_ALREADY\_ON\_INFLUORA carries linkedCreatorProfileId that the FE type system discards — brand hits a dead\-end toast
- **Where:** src/lib/api\.ts:215\-224
- **Issue:** ApiErrorBody\.creatorAlreadyOnInfluora \(ApiErrorBody\.java:62\-64\) puts linkedCreatorProfileId on the wire and api\.ts:1918\-1922 tells callers to use it, but ApiErrorPayload \(api\.ts:215\-224\) never declares the field and both ApiError throw sites \(api\.ts:562\-567, :631\-636\) populate \`details\` only via extractInsufficientFundsDetails \(api\.ts:253\-264\), so the id is unreachable\. submitConnect \(creator\-discovery\.tsx:2154\-2160\) therefore shows a generic destructive toast and the card still reads "Connect this creator"\. TASKS\.md L87 specified this payload for a create\-campaign CTA that does not exist\.
- **Fix:** Add \`linkedCreatorProfileId?: string\` to ApiErrorPayload, widen ApiError\.details to a discriminated union \(or add a sibling \`creatorAlreadyOnInfluora\` extractor next to extractInsufficientFundsDetails\), and branch in submitConnect on err\.code === 'CREATOR\_ALREADY\_ON\_INFLUORA' to patch the card to JOINED and toast with a Create\-campaign action\. If the CTA is not wanted, delete the field from ApiErrorBody and the api\.ts doc comment instead of shipping an unread payload\.

#### [Medium] \[Q3\.3\] Admin connection\-request email has no retry: a transient SMTP failure loses the notification permanently
- **Where:** influora\-api/src/main/java/com/influora/service/notification/NotificationListener\.java:652\-661
- **Issue:** The @Async listener calls Msg91EmailClient directly; sendTemplateEmail returning false \(Msg91EmailClient\.java:132\-148\) produces one log\.error and nothing else — no outbox row, no retry, no marker on creator\_connection\_requests\. The admin console is the only recovery and nothing tells anyone to look\.
- **Fix:** Queue an EmailOutbox row with userId=null \(the pre\-account recipient path the client already supports\) so EmailWorker retries with backoff, keeping the in\-app half suppressed as today\.

#### [Medium] \[Q3\.4\] Concurrent connect returns 409 DATA\_INTEGRITY\_VIOLATION instead of the contracted idempotent 200
- **Where:** influora\-api/src/main/java/com/influora/service/ExternalCreatorService\.java:336\-361
- **Issue:** Double\-click/two\-tab POSTs race past findByWorkspaceIdAndExternalCreatorId; the loser violates uk\_ccr\_workspace\_creator and falls through to GlobalExceptionHandler\.java:110\-115 \(409 DATA\_INTEGRITY\_VIOLATION\)\. The brand sees 'Could not send request' and a card still offering 'Connect this creator' although the request was created\. Not a 500, and only one admin email \(the loser rolls back before the AFTER\_COMMIT listener\)\.
- **Fix:** Wrap the save in try/catch \(DataIntegrityViolationException\) as CreatorDiscoveryService\.java:493\-499 does, re\-read the row, and return it as the idempotent 200 the contract specifies; also disable the dialog's Send button on the pending promise\.

#### [Medium] \[Q3\.5\] No per\-workspace cap or rate limit on POST /creators/external/\{id\}/connect
- **Where:** influora\-api/src/main/java/com/influora/web/ExternalCreatorController\.java:58\-66
- **Issue:** connect accepts any external\_creators id regardless of source/status \(ExternalCreatorService\.java:313\-328\) and list exposes every row unfiltered \(L111\); one brand can create a request per creator across the whole table, each publishing an admin email \(L361\-373\)\. No annotation, filter or counter caps this\.
- **Fix:** Add a config\-driven per\-workspace daily cap on new/reopened requests returning 429, plus digest/batching of admin\.creator\_connection\_requested when several fire inside a window\.

#### [Medium] \[Q4\.2\] Invite email hard\-codes brand\_name to 'A brand' although the workspace name is already resolved
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService\.java:241
- **Issue:** The creator\.join\_invitation subject templates \{\{brand\_name\}\} \(EmailTemplateRegistry\.java:337\) but invite\(\) passes the literal 'A brand', so every invite reads 'A brand wants to work with you on Influora'\. The request row has workspaceId and toDtos already loads Workspace\.getName\(\) at :504 — the data was in hand\.
- **Fix:** Pass the CreatorConnectionRequest into sendJoinInvitationEmail, look up the Workspace by request\.getWorkspaceId\(\), and use its name; fall back to 'A brand' only when the workspace row is gone\.

#### [Medium] \[Q4\.3\] Bulk import runs up to 50 blocking Graph calls inside one transaction and reports rate\-limited handles as ordinary imports
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService\.java:295\-366
- **Issue:** @Transactional importHandles holds a pooled DB connection across up to 50 synchronous Business Discovery round\-trips\. MetaRateLimitException is a MetaApiException subclass \(MetaRateLimitException\.java:6\), so the catch at :354 swallows throttling; once MetaGraphApiClient's pre\-flight threshold \(:109\-113\) trips, every remaining handle silently becomes an un\-enriched stub and ImportResult still counts it as imported, with no retry surface\.
- **Fix:** Persist the stubs in a short transaction, then enrich out of band \(a job keyed on lastSyncedAt IS NULL\)\. Catch MetaRateLimitException separately, stop the loop, and return the un\-enriched handles so the admin knows to retry\.

#### [Medium] \[Q4\.5\] AdminConnection TS type declares four fields non\-null that the Java record emits as null
- **Where:** src/admin/types/admin\.types\.ts:617\-626
- **Issue:** brandName \(:617\), requestedByEmail \(:619\), igUsername \(:621\) and creatorStatus \(:626\) are typed \`string\`, but toDtos emits null for each when the workspace, user or external\-creator row is missing \(AdminCreatorConnectionService\.java:504,506,508,513\)\. Only external\_creator\_id has an FK \(TASKS\.md L73\)\. CreatorConnectionsPage\.tsx:447 calls c\.igUsername\.charAt\(0\) with no guard — the same FE\-asserts\-a\-field\-the\-record\-may\-not\-send class as F1/PHONE\-0829 P3\.
- **Fix:** Widen all four to \`\| null\` in admin\.types\.ts and render explicit fallbacks \(an em dash for brandName at :442 and :188, a safe initial at :447\); separately, page the external\-creators table and wire its status/q filters, and replace the in\-memory search IN\-list \(:434\-445\) with a join\.

#### [Medium] \[Q5\.2\] Meta token refresh re\-runs the JOINED hook: joined\_at drifts, Discover re\-sorts, and a reopened request re\-emails the brand
- **Where:** influora\-api/src/main/java/com/influora/domain/entity/ExternalCreator\.java:231\-236
- **Issue:** MetaTokenRefreshService\.java:190\-201 calls the 8\-arg storeCreatorToken every \~55 days with igBusinessAccountId set, so ExternalCreatorLinkService\.java:106 calls markJoined again on an already\-JOINED row\. markJoined re\-stamps joinedAt and updatedAt unconditionally, so \(a\) the real join date is lost, and \(b\) ExternalCreatorService\.java:115 sorts updatedAt DESC, so a token refresh bumps that creator to the top of every brand's Discover list\. Separately, CreatorConnectionRequest\.reopen:119 nulls joinedNotifiedAt, which is the exact field NotificationListener\.java:675\-681 uses for idempotency — a reopened request will be re\-flipped and re\-emailed by a background refresh\.
- **Fix:** Make markJoined idempotent: no\-op \(or only set linkedCreatorProfileId\) when status is already JOINED and linkedCreatorProfileId equals the incoming id, so joinedAt/updatedAt are set once\. Preserve joinedAt the way MetaTokenStorage preserves createdAt \(F\-0173\)\. Do not clear joinedNotifiedAt in reopen\(\); keep it as a permanent 'this brand has already been told' marker, or gate the hook on a real identity change rather than every token write\.

#### [Medium] \[Q5\.3\] JOINED hook discards the IG business account id at the one site that has it from a real Meta sync
- **Where:** influora\-api/src/main/java/com/influora/service/portfolio/PortfolioService\.java:367\-369
- **Issue:** syncPlatforms already resolves tokenRow\.getIgBusinessAccountId\(\) at PortfolioService\.java:276 before calling upsertPlatformStat at :313, but the hook call at :367\-369 passes igAccountId=null, so ExternalCreatorLinkService falls back to the case\-insensitive ig\_username branch \(:79\-83\)\. That branch cannot match a renamed handle, cannot match a handle containing a dot after normalization, and is the weaker of the two keys\. The strongest available identity signal in the whole feature is thrown away\.
- **Fix:** Thread the resolved igBusinessAccountId from syncPlatforms \(PortfolioService\.java:276\) into upsertPlatformStat and pass it as the third argument at :367\-369, so the hook takes the exact ig\_account\_id branch\. Keep metric\.getUsername\(\) as the secondary key\.

#### [Medium] \[Q5\.5\] Invite signup\_url's handle/ref params are never read at registration, and the hook's 'never fail the caller' catch cannot survive a flush\-time constraint violation
- **Where:** src/pages/creator\-register\.tsx:2
- **Issue:** AdminCreatorConnectionService\.java:244\-245 emits /creator/register?ref=influora\-invite\&handle=\{igUsername\}, but creator\-register\.tsx imports only useNavigate and Link from react\-router\-dom and never reads searchParams, so the invite context is discarded — the JOINED flip depends entirely on the creator later connecting Meta\. Separately, ExternalCreatorLinkService\.java:135\-143 swallows the exception but a flush failure has already marked the transaction rollback\-only, so the @Transactional proxy at :63 throws UnexpectedRollbackException at commit and the Meta OAuth callback 500s regardless\. ExternalCreatorLinkServiceTest\.java:34 is pure Mockito with no EntityManager, so this is untested\.
- **Fix:** Issue a signed single\-use invite token bound to the external\_creators row \(never trust a bare ?handle=\), read it in creator\-register\.tsx, pass it to the register call, and consume it server\-side to link the row — the claim must be server\-verified, not URL\-supplied\. For the hook, run it in REQUIRES\_NEW \(or via an AFTER\_COMMIT listener on the caller's transaction\) so its failure cannot poison the caller's transaction, and add a @DataJpaTest that inserts a conflicting ig\_account\_id row and asserts the caller's write still commits\.

#### [Medium] \[Q6\.5\] Handoff banner shows the Influora username while every upstream surface says @igUsername
- **Where:** src/components/brand/campaigns/campaign\-form\.tsx:284
- **Issue:** The banner resolves the handle via api\.creators\.getProfile\(\)\.username \(also brand\-new\-campaign\.tsx:100\), i\.e\. the Influora username, while the Discover card and both connect/joined emails identify the creator by @igUsername\. The two differ whenever the creator's Influora username differs from their IG handle, and differ necessarily when the IG handle contains a dot \(UsernameUtils\.VALID allows only \[a\-z0\-9\_\]\), so a brand arriving from the '@foodie\.mumbai joined' email reads a banner naming a different account\.
- **Fix:** Append the external context to the handoff URL at creator\-discovery\.tsx:1997 \(\`\&ig=\<igUsername\>\&crq=\<connectionRequestId\>\`\) and in the joined\-email link, and have the banner prefer the \`ig\` param over profile\.username; add the click test described above covering both the banner copy and the post\-create invite\.

#### [Medium] \[Q7\.2\] Marketplace upsert discards the IG account id, leaving META\_MARKETPLACE rows matchable only by username
- **Where:** influora\-api/src/main/java/com/influora/service/ExternalCreatorService\.java:196
- **Issue:** upsertFromMarketplace calls applySync only and never applyIgAccountId\(creator\.id\(\)\), although the DTO carries id \(CreatorMarketplaceCreatorsResponse\.java:20\) and the other two upsert paths do call it \(ExternalCreatorService\.java:262, ExternalCreatorLinkService\.java:104\)\. Every Marketplace\-sourced row therefore has ig\_account\_id NULL and can only ever be joined by the weak case\-insensitive username match, which a handle rename breaks and an impostor can claim\. is\_account\_verified and insights are parsed and dropped with no destination\.
- **Fix:** Call external\.applyIgAccountId\(creator\.id\(\)\) in upsertFromMarketplace before save \(guarding the existing uk\_external\_creators\_ig\_account collision the same way the Business Discovery path must\), and either persist is\_account\_verified or drop it and insights from the FIELDS list so the request stops asking for data nothing stores\.

#### [Medium] \[Q7\.3\] Marketplace enrichment holds a DB connection across two synchronous Graph calls inside @Transactional list
- **Where:** influora\-api/src/main/java/com/influora/service/ExternalCreatorService\.java:100\-157
- **Issue:** list\(\) is @Transactional; firstUsableBrandToken \(L130\) acquires the Hikari connection, then resolvePageAccessToken \(L145\) and creatorMarketplaceClient\.search \(L153\) each make a Meta round\-trip while it is held, on every page of the brand Discover tab\. A slow Meta response exhausts the shared pool and degrades the entire API, not just this endpoint\. Unreachable today only because the flag defaults false\.
- **Fix:** Precondition on flipping META\_CREATOR\_MARKETPLACE\_ENABLED: cache the resolved Page access token per workspace \(it does not rotate independently of the user token\) and move upsertFromMarketplace out of the request transaction into an async job, so list\(\) only ever reads external\_creators\.

#### [Medium] \[Q7\.5\] findByIgUsernameIgnoreCase forces LOWER\(\) on ig\_username, defeating uk\_external\_creators\_username
- **Where:** influora\-api/src/main/java/com/influora/repository/ExternalCreatorRepository\.java:24
- **Issue:** Spring Data derives lower\(e\.igUsername\) = lower\(?1\) from the IgnoreCase keyword; a function on the column makes the unique index at migration V20260902120000 L42 unusable, so every lookup full\-scans external\_creators\. The column is already utf8mb4\_unicode\_ci \(migration L45\), i\.e\. case\-insensitive by collation, so IgnoreCase adds nothing\. Hit on the Marketplace upsert path \(ExternalCreatorService\.java:188\) and the join hook\.
- **Fix:** Rename to findByIgUsername \(usernames are already normalised lower\-case, no leading @, per migration L24 and normalizeUsername\) so the equality predicate hits uk\_external\_creators\_username directly; the ci collation preserves the case\-insensitive semantics\.

#### [Low] \[Q1\.2\] firstUsableBrandToken does not filter authPath, so a future INSTAGRAM\_LOGIN brand row would 503 instead of falling back
- **Where:** influora\-api/src/main/java/com/influora/service/ExternalCreatorService\.java:175\-179
- **Issue:** Business Discovery and /me/accounts are FACEBOOK\_LOGIN\-only \(InstagramInsightsClient\.java:112\-113, MetaGraphApiClient\.java:92\-94\), but firstUsableBrandToken selects on revoked\+expiry alone\. Unreachable today only because no writer sets authPath on a workspace\-scoped row; a future brand IG\-Login flow silently breaks lookup and Marketplace enrichment with a misleading 503\.
- **Fix:** Add \.filter\(t \-\> t\.getAuthPath\(\) == MetaAuthPath\.FACEBOOK\_LOGIN\) to firstUsableBrandToken, mirroring the creator\-side filter at ExternalCreatorService\.java:293, so an unusable row falls through to the creator fallback\.

#### [Low] \[Q2\.1\] external\_creators\.engagement\_rate is write\-never: the Discover card's Engagement tile can only ever render "—"
- **Where:** influora\-api/src/main/java/com/influora/service/ExternalCreatorService\.java:196\-202,263
- **Issue:** All three applySync call sites \(ExternalCreatorService\.java:196\-202 Marketplace, :263 Business Discovery, AdminCreatorConnectionService\.java:344\-352 admin import\) pass null for engagementRate, and applySync \(ExternalCreator\.java:200\) only writes on non\-null\. The column, the DTO field \(ExternalCreatorDtos\.java:27\), the TS field \(api\.ts:1835\) and a third of the card's stat grid \(creator\-discovery\.tsx:1976\-1980\) are dead weight\. Honest per F\-0259, but a stat tile no code path can populate\.
- **Fix:** Either drop the Engagement tile from ExternalCreatorCard for external rows \(leave the column for a future Marketplace insights mapping\), or populate it in tryEnrichFromMarketplace from the Marketplace \`insights\` field the FIELDS list already requests but discards\.

#### [Low] \[Q2\.2\] Half\-guarded JOINED card: "Create campaign" checks linkedCreatorProfileId, "View profile" links to /brand/creators/ with an empty id
- **Where:** src/components/brand/discover/creator\-discovery\.tsx:1993
- **Issue:** L1952 ORs status==='JOINED' with the server's verifiedWithInfluora, admitting a JOINED\-but\-unlinked row the server flag excludes\. The Create\-campaign button defends against it \(L1996\-2001\) but the View\-profile Link at L1993 falls back to an empty id and navigates to a dead route\. The state is unreachable through code \(ExternalCreator\.markJoined, ExternalCreator\.java:231\-236, always sets both\), so this is defensive\-code inconsistency, not a live break\.
- **Fix:** Drop the OR and trust the server flag: \`const verified = creator\.verifiedWithInfluora;\`\. If the defensive OR is kept deliberately, render "View profile" only when linkedCreatorProfileId is non\-null, matching the Create\-campaign guard\.

#### [Low] \[Q2\.5\] Brand connection\-request panel renders no dates: handledAt is on the wire and unused, invitedAt is on no brand DTO at all
- **Where:** src/components/brand/discover/creator\-discovery\.tsx:2213\-2219
- **Issue:** ConnectionRequestResponse already ships createdAt \(ExternalCreatorDtos\.java:45\) and handledAt \(:47\), mirrored at api\.ts:1858,1860, but the My\-connection\-requests panel \(creator\-discovery\.tsx:2205\-2222\) renders only the handle and the status label — a brand cannot tell a 3\-week\-stale CONTACTED from yesterday's\. Separately, external\_creators\.invited\_at \(set in ExternalCreator\.java:225\-227\) is absent from both brand DTOs \(ExternalCreatorDtos\.java:19\-36 and :38\-49\), so "an invite was actually emailed" never reaches the brand, and the amber badge collapsing UNVERIFIED/INVITED \(L1904\-1920\) has no signal to split on\.
- **Fix:** Render relative createdAt and handledAt on each request row \(no backend change needed\)\. Add \`Instant invitedAt\` to ExternalCreatorResponse, populate it in toResponse \(ExternalCreatorService\.java:413\-435\), mirror it in api\.ts's ExternalCreator, and give INVITED its own badge copy \("Invited to Influora"\) with the date\.

## Next steps
- Route Critical/High blockers to Ananya (frontend) / Vikram (backend) via Arjun.
- Escalate Critical to Swapnil via Kavya/Priya.
- Re-run the same stage after fixes before final PASS.

_Generated by the `tester` skill._