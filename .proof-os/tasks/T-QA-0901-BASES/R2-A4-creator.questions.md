# Swapnil -> Priya · Round 2 · Area 4 of 5 · CREATOR BASE · 100 questions

Scope: everything a CREATOR user does — onboarding and profile, portfolio, platform
connections and metrics sync, campaign browsing and applications, invitations received,
negotiation and counter-offers, deliverable submission and revisions, post verification,
earnings, wallet and withdrawals, bank and tax identity, affiliate coupons and
commissions, reviews, disputes, creator analytics, and the creator copilot.

Do not ask whether an endpoint merely exists. Ask whether it works end to end: does a
control's value ever reach the API, does a field the server reads ever get sent, does a
field the server returns ever get read, is a stored limit actually enforced, can every
declared status be reached, does a success toast correspond to a real write, is any value
fabricated client-side when the server sent none, is any error swallowed into a false
empty state, and does money recorded as a status ever get posted to a ledger.

Answer from the actual code. Cite `path/to/file.ext:LINE` and quote the exact span.
Where the wiring breaks, say so plainly and cite the exact line.

## Onboarding — socials, profile, KYC, payout
1. `CreatorOnboardingController.connectSocial` accepts a `CreatorSocialRequest` — does creator-onboarding.tsx send a platform handle for every network the wizard lets the creator enter, or only the ones the client happens to validate?
2. `CreatorOnboardingService.saveProfile` persists a `CreatorProfileRequest` — which fields does the onboarding form collect that the request body never carries, and are they silently lost?
3. Does `CreatorOnboardingController.complete` verify server-side that `socials`, `profile`, `kyc`, and `payout` were all actually saved, or does it flip a completion flag regardless of what preceded it?
4. If a creator calls `/onboarding/creator/complete` having skipped `/onboarding/creator/kyc`, what does the server do — reject, or mark onboarding COMPLETE with no KYC on file?
5. Does `CreatorKycRequest` get parsed and stored fields validated (PAN/Aadhaar format), or does `submitKyc` accept and persist whatever string the client sends?
6. Does `CreatorPayoutRequest`/`savePayout` write to the same `CreatorBankAccount` table `WalletController.addPayoutMethod` writes to, or a separate row the wallet screen never reads?
7. If a creator abandons the onboarding wizard mid-step and returns later, is the last completed step read back from the server, or does the wizard restart from step one and silently overwrite what was saved?
8. Does the onboarding wizard's step indicator reflect a server-computed progress value, or a client-only counter that can drift from what is actually persisted?
9. What does creator-onboarding.tsx render when `saveProfile` or `submitKyc` returns a 4xx validation error — is it mapped to the offending field, or does the wizard advance as if it succeeded?
10. Is there a creator onboarding field the server validates as required that the onboarding form never actually collects, leaving `complete` permanently unreachable for some creators?

## Profile — MeCreatorProfileController and CreatorController
11. `MeCreatorProfileController`'s `PatchMapping` — which fields does creator-settings.tsx send in its patch body that the DTO silently drops before it reaches the service?
12. Does the profile GET response include every field the settings form pre-fills, or does the form fabricate defaults for fields the server never returned?
13. `CreatorController.search` and `.featured` — do their query/response DTOs match what creator-discovery-style pages actually render, or are there filters the UI offers that the backend search never applies?
14. `CreatorController.invite` at `/{creatorId}/invite` — does any brand-side UI actually call this path, or is it a caller-less endpoint?
15. `CreatorController.save` (`/{creatorId}/save`) — is the "saved" state read back anywhere the creator or brand can see it, or does the button toggle with no visible persisted effect?
16. Does `/{username}/similar` return creators the client actually renders distinctly from `/featured`, or do both surfaces silently show the same fabricated/mock list on an empty result?

## Portfolio — page, patch, sync, cover
17. `PortfolioPatchRequest` fields versus creator-portfolio-editor.tsx's form state — which editable fields in the editor never make it into the PATCH body?
18. `PortfolioController.syncPlatforms` — does it re-pull real metrics from each connected platform integration, or does it just recompute derived fields from already-stored numbers with nothing actually re-fetched?
19. After `/me/portfolio/sync` returns, does creator-portfolio-editor.tsx refetch `/me/portfolio` to show the synced numbers, or does it keep rendering the pre-sync state until a manual reload?
20. `uploadCover` returns a `CoverUploadResponse` URL — is that URL persisted onto the portfolio record before the response is returned, or can a returned URL 404 if the write happens out of order?
21. Does `getPublic(username)` return the same field set `getMine` returns, or are there fields visible on the owner's edit screen that silently never reach the public portfolio page?
22. `recordPublicView` is wrapped in try/catch specifically so a failed view write can't 500 the page — does `PortfolioService#analytics` (`/me/portfolio/analytics`) actually reflect those recorded views, or can the counter silently stop incrementing while the page keeps rendering fine?
23. `PortfolioContactRequest`/`contact` — is the submitted message actually delivered anywhere (email, inbox row, notification), or does the endpoint return 200 having written nothing a creator will ever see?
24. Is there a cap on portfolio contact submissions per visitor that's enforced server-side, or can the public contact form be spammed with no rate limit at all?

## Platform connections and metrics sync
25. Which platforms does creator-settings-connected-accounts.test.tsx exercise that have no corresponding backend integration under `integration/meta` or elsewhere — i.e., a "Connect" button wired to nothing real?
26. When a creator disconnects a platform, is the stored access token actually revoked/deleted server-side, or does the UI just hide the card while the token and cached metrics remain live in the database?
27. Is there a background job (see `job/` and `MetricsPollingJob.java`) that refreshes creator platform metrics on a schedule, and does its failure for one creator silently skip them forever with no retry or alert?
28. If Meta/Instagram token refresh fails, does the creator ever see a "reconnect required" state, or does the UI keep showing stale follower/engagement numbers as if they were current?
29. Do the metrics shown on `creator-analytics.tsx` come from the same verified numbers `CreatorDeliverableController.verify` computes, or from an independently cached value that can diverge from what verification just proved?
30. Is the "connected since" / "last synced" timestamp shown in the UI backed by a real server field, or is it computed/faked client-side from whatever data happens to be present?

## Campaign browsing and applications
31. `CreatorCampaignController.list` — which filter/sort controls does creator-campaigns.tsx render whose selected value is never included in the request query params?
32. `CreatorCampaignController.apply` (`/{campaignId}/apply`) — does the request body carry every field the application form collects (pitch, rate, availability), or are some silently dropped before the POST?
33. After a successful apply, does the campaign card's state update to "applied" from the server response, or does the client optimistically flip it with no confirmation the write actually took?
34. `CreatorApplicationController.list` and `/{dealId}/history` — does creator-applications.tsx render every status the backend can return, or are there statuses (e.g. withdrawn, expired) the switch/case silently falls through and renders as nothing?
35. Can a creator apply twice to the same campaign, and if the server rejects the duplicate, does the UI surface that as an error or does the button just silently do nothing on the second click?
36. Does the campaign detail page (`creator-campaign-detail.tsx`) show budget/reward figures pulled from the same response the apply POST validates against, or can the displayed number and the enforced number diverge?
37. Is there a caller for every `CreatorCampaignController` endpoint, and is there a route in `creator-campaigns.tsx`/`creator-campaign-detail.tsx` that calls an endpoint that doesn't exist under this controller?

## Invitations received
38. Where in the creator app does an invitation (brand-initiated, via `CreatorController.invite` or `DealController.create`) actually surface to the invited creator — is there a distinct "invitations" list, or do invites only ever appear if the creator happens to open `creator-deals.tsx`?
39. Does the invited creator ever see who invited them and on what terms before a `Deal` row exists, or does the first visible artifact already assume acceptance is imminent?
40. If a creator ignores an invitation past some expiry, is there a status transition to EXPIRED that any code path actually triggers, or does the row sit indefinitely reachable for accept/reject regardless of age?

## Negotiation and counter-offers
41. `DealController.counter` — is there any server-enforced maximum number of counter rounds, or can `counter` be called an unbounded number of times against the same deal (confirm against `DealService.counter`, lines around the counter-cap comments)?
42. The code comment in `DealService` says a brand counter publishes no notification event ("no equivalent notification event exists for a brand counter") — does the creator's `creator-chat.tsx` poll or otherwise discover a brand's counter-offer any other way, or can it sit unseen until a manual refresh?
43. `DealService#counter`'s amount validation is described as "deliberately narrower" than `validateProposalAmount` — what specific bound does the narrower check skip, and can a creator counter to a price the original proposal validation would have rejected?
44. When a counter supersedes the prior proposal (`settleLatestProposal(..., "countered")`), is the previous proposal's card actually removed/disabled in creator-chat.tsx, or can a stale "Accept" button on the superseded offer still be clicked?
45. Does accepting a counter-offer (`DealController.accept`) require the Idempotency-Key header, and if a client retries an accept without one after a timeout, can the same deal be accepted twice with two downstream effects?
46. `DealController.reject` takes an optional `RejectRequest` body — is a rejection reason actually captured and shown to the other party, or does the UI collect a reason that never leaves the browser?
47. Do deliverables carried forward from a superseded proposal (per the "carry forward deliverables ... when the counter does not revise them" comment) retain their original due dates, or can a stale deadline survive a price-only counter and go unenforced?
48. Is deal-room role authorization for `accept`/`counter`/`reject` scoped to a creator's own membership the same way `sendMessage`/`listMessages` are, or is there a narrower/looser check on one of the three that the others don't share?
49. `streamMessages` uses SSE with `Last-Event-ID` replay — if a creator's browser reconnects after the `EMITTER_TIMEOUT_MS` window, are missed messages actually redelivered, or silently lost with the UI showing no gap indicator?
50. Does `markRead` get called automatically when a creator opens a deal thread, or does the unread badge in `creator-layout.tsx` require an action the UI never actually triggers?

## Deliverable submission and revisions
51. `CreatorDeliverableController.upload` accepts `files`, `thumbnail`, `caption`, `hashtags`, `creatorNotes` — does the deliverable submission UI collect and send all five, or are some (e.g. `hashtags`) rendered as an input with no value ever reaching the multipart request?
52. After `upload` succeeds, does `submit` (`/{deliverableId}/submit`) require the uploaded content to exist server-side, or can a creator submit for review with nothing actually uploaded?
53. Is there a declared revision-request status the brand can set that no creator-facing code path lets the creator act on — i.e., a "changes requested" state that's stored and displayed but has no resubmission flow wired to it?
54. `DeliverableStatusResponse` — does `creator-deliverables`-equivalent UI (via `creator-deals.tsx`/deal room) render every status value the enum defines, or does an unhandled status fall through to a blank/default card?
55. `reportMetrics` (`/{deliverableId}/metrics`) — are the numbers a creator self-reports here ever cross-checked against `verify`'s Meta-sourced numbers, or does a self-reported inflated number stand unchallenged if verification never runs?
56. `markPosted` — does its request body's post URL get validated as belonging to the platform/account the deal actually requires, or is any URL string accepted and stored as proof?
57. `verify` is described as running "the same verification the 6h batch job runs, on request" — does a failed on-demand verify actually surface the specific failure reason to the creator, or just a generic "verification failed" with the real cause only in server logs?
58. The verify endpoint mentions "whether a manual fallback is permitted (only when Meta genuinely failed for a connected account)" — is that permission flag actually read and enforced by the UI before allowing manual proof upload, or is the manual-proof control always shown regardless?
59. `listBulk` (`/creator/deliverables/bulk`) powers the dashboard rollup — if one `collaboration_id` in the batch errors server-side, does the whole bulk call fail for every deal, or does the dashboard silently show zero pending deliverables for the creator with no error surfaced?
60. Is there a client-side file-size/type check before `upload` that the server also enforces, or can a creator's browser block an oversized file while a direct API call would have been silently accepted (or vice versa — server rejects what the UI already let through)?

## Post verification
61. Does `CreatorDeliverableService#verifyNow` write its verified metrics to the same fields the brand's deliverable/analytics views read, or to a creator-only copy that a brand's screen never sees updated?
62. If Meta verification returns a lower view/engagement count than the creator's self-reported `reportMetrics` value, which number does the payout/commission calculation actually use?
63. Is there a maximum number of on-demand `verify` calls a creator can make per deliverable, and if so, is it enforced server-side or only suggested by a disabled button the API doesn't actually gate?
64. Does a deliverable ever reach a terminal VERIFIED state through any path other than `verify` or the batch job — e.g., can a brand or admin force-verify, and if so does that path apply the identical checks?

## Earnings, wallet, and withdrawals
65. `WalletController.withdraw` takes only `amount` — how does `requestCreatorWithdrawal` know which bank account (of possibly several `CreatorBankAccount` rows) to pay out to, and can a creator withdraw before ever adding a payout method?
66. `MIN_CREATOR_WITHDRAWAL`/`MAX_CREATOR_WITHDRAWAL` are enforced in `WalletService` — does creator-wallet.tsx read and display these same bounds, or can the withdraw form submit an amount the UI never warned was out of range?
67. Does `withdraw` require an `Idempotency-Key` the way `topup` does (compare the `@RequestHeader` `required` flag on both), and per creator-wallet-withdraw-idempotency.test.tsx, what actually happens if the header is omitted and the request is retried after a client timeout?
68. `GET /wallet/payouts` is explicitly creator-only and 403s a brand principal — does creator-wallet-payouts.test.tsx (or the equivalent page) distinguish that 403 from "no payouts yet," or does the UI render an empty state for both?
69. Does the balance shown on `creator-wallet.tsx` (`/wallet/balance`) ever include escrowed-but-unreleased funds as spendable, or is the distinction between available and pending balance enforced server-side and reflected correctly in the UI?
70. When a withdrawal is requested, is a `Payout` row created synchronously before the response returns, or can the wallet UI show a success toast for a withdrawal that never actually got a corresponding ledger/payout entry?
71. `WalletTransactionType.WITHDRAWAL` entries in `/wallet/transactions` — do they reconcile 1:1 with rows in `/wallet/payouts`, or can a transaction exist with no matching payout (money debited from the ledger with nothing actually disbursed)?
72. Does creator-wallet.money-buckets.test.tsx's bucket logic (available/pending/lifetime) match how `WalletService` actually computes those figures, or does the frontend derive its own bucket totals from raw transactions that can drift from the server's number?
73. If a withdrawal fails at the payment-processor step after the wallet has already been debited, is there a compensating credit-back path, or does the creator's balance stay silently short with only a support ticket as recourse?
74. Does the `period` filter on `/wallet/transactions` ("this-month"/"last-month"/"3-months"/"all") match exactly what the History tab's dropdown sends, or can the UI send a value `resolvePeriodRange` doesn't recognize and silently get back the unfiltered "all" result?

## Bank and tax identity
75. `WalletController.addPayoutMethod` — are `accountOrVpa`/`ifsc` actually encrypted before persistence as the comment claims, and does `BankAccountResponse` ever leak the plaintext value back to the client on any response path (including error responses)?
76. `setPrimaryPayoutMethod` — does the server enforce exactly one primary account, or can two methods both be marked primary through a race between two rapid requests?
77. If a creator deletes/replaces their only bank account, does the wallet UI block a subsequent withdraw attempt, or does `withdraw` succeed server-side with no destination account to actually pay out to?
78. `MeTaxIdentityController.submit` — there is no corresponding GET; how does creator-settings.tsx know whether a tax identity was already submitted and what value to show, or does the form always render blank even after a successful prior submission?
79. Is GSTIN/PAN format validated server-side in `TaxIdentitySubmitRequest`, client-side in the settings form, both, or neither — and if only client-side, can a malformed value be submitted directly against the API?
80. Does a submitted tax identity actually get attached to the invoices `CreatorInvoicingController` generates, or can a commission/campaign invoice PDF be generated with tax fields blank despite a valid submission on file?

## Affiliate coupons and commissions
81. `CreatorCouponController.list` is explicitly read-only, with creation living on `CampaignTrackingController` — does creator-coupons.tsx ever call a creation/edit endpoint that doesn't exist on this read-only controller?
82. `CreatorCouponListItem` — does it include a usage count or redemption total, and if so, is that number live from the tracking service or a stale snapshot taken at coupon-creation time?
83. `CreatorAffiliateEarningController.list`'s SETTLED-vs-pending summary — does creator-affiliate-earnings.tsx sum the individual rows itself, or trust the server summary, and can the two disagree when a page boundary splits a settlement?
84. With `page`/`limit` both optional and defaulting to "page 0, the service's own default size" — does creator-affiliate-earnings.tsx send 1-indexed or 0-indexed page numbers, and could that off-by-one silently duplicate or skip a page of earnings in the UI?
85. Is there a cap on coupon discount or commission rate that's stored on the campaign/coupon record but never checked when `AffiliateEarningsService` computes a creator's commission row?
86. When a coupon is deactivated by the brand mid-campaign, do already-accrued but unsettled affiliate earnings for that coupon still get created, or does deactivation silently orphan pending commission that was already promised?

## Reviews
87. `CreatorReviewController.list` (`/received`) — does `creator-received-reviews.tsx` render a review whose rating is present but whose text body is null, or does a missing text field break the render entirely?
88. `POST /creator/reviews` — can a creator review a brand for a deal that never reached a completed/eligible state, or does the server enforce the same completed-deal precondition the UI's "leave a review" button visibility implies?
89. `/{reviewId}/flag` — once a creator flags a review, does anything actually happen server-side (a moderation queue row, a status change), or does the endpoint 200 with no observable effect and the flagged review stays displayed unchanged forever?
90. Is there a one-review-per-deal constraint enforced server-side, and if a creator's client retries a slow POST, can two review rows be created for the same deal?

## Disputes
91. `CreatorDisputeController.list` and `/eligible-deals` — does creator-disputes.tsx only let a creator open a dispute against deals actually present in `/eligible-deals`, or can the "open dispute" form be reached for a deal the eligibility endpoint would have excluded?
92. `DealController.openDispute` (`POST /deal/{dealId}/disputes`) — per creator-chat.dispute-entry.test.tsx, does the entry point in the chat UI send the same `dealId` scoping `CreatorDisputeController.list` uses to show a creator only their own disputes, or could a mismatch expose/hide the wrong set?
93. Once a dispute is opened, does the escrow hold on that deal's funds actually change state (per `EscrowService`), or does opening a dispute have zero effect on money movement while the UI implies funds are now frozen?
94. Is there a dispute status that `AdminDisputeController` can set that `CreatorDisputeController.list` never renders a label for, leaving the creator's dispute list showing a stale or blank status after admin action?
95. When a dispute resolves in the creator's favor, is a corresponding wallet/ledger entry created automatically, or does resolution only update the dispute row with the actual payout requiring a separate manual step nothing in the UI prompts for?

## Creator analytics
96. `CreatorAnalyticsController.metrics`/`.scores`/`.demographics`/`.media` — does creator-analytics.tsx call all four, and if `.demographics` or `.media` fails independently, does the page show a partial dashboard with a clear per-section error, or does one failed call blank the entire page?
97. Are the `.scores` values (quality/performance score) computed from the same verified deliverable data `CreatorDeliverableService#verifyNow` produces, or from a separately maintained score that can go stale relative to what's actually been verified?

## Creator copilot
98. `CreatorCopilotController.suggestion/today` — is "today" computed server-side per creator timezone, or does a creator in a different timezone than the server ever get shown yesterday's or tomorrow's suggestion mislabeled as today's?
99. `.../{id}/dismiss` and `.../{id}/acted` — are these mutually exclusive server-side (can a suggestion be both dismissed and marked acted), and does creator-copilot.tsx prevent firing both against the same id, or can a double-click send both and leave the row in an inconsistent state?
100. Does an "acted" suggestion actually trigger the action it recommended (e.g. navigate to and pre-fill a real flow), or does clicking "act on this" only record the acted-on event with no functional follow-through?
