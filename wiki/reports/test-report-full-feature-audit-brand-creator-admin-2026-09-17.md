# 🧪 Test Report: full\-feature\-audit\-brand\-creator\-admin

- **Date:** 2026\-09\-17
- **Target:** Whole repo, code\-only \(no \.md evidence\): src/ \(React\), influora\-api/ \(Spring Boot\), influora\-ai/ \(Python\)\. Branch feat/meera\-creator\-phase\-e, working tree as on disk incl\. uncommitted edits\.
- **Stages run:** Build, Functional, Security, AI
- **Health:** 0%
- **Verdict:** FAIL ❌

## Summary

| Severity | Count |
|----------|-------|
| Critical | 4 |
| High | 39 |
| Medium | 101 |
| Low | 44 |

## Findings by tester

### Kavya — Functional

#### [Critical] \[Brand\] Publish path locks the full campaign budget in a pool hold that cannot pay creators, be released, or be refunded from the UI
- **Where:** src/components/brand/campaigns/secure\-and\-publish\-step\.tsx:214 ; influora\-api/\.\.\./service/EscrowService\.java:254\-272,333\-362,537\-545,690\-699
- **Issue:** Every brand publish \(standard form, hype form, detail dialog\) funds with campaignId only \(FundEscrowButton at secure\-and\-publish\-step\.tsx:214; api\.ts:4056 sends milestoneId:null\)\. EscrowService\.initiateFund then debits budgetMax \(deriveFundAmount :401\-414\) into a hold with milestoneId=null and collaborationId=null \(:354\-361\)\. CampaignActivationGuard\.java:71\-81 needs that FUNDED hold to go ACTIVE\. The hold is never used again\. Funding a signed deal's milestone looks up findActiveByCampaignIdAndMilestoneId\(campaignId, milestoneId\) \(:303\-305, query at EscrowHoldRepository\.java:128\-136\), which never matches the pool hold, so the wallet is debited a second time for each milestone\. releaseByHoldIdInternal refuses the pool hold with ESCROW\_HOLD\_NOT\_LINKED \(:690\-699\)\. The only caller of EscrowHold\.bindCollaboration is Meera's ConfirmLaunchExecutor\. POST /wallet/escrow/refund has no FE caller \(EscrowController\.java:152\-166\), and no code moves a campaign to COMPLETED or CANCELLED or refunds on it\. Net effect: a brand pays the budget \(stuck\), plus each milestone again, plus the fee\. Orchestrator re\-check: EscrowService\.initiateFund builds the pool hold with milestoneId=null and no collaborationId; the milestone path looks up findActiveByCampaignIdAndMilestoneId\(campaignId, milestoneId\) so it never reuses the pool; releaseByHoldIdInternal throws ESCROW\_HOLD\_NOT\_LINKED; escrowService\.refund has exactly one caller \(EscrowController\.java:165\) and zero frontend callers\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Choose one\. \(a\) Draw milestone funding down from the campaign pool hold by splitting and binding it, instead of a fresh wallet debit\. \(b\) Stop requiring a pool hold to activate and gate activation on wallet balance or a reservation instead\. \(c\) At minimum, auto\-refund the unbound pool remainder on campaign complete/cancel and give the brand a refund\-unused\-funds control\. Add an integration test: publish, sign a contract, fund the milestone, then assert total wallet debit is at most budget plus fee\.

#### [Critical] \[Creator\] Creator withdrawal can never complete: the idempotency key is longer than the DB columns that store it
- **Where:** influora\-api/src/main/java/com/influora/service/WalletService\.java:304
- **Issue:** scopedKey = "creator\-withdraw:"\(17\) \+ userId ULID\(26\) \+ ":" \+ client key\. The FE always sends a UUID\(36\) \(src/pages/creator\-wallet\.tsx:64\-69,593\), so the key is 80 chars\. WalletLedgerService\.java:159/178 appends ":D"/":C" \(82 chars\) and writes it to wallet\_transactions\.idempotency\_key, which is length 64 \(WalletTransaction\.java:61, V8\_\_wallet\_transactions\.sql:15; no later ALTER\)\. The same 80\-char key also goes to payouts\.idempotency\_key, length 64 \(Payout\.java:56, V48\_\_payouts\.sql:13\)\. MySQL strict mode / Connector\-J will reject both inserts\. Entities have assigned ids and no @Version, so save\(\) is a merge and the INSERT should flush at commit, which is after razorpayXClient\.initiatePayout \(WalletService\.java:399\) has already been called\. Consequences: \(a\) every live withdrawal fails; \(b\) IdempotencyService\.executeOnce has already marked the key COMPLETED in its own transaction \(IdempotencyService\.java:158\), so a retry with the same key returns 409 IDEMPOTENCY\_KEY\_IN\_PROGRESS indefinitely; \(c\) if RazorpayX accepts the call, money leaves while the debit and the Payout row roll back, so the balance is intact and the 3\-per\-day counter never increments\. RazorpayX's own limits on reference\_id and X\-Payout\-Idempotency \(RazorpayXClient\.java:135,143\) are external, so that part is PLAUSIBLE only\. WalletServiceTest is Mockito and has no schema\. The Withdraw button is currently disabled by VITE\_PAYOUTS\_ENABLED defaulting to false \(api\.ts:109\-122\); the defect fires as soon as that flag is turned on\. Exposure note: the Withdraw button is currently disabled by VITE\_PAYOUTS\_ENABLED defaulting to false \(api\.ts:109\-122\), so this fires the day payouts are switched on, not today\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Hash the scoped key to 62 chars or fewer \(for example "cw:" \+ first 40 hex of SHA\-256\) before passing it to the ledger, the payout row and the gateway; or widen both columns by migration\. Adopt PayoutService's pattern: persist a PENDING Payout row and commit the debit before the gateway call, so the orphaned\-debit sweep can reconcile\. Add a Testcontainers test using a real UUID key\.

#### [Critical] \[Creator\] Affiliate commission is never credited: settlement posts an enum value the DB rejects, and would credit the wrong wallet if it succeeded
- **Where:** influora\-api/src/main/java/com/influora/job/AffiliateSettlementWriter\.java:131
- **Issue:** Defect 1: creditCreatorWallet posts WalletTransactionType\.AFFILIATE\_COMMISSION with TxnReferenceType\.AFFILIATE\_EARNING \(:137\-138\)\. wallet\_transactions\.type is ENUM\('DEPOSIT',\.\.\.,'ADJUSTMENT'\) and reference\_type is ENUM\(\.\.\.,'MANUAL'\) \(V8\_\_wallet\_transactions\.sql:6\-7,12\)\. No migration adds the new values, and ddl\-auto is validate \(application\.yml:47\)\. The insert should fail at flush, doSettleCreator rolls back, AffiliateSettlementJob catches it and increments failedCount, and every earning stays PENDING every month\. Defect 2, currently hidden by defect 1: the wallet is resolved with requireOrCreateUserWallet\(earning\.getCreatorId\(\)\), but creatorId is the CreatorProfile id \(CouponCodeService\.java:219; V28:53 says FK creator\_profiles\(id\)\)\. The creator's real wallet is owned by the user id \(AuthService\.java:406\-412 mints a separate profile id and calls Wallet\.forUser\(\.\., userId\); WalletController\.java:77 reads by principal\.getUserId\(\)\)\. If the enum were fixed, commission would go into a new wallet keyed by profile id that no endpoint reads or withdraws from\. The creator page meanwhile shows "Pending settlement" \(AffiliateEarningsView\.tsx:141\-146\)\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Add a migration extending both ENUM columns\. In the writer, resolve creatorProfileRepository\.findById\(earning\.getCreatorId\(\)\)\.getUserId\(\) and credit that wallet\. Add a Flyway\-backed integration test asserting the creator's GET /wallet balance increases after settlement\.

#### [High] \[Brand\] Workspace invite email has no accept link and no token — an invitee can never redeem it
- **Where:** influora\-api/src/main/java/com/influora/integration/msg91/EmailTemplateRegistry\.java:309\-323
- **Issue:** WorkspaceMemberService puts invite\_token in templateData \(WorkspaceMemberService\.java:463\-467,514\-518\) but both invite specs use the 3\-arg Spec with no CTA and only reference workspace\_name/role/expires\_at\. Nothing in src/main builds a /brand/invite?token= URL \(grep re\-verified: zero hits\)\. The FE accept page requires ?token= \(brand\-accept\-invite\.tsx:26,49\)\. Seats are a paid PRO entitlement, so a paid feature cannot be used\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Build inviteUrl = webBaseUrl \+ '/brand/invite?token=' \+ rawToken in queueInviteEmail/sendInviteEmailDirect and add ctaLabel \+ ctaUrlVar 'invite\_url' to both specs\.

#### [High] \[Brand\] /brand/onboarding and /brand/invite never restore the in\-memory token — every API call after a page load is an unauthenticated 401
- **Where:** src/App\.tsx:273\-277
- **Issue:** Live\-mode access token lives only in memory \(api\.ts:468\-473\); the only recovery is api\.auth\.bootstrap inside ProtectedRoute/CreatorProtectedRoute \(App\.tsx:125\-142\)\. These two routes sit outside the guard and decide 'signed in' from a localStorage hint \(auth\-session\.ts:309\-311\)\. After reload/tab\-restore/email\-link the hint exists but the token is null, so request\(\) sends no Authorization header and there is no refresh\-and\-retry \(api\.ts:637\-658,730\)\. Onboarding step 2 fails UNAUTHENTICATED and invite accept can never succeed\. The sign\-in detour dead\-ends too: brand\-accept\-invite\.tsx:75 passes ?next= but brand\-login\.tsx:43 ignores it\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Wrap both routes in a guard that awaits api\.auth\.bootstrap\('brand'\) when live and no memory token exists; make brand\-login honour a validated same\-origin ?next=\.

#### [High] \[Brand\] An accepted invite is unusable — the session never moves to the invited workspace
- **Where:** src/pages/brand\-accept\-invite\.tsx:39\-40,94
- **Issue:** acceptInvite only inserts the membership row \(WorkspaceMemberService\.java:244\-251\) and returns no new token; the JWT keeps the old workspaceId \(BrandContextService\.java:72\-74\)\. Login/refresh pick the oldest membership \(AuthService\.java:288\-295,561\-580\) and brandRegister always creates an own workspace first \(AuthService\.java:188\-206\), so the invited one is never chosen\. POST /workspace/members/switch \(WorkspaceMemberController\.java:104\) has no FE caller\. 'Go to dashboard' opens the invitee's own empty workspace\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Return a workspace\-scoped token from accept and store it; expose GET /workspaces/mine \(WorkspaceService\.java:138 already implements it\) plus a switcher; persist last\-active workspace\.

#### [High] \[Brand\] Shopify connect cannot work on any deployed stack — api\-key/api\-secret are bound nowhere, /authorize always 503s
- **Where:** influora\-api/src/main/java/com/influora/web/ShopifyConnectController\.java:94\-99
- **Issue:** ShopifyProperties\.apiKey/apiSecret default to "" \(ShopifyProperties\.java:25\-35\)\. application\.yml binds only token\-encryption\-key and redirect\-uri \(application\.yml:483\-495\) and no compose file forwards a Shopify key \(only TOKENENCRYPTIONKEY at docker\-compose\.utho\.yml:162, docker\-compose\.hostinger\.yml:154\)\. The UI still advertises 'Shopify · OAuth — one click' \(StoreIntegrationSetup\.tsx:204\-205,250\)\. webhookSigningSecret is unbound as well\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Add api\-key/api\-secret/webhook\-signing\-secret placeholders and forward them through compose \+ generate\-env\.sh; until then expose a 'configured' flag and hide/disable the Shopify card\.

#### [High] \[Brand\] "Save Draft" while editing a PAUSED campaign silently reverts it to DRAFT; backend has no status\-transition validation
- **Where:** src/components/brand/campaigns/campaign\-form\.tsx:652,661 ; src/pages/brand\-new\-hype\-campaign\.tsx:269,686 ; influora\-api/\.\.\./service/CampaignService\.java:336\-340 ; domain/entity/Campaign\.java:481
- **Issue:** The UI tells brands to pause before editing \(campaign\-form\.tsx:720; disabled\-Edit tooltip at campaigns\-list\.tsx:802\)\. In edit mode, savedStatus resolves to 'DRAFT' for the Save Draft button \(campaign\-form\.tsx:652\), and the hype page does the same \(:269\)\. CampaignService\.update passes req\.status\(\) straight to applyPatch \(:336\-340\), which writes any value \(Campaign\.java:481\)\. CampaignValidator only blocks terminal statuses and content edits on ACTIVE campaigns \(:72\-109\)\. A once\-live campaign with collaborators, a paid fee and funded holds becomes DRAFT\. Creators then lose their deals from GET /deals \(DealService\.java:158\-160,1449\-1465 exclude DRAFT\-campaign collaborations\), CreatorDiscoveryService\.java:522 refuses new invites, and the campaign becomes delete\-eligible \(CampaignValidator\.java:111\-116\)\. A raw PATCH also allows ACTIVE\-\>DRAFT, DRAFT\-\>COMPLETED and DRAFT\-\>PAUSED\. — Confidence: CONFIRMED\.
- **Fix:** FE: when isEditing, never send status on Save; rename the button to Save changes\. BE: add an explicit transition table in CampaignService\.update \(DRAFT\-\>ACTIVE via the guard, ACTIVE\<\-\>PAUSED, ACTIVE/PAUSED\-\>COMPLETED/CANCELLED\) and reject everything else with 409 INVALID\_STATUS\_TRANSITION\.

#### [High] \[Brand\] "Direct Deal" campaigns cannot be created unless a Shopify or WooCommerce store is connected; FE and BE disagree on what DIRECT means
- **Where:** src/pages/brand\-new\-campaign\.tsx:65\-68 ; src/lib/api\.ts:1628 ; influora\-api/\.\.\./service/IntegrationHealthService\.java:74\-76 ; service/CampaignService\.java:186\-193
- **Issue:** The FE tile describes Direct Deal as inviting specific creators and negotiating one\-on\-one, and sends campaignType DIRECT \(api\.ts:1628\)\. The BE treats DIRECT as a sale/conversion campaign and returns 409 NO\_STORE\_INTEGRATION unless an active store integration exists \(CampaignService\.java:186\-193\)\. campaign\-form\.tsx has no pre\-check and no NO\_STORE\_INTEGRATION handling \(no match for the code in src\)\. The brand completes the whole wizard and then gets a toast telling them to connect Shopify for something that involves no store\. The ?creatorId= Discover handoff steers brands to Open or Direct, so half of that path dead\-ends\. — Confidence: CONFIRMED\.
- **Fix:** Map the FE 'DIRECT' to a backend type that is not store\-gated \(STANDARD with isPrivate=true, or a new INVITE\_ONLY type\), or remove the store gate from DIRECT and gate a real conversion type\. If the gate stays, check storeIntegrations status in the type picker and explain before the wizard starts\.

#### [High] \[Brand\] No way to complete, cancel or close a campaign, so the lifecycle can never end
- **Where:** src/pages/brand\-campaign\-detail\.tsx:1121\-1152 ; src/components/brand/campaigns/campaigns\-list\.tsx:824\-840 ; influora\-api/\.\.\./service/CampaignService\.java \(no COMPLETED or CANCELLED writer\)
- **Issue:** Both action menus offer only Pause, Resume, Publish and Delete\. Searching src for status 'COMPLETED' or 'CANCELLED' finds only mock fixtures \(campaigns\-list\.tsx:143, brand\-campaign\-detail\.tsx:110\)\. Searching the backend for CampaignStatus\.COMPLETED or CANCELLED finds only read\-side guards \(CampaignValidator\.java:73, MeeraContextService\.java:85\); no service, scheduler or admin endpoint sets them, and AdminCampaignController is GET\-only\. In live mode the Completed filter is always empty, the detail Report tab \(:704\-706,729\) is unreachable, a campaign past its endDate stays ACTIVE, and the pool funds in finding 1 are never reconciled\. — Confidence: CONFIRMED\.
- **Fix:** Add Complete campaign and Cancel campaign actions that send a status\-only PATCH \(the backend already accepts these for ACTIVE/PAUSED\)\. Guard against open collaborations and refund unbound holds on the way out\. Optionally add an end\-date scheduler\.

#### [High] \[Brand\] Accept Proposal on /brand/deals can never succeed in live mode
- **Where:** src/components/brand/deals/deal\-room\-dashboard\.tsx:86
- **Issue:** The dashboard shows Accept only for the 'proposed' bucket, which is INVITED/APPLIED/SHORTLISTED \(dashboard:88\-93, buttons :773\-782 and :1071\)\. SHORTLISTED is never written anywhere \(the only transitionTo calls are DealService:449,:1149,:1314, DisputeService:144 and CollaborationLifecycleService:199,:222\)\. INVITED and APPLIED rows never carry agreedRate \(Collaboration\.java:116 invite, :135\-152 apply; updateAgreedRate is called only at DealService:279 and :1305, and both paths move the deal to IN\_NEGOTIATION at :1314 / Collaboration\.java:337\)\. doAccept therefore always throws 409 AGREED\_RATE\_REQUIRED \(DealService\.java:1131, :1822\-1829\)\. The catch block discards the error and shows 'Could not accept the proposal\. Try again\.' \(dashboard:465\-466\), so a retry can never work\. The one state where accept is legal, IN\_NEGOTIATION with a creator counter, maps to 'negotiating' and gets no Accept button\. The same catch block hides the 403 that MEMBER and VIEWER roles receive \(DealService:1572\-1577\)\. — Confidence: CONFIRMED\.
- **Fix:** Offer Accept only when the latest proposal is from the creator and the deal has a rate\. Mirror brand\-chat\.tsx:1824\-1825 by reading the messages and using allowsProposalResponse plus sender \!= brand\. Surface ApiError\.message and code, reusing describeAcceptError from brand\-chat\.tsx:640\. For rate\-less INVITED/APPLIED deals offer only Counter\.

#### [High] \[Brand \+ Creator\] Accept and reject use a permanent per\-deal idempotency key, so a second reject or accept on a revived deal silently no\-ops with 200
- **Where:** influora\-api/src/main/java/com/influora/service/DealService\.java:412
- **Issue:** The frontend never sends an Idempotency\-Key for accept or reject \(src/lib/api\.ts:2480\-2496\)\. The backend falls back to the static keys 'deal\-reject:'\+dealId and 'deal\-accept:'\+dealId \(DealService:353, :412\)\. IdempotencyService marks the key COMPLETED with no expiry \(IdempotencyService\.java:150, :158\)\. A brand\-rejected application can be re\-applied in place on the same collaboration id \(CollaborationReviveService\.java:152\-179, called from CreatorCampaignService\.java:232\)\. On the second brand reject the AlreadyCompletedException branch returns OkResponse\.success\(\) without cancelling anything \(DealService:420\-425\)\. The UI closes the dialog as a success \(dashboard:516\-520\) and the deal stays APPLIED\. That workspace can never reject the deal again\. Accept has the same shape and returns the unchanged deal \(DealService:361\-365\)\. The dashboard then shows 'Proposal accepted\.' \(dashboard:457\)\. Creator side \(same root cause\): creator Accept/Decline on a re\-invited or re\-applied deal returns 200 with no state change, the row is removed / 'Proposal accepted' is shown, and that creator can never act on the deal again \(creator\-deals\.tsx:415\-416; creator\-chat\.tsx:1438\-1485\)\. — Confidence: CONFIRMED\.
- **Fix:** Send a fresh UUID Idempotency\-Key for each user action from api\.deals\.accept and api\.deals\.reject\. On the server, include a revision marker in the fallback key, such as collaboration\.updatedAt or appliedAt\. In the AlreadyCompleted branch, re\-check the state and execute if the deal is still actionable instead of returning success blindly\.

#### [High] \[Brand\] A counter from /brand/deals carries no deliverables, so the later contract creates zero Deliverable rows and the deal dead\-ends
- **Where:** src/components/brand/deals/deal\-room\-dashboard\.tsx:487
- **Issue:** The dashboard counter payload is \{amount, message\} only\. CounterRequest\.deliverables is optional \(DealDtos\.java CounterRequest\)\. doCounter carries deliverables forward only from a superseded proposal card \(DealService\.java:1333\-1352\)\. A creator application writes no proposal card; it writes only a system message and a text message \(CreatorCampaignService\.java:309\-330\)\. The resulting proposal card therefore has no 'deliverables' metadata \(DealService:1924\-1933\)\. When a contract is generated later, materializeDeliverables finds no slots and returns silently \(ContractService\.java:741\-744\), and generate does not refuse\. Nothing else creates Deliverable rows; the only Deliverable\.builder\(\) call is ContractService:767\. The creator has nothing to submit\. recomputeReviewState returns early on an empty list \(CollaborationLifecycleService\.java:217\-219\), so the deal can never reach COMPLETED and can never be reviewed\. The brand sees 'No deliverables yet' indefinitely \(deal\-deliverables\-tab\.tsx:53\-58\)\. — Confidence: CONFIRMED\.
- **Fix:** Require deliverables in the dashboard counter, or route it to the ProposalForm wizard\. On the server, reject contract generation with a 409 when the agreed offer has no deliverable slots, or require deliverables on the first proposal\.

#### [High] \[Brand\] Approving a deliverable never releases payment: Deliverable\.milestoneId is never written, so the release is always held as NO\_MILESTONE
- **Where:** influora\-api/src/main/java/com/influora/service/BrandDeliverableService\.java:171
- **Issue:** approve\(\) calls tryReleaseOnApproval\(workspaceId, deliverable\.getMilestoneId\(\)\)\. The only place a Deliverable row is created is ContractService\.java:767\-774, and it never sets milestoneId\. The entity has no setter; the only writer is the builder at Deliverable\.java:337\-338, and no caller uses it\. tryReleaseOnApproval therefore returns held\('NO\_MILESTONE'\) every time \(EscrowService\.java:772\-773\)\. Every approve response has paymentReleased=false, so the UI always shows the destructive toast 'Approved — but payment was NOT released… It needs a contract with milestones' \(brand\-chat\.tsx:1302\-1307; contracts\-and\-deliverables\.tsx:819\-824; src/lib/escrow\-release\-reason\.ts NO\_MILESTONE copy\)\. That message is false when a funded milestone exists\. The 'Payment has been released' branch is unreachable\. The copy '₹X releases from secured funds as each deliverable is approved' \(deal\-deliverables\-tab\.tsx:48\) and 'will release on approved deliverables' \(deal\-contract\-tab\.tsx:367\) is untrue\. Creators are paid only if an OWNER or ADMIN manually presses Release \(deal\-payments\-tab\.tsx:261\-269\)\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** On approve, resolve the release at collaboration level, the same way assertReleaseConditionSatisfied already works \(EscrowService\.java:1594\): find the FUNDED milestones for deliverable\.getCollaborationId\(\) and attempt a release for each\. Alternatively, drop the auto\-release promise and rewrite the copy and toast to say 'Approved — release payment from the Payments panel', with a deep link\.

#### [High] \[Brand\] The brand cannot view the submitted deliverable files, caption or notes before approving, because the viewer component is mounted nowhere
- **Where:** src/components/brand/deal\-room/deal\-deliverables\-tab\.tsx:60
- **Issue:** The deal\-room deliverables panel renders only a title and a status, with Approve and Request\-changes buttons \(deal\-deliverables\-tab\.tsx:60\-137\)\. brand\-chat maps list rows to \{id,title,status\} and hard\-codes type:'image' \(brand\-chat\.tsx:1267\-1272\)\. GET /deliverables/\{id\}, which returns presigned file URLs, caption and notes \(BrandDeliverableController\.java:39; api\.ts:3381\), is consumed only by src/hooks/brand/useDeliverableDetail\.ts:34 and src/components/brand/deliverables/DeliverableViewer\.tsx\. Neither is imported or rendered anywhere in src\. The /brand/contracts Deliverables tab is always empty in live mode \(contracts\-and\-deliverables\.tsx:475, :1478\-1482\)\. Approval, which is the money\-gating step, is therefore blind\. POST /deliverables/\{id\}/reject and GET /deliverables/\{id\}/safety\-review are reachable only from the same unmounted viewer \(DeliverableViewer\.tsx:334; useDeliverableSafetyReview\.ts:43\)\. — Confidence: CONFIRMED\.
- **Fix:** Mount DeliverableViewer from the deal\-room deliverables panel, for example a 'Review' button per SUBMITTED or RESUBMITTED row that opens the viewer with approve, revise and reject\. Load deliverables on /brand/contracts through api\.deliverables\.list\(collaborationId\)\.

#### [High] \[Brand\] All brand deal\-lifecycle notifications link to routes that do not exist, so every click lands on the 404 page
- **Where:** influora\-api/src/main/java/com/influora/service/notification/NotificationListener\.java:330
- **Issue:** The backend stores links verbatim \(NotificationService\.java:103\-112\): '/brand/proposals/\{id\}' at :330 for counter\-bids, '/brand/collaborations/\{id\}' at :346 for proposal accepted, '/brand/contracts/\{id\}' at :359 and :383 for contract signed and ready for escrow, '/brand/deliverables/\{id\}' at :398, '/brand/shipments/\{id\}' at :414 and '/brand/messages/\{id\}' at :427\. The frontend navigates to notification\.link unchanged \(src/pages/brand\-notifications\.tsx:85,:91; src/components/brand/brand\-layout\.tsx:517; mapping at src/lib/api\.ts:4187\)\. App\.tsx defines only /brand/contracts, /brand/messages, /brand/chat and /brand/deals/:id \(src/App\.tsx:353\-383, :499\-514\)\. None of those seven patterns has a route, so the catch\-all NotFoundPage renders \(App\.tsx:919\)\. Deliverable\-submitted, counter\-received and shipment\-damaged notifications all dead\-end\. Orchestrator addendum: the same listener also emits '/brand/campaigns/\{id\}/applications' \(:315\), '/brand/wallet/add\-funds' \(:536\) and '/brand/billing' \(:557,:579\) — none of which is a route in App\.tsx either \(wallet is /brand/wallet, billing is /brand/settings/billing\)\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Emit real routes from the listener: /brand/chat?deal=\{collaborationId\}\&tab=contract, \&tab=deliverables or \&tab=payments as appropriate\. Note that several events carry a contract id or escrow id as entityId, so resolve the collaboration id first\. Alternatively add redirect routes in App\.tsx\.

#### [High] \[Brand\] Brand analytics returns 403 for every creator in live mode
- **Where:** src/pages/brand\-analytics\.tsx:174\-181 ; influora\-api/src/main/java/com/influora/service/MetricsAuthorizationService\.java:65\-73
- **Issue:** Two independent defects\. \(a\) The roster is built from deal\.counterpartyId \(brand\-analytics\.tsx:178\-179\), which is the creator's USER id \(DealService\.java:2259\-2262 sets Counterparty\(collaboration\.getCreatorId\(\), creator\.getId\(\)\)\)\. It is passed to /analytics/creators/\{id\}/\* \(brand\-analytics\.tsx:57,455\), but the backend matches on MetaOAuthToken\.creatorProfileId\. \(b\) Even with the right id, authorization requires a meta\_oauth\_tokens row with workspaceId equal to the brand workspace\. The only code that inserts such a row is MetaTokenStorage\.storeToken \(:95\-132\), and its only caller is MetaTokenRefreshService\.java:203, which refreshes an already existing brand\-scoped row\. Every connect flow uses storeCreatorToken with workspaceId null \(MetaTokenStorage\.java:324\-327; CreatorMetaOAuthService\.java:142,222\)\. So metrics, scores, demographics and media all 403, and the tiles render 0 \(brand\-analytics\.tsx tiles use ?? 0; brand\-creator\-analytics\.tsx:130\-151\)\. — Confidence: CONFIRMED\.
- **Fix:** Use counterpartyProfileId for the roster, and replace the token\-row check with a real workspace\-to\-creator relationship \(for example a collaboration between the workspace and the creator, plus a live creator\-owned token\)\. Render a dash instead of 0 when a request fails\.

#### [High] \[Brand\] Billing page shows paise as rupees \(Pro plan reads Rs 4,99,900 per month; invoices are 100x\)
- **Where:** src/pages/brand\-billing\-settings\.tsx:377,683 ; influora\-api/src/main/java/com/influora/web/BillingController\.java:223,244
- **Issue:** Plan\.priceInr is stored in paise \(V55\_\_seed\_billing\_plans\.sql PRO = 499900; SubscriptionService\.java:512\-516 passes it to Razorpay as paise\)\. Invoice\.amount is paise \(InvoiceService\.java:207 stores \(int\) amountInPaise; the PDF divides by 100 at :252\-253\)\. BillingController returns both raw \(:223, :244\), and the FE formatCurrency \(:47\-53\) does no division, so it renders 100x values \(:377, :683\)\. The mock plan is Free, so the demo never shows it\. The upsell hardcodes 4999 \(:554\)\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Return rupees, or clearly named \*Paise fields, from BillingController and divide by 100 in one place\. Add a contract test using a Pro fixture\.

#### [High] \[Brand\] Wallet top\-up has no fallback when the Razorpay webhook is missed; money can be captured and never credited
- **Where:** influora\-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController\.java:190\-200 ; src/lib/razorpay\.ts:176
- **Issue:** WalletTopUpService\.confirmCredited \(:182\) is called only from the webhook dispatch\. There is no verify endpoint \(razorpay\.ts:176 discards razorpay\_payment\_id and razorpay\_signature\)\. There is no scheduled reconciliation for PENDING top\-ups \(WalletTopUpRepository has only findByIdempotencyKey and findByCreatedAtBetween; no job references it\)\. Admin reconciliation is read\-only \(AdminFinanceService\.java:442\-486 classifies MISMATCH but never credits\)\. A dropped or disabled webhook, or a rejected signature, strands the payment\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Add an authenticated POST /wallet/topup/\{id\}/verify that checks the signature or fetches the order \(RazorpayClient\.fetchOrder :131\) and calls confirmCredited\. Add a scheduled sweep of PENDING top\-ups older than N minutes, and an admin 'credit now' action\.

#### [High] \[Creator\] Any portfolio Save overwrites the stored cover key with a 15\-minute presigned URL
- **Where:** src/pages/creator\-portfolio\-editor\.tsx:165
- **Issue:** uploadCover stores the R2 object KEY \(PortfolioService\.java:699\)\. GET /me/portfolio returns coverUrl = resolveCoverUrl\(key\), which is a presigned GET \(PortfolioService\.java:795,1454\) with a 900s TTL \(application\.yml:380\)\. handleSave always sends coverUrl: page\.coverUrl \(editor:165\)\. updateMine passes patch\.coverUrl\(\) into applySelfEdit \(PortfolioService\.java:307\), which overwrites coverImageUrl \(CreatorProfile\.java:455\)\. On the next read toCoverObjectKey returns null for a non\-public\-base https URL \(PortfolioService\.java:1461\-1474\), so the stored, soon\-expired URL is served\. The cover breaks on both the editor and the public page about 15 minutes after any save\. The column is VARCHAR\(500\) \(CreatorProfile\.java:41\), so a longer signed URL would 500 the save instead\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Stop sending coverUrl from the editor unless the creator cleared it \(send '' to clear only\)\. Server\-side, ignore or reject patch\.coverUrl unless it is blank or a creators/\{id\}/cover/ key\.

#### [High] \[Creator\] Public 'verified metrics' page can show creator\-typed follower counts as Meta\-verified
- **Where:** influora\-api/src/main/java/com/influora/service/PublicCreatorService\.java:77
- **Issue:** getVerifiedMetrics takes the newest creator\_metrics row with no platform or dataSource filter \(PublicCreatorService\.java:77; CreatorMetricsRepository\.java:40\)\. declarePlatform writes a CREATOR\_REPORTED row with any follower count up to 1e9 for INSTAGRAM, YOUTUBE, TIKTOK or TWITTER \(PortfolioService\.java:488\-499\)\. A Meta\-connected creator who then declares, say, TikTok with 50,000,000 followers gets that number rendered under 'Verified Metrics' with the caption 'Verified on \<date\> via connected Meta account' \(creator\-verified\-metrics\.tsx:115\-118,140\)\. verified\_at is the fetchedAt of the self\-declared row\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Query findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc for INSTAGRAM and require dataSource == META\_API \(isPlatformVerified\)\. Otherwise return null metrics, i\.e\. 'pending'\.

#### [High] \[Creator\] Delete Account only anonymises the User row; the public portfolio and Meta token stay live, with no guard for active deals or wallet balance
- **Where:** influora\-api/src/main/java/com/influora/web/AccountController\.java:91
- **Issue:** The dialog promises to 'permanently delete your account and remove all your data' \(creator\-settings\.tsx:817\)\. deleteAccount only runs user\.softDelete\(\), logout and a reset\-token purge \(AccountController\.java:91\-101\)\. Nothing touches CreatorProfile, PlatformStat or MetaOAuthToken, and outside auth the only readers of deletedAt are BrandContextService and CreatorContextService\. GET /portfolio/\{username\} still serves display name, bio, avatar and platforms because it checks only profile\.isDiscoverable \(PortfolioService\.java:248\-251,765\-769\), and the verified\-metrics page still resolves \(PublicCreatorService\.java:62\-66\)\. The Meta token is never revoked\. There is no check for in\-progress collaborations or wallet balance before deletion, and the controller method is not @Transactional\. — Confidence: CONFIRMED\.
- **Fix:** In one transaction: set the profile non\-discoverable and anonymised, revoke the creator token \(MetaTokenStorage\.revokeCreatorToken\), and block with a 409 when active collaborations or a non\-zero wallet exist\.

#### [High] \[Creator\] Manual 'Sync' ignores the token's auth path, so Instagram\-Login creators are sent to graph\.facebook\.com
- **Where:** influora\-api/src/main/java/com/influora/service/portfolio/PortfolioService\.java:403
- **Issue:** syncPlatforms calls instagramInsightsClient\.getProfile\(igBusinessAccountId, accessToken\)\. The 2\-arg overload hardcodes MetaAuthPath\.FACEBOOK\_LOGIN \(InstagramInsightsClient\.java:45\-46\), and the host is chosen from authPath \(MetaGraphApiClient\.java:81\-82\)\. tokenRow\.getAuthPath\(\) is never read here, although MetricsPollingJob\.java:224 and MetaConnectionService pass it\. For a creator connected via INSTAGRAM\_LOGIN, the 'Sync' button on /creator/profile \(creator\-profile\.tsx:211\) and 'Sync now' in the editor \(creator\-portfolio\-editor\.tsx:207\) send an Instagram\-Login token to the Facebook host and fail\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Pass tokenRow\.getAuthPath\(\) to the 3\-arg getProfile, and consider removing the defaulting overloads\.

#### [High] \[Creator\] Most creator notification deep links land on the 404 page
- **Where:** influora\-api/src/main/java/com/influora/service/notification/NotificationListener\.java:201
- **Issue:** The listener stores in\-app links to /creator/messages/\{id\} \(:201\), /creator/proposals/\{id\} \(:216\), /creator/collaborations/\{id\} \(:234,:252\), /creator/shipments/\{id\} \(:265\), /creator/contracts/\{id\} \(:283\) and /creator/profile/kyc \(:523\)\. None of these are routes in src/App\.tsx; the creator routes are at :527\-701 and unknown paths fall to the catch\-all at :919\. creator\-notifications\.tsx:82\-84 calls navigate\(notification\.link\) verbatim, and api\.ts:4178\-4188 applies no rewrite\. New message, proposal, bid accepted, campaign live, product shipped, sign contract and KYC rejected notifications therefore all open NotFound\. — Confidence: CONFIRMED\.
- **Fix:** Emit real routes: /creator/chat?deal=\{collaborationId\}, with \&tab=contract for contracts, /creator/deals?status=new for proposals and /creator/settings for KYC\. Alternatively add a link\-normalising map in fromNotificationWire\. Add a test that checks every listener link against the route table\.

#### [High] \[Creator\] Creator Payments panel says 'Payment secured' when the contract is signed but no funds are held
- **Where:** src/pages/creator\-chat\.tsx:3065
- **Issue:** The creator room renders the brand DealPaymentsTab without escrowFunded or milestones \(creator\-chat\.tsx:3066\-3071\)\. In that component escrowLocked = escrowFunded ?? fullySigned \(deal\-payments\-tab\.tsx:84\-87\)\. Once both parties have signed, the creator reads 'Payment secured — Rs X is secured until deliverables are approved' and sees Secured = dealValue \(deal\-payments\-tab\.tsx:158,163\-184,224\), although the real flag selectedDeal\.escrowFunded \(DealService\.java:2116\-2123,2168\) may be false\. This contradicts the sign toast 'Waiting for the brand to secure the funds' \(creator\-deal\-contract\-tab\.tsx:160\) and can lead a creator to start work with no money on hold\. — Confidence: CONFIRMED\.
- **Fix:** Pass escrowFunded=\{selectedDeal\.escrowFunded\} and milestones=\{liveContract?\.milestones\}, as brand\-chat does\. Remove the '?? fullySigned' fallback\.

#### [High] \[Creator\] Creator's own counter\-offer renders as a 'Brand Proposal' with live Accept/Counter/Decline
- **Where:** src/pages/creator\-chat\.tsx:535
- **Issue:** The live mapper sends every kind='proposal' message to type 'proposal' \(creator\-chat\.tsx:535\-543\)\. The proposal branch ignores event\.sender: it uses the brand avatar, the static label 'Brand Proposal' \(:2473\-2486\), and shows buttons whenever metadata\.status==='pending' \&\& canRespondToProposal \(:2576\)\. persistProposalMessage writes only amount, status, deliverables, deadline and usageRights and never writes proposalType \(DealService\.java:1922\-1923\), so the shared mapper's counter branch \(creator\-deal\-mappers\.ts:262\-263\) and the 'Your Counter Proposal' card \(creator\-chat\.tsx:2694\) are unreachable in live mode\. After the creator counters, their own offer appears as the brand's\. Accept returns 409 CANNOT\_ACCEPT\_OWN\_OFFER \(DealService\.java:1119\-1123\)\. Decline cancels the whole deal \(DealService\.java:449\)\. — Confidence: CONFIRMED\.
- **Fix:** Discriminate on senderType: when senderType is 'creator', render the counter\_proposal card with no action buttons\. Optionally have persistProposalMessage write proposalType\.

#### [High] \[Creator\] Signing a CANCELLED contract resurrects it, because the sign path has no contract\-status guard
- **Where:** influora\-api/src/main/java/com/influora/service/ContractService\.java:1083
- **Issue:** doRecordSignature checks only whether this role already signed and whether the collaboration is CANCELLED \(ContractService\.java:1085\-1110\)\. Contract\.recordCreatorSignature then calls advanceIfFullySigned, which unconditionally sets status to ACTIVE or PENDING\_SIGNATURES \(Contract\.java:202\-215\)\. A contract cancelled through POST /contracts/\{id\}/cancel \(ContractService\.java:1046\-1054\) or superseded by an amendment \(ContractService\.java:507\-509\) can be re\-activated by POST /contracts/\{id\}/sign\. If the brand had already signed, the contract becomes ACTIVE, the collaboration moves to CONTRACTED, the PDF is emailed and the brand is prompted to fund \(ContractService\.java:1126\-1173\)\. FE has the same gap: mapApiContractToDealStatus ignores CANCELLED and returns 'brand\_signed', so 'Your turn to sign' would show \(creator\-contract\-mappers\.ts:13\-25\)\. Cancel and amend have no FE client, so today this is reachable by direct API call only\. — Confidence: CONFIRMED\.
- **Fix:** In doRecordSignature, throw 409 CONTRACT\_NOT\_SIGNABLE unless status is DRAFT or PENDING\_SIGNATURES, and guard inside Contract\.record\*Signature as well\. In the FE mapper, return a distinct state for CANCELLED\.

#### [High] \[Creator\] Withdrawal calls the payment gateway inside the DB transaction, so a failure after the gateway accepts means money sent with no debit and no record
- **Where:** influora\-api/src/main/java/com/influora/service/WalletService\.java:249
- **Issue:** requestCreatorWithdrawal is a single @Transactional method\. The ledger post \(:385, REQUIRED propagation per WalletLedgerService\.java:58\), RazorpayX initiatePayout \(:399\) and payoutRepository\.save \(:401\) all run in that one transaction\. doProcessWithdrawal's own @Transactional \(:381\) is protected and self\-invoked, so it does nothing\. If anything throws after the gateway accepted the request \(read timeout, save or commit failure\), the debit rolls back, no Payout row exists, and PayoutReconciliationService\.confirmExecuted only logs "No Payout row found" \(:124\-131\) when the webhook arrives\. PayoutOrphanedDebitSweepJob only covers STATUS\_PENDING rows created by PayoutService\. The sweep job's own javadoc \(PayoutOrphanedDebitSweepJob\.java\) describes this as the double\-pay hole a real transaction reintroduces, and this method is that real transaction\. Separately, initiatePayout's HttpRequest has no \.timeout\(\) \(RazorpayXClient\.java:139\-145; only a 10s connect timeout at :86\), so a stalled gateway holds the wallet row lock taken at WalletService\.java:265 and a DB connection with no bound\. — Confidence: CONFIRMED\.
- **Fix:** Use the PayoutService shape: persist a PENDING Payout, commit the debit, then call the gateway outside the transaction, mark it confirmed, and let the sweep reconcile\. Add \.timeout\(Duration\.ofSeconds\(30\)\) to every RazorpayX request\.

#### [High] \[Admin\] Content flag queue is always empty: the FE reads data\.data, the backend sends items
- **Where:** src/admin/hooks/useFlagQueue\.ts:81
- **Issue:** useFlagQueue reads response?\.data?\.data \(81\), typed as PaginatedResponse \{data,total,\.\.\.\} \(admin\.types\.ts:945\)\. The backend returns PagedFlagsDto\(List\<FlagDto\> items, int total, int page, int pageSize\) \(AdminModerationDtos\.java:41; AdminModerationService\.java:144\)\. allFlags is therefore always \[\]\. The error expression on line 82 also ignores response\.success === false, so a 403 or 500 also renders as an empty queue\. The REVIEW contentType that the only producer writes \(ReviewService\.java:196\-204\) is missing from the FE union \(admin\.types\.ts:581\)\. No flagged review can be seen or actioned in the Flags tab\. — Confidence: CONFIRMED\.
- **Fix:** Read response\.data\.items and response\.data\.total, or rename the DTO field to data\. Surface response\.error\. Add REVIEW to the ContentFlag contentType union\.

#### [High] \[Admin\] Flag REMOVE removes nothing, and failed actions show 'Action applied successfully'
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminModerationService\.java:195
- **Issue:** REMOVE only calls flag\.markActioned and writes an audit row saying 'Removed flagged content' \(195\-205\)\. Nothing outside the admin package reads ContentFlagStatus\.ACTIONED, and the review stays published, so it is a status nothing reads\. On the FE, moderationApi\.actionFlag goes through apiRequest, which never throws and returns \{success:false\} on failure \(api\-contracts\.ts:145\-155\)\. FlagQueue\.tsx:297\-300 onSuccess still shows 'Action applied successfully' on 400, 403 or 500\. The Approvals tab is the only place flags are visible, and there Approve/Reject on CONTENT\_MODERATION rows hits a 501 \(ApprovalWorkflowService\.java:172\-178\)\. useApprovalQueue\.ts:74 derives processError from mutation\.error, which is never set, so that failure is silent too\. — Confidence: CONFIRMED\.
- **Fix:** Make REMOVE actually hide or soft\-delete the target \(review, deliverable, profile\)\. Have the mutationFns throw when \!res\.success\. Hide the action buttons on CONTENT\_MODERATION approval rows or wire them to actionFlag\.

#### [High] \[Admin\] Split dispute resolution issues a creator service invoice and TCS for the full hold, not the amount released
- **Where:** influora\-api/src/main/java/com/influora/service/EscrowService\.java:1453
- **Issue:** adminSplitForDispute credits creatorAmount = hold x split% \(1390\-1393\) and refunds the rest\. It then calls safelyCreateServiceInvoice\(hold, \.\.\.\) \(1453\)\. CampaignServiceInvoiceService\.createAtRelease uses grossAmount = hold\.getAmount\(\) \(158\) and computes TCS as 1% of that \(209\)\. A 60/40 split on Rs 10,000 pays Rs 6,000 but produces a Rs 10,000 invoice with Rs 100 TCS\. The hold is also marked fully RELEASED \(1441\), which inflates released\-GMV aggregates\. The path is reachable from the admin UI \(DisputeList\.tsx:206\-209\)\. — Confidence: CONFIRMED\.
- **Fix:** Pass the released amount into createAtRelease and invoice that\. Record the split legs on the hold \(a partial\-release state or amount columns\) so reports use the real released value\.

#### [High] \[Admin\] MFA enrolment deadlock: an unenrolled ADMIN or SUPER\_ADMIN can never log in, and the reset 'recovery' endpoint creates that state
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminAuthService\.java:159
- **Issue:** login rejects any SUPER\_ADMIN or ADMIN with mfaEnabled=false \(159\-168\)\. mfaEnforceOnLogin defaults to true \(AdminSecurityProperties\.java:37\) and has no application\.yml override\. /admin/auth/mfa/setup and /verify require an admin JWT \(SecurityConfig\.java:263\-277; AdminAuthService\.java:278\-306\) that such an admin cannot obtain\. resetMfaForAdmin sets mfaEnabled=false and revokes tokens \(342\-354; AdminUser\.java:262\-268\), so the recovery path permanently locks the target out of the app\. The FE has no enrolment UI: authApi\.setupMfa and verifyMfa \(api\-contracts\.ts:218\-225\) have zero callers\. resetMfaForAdmin also writes no audit row\. — Confidence: CONFIRMED\.
- **Fix:** Issue a limited enrolment\-only token, or a pre\-auth enrolment challenge, when the password is correct but MFA is not enrolled\. Build the enrolment screen\. Audit\-log MFA resets\.

#### [High] \[Admin\] Admin must rule money disputes blind: no dispute reason, amount or evidence is available before resolving
- **Where:** influora\-api/src/main/java/com/influora/web/dto/dispute/DisputeDtos\.java:75
- **Issue:** DisputeSummaryDto carries only disputeId, campaignId, brand and creator names, status and dates \(75\-82\)\. AdminDisputeController exposes only list and resolve \(AdminDisputeController\.java:41\-70\), with no GET by id\. DisputeList\.tsx shows the reason only from the DisputeResponse that resolve\(\) returns \(file header 12\-16\)\. The release, refund or split decision is therefore made without seeing the complaint, the frozen amount or the collaborationId\. DisputeService also sends no notification on resolution, and the collaboration stays DISPUTED afterwards \(CollaborationLifecycleService\.java:55 treats DISPUTED as terminal; CreatorApplicationMapper\.java:60 still shows 'In dispute'\)\. — Confidence: CONFIRMED\.
- **Fix:** Add GET /admin/disputes/\{id\} returning reason, opener, collaboration and deal value, frozen hold total, deliverables and messages, and render it in the resolve modal\. Notify both parties on resolution\. Define a post\-resolution collaboration state\.

#### [High] \[Admin \+ Brand \+ Creator\] Support queue has no producer: no brand/creator UI or API client ever creates a ticket
- **Where:** BE/web/SupportController\.java:47
- **Issue:** The admin Support page reads support\_tickets \(AdminSupportService\.java:122\-125\), but the only writer is SupportService\.create \(SupportService\.java:52\-61\), reached only from POST /support/tickets \(SupportController\.java:47\-52\)\. A grep of SRC outside src/admin for 'support/tickets', 'supportTicket' and 'createTicket' returns nothing: api\.ts and meera\-api\.ts have no client, brand\-help\.tsx only opens the tour or Meera, and contact\.tsx:56 is a mailto link\. The admin queue can never receive a ticket through the product\. Brand side: /support is a static 'being set up' page \(App\.tsx:898\-905\) while brand\-register\.tsx:175 tells blocked users to 'contact support'\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Add a support API client for POST/GET /support/tickets and POST /\{id\}/messages, plus a brand/creator Help \> Contact support form and a my\-tickets thread view\.

#### [High] \[Admin\] Ticket Assign sends \{adminId\} but the backend binds \{assignedTo\}: it unassigns the ticket and the UI reports success
- **Where:** FE/services/api\-contracts\.ts:712
- **Issue:** The FE body is JSON\.stringify\(\{ adminId \}\) \(api\-contracts\.ts:713\-716\)\. The backend record is AssignRequest\(String assignedTo\) \(AdminSupportDtos\.java:71\), read at AdminSupportController\.java:101\-102\. The unknown property is ignored, so assignedTo is null, normalizedAssignee is null \(AdminSupportService\.java:198\) and ticket\.assignTo\(null\) runs \(:206\)\. The call returns 200, TicketList\.tsx:265\-272 shows 'Ticket assigned\.', and an audit row is written\. Assignment never works, and the action clears any existing assignee\. The input is also a free\-text admin id \(TicketList\.tsx:437\-449\) with no admin picker\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Send \{ assignedTo: adminId \}, or add @JsonAlias\("adminId"\) on the record\. Reject a body that carries neither field\. Replace the free\-text id with a select of active admins\.

#### [High] \[Admin\] Comp expiry is never enforced: the renewal safety\-net job auto\-renews expired comped Pro subscriptions indefinitely
- **Where:** BE/job/SubscriptionRenewalResetJob\.java:116
- **Issue:** grantAdminPlan sets currentPeriodEnd to expiresAt, status ACTIVE and cancelAtPeriodEnd false \(SubscriptionService\.java:458\-482\)\. compExpiresAt has no reader \(grep: only the audit snapshot\)\. The nightly job \(SubscriptionRenewalResetJob\.java:99\) treats every ACTIVE subscription whose period has ended and whose cancelAtPeriodEnd is false as a missed webhook and renews it \(:116\-131 \-\> applyRenewalSafetyNet, SubscriptionService\.java:786\-796\)\. That extends the period by the same length and re\-applies the Pro AI allotment\. Neither job checks isComp\. The 'Expiry date' control in CompProModal \(BillingConsole\.tsx:204\) therefore does nothing: a comped workspace keeps Pro, the 7% brand fee \(BrandCampaignFeeService\.java:124\-125\) and its AI credits for good\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** In the job, or a dedicated comp\-expiry job, downgrade subscriptions where isComp is true and compExpiresAt is not after now to Free \(finalizeLapsedCancellation semantics\)\. Alternatively set cancelAtPeriodEnd=true when expiresAt is given\. Add a test\.

#### [Medium] \[Brand\] Team tab errors out for every non\-Owner/Admin member, then fails open to show the invite form
- **Where:** src/components/brand/settings/team\-members\-panel\.tsx:78\-89,101\-105
- **Issue:** load\(\) awaits Promise\.all\(\[list\(\), listInvites\(\)\]\); listInvites is OWNER/ADMIN only \(WorkspaceMemberService\.java:316\-320\), so the 403 rejects the whole load for MANAGER/MEMBER/VIEWER\. members stays empty → myRole null → canManage evaluates true \(:105\) and the invite form renders for someone the server rejects\. — Confidence: CONFIRMED\.
- **Fix:** Use Promise\.allSettled or fetch invites only once myRole is OWNER/ADMIN; treat 403 as 'not a manager'\.

#### [Medium] \[Brand \+ Creator\] Change Password silently revokes the caller's own session
- **Where:** influora\-api/src/main/java/com/influora/web/AccountController\.java:125\-133
- **Issue:** changePassword reads the refresh cookie to spare the caller's own token \(AuthService\.java:814\-819\) but the cookie is scoped Path=/api/v1/auth \(application\.yml:179\) so the browser never sends it to POST /api/v1/me/password\. The fallback revokeAllForUser runs; the FE says 'Password changed' \(brand\-settings\.tsx:528\) and the next refresh ejects the user to login with no explanation \(api\.ts:653\-657\)\. Creator side: identical behaviour from creator\-settings\.tsx:295\-302 \('Password updated', then ejected at next refresh\)\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Move the endpoint under /auth, or re\-establish the session on the FE after a successful change\.

#### [Medium] \[Brand\] No workspace\-role guard on KYC submit, company save, store connect/disconnect, webhook\-secret rotate; KYC resubmit resets a VERIFIED workspace to PENDING
- **Where:** influora\-api/src/main/java/com/influora/service/OnboardingService\.java:56\-80,138\-149
- **Issue:** submitBrandKyc and saveBrandCompany call only requireBrandWorkspace \(no requireRole\), unlike WorkspaceService\.updateMyWorkspace \(:96\-98\)\. Same gap in ShopifyConnectController\.java:79,118, WooCommerceConnectController\.java:64, StoreIntegrationStatusController\.java:117, ConversionWebhookSecretController\.java:52,65\. Workspace\.applyKyc sets PENDING unconditionally \(Workspace\.java:368\-375\), so any VIEWER can un\-verify the workspace and block campaign publishing, rename the workspace/slug, or rotate the conversion secret\. The FE claims 'the server checks permissions independently' \(brand\-verification\.tsx:73,90\-91\) — false\. — Confidence: CONFIRMED\.
- **Fix:** Add requireRole\(OWNER, ADMIN\) to all of these; refuse KYC resubmit when VERIFIED/PENDING unless an explicit re\-verify flow\.

#### [Medium] \[Brand\] Brand KYC decision notifies nobody and the rejection reason is never shown to the brand
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminBrandService\.java:280\-320
- **Issue:** verifyKyc only saves \+ audits; nothing publishes KycApprovedEvent/KycRejectedEvent anywhere in src/main\. FE promises 'We'll email you when it's approved' \(brand\-verification\.tsx:67\) and 'See what's needed' \(WorkspaceVerificationBanner\.tsx:29\-34\), but kycRejectionReason is absent from WorkspaceReadResponse \(WorkspaceMemberDtos\.java:54\-65\) and the page falls through to a blank form\. — Confidence: CONFIRMED\.
- **Fix:** Publish KYC approved/rejected events with template \+ in\-app row; add kycRejectionReason to WorkspaceReadResponse and render it\.

#### [Medium] \[Brand\] Reset\-password page reports 'link invalid or expired' for a weak\-password rejection
- **Where:** src/pages/brand\-reset\-password\.tsx:32\-38,59\-66
- **Issue:** Client checks only length\>=8; server runs PasswordPolicy\.validate \(upper/lower/digit/non\-common, PasswordPolicy\.java:35\-44\) before looking at the token \(AuthService\.java:646\)\. The catch maps every ApiError to the expired\-link copy, so users loop through forgot\-password\. The same page sends creators to /brand/login \(:58\)\. — Confidence: CONFIRMED\.
- **Fix:** Branch on err\.code; mirror the complexity rule client\-side; route by role\.

#### [Medium] \[Brand\] 'Generate signing secret' silently rotates the live conversion secret on any later visit
- **Where:** src/components/brand/settings/ConversionWebhookSecretCard\.tsx:37,152\-166
- **Issue:** Rotate confirmation is keyed on component\-local state that is null on every mount; no endpoint reports whether a secret exists \(only POST /generate and DELETE, ConversionWebhookSecretController\.java:50,63\)\. One click rotates and every store webhook starts failing INVALID\_WEBHOOK\_SIGNATURE\. Revoke client \(api\.ts:5200\) has no UI\. — Confidence: CONFIRMED\.
- **Fix:** Add GET status \{exists, createdAt\}; drive label \+ confirm from it; add a Revoke control\.

#### [Medium] \[Brand\] Notifications page and bell show only the first 20 notifications, no paging
- **Where:** src/lib/api\.ts:4200\-4204
- **Issue:** notifications\.list sends no page/size; controller defaults page=0,size=20 \(NotificationController\.java:85\-89\)\. The page has no load\-more \(brand\-notifications\.tsx:78\) while the header uses the server\-wide unreadCount, so '35 unread' can sit above 20 rows\. — Confidence: CONFIRMED\.
- **Fix:** Thread page/size through client \+ hook, add load\-more, return totalElements\.

#### [Medium] \[Brand\] Logout never clears the react\-query cache — next brand on the same tab inherits previous workspace identity/verification/onboarding state for up to 5 min
- **Where:** src/components/brand/brand\-layout\.tsx:254\-281
- **Issue:** handleLogout clears tokens and localStorage then SPA\-navigates; no queryClient\.clear\(\) anywhere in src/\. \['workspace','me'\] \(staleTime 5m\), \['workspace','my\-role'\] and \['brand\-onboarding\-status', true\] \(App\.tsx:172\-178\) survive\. A cached onboardingCompleted=true lets a not\-yet\-onboarded brand through ProtectedRoute \(App\.tsx:188\)\. — Confidence: CONFIRMED\.
- **Fix:** queryClient\.clear\(\) in handleLogout and handleLogoutAllDevices, or key queries by userId\.

#### [Medium] \[Brand\] Discovery, external\-creator and tracking writes skip membership and role checks
- **Where:** influora\-api/\.\.\./service/CreatorDiscoveryService\.java:445\-447,502\-505 ; web/CampaignTrackingController\.java:72,99 ; service/BrandContextService\.java:72\-105
- **Issue:** requireBrandWorkspace trusts the JWT's workspaceId and never checks active membership \(BrandContextService\.java:72\-105\)\. CampaignService, DealService \(:1572\-1578\) and the templates service all add requireMember and requireRole\. CreatorDiscoveryService and ExternalCreatorService contain no requireMember or requireRole call at all, and CampaignTrackingController creates coupons and links with only requireBrandWorkspace\. A VIEWER, or a removed member whose token is still valid, can invite creators onto live campaigns \(creating Collaboration rows\), create store coupons and UTM links, save creators and send connection requests\. The priced\-offer path \(POST /deals\) correctly requires OWNER, ADMIN or MANAGER\. CampaignTemplateService\.delete \(:120\-145\) also has no role gate\. — Confidence: CONFIRMED\.
- **Fix:** Add requireMember to every CreatorDiscoveryService, ExternalCreatorService and CampaignTrackingService entry point\. Add requireRole\(OWNER, ADMIN, MANAGER\) on invite, connect, createCoupon, createTrackingLink and template delete\.

#### [Medium] \[Brand\] The detail\-page Publish dialog lets an unverified workspace lock its budget before the server refuses activation
- **Where:** src/pages/brand\-campaign\-detail\.tsx:1190\-1208 ; src/components/brand/campaigns/secure\-and\-publish\-step\.tsx:212\-220 ; influora\-api/\.\.\./service/CampaignService\.java:272\-274
- **Issue:** campaign\-form\.tsx:1741 and brand\-new\-hype\-campaign\.tsx:251\-254 stop an unverified publish before funding\. brand\-campaign\-detail\.tsx has no useWorkspaceVerification, and neither SecureAndPublishStep nor EscrowService\.initiateFund checks verification\. Sequence: fund succeeds and the wallet is debited into the hold, then PATCH ACTIVE returns 403 WORKSPACE\_NOT\_VERIFIED \(CampaignValidator\.java:62\-70\)\. Given finding 1, that money has no self\-serve way back\. The same happens from the forms when the verification state is still unknown, because isPublishDisabled only blocks on a known false\. — Confidence: CONFIRMED\.
- **Fix:** Move the verification check into SecureAndPublishStep so it runs before the fund control renders\. Also reject campaign\-level funding for unverified workspaces server\-side\.

#### [Medium] \[Brand\] The publish fee is charged after the wallet is drained for the budget, so a first publish commonly fails with 402
- **Where:** src/components/brand/campaigns/secure\-and\-publish\-step\.tsx:158\-161,68\-70 ; influora\-api/\.\.\./service/CampaignActivationGuard\.java:55\-58 ; service/BrandCampaignFeeService\.java:246\-249
- **Issue:** The inline top\-up covers only the server\-computed budget shortfall \(useEscrowFund; EscrowService\.java:325\-330\)\. Immediately afterwards chargeOnPublish needs fee = budget x bps from the same wallet, which the hold has just emptied, so it returns INSUFFICIENT\_WALLET\_BALANCE\_FOR\_PUBLISH\. The FE handles the error \(top\-up link\), but the brand must leave the flow, top up again, reopen the draft and publish from the detail menu\. The list has no Publish action for DRAFT rows \(campaigns\-list\.tsx:824\-840\)\. — Confidence: CONFIRMED\.
- **Fix:** Return budget plus fee as the required amount in the step, or pre\-check and top up for both in one go\. Add a Publish action for DRAFT rows in the list\.

#### [Medium] \[Brand\] Publishing a saved draft later activates it with a stale timeline; a HYPE campaign can go live with its 72\-hour window already expired
- **Where:** src/pages/brand\-new\-hype\-campaign\.tsx:290\-295 ; src/components/brand/campaigns/secure\-and\-publish\-step\.tsx:144 ; influora\-api/\.\.\./service/CampaignService\.java:261 ; service/CampaignValidator\.java:131\-166
- **Issue:** The hype form computes liveUntil and the timeline at the moment the DRAFT is saved\. Publishing later from the detail dialog sends only \{status:'ACTIVE'\}\. update\(\) validates the timeline only when the patch carries one \(:261\), and validateHypeConfig never checks liveUntil\. Result: a HYPE campaign can activate with liveUntil in the past, and a standard campaign with past start and end dates, after the fee is charged\. — Confidence: CONFIRMED\.
- **Fix:** At the \-\>ACTIVE edge, reject endDate or hype\.liveUntil at or before now\. For HYPE, recompute liveUntil as now \+ 72h inside the activation transaction\.

#### [Medium] \[Brand\] Discover invite modal's "Create new campaign" drops the creator handoff because of a query\-param mismatch
- **Where:** src/components/brand/discover/creator\-discovery\.tsx:561 ; src/pages/brand\-new\-campaign\.tsx:87\-90 ; src/components/brand/campaigns/campaign\-form\.tsx:384
- **Issue:** The modal navigates to /brand/campaigns/new?creator=\<id\>\. The type picker and wizard read only creatorId \(and ig\)\. There is no banner, no stashPendingCreatorInvite and no invite after publish\. The external\-creator card uses the correct key \(creator\-discovery\.tsx:2058\)\. brand\-creator\-profile\.tsx:518 sends no parameter at all\. — Confidence: CONFIRMED\.
- **Fix:** Use ?creatorId=$\{inviteCreator\.id\} \(plus ig\) at creator\-discovery\.tsx:561 and brand\-creator\-profile\.tsx:518\.

#### [Medium] \[Brand\] The saved\-creator plan\-cap error \(402\) is swallowed, so the save just bounces with a generic toast
- **Where:** src/components/brand/discover/creator\-discovery\.tsx:685\-692 ; influora\-api/\.\.\./service/CreatorDiscoveryService\.java:459\-462,486\-498
- **Issue:** toggleSaved now throws 402 UPGRADE\_REQUIRED with a clear message about the saved\-creators limit and the upgrade path \(commit ab79377\)\. The FE catch has no error binding and shows only 'Could not update saved list'\. The only UPGRADE\_REQUIRED handler in src is useCreatorMetrics\.ts:34\. A Free brand at its cap sees the bookmark flip back with no explanation and no upgrade path\. — Confidence: CONFIRMED\.
- **Fix:** Catch the ApiError\. On status 402 or code UPGRADE\_REQUIRED, show err\.message with a link to /brand/settings/billing\.

#### [Medium] \[Brand\] Creator profile Save \(bookmark\) button is a local\-state stub
- **Where:** src/pages/brand\-creator\-profile\.tsx:455,584\-591 ; src/lib/api\.ts:2103\-2106,2026
- **Issue:** The click handler only toggles local state and never calls api\.creators\.toggleSaved\. The initial value is always false even though GET /creators/profile/:id returns saved \(CreatorDiscoveryService\.java:267\-271\)\. The icon shows 'saved', nothing persists, and the state resets on reload\. This is live\-mode behaviour, not mock\-only\. — Confidence: CONFIRMED\.
- **Fix:** Initialise from row\.saved\. Call toggleSaved with optimistic rollback and the same 402 handling as above\.

#### [Medium] \[Brand\] The platform\-fee estimate on campaign detail uses the global rate while the charge uses the plan rate
- **Where:** src/pages/brand\-campaign\-detail\.tsx:2106\-2111 ; influora\-api/\.\.\./service/BrandPlatformFeeService\.java:53\-58 ; service/BrandCampaignFeeService\.java:113\-127,224
- **Issue:** GET /brand/platform\-fee returns the global config bps with source GLOBAL\_DEFAULT\. chargeOnPublish uses resolveBrandFeeBps \(Pro plan feeBps\)\. A Pro brand sees a 10% estimate and is charged 7%\. The endpoint also requires OWNER, ADMIN or MANAGER, so other roles get a 403 that the page swallows to null\. Also seen in the wallet/billing slice: the platform\-fee disclosure hardcodes '\(10%\)' and ignores the plan rate\. — Confidence: CONFIRMED\.
- **Fix:** Have getCurrentFee call BrandCampaignFeeService\.resolveBrandFeeBps\(workspaceId\) and return source PLAN or GLOBAL\.

#### [Medium] \[Brand\] The tracking page requires brands to type raw internal ids
- **Where:** src/components/campaigns/tracking/UTMGeneratorForm\.tsx:95\-110 ; src/components/campaigns/tracking/CouponCodeGenerator\.tsx:103\-110
- **Issue:** The UTM form has free\-text inputs for Collaboration ID \('col\_xxxxxxxx'\) and Creator Profile ID \('cr\_xxxxxxxx'\), and the coupon form for Creator Profile ID\. Real ids are bare ULIDs \(Ulids\.newUlid\) and are never shown as copyable values in the brand UI\. UTMGeneratorForm\.tsx:42 needs both ids and CouponCodeGenerator\.tsx:43 needs the profile id\. The feature works only for someone copying ids out of URLs\. — Confidence: CONFIRMED\.
- **Fix:** Replace the inputs with a select of this campaign's collaborations \(GET /deals filtered by campaignId\), supplying dealId and counterpartyProfileId\.

#### [Medium] \[Brand\] The /brand/deals counter dialog sends no idempotency key, so a repeat counter at the same amount is swallowed as success
- **Where:** src/components/brand/deals/deal\-room\-dashboard\.tsx:485
- **Issue:** dealsApi\.counter is called without the fourth idempotencyKey argument \(dashboard:485\-489; the argument is optional at api\.ts:2527\)\. The server key becomes 'deal\-counter:'\+dealId\+':'\+amount \(DealService\.java:583\) and stays COMPLETED forever\. A second brand counter at the same amount on the same deal, for example after the creator countered in between, takes the AlreadyCompleted branch and returns 200 with the unchanged deal \(DealService:593\-596\)\. The dialog closes as a success and no offer is sent\. brand\-chat\.tsx:1638 already passes a fresh key\. — Confidence: CONFIRMED\.
- **Fix:** Pass \`$\{dealId\}\-counter\-$\{Date\.now\(\)\}\` or a UUID from the dashboard, as brand\-chat does\. Make the server fallback key non\-permanent\.

#### [Medium] \[Brand\] 'Send Proposal' on /brand/deals is a mislabelled dead control, and an IN\_NEGOTIATION deal has no accept, counter or reject there
- **Where:** src/components/brand/deals/deal\-room\-dashboard\.tsx:800
- **Issue:** For 'negotiating' deals, which is every deal after any counter, the primary button 'Send Proposal' only calls setShowProposalDialog\(true\) \(dashboard:798\-803\)\. That opens the read\-only 'Proposal Details' dialog, and its action buttons are gated on status==='proposed' \(dashboard:1054\), so only Close is offered\. The page header claims 'accept, counter, or reject in one place' \(dashboard:546\)\. The backend allows accept, counter and reject in IN\_NEGOTIATION \(Collaboration\.java:351\-360, :387\-393\)\. After a successful accept the only control is 'View Contract', which goes to the bare /brand/contracts \(dashboard:815\), although no contract exists yet and generation is available only in brand\-chat\.tsx:1764\. — Confidence: CONFIRMED\.
- **Fix:** Wire the button to a real counter or proposal form, or relabel it and route it to /brand/chat?deal=\. Render Accept, Counter and Reject from the latest proposal message\. After an accept, link to /brand/chat?deal=\<id\>\&tab=contract\.

#### [Medium] \[Brand\] ProposalForm deliverable type strings never match the DeliverableType enum, so every slot silently becomes INSTAGRAM\_REEL
- **Where:** src/components/brand/deal\-room/proposal\-form\.tsx:75
- **Issue:** The wizard sends the type values 'Instagram Reel', 'TikTok Video', 'Instagram Story Set', 'Instagram Feed Post', 'YouTube Video' and 'Blog Post' verbatim \(proposal\-form\.tsx:75\-81; brand\-chat\.tsx:1615\-1617\)\. DeliverableSlot\.type is a free String \(DealDtos\.java\)\. At contract generation, parseDeliverableType calls DeliverableType\.valueOf\(raw\.trim\(\)\.toUpperCase\(\)\) \(ContractService\.java:816\-825\)\. 'INSTAGRAM REEL' contains a space and does not match INSTAGRAM\_REEL, so every value, including YouTube Video and Blog Post, lands in the catch block and defaults to INSTAGRAM\_REEL\. The Deliverable rows are created with the wrong type and with titles such as 'INSTAGRAM REEL \#1' \(ContractService\.java:773\)\. — Confidence: CONFIRMED\.
- **Fix:** Send the enum constants from the form, mapping label to INSTAGRAM\_REEL, TIKTOK\_VIDEO, YOUTUBE\_VIDEO and so on, and remove options that have no enum value\. On the server, validate DeliverableSlot\.type against the enum and return 400 instead of silently defaulting\.

#### [Medium] \[Brand\] Disputed deals vanish from /brand/chat permanently, and the 'View deal room' link opens a different deal
- **Where:** src/pages/brand\-chat\.tsx:228
- **Issue:** mapDealStatusToChatStatus returns null for DISPUTED and CANCELLED, and the row is dropped \(brand\-chat\.tsx:228\-229, :237, :848\)\. When the ?deal= id is not in the list, the selection falls back to liveDealRooms\[0\] \(brand\-chat\.tsx:1025\-1030\)\. The 'View deal room' link on /brand/disputes \(src/pages/brand\-disputes\.tsx:146\) therefore opens an unrelated creator's room\. Backend links of the form '/brand/chat?deal=' do the same\. DISPUTED is never left once set: DisputeService\.java:144 is the only writer, and resolveDispute never transitions the collaboration\. CollaborationLifecycleService treats DISPUTED as frozen \(CollaborationLifecycleService\.java:55\-56\)\. Even after a resolution the deal stays hidden from chat, shows as 'Rejected' on /brand/deals \(deal\-room\-dashboard\.tsx:94\-96\), and can never be reviewed because reviews require COMPLETED \(ReviewService\.java:117\)\. The backend still allows messaging in a disputed deal \(DealService\.java:618\-665 has no status gate\)\. After a dispute is opened, handleOpenDispute also does not refresh the deal \(brand\-chat\.tsx:1190\-1196\)\. — Confidence: CONFIRMED\.
- **Fix:** Give the brand chat list a 'disputed' bucket that keeps the room reachable with money actions disabled\. Show a 'deal not available' state instead of falling back to the first room when a requested deal or creator is missing\. Transition the collaboration out of DISPUTED when the dispute resolves\.

#### [Medium] \[Brand\] The dispute resolution notes and date are never delivered to the brand
- **Where:** src/pages/brand\-disputes\.tsx:135
- **Issue:** The page renders a Resolution block from dispute\.resolutionNotes and dispute\.resolvedAt \(brand\-disputes\.tsx:135\-142; declared at src/lib/api\.ts:6301\-6302\)\. DisputeListItemResponse has only collaborationId, campaignName, counterpartyName, dealValue, currency, disputeStatus, openedAt and reason \(DisputeDtos\.java DisputeListItemResponse; built at DisputeService\.java:533\-541\)\. There is no per\-dispute detail endpoint for brands; BrandDisputeController exposes only GET /list at :40\. The admin's written decision never reaches the brand\. The brand sees only the status label\. The list key is collaborationId \(brand\-disputes\.tsx:100\), so two disputes on one deal would collide\. — Confidence: CONFIRMED\.
- **Fix:** Add resolutionNotes, resolvedAt and the dispute id to DisputeListItemResponse, populate them from the Dispute row, and key the list by dispute id\.

#### [Medium] \[Brand\] Live mode hides the shipment state, including a DAMAGED report, and offers no re\-ship control although the backend supports it
- **Where:** src/pages/brand\-chat\.tsx:2388
- **Issue:** The 'Shipping address received' notice and the ShipmentCard sit inside the demo\-only block \`\{\!isApiLive\(\) \&\& \(…\)\}\` \(brand\-chat\.tsx:2388\-2667; notice at :2604, card at :2629\)\. In live mode the only shipment UI is the header 'Ship Product' button, gated on dealStatus==='in\_progress' \&\& shippingAddress \&\& \!shipment \(brand\-chat\.tsx:2151\)\. Once the product is shipped, the brand can no longer see the carrier, the tracking number, or the RECEIVED or DAMAGED status\. ShipmentService allows re\-shipping from DAMAGED \(ShipmentService\.java:163\-164\), but \`shipment\` is non\-null for DAMAGED \(brand\-chat\.tsx:1102\-1115\), so the button is hidden\. The shipment\-received notification link is also dead \(finding 9\)\. — Confidence: CONFIRMED\.
- **Fix:** Render the ShipmentCard and the address notice in live mode from liveShipment\. Show a re\-ship call to action when the status is DAMAGED\.

#### [Medium] \[Brand\] The contract tab claims funds are 'locked' as soon as both parties sign, regardless of the real escrow state
- **Where:** src/components/brand/deal\-room/deal\-contract\-tab\.tsx:366
- **Issue:** isActive is status==='active' \|\| status==='creator\_signed' \(deal\-contract\-tab\.tsx:198\)\. mapApiContractToDealStatus returns 'active' for any ACTIVE contract even when escrowFunded is false \(src/lib/creator\-contract\-mappers\.ts, the branch \`escrowFunded \|\| contract\.status === 'ACTIVE' ? 'active'\`\)\. The Secure Payments card then states '₹X is locked and will release on approved deliverables' \(deal\-contract\-tab\.tsx:366\-368\)\. Signing moves no money, and funding is a separate brand action \(EscrowService\.java:226\)\. The sibling Payments tab already reads the real escrowFunded flag \(deal\-payments\-tab\.tsx:87\)\. For the unsigned case the same card says funds 'will be locked when both parties have signed', which is also untrue\. — Confidence: CONFIRMED\.
- **Fix:** Pass escrowFunded and the milestone statuses into DealContractTab and drive the card from them\. Reword the unsigned copy to 'fund after both sign'\.

#### [Medium] \[Brand\] Contracts are generated and e\-signed with no terms text; the negotiated deliverables, usage rights and exclusivity never enter the contract
- **Where:** src/pages/brand\-chat\.tsx:984
- **Issue:** handleGenerateContract sends only \{collaborationId, milestones\} \(brand\-chat\.tsx:984\-992\)\. DealContractGenerate has no terms input \(deal\-contract\-generate\.tsx:41\-172\)\. ContractGenerateRequest\.terms is optional and is persisted as null \(ContractService\.java:299\)\. The sign panel then shows 'No terms are on file for this contract\.' \(deal\-contract\-tab\.tsx:323\-326\) directly above a signature the UI describes as binding under the IT Act\. The generate toast says the creator will be notified after the brand signs \(brand\-chat\.tsx:993\-996\), but the server notifies the creator at generation \(ContractService\.java:386\)\. The mismatch hint says 'the server will use the milestone total' \(deal\-contract\-generate\.tsx:160\-164\), but a total above agreedRate returns 400 \(ContractService\.java:279\-285\)\. — Confidence: CONFIRMED\.
- **Fix:** Compose the terms text from the accepted proposal and the Collaboration deal terms, server\-side, and persist it at generate time; block generation when no terms can be composed\. Correct the toast and the mismatch copy\.

#### [Medium] \[Brand\] The Sign dialog on /brand/contracts asks the brand to 'review the terms' but shows none and does not gate on them
- **Where:** src/components/brand/contracts/contracts\-and\-deliverables\.tsx:1816
- **Issue:** adaptContractRecord never maps rec\.terms \(contracts\-and\-deliverables\.tsx:561\-590\)\. In live mode clauses and deliverables are always \[\] \(contracts:475\-476\)\. The Sign dialog summary therefore reads '0 deliverables \| ₹X \| Due …' \(contracts:1824\) under the 'legally bound… IT Act 2000' copy \(contracts:1845\-1848\)\. The deal\-room tab blocks signing until the real contract record loads \(deal\-contract\-tab\.tsx:410\); this page has no equivalent gate\. signatureText is not trimmed, so a whitespace\-only name enables Sign \(contracts:889, :1853\)\. — Confidence: CONFIRMED\.
- **Fix:** Map the terms and the milestones into the row and render them in the Terms tab and the Sign dialog\. Disable Sign until the record has loaded, and trim and validate the name\.

#### [Medium] \[Brand\] Backend state\-machine and authorization gaps around contracts: any workspace member can brand\-sign, a CANCELLED contract can be re\-signed back to life, and generate has no TERMS\_AGREED precondition
- **Where:** influora\-api/src/main/java/com/influora/service/ContractService\.java:903
- **Issue:** First, recordSignature resolves \`member\` but never calls requireRole \(ContractService\.java:903\-904\), while generate, cancel and amend require OWNER, ADMIN or MANAGER \(:148, :1033, :443\)\. A VIEWER can therefore execute the binding brand signature\. Second, doRecordSignature checks only the collaboration's CANCELLED status \(:1105\), and Contract\.advanceIfFullySigned unconditionally sets PENDING\_SIGNATURES or ACTIVE \(Contract\.java:211\-217\)\. Signing a CANCELLED contract, for example one cancelled through /contracts/\{id\}/cancel or superseded by amend, resurrects it\. The frontend would offer this, because mapApiContractToDealStatus returns 'generated' for a CANCELLED contract \(src/lib/creator\-contract\-mappers\.ts, final return\) and canBrandSign is true \(deal\-contract\-tab\.tsx:197\)\. Third, generate\(\) rejects only CANCELLED collaborations \(ContractService\.java:214\-219\), and CollaborationLifecycleService allows INVITED, APPLIED and IN\_NEGOTIATION to advance to CONTRACT\_PENDING \(CollaborationLifecycleService\.java:58\-64\)\. POST /contracts can therefore skip doAccept's budget and collaborator\-cap gates \(DealService\.java:1141, :1147\) and the agreedRate bound \(ContractService\.java:279\)\. Only the frontend enforces TERMS\_AGREED \(brand\-chat\.tsx:1764\-1766\)\. — Confidence: CONFIRMED\.
- **Fix:** Add requireRole\(OWNER, ADMIN, MANAGER\) to recordSignature\. Refuse signing unless the contract status is DRAFT or PENDING\_SIGNATURES\. Require collaboration\.status == TERMS\_AGREED and a non\-null agreedRate in generate\(\)\. Map CANCELLED explicitly in the frontend mappers\.

#### [Medium] \[Brand \+ Creator\] Unread counts include the viewer's own messages and system rows, and the badges never clear without a reload
- **Where:** influora\-api/src/main/java/com/influora/service/DealService\.java:2074
- **Issue:** unreadCount counts every message whose readBy lacks the viewer's userId \(DealService\.java:2074\-2080\)\. New messages start with readBy '\[\]' and the sender is never added \(DealMessage\.java:102; DealService\.java:635\-644\)\. A brand's own sent messages and all system or lifecycle rows therefore count as unread until markRead next runs\. The frontend fires markRead only when a thread loads \(deal\-room\-dashboard\.tsx:301; brand\-messages\.tsx:433; brand\-chat\.tsx:1226\)\. It never fires for SSE frames and never updates or refetches the list row's unreadCount \(brand\-messages\.tsx:284\-305, :426\-440\)\. Badges persist for the thread that is currently open\. Creator side: inflates the dashboard 'unread messages' tile \(creator\-dashboard\.tsx:143\) and deal badges \(creator\-deals\.tsx:599,732\)\. — Confidence: CONFIRMED\.
- **Fix:** Seed readBy with the senderId, exclude the viewer's own messages and system messages from the count, zero the local badge after markRead, and call markRead for incoming SSE frames\.

#### [Medium] \[Brand\] Wallet 'Payouts to creators' tab is always empty in live mode
- **Where:** src/pages/brand\-wallet\.tsx:723 ; influora\-api/src/main/java/com/influora/service/escrow/LedgerEscrowBackend\.java:74\-96
- **Issue:** The tab filters the BRAND wallet's transactions for ESCROW\_RELEASE\. That type is posted clearing wallet to creator wallet only, and GET /wallet/transactions is scoped to the caller's own wallet \(WalletService\.java:510\-516\)\. Brand rows can only be DEPOSIT, ESCROW\_HOLD, ESCROW\_REFUND or PLATFORM\_FEE\. — Confidence: CONFIRMED\.
- **Fix:** Back the tab with a brand\-scoped query over RELEASED escrow holds or milestones \(payee, campaign, amount, releasedAt\)\.

#### [Medium] \[Brand\] Top\-up dialog reports 'Payment received' without confirmation, and the payment\-method picker is fake
- **Where:** src/pages/brand\-wallet\.tsx:594\-599,850,951\-1003
- **Issue:** handleTopUpCheckoutSuccess does a single immediate refetch, then sets 'confirmed' \(green check, 'Payment received'\) whether or not the webhook has credited\. There is no polling, and the status row keeps showing the order's PENDING\. The UPI/Card/Net Banking selector is never passed to Checkout \(razorpay\.ts:167\-181 has no method or config\), yet it states '2% convenience fee' and 'no fees'\. No such fee logic exists anywhere\. — Confidence: CONFIRMED\.
- **Fix:** Poll GET /wallet, or a top\-up status endpoint, until CREDITED or a timeout, and only then show success\. Remove the selector or wire it through with truthful fee copy\.

#### [Medium] \[Brand\] Failed renewal \(PAST\_DUE/HALTED\) is invisible; the brand sees Free plus Upgrade and cannot cancel
- **Where:** src/pages/brand\-billing\-settings\.tsx:319\-321,460 ; influora\-api/src/main/java/com/influora/service/billing/SubscriptionService\.java:217\-222,342\-348
- **Issue:** subscription\.status is sent but never read by the page\. getActivePlanForWorkspace returns Free for any non\-ACTIVE status, so a Pro subscriber in dunning sees 'Free Plan' and the cancel control is hidden \(\!isFreeTier\)\. Clicking Upgrade passes the ALREADY\_SUBSCRIBED guard \(status is not ACTIVE\) and creates a SECOND Razorpay subscription while the first is still being retried\. That is the two\-subscription overwrite hazard described in SubscriptionService\.java:268\-279\. — Confidence: CONFIRMED\.
- **Fix:** Render PAST\_DUE/HALTED states with an update\-payment path\. Block initiateCheckout while a linked non\-terminal Razorpay subscription exists\.

#### [Medium] \[Brand\] 'Tracked/Saved Creators' usage meter always reads 0
- **Where:** influora\-api/src/main/java/com/influora/web/BillingController\.java:128 ; influora\-api/src/main/java/com/influora/service/CreatorDiscoveryService\.java:488
- **Issue:** The meter reads UsageMetric\.TRACKED\_CREATOR from usage counters, and nothing in src/main writes that metric \(it is the only reference\)\. Enforcement uses savedCreatorRepository\.countByWorkspaceIdAndSavedTrue\. A Free brand at 5 of 5 sees '0 of 5' and then gets blocked\. — Confidence: CONFIRMED\.
- **Fix:** Return the saved\-creator row count in the usage summary response\.

#### [Medium] \[Brand\] Analytics view quota is consumed before authorization; one page load burns the Free quota
- **Where:** influora\-api/src/main/java/com/influora/security/AnalyticsUsageCapInterceptor\.java \(preHandle, registered in PlanGateWebConfig\.java:39\-40\) ; src/pages/brand\-analytics\.tsx:57
- **Issue:** The interceptor consumes CREATOR\_ANALYTICS\_VIEWS \(Free limit 1, V55 seed\) before AnalyticsService authorizes, so 403 and 404 responses still spend quota\. The aggregate overview fires one metrics call per roster creator in parallel, so an arbitrary creator takes the single monthly view and the rest return 402\. — Confidence: CONFIRMED\.
- **Fix:** Meter after successful authorization \(in the service or a post\-handle hook\)\. Provide a non\-metered aggregate endpoint for the overview\.

#### [Medium] \[Brand\] Analytics tiles are mislabelled, ignore the date range, and the 'combined' trend never aligns
- **Where:** influora\-api/src/main/java/com/influora/service/analytics/AnalyticsService\.java:125\-141,156\-170 ; src/pages/brand\-analytics\.tsx:65\-123
- **Issue:** 'Total Reach' and 'Total Impressions' are the latest snapshot's average per post\. 'Total Engagements' is followers multiplied by engagement rate, which is an estimate\. None of them use startDate or endDate\. The FE sums these across creators and labels the result 'Combined performance'\. trendData\.date is a full ISO instant \(MetricsPollingJob\.java:244 uses Instant\.now\(\) per creator\), and the FE buckets by the exact string \(:98\-113\), so points from different creators never merge\. — Confidence: CONFIRMED\.
- **Fix:** Compute true range totals server\-side, rename the tiles, and bucket the trend by UTC day\.

#### [Medium] \[Brand\] Secured amount is never reconciled with the campaign budget; raise the budget after funding and the campaign goes live under\-secured
- **Where:** influora\-api/src/main/java/com/influora/service/CampaignActivationGuard\.java:71\-74 ; influora\-api/src/main/java/com/influora/service/EscrowService\.java:303\-316
- **Issue:** The activation guard only checks that any FUNDED hold exists\. The budget is editable while DRAFT or PAUSED \(CampaignValidator ensureEditable :100\-108\)\. A second fund attempt for the same scope returns the existing hold instead of topping it up\. The fee delta IS charged on the new budget \(BrandCampaignFeeService\.java:236\-237\), so fee and secured funds diverge\. — Confidence: CONFIRMED\.
- **Fix:** In the guard, compare the FUNDED total against deriveFundAmount and return 409 with the shortfall\. Allow a top\-up hold for the difference\.

#### [Medium] \[Brand\] Wallet 'Secure Campaign Funds' and the Meera funding handoff point brands to a control that cannot list their campaign
- **Where:** src/pages/brand\-wallet\.tsx:488,556\-568 ; src/components/feature/meera/MeeraChatPanel\.tsx:324\-331 ; influora\-api/src/main/java/com/influora/service/CampaignActivationGuard\.java:55
- **Issue:** The wallet lists ACTIVE campaigns only and excludes those with a PENDING or FUNDED hold\. A campaign cannot become ACTIVE without a FUNDED hold\. So a draft that needs first\-time funding never appears\. After create\_campaign, Meera tells the brand 'once it's active, your wallet has a Secure Campaign Funds card' and links to /brand/wallet\. The working route is the campaign page's publish step \(brand\-campaign\-detail\.tsx:600\-605\)\. — Confidence: CONFIRMED\.
- **Fix:** List DRAFT and PENDING\_APPROVAL campaigns that have a budget in the wallet card, or link the handoff to /brand/campaigns/\{id\}\. Update the matching persona copy in influora\-ai\.

#### [Medium] \[Brand\] Removed workspace members keep billing and Meera access until their token expires
- **Where:** influora\-api/src/main/java/com/influora/service/BrandContextService\.java \(requireBrandWorkspace\) ; BillingController\.java getPlan/getInvoices/getUsage/getInvoicePdf ; MeeraController\.java:98,123
- **Issue:** requireBrandWorkspace trusts the JWT's workspaceId claim and does not check membership\. These endpoints never call requireMember, unlike WalletController\.java:68\. A deactivated member can read invoices and spend the workspace's AI credits for up to 900 seconds \(application\.yml:205\)\. — Confidence: CONFIRMED\.
- **Fix:** Call requireMember on these routes, or fold the check into requireBrandWorkspace\.

#### [Medium] \[Creator\] /meta/oauth/status reports connected:true for a token with no Instagram business account
- **Where:** influora\-api/src/main/java/com/influora/service/MetaConnectionService\.java:145
- **Issue:** On the Facebook path the token is stored even when no IG professional account resolves, and the callback returns connected:false / 'personal' \(CreatorMetaOAuthService\.java:142\-156\)\. getStatus returns connected:true for any unrevoked, unexpired row \(MetaConnectionService\.java:78,145\) without checking igBusinessAccountId\. The FE hides this only through a localStorage accountType \(useMetaConnection\.ts:62\), which logout wipes \(auth\-session\.ts:298\)\. After a re\-login or on another device Settings shows 'Connected' while sync answers NOT\_CONNECTED \(PortfolioService\.java:385\-390\)\. — Confidence: CONFIRMED\.
- **Fix:** Make getStatus return connected=false, or add accountType to the response, when igBusinessAccountId is blank\. Drop the localStorage override\.

#### [Medium] \[Creator\] 'Remember me' is never sent to the server
- **Where:** src/pages/creator\-login\.tsx:39
- **Issue:** creatorLogin posts \{email,password\} only \(creator\-login\.tsx:39; LoginPayload at api\.ts:970\)\. The checkbox value feeds only api\.auth\.setToken \(line 40\), which decides where a non\-credential hint is stored\. The server treats a null rememberMe as true \(LoginRequest\.java:34,41\), so it issues the long\-lived refresh token and persistent cookie \(AuthController\.java:121\)\. The route guard restores the session from that cookie regardless of the hint \(App\.tsx:125\-142\), so unchecking the box on a shared device changes nothing\. — Confidence: CONFIRMED\.
- **Fix:** Add rememberMe to LoginPayload and send it from both login pages\.

#### [Medium] \[Creator\] Creator password reset always ends on the brand login page
- **Where:** src/pages/brand\-reset\-password\.tsx:58
- **Issue:** The reset link carries no role \(AuthService\.java:857 builds webBaseUrl \+ '/reset\-password?token='\)\. The single page navigates to /brand/login on success and on both buttons \(brand\-reset\-password\.tsx:58,126,133\)\. A creator who signs in there gets a 403 WRONG\_USER\_TYPE 'This endpoint is for brand accounts only' \(AuthService\.java:262\-264\)\. — Confidence: CONFIRMED\.
- **Fix:** Append the role to the link from user\.getUserType\(\), or return userType from /auth/reset\-password, and route to /creator/login\.

#### [Medium] \[Creator\] Forgot\-password and reset\-password forms render white text on a white card
- **Where:** src/pages/creator\-forgot\-password\.tsx:63
- **Issue:** The heading uses text\-white on bg\-card and the input uses text\-white on bg\-muted/50 \(creator\-forgot\-password\.tsx:45,63; brand\-reset\-password\.tsx:77,96,111\)\. The default theme defines \-\-card as \#ffffff and \-\-muted as \#ebe4f8 \(src/app/globals\.css:16,24\), and the \.dark class is never applied anywhere\. The typed email and password are effectively invisible\. — Confidence: CONFIRMED\.
- **Fix:** Use text\-foreground and border\-input like creator\-login\.tsx\.

#### [Medium] \[Creator\] Onboarding step 2: a blank Max rate is sent as 0, so the server rejects the save with a developer\-worded error
- **Where:** src/pages/creator\-onboarding\.tsx:223
- **Issue:** canProceed requires only rateMin \(creator\-onboarding\.tsx:199\), but the payload sends rateMax: Number\(''\) \|\| 0 \(line 223\)\. The server throws INVALID\_RATE\_RANGE 'rateMin cannot exceed rateMax' \(CreatorOnboardingService\.java:96\-98\)\. The creator is stuck on step 2 with a toast that names no field, and the label gives no hint that Max is required \(line 794\)\. — Confidence: CONFIRMED\.
- **Fix:** Require rateMax in canProceed or default rateMax to rateMin, and map INVALID\_RATE\_RANGE to an inline field error\.

#### [Medium] \[Creator\] Portfolio 'Niches' input swallows commas, so a second niche cannot be typed
- **Where:** src/pages/creator\-portfolio\-editor\.tsx:440
- **Issue:** The controlled input has value page\.niches\.join\(', '\) and an onChange that does split\(','\)\.map\(trim\)\.filter\(Boolean\) \(creator\-portfolio\-editor\.tsx:440\-448\)\. Typing 'Fashion,' parses to \['Fashion'\] and re\-renders as 'Fashion', so the comma and any trailing space vanish on every keystroke\. Only pasting a full list works, despite the label 'up to 3, comma separated'\. — Confidence: CONFIRMED\.
- **Fix:** Keep the raw string in local state and parse on blur, or switch to a chip input\.

#### [Medium] \[Creator\] Portfolio visibility toggles are mostly client\-side; badges are wrongly tied to trustBar; the contact endpoint ignores contactForm
- **Where:** influora\-api/src/main/java/com/influora/service/portfolio/PortfolioService\.java:798
- **Issue:** assemble\(\) gates badges on visibility\.trustBar \(PortfolioService\.java:798\) and never reads visibility\.badges\. The editor has a separate Badges toggle \(creator\-portfolio\-editor\.tsx:477\) and the public page gates on visibility\.badges \(creator\-portfolio\-public\.tsx:492\), so trustBar off plus badges on never shows badges\. stats, platforms and languages are always serialised to the unauthenticated endpoint \(PortfolioService\.java:797,799,808\) and hidden only in the browser \(creator\-portfolio\-public\.tsx:458,506,600\)\. contact\(\) checks only discoverable \(PortfolioService\.java:705\-707\), so POST /portfolio/\{u\}/contact still reaches a creator who switched the form off \(creator\-portfolio\-editor\.tsx:517\)\. — Confidence: CONFIRMED\.
- **Fix:** Apply each visibility flag server\-side when publicView is true \(badges by \.badges\(\), platforms by \.platformStats\(\), languages and topCities by \.languages\(\), stats by \.trustBar\(\)\), and reject contact when contactForm is false\.

#### [Medium] \[Creator\] The first portfolio Save freezes the public rate card; later profile rate edits never reach it
- **Where:** influora\-api/src/main/java/com/influora/service/portfolio/PortfolioService\.java:1119
- **Issue:** The editor has no rate\-row UI \(rateCard appears only at creator\-portfolio\-editor\.tsx:164,529\-531\), but handleSave always posts page\.rateCard\. Those are the fallback rows synthesised from rateMin/rateMax \(PortfolioService\.java:1124\-1129\), and updateMine persists them as stored rows \(PortfolioService\.java:333\-343\)\. From then on buildRateCard returns the stored rows first \(1119\-1122\)\. A creator who later changes rates on /creator/profile \(CreatorProfileService\.patchMyProfile updates only the rateMin/rateMax columns\) keeps the old prices on the public page indefinitely\. — Confidence: CONFIRMED\.
- **Fix:** Do not send rateCard from the editor until a row editor exists; or have patchMyProfile rewrite the stored rows, or let buildRateCard ignore stored rows whose ids are the two fallback ids\.

#### [Medium] \[Creator\] 'Resend code' sends nothing for 5 minutes, and signing up with an already\-registered email silently sends no OTP
- **Where:** influora\-api/src/main/java/com/influora/service/BrandEmailOtpService\.java:137
- **Issue:** While an unexpired challenge exists, sendOtp returns 'OTP sent successfully' without mailing \(BrandEmailOtpService\.java:137\-140,214\-229\)\. The gate's Resend button re\-arms its 30s timer as if a mail went out \(email\-otp\-gate\.tsx:159\-166\), so a lost first email cannot be retried for 300s\. For an address that already has a verified account, deliver is false \(lines 146\-151\)\. The creator sends an OTP before register \(creator\-register\.tsx:230\), waits on the OTP panel for a code that never comes, and never reaches the EMAIL\_ALREADY\_EXISTS 409 that register itself returns without any OTP \(AuthService\.java:343\-345\)\. — Confidence: CONFIRMED\.
- **Fix:** Tell the client that a challenge was reused so the gate can say 'use the code already sent'\. For a verified existing address, mail a 'you already have an account, sign in or reset' notice\.

#### [Medium] \[Creator\] '30\-Day Reach' tile is populated from average reach per post
- **Where:** influora\-api/src/main/java/com/influora/service/PublicCreatorService\.java:89
- **Issue:** VerifiedMetrics\.reach\_30d is filled with m\.getAvgReachPerPost\(\) \(PublicCreatorService\.java:89\) and rendered under the label '30\-Day Reach' \(creator\-verified\-metrics\.tsx:122\-127\)\. The page exists for brand due diligence and shows a wrong metric\. — Confidence: CONFIRMED\.
- **Fix:** Populate the field from the 30\-day account\-insights reach, or rename the field and label to 'Avg reach / post'\.

#### [Medium] \[Creator\] Tax identity \(PAN/GSTIN\) is write\-only, so Settings never shows what is saved
- **Where:** influora\-api/src/main/java/com/influora/web/MeTaxIdentityController\.java:27
- **Issue:** The controller exposes POST only, and the FE has only creatorTaxIdentity\.submit \(api\.ts:4536\-4538\)\. TaxIdentityForm always opens blank \(TaxIdentityForm\.tsx:72\) and the Settings row always reads 'Add your GSTIN and PAN' \(creator\-settings\.tsx:384\)\. A creator cannot confirm which PAN or GSTIN will appear on invoices\. The KYC row has the same gap: item\.status is never set \(creator\-settings\.tsx:388\-392,593\)\. — Confidence: CONFIRMED\.
- **Fix:** Add GET /me/tax\-identity returning gstin, maskedPan and status, plus the KYC status\. Prefill the dialog and show the status on the row\.

#### [Medium] \[Creator\] Token refresh job's revoke\-then\-insert is not transactional
- **Where:** influora\-api/src/main/java/com/influora/integration/meta/oauth/MetaTokenStorage\.java:292
- **Issue:** @Transactional sits only on the 5\-arg storeCreatorToken \(MetaTokenStorage\.java:219\), which self\-invokes the 8\-arg overload; that overload and the 6\-arg one carry none \(249,292\)\. MetaTokenRefreshService, which has no @Transactional, calls the 8\-arg overload directly \(MetaTokenRefreshService\.java:190\)\. existing\.revoke\(\) followed by save \(309\-318\) and the new\-row save \(337\) therefore commit separately\. A failure in encrypt or the insert leaves the creator with a revoked token and no replacement, i\.e\. silently disconnected\. — Confidence: CONFIRMED\.
- **Fix:** Annotate the 8\-arg overload, which is the real entry point, with @Transactional\.

#### [Medium] \[Creator\] 'Content portfolio' \(pinned posts\) has no editor anywhere
- **Where:** src/pages/creator\-portfolio\-editor\.tsx:498
- **Issue:** The backend accepts and stores pinnedPosts \(PortfolioDtos\.java:163; PortfolioService\.java:323\-325\) and the public page renders them \(creator\-portfolio\-public\.tsx:533\-537\)\. The only FE reference in the editor is a count hint \(line 498\), and handleSave never sends pinnedPosts \(155\-173\)\. In live mode the section is always empty and its visibility toggle governs nothing\. The same goes for per\-row rate editing\. — Confidence: CONFIRMED\.
- **Fix:** Build the pinned\-post editor, or hide the toggle and section until one exists\.

#### [Medium] \[Creator\] Connected Accounts shows 'Facebook Page connected' for an Instagram\-only login and prints raw scope ids
- **Where:** src/components/creator/connected\-accounts\.tsx:204
- **Issue:** Both rows share one isConnected flag \(connected\-accounts\.tsx:104,159\-161,204\-206\), so an INSTAGRAM\_LOGIN connection, which by design has no Page, displays 'Page list connected'\. SCOPE\_LABELS covers only the Facebook\-Login scopes \(30\-34\), so instagram\_business\_basic and instagram\_business\_manage\_insights \(MetaOAuthService\.java:57\-61\) render as raw identifiers\. The status response carries no authPath \(MetaDtos\.java MetaConnectionStatusResponse\)\. — Confidence: CONFIRMED\.
- **Fix:** Return authPath in the status response, render the Facebook row only for FACEBOOK\_LOGIN, and add labels for the instagram\_business\_\* scopes\.

#### [Medium] \[Creator\] A transient Graph failure during connect is reported to the creator as 'Business account needed'
- **Where:** influora\-api/src/main/java/com/influora/service/creatorcopilot/CreatorMetaOAuthService\.java:253
- **Issue:** resolveIgAccountSafely returns null both for 'no linked IG account' and for any MetaApiException \(CreatorMetaOAuthService\.java:245\-255\)\. connect\(\) then returns connected=false with accountType 'personal' \(154\-156\), and the callback page tells a legitimate business\-account creator to convert their account or use the other login path \(creator\-meta\-callback\.tsx:263\-275\)\. — Confidence: CONFIRMED\.
- **Fix:** Distinguish lookup failure from no account, for example with accountType 'unknown' and a retry message\.

#### [Medium] \[Creator\] Rejected deliverable is invisible to the creator and the deal flips to Completed
- **Where:** influora\-api/src/main/java/com/influora/service/BrandDeliverableService\.java:385
- **Issue:** BrandDeliverableService\.reject publishes no event, no deal\-room row and no application\-history entry \(:385\-410\)\. Approve and revise do publish \(:264,:281\)\. REJECTED is terminal for the creator because canUploadNewVersion and canSubmit exclude it \(CreatorDeliverableService\.java:833\-841\)\. It also counts as resolved \(CollaborationLifecycleService\.java:109\-112\), so the collaboration becomes COMPLETED \(:282\-284\) and the FE shows 'Done' / 'Completed'\. The creator deliverables panel only surfaces REVISION\_REQUESTED and APPROVED\+ rows \(creator\-chat\.tsx:3013,3049\), so the creator never sees the rejection or the brand's feedback\. — Confidence: CONFIRMED\.
- **Fix:** Publish a DeliverableRejected event \(notification, timeline row, history entry\)\. Render REJECTED rows with reviewNotes in the deliverables panel\. Reconsider whether an all\-rejected deal should become COMPLETED\.

#### [Medium] \[Creator\] No UI to withdraw an application, and no UI to re\-apply after one is closed
- **Where:** src/pages/creator\-campaign\-detail\.tsx:356
- **Issue:** The backend supports creator withdrawal \(DealService\.java:398,525\-536, recorded as APPLICATION\_WITHDRAWN\) and re\-application through revive \(CreatorCampaignService\.java:231\-254\)\. Neither creator\-campaign\-detail\.tsx nor creator\-applications\.tsx offers Withdraw\. In chat, Decline needs either INVITED \(creator\-chat\.tsx:1978\-1981\) or a pending proposal card \(:2576\), so a plain APPLIED application cannot be retracted\. The detail DTO returns the raw status including CANCELLED \(CreatorCampaignMapper\.java:30,52\), and hasApplied = Boolean\(applicationStatus\) \(creator\-campaign\-detail\.tsx:187\) replaces Apply with 'Closed — You've already applied' \(:356\-371\)\. The revive path is therefore unreachable from the UI\. — Confidence: CONFIRMED\.
- **Fix:** Add a Withdraw action on Applications and the detail page for APPLIED, SHORTLISTED and IN\_NEGOTIATION, calling deals\.reject with a reason\. Treat applicationStatus==='CANCELLED' as not applied and show 'Apply again'\.

#### [Medium] \[Creator\] Submit Deliverable control disappears while any slot is under review
- **Where:** src/pages/creator\-chat\.tsx:2156
- **Issue:** The only first\-submission entry point renders when selectedDeal\.status==='in\_progress' \(creator\-chat\.tsx:2156\-2168\)\. Submitting one slot moves the collaboration to REVIEW\_PENDING \(CollaborationLifecycleService\.java:271\-276\), which maps to stage 'review' \(deal\-stage\.ts:127\-129\), and the button vanishes\. A creator with several slots cannot submit the next one until the brand reviews the first\. The server allows it \(CreatorDeliverableService\.java:333\-349 checks only the slot's own status\)\. The REVISION\_REQUESTED card is the only other entry \(:3011\)\. — Confidence: CONFIRMED\.
- **Fix:** Gate the control on the existence of a slot whose status is PENDING, DRAFT or REVISION\_REQUESTED \(from liveDeliverables\) rather than on the collaboration stage, and add a per\-slot Submit button in the deliverables panel\.

#### [Medium] \[Creator\] A sent message can appear twice because the SSE frame usually arrives before the POST response
- **Where:** src/pages/creator\-chat\.tsx:1410
- **Issue:** handleSendMessage blindly appends the POST result \(setLiveMessages\(prev =\> \[\.\.\.prev, sent\]\), :1410\)\. The SSE handler upserts by id \(:988\-995\)\. The server publishes to every emitter, including the sender's, synchronously in afterCommit \(DealService\.java:662,695\-707; DealMessageStreamRegistry\.java:66\-83\), which runs before the controller writes the HTTP response\. When the frame arrives first, the later append adds a second row with the same id\. The live events memo has no dedupe \(:1786\-1793\), unlike the mock path \(:1805\-1810\)\. — Confidence: PLAUSIBLE\.
- **Fix:** Make the send path an upsert\-by\-id as well, sharing one helper with the SSE handler\.

#### [Medium] \[Creator\] Proof screenshot upload is a no\-op
- **Where:** src/components/creator/deal\-room/deliverable\-lifecycle\-panel\.tsx:141
- **Issue:** The panel uploads the file and toasts 'Your proof screenshot was saved' \(:141\-143\) but discards the returned key, and the metrics submit sends reportMetrics\(\{ metrics \}\) only \(:131\)\. The server attaches proof solely from request\.proofScreenshots \(CreatorDeliverableService\.java:506\-560, via firstNonBlank\)\. uploadProof itself writes nothing to the database \(CreatorDeliverableService\.java:290\-325\)\. The uploaded object is never linked to the deliverable and the brand never sees it\. — Confidence: CONFIRMED\.
- **Fix:** Keep the returned key in state and pass proofScreenshots:\[key\] to reportMetrics, or persist the key on the deliverable inside uploadProof\.

#### [Medium] \[Creator\] Disputes notify nobody, the collaboration stays DISPUTED after resolution, and the UI claims the brand is notified
- **Where:** influora\-api/src/main/java/com/influora/service/DisputeService\.java:101
- **Issue:** openDispute \(:101\-145\) and resolveDispute \(:195\-362\) publish no event and write no deal\-room row; the service contains no eventPublisher or DealMessage reference\. The creator dialog states that the brand 'is notified' \(creator\-chat\.tsx:3178\-3181\)\. resolveDispute never moves the collaboration out of DISPUTED, and CollaborationLifecycleService treats DISPUTED as FROZEN \(:54\), so the deal is permanently 'Disputed' and can never reach COMPLETED; reviews require COMPLETED \(ReviewService\.java:117\)\. handleOpenDispute also does not refresh the deal \(creator\-chat\.tsx:1387\-1393\)\. Neither side has a respond or evidence flow \(CreatorDisputeController\.java:33,45 is GET only\)\. — Confidence: CONFIRMED\.
- **Fix:** Publish DisputeOpened and DisputeResolved events \(notification plus a system row\)\. On resolution, move the collaboration to a terminal or resumed status\. Call refreshDeal after opening\. Either drop the 'is notified' copy or make it true\. Add respond and evidence endpoints if they are in scope\.

#### [Medium] \[Creator\] Per\-deal fetches in the deal room have no stale\-response guard
- **Where:** src/pages/creator\-chat\.tsx:1098
- **Issue:** fetchLiveContract \(:1098\-1114\), loadDeliverables \(:1126\-1137\) and fetchLiveShipment \(:1213\-1232\) set state unconditionally\. Messages \(:880\-883\) and refreshDeal \(:771\-778\) use request tokens\. After a fast switch from deal A to deal B, a late response for A overwrites B's contract, milestones, status, deliverable list and shipment\. The submission dialog is fed from liveDeliverables \(:3149\), so the creator can submit a file into deal A's slot while viewing deal B; the server allows it because both belong to the same creator\. creator\-campaigns\.tsx:72\-120 has the same gap between filter changes and load\-more\. — Confidence: PLAUSIBLE\.
- **Fix:** Use the monotonic token pattern from loadMessages, keyed by deal id, and ignore responses whose deal id does not match the current selection\.

#### [Medium] \[Creator\] The creator is told to review the PDF before signing, but no real document or terms are available at that point
- **Where:** src/components/creator/deal\-room/creator\-deal\-contract\-tab\.tsx:186
- **Issue:** The tab says 'Review the PDF, then sign to proceed' \(:186\-187\)\. The server generates the PDF only after both signatures \(ContractService\.java:1126,1172\) and the download returns 404 CONTRACT\_PDF\_NOT\_READY until then \(:1411\)\. The FE falls back to a locally generated copy with deliverables:\[\] and no terms \(:101\-137\)\. ContractResponse\.terms is fetched \(api\.ts:3140\) but never rendered; the only caller passes milestones and amount only \(creator\-chat\.tsx:2946\-2962\)\. — Confidence: CONFIRMED\.
- **Fix:** Render liveContract\.terms and the real deliverable slots in the tab, and serve a pre\-signature draft PDF or change the copy\.

#### [Medium] \[Creator\] Deliverables panel shows synthetic rows in live mode
- **Where:** src/pages/creator\-chat\.tsx:2009
- **Issue:** deliverableItems are built from counts as 'Deliverable N', with the first deliverablesDone rows marked approved \(:2009\-2019\), and passed to DealDeliverablesTab \(:3000\-3005\)\. The real rows with titles and statuses are already in liveDeliverables \(:1132\)\. If slot 2 is approved and slot 1 is pending, the panel shows 'Deliverable 1 approved'\. SUBMITTED, REVISION\_REQUESTED and REJECTED all render as 'pending'\. — Confidence: CONFIRMED\.
- **Fix:** Build the items from liveDeliverables when liveApi is on, using the real title and status mapping\.

#### [Medium] \[Creator\] A payout that RazorpayX reports as failed is never re\-credited or closed
- **Where:** influora\-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController\.java:110
- **Issue:** Only payout\.processed, reversed, rejected and cancelled are routed to reconciliation\. FAILURE\_STATUSES has the same three failure values \(PayoutReconciliationService\.java:63\-64\)\. The payout\.failed event and the "failed" status are not handled\. fetchPayout is only used by an admin action \(AdminFinanceService\.java:518\); nothing polls in\-flight payouts\. A failed payout would keep confirmedAt null, stay in the creator's Pending Payouts figure \(PayoutRepository sumAmountByCreatorUserIdAndConfirmedAtIsNull\), and the debit would never come back\. — Confidence: PLAUSIBLE\.
- **Fix:** Route payout\.failed, add "failed" to FAILURE\_STATUSES, and add a scheduled poll for payouts unconfirmed after N hours\.

#### [Medium] \[Creator\] Manually recorded payouts show an internal id instead of the bank UTR, and the gross amount instead of what was sent
- **Where:** influora\-api/src/main/java/com/influora/service/WalletService\.java:469
- **Issue:** Manual payout is the intended operating mode while RazorpayX is off \(api\.ts:83\-88\)\. Payout\.createManualPaid sets razorpayPayoutId to "manual:" \+ id and stores the UTR in bankReference plus tdsAmount \(Payout\.java:212\-236\)\. getPayoutsForCreator maps reference from getRazorpayPayoutId\(\) and amount from getAmount\(\), and exposes neither bankReference nor tdsAmount\. The FE only hides references starting with "pending:" \(creator\-wallet\.tsx:863\), so the creator sees "Ref: manual:01J\.\.\.", which neither support nor their bank can look up\. The page also says each row "shows the amount actually sent to your bank" \(:899\-902\), which is wrong whenever TDS was withheld\. — Confidence: CONFIRMED\.
- **Fix:** For METHOD\_MANUAL rows return bankReference as the reference, and add tdsAmount and netAmount to CreatorPayoutRowResponse and CreatorPayoutRow; render the net amount and the TDS line\.

#### [Medium] \[Creator\] Tax Docs tab says 1% TDS is deducted at source; no code deducts TDS
- **Where:** src/pages/creator\-wallet\.tsx:978
- **Issue:** The tab states "TDS \(1%\) is deducted at source as per IT Act\. Download Form 16A quarterly"\. The only TDS in the backend is an optional number an admin types on a manual payout \(AdminFinanceService\.java:156\-183\)\. WalletService\.doProcessWithdrawal and PlatformFeeService\.deductAtRelease deduct none\. There is no tax\-document endpoint, so the tab is permanently empty under a false statutory claim, and it contradicts the Payouts tab's own note \(:899\) that TDS is not available\. — Confidence: CONFIRMED\.
- **Fix:** Remove or reword the claim until TDS exists end to end; hide the tab or state plainly that TDS is not yet deducted\.

#### [Medium] \[Creator\] Payout methods are saved without validation and cannot be deleted; a bad one surfaces 24 hours later as a failed withdrawal
- **Where:** influora\-api/src/main/java/com/influora/service/payout/CreatorBankAccountService\.java:54
- **Issue:** addInstrument only checks that the fields are not blank\. It does not check the VPA format, account\-number digits, that IFSC is present for BANK, or that type is UPI or BANK\. The FE enables Save on any non\-empty value \(creator\-wallet\.tsx:1155\) and sends the IFSC from an unvalidated input, which may be empty \(:630\)\. A BANK row without IFSC is accepted \(ifscCipher null\) and only fails at RazorpayFundAccountService\.createFundAccount after the 24\-hour cool\-down\. WalletController has no DELETE mapping and the FE has no remove control\. The first method auto\-becomes primary, so a mistyped first entry can only be displaced by adding another method\. — Confidence: CONFIRMED\.
- **Fix:** Validate server\-side \(IFSC ^\[A\-Z\]\{4\}0\[A\-Z0\-9\]\{6\}$, VPA ^\[\\w\.\\\-\]\{2,\}@\[a\-zA\-Z\]\{2,\}$, type in \{UPI,BANK\}, IFSC required when BANK\) and mirror it in the FE; add DELETE /wallet/payout\-methods/\{id\}\.

#### [Medium] \[Creator\] Withdrawal KYC gate passes on an unreviewed submission; VERIFIED is unreachable
- **Where:** influora\-api/src/main/java/com/influora/service/WalletService\.java:364
- **Issue:** requireIdentityKycSubmitted accepts PENDING\. The only writer of identityKycStatus is CreatorProfile\.applyIdentityKyc, which always sets PENDING \(:404\-410\)\. No admin or automated path sets VERIFIED or REJECTED; a search for "IdentityKyc" across src/main finds only that setter\. Any well\-formed PAN, four digits and a URL unlock withdrawals\. The Withdraw dialog does not mention KYC or link to Settings \(the form is mounted only at creator\-settings\.tsx:662\), so a creator just sees the 409 message\. — Confidence: CONFIRMED\.
- **Fix:** Build admin KYC approve/reject and gate on VERIFIED, or state explicitly that KYC is collection\-only\. In the dialog, link the IDENTITY\_KYC\_REQUIRED error to Settings \> KYC\.

#### [Medium] \[Creator\] Commission accrues on unpaid, cancelled and refunded orders, and link\-only sales earn nothing
- **Where:** influora\-api/src/main/java/com/influora/web/ShopifyWebhookController\.java:101
- **Issue:** Shopify processes orders/create as well as orders/paid \(:101\-103\)\. WooCommerce processes order\.created and order\.updated\. Neither payload parser reads a payment or order status, and the controllers ignore every other topic, so cancellations and refunds are dropped\. AffiliateEarning has no reversal state, so a creator is credited 10% of an order that is later refunded or never paid\. Separately, /webhooks/conversion only rolls up revenue \(ConversionTrackingService\.java:162\-178\)\. AffiliateEarningsService\.recordEarning has exactly two callers, both coupon\-redemption paths, so a sale attributed only by the tracking link pays no commission, while the coupons page says "Tracking links work immediately" \(creator\-coupons\.tsx:18\-21\)\. — Confidence: CONFIRMED\.
- **Fix:** Only redeem when financial\_status is paid \(Woo: processing or completed\)\. Handle orders/cancelled and refunds/create by marking the earning REVERSED while it is still PENDING\. Decide whether link conversions earn commission and either build it or correct the copy\.

#### [Medium] \[Creator\] Analytics tiles labelled Total Reach and Total Impressions show one row's per\-post averages and ignore the date range
- **Where:** influora\-api/src/main/java/com/influora/service/analytics/AnalyticsService\.java:127
- **Issue:** totalReach is the latest row's avgReachPerPost \(:127\)\. totalImpressions is avgImpressionsPerPost \(:128\)\. avgViewsPerPost is that same avgImpressionsPerPost \(:130\-133\)\. The FE renders them as "Total Reach", "Total Impressions" and "Avg\. Views Per Post" \(creator\-analytics\.tsx:208\-228\), so two tiles always show an identical number and neither is a total\. The start/end dates only feed trendData \(:158\-173\)\. followerGrowth is the delta across the fixed lookback window \(:144\-154\), not the selected window\. The header still says "over the last \{rangeDays\} days" \(:134\)\. — Confidence: CONFIRMED\.
- **Fix:** Either compute real sums over \[startDate,endDate\] from the per\-post media table, or relabel the tiles \(Avg reach per post, Avg impressions per post\), drop the duplicate, and compute followerGrowth over the selected range\.

#### [Medium] \[Admin\] Creator application review is a dead feature: PENDING is never written and the decision is never read
- **Where:** influora\-api/src/main/java/com/influora/domain/entity/CreatorProfile\.java:215
- **Issue:** newForUser sets applicationStatus=APPROVED \(215\) and the V38 column default is 'APPROVED' \(V38\_\_creator\_profile\_moderation\.sql:32\)\. The only writer is applyApplicationDecision, which writes APPROVED or REJECTED \(513\)\. No code outside the admin package reads getApplicationStatus\(\): discovery filters only on discoverable and suspended \(CreatorProfileSpecifications\.java:27\)\. So the Pending Applications tab \(UsersPage\.tsx:555\), the CREATOR\_APPLICATION approvals queue \(ApprovalWorkflowService\.java creatorApplicationQueue\) and the Approve/Reject buttons gated on PENDING \(CreatorProfile\.tsx:704\) can never show anything, and a REJECTED status has no effect on the creator\. — Confidence: CONFIRMED\.
- **Fix:** Either gate signup, discovery and apply on applicationStatus and create profiles as PENDING, or remove the tab, queue and buttons until a review flow exists\.

#### [Medium] \[Admin\] Campaign monitor silently truncates to the newest 50 campaigns; search, filter and totals cover only those
- **Where:** src/admin/services/api\-contracts\.ts:428
- **Issue:** listAll\(\) calls GET /campaigns with no params \(428\)\. The controller defaults to page=1, pageSize=50 \(AdminCampaignController\.java:57\-58\) and puts the real total only in the X\-Total\-Count header \(61\), which apiRequest never reads\. useCampaignList reports totalCount = allCampaigns\.length \(useCampaignList\.ts:106\) and filters and sorts client\-side \(70\-100\)\. Once more than 50 campaigns exist, older ones cannot be seen or searched, and the 'N of M' count is wrong\. — Confidence: CONFIRMED\.
- **Fix:** Send page and pageSize and read X\-Total\-Count, or return a paged envelope\. Move search and filter server\-side\.

#### [Medium] \[Admin\] Campaign type and timeline are hardcoded: the Hype filter always returns 0 rows, and every campaign shows STANDARD and ON\_TRACK
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminCampaignService\.java:477
- **Issue:** toSummaryDto sets type="STANDARD" \(477\)\. The detail DTO sets type="STANDARD" \(369\) and timelineStatus="ON\_TRACK" \(391\)\. The same service does know CampaignIntentType\.HYPE \(118\)\. The FE offers a Hype type filter \(CampaignTable\.tsx:416\-418; useCampaignList\.ts:78\-80\) and renders the timeline pill \(CampaignTable\.tsx:186\-195\)\. A delayed campaign is shown as ON\_TRACK\. — Confidence: CONFIRMED\.
- **Fix:** Map type from campaign\.intentType\. Compute timelineStatus from endDate and slaBreachRate, or drop the field from the UI\.

#### [Medium] \[Admin\] Force Instagram Re\-auth is offered only to creators with no Meta connection, hidden for connected ones, and has no confirmation
- **Where:** src/admin/components/users/CreatorProfile\.tsx:806
- **Issue:** The button renders when \!creator\.instagramVerified \(806\)\. The backend sets instagramVerified = PlatformStat\.isVerified\(\) \(AdminCreatorService\.java:558\), which comes from CreatorMetric\.isPlatformVerified\(\) meaning dataSource == META\_API \(CreatorMetric\.java:61\-63; PlatformStatsAggregationJob\.java:223\)\. That is true for connected creators\. For the creators who actually hold a token the button is hidden\. For the others the action revokes 0 tokens and still reports 'Instagram re\-auth requested\.' \(CreatorProfile\.tsx:256\)\. It fires directly from onClick with no confirm dialog and no reason, and the creator is not notified to reconnect\. — Confidence: CONFIRMED\.
- **Fix:** Gate on the presence of an active token \(instagramOauthAt \!= null\)\. Add a confirm dialog with a reason\. Return the revoked count and notify the creator\.

#### [Medium] \[Admin\] Users search and filters disagree with what the table shows
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminBrandService\.java:184
- **Issue:** Three mismatches\. \(a\) Brand search matches name or billingEmail only \(184\-193\), but the row email falls back to the owner's email when billingEmail is null \(271, 797\-801\)\. The placeholder says 'Search brand name or email\.\.\.' \(UsersPage\.tsx:243\), so searching a displayed email finds nothing\. \(b\) The KYC filter 'PENDING' maps to VerificationStatus\.PENDING only \(908\), while UNVERIFIED brands, the signup default at Workspace\.java:147, are displayed as PENDING \(896\)\. Those rows vanish under the 'KYC Pending' filter, and verifyKyc lets an admin approve a brand that never submitted KYC \(no fromStatus guard, 281\-307\)\. \(c\) Creator search matches displayName only \(CreatorProfileSpecs\.java:38\-43\), while the placeholder says 'name or email\.\.\.' \(UsersPage\.tsx:409\)\. — Confidence: CONFIRMED\.
- **Fix:** Join the owner user email into brand search and the user email into creator search\. Expose UNVERIFIED as its own status, or include it in the PENDING filter\. Require PENDING before APPROVE\.

#### [Medium] \[Admin\] Users get no notification on admin decisions \(KYC, suspend, reinstate, force re\-auth, dispute ruling\), so the reason never reaches them
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminBrandService\.java:323
- **Issue:** AdminBrandService \(verifyKyc 281, suspend 323, reinstate 352\), AdminCreatorService \(reviewApplication 214, forceInstagramReauth, suspend\), AdminModerationService and DisputeService\.resolveDispute \(195\-350\) contain no NotificationService, email or event call\. I grepped those files for Notification, Email and publishEvent and found none\. A suspended brand simply starts receiving 403 WORKSPACE\_SUSPENDED \(BrandContextService\.java:104\)\. The admin\-entered reason is stored only in the audit log\. — Confidence: CONFIRMED\.
- **Fix:** Publish notification events for brand\.kyc\_approved/rejected, account\.suspended/reinstated, creator\.instagram\_reauth\_required and dispute\.resolved, carrying the reason\.

#### [Medium] \[Admin\] Invite reports success when the invitation email failed to send
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService\.java:295
- **Issue:** sendJoinInvitationEmail only logs an error when msg91EmailClient\.sendTemplateEmail returns false \(293\-299\)\. invite\(\) has already set the external creator to INVITED and the request to CONTACTED, written audit rows and returned 200 \(210\-262\)\. The FE shows a successful invite \(CreatorConnectionsPage\.tsx:285\-293\)\. The admin has no signal that the creator never received the link\. — Confidence: CONFIRMED\.
- **Fix:** Return a delivery flag in AdminConnectionDto, or throw 502 and roll back\. Show a 'not delivered, resend' state in the UI\.

#### [Medium] \[Admin\] Dashboard 'Campaigns At Risk' KPI is a hardcoded 0 although a real at\-risk query exists
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminDashboardService\.java:122
- **Issue:** operations\(\) passes the literal 0 for campaignsAtRisk \(119\-122\)\. PulseDashboard\.tsx:97 renders it as a live KPI\. AdminCampaignService\.atRisk already computes ACTIVE campaigns with slaBreachRate \> 0 \(255\-263\), so the dashboard shows 0 while the at\-risk list is non\-empty\. — Confidence: CONFIRMED\.
- **Fix:** Use a count from the atRisk\(\) query, or hide the card\.

#### [Medium] \[Admin\] Admins cannot resolve or close tickets, and only the newest 20 tickets are ever loaded
- **Where:** FE/components/support/TicketList\.tsx:420
- **Issue:** The backend exposes PUT /admin/support/tickets/\{id\} \(AdminSupportController\.java:87\-94 \-\> AdminSupportService\.updateStatus :160\-190\)\. supportApi\.update \(api\-contracts\.ts:694\-698\) has no UI caller, and its Partial\<SupportTicket\> body does not match UpdateStatusRequest\(status, reason\)\. The drawer offers only reply, assign and escalate, so resolvedAt is never set from the console and avgResolutionTime \(AdminSupportService\.java:308\-315\) stays 0\. useTicketList\.ts:86\-87 calls supportApi\.list\(filters\) with the default page 1 and size 20 and has no page state, while the header reads 'N of total tickets' \(TicketList\.tsx:625\)\. An admin reply \(AdminSupportService\.java:141\-157\) changes no status and sends the user no notification\. — Confidence: CONFIRMED\.
- **Fix:** Add a status select or Resolve/Close buttons that send \{status, reason\}\. Add pagination to useTicketList and TicketList\. Move the ticket to WAITING\_USER and notify the user on an admin reply\.

#### [Medium] \[Admin\] Audit Log date filters always fail with 400
- **Where:** FE/pages/AuditLogPage\.tsx:196
- **Issue:** The From and To inputs are \<Input type="date"\> \(AuditLogPage\.tsx:196\-214\) and their yyyy\-MM\-dd value is passed through unchanged \(:151\-152 \-\> api\-contracts\.ts:1318\-1319\)\. The backend runs Instant\.parse\(value\) and throws INVALID\_DATE\_RANGE 'must be an ISO\-8601 instant' \(AdminAuditLogService\.java:537\-548\)\. Any date filter turns the page into an error state\. The Action placeholder 'e\.g\. brand\.suspend…' \(:187\) is also misleading: matching is exact equality \(AdminAuditLogSpecs\.java:35\) against values such as 'SUSPEND'\. — Confidence: CONFIRMED\.
- **Fix:** Convert on the FE: start becomes \`$\{d\}T00:00:00Z\` and end becomes the next day's start \(or accept LocalDate on the backend with an exclusive end\)\. Make Action and Entity type selects of the real values\.

#### [Medium] \[Admin\] Comp/Override workspace picker lists only workspaces on the current page of the subscriptions table
- **Where:** FE/components/billing/BillingConsole\.tsx:461
- **Issue:** workspaceOptions is built from subscriptionsResult\.data\.data \(BillingConsole\.tsx:461\-468\), which is at most 20 rows and follows the current status and search filter\. Subscription rows are created lazily \(SubscriptionService\.getOrCreateFreeSubscription :218\-220\), and grantAdminPlan explicitly supports a workspace with no row \(:462\-476\)\. A brand that has never opened billing, or that sits on another page, cannot be selected, even though the backend would accept it\. The placeholder says 'Search / select a workspace…' but no search exists\. — Confidence: CONFIRMED\.
- **Fix:** Back the picker with a search over the brands endpoint \(brandApi\.list\) instead of the visible subscription rows\.

#### [Medium] \[Admin\] Fee\-change confirm dialog tells the SUPER\_ADMIN that the brand fee is not wired to billing, but it is
- **Where:** FE/components/finance/FeeControlPanel\.tsx:190
- **Issue:** describeChange says 'brand campaign\-spend escrow funding is not yet wired to this rate, so brands will not actually be charged differently' \(:190\)\. On the very next publish, CampaignActivationGuard\.activate \(CampaignActivationGuard\.java:58\) calls BrandCampaignFeeService\.chargeOnPublish, which reads config\.getBrandFeeBps\(\) \(BrandCampaignFeeService\.java:118, 224\-228\) and debits the brand wallet\. An admin who is told the change has no effect can alter real charges\. The dialog also omits that PRO workspaces ignore this rate \(:121\-125\)\. The file header \(:19\-36\) still says the concurrency token is not enforced and mentions @PreAuthorize; both are stale, because enforcement sits at PlatformFeeAdminService\.java:106 and :92\. — Confidence: CONFIRMED\.
- **Fix:** Replace the copy with the truth: the rate applies to every non\-Pro campaign published from now on, Pro workspaces are charged their plan rate, and already\-published campaigns are charged only a delta when their budget increases\.

#### [Medium] \[Admin\] 'Platform absorbs payment processing costs' switch is a dead control
- **Where:** FE/components/finance/FeeControlPanel\.tsx:512
- **Issue:** The switch is persisted \(PlatformFeeAdminService\.java:124\-125\) and the UI says costs 'will start being passed to brands/creators' \(FeeControlPanel\.tsx:203\-207, 415\-417\)\. isRazorpayAbsorbedByPlatform\(\) has no consumer in src/main; the only hits are PlatformFeeConfig\.java:122 and the audit allowlist AdminAuditLogService\.java:252\. No top\-up, escrow or payout path reads it, so toggling it changes nothing except the displayed pill\. — Confidence: CONFIRMED\.
- **Fix:** Implement the pass\-through in the top\-up and fee paths, or remove the switch or make it read\-only and label it informational\.

#### [Medium] \[Admin\] Escrow 'Pending Release' shows a count formatted as rupees
- **Where:** FE/components/finance/FinanceConsole\.tsx:364
- **Issue:** pendingRelease is countByStatus\(FUNDED\) \(AdminFinanceService\.java:278; DTO \`long pendingRelease\` at AdminFinanceDtos\.java:41\), but the panel renders formatCurrency\(escrowSummary\.pendingRelease\) \(:364\)\. Five funded holds display as '₹5'\. totalLocked also sums only FUNDED holds \(:277\) and leaves out FROZEN holds, which are still locked funds\. — Confidence: CONFIRMED\.
- **Fix:** Render it as a count \('5 holds'\), or have the backend return the pending amount\. Decide whether FROZEN holds belong in totalLocked\.

#### [Medium] \[Admin\] GMV is overstated for dispute split settlements
- **Where:** BE/service/admin/AdminRevenueService\.java:145
- **Issue:** aggregate\(\) sums hold\.getAmount\(\) for every RELEASED hold \(:145\-151\)\. In a split, the hold is marked RELEASED whenever the creator receives anything \(EscrowService\.java:1440\-1441\), and markReleased leaves the amount unchanged \(EscrowHold\.java:159\-164\)\. A 10%\-to\-creator / 90%\-refunded split therefore counts 100% of the hold as GMV\. — Confidence: CONFIRMED\.
- **Fix:** Sum the ESCROW\_RELEASE ledger legs, or store releasedAmount on the hold and sum that\.

#### [Medium] \[Admin \+ Brand\] Platform revenue misses brand\-fee delta top\-ups because the brand commission invoice is one per campaign
- **Where:** BE/service/CommissionInvoiceService\.java:96
- **Issue:** Revenue is the sum of PlatformCommissionInvoice\.commissionAmount \(AdminRevenueService\.java:153\-160\)\. chargeOnPublish posts a delta PLATFORM\_FEE when the budget grows \(BrandCampaignFeeService\.java:236\-279\) and then calls createBrandLegAtPublish\(\.\.\., delta, \.\.\.\) \(:303\)\. That method returns the existing invoice unchanged when a BRAND\-leg invoice already exists \(CommissionInvoiceService\.java:96\-99\)\. The ledger holds fee \+ delta while the invoices, and so the dashboard, hold only the first fee\. The delta also gets no GST invoice\. Brand side: the brand is debited the delta fee from the wallet but receives no commission/GST invoice for it \(BrandCampaignFeeService\.java:303 \-\> CommissionInvoiceService\.java:96\-99\)\. This is in the UNCOMMITTED fee work\. — Confidence: CONFIRMED\.
- **Fix:** Issue a supplementary invoice for each delta \(keyed by ledgerTxnId\), or compute revenue from PLATFORM\_FEE ledger credits to the revenue wallet\.

#### [Medium] \[Admin\] 'Retry payout' is offered only on rows that can never be retried
- **Where:** FE/components/finance/ReconciliationPanel\.tsx:264
- **Issue:** The button appears only for status 'MISMATCH'\. retryFailedPayout accepts only reversed, rejected or cancelled payouts \(PayoutReconciliationService\.java:95\-98, 325\-334\)\. Such a payout carries the same status in its stored webhook payload \(confirmStatus :135\), so classify\(\) returns MATCHED \(AdminFinanceService\.java:488\-495, outcomeAgrees at :430\)\. A gateway failure before confirmation keeps the 'pending:' id and returns PENDING \(:399\-408\)\. MISMATCH rows are payouts in a non\-failure state, which answer 409 PAYOUT\_NOT\_RETRYABLE, or wallet top\-up rows \(reconcileTopUp :442\-486, where internalId is the top\-up id\), which answer 404 PAYOUT\_NOT\_FOUND\. The retry feature cannot be reached from the UI\. — Confidence: CONFIRMED\.
- **Fix:** Return a row type and a retryable flag \(payout in a failure state\) from the backend and gate the button on those\. Never show it on top\-up rows\.

#### [Medium] \[Admin\] Manual payout debits only the 'amount sent'; TDS withheld stays withdrawable in the creator wallet
- **Where:** BE/service/admin/AdminFinanceService\.java:216
- **Issue:** The UI labels are 'Amount sent \(₹\)' and 'TDS deducted \(₹\)' \(ManualPayoutPanel\.tsx:136, 163\)\. The server requires tds \<= amount \(:180\) and debits only \`amount\` \(:216\-226\); tdsAmount is stored on the Payout and nothing else uses it\. If a creator is owed 10,000, 1,000 TDS is withheld and 9,000 is sent, the wallet still shows 1,000 available\. The validation tds \<= amount hints that the author meant amount to be gross, which contradicts the 'Amount sent' label\. Either reading leaves the books or the label wrong\. — Confidence: PLAUSIBLE\.
- **Fix:** Define it explicitly: debit amount \+ tds, with the TDS leg going to a TDS\-payable wallet; or relabel the field 'Gross amount settled' and show the net\. Add a test\.

#### [Medium] \[Admin\] Front\-end crashes never reach the admin Error Log
- **Where:** BE/web/ClientErrorController\.java:134
- **Issue:** The ErrorBoundary posts to /client\-errors \(SRC/lib/api\.ts:6911\)\. handle\(\) only calls log\.warn\('\[CLIENT\_ERROR\_REPORT\] …'\) \(:134\-141\) and the controller has no ErrorLogService dependency \(constructor :81\-83\)\. ErrorLogPage reads error\_log, whose only writers are GlobalExceptionHandler\.java:213 \(backend 500s\) and NotificationListener\.java:736\. Client crashes are visible only in server logs\. The 'Critical \(24h\)' KPI \(AdminErrorLogService\.java:84\-85\) can never be non\-zero, because no writer uses ErrorLogSeverity\.CRITICAL\. — Confidence: CONFIRMED\.
- **Fix:** Persist rate\-limited client reports through ErrorLogService with a source=CLIENT field and severity WARN \(the payload is already capped and redacted\), or drop the expectation and relabel the page 'Server errors'\. Assign CRITICAL to money and escrow failures, or remove the tile\.

#### [Low] \[Brand\] Notification mark\-read/preferences endpoints return bare bodies that pass the envelope check by accident
- **Where:** influora\-api/src/main/java/com/influora/web/NotificationController\.java:104,135,271
- **Issue:** No ApiResponse wrapper; accepted only because the record's own \`success:true\` satisfies envelope\.success, and envelope\.data is undefined \(api\.ts:736\-750\)\. Renaming the DTO field would break every mark\-read\. — Confidence: CONFIRMED\.
- **Fix:** Wrap in ApiResponse\.ok\(\.\.\.\)\.

#### [Low] \[Brand\] Team roster shows raw user ULIDs instead of names/emails
- **Where:** src/components/brand/settings/team\-members\-panel\.tsx:221\-223,234
- **Issue:** MemberResponse carries only ids and role \(WorkspaceMemberDtos\.java:22\-23\), so an owner cannot tell who they are removing\. — Confidence: CONFIRMED\.
- **Fix:** Add displayName \+ email to MemberResponse and render them\.

#### [Low] \[Brand\] Register writes non\-canonical companySize/industry and onboarding re\-asks the same fields
- **Where:** src/pages/brand\-register\.tsx:146\-147,346\-349
- **Issue:** Register sends '1\-5','6\-20'\.\.\. while the closed vocabulary is STARTUP/SMB/ENTERPRISE \(onboarding\-steps\.tsx:134\-138; AdminBrandService\.java:875\); onboarding never prefills from GET /workspaces/me\. Abandoned onboarding keeps a value the admin edit form rejects\. — Confidence: CONFIRMED\.
- **Fix:** Use the canonical union on register; prefill CompanyDetailsStep\.

#### [Low] \[Brand\] Brand has no profile editor and no account\-deletion UI; deleteAccount FE type disagrees with Java
- **Where:** src/pages/brand\-settings\.tsx:368
- **Issue:** PATCH /users/me accepts name/timezone/avatar \(UserDtos\.java:47\-53\) but the only brand caller sends \{phone\}\. DELETE /me/account is only called from creator\-settings\.tsx:337\. api\.me\.deleteAccount is typed \{success\} \(api\.ts:4024\) while Java returns \{deleted\} \(UserDtos\.java:55\)\. — Confidence: CONFIRMED\.
- **Fix:** Add a Profile card \+ delete flow to brand settings; align the type\.

#### [Low] \[Brand\] Creator profile invite dropdown lists campaigns the server will refuse, and only the first 20
- **Where:** src/pages/brand\-creator\-profile\.tsx:476\-477 ; influora\-api/\.\.\./service/CreatorDiscoveryService\.java:522\-527
- **Issue:** api\.campaigns\.list\(\{\}\) applies no status filter and uses the default limit of 20\. Choosing a DRAFT returns 409 CAMPAIGN\_NOT\_PUBLISHED\. PAUSED, COMPLETED and CANCELLED campaigns can also be chosen and are accepted by the backend\. Discover filters to ACTIVE \(creator\-discovery\.tsx:1709\) but fetches only 50 rows across all statuses \(:787\), so an ACTIVE campaign beyond row 50 is missing\. — Confidence: CONFIRMED\.
- **Fix:** Fetch with status:'ACTIVE' and limit:100 in both places\. On the server, refuse invites to campaigns that are not ACTIVE\.

#### [Low] \[Brand\] Delete is offered for campaigns that cannot be deleted
- **Where:** src/components/brand/campaigns/campaigns\-list\.tsx:842\-850 ; src/pages/brand\-campaign\-detail\.tsx:1154\-1160 ; influora\-api/\.\.\./service/CampaignValidator\.java:111\-116
- **Issue:** Delete is enabled for ACTIVE, PAUSED and COMPLETED rows and always returns 409 CAMPAIGN\_NOT\_DELETABLE\. The confirm dialog promises permanent deletion\. A DRAFT that already has a secured hold or a stashed invite fails on an FK \(V9\_\_escrow\_holds\.sql:23, V6:70\) with the generic DATA\_INTEGRITY\_VIOLATION message 'The request could not be completed due to a data conflict'\. — Confidence: CONFIRMED\.
- **Fix:** Show Delete only for DRAFT\. In the service, check for existing holds or collaborations and return a specific message\.

#### [Low] \[Brand\] Dashboard pipeline stage click goes to an unfiltered campaigns list
- **Where:** src/components/brand/dashboard/dashboard\-page\.tsx:402\-404 ; src/components/brand/campaigns/campaigns\-list\.tsx:240
- **Issue:** The click navigates to /brand/campaigns?status=negotiating\. CampaignsList keeps statusFilter in local state and never reads search params\. The buckets are collaboration stages \(DashboardService\.java:133\-139\), so the right target is /brand/pipeline\. — Confidence: CONFIRMED\.
- **Fix:** Navigate to /brand/pipeline?stage=\<id\> and have brand\-pipeline read the parameter\.

#### [Low] \[Brand\] Campaign detail header state badge is hardcoded
- **Where:** src/pages/brand\-campaign\-detail\.tsx:1039\-1042
- **Issue:** currentState is isCompleted ? 'SETTLED' : 'IN\_PRODUCTION' regardless of campaign\.status or the deal stages\. DRAFT, PAUSED and zero\-applicant campaigns all read 'In production'\. The same menu offers Resume for CANCELLED and PENDING\_APPROVAL campaigns \(:1141\-1149\), which the server rejects \(CampaignValidator\.java:72\-79\)\. — Confidence: CONFIRMED\.
- **Fix:** Derive the state from campaign\.status plus the deal stages\. Render Resume only for PAUSED\.

#### [Low] \[Brand\] isStatusOnlyPatch ignores endBrandName and endBrandCategory, so those fields can be edited on an ACTIVE campaign
- **Where:** influora\-api/\.\.\./service/CampaignService\.java:643\-659,357\-358
- **Issue:** The guard lists 15 fields but not the two end\-brand fields that applyPatch writes\. A PATCH of \{status:'PAUSED', endBrandName:'X'\}, or just \{endBrandName:'X'\}, on an ACTIVE campaign passes ensureEditable and changes the brand creators agreed to work for\. No FE path sends this today\. — Confidence: CONFIRMED\.
- **Fix:** Add both fields to isStatusOnlyPatch\.

#### [Low] \[Brand\] Bid "Message" can open the wrong deal room
- **Where:** src/pages/brand\-campaign\-detail\.tsx:1426 ; src/pages/brand\-chat\.tsx:1027\-1028
- **Issue:** The link is /brand/chat?creator=\<userId\>\. brand\-chat selects the first deal room with that creatorId\. A brand with two campaigns involving the same creator can land in the other campaign's room\. bid\.id is the deal id, and brand\-chat already supports ?deal=\. — Confidence: CONFIRMED\.
- **Fix:** Navigate to /brand/chat?deal=$\{bid\.id\}\.

#### [Low] \[Brand\] Campaign list header stats come from the filtered page, not the workspace
- **Where:** src/components/brand/campaigns/campaigns\-list\.tsx:307,492\-498
- **Issue:** Total, active, draft and totalBudget are reduced over allCampaigns, which is the server\-filtered first 100 rows\. Filtering to ACTIVE shows 'Draft 0', and brands with more than 100 campaigns are undercounted even though meta\.total is already available\. — Confidence: CONFIRMED\.
- **Fix:** Use meta\.total plus an unfiltered counts call or endpoint for the header\.

#### [Low] \[Brand\] Resume of a PAUSED campaign depends on a still\-FUNDED hold
- **Where:** influora\-api/\.\.\./service/CampaignActivationGuard\.java:71\-81 ; src/pages/brand\-campaign\-detail\.tsx:1142\-1148
- **Issue:** The FE assumes a PAUSED campaign's funds are already secured and patches ACTIVE directly\. The guard requires at least one hold in FUNDED at that moment\. For Meera\-launched campaigns the holds are bound to collaborations and released on approval\. Once all are RELEASED \(or refunded after a dispute\), resume returns 409 'Campaign has no secured payment in FUNDED status — cannot activate' as a raw toast, with no route to re\-fund\. — Confidence: PLAUSIBLE\.
- **Fix:** On resume, accept FUNDED or RELEASED holds, or send the ESCROW\_NOT\_FUNDED failure into SecureAndPublishStep\.

#### [Low] \[Brand\] Dead catch around plain save\(\) in invite, and dashboard review deadline measured from collaboration creation
- **Where:** influora\-api/\.\.\./service/CreatorDiscoveryService\.java:555\-562 ; service/DashboardService\.java:104
- **Issue:** \(a\) collaborationRepository\.save\(\) inside @Transactional does not flush, so the DataIntegrityViolationException catch that maps to COLLABORATION\_EXISTS cannot fire\. A racing duplicate surfaces at commit as the generic DATA\_INTEGRITY\_VIOLATION, and the FE's e\.code === 'COLLABORATION\_EXISTS' branch \(creator\-discovery\.tsx:640\) misses it\. \(b\) The review deadline is c\.getCreatedAt\(\) \+ SLA rather than submission time, so long\-running deals always show as overdue\. The FE also declares action types counter\_proposal and sign\_contract \(api\.ts:4113\) that the server never emits\. — Confidence: CONFIRMED\.
- **Fix:** \(a\) Use saveAndFlush\. \(b\) Base the deadline on the deliverable's submittedAt and trim the FE union to the types the server emits\.

#### [Low] \[Brand\] Smaller issues: reviewed deals reappear in 'Rate', received reviews are anonymous, system and proposal rows render as creator chat bubbles, REJECTED shows as 'Awaiting submission', any member can open a dispute, and there is no way to cancel or amend a contract
- **Where:** src/components/shared/collaboration\-reviews\-panel\.tsx:77
- **Issue:** First, reviewedIds is session state only \(panel:77, :185\), and no endpoint lists the brand's own submitted reviews \(BrandReviewController\.java:36\-58\)\. After a reload every COMPLETED deal is offered again and returns ALREADY\_REVIEWED\. Second, ReviewResponse has no reviewer or campaign name \(ReviewDtos\.java\), and mapReviewFromApi sets both undefined \(src/lib/api\.ts:5280\-5282\)\. Third, the /brand/deals Messages tab renders every message that is not from the brand, including system and proposal kinds, with the creator's avatar and m\.content only \(deal\-room\-dashboard\.tsx:840\-856\)\. Fourth, brand\-chat maps REJECTED to 'pending', so it shows as 'Awaiting submission' \(brand\-chat\.tsx:1259\-1266; deal\-deliverables\-tab\.tsx:99\-104\)\. Fifth, openDispute has no workspace\-role gate, so a VIEWER can freeze escrow \(DisputeService\.java:109\-110, :561\-575\)\. Sixth, POST /contracts/\{id\}/cancel and /amend have no frontend client \(ContractController\.java:125, :144; no match in src/lib/api\.ts or src/lib/meera\-api\.ts\), so a wrong milestone split cannot be corrected from the UI\. — Confidence: CONFIRMED\.
- **Fix:** Add GET /brand/reviews/given or a \`reviewed\` flag on /deals?status=completed\. Enrich ReviewResponse with display names\. Render system and proposal kinds distinctly\. Map REJECTED to its own badge\. Add a role gate to openDispute\. Add cancel and amend controls to the contract tab\.

#### [Low] \[Brand\] Transaction history shows only the first 20 rows
- **Where:** src/pages/brand\-wallet\.tsx:448,707 ; src/lib/api\.ts:3730
- **Issue:** There are no page controls and the response meta is discarded, although the backend pages \(WalletService\.java:502\-540\)\. Search, filter and 'last recharge' \(:337\-342\) all operate on those 20 rows only\. — Confidence: CONFIRMED\.
- **Fix:** Add load\-more using requestWithMeta\.

#### [Low] \[Brand\] Checkout receives the amount in rupees while Razorpay expects paise
- **Where:** src/pages/brand\-wallet\.tsx:619 ; src/lib/razorpay\.ts:169\-171
- **Issue:** topUpOrder\.amount \(rupees\) is passed as Checkout amount\. The order\_id is authoritative, so the charge is correct, but the option is wrong by 100x\. — Confidence: PLAUSIBLE\.
- **Fix:** Omit the amount option, or multiply by 100\.

#### [Low] \[Brand\] Creator analytics page header shows the raw id and says demographics are 'not built' while the endpoint exists
- **Where:** src/pages/brand\-creator\-analytics\.tsx:106\-110,206\-213 ; AnalyticsController\.java /\{creatorId\}/demographics
- **Issue:** No profile is fetched in live mode, so the header is the id string\. api\.analytics\.getCreatorDemographics and the endpoint are both present and unused on this page\. — Confidence: CONFIRMED\.
- **Fix:** Fetch the creator summary and wire useCreatorDemographics\.

#### [Low] \[Creator\] FE declares lastSyncedAt and avgReach on platform stats that the Java record never sends
- **Where:** src/lib/api\.ts:4610
- **Issue:** PortfolioPlatformStats declares avgReach and lastSyncedAt \(api\.ts:4610,4618\)\. PlatformStatResponse has only platform, handle, followers, engagementRate, isVerified and profileUrl \(CreatorDtos\.java:13\-19\), and toPlatform builds exactly those \(PortfolioService\.java:1317\-1325\)\. The 'Synced … ago' label and the Avg reach row \(creator\-portfolio\-public\.tsx:773,814\) can never render in live mode\. — Confidence: CONFIRMED\.
- **Fix:** Add lastSyncedAt \(PlatformStat\.updatedAt\) and avgReach to the record, or remove the dead UI\.

#### [Low] \[Creator\] Meta callback effect double\-runs under React\.StrictMode and shows 'Connection failed' after a successful connect \(dev builds only\)
- **Where:** src/pages/creator\-meta\-callback\.tsx:125
- **Issue:** main\.tsx:8 enables StrictMode\. The first effect run strips the query via replaceState \(creator\-meta\-callback\.tsx:125\-127\) and is then cancelled, so its success is dropped at line 203\. The second run finds no code or state and sets the 'Missing or invalid parameters' error \(160\-165\)\. Production builds do not double\-invoke effects\. — Confidence: CONFIRMED\.
- **Fix:** Read and strip the params once, outside the effect \(a useRef or lazy useState\), and guard the run with a ref\.

#### [Low] \[Creator\] Password rules are enforced only server\-side, after the OTP step
- **Where:** src/pages/creator\-register\.tsx:97
- **Issue:** The FE checks only length \>= 8 \(creator\-register\.tsx:97; creator\-settings\.tsx:284; brand\-reset\-password\.tsx:34\)\. PasswordPolicy requires upper, lower and digit plus a denylist check \(PasswordPolicy\.java:38\-45\), and creatorRegister runs only after the OTP is verified\. 'password123' therefore fails with WEAK\_PASSWORD after the user has completed email verification\. — Confidence: CONFIRMED\.
- **Fix:** Mirror the policy in a shared FE validator and state the rule in the placeholder\.

#### [Low] \[Creator\] Login reveals account type before checking the password
- **Where:** influora\-api/src/main/java/com/influora/service/AuthService\.java:456
- **Issue:** creatorLogin throws WRONG\_USER\_TYPE \(403\) for a brand email before passwordEncoder\.matches runs \(AuthService\.java:456\-463\); brandLogin does the same at 262\-268\. Any caller can learn that an email exists and which role it holds\. — Confidence: CONFIRMED\.
- **Fix:** Check the password first, and return INVALID\_CREDENTIALS on a type mismatch unless the password is correct\.

#### [Low] \[Creator\] OAuth state store is in\-memory, never evicts abandoned states, and burns a state before checking its owner
- **Where:** influora\-api/src/main/java/com/influora/integration/meta/oauth/MetaOAuthStateStore\.java:60
- **Issue:** State lives in a ConcurrentHashMap \(MetaOAuthStateStore\.java:24\), so an API restart or a second instance during the consent dialog yields META\_OAUTH\_STATE\_INVALID, and Meta's code is single\-use\. Entries are removed only on consume, so abandoned dialogs leak memory\. pending\.remove runs before the userId check \(60\-68\), so another user who presents a state destroys it\. — Confidence: CONFIRMED\.
- **Fix:** Persist state with a TTL \(a DB table or a signed stateless token\), and check the owner before removing\.

#### [Low] \[Creator\] USERNAME\_TAKEN race handlers are dead code
- **Where:** influora\-api/src/main/java/com/influora/service/CreatorProfileService\.java:92
- **Issue:** Both handlers wrap catch\(DataIntegrityViolationException\) around a plain save\(\) of a managed entity inside @Transactional \(CreatorProfileService\.java:92; PortfolioService\.java:344\-349\)\. The constraint only fires at flush or commit, outside the catch, so a raced username surfaces as a 500\. AuthService and UserPhoneService use saveAndFlush for exactly this reason\. — Confidence: CONFIRMED\.
- **Fix:** Use saveAndFlush in both places\.

#### [Low] \[Creator\] Orphan backend surface: a 501 socials stub and a permitAll matcher for a route no controller serves
- **Where:** influora\-api/src/main/java/com/influora/service/CreatorOnboardingService\.java:85
- **Issue:** POST /onboarding/creator/socials always throws SOCIAL\_OAUTH\_NOT\_IMPLEMENTED \(CreatorOnboardingService\.java:85\)\. Its client connectCreatorSocial \(api\.ts:1541\) has no caller; creator\-onboarding\.tsx uses the Meta OAuth flow instead\. SecurityConfig\.java:93 permits GET /auth/verify\-email, but AuthController \(read in full\) has no GET mapping at all\. — Confidence: CONFIRMED\.
- **Fix:** Delete the stub, its client and the unused matcher\.

#### [Low] \[Creator\] Counter CTA on the deals list does not open the counter form
- **Where:** src/pages/creator\-deals\.tsx:406
- **Issue:** handleCounter navigates to /creator/chat?deal=\.\.\&action=counter, but creator\-chat reads only the deal and tab params \(:715\-717\) and nothing calls setShowCounterForm from the URL\. For rate\-less invites Counter is the primary CTA \(creator\-deals\.tsx:698\) and it merely opens the room\. — Confidence: CONFIRMED\.
- **Fix:** Read action=counter once in creator\-chat, open CounterProposalForm, then strip the param\.

#### [Low] \[Creator\] Counter form shows fabricated deliverables and 'Infinity%' in live mode
- **Where:** src/pages/creator\-chat\.tsx:3238
- **Issue:** CounterProposalForm always receives a hard\-coded \[Instagram Reel x2, Instagram Story x1\] \(:3238\-3241\) and renders 'Deliverables: 2 items' \(counter\-proposal\-form\.tsx:135\)\. For a rate\-less invite originalAmount is 0, so diffPercent = difference / 0 renders as Infinity% or NaN% \(counter\-proposal\-form\.tsx:82,182\)\. — Confidence: CONFIRMED\.
- **Fix:** Pass the real slots from the latest proposal metadata using deliverableSlotsOf, and hide the diff when originalAmount is 0\.

#### [Low] \[Creator\] Already\-reviewed deals reappear in the 'Rate' list after a reload
- **Where:** src/components/shared/collaboration\-reviews\-panel\.tsx:77
- **Issue:** reviewedIds is component state only \(:77,184\-186\)\. The list is every COMPLETED deal \(:60\), and there is no endpoint for reviews the creator has given \(CreatorReviewController\.java:36\-56 exposes create, received and flag\)\. After a reload each reviewed deal is offered again and submitting returns ALREADY\_REVIEWED \(ReviewService\.java:125\)\. — Confidence: CONFIRMED\.
- **Fix:** Add GET /creator/reviews/given, or a reviewedByMe flag on the deal, and filter on it\.

#### [Low] \[Creator\] Deliverable file picker accepts PDF, which the server rejects; invalid types fail silently; the dropdown lists slots that cannot be submitted
- **Where:** src/components/creator/deal\-room/deliverable\-submission\.tsx:89
- **Issue:** validTypes and the accept attribute include application/pdf \(:89,212\), but the server allows only image/\* and video/\* \(CreatorDeliverableService\.java:87,1020\) and returns 400 INVALID\_FILE\_TYPE\. A disallowed drop returns with no feedback \(:91\-93\)\. The Select lists every slot including SUBMITTED and APPROVED ones and defaults to index 0 \(:62\-64,174\-185\); choosing one of those returns 409 INVALID\_STATE \(CreatorDeliverableService\.java:231\)\. — Confidence: CONFIRMED\.
- **Fix:** Remove PDF, toast on rejected types, and disable or filter slots that are not PENDING, DRAFT or REVISION\_REQUESTED\.

#### [Low] \[Creator\] Only the latest 50 messages of a deal room can ever be loaded
- **Where:** src/pages/creator\-chat\.tsx:887
- **Issue:** The server pages messages at 50 with a \`before\` cursor \(DealService\.java:98,601\-606; DealController\.java:118\), and api\.messages\.list accepts before \(api\.ts:2607\)\. The room never passes it and has no 'load older' control\. In a long negotiation the original proposal card and early terms scroll out of reach, and hasProposalCard \(:1977\) is computed over that window only\. — Confidence: CONFIRMED\.
- **Fix:** Add upward pagination that passes the oldest createdAt as before\.

#### [Low] \[Creator\] Per\-campaign commission rate column is never written; every earning is a flat 10%
- **Where:** influora\-api/src/main/java/com/influora/service/AffiliateEarningsService\.java:426
- **Issue:** validateAndCompute reads Campaign\.commissionRate \(Campaign\.java:113\)\. The only call to the builder setter is the copy\-builder at Campaign\.java:549\. No DTO, service or FE file references commissionRate, so DEFAULT\_COMMISSION\_RATE 0\.10 \(:102\) always applies, and a brand cannot set the rate it offered\. — Confidence: CONFIRMED\.
- **Fix:** Expose commissionRate on the affiliate campaign create/update DTO and form with 0 \< rate \<= 1, and show it to creators; or remove the column\.

#### [Low] \[Creator\] Consent withdrawal endpoint has no client
- **Where:** influora\-api/src/main/java/com/influora/web/CreatorAgentController\.java
- **Issue:** DELETE /creator/agent\-preferences/consent \(withdrawConsent\) exists, but api\.ts creatorAgentPrefs \(:6641\-6692\) and meera\-api\.ts have no caller, and MeeraSettingsSection\.tsx only offers update, export and delete\-conversation\. A creator can give DPDP consent but cannot withdraw it from the UI\. — Confidence: CONFIRMED\.
- **Fix:** Add creatorAgentPrefs\.withdrawConsent\(\) and a "Withdraw consent" control in MeeraSettingsSection\.

#### [Low] \[Creator\] A withdrawal amount with more than two decimals returns a 500, and the balance display rounds paise
- **Where:** influora\-api/src/main/java/com/influora/integration/razorpay/RazorpayXClient\.java:126
- **Issue:** CreatorWithdrawRequest validates only @NotNull and @DecimalMin \(MoneyDtos\.java:82\), with no @Digits\(fraction=2\)\. The FE input is type=number with no step \(creator\-wallet\.tsx:1219\-1227\)\. An amount like 500\.125 reaches amountInRupees\.movePointRight\(2\)\.longValueExact\(\), which throws ArithmeticException before the try block, giving a 500 instead of a 400\. Separately, formatINR uses maximumFractionDigits 0 \(creator\-wallet\.tsx:203\-209\), so an available balance of 1234\.56 displays as ₹1,235, more than can actually be withdrawn\. — Confidence: CONFIRMED\.
- **Fix:** Add @Digits\(integer=12, fraction=2\) to the request DTO and step="0\.01" to the input\. Show two decimals on money tiles\.

#### [Low] \[Admin\] FE RBAC matrix is dead code: SUPPORT sees every destructive control and the route guard trusts any string
- **Where:** src/admin/hooks/useAdminAuth\.ts:230
- **Issue:** hasPermission, hasAllPermissions and hasAnyPermission have zero consumers across src/admin and src/pages\. AdminLayout shows all nav items \(AdminLayout\.tsx:63\-79\), and BrandProfile and CreatorProfile render KYC, suspend and budget actions for every role\. The server returns 403 INSUFFICIENT\_ROLE only after submit\. AdminProtectedRoute checks only that localStorage\.admin\_token is truthy \(App\.tsx:222\-225\)\. When refresh fails, apiRequest clears the token \(api\-contracts\.ts:141\) but nothing navigates to /admin/login, so the console stays mounted showing error cards\. No data leaks, because the server enforces access\. — Confidence: CONFIRMED\.
- **Fix:** Gate nav items and action buttons with hasPermission\. Make AdminProtectedRoute depend on useAdminAuth \(isAuthenticated / isLoading\) and redirect on session loss\.

#### [Low] \[Admin\] Unvalidated paging on the creators and flags lists: page 0 returns 500 and pageSize is unbounded
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminCreatorService\.java:167
- **Issue:** PageRequest\.of\(page \- 1, pageSize\) has no clamping \(AdminCreatorService\.java:167; AdminModerationService\.java:138\)\. page=0 throws IllegalArgumentException and returns 500\. pageSize=1000000 loads the whole table plus the batched user and stat lookups\. Sibling services clamp \(AdminBrandService\.java:176\-177; DisputeService list; AdminCreatorConnectionService list\)\. The flags endpoint also ignores the status param the FE sends \(api\-contracts\.ts:733\-740 vs AdminModerationController\.java:55\-60\)\. — Confidence: CONFIRMED\.
- **Fix:** Clamp page to at least 1 and pageSize to 1\.\.100\. Honour or remove the status param\.

#### [Low] \[Admin\] Dead enum value and missing state guards in disputes and connection requests
- **Where:** influora\-api/src/main/java/com/influora/domain/entity/Dispute\.java:102
- **Issue:** Dispute\.markUnderReview\(\) has no callers, so the 'Under Review' filter \(DisputeList\.tsx:407\) is always empty and admins cannot claim a dispute\. CreatorConnectionRequest\.markContacted, markDeclined and markInvited are unconditional \(CreatorConnectionRequest\.java:152\-182\)\. Only the FE guards them \(canAct at CreatorConnectionsPage\.tsx:469\), so a direct API call can move a JOINED request back to CONTACTED or DECLINED\. — Confidence: CONFIRMED\.
- **Fix:** Add a 'start review' action, or drop UNDER\_REVIEW\. Reject transitions from JOINED and DECLINED in the entity\.

#### [Low] \[Admin\] Orphan admin endpoints with no console UI
- **Where:** influora\-api/src/main/java/com/influora/web/AdminExternalCreatorController\.java:60
- **Issue:** These have no FE caller: DELETE /admin/external\-creators/\{id\} \(it returns void, and apiRequest would report 'Malformed response' on an empty 200, api\-contracts\.ts:157\-162\); GET /admin/creator\-connections/\{id\} \(AdminCreatorConnectionController\.java:46\); POST /admin/brands/\{id\}/reanalyze \(AdminBrandController\.java:183\); PUT /admin/creator\-agent/creators/\{id\}/monthly\-cap \(AdminCreatorAgentController\.java:52\); POST /admin/auth/mfa/reset/\{id\} \(AdminAuthController\.java:135\)\. Creator tier override is read only by MeeraContextService\.java:258, not by discovery or rate estimation\. — Confidence: CONFIRMED\.
- **Fix:** Wire UI for delete, reanalyze, cap override and MFA reset, or remove the endpoints\. Make apiRequest tolerate 204 or an empty body\.

#### [Low] \[Admin\] Manual payouts stay PENDING in reconciliation and trigger a bogus live RazorpayX lookup
- **Where:** BE/service/admin/AdminFinanceService\.java:399
- **Issue:** Manual rows get razorpayPayoutId 'manual:\<id\>' \(Payout\.java:225\)\. reconcilePayout skips only null and 'pending:' ids \(:399\), so it calls razorpayXClient\.fetchPayout\('manual:…'\) \(:518\), catches the exception and reports PENDING \(:529\-534\)\. That happens for every manual row on every page load\. — Confidence: CONFIRMED\.
- **Fix:** Short\-circuit METHOD\_MANUAL rows to a MANUAL or MATCHED status with no gateway call\.

#### [Low] \[Admin\] Email queue 'Retrying' filter returns 400
- **Where:** FE/pages/EmailQueuePage\.tsx:300
- **Issue:** The filter option sends status=RETRYING \(useEmailQueue\.ts:91\)\. 'RETRYING' exists only in the DTO \(AdminEmailService\.java:179\-181\)\. parseStatus calls EmailOutboxStatus\.valueOf, and that enum has only PENDING, SENT and FAILED \(EmailOutboxStatus\.java\), so the request fails with 400 'status must be one of PENDING, SENT, FAILED' \(:165\-172\)\. — Confidence: CONFIRMED\.
- **Fix:** Map RETRYING to PENDING with retryCount \> 0 in the backend query, or remove the option\.

#### [Low] \[Admin\] Compose page ignores the \`replay\` flag and says a send 'cannot be recalled' although a cancel endpoint exists
- **Where:** FE/pages/EmailComposePage\.tsx:289
- **Issue:** SendResponse includes \`boolean replay\` \(AdminCustomEmailDtos\.java:78\); true means nobody new was mailed\. The FE type leaves it out \(admin\.types\.ts:870\-874\) and the toast always reads 'N queued' \(EmailComposePage\.tsx:289\-290\)\. The copy 'It cannot be recalled once queued' \(:564\) contradicts POST /admin/emails/custom/\{campaignId\}/cancel \(AdminEmailController\.java:74\-79\), which has no FE client\. — Confidence: CONFIRMED\.
- **Fix:** Show 'Already sent earlier — no new emails queued' when replay is true\. Add a 'Cancel remaining' button wired to the cancel endpoint\.

#### [Low] \[Admin\] Orphan backend endpoints, and a UI message that points the admin to one of them
- **Where:** BE/web/AdminFestivalEnquiryController\.java:108
- **Issue:** These have no FE caller: POST /festival\-enquiries/\{id\}/link\-existing and GET /\{id\}/existing\-account \(:108\-130\); POST /admin/platform\-stats/backfill \(AdminPlatformStatBackfillController\.java:36\); PUT /admin/support/tickets/\{id\}; POST /admin/emails/custom/\{id\}/cancel\. emailApi\.sendBulk \(api\-contracts\.ts:1370\) targets an endpoint that always returns 501\. The provision fallback for EMAIL\_ALREADY\_REGISTERED tells the admin to 'link it to this enquiry manually' \(api\-contracts\.ts:1055\-1056\), but the console has no way to do that\. — Confidence: CONFIRMED\.
- **Fix:** Build the link\-existing flow \(lookup plus confirm\) in FestivalEnquiriesPage\. Remove sendBulk, or mark it disabled\.

#### [Low] \[Admin\] Admin nav is not role\-aware: ADMIN and SUPPORT roles hit hard error cards on SUPER\_ADMIN\-only pages, and the FeeControlPanel read\-only view is unreachable
- **Where:** FE/components/AdminLayout\.tsx:64
- **Issue:** NAV\_ITEMS \(:64\-79\) is rendered for every role\. Finance, Billing, Audit Log, Error Log and Email Queue are SUPER\_ADMIN\-only on the server \(PlatformFeeAdminService\.java:85; AdminBillingService\.java:88; AdminAuditLogService\.java:482; AdminErrorLogService\.java:48; AdminEmailService\.java:60\)\. In FeeControlPanel the GET fails for an ADMIN, so the component returns the error card at :358\-364\. The 'Super Admin only… the schedule above is the live rate' branch \(:449\-456\) is therefore dead code\. — Confidence: CONFIRMED\.
- **Fix:** Filter the nav by role from useAdminAuth, or allow ADMIN to GET the fee config read\-only so the designed read\-only view renders\.

### Ash — AI

#### [High] \[Brand\] Meera credit paywall is a dead end, and its 'fund a campaign to unlock' promise cannot be fulfilled
- **Where:** src/components/feature/meera/MeeraChatPanel\.tsx:1097 ; influora\-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor\.java:386
- **Issue:** When credits are exhausted, CreditPaywall \(meera\-copy\.ts:104\-108 'Fund your first campaign to unlock me fully'\) calls onFunctionCall\('request\_payment'\) with no payload\. useMeeraStage\.ts:38\-45 moves the canvas to the funding stage, and StageFunding\.tsx:83\-85 renders a permanent 'Securing your funds' loader in live mode\. The only code that grants the unlock is AICreditService\.applyEscrowFundedReset \(:288\), called solely from ConfirmLaunchExecutor\.java:386\. That executor is unreachable: confirm\_launch is never offered \(influora\-ai/app/tools/schemas\.py:456\-460\) and is outside the token scope \(OnBehalfTokenService\.java:68,105\)\. A brand that really funds a campaign via EscrowService or CampaignActivationGuard stays EXHAUSTED\. — Confidence: CONFIRMED\.
- **Fix:** Point the CTA at a real route \(the campaign publish step, or the wallet\)\. Call applyEscrowFundedReset from the human funding/activation path \(EscrowService\.applyFunding or CampaignActivationGuard\), or change the copy\.

#### [Medium] \[Brand\] @Transactional is inert on MeeraSessionService\.doSendTurn and doPersistAssistantWriteback, and on CreateCampaignExecutor and RequestPaymentExecutor doExecute
- **Where:** influora\-api/src/main/java/com/influora/service/meera/MeeraSessionService\.java:344,354\-355 ; influora\-api/src/main/java/com/influora/service/meera/tool/CreateCampaignExecutor\.java:170,207\-208
- **Issue:** These protected @Transactional methods are called through a lambda on this inside the non\-transactional IdempotencyService\.executeOnce, so the Spring proxy is bypassed\. In doSendTurn the credit charge commits in its own transaction \(:388\), so a later failure such as message save, context assembly \(:432\) or token mint leaves the brand charged with no turn, and each retry charges again\. In create\_campaign the intent, campaign and tool\-call rows are saved separately; a failure after the campaign save marks the key FAILED, and a retry creates a duplicate draft\. ConfirmLaunchExecutor already avoids this with a self\-proxy \(:177\-185\); the others do not\. — Confidence: CONFIRMED\.
- **Fix:** Use an injected self\-proxy or TransactionTemplate, as in ConfirmLaunchExecutor\. Add compensation that releases the credit when doSendTurn fails\.

#### [Medium] \[Brand\] calculate\_budget always sizes the pool for 5 creators
- **Where:** influora\-ai/app/tools/schemas\.py:116\-132 ; influora\-api/src/main/java/com/influora/service/meera/tool/CalculateBudgetExecutor\.java:104\-105
- **Issue:** The executor reads creator\_count and defaults to 5, but the tool schema only exposes product\_price and goal\. The model has no way to pass the count\. suggestedPoolTotal and the rationale are wrong whenever the brand asks for any other number of creators\. — Confidence: CONFIRMED\.
- **Fix:** Add an optional integer creator\_count \(minimum 1\) to the schema and mention it in the tool description\.

#### [Medium] \[Brand\] A failed Meera turn ends in silence
- **Where:** src/components/feature/meera/MeeraChatPanel\.tsx:931\-936
- **Issue:** On a stream error the panel calls getMessagesAfter\. When the provider failed, nothing was persisted, so the result is empty\. The code then removes the assistant bubble and appends nothing\. The text fallback lives only in \.catch, which runs only if the HTTP call itself fails\. — Confidence: CONFIRMED\.
- **Fix:** If the recovered list is empty, render event\.message or a fixed 'Didn't catch that \- try again?' bubble\.

#### [Medium] \[Brand\] On\-behalf JWT \(120s, no refresh\) is reused for late tool calls and the end\-of\-turn write\-back
- **Where:** influora\-api/src/main/java/com/influora/service/meera/OnBehalfTokenService\.java:43 ; influora\-ai/app/routes/chat\.py:990\-1006
- **Issue:** One token is minted at send time and reused for every Spring call in the loop \(up to 6 iterations, 30s read timeout each, analyze\_site up to 15s per hop\)\. It is also used for the final persist\_assistant\_message\. If the turn runs past 120 seconds, the write\-back gets ON\_BEHALF\_JWT\_INVALID and is only logged\. The reply disappears on reload while the credit stays charged\. — Confidence: PLAUSIBLE\.
- **Fix:** Authenticate the write\-back and refund with the service token plus the verified messageId claim, or allow the token to be refreshed\.

#### [Medium] \[Brand\] Meera public chat URL is only bound in the prod profile
- **Where:** influora\-api/src/main/java/com/influora/config/MeeraStreamProperties\.java:25 ; influora\-api/src/main/resources/application\-prod\.yml:109
- **Issue:** application\.yml has no influora\.meera\.stream\.public\-chat\-url entry\. The sibling \*\-ai\.base\-url entries were fixed for exactly this problem \(application\.yml:222\-257\)\. Any non\-prod deploy hands the browser http://localhost:8000/chat \(MeeraController\.java:145\), so every turn fails to stream\. Confirmed in code; the impact depends on which profile the deploy runs\. — Confidence: PLAUSIBLE\.
- **Fix:** Add public\-chat\-url: $\{MEERA\_PUBLIC\_CHAT\_URL:http://localhost:8000/chat\} to application\.yml\.

#### [Medium] \[Creator\] Creator voice always reaches the AI service without a creator identity, so creator language and the monthly spend cap never apply to voice
- **Where:** influora\-api/src/main/java/com/influora/integration/ai/MeeraVoiceAiClient\.java:203
- **Issue:** CreatorMeeraController's speak and transcribe call voiceAiClient with tokenService\.mint\(workspaceId\)\. That token carries only workspace\_id and scope=service, with no userType \(BrandSafetyServiceTokenService\.java:63\-78\)\. In voice\.py, derive\_audience then returns None \(audience\.py:44\-60\) and the function returns early for any audience other than CREATOR \(voice\.py:189\-193\)\. The browser never calls the AI service's /voice routes directly \(meera\-api\.ts:789, 843 go through Spring\), so the creator branch is unreachable: the forced creator\_language, the per\-creator monthly reservation and accrual \(voice\.py:260\-270, 310\-312\) and the cap override never run\. transcribe\(\) passes no language, so a Hindi\- or Tamil\-speaking creator is transcribed with the default STT language\. Voice spend does not count against the creator's monthly allowance\. — Confidence: CONFIRMED\.
- **Fix:** Mint a service token carrying userType=CREATOR for creator voice calls \(add an audience argument to mint and to MeeraVoiceAiClient\.speak/transcribe\), and add a test that a creator voice call increments the creator's monthly total\.

#### [Medium] \[Creator\] Meera quotes gross agreed rates as money earned and always reports KYC as not done
- **Where:** influora\-api/src/main/java/com/influora/service/meera/MeeraContextService\.java:387
- **Issue:** total\_earned\_inr is the sum of Collaboration\.agreedRate for COMPLETED deals \(:387\-391\)\. It is rendered as "Total earned on Influora: INR X" \(assembler\.py:595\-597\), and the persona tells the model to quote context numbers exactly \(creator\_persona\.py:60\-64\)\. That figure is before the platform fee \(PlatformFeeService\.split\), takes no account of whether funds were released, and excludes affiliate commission, so it disagrees with the wallet\. kyc\_done is true only for VERIFIED \(:255\), which no code sets, so Meera says "KYC: not done" \(assembler\.py:647\-651\) to a creator who has submitted KYC and is able to withdraw\. — Confidence: CONFIRMED\.
- **Fix:** Source total earned from creator\-wallet ledger credits \(ESCROW\_RELEASE net plus AFFILIATE\_COMMISSION\), or label the number as gross deal value\. Send a three\-state KYC value \(not\_submitted, submitted, verified\)\.

#### [Medium] \[Creator\] Daily suggestion is permanently pending with default configuration while the UI promises an idea by tomorrow morning
- **Where:** influora\-api/src/main/java/com/influora/service/creatorcopilot/CreatorNudgeService\.java
- **Issue:** getSuggestion returns pending\_tagging whenever themeTagsJson is blank\. Its only producers, CreatorThemeTaggingJob \(:72\) and CreatorCaptionSyncJob \(:91 nightly, :159 on connect\), return immediately unless influora\.creator\-copilot\.enabled is true, and the default is false \(application\.yml:534; deploy/\*/docker\-compose\*\.yml also default it to false\)\. The endpoint itself is not gated on the flag\. The FE maps pending\_tagging to a loading card reading "Reading your recent posts — your first idea lands by tomorrow morning\." \(SuggestionEmptyState\.tsx:26\), with staleTime Infinity \(useDailySuggestion\.ts:158\)\. — Confidence: CONFIRMED\.
- **Fix:** Return a distinct feature\_disabled status, or gate the endpoint on the flag so the FE can hide the section\. Otherwise turn the flag on in the deploy configs together with TREND\_INGEST\_ENABLED\.

#### [Medium] \[Brand \+ Creator\] The Meera API layer has no token refresh and no request timeout
- **Where:** src/lib/meera\-api\.ts:514
- **Issue:** request\(\), speak\(\) and transcribe\(\) use plain fetch with the in\-memory access token \(:456\-473\)\. A search of the file for refresh, 401, AbortController or timeout finds nothing relevant\. api\.ts renews proactively and retries on 401 \(fetchWithAuthRetry; REQUEST\_TIMEOUT\_MS at api\.ts:372\), but only on its own calls\. A creator who stays in the Co\-pilot chat past the access\-token lifetime gets 401s, shown as "Something went wrong sending that" or "Couldn't reach Meera\.", until some api\.ts call happens to refresh the token\. A stalled Spring call leaves the chat in the sending state indefinitely, because the heartbeat timeout only covers the SSE stream, not the send\-turn POST\. Brand side: the same meera\-api\.ts layer serves /brand/meera, so a brand chat past the access\-token lifetime 401s the same way\. — Confidence: CONFIRMED\.
- **Fix:** Route Meera REST calls through the shared HttpClient, or export its refresh\-and\-retry and timeout wrapper and use it in meera\-api\.ts\.

#### [Low] \[Brand\] OPTIONS\_PRESENTED events carry no session id, and client\-supplied history is uncapped
- **Where:** influora\-ai/app/tools/loop\.py:370\-378 ; influora\-ai/app/prompt/assembler\.py:713\-757
- **Issue:** session\_id is hardcoded to None, so the row cannot be joined with OPTION\_TAPPED events \(MeeraInternalController\.java:413\)\. The conversation array is replayed whole with no length cap; only the per\-workspace daily USD cap bounds the cost of one credit \(costs/gate\.py:81\-114\)\. — Confidence: CONFIRMED\.
- **Fix:** Pass the verified conversation id, and cap the history by turns or tokens\.

#### [Low] \[Creator\] Message typed while the chat is in its connect\-error state is silently dropped; error bubbles are replayed to the model as assistant history
- **Where:** src/components/creator/MeeraCopilotChat\.tsx:211
- **Issue:** When startSession fails, the input and Send button are enabled \(they are disabled only while connecting or sending, :391, :411\)\. handleSend clears the draft and adds the user bubble \(:196\-197\), then returns at \`if \(\!conversationId\) return;\` with no error and no retry, so the message is never sent\. Separately, the conversation replay \(:294\-297\) maps every local bubble, including "Meera stopped responding — try again?" and the mock or error texts, to role assistant and sends them to the AI service's /chat as history\. — Confidence: CONFIRMED\.
- **Fix:** Disable the composer while conversationId is null, or trigger a reconnect on send\. Tag local\-only bubbles and leave them out of the replayed conversation\.

### Meera — Build

#### [Critical] \[All\] Backend test suite fails: mvn test = BUILD FAILURE \(3094 run, 0 failures, 1 error, 21 skipped\)
- **Where:** influora\-api/src/test/java/com/influora/service/BrandCampaignFeeServiceTest\.java:213\-214
- **Issue:** BrandCampaignFeeServiceTest\.deltaChargeInsufficientBalanceNamesOnlyTheShortfall errors with Mockito UnnecessaryStubbingException: the two stubs in stubBrandWallet \(:213\-214\) are never used by that test under strict stubs\. Rated Critical only because a red suite blocks CI/publish\-images; it is a 2\-line test\-hygiene defect, not product behaviour\. The file has UNCOMMITTED edits made after this session started, i\.e\. it sits in the other session's in\-progress fee work\. — Confidence: CONFIRMED \(command output\)\.
- **Fix:** Drop the unused stubs from that test \(or mark them lenient\(\)\), re\-run \`mvn \-B test\` un\-piped and confirm BUILD SUCCESS before committing the fee changes\.

#### [High] \[Brand\] Tracked code depends on an untracked file
- **Where:** influora\-api/src/main/java/com/influora/service/CampaignActivationGuard\.java \(untracked\) ; ConfirmLaunchExecutor\.java:344 ; CampaignService\.java:370
- **Issue:** The on\-disk code is coherent\. The risk is a partial commit: committing only the modified tracked files leaves a branch that does not compile\. Orchestrator re\-rate \(Low \-\> High\): \`git ls\-files\` confirms CampaignActivationGuard\.java is untracked \('??'\) while 5 TRACKED files import it \(Campaign\.java, BrandCampaignFeeService\.java, CampaignService\.java, EscrowService\.java, ConfirmLaunchExecutor\.java\)\. Every local gate \(javac, mvn test, IDE\) passes because the file is on disk; a commit of the modified tracked files alone will not compile in CI\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** git add the guard file and its tests together with the callers\.

#### [Medium] \[All\] 21 database\-integration tests are skipped locally, which is exactly the layer the two creator\-wallet Criticals live in
- **Where:** influora\-api/src/test/java/com/influora/integration/dbconstraints/\*, EscrowReleaseGateIntegrationTest, CampaignActivationLedgerIntegrationTest
- **Issue:** All 7 Testcontainers classes report Skipped \(no Docker\): DatabaseConstraintIntegrationTest, EscrowReleaseGateIntegrationTest \(7\), CampaignActivationLedgerIntegrationTest, CreatorConversationPersistIntegrationTest \(4\), and others\. Everything that passed is Mockito/H2\-level, so a VARCHAR\(64\) overflow or a MySQL ENUM rejection \(the withdrawal and affiliate\-settlement Criticals\) cannot be caught by the green 3,073\. — Confidence: CONFIRMED \(command output\)\.
- **Fix:** Run the suite where Docker is available before any money\-path release; add Flyway\-backed integration tests for creator withdraw \(real UUID key\) and affiliate settlement \(assert GET /wallet balance increases\)\.

#### [Low] \[All\] Frontend suite is timing\-fragile: 5 tests \+ 1 suite timed out under load, all pass in isolation
- **Where:** src/\_\_tests\_\_/creator\-protected\-route\.test\.tsx:55; src/pages/\_\_tests\_\_/creator\-chat\.dispute\-entry\.test\.tsx:160,203; src/pages/brand\-onboarding\-duplicate\-409\.test\.tsx:120; src/pages/brand\-new\-hype\-campaign\.f0848\-publish\-route\.test\.tsx:134
- **Issue:** Full run: 219/223 files passed, 5 tests failed \+ 1 suite failed, every one a timeout while the machine was also running mvn and the audit\. Re\-run alone: all 18 tests pass \(creator\-protected\-route 3/3 in 28s\)\. But individual tests take 10\-28s against a 30s test / 120s hook budget, so a busy CI runner will flake\. \`npm run typecheck\` passed clean \(exit 0\)\. — Confidence: CONFIRMED \(command output\)\.
- **Fix:** Stop importing the whole '@/App' in the protected\-route test \(export the guard from its own module\); use userEvent\.setup\(\{delay:null\}\) or fireEvent in the long form tests; raise testTimeout only as a last resort\.

### Kabir — Security

#### [High] \[Admin\] Admin login and MFA lockout counters are rolled back on every failed attempt, so lockout never triggers
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminAuthService\.java:107
- **Issue:** login\(\) is @Transactional \(107\)\. recordFailedAttemptAndThrow saves the incremented counter \(190\) and then throws ApiException \(193\-198\)\. recordFailedMfaAttemptAndThrow does the same \(213, 215\)\. ApiException extends RuntimeException \(common/ApiException\.java:5\) and there is no noRollbackFor, so the transaction rolls back and failed\_login\_attempts, locked\_until and mfa\_locked\_until are never persisted\. The repo already documents this exact trap at BrandEmailOtpService\.java:376 \(noRollbackFor = ApiException\.class\)\. An attacker gets unlimited password guesses, and unlimited 6\-digit TOTP guesses once the password is known\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Persist the counter in a separate REQUIRES\_NEW bean method, or put @Transactional\(noRollbackFor = ApiException\.class\) on login\(\)\. Add an integration test that reloads the row after a failed login\.

#### [High] \[Admin\] POST /admin/auth/login and /admin/auth/refresh have no rate\-limit bucket
- **Where:** influora\-api/src/main/java/com/influora/security/AuthRateLimitFilter\.java:475
- **Issue:** bucketFor strips only the /api/v1 context \(343, 613\-618\), so the path is /admin/auth/login\. The sensitive bucket matches path\.startsWith\("/auth/"\) \(475\)\. The only /admin rule is coupons \(508\)\. Everything else returns null, meaning unthrottled\. SecurityConfig\.java:263\-266 makes both routes permitAll\. Together with the inert lockout above, admin credentials and TOTP have no brute\-force defence at all, contrary to the AdminAuthService javadoc\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Add an explicit bucket for /admin/auth/login and /admin/auth/refresh, stricter than the sensitive bucket, keyed by IP and by email\.

#### [High] \[Admin\] AdminCreatorAgentController has no AdminContextService check: any ROLE\_ADMIN token can set AI spend caps, with no MFA, tier, active\-admin or audit check
- **Where:** influora\-api/src/main/java/com/influora/web/AdminCreatorAgentController\.java:52
- **Issue:** Both handlers accept the principal and ignore it: getBaselines\(\) \(39\-41\) and adminSetMonthlyCapOverride\(creatorId, cap\) \(52\-58\)\. CreatorAgentPreferencesService\.java:265\-283 and CreatorAgentBaselineService\.java:62 make no role check\. The only guard is SecurityConfig\.java:276\-277 hasRole\(ADMIN\), which is a pure JWT claim \(JwtAuthenticationFilter\.java:33\-41\)\. So a SUPPORT\-tier admin, an admin without MFA, or a deactivated admin whose 15\-minute token is still valid can change any creator's AI monthly USD cap\. No audit row is written\. Every other admin service calls requireRoleWithMfaSatisfied\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Pass the principal through\. Call requireRoleWithMfaSatisfied\(SUPER\_ADMIN\) for the PUT and \(SUPER\_ADMIN, ADMIN, SUPPORT\) for the GET\. Add adminAuditLogService\.record on the write\.

#### [High] \[Admin\] Audit allowlist drift: manual payouts, festival\-enquiry status changes and all moderation actions are silently never audited
- **Where:** BE/service/admin/AdminAuditLogService\.java:97
- **Issue:** doRecord throws IllegalArgumentException for any action outside ALLOWED\_ACTIONS \(:97\-116\) or any entityType outside ALLOWED\_ENTITY\_TYPES \(:119\-166\), and record\(\) swallows the exception into log\.error \(:326\-346\)\. Three writers pass values that are not on those lists\. AdminFinanceService\.java:242\-255 passes action "PAYOUT\_RECORDED\_MANUAL" and entityType "Payout"; it debits the creator's wallet ledger, and FIELD\_ALLOWLIST has no Payout entry either\. AdminFestivalEnquiryService\.java:126\-134 passes entityType "FESTIVAL\_ENQUIRY"\. AdminModerationService\.java:197\-256 passes actions "ACTION", "REVIEW", "WARN" and "ESCALATE"\. All three return success and write no admin\_audit\_log row\. The file's own comments at :147\-165 record this same defect for two other entity types, and it has recurred\. — Confidence: CONFIRMED \+ independently re\-verified by orchestrator\.
- **Fix:** Add the missing actions and entity types together with their FIELD\_ALLOWLIST entries\. Make an unrecognised action or type fail startup or a test: enumerate the constants, have every caller use an enum, and add an integration test that asserts a row exists after each admin mutation\. For money\-affecting actions, fail the request when the audit write fails\.

#### [Medium] \[Admin\] Logout after the access token has expired does not end the server session; the 30\-day refresh cookie keeps working
- **Where:** src/admin/hooks/useAdminAuth\.ts:249
- **Issue:** authApi\.logout is excluded from refresh\-and\-retry \(api\-contracts\.ts:104, 115\) and /admin/auth/logout sits behind hasRole\(ADMIN\) \(SecurityConfig\.java:276\)\. With an access token older than 15 minutes the call returns 401, so revokeAllForAdmin and clearRefreshCookie \(AdminAuthController\.java:101\-107\) never run\. The FE still clears localStorage and shows the admin as logged out \(259\-261\)\. Anyone on that browser can then put any string in localStorage\.admin\_token, which passes AdminProtectedRoute \(App\.tsx:223\)\. useAdminAuth sees a malformed token and calls refreshAdminSession\(\) \(199\-200\), which mints a fresh admin token from the surviving cookie\. — Confidence: CONFIRMED\.
- **Fix:** Refresh first, then call logout\. Better, make logout permitAll and revoke by the refresh cookie\. Always clear the cookie\.

#### [Medium] \[Admin\] Admin login, logout and MFA reset have no server\-side audit; the only login trail is client\-posted
- **Where:** influora\-api/src/main/java/com/influora/service/admin/AdminAuthService\.java:88
- **Issue:** AdminAuthService injects no AdminAuditLogService \(88\-105\)\. login \(108\), logout \(266\) and resetMfaForAdmin \(342\) write nothing, while the login page promises 'All actions are logged' \(admin\-login\.tsx:89\)\. The only LOGIN rows come from the browser: useAdminAuth posts AuditAction\.LOGIN on every hook mount \(useAdminAuth\.ts:215\)\. The hook is mounted in admin\-console\.tsx:59, AdminLayout\.tsx:106 and FeeControlPanel\.tsx:231, so each page load writes 2\-3 fake LOGIN rows, and an attacker using the API directly leaves none\. Failed logins are never recorded\. Related: the client\-posted LOGIN/LOGOUT entries use entityType 'ADMIN\_SESSION', which AdminAuditLogService\.recordClientEntry drops \(:399\-404\) while AuditLogController\.java:100\-101 still answers 201; ADMIN/SUPPORT roles get 403 on every client audit call and the entries pile up in a localStorage retry queue \(auditLogger\.ts:131\-139\)\. — Confidence: CONFIRMED\.
- **Fix:** Record LOGIN\_SUCCESS, LOGIN\_FAILED, LOGOUT and MFA\_RESET server\-side with IP and user agent\. Remove the client\-side LOGIN audit call\.

#### [Medium] \[Admin\] Privileged mutations with no audit call: payout retry, email\-queue retry, error resolve
- **Where:** BE/service/admin/AdminFinanceService\.java:584
- **Issue:** retryPayout \(AdminFinanceService\.java:584\-594\) re\-drives a live RazorpayX payout through PayoutReconciliationService\.retryFailedPayout \(:316\-350\); that service has no audit dependency \(constructor :77\-92\)\. 'PAYOUT\_RETRY' is an allowed action but is never used\. AdminEmailService\.retry \(:77\-94\) and AdminErrorLogService\.resolve \(:61\-68\) also write nothing\. Email retry additionally re\-queues rows that markCancelled or markSkippedUnsubscribed set to FAILED \(EmailOutbox\.java:160\-181\)\. EmailWorker\.java:294 only checks that the row is PENDING, so a broadcast row the admin cancelled can be sent again one row at a time, with no audit trail\. — Confidence: CONFIRMED\.
- **Fix:** Call adminAuditLogService\.record \(passing the HttpServletRequest\) in all three\. Refuse to retry rows whose errorMessage marks them cancelled or unsubscribed, or add dedicated terminal statuses\.

#### [Medium] \[Admin\] Broadcast\-email audit goes to a table the Audit Log page never reads
- **Where:** BE/service/admin/AdminCustomEmailService\.java:251
- **Issue:** Preview, send and cancel are audited through AuditLogService\.recordAdminAction \(AdminCustomEmailService\.java:251, 472, 524\), which writes to table audit\_log \(AuditLogEntry\.java:22\)\. The admin Audit Log page reads only admin\_audit\_log \(AdminAuditLogService\.java:490\-495; AdminAuditLog\.java:29\)\. AuditLogEntryRepository has no reader in src/main; AuditLogService\.java:53\-130 is its only user\. Mass mailings to up to 5,000 users therefore never appear in the console's audit trail\. — Confidence: CONFIRMED\.
- **Fix:** Also write an admin\_audit\_log row \(a new EMAIL\_CAMPAIGN entity type\), or give AuditLogController a view of audit\_log entries where actorType is HUMAN\.

## Next steps
- Route Critical/High blockers to Ananya (frontend) / Vikram (backend) via Arjun.
- Escalate Critical to Swapnil via Kavya/Priya.
- Re-run the same stage after fixes before final PASS.

_Generated by the `tester` skill._
---

## Feature matrix (every feature traced, by role)

390 features traced end to end: page/control -> API client -> controller -> guard -> service -> repository.

| Role | Features | Working | Partial | Broken | Stub / not built | Backend only (no UI) | Needs live check | Working % |
|---|---|---|---|---|---|---|---|---|
| **Brand** | 176 | 76 | 51 | 17 | 14 | 13 | 5 | 43% |
| **Creator** | 119 | 52 | 45 | 10 | 6 | 4 | 2 | 44% |
| **Admin** | 95 | 40 | 24 | 12 | 8 | 9 | 2 | 42% |
| **ALL** | 390 | 168 | 120 | 39 | 28 | 26 | 9 | 43% |


### Brand — auth, onboarding, workspace, settings, integrations

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Brand login | **WORKING** | brand-login.tsx:34 → api.ts:1072 → AuthController.java:81 → AuthService.java:251 |
| 2 | Login ?next= return path (used by invite page) | **BROKEN** | brand-accept-invite.tsx:75 sends ?next=; brand-login.tsx:43 never reads it |
| 3 | Login recovery for unverified email | **WORKING** | brand-login.tsx:45-47,80 → BrandEmailOtpService.java:413-421 |
| 4 | Brand register (2 steps, phone, email OTP) | **WORKING** | brand-register.tsx:140 → api.ts:1123 → AuthController.java:70 → AuthService.java:126-247 |
| 5 | Email OTP send/verify | **WORKING** (WORKING (delivery needs MSG91)) | api.ts:1156-1171 ↔ AuthController.java:51-68 |
| 6 | Public config read | **WORKING** | api.ts:3643 ↔ PublicConfigController.java:57 |
| 7 | Forgot password | **WORKING** | brand-forgot-password.tsx:22 → api.ts:1202 → AuthController.java:163 |
| 8 | Reset password | **PARTIAL** | brand-reset-password.tsx:53 → AuthController.java:170; wrong error copy for weak password |
| 9 | Change password | **PARTIAL** | brand-settings.tsx:527 → AccountController.java:125; kills own session |
| 10 | Logout | **PARTIAL** | brand-layout.tsx:254-281 → AuthController.java:153; react-query cache never cleared |
| 11 | Logout from all devices | **WORKING** | brand-settings.tsx:543 → AuthService.java:620 |
| 12 | Session refresh/bootstrap on protected routes | **WORKING** | App.tsx:125-142 → api.ts:558-593 → AuthController.java:131 |
| 13 | Session on unprotected /brand/onboarding and /brand/invite | **BROKEN** | App.tsx:273-277; nothing calls bootstrap for these routes |
| 14 | Onboarding company save | **WORKING** (WORKING (when token in memory)) | brand-onboarding.tsx:99 → OnboardingController.java:32 → OnboardingService.java:56 |
| 15 | Onboarding slug check | **WORKING** | onboarding-steps.tsx:1068 → api.ts:1443 ↔ WorkspaceController.java:36 |
| 16 | Onboarding logo upload | **PARTIAL** | onboarding-steps.tsx:1103 → upload.ts:63 → UploadController.java:42; 401 before registration |
| 17 | Onboarding complete + status guard | **WORKING** | brand-onboarding.tsx:169,178 → OnboardingController.java:39,57 |
| 18 | Accept workspace invite | **BROKEN** | no link in email; no session on cold load; membership never becomes active workspace |
| 19 | Team: list/invite/revoke/remove | **PARTIAL** | team-members-panel.tsx:78-167 ↔ WorkspaceMemberController.java:51-101; non-admin error view, raw ULIDs |
| 20 | Workspace switch | **ORPHAN-BE** | WorkspaceMemberController.java:104 has no FE caller |
| 21 | Member role change | **ORPHAN-BE** | WorkspaceMemberDtos.java:34 ChangeRoleRequest has no mapping |
| 22 | Settings: workspace info GET/PATCH | **WORKING** | brand-settings.tsx:185,246 ↔ WorkspaceController.java:52,65 |
| 23 | Settings: account mobile number | **WORKING** | brand-settings.tsx:299,368 ↔ UserController.java:27,32 |
| 24 | Settings: personal name/avatar/timezone | **STUB** (STUB (no UI)) | PATCH /users/me supports it; FE only sends {phone} |
| 25 | Settings: notification toggles | **WORKING** | brand-settings.tsx:410-482 ↔ NotificationController.java:253,270 |
| 26 | Settings: push + weekly digest | **STUB** (STUB (honestly disabled)) | brand-settings.tsx:744,786-791 |
| 27 | Billing plan/usage/invoices/upgrade/cancel | **WORKING** (WORKING (Razorpay not verifiable statically)) | api.ts:4346-4393 ↔ BillingController.java:86-197 |
| 28 | Workspace verification (KYC) submit | **PARTIAL** | brand-verification.tsx:98 → OnboardingController.java:45; no role gate, rejected reason never shown |
| 29 | Shopify connect + callback | **BROKEN** (BROKEN on every deployed stack) | ShopifyConnectController.java:94-99; api-key/secret bound nowhere |
| 30 | WooCommerce connect | **WORKING** (WORKING (webhook delivery not verifiable)) | StoreIntegrationSetup.tsx:109 ↔ WooCommerceConnectController.java:61 |
| 31 | Store status + disconnect | **WORKING** | api.ts:5237,5243 ↔ StoreIntegrationStatusController.java:75,113 |
| 32 | Conversion webhook secret generate | **PARTIAL** | ConversionWebhookSecretCard.tsx:47 ↔ ConversionWebhookSecretController.java:50; silent rotate |
| 33 | Conversion webhook secret revoke | **ORPHAN-BE** | api.ts:5200 client exists, no UI |
| 34 | Notification bell list/mark read/mark all | **WORKING** | useNotifications.ts:182-262 ↔ NotificationController.java:82-133 |
| 35 | /brand/notifications page | **PARTIAL** | first 20 rows only, no paging |
| 36 | /brand/help | **WORKING** | brand-help.tsx:57-67 |
| 37 | Support tickets (requester side) | **ORPHAN-BE** | SupportController.java:47-75 has no FE caller |
| 38 | Brand account delete | **ORPHAN-BE** | AccountController.java:79 only called from creator-settings.tsx:337 |
| 39 | Sidebar/header/command-bar links | **WORKING** | brand-layout.tsx:118-156, command-bar.tsx:44-85 all resolve |
| 40 | Command bar search | **PARTIAL** | command-bar.tsx:110-111,152 lists only static nav items in live mode |

### Brand — campaigns, discovery, pipeline

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Dashboard load (actions, wallet, pipeline) | **WORKING** | FE src/components/brand/dashboard/dashboard-page.tsx:152-154 -&gt; src/lib/api.ts:4108-4128 -&gt; BE web/DashboardController.java:34-47 (requireMember) -&gt; service/DashboardService.java:90-128 |
| 2 | Dashboard pipeline-stage click | **PARTIAL** | dashboard-page.tsx:402-404 navigates to /brand/campaigns?status=&lt;collab-stage&gt;. campaigns-list.tsx never reads search params, and the buckets are collaboration stages (DashboardService.java:133-139), not campaign statuses |
| 3 | Dashboard action-item links | **WORKING** | DashboardService.java:107,127 emit /brand/chat?deal=...&tab=... Route App.tsx:353; brand-chat.tsx:684-685 reads both params |
| 4 | Campaign list: load, status filter, search, load-more | **WORKING** | campaigns-list.tsx:306-317,340-355 -&gt; api.ts:1753-1781 -&gt; CampaignController.java:45-56 -&gt; CampaignService.java:94-137 -&gt; repository/CampaignSpecs.java:17-32. Title/description search matches on both sides |
| 5 | Campaign list header stats | **PARTIAL** | campaigns-list.tsx:492-498 compute total/active/draft from the status-filtered page fetched at :307. Filtering by DRAFT shows 'Active 0' |
| 6 | Duplicate (list and detail) | **WORKING** | campaigns-list.tsx:375-380, brand-campaign-detail.tsx:896-902 -&gt; api.ts:1815 -&gt; CampaignController.java:86 -&gt; CampaignService.java:393-407 (role gate, tenant scope, type and hype copied at Campaign.java:524-552) |
| 7 | Delete campaign | **PARTIAL** | Offered for every status (campaigns-list.tsx:842-850, brand-campaign-detail.tsx:1154-1160). BE allows DRAFT only (CampaignValidator.java:111-116). A DRAFT with a funded hold hits an FK and returns a generic 409 (common/GlobalExceptionHandler.java:110-114) |
| 8 | Archive campaign | **STUB** | Absent: no FE control. No ARCHIVED value in domain/enums/CampaignStatus.java:3-10 |
| 9 | Pause (ACTIVE-&gt;PAUSED) | **WORKING** | campaigns-list.tsx:411-416, brand-campaign-detail.tsx:914-918 -&gt; PATCH /campaigns/:id -&gt; CampaignService.java:254 (status-only patch allowed) -&gt; Campaign.java:481 |
| 10 | Resume (PAUSED-&gt;ACTIVE) | **PARTIAL** | Same PATCH -&gt; CampaignActivationGuard.java:51-62 requires a FUNDED hold. The fee delta is handled (BrandCampaignFeeService.java:236-244). Fails with a raw toast if no FUNDED hold remains |
| 11 | Save as template | **WORKING** | campaigns-list.tsx:392-397 -&gt; api.ts:2373 -&gt; CampaignTemplateController.java:54-60 (@RequiresPlan) -&gt; CampaignTemplateService.java:69-103. The 402 message reaches the toast |
| 12 | Template list, apply, delete | **WORKING** | brand-new-campaign.tsx:168-229 -&gt; api.ts:2358-2382 -&gt; CampaignTemplateController.java:42-66. Tenant-scoped at CampaignTemplateService.java:110-133 |
| 13 | New-campaign type picker | **WORKING** | brand-new-campaign.tsx:199-246 |
| 14 | OPEN campaign: create as DRAFT | **WORKING** | campaign-form.tsx:642-704 -&gt; api.ts:1791-1796,1665-1738 -&gt; CampaignController.java:64 -&gt; CampaignService.java:144-242. DTO fields match CampaignDtos.java:60-94 |
| 15 | DIRECT ('Direct Deal') campaign: create | **BROKEN** | See finding 3: brand-new-campaign.tsx:65-68, api.ts:1628 vs IntegrationHealthService.java:74-76, CampaignService.java:186-193 (409 NO_STORE_INTEGRATION, no FE handling) |
| 16 | Publish (DRAFT -&gt; secure funds -&gt; ACTIVE + fee) | **PARTIAL** | secure-and-publish-step.tsx:137-161 -&gt; POST /wallet/escrow/fund (EscrowController.java:68-89) -&gt; PATCH status=ACTIVE -&gt; CampaignService.java:369-371 -&gt; CampaignActivationGuard.java:50-63. FE and BE agree; money consequences in findings 1, 6, 7 |
| 17 | Edit non-ACTIVE campaign | **PARTIAL** | campaign-form.tsx:419-466,686-688. 'Save Draft' on a PAUSED campaign reverts it to DRAFT (finding 2) |
| 18 | Edit blocked for ACTIVE | **WORKING** | campaigns-list.tsx:789-807, brand-campaign-detail.tsx:1062-1083, CampaignValidator.java:100-109, handled at campaign-form.tsx:717-724 |
| 19 | HYPE create and launch | **PARTIAL** | brand-new-hype-campaign.tsx:245-310 -&gt; CampaignService.java:195-206 (budget derived server-side) -&gt; same publish step. Stale liveUntil when publishing later (finding 8); also affected by findings 1, 2 |
| 20 | HYPE edit / resume a Meera draft | **WORKING** | brand-edit-campaign.tsx:73-77 -&gt; brand-new-hype-campaign.tsx:136-177 -&gt; CampaignService.java:307-331 (merge and validate) |
| 21 | Campaign detail load | **WORKING** | brand-campaign-detail.tsx:646-662 -&gt; GET /campaigns/:id, GET /deals, GET /campaigns/:id/analytics. Analytics is tenant-scoped at DeliverableMetricService.java:162-170 |
| 22 | Detail 'Publish campaign' dialog (DRAFT) | **PARTIAL** | brand-campaign-detail.tsx:1131-1140,1190-1208. No verification pre-check (finding 6) |
| 23 | Bids accept, reject, counter | **WORKING** | brand-campaign-detail.tsx:760-842 -&gt; api.ts:2480-2533 -&gt; DealController.java:86-108 -&gt; DealService.java:346,1102-1150 (role gate at :1572-1578) |
| 24 | Generate contract from the detail page | **NOT-VERIFIABLE-STATICALLY** | FE brand-campaign-detail.tsx:855-868 verified only. ContractController is another slice |
| 25 | Export CSV/PDF | **WORKING** | brand-campaign-detail.tsx:611-635 -&gt; api.ts:4406-4410 -&gt; ReportExportController.java:36-52 (Pro gate; 402 copy handled) |
| 26 | Campaign state badge in detail header | **STUB** | brand-campaign-detail.tsx:1039-1042 hardcodes isCompleted ? 'SETTLED' : 'IN_PRODUCTION'. A DRAFT reads 'In production' |
| 27 | Platform-fee estimate on detail | **PARTIAL** | Finding 12: brand-campaign-detail.tsx:2106-2111 vs BrandPlatformFeeService.java:53-58 (global rate) vs BrandCampaignFeeService.java:113-127,224 (plan rate) |
| 28 | Complete / cancel / close campaign | **BROKEN** | Absent (finding 4): no FE control (brand-campaign-detail.tsx:1121-1152, campaigns-list.tsx:824-840) and no BE writer of COMPLETED/CANCELLED |
| 29 | Bid 'Message' link | **PARTIAL** | brand-campaign-detail.tsx:1426 uses ?creator=&lt;userId&gt;. brand-chat.tsx:1027-1028 picks the first deal room with that creator, not this campaign's deal |
| 30 | Bid 'Profile' link | **WORKING** | brand-campaign-detail.tsx:1430 passes a User id. CreatorDiscoveryService.java:952-968 resolves profile id, user id or username |
| 31 | Tracking page (UTM links, coupons) | **PARTIAL** | Endpoints and DTOs match (api.ts:5103-5140 &lt;-&gt; CampaignTrackingController.java:67-119, web/dto/tracking/TrackingDtos.java:71-156). The forms require typed raw ids (finding 13) |
| 32 | Discover search, filters, sort, facets, load-more | **WORKING** | creator-discovery.tsx:709-727 -&gt; api.ts:2070-2093,1896-1918 -&gt; CreatorController.java:81-125 -&gt; CreatorDiscoveryService.java:159-249. Multi-city CSV handled at service/CreatorProfileSpecifications.java:101-109 |
| 33 | Featured rail and review-step suggestions | **WORKING** | creator-discovery.tsx:805-812, campaign-form.tsx:350-365 -&gt; CreatorController.java:127-142 |
| 34 | Save creator (Discover) | **PARTIAL** | creator-discovery.tsx:679-692 -&gt; CreatorController.java:166 -&gt; CreatorDiscoveryService.java:444-473. The plan-cap 402 message is swallowed (finding 10) |
| 35 | Invite / priced offer modal | **WORKING** | creator-discovery.tsx:592-616 -&gt; POST /deals or POST /creators/:id/invite. ACTIVE-only picker at :1709; DRAFT guard at CreatorDiscoveryService.java:522 |
| 36 | Invite modal -&gt; 'Create new campaign' handoff | **BROKEN** | Finding 9: creator-discovery.tsx:561 sends ?creator= but brand-new-campaign.tsx:87-90 and campaign-form.tsx:384 read ?creatorId= |
| 37 | External (Meta) creators: list, lookup, connect, requests | **WORKING** | creator-discovery.tsx:2187,2243,2270,2301 -&gt; api.ts:2254-2325 -&gt; ExternalCreatorController.java:37-72. DTOs match web/dto/creator/ExternalCreatorDtos.java:19-56 |
| 38 | External card -&gt; create campaign -&gt; invite after publish | **WORKING** | creator-discovery.tsx:2058-2105 (creatorId, ig) -&gt; campaign-form.tsx:384,694-696,744-749 |
| 39 | Creator profile load and similar creators | **WORKING** | brand-creator-profile.tsx:431,499 -&gt; CreatorController.java:144-164 |
| 40 | Creator profile Save (bookmark) button | **STUB** | Finding 11: brand-creator-profile.tsx:455,584-591 toggles local state only; never calls api.creators.toggleSaved (api.ts:2103-2106) |
| 41 | Creator profile invite | **PARTIAL** | Finding 14: brand-creator-profile.tsx:476-477 lists all statuses, first 20 only; CreatorDiscoveryService.java:522-527 refuses DRAFT |
| 42 | Creator profile Message | **WORKING** | brand-creator-profile.tsx:663 -&gt; brand-messages.tsx:341,366 matches on creatorProfileId |
| 43 | Pipeline board, list, timeline | **WORKING** | Read-only. brand-pipeline.tsx:425-445 -&gt; GET /deals -&gt; DealService.java:152-178. Cards link to /brand/deals/:id (route App.tsx:508) |
| 44 | Campaign CRUD role gates and tenant scoping | **WORKING** | CampaignService.java:150-151,247-248,385,397,425-427. No cross-workspace access found |
| 45 | Discovery and tracking authorization | **PARTIAL** | Finding 5: CreatorDiscoveryService.java:445-447,502-505 and CampaignTrackingController.java:72,99 use requireBrandWorkspace only; no requireMember/requireRole |
| 46 | GET /creators, GET /creators/{id} | **ORPHAN-BE** | Client methods exist (api.ts:2037,2096) but no page calls them. Discover uses /creators/search; the profile page uses /creators/profile/:id |
| 47 | POST /wallet/escrow/refund | **ORPHAN-BE** | EscrowController.java:152-166. No FE caller anywhere. It matters because of finding 1 |

### Brand — deals, chat, contracts, deliverables, disputes, reviews

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Deal list on /brand/deals | **WORKING** | src/components/brand/deals/deal-room-dashboard.tsx:270 -&gt; src/lib/api.ts:2462 -&gt; influora-api/.../web/DealController.java:65 -&gt; service/DealService.java:152, workspace-scoped at :1443 |
| 2 | Deep link /brand/deals/:id and brand-view recording | **WORKING** | deal-room-dashboard.tsx:231,:328 -&gt; api.ts:2468 -&gt; DealController.java:72 -&gt; DealService.java:181-205 |
| 3 | Accept Proposal on /brand/deals | **BROKEN** | deal-room-dashboard.tsx:86-101,:448,:773 -&gt; DealService.java:1131,:1822-1829. Always returns 409 AGREED_RATE_REQUIRED (finding 1) |
| 4 | Counter offer on /brand/deals | **PARTIAL** | deal-room-dashboard.tsx:485-489 sends no Idempotency-Key and no deliverables -&gt; DealService.java:583,:1333-1352 (findings 3, 4) |
| 5 | Reject on /brand/deals | **PARTIAL** | deal-room-dashboard.tsx:516 -&gt; api.ts:2490 -&gt; DealService.java:398-426. Works once and silently no-ops after a revive (finding 2) |
| 6 | "Send Proposal" button in the negotiating state on /brand/deals | **BROKEN** | deal-room-dashboard.tsx:798-803 opens a read-only dialog; footer actions gated on status==='proposed' at :1054 (finding 5) |
| 7 | Next step after accept on /brand/deals ("View Contract") | **PARTIAL** | deal-room-dashboard.tsx:814-818 navigates to /brand/contracts with no ?contract= parameter. Contract generation exists only in src/pages/brand-chat.tsx:1764 |
| 8 | Proposal breakdown and History tab on /brand/deals | **STUB** | deal-room-dashboard.tsx:736-742,:935-941 show 'not available yet' in live mode |
| 9 | Messages tab on /brand/deals (list, send, markRead, SSE) | **WORKING** | deal-room-dashboard.tsx:298,:301,:342,:426 -&gt; api.ts:2607-2686 -&gt; DealController.java:114-173 -&gt; DealService.java:601,:618,:736,:1070 |
| 10 | Message attachments, all three surfaces | **STUB** | deal-room-dashboard.tsx:904-913, brand-chat.tsx:2688-2704, src/pages/brand-messages.tsx:997-1022 are disabled; web/dto/deal/DealDtos.java SendMessageRequest(content,kind) has no attachment field |
| 11 | View Profile from a deal | **WORKING** | deal-room-dashboard.tsx:687 -&gt; api.ts:2178 -&gt; service/CreatorDiscoveryService.java:952-968, which tolerates a user id |
| 12 | /brand/chat deal list and deep links | **PARTIAL** | brand-chat.tsx:217-237 drops DISPUTED and CANCELLED deals; :1030 falls back to the first deal (finding 10) |
| 13 | /brand/chat Send Proposal / Counter wizard | **PARTIAL** | brand-chat.tsx:1609-1639 sends a fresh idempotency key -&gt; DealController.java:104 -&gt; DealService.doCounter:1274. Deliverable type strings never match the enum (finding 6) |
| 14 | /brand/chat Accept on a creator counter | **WORKING** | brand-chat.tsx:1678,:1824-1825 -&gt; DealService.doAccept:1102-1236, error mapping at brand-chat.tsx:640-661 |
| 15 | Brand decline or withdraw of an IN_NEGOTIATION or TERMS_AGREED deal | **PARTIAL** | Backend allows it (domain/entity/Collaboration.java:387-393). No control in brand-chat.tsx; dashboard shows Reject only for INVITED/APPLIED (:773) |
| 16 | Contract generate | **PARTIAL** | brand-chat.tsx:980-992 sends no terms -&gt; api.ts:3223 -&gt; web/ContractController.java:40 -&gt; service/ContractService.java:146-396 (findings 13, 14) |
| 17 | Contract view in the deal room | **WORKING** | src/components/brand/deal-room/deal-contract-tab.tsx:78 -&gt; api.ts:3196 -&gt; ContractController.java:70 -&gt; ContractService.java:1323, scoped by findByIdAndWorkspaceId at :1431 |
| 18 | Brand sign in the deal room | **WORKING** | deal-contract-tab.tsx:173 -&gt; src/lib/contract-generator.ts:244 -&gt; api.ts:3247 -&gt; ContractController.java:80-114 -&gt; ContractService.java:897-956. Role derived server-side; idempotent |
| 19 | Contract PDF download | **WORKING** | deal-contract-tab.tsx:116 and src/components/brand/contracts/contracts-and-deliverables.tsx:934 -&gt; api.ts:3297 -&gt; ContractController.java:158 -&gt; ContractService.java:1410-1423. Depends on R2 at runtime |
| 20 | Fund milestone escrow | **WORKING** | src/components/brand/deal-room/deal-payments-tab.tsx:205 -&gt; src/hooks/useEscrowFund.ts:208 -&gt; src/lib/meera-api.ts:684 -&gt; web/EscrowController.java:68 -&gt; service/EscrowService.java:226-377. Requires OWNER/ADMIN, server-derived amount, duplicate-hold guard at :274-316 |
| 21 | Manual Release | **WORKING** | deal-payments-tab.tsx:110 -&gt; api.ts:4089-4099 -&gt; EscrowController.java:107 -&gt; EscrowService.java:890-984. Row lock, requires FUNDED, idempotent when already RELEASED |
| 22 | Automatic release on approval | **BROKEN** | service/BrandDeliverableService.java:171 passes a milestoneId that is never set -&gt; EscrowService.java:772-773 returns NO_MILESTONE (finding 7) |
| 23 | Deliverables list in the deal room | **WORKING** | brand-chat.tsx:1248 -&gt; api.ts:3375 -&gt; DealController.java:179 -&gt; DealService.java:1093 |
| 24 | View submitted deliverable content | **BROKEN** | src/components/brand/deal-room/deal-deliverables-tab.tsx:60-137 shows title and status only. DeliverableViewer.tsx and src/hooks/brand/useDeliverableDetail.ts:34 are mounted nowhere (finding 8) |
| 25 | Approve deliverable | **PARTIAL** | brand-chat.tsx:1296 and contracts-and-deliverables.tsx:811 -&gt; api.ts:3436 -&gt; web/BrandDeliverableController.java:46 -&gt; BrandDeliverableService.java:109-243. Approval works, but the UI always reports 'payment NOT released' |
| 26 | Request revision | **WORKING** | brand-chat.tsx:1344 -&gt; api.ts:3450 -&gt; BrandDeliverableController.java:53 -&gt; BrandDeliverableService.java:325-377. Feedback required; revision cap enforced |
| 27 | Reject deliverable and safety review | **ORPHAN-BE** | BrandDeliverableController.java:64,:81 and api.ts:3462,:3480 exist. Only caller is src/components/brand/deliverables/DeliverableViewer.tsx:334, which is unmounted |
| 28 | Shipment: mark shipped | **PARTIAL** | brand-chat.tsx:1134,:2151 -&gt; api.ts:3075 -&gt; DealController.java:208 -&gt; service/ShipmentService.java:152. In live mode there is no shipment card and no re-ship control (finding 12) |
| 29 | Open dispute from the deal room | **PARTIAL** | brand-chat.tsx:1190 -&gt; api.ts:6394 -&gt; DealController.java:186 -&gt; service/DisputeService.java:107-148. Works, but the deal then vanishes from the chat list (finding 10) |
| 30 | /brand/disputes list | **PARTIAL** | src/pages/brand-disputes.tsx:52 -&gt; api.ts:6374 -&gt; web/BrandDisputeController.java:40 -&gt; DisputeService.java:483,:533-541. DTO carries no resolution fields (finding 11) |
| 31 | Dispute evidence and respond | **STUB** | Absent on both sides. OpenDisputeRequest(reason) is the only input (web/dto/dispute/DisputeDtos.java) |
| 32 | Leave a review | **PARTIAL** | src/components/shared/collaboration-reviews-panel.tsx:60,:210 -&gt; api.ts:5328 -&gt; web/BrandReviewController.java:36 -&gt; service/ReviewService.java:60. Already-reviewed deals reappear after a reload (:77,:185) |
| 33 | Reviews received and flag | **WORKING** | collaboration-reviews-panel.tsx:159,:102 -&gt; api.ts:5338,:5348 -&gt; BrandReviewController.java:45,:51. In live mode no reviewer or campaign name is shown (api.ts:5280-5282) |
| 34 | /brand/contracts list, detail and payments tab | **WORKING** | contracts-and-deliverables.tsx:729,:768 -&gt; api.ts:3181 -&gt; ContractController.java:48 -&gt; ContractService.java:1342 |
| 35 | /brand/contracts Sign | **PARTIAL** | contracts-and-deliverables.tsx:893,:1464-1470. Contract terms are never shown on this page (finding 15) |
| 36 | /brand/contracts Deliverables and Clauses tabs | **STUB** | contracts-and-deliverables.tsx:475-476 hard-codes [] in live mode; :1393-1397 and :1478-1482 show "isn't available yet" |
| 37 | /brand/messages inbox (list, send, SSE, read) | **PARTIAL** | brand-messages.tsx:394,:430,:456,:488. Unread count is wrong (finding 17) |
| 38 | /brand/messages header actions (call, video, pin, mute, archive) | **STUB** | brand-messages.tsx:690-739, disabled with honest tooltips |
| 39 | Deal-lifecycle notification deep links | **BROKEN** | service/notification/NotificationListener.java:330,:346,:359,:383,:398,:414,:427 -&gt; src/pages/brand-notifications.tsx:85 and src/components/brand/brand-layout.tsx:517 -&gt; no matching route in src/App.tsx (finding 9) |
| 40 | Contract cancel and amend | **ORPHAN-BE** | ContractController.java:125,:144. No client exists in api.ts or meera-api.ts |
| 41 | Escrow refund and payout endpoints | **ORPHAN-BE** | EscrowController.java:152,:168. No frontend caller; the refund case is by design |
| 42 | SSE transport (reconnect, Last-Event-ID, 401 refresh) | **WORKING** | api.ts:2686-2866 -&gt; DealController.java:141-158. Registry is in-memory and single-instance |
| 43 | Razorpay top-up leg, VITE_PAYMENTS_IN_ENABLED flag, and R2 storage at runtime | **NOT-VERIFIABLE-STATICALLY** | api.ts:76,:119-122; ContractService.java:1257,:1417 |

### Brand — wallet, billing, invoices, analytics, Meera

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Wallet summary cards (balance / secured / pending / runway) | **WORKING** | src/pages/brand-wallet.tsx:447 -&gt; src/lib/api.ts:3655 GET /wallet -&gt; WalletController.java:73-82 (requireMember) -&gt; WalletService.java:153 |
| 2 | Add funds - order creation | **WORKING** | brand-wallet.tsx:634-647 -&gt; api.ts:3670 POST /wallet/topup + Idempotency-Key -&gt; WalletController.java:95-110 -&gt; WalletTopUpService.java:98-166 (OWNER/ADMIN, cap, idempotent replay) -&gt; RazorpayClient.java:97-110 (rupees to paise) |
| 3 | Add funds - payment confirmation / credit | **PARTIAL** | Credit only via RazorpayWebhookController.java:190-200 -&gt; WalletTopUpService.java:182; no verify or reconciliation path; UI flips to 'Payment received' after one refetch (brand-wallet.tsx:594-599, :850) |
| 4 | Payment-method selector (UPI/Card/Netbanking) | **STUB** | paymentMethod state only read for styling (brand-wallet.tsx:384, 951-1003); never reaches razorpay.ts:167-181 |
| 5 | Transaction history + search/filter | **PARTIAL** | Only page 1, 20 rows (brand-wallet.tsx:448, api.ts:3730); no pagination UI; WalletService.java:502 supports paging |
| 6 | Transaction export | **STUB** | Permanently disabled button with honest tooltip (brand-wallet.tsx:800-810) |
| 7 | Secured-funds list | **WORKING** | brand-wallet.tsx:464 -&gt; api.ts:3797 GET /wallet/escrow -&gt; EscrowController.java:58-66 |
| 8 | Secure Campaign Funds from wallet | **PARTIAL** | Lists ACTIVE campaigns only (brand-wallet.tsx:488) and excludes those with an active hold (:556-568); ACTIVE requires a FUNDED hold first (CampaignActivationGuard.java:55,71-74) |
| 9 | Release a campaign-level hold from wallet | **WORKING** | brand-wallet.tsx:529-550 -&gt; api.ts releasePayout (POST /wallet/escrow/release + key) -&gt; EscrowController.java:107-136 (XOR target) |
| 10 | Payouts to creators tab | **BROKEN** | FE filters brand transactions for ESCROW_RELEASE (brand-wallet.tsx:723); that type posts clearing -&gt; creator wallet only (LedgerEscrowBackend.java:74,84-96) |
| 11 | Brand refund / withdraw unused balance | **ORPHAN-BE** | POST /wallet/escrow/refund has no FE caller (EscrowController.java:152); POST /wallet/withdraw is creator-only (WalletController.java:116-121) |
| 12 | Runway days | **WORKING** | WalletService.computeRunwayDays (null-safe) -&gt; brand-wallet.tsx:671,697-698 |
| 13 | TDS/GST totals, projected burn, suggested recharge | **STUB** | Hard null in live mode (brand-wallet.tsx:674-677) |
| 14 | Current plan card | **PARTIAL** | Price sent in paise (BillingController.java:223; V55 seed 499900) and rendered as rupees (brand-billing-settings.tsx:377) |
| 15 | Upgrade to Pro (checkout) | **NOT-VERIFIABLE-STATICALLY** | Chain coherent: brand-billing-settings.tsx:259-263 -&gt; api.ts:4375 -&gt; BillingController /checkout (OWNER/ADMIN) -&gt; SubscriptionService.java:308-374 |
| 16 | Cancel subscription | **WORKING** | brand-billing-settings.tsx:276-294 -&gt; api.ts:4389 -&gt; BillingController /cancel -&gt; SubscriptionService.java:381-410 |
| 17 | Past-due / halted subscription state | **BROKEN** | Page never reads subscription.status; PAST_DUE/HALTED falls back to Free (SubscriptionService.java:217-222); cancel control hidden (brand-billing-settings.tsx:321,460) |
| 18 | Usage meters | **PARTIAL** | Tracked/Saved Creators reads UsageMetric.TRACKED_CREATOR (BillingController.java:128) which nothing writes; enforcement counts rows (CreatorDiscoveryService.java:488) |
| 19 | Subscription invoices list + PDF | **PARTIAL** | Amount stored in paise (InvoiceService.java:207), returned raw (BillingController.java:244), rendered as rupees (brand-billing-settings.tsx:683); PDF math correct (InvoiceService.java:252-253); ownership check OK (:99) |
| 20 | Campaign service invoices + PDF | **WORKING** | api.ts:4463-4472 &lt;-&gt; BrandInvoicingController.java:48-63 |
| 21 | Commission invoices + PDF | **PARTIAL** | Second (delta) fee debit gets no invoice: BrandCampaignFeeService.java:303 -&gt; CommissionInvoiceService.java:96-99 returns the existing invoice |
| 22 | Billing role gate | **WORKING** | FE useBrandBillingAccess &lt;-&gt; BillingController requireRole on /checkout and /cancel |
| 23 | Platform-fee disclosure | **PARTIAL** | GET /brand/platform-fee returns global rate + hardcoded '(10%)' copy (BrandPlatformFeeService.java:35-36,53); charge uses Pro plan rate (BrandCampaignFeeService.java:113-127); Free shows a dash (V57 fee_bps NULL; billing page :395) |
| 24 | Publish-fee delta charging (uncommitted) | **WORKING** | Coherent with both callers via CampaignActivationGuard (CampaignService.java:369-371, ConfirmLaunchExecutor.java:344); new query in WalletTransactionRepository (uncommitted diff); guard file is untracked |
| 25 | Secured amount vs budget after an edit | **PARTIAL** | Guard checks only that any FUNDED hold exists (CampaignActivationGuard.java:74); duplicate-scope fund returns the old hold (EscrowService.java:303-316); budget editable while DRAFT/PAUSED |
| 26 | /brand/analytics overview | **BROKEN** | Roster uses creator USER id (brand-analytics.tsx:174-181; DealService.java:2259-2262); backend matches creatorProfileId and needs a workspace-scoped token row that no code creates (MetricsAuthorizationService.java:65-73; MetaTokenStorage.java:95-132,324-327) |
| 27 | /brand/analytics/:creatorId | **BROKEN** | Same cause; header shows the raw id (brand-creator-analytics.tsx:106-110) |
| 28 | Creator-analytics view metering | **PARTIAL** | Quota consumed before authorization (AnalyticsUsageCapInterceptor.java preHandle; PlanGateWebConfig.java:39-40); aggregate view fires one call per roster creator (brand-analytics.tsx:57) |
| 29 | Audience demographics | **ORPHAN-BE** | Endpoint and client exist (AnalyticsController /demographics; api.ts:4965); page hardcodes 'Coming soon' (brand-creator-analytics.tsx:206-213) |
| 30 | Campaign report export CSV/PDF | **WORKING** | brand-campaign-detail.tsx:611-636 -&gt; api.ts:4406 -&gt; ReportExportController (@RequiresPlan EXPORT) -&gt; ReportExportService.java:51-71; 402 maps to an upgrade prompt |
| 31 | TrendSpark nudge | **WORKING** | api.ts:6464-6480 &lt;-&gt; TrendSparkController.java:40-64 |
| 32 | Meera session start + history | **WORKING** | MeeraChatPanel.tsx:568-596 -&gt; meera-api.ts:570,717 -&gt; MeeraController.java:95,166 |
| 33 | Meera send turn + credit charge | **PARTIAL** | Charge commits separately from the rest of the turn (MeeraSessionService.java:344,354-355,388) |
| 34 | Meera SSE stream | **NOT-VERIFIABLE-STATICALLY** | Contract coherent (MeeraChatPanel.tsx:815-823 &lt;-&gt; chat.py:338-372); public chat URL bound only in application-prod.yml:109 |
| 35 | Tool show_creators | **WORKING** | schemas.py:93-104 &lt;-&gt; ShowCreatorsExecutor.java:51-53 &lt;-&gt; MeeraToolDtos.java:34-44 &lt;-&gt; meera-api.ts:198-212 |
| 36 | Tool calculate_budget | **PARTIAL** | Java reads creator_count (CalculateBudgetExecutor.java:104-105) but the schema never offers it (schemas.py:116-132); always 5 |
| 37 | Tool create_campaign | **PARTIAL** | Non-atomic writes (CreateCampaignExecutor.java:170,207-208); handoff copy points to a dead end (MeeraChatPanel.tsx:324-331) |
| 38 | Tool get_campaign_performance | **WORKING** | schemas.py:333-362 &lt;-&gt; MeeraInternalController.java:300-310 &lt;-&gt; MeeraToolDtos.java:145-159 &lt;-&gt; meera-api.ts:409-417 |
| 39 | Tools request_payment / confirm_launch (hire, launch, pay) | **ORPHAN-BE** | Never offered to the model (schemas.py:456-460); scope excludes them (OnBehalfTokenService.java:68,105; OnBehalfAuthResolver requireScope); executors unreachable |
| 40 | analyze_site + profile write-back | **WORKING** | loop.py:395-450 -&gt; MeeraInternalController.java:379-391 |
| 41 | present_options cards + telemetry | **WORKING** | loop.py:354-393; meera-api.ts:895 &lt;-&gt; MeeraInteractionController.java:46-63 |
| 42 | Voice TTS / STT | **NOT-VERIFIABLE-STATICALLY** | Contract coherent: meera-api.ts:777-879 &lt;-&gt; MeeraController.java:237-322 &lt;-&gt; MeeraVoiceAiClient &lt;-&gt; voice.py:384-412; silent fallback by design |
| 43 | Credit paywall 'Fund a campaign' | **BROKEN** | MeeraChatPanel.tsx:1013,1097 -&gt; useMeeraStage.ts:38-45 -&gt; StageFunding.tsx:83-85 permanent loader; unlock only in ConfirmLaunchExecutor.java:386 which is unreachable |
| 44 | Wallet talk (ask Meera about balance) | **STUB** | Wallet keys deny-listed from the prompt (assembler.py:78-79); no wallet tool exists |
| 45 | Stream-error recovery | **PARTIAL** | Empty recovery removes the bubble and shows nothing (MeeraChatPanel.tsx:931-936) |
| 46 | Razorpay webhook: signature, amount cross-check, idempotency | **WORKING** | WebhookSignatureVerifier.java (HMAC, constant-time compare); WalletTopUpService.java:193-227; startup validator rejects the placeholder secret |

### Creator — auth, onboarding, Meta connect, profile, settings, portfolio

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Register (form → OTP gate → POST /auth/creator/register, phone/email 409s, invite token) | **WORKING** | src/pages/creator-register.tsx:141,166-203 → src/lib/api.ts:1134-1153 → influora-api/.../web/AuthController.java:106 → influora-api/.../service/AuthService.java:342-442 |
| 2 | Email OTP send / verify / resend | **PARTIAL** | src/components/shared/email-otp-gate.tsx:159-166 → AuthController.java:91-104 → service/BrandEmailOtpService.java:131-151,214-229 (resend is a no-op; a registered email gets no mail) |
| 3 | Verify-brick guard (unverified at 2nd login) | **WORKING** | influora-api/src/main/resources/application.yml:157,171; deploy compose files default REQUIRE_EMAIL_OTP_BEFORE_REGISTER to true; AuthService.java:401-404 |
| 4 | Login | **WORKING** | src/pages/creator-login.tsx:39-68 → api.ts:1096-1115 → AuthController.java:116 → AuthService.java:445-497 |
| 5 | Remember me | **PARTIAL** | creator-login.tsx:39-40 vs web/dto/auth/LoginRequest.java:34,41 |
| 6 | Unverified-email recovery at login | **WORKING** | creator-login.tsx:70-74,105-115 → BrandEmailOtpService.verifyOtp marks the user verified |
| 7 | Cold-load session restore / route guard | **WORKING** | src/App.tsx:125-142,205-214 → api.ts:558-593 → AuthController.java:131 → AuthService.java:513-617 |
| 8 | Token storage (memory-only in live mode, role-slot guard) | **WORKING** | api.ts:436-507; src/lib/auth-session.ts |
| 9 | Logout (sidebar and settings) | **WORKING** | src/components/creator/creator-layout.tsx:215-239; src/pages/creator-settings.tsx:243-273 → AuthController.java:153 |
| 10 | Forgot password | **PARTIAL** | src/pages/creator-forgot-password.tsx:26 → AuthController.java:163 → AuthService.java:639,839-860; input text is unreadable (lines 45,63) |
| 11 | Reset password (creator on the brand-named page) | **PARTIAL** | src/pages/brand-reset-password.tsx:53,58 → AuthService.java:645-748; always lands on /brand/login |
| 12 | Onboarding step 1: Instagram connect and skip | **WORKING** | src/pages/creator-onboarding.tsx:136-152,403-413 |
| 13 | Onboarding step 1: YouTube | **STUB** | creator-onboarding.tsx:159-164 (toast only) |
| 14 | POST /onboarding/creator/socials | **ORPHAN-BE** | service/CreatorOnboardingService.java:85 (501); no FE caller |
| 15 | Onboarding step 2: save profile | **PARTIAL** | creator-onboarding.tsx:199,223 vs CreatorOnboardingService.java:96-98 |
| 16 | Onboarding complete | **WORKING** | creator-onboarding.tsx:276 → web/CreatorOnboardingController.java /complete |
| 17 | Meta authorize (state, authPath required, redirect-uri matches the FE route) | **WORKING** | api.ts:5433 → web/MetaOAuthController.java:65-112 → integration/meta/oauth/MetaOAuthService.java:106-163 → config/MetaRedirectUri.java; App.tsx:541 |
| 18 | Meta callback, FACEBOOK_LOGIN | **WORKING** | code chain only: src/pages/creator-meta-callback.tsx:199-202 → MetaOAuthController.java:130-164 → service/creatorcopilot/CreatorMetaOAuthService.java:104-159 → MetaOAuthService.java:170-200,339-341 |
| 19 | Meta callback, INSTAGRAM_LOGIN | **NOT-VERIFIABLE-STATICALLY** | CreatorMetaOAuthService.java:189-238; MetaOAuthService.java:237-294. The request shape looks coherent; whether Meta accepts it needs a live check |
| 20 | Graph host selection and query-string handling | **WORKING** | integration/meta/client/MetaGraphApiClient.java:81-82,186-201 |
| 21 | Meta status | **PARTIAL** | service/MetaConnectionService.java:78,145 |
| 22 | Meta disconnect | **WORKING** | src/components/creator/connected-accounts.tsx:64-80 → MetaOAuthController.java:184 → integration/meta/oauth/MetaTokenStorage.java:401-419 |
| 23 | Long-lived token refresh job | **PARTIAL** | job/MetaTokenRefreshService.java:159-201 → MetaTokenStorage.java:292 (no @Transactional) |
| 24 | Connected Accounts card | **PARTIAL** | connected-accounts.tsx:30-34,202-206 |
| 25 | Profile view and edit (rates, niches, languages, username, discoverable) | **WORKING** | src/pages/creator-profile.tsx:297-351 → api.ts:3921 → web/MeCreatorProfileController.java → service/CreatorProfileService.java; the DTOs match (api.ts:3828-3864 vs web/dto/creator/CreatorProfileDtos.java:13-38) |
| 26 | Avatar upload | **WORKING** | creator-profile.tsx:245-246 → web/UploadController.java → UploadService.upload |
| 27 | Sync stats (profile page and editor Sync now) | **PARTIAL** | service/portfolio/PortfolioService.java:402-403 vs integration/meta/client/InstagramInsightsClient.java:45-46 (fails for Instagram-Login tokens) |
| 28 | Settings: change password | **PARTIAL** | creator-settings.tsx:295 → web/AccountController.java:125-130 → AuthService.java:814-818 (kills the caller's own session) |
| 29 | Settings: mobile number (incl. 409) | **WORKING** | creator-settings.tsx:182-215 → CreatorProfileService patch → service/UserPhoneService.java applyPhone |
| 30 | Settings: delete account | **PARTIAL** | creator-settings.tsx:337 → AccountController.java:79-104 (only the User row is anonymised) |
| 31 | Settings: tax identity PAN/GST | **PARTIAL** | src/components/creator/TaxIdentityForm.tsx:72-80 → web/MeTaxIdentityController.java (POST only, no GET) |
| 32 | Settings: KYC submit | **WORKING** | write-only: src/components/creator/KycIdentityForm.tsx:85,102 → CreatorOnboardingController /kyc |
| 33 | Settings: email notification preference | **WORKING** | creator-settings.tsx:119-126,226 → api.ts:4222-4235 → web/NotificationController.java:253,270 |
| 34 | Settings: four category switches and SMS | **STUB** | creator-settings.tsx:480-539 (rendered disabled) |
| 35 | Settings: Help, Support, Terms rows | **WORKING** | creator-settings.tsx:421-441; App.tsx:781,791,898 |
| 36 | Editor: save bio, visibility, custom links, collab display modes | **WORKING** | src/pages/creator-portfolio-editor.tsx:155-181 → PortfolioService.java:292-351 |
| 37 | Editor: cover upload followed by Save | **BROKEN** | creator-portfolio-editor.tsx:165,286-287 vs PortfolioService.java:307,699,795,1445-1475 |
| 38 | Editor: niches input | **BROKEN** | creator-portfolio-editor.tsx:440-448 |
| 39 | Editor: rate card | **PARTIAL** | creator-portfolio-editor.tsx:164,529-531; PortfolioService.java:1105-1131 |
| 40 | Editor: pinned posts (Content portfolio) | **STUB** | creator-portfolio-editor.tsx:498 shows a count only; no FE writer anywhere |
| 41 | Editor: declare platform | **WORKING** | creator-portfolio-editor.tsx:249 → PortfolioService.java:449-508 |
| 42 | Editor: analytics tiles | **PARTIAL** | PortfolioService.java:625-638 (profile clicks are an estimate, link clicks always empty) |
| 43 | Public /:handle page | **PARTIAL** | src/pages/creator-portfolio-public.tsx:156-157,213 → web/PortfolioController.java:39 → PortfolioService.java:248-251,772-811 |
| 44 | Public contact form | **PARTIAL** | creator-portfolio-public.tsx:993 → PortfolioService.java:705-707 (the contactForm flag is never checked) |
| 45 | Public /c/:username/verified | **PARTIAL** | src/pages/creator-verified-metrics.tsx:45,127,140 → service/PublicCreatorService.java:77,89 |
| 46 | In-progress portfolio edits: FE types vs Java records | **WORKING** | api.ts:4684-4698 vs web/dto/portfolio/PortfolioDtos.java:50-55; consumers are null-safe (creator-portfolio-editor.tsx:465-469, creator-portfolio-public.tsx:476-478) |
| 47 | Layout shell: nav links, bell, logout | **WORKING** | creator-layout.tsx:126-162,473; every href has a route in App.tsx:561-701 |
| 48 | IDOR on /me/*, /meta/oauth/* | **WORKING** | the profile is always resolved from the principal (service/CreatorContextService.java; MetaOAuthController.java:200-210); public payloads carry no email, phone or floors |

### Creator — campaigns, applications, deals, chat, contracts, deliverables, disputes, reviews

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Dashboard rollup (wallet, deals, pending) | **PARTIAL** | src/pages/creator-dashboard.tsx:136-160 -&gt; src/lib/api.ts:2462 -&gt; BE/web/DealController.java:65 -&gt; BE/service/DealService.java:152; the unread figure counts the creator's own messages (DealService.java:2074-2080) |
| 2 | Dashboard unsigned-contract tile and deep link | **WORKING** | creator-dashboard.tsx:371,663 -&gt; api.ts:3279 -&gt; BE/web/ContractController.java:60 -&gt; BE/repository/ContractRepository.java:87-93; ?tab=contract consumed at src/pages/creator-chat.tsx:1150 |
| 3 | Browse and filter campaigns | **PARTIAL** | src/pages/creator-campaigns.tsx:82 -&gt; api.ts:5898 -&gt; BE/web/CreatorCampaignController.java:40 -&gt; BE/service/CreatorCampaignService.java:122; no stale-response guard (creator-campaigns.tsx:72-120); niche is a substring search (CreatorCampaignService.java:540) |
| 4 | Campaign detail | **WORKING** | src/pages/creator-campaign-detail.tsx:91 -&gt; api.ts:5911 -&gt; CreatorCampaignController.java:54 -&gt; CreatorCampaignService.java:189,494; request-token guard at :83-107 |
| 5 | Apply to campaign | **WORKING** | creator-campaign-detail.tsx:124 -&gt; api.ts:5917 -&gt; CreatorCampaignController.java:60 -&gt; CreatorCampaignService.java:208-287 |
| 6 | Withdraw application | **BROKEN** | BE path exists (DealService.java:398,525-536); no control in creator-campaign-detail.tsx:356-382 or creator-applications.tsx; chat Decline is gated to INVITED or a proposal card (creator-chat.tsx:1978-1981,2576) |
| 7 | Re-apply after withdraw | **BROKEN** | BE revives the row (CreatorCampaignService.java:231-254); FE hides Apply because applicationStatus='CANCELLED' is truthy (creator-campaign-detail.tsx:187,356; BE/service/CreatorCampaignMapper.java:30,52) |
| 8 | My Applications list and pagination | **WORKING** | src/pages/creator-applications.tsx:47 -&gt; api.ts:5791 -&gt; BE/web/CreatorApplicationController.java:33 -&gt; CreatorApplicationService.java:61-76 |
| 9 | Application history timeline | **WORKING** | api.ts:5816 -&gt; CreatorApplicationController.java:45 -&gt; CreatorApplicationService.java:126-136 (owner-scoped) |
| 10 | Deals tabs, filters, badge counts | **WORKING** | src/pages/creator-deals.tsx:79-89,250,293 -&gt; DealService.java:2337-2408; stage mapper src/lib/deal-stage.ts:113-139 covers all 13 BE states |
| 11 | Deals list Accept (New tab) | **PARTIAL** | creator-deals.tsx:373,589,690; an INVITED row never has a rate (BE/domain/entity/Collaboration.java:104-120), so Accept is always disabled on this tab, with a hint at :717 |
| 12 | Deals list Decline | **PARTIAL** | creator-deals.tsx:413 -&gt; api.ts:2490 -&gt; DealController.java:94 -&gt; DealService.java:398-427; see finding 1 |
| 13 | Deals list Counter CTA | **PARTIAL** | creator-deals.tsx:406 sends &action=counter; creator-chat.tsx:715-717 reads only deal and tab |
| 14 | Hype invites one-tap accept | **STUB** | creator-deals.tsx:240 hard-codes hypeInvites = []; handleHypeAccept at :354 is unreachable |
| 15 | Deal room list, selection, stale-link guard | **WORKING** | creator-chat.tsx:731,812-825,1872-1902 |
| 16 | Messages list and send | **PARTIAL** | creator-chat.tsx:887,1409 -&gt; api.ts:2607,2613 -&gt; DealController.java:114,160 -&gt; DealService.java:601,618; duplicate-on-send race (finding 9); only the latest 50 are loadable (finding 20) |
| 17 | Message SSE stream, reconnect, replay | **WORKING** | creator-chat.tsx:987-1031 -&gt; api.ts:2686-2866 -&gt; DealController.java:141-158 -&gt; DealService.java:736 |
| 18 | Mark read and unread counts | **PARTIAL** | creator-chat.tsx:943-948 -&gt; DealController.java:169 -&gt; DealService.java:1070; own messages are counted as unread (finding 10) |
| 19 | Accept proposal (room) | **PARTIAL** | creator-chat.tsx:1430 -&gt; DealService.java:346-366,1102-1236; finding 1 |
| 20 | Decline proposal (room) | **PARTIAL** | creator-chat.tsx:1470 -&gt; DealService.java:398-554; finding 1 |
| 21 | Counter proposal | **PARTIAL** | creator-chat.tsx:1512-1518 (fresh idempotency key) -&gt; DealController.java:104 -&gt; DealService.java:557,1274; own counter is mis-rendered (finding 4); form shows fabricated data (finding 17) |
| 22 | Bare-invite response card | **WORKING** | creator-chat.tsx:1977-1998,2293 |
| 23 | Contract view and milestones | **PARTIAL** | creator-chat.tsx:1106,2946-2962 -&gt; api.ts:3198 -&gt; ContractController.java:70 -&gt; BE/service/ContractService.java:1357 (owner-scoped); terms are never rendered and the PDF is unavailable before signing (finding 14) |
| 24 | Creator e-sign | **PARTIAL** | src/components/creator/deal-room/creator-deal-contract-tab.tsx:153 -&gt; src/lib/contract-generator.ts:244 -&gt; api.ts:3249 -&gt; ContractController.java:80-92 -&gt; ContractService.java:966,1083; the FE chain is correct but BE has no CANCELLED guard (finding 5) |
| 25 | Contract PDF download | **PARTIAL** | creator-deal-contract-tab.tsx:126 -&gt; ContractController.java:158 -&gt; ContractService.java:1411; 404s until both parties have signed |
| 26 | Creator cancels an unsigned contract | **ORPHAN-BE** | ContractController.java:125-135 and ContractService.java:1040; no client in src/lib/api.ts or src/lib/meera-api.ts |
| 27 | Payments panel (creator) | **BROKEN** | creator-chat.tsx:3065-3071 passes neither escrowFunded nor milestones -&gt; src/components/brand/deal-room/deal-payments-tab.tsx:84-87,163-184 (finding 3) |
| 28 | Deliverable slots panel | **PARTIAL** | the real list is fetched (creator-chat.tsx:1132 -&gt; api.ts:6094 -&gt; BE/web/CreatorDeliverableController.java:48), but the panel renders synthetic rows (creator-chat.tsx:2009-2019,3000-3005) |
| 29 | Deliverable upload and submit | **PARTIAL** | creator-chat.tsx:1553,1568 -&gt; api.ts:6140,3419 -&gt; CreatorDeliverableController.java:72,94 -&gt; BE/service/CreatorDeliverableService.java:220,328; the entry point only shows in stage in_progress (creator-chat.tsx:2156); PDF is accepted client-side only |
| 30 | Resubmit after revision | **WORKING** | creator-chat.tsx:3011-3042,1609-1665 -&gt; getStatus (CreatorDeliverableController.java:87) -&gt; upload + submit; RESUBMITTED set at CreatorDeliverableService.java:352-355 |
| 31 | Rejected deliverable surfaced to creator | **BROKEN** | BE/service/BrandDeliverableService.java:385-410 emits no event; FE filters only REVISION_REQUESTED and APPROVED+ (creator-chat.tsx:3013,3049); BE/service/CollaborationLifecycleService.java:109-112,282-284 then marks the deal COMPLETED |
| 32 | Mark posted, verify, report metrics | **WORKING** | (wiring only) src/hooks/creator/useCreatorDeliverableLifecycle.ts:93,119,141 -&gt; api.ts:6186,6206,6228 -&gt; CreatorDeliverableController.java:135,121,103 |
| 33 | Proof screenshot upload | **BROKEN** | src/components/creator/deal-room/deliverable-lifecycle-panel.tsx:141-143 discards the returned key, and :131 sends reportMetrics({metrics}) only; BE uploadProof writes no DB row (CreatorDeliverableService.java:290-325) |
| 34 | Shipping address and confirm receipt | **WORKING** | creator-chat.tsx:1305,1350 -&gt; api.ts:3052,3064 -&gt; DealController.java:199,217 -&gt; BE/service/ShipmentService.java:360-364 (owner-scoped) |
| 35 | Disputes list and eligible deals | **WORKING** | src/pages/creator-disputes.tsx:64,78 -&gt; api.ts:6339,6351 -&gt; BE/web/CreatorDisputeController.java:33,45 -&gt; BE/service/DisputeService.java:495-497 |
| 36 | Raise dispute (page and room) | **PARTIAL** | creator-disputes.tsx:106, creator-chat.tsx:1387 -&gt; api.ts:6357 -&gt; DealController.java:186 -&gt; DisputeService.java:101-145; finding 12 |
| 37 | Dispute respond / evidence | **STUB** | no BE endpoint (CreatorDisputeController.java exposes two GETs; AdminDisputeController.java:73 has resolve only) and no FE control |
| 38 | Rate brand after a completed deal | **PARTIAL** | src/components/shared/collaboration-reviews-panel.tsx:60,210 -&gt; api.ts:5303 -&gt; BE/web/CreatorReviewController.java:36; the reviewed state lives only in this session (:77,184) |
| 39 | Reviews received and flag | **WORKING** | collaboration-reviews-panel.tsx:159,102 -&gt; api.ts:5310,5320 -&gt; CreatorReviewController.java:45,51 -&gt; BE/service/ReviewService.java:67-70 |
| 40 | Notifications list, mark read, read-all | **WORKING** | src/pages/creator-notifications.tsx:38 -&gt; src/hooks/useNotifications.ts:229,262 -&gt; api.ts:4192-4205 -&gt; BE/web/NotificationController.java:82,103,133 (userId-scoped at :109) |
| 41 | Notification deep links | **BROKEN** | creator-notifications.tsx:82-84 navigates to the raw link; BE/service/notification/NotificationListener.java:201,216,234,252,265,283,523 emit routes missing from src/App.tsx, which falls to the catch-all at App.tsx:919 |
| 42 | IDOR checks: deals, contracts, deliverables, shipments, disputes, notifications, review flag | **WORKING** | DealService.java:1414-1436; ContractService.java:1438-1443; CreatorDeliverableService.java:802-820; ShipmentService.java:360-373; NotificationController.java:109; ReviewService.java:69 |
| 43 | Live mode isolated from the localStorage/session demo stores | **WORKING** | creator-chat.tsx:1912-1927 (live branches never read creator-contract-store); src/lib/creator-deal-messages.ts is used only on the non-live branch at :1416,1796 |
| 44 | Notification isRead JSON field binding | **NOT-VERIFIABLE-STATICALLY** | BE record component boolean isRead (BE/web/dto/notification/NotificationDtos.java:25) against FE wire key isRead (api.ts:4163); the emitted key depends on the Jackson version |

### Creator — wallet, payouts, coupons, affiliate, analytics, copilot

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Wallet tiles (available / secured / pending) | **WORKING** | src/pages/creator-wallet.tsx:445-456 -&gt; src/lib/api.ts:3655 GET /wallet -&gt; WalletController.java:73-78 -&gt; WalletService.java:209-226 |
| 2 | Platform fee label | **WORKING** | src/pages/creator-wallet.tsx:429 -&gt; src/lib/api.ts:3683 -&gt; CreatorPlatformFeeController.java -&gt; CreatorPlatformFeeService.java (same config row PlatformFeeService.deductAtRelease uses) |
| 3 | Withdraw (POST /wallet/withdraw) | **BROKEN** | src/pages/creator-wallet.tsx:577-596 -&gt; src/lib/api.ts:3715 -&gt; WalletController.java:116 -&gt; WalletService.java:304, 385-401 -&gt; WalletLedgerService.java:159/178 vs V8__wallet_transactions.sql:15 |
| 4 | Payout methods: list / add / set primary | **PARTIAL** | src/pages/creator-wallet.tsx:622-657 -&gt; src/lib/api.ts:3760-3788 -&gt; WalletController.java:191-229 -&gt; CreatorBankAccountService.java (no IFSC/VPA/type validation, no delete) |
| 5 | KYC gate on withdrawal | **PARTIAL** | WalletService.java:354-371 accepts PENDING; CreatorProfile.java:404-410 only ever sets PENDING; form is mounted only in src/pages/creator-settings.tsx:662 |
| 6 | Payouts tab (GET /wallet/payouts) | **PARTIAL** | src/pages/creator-wallet.tsx:519-541, 863-864 -&gt; WalletService.java:452-489 (reference is manual:&lt;ULID&gt;, amount is gross) |
| 7 | Payout status advance (RazorpayX webhook) | **PARTIAL** | RazorpayWebhookController.java:110 -&gt; PayoutReconciliationService.java:117-158 (payout.failed not routed; no polling) |
| 8 | History tab + period filter | **WORKING** | src/pages/creator-wallet.tsx:482-511 -&gt; src/lib/api.ts:3730 -&gt; WalletController.java:141 -&gt; WalletService.java:502-571 |
| 9 | Invoices tab (list + PDF, both kinds) | **WORKING** | src/pages/creator-wallet.tsx:270-372 -&gt; src/lib/api.ts:4488-4511 -&gt; CreatorInvoicingController.java -&gt; CampaignServiceInvoiceService.java:585-592, CommissionInvoiceService.java:252-259 (scoped to owner) |
| 10 | Tax Docs tab | **STUB** | src/pages/creator-wallet.tsx:971-995 (no endpoint; states TDS (1%) is deducted but no code deducts TDS) |
| 11 | Coupons list / copy / tracking link | **WORKING** | src/pages/creator-coupons.tsx -&gt; src/lib/api.ts:5667 -&gt; CreatorCouponController.java -&gt; CreatorCouponService.java:61-127; discount type percentage/fixed consistent |
| 12 | Affiliate earnings page (list, summary, load more) | **WORKING** | src/components/creator/AffiliateEarningsView.tsx -&gt; src/hooks/creator/useAffiliateEarnings.ts -&gt; src/lib/api.ts:5682 -&gt; AffiliateEarningsService.java:182-294 (DTOs match 1:1) |
| 13 | Redemption webhook -&gt; commission row | **WORKING** | ConversionWebhookController.java:198-248 (HMAC check, key namespaced per workspace) -&gt; RedemptionWriter.doRedeem -&gt; AffiliateEarningRecordingListener -&gt; AffiliateEarningsService.java:338-521; hourly backfill job AffiliateEarningReconciliationJob.java:82 |
| 14 | Shopify / Woo webhook -&gt; redemption | **PARTIAL** | ShopifyWebhookController.java and WooCommerceWebhookController.java: signature and idempotency fine, but no payment-status check and no cancel/refund handling |
| 15 | Link-only conversion -&gt; commission | **PARTIAL** | ConversionWebhookController.java:266-283 -&gt; ConversionTrackingService.java:162-178 creates no earning (recordEarning has only two callers) |
| 16 | Per-campaign commission rate | **ORPHAN-BE** | Campaign.java:113 is read at AffiliateEarningsService.java:426-431 but never written; always a flat 10% (:102) |
| 17 | Monthly settlement -&gt; wallet credit | **BROKEN** | AffiliateSettlementJob.java:131 -&gt; AffiliateSettlementWriter.java:131-138 vs V8__wallet_transactions.sql:6-7,12 and AuthService.java:406-412 |
| 18 | Analytics metric tiles + date range | **PARTIAL** | src/pages/creator-analytics.tsx:208-235 -&gt; src/lib/api.ts:4977 -&gt; CreatorAnalyticsController.java -&gt; AnalyticsService.java:125-133, 144-154 |
| 19 | Analytics scores / demographics / media | **WORKING** | hooks -&gt; src/lib/api.ts:4985-5003 -&gt; CreatorAnalyticsController.java -&gt; CreatorAnalyticsService.java (principal-scoped) |
| 20 | Copilot consent gate + feature-disabled state | **WORKING** | src/pages/creator-copilot.tsx:69-115 -&gt; src/lib/api.ts:6643-6661 -&gt; CreatorAgentController.java; SecurityConfig.java:296-297 restricts /creator/** to CREATOR; CreatorMeeraController.java requireConsent; influora-ai/app/routes/chat.py:577-584 |
| 21 | Copilot chat (send, stream, cap / consent errors) | **PARTIAL** | src/components/creator/MeeraCopilotChat.tsx:193-313 -&gt; src/lib/meera-api.ts:565-617 -&gt; CreatorMeeraController.java -&gt; MeeraSessionService.java -&gt; influora-ai/app/routes/chat.py:548-614 -&gt; influora-ai/app/prompt/assembler.py:670-757; defects at MeeraCopilotChat.tsx:211 and meera-api.ts:514-541 |
| 22 | Copilot voice (speak / transcribe) | **PARTIAL** | src/lib/meera-api.ts:777-880 -&gt; CreatorMeeraController.java voice methods -&gt; MeeraVoiceAiClient.java:203 -&gt; influora-ai/app/routes/voice.py:189-193 |
| 23 | Daily suggestion | **PARTIAL** | src/hooks/useDailySuggestion.ts:154-186 -&gt; src/lib/api.ts:6521 -&gt; CreatorCopilotController.java -&gt; CreatorNudgeService.java getSuggestion; jobs gated at CreatorThemeTaggingJob.java:72 and CreatorCaptionSyncJob.java:91,159; application.yml:534 |
| 24 | Meera creator context accuracy | **PARTIAL** | MeeraContextService.java:255, 387-396 -&gt; influora-ai/app/prompt/assembler.py:595-597, 647-651 |
| 25 | Consent withdrawal (DELETE /creator/agent-preferences/consent) | **ORPHAN-BE** | CreatorAgentController.java withdrawConsent; no client in src/lib/api.ts:6641-6692 or src/lib/meera-api.ts |
| 26 | Conversation list / export / delete (DPDP) | **WORKING** | src/components/creator/MeeraSettingsSection.tsx:252-291 -&gt; src/lib/api.ts:6665-6692 -&gt; CreatorAgentController.java conversation endpoints |
| 27 | Creator AI metering | **WORKING** | MeeraSessionService.java:378-388 (creator turn never touches brand credits); influora-ai/app/routes/chat.py:592-608 applies the monthly USD cap; src/hooks/useMeeraStream.ts:282-295 reads error.code |

### Admin — auth, dashboard, users, campaigns, moderation, disputes, creator connections

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Admin login (email + password + TOTP) | **WORKING** | admin-login.tsx:53-61 -&gt; api-contracts.ts:185 -&gt; AdminAuthController.java:69 -&gt; AdminAuthService.java:108-175; AdminAuthDtos.java:45 `token` matches the FE read |
| 2 | Per-account login/MFA lockout | **BROKEN** | AdminAuthService.java:107,183-199,207-219: counter saved, then a RuntimeException rolls it back |
| 3 | Per-IP rate limit on admin login | **BROKEN** | AuthRateLimitFilter.java:343,475,508: no bucket matches /admin/auth/* |
| 4 | Access-token refresh (proactive and on 401) | **WORKING** | admin-session.ts:135-196; api-contracts.ts:115,136-143; AdminAuthController.java:83-99; AdminAuthService.java:225-263 (rotation) |
| 5 | Logout | **PARTIAL** | useAdminAuth.ts:249-262 and api-contracts.ts:104 (no refresh retry) vs AdminAuthController.java:101-107: an expired token gets 401, so the refresh cookie and token rows survive |
| 6 | /auth/me session resolve | **WORKING** | useAdminAuth.ts:210 -&gt; AdminAuthController.java:109 -&gt; AdminAuthService.java:272 |
| 7 | MFA enrol (setup/verify) | **BROKEN** | api-contracts.ts:218-225 has no UI callers; AdminAuthService.java:159-168 vs 277-306 is an enrolment deadlock |
| 8 | MFA reset for another admin | **ORPHAN-BE** | AdminAuthController.java:135; AdminAuthService.java:341-354; no FE; bricks the target |
| 9 | /admin/** blocked for brand/creator JWT | **WORKING** | SecurityConfig.java:276-277; AuthPrincipal.java:90; AdminContextService.java:69-73 |
| 10 | Admin tier RBAC enforced server-side | **WORKING** | AdminContextService.java:104-120; every service in the slice calls it, except creator-agent |
| 11 | FE route guard and FE RBAC | **PARTIAL** | App.tsx:222-225 checks token presence only; the hasPermission matrix (useAdminAuth.ts:99-142) has zero consumers |
| 12 | Admin role changes / admin user management | **STUB** | no controller, no UI; Permission.ADMIN_MANAGE is declared at useAdminAuth.ts:73 only |
| 13 | Dashboard: CEO Pulse KPIs | **WORKING** | PulseDashboard.tsx:79-92 -&gt; usePulseData.ts:42 -&gt; AdminDashboardController.java:45 -&gt; AdminDashboardService.java:93-104; DTO matches admin.types.ts:111-127 (stats-cache internals not opened) |
| 14 | Dashboard: Operations row | **PARTIAL** | AdminDashboardService.java:119-122 hardcodes campaignsAtRisk to 0, rendered at PulseDashboard.tsx:97 |
| 15 | Brand list, pagination, filters | **PARTIAL** | useBrandList.ts:70-77 -&gt; AdminBrandController.java:77 -&gt; AdminBrandService.java:165-277; the PENDING filter and email search mismatch (see findings) |
| 16 | Brand detail | **WORKING** | useBrandDetail.ts:56 -&gt; AdminBrandController.java:89 -&gt; AdminBrandService.java:145; DTO matches admin.types.ts |
| 17 | Brand edit | **WORKING** | BrandProfile.tsx:290-306 -&gt; PUT /brands/{id} -&gt; AdminBrandService.java:388-445 (allow-list, audit) |
| 18 | Brand KYC approve/reject | **WORKING** | BrandProfile.tsx:207-237 -&gt; AdminBrandService.java:281-320 (role, audit); status read by CampaignValidator.java:64 |
| 19 | Brand suspend/reinstate | **WORKING** | BrandProfile.tsx:239-269 -&gt; AdminBrandService.java:323-375; enforced at BrandContextService.java:104 and AuthService.java:327 |
| 20 | Brand budget override | **WORKING** | BrandProfile.tsx:317-334 (confirm, reason &gt;=10 chars) -&gt; AdminBrandService.java:553-688 (SUPER_ADMIN, committed-spend floor, audit) |
| 21 | Brand Meta pixel | **WORKING** | api-contracts.ts:326 -&gt; AdminBrandController PATCH -&gt; AdminBrandService.java:467-495 |
| 22 | Brand reanalyze | **ORPHAN-BE** | AdminBrandController.java:183; no FE caller |
| 23 | Creator list / pending-applications endpoint | **PARTIAL** | AdminCreatorService.java:143-203; search covers displayName only (CreatorProfileSpecs.java:38-43); page 0 returns 500 (line 167) |
| 24 | Creator detail | **WORKING** | useCreatorDetail -&gt; AdminCreatorService.java:205-211, 525-570 |
| 25 | Creator edit | **WORKING** | CreatorProfile.tsx:313-320 -&gt; PUT /creators/{id} |
| 26 | Creator application approve/reject and Pending tab | **BROKEN** | CreatorProfile.java:215 (always APPROVED); nothing writes PENDING; UI gated on PENDING (CreatorProfile.tsx:704) |
| 27 | Creator suspend/reinstate | **WORKING** | AdminCreatorService.java suspend -&gt; enforced at CreatorContextService.java:65 |
| 28 | Creator tier adjust | **PARTIAL** | CreatorProfile.tsx:334-337 -&gt; PUT /tier; the override is read only by MeeraContextService.java:258 |
| 29 | Force Instagram re-auth | **PARTIAL** | CreatorProfile.tsx:806-811 gate is inverted and has no confirm; AdminCreatorService.java:558 (see findings) |
| 30 | Campaign list | **PARTIAL** | api-contracts.ts:428 vs AdminCampaignController.java:58; type hardcoded at AdminCampaignService.java:477 |
| 31 | Campaign detail drawer | **PARTIAL** | AdminCampaignService.java:369,391 (type STANDARD, timelineStatus ON_TRACK hardcoded) |
| 32 | Campaign approve/reject/pause/feature | **STUB** | AdminCampaignController.java:40-100 has GETs only; CampaignTable.tsx has no mutation |
| 33 | Moderation: content flag queue | **BROKEN** | useFlagQueue.ts:81 reads .data.data; AdminModerationDtos.java:41 sends items |
| 34 | Moderation: flag actions | **BROKEN** | unreachable because the queue is empty; REMOVE writes a dead status (AdminModerationService.java:195-205); fake success at FlagQueue.tsx:297-300 |
| 35 | Moderation: approvals queue list | **WORKING** | useApprovalQueue.ts:47-57 -&gt; ApprovalWorkflowController.java:51 -&gt; ApprovalWorkflowService.java:92-107 |
| 36 | Moderation: process approval | **PARTIAL** | BRAND_KYC works (ApprovalWorkflowService.java:127-140); CONTENT_MODERATION returns 501 (line 172) and the failure is silent |
| 37 | Suspension appeal review | **STUB** | api-contracts.ts:764-767 unavailable() |
| 38 | Dispute list, filters, pagination | **WORKING** | useDisputeList -&gt; AdminDisputeController.java:41 -&gt; DisputeService.java:382-...; shape data/total/totalPages matches |
| 39 | Dispute view (reason, amount, evidence) | **STUB** | no GET /admin/disputes/{id}; DisputeDtos.java:75-82 carries no reason or amount |
| 40 | Dispute resolve: release / refund | **WORKING** | DisputeList.tsx:206 -&gt; DisputeService.java:195-350 -&gt; EscrowService.java:1289-1350; audit, optimistic lock, settlement guard |
| 41 | Dispute resolve: split | **PARTIAL** | EscrowService.java:1390-1453 vs CampaignServiceInvoiceService.java:158 (see findings) |
| 42 | Creator-connection request list | **WORKING** | CreatorConnectionsPage.tsx:270,343 -&gt; AdminCreatorConnectionController.java:36 -&gt; service list; items/total match |
| 43 | Mark contacted / decline | **WORKING** | CreatorConnectionsPage.tsx:285-293 -&gt; AdminCreatorConnectionService.java markContacted/decline (role, audit) |
| 44 | Invite (emails the creator) | **PARTIAL** | AdminCreatorConnectionService.java:293-299: email failure is logged only, response is still 200 |
| 45 | Brand emailed when the creator joins | **WORKING** | RegistrationService.java:106 and MetaTokenStorage.java:366 -&gt; ExternalCreatorLinkService finishLinking -&gt; NotificationListener.java:763-812 |
| 46 | External creators list | **WORKING** | CreatorConnectionsPage.tsx:280,644 -&gt; AdminExternalCreatorController list |
| 47 | Import handles | **WORKING** | CreatorConnectionsPage.tsx:318 -&gt; AdminExternalCreatorController POST /import (role checked, max 50) |
| 48 | Delete external creator | **ORPHAN-BE** | AdminExternalCreatorController.java:60; no FE |
| 49 | Get connection by id | **ORPHAN-BE** | AdminCreatorConnectionController.java:46; creatorConnectionsApi.get has no caller |
| 50 | Creator-agent baselines page | **WORKING** | creatorAgentApi -&gt; AdminCreatorAgentController.java:39; snake_case via @JsonProperty; hardcoded sample honestly labelled (CreatorAgentBaselinesPage.tsx:149-151); no role check |
| 51 | Creator-agent monthly cap override | **ORPHAN-BE** | AdminCreatorAgentController.java:52-58; no FE, no role/MFA/audit |
| 52 | Prod same-origin for the refresh cookie | **NOT-VERIFIABLE-STATICALLY** | admin-session.ts:36 (relative base) with .env.production:38 (same host); depends on the live reverse proxy |
| 53 | Server-side audit of admin logins | **BROKEN** | AdminAuthService.java:88-105 injects no audit service; the only login trail is client-posted (useAdminAuth.ts:215) |

### Admin — finance, revenue, billing, escrow, support, email, festival, audit/error logs

| # | Feature | Status | Evidence (file:line chain) |
|---|---|---|---|
| 1 | Admin token attach and refresh | **WORKING** | FE/services/api-contracts.ts:106-164 -&gt; FE/services/admin-session.ts:102-196 (localStorage admin_token, proactive + 401 refresh) |
| 2 | /admin/** server guard (brand/creator JWT blocked) | **WORKING** | BE/config/SecurityConfig.java:263-277 hasRole(ADMIN); BE/security/AuthPrincipal.java:41-43; every in-slice service calls requireRoleWithMfaSatisfied (BE/service/admin/AdminContextService.java:104-108) |
| 3 | Fee config view/update with optimistic lock | **PARTIAL** | FE/components/finance/FeeControlPanel.tsx:282-338 -&gt; api-contracts.ts:593-635 PUT /finance/fee-config -&gt; BE/web/PlatformFeeAdminController.java:76-89 -&gt; BE/service/admin/PlatformFeeAdminService.java:89-132; wire works, confirm-dialog copy wrong |
| 4 | Creator fee % read at escrow release | **WORKING** | BE/service/PlatformFeeService.java:42-43,79 &lt;- BE/service/escrow/LedgerEscrowBackend.java:77 |
| 5 | Brand fee % read at publish | **WORKING** | BE/service/BrandCampaignFeeService.java:113-118,224 &lt;- BE/service/CampaignActivationGuard.java:58; PRO plan overrides global rate (:121-125) |
| 6 | Platform absorbs Razorpay cost toggle | **STUB** | Stored at PlatformFeeAdminService.java:124-125; isRazorpayAbsorbedByPlatform has no reader in src/main (only PlatformFeeConfig.java:122 and audit allowlist AdminAuditLogService.java:252) |
| 7 | Fee change history | **WORKING** | PlatformFeeAdminService.java:141-146 reads audit rows for entityId 'default' |
| 8 | Revenue KPIs and trend | **PARTIAL** | FE/hooks/useFinanceConsole.ts:125 -&gt; BE/web/AdminDashboardController.java:62 -&gt; BE/service/admin/AdminRevenueService.java:105-162; GMV and fees skewed |
| 9 | Revenue custom range | **WORKING** | FE/components/finance/RevenueRangePanel.tsx:96 -&gt; BE/web/AdminRevenueController.java:36-43 -&gt; AdminRevenueService.java:66-91; yyyy-MM-dd both sides (same data caveats as trend) |
| 10 | Escrow summary | **PARTIAL** | BE/service/admin/AdminFinanceService.java:274-287; count rendered as rupees at FE/components/finance/FinanceConsole.tsx:364 |
| 11 | Flagged (FROZEN) escrow list | **WORKING** | BE/web/AdminEscrowController.java:41 -&gt; AdminFinanceService.java:296-353 |
| 12 | Manual escrow release/hold/refund | **STUB** | api-contracts.ts:653-666 unavailable(); no UI callers, no backend endpoint |
| 13 | Reconciliation view | **PARTIAL** | AdminFinanceService.java:370-495; manual payouts stay PENDING forever (:399, :518-534) |
| 14 | Payout retry | **BROKEN** | FE/components/finance/ReconciliationPanel.tsx:264 (button only on MISMATCH) vs AdminFinanceService.java:488-495 and BE/service/PayoutReconciliationService.java:325 (only failure-state payouts retryable, which classify MATCHED/PENDING) |
| 15 | Record manual payout | **PARTIAL** | FE/components/finance/ManualPayoutPanel.tsx:66-107 -&gt; BE/web/AdminFinanceController.java:91 -&gt; AdminFinanceService.java:149-266; ledger debit idempotent, but unaudited and TDS rule questionable |
| 16 | TDS 26Q report / reconciliation resolve | **STUB** | api-contracts.ts:544-550 unavailable() |
| 17 | Billing subscriptions list and MRR/ARR/churn | **WORKING** | BE/web/AdminBillingController.java:104-117 -&gt; BE/service/admin/AdminBillingService.java:86-209; price_inr is paise (V54__subscription_billing.sql:16), divided at :195-196 |
| 18 | Grant comp / override plan | **PARTIAL** | FE/components/billing/BillingConsole.tsx:197-215 -&gt; AdminBillingService.java:246-295; workspace picker too narrow (BillingConsole.tsx:461-468) |
| 19 | Comp expiry | **BROKEN** | BE/service/billing/SubscriptionService.java:458-482 vs BE/job/SubscriptionRenewalResetJob.java:116-140 (expired comp auto-renewed; compExpiresAt has no reader) |
| 20 | Support list/detail/reply/escalate | **PARTIAL** | BE/web/AdminSupportController.java:57-112; FE/hooks/useTicketList.ts:86-87 never pages |
| 21 | Support assign | **BROKEN** | api-contracts.ts:712-716 sends {adminId} vs BE/web/dto/admin/AdminSupportDtos.java:71 AssignRequest(String assignedTo); unassigns with fake success |
| 22 | Support resolve/close | **ORPHAN-BE** | PUT at AdminSupportController.java:87-94; no control in FE/components/support/TicketList.tsx |
| 23 | Support stats | **WORKING** | BE/web/AdminSupportStatsController.java:35 -&gt; BE/service/admin/AdminSupportService.java:300-320 |
| 24 | Brand/creator ticket creation reaching the admin list | **BROKEN** | BE/web/SupportController.java:47 has no caller anywhere in SRC outside src/admin |
| 25 | Email queue list/stats/templates/retry | **PARTIAL** | BE/service/admin/AdminEmailService.java:59-141; 'Retrying' filter returns 400 (FE/pages/EmailQueuePage.tsx:300 vs AdminEmailService.java:161-172) |
| 26 | Outbox actually sends | **NOT-VERIFIABLE-STATICALLY** | BE/service/notification/EmailWorker.java:197 @Scheduled; BE/config/TaskSchedulerConfig.java:70-75 on by default; JavaMailSender bound at application.yml:23-27 ${SMTP_HOST:}; env dependent |
| 27 | Compose preview/send broadcast | **WORKING** | FE/pages/EmailComposePage.tsx:234-290 -&gt; BE/web/AdminEmailController.java:56-66 -&gt; BE/service/admin/AdminCustomEmailService.java:212,311-372 (SUPER_ADMIN+MFA, cap, send lock + min interval, 409 on count mismatch); iframe sandbox at EmailComposePage.tsx:546-549 |
| 28 | Cancel a broadcast | **ORPHAN-BE** | AdminEmailController.java:74-79; no FE caller |
| 29 | send-bulk | **STUB** | AdminEmailController.java:116-128 returns 501; no UI caller |
| 30 | Festival enquiry list / status update | **PARTIAL** | BE/service/admin/AdminFestivalEnquiryService.java:74-138; status change never audited (entityType FESTIVAL_ENQUIRY not allowlisted) |
| 31 | Festival public form reaching the admin list | **WORKING** | SRC/lib/api.ts:6843 -&gt; BE/web/FestivalEnquiryController.java:62-79 -&gt; same repository as admin list |
| 32 | Provision a sponsor | **WORKING** | api-contracts.ts:972-1013 -&gt; BE/web/AdminFestivalEnquiryController.java:91-97 |
| 33 | link-existing / existing-account | **ORPHAN-BE** | AdminFestivalEnquiryController.java:108-130; no FE caller |
| 34 | Festival coupons | **WORKING** | api-contracts.ts:1134-1193 -&gt; BE/web/AdminCampaignCouponController.java:69-91; rate-limited at BE/security/AuthRateLimitFilter.java:508 |
| 35 | Festival metrics | **WORKING** | FE/pages/FestivalMetricsPage.tsx:74-76 -&gt; BE/service/admin/AdminFestivalMetricsService.java:46-61; producer SRC/pages/festival-box-edition.tsx:163 |
| 36 | Audit log list and filters | **PARTIAL** | BE/web/AuditLogController.java:70-82; date filter always 400 (FE/pages/AuditLogPage.tsx:196-214 vs AdminAuditLogService.java:537-548) |
| 37 | Audit-log write coverage | **PARTIAL** | BE/service/admin/AdminAuditLogService.java:97-166; several writers silently rejected by allowlists |
| 38 | Error log list/stats/resolve | **WORKING** | BE/web/AdminErrorLogController.java:37-59 -&gt; BE/service/admin/AdminErrorLogService.java:47-94; writers GlobalExceptionHandler.java:213, NotificationListener.java:736 |
| 39 | FE ErrorBoundary crashes reaching ErrorLogPage | **BROKEN** | BE/web/ClientErrorController.java:134-141 only logs; never calls ErrorLogService |
| 40 | ClientErrorController abuse limits | **WORKING** | 16 KB cap ClientErrorController.java:70,152-169; truncation/redaction :123-129,:247-258; rate-limit bucket AuthRateLimitFilter.java:406-407,520 |
| 41 | Platform-stat backfill | **ORPHAN-BE** | BE/web/AdminPlatformStatBackfillController.java:36; guarded at BE/service/admin/AdminPlatformStatBackfillService.java:75; no UI |
| 42 | Role-aware admin navigation | **PARTIAL** | FE/components/AdminLayout.tsx:64-79 has no role filter |
