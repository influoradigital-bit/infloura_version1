## 🚨 CURRENT WAVE — QA-BASES-0902 (audit findings, 2 of 5 bases) — 2026-09-02

**Task:** `.proof-os/tasks/T-QA-0901-BASES/` · **Ledger:** F-0399–F-0467 · **Origin:** Swapnil→Priya code-cited Q&A, 400 questions across the features and user bases.

**Ownership is not a judgement call** — every row below was assigned by matching the ledger `where` path against the jurisdiction globs in `registry.json`. 59/59 matched exactly one owner, no overlaps. **Priority (P0/P1/P2) is a judgement** made in the dispatching session, not an oracle result.

| Owner | P0 | P1 | P2 | Total |
|---|---|---|---|---|
| **vikram** | 5 | 5 | 16 | 26 |
| **ananya** | 5 | 7 | 13 | 25 |
| **dev** | 0 | 3 | 3 | 6 |
| **arjun** | 0 | 0 | 2 | 2 |
| | **10** | **15** | **34** | **59** |


### Vikram — 26 findings

| Pri | ID | Class | Where | Symptom |
|---|---|---|---|---|
| P0 | F-0399 | unenforced-limit | `DealService.java` | Campaign budget is checked per-offer only, never summed across the campaign, so N creators can each be offered the full budgetMax and the campaign can be c… |
| P0 | F-0402 | money-path-dead-end | `CreatorAffiliateEarningService.java` | Affiliate earning settlement flips status to SETTLED but never posts to the wallet ledger, so affiliate earnings never become withdrawable money for the cr… |
| P0 | F-0406 | silent-partial-success | `EscrowService.java` | Deliverable APPROVED does not imply paid: release succeeds-with-warning on eight held branches, so a brand sees an approved deliverable while no money move… |
| P0 | F-0415 | compliance-gap | `EscrowService.java` | No TDS computation exists in EscrowService, LedgerEscrowBackend, PlatformFeeService, PayoutService or WalletService, and there is no TDS rate constant or c… |
| P0 | F-0444 | unreachable-endpoint | `NotificationController.java` | Email unsubscribe is unreachable: POST /notifications/unsubscribe and GET /notifications/unsubscribe-link have no frontend caller, so a recipient has no wa… |
| P1 | F-0403 | missing-feature | `Contract.java` | Contract.setStatus has zero call sites in main, so no contract can ever reach CANCELLED; a contract signed by one party only is permanently frozen and post… |
| P1 | F-0404 | machine-bound-config | `application.yml` | influora.shopify binds only token-encryption-key, so no env var can ever supply apiKey/secret/redirectUri; the HMAC verifier is fail-closed and therefore r… |
| P1 | F-0413 | missing-feature | `Contract.java` | Contract.expirationDate exists but nothing ever populates it, and there is no scheduled job touching collaborations, so an unanswered offer stays INVITED o… |
| P1 | F-0414 | missing-feature | `ContractController.java` | ContractController exposes only generate, list, get, sign and pdf-download-url; there is no PUT, PATCH or amend route at any stage, so a signed contract ca… |
| P1 | F-0443 | unreachable-endpoint | `WorkspaceMemberController.java` | Workspace team management is backend-complete and has no UI at all: accept invite, remove member, list invites, revoke invite and switch workspace are five… |
| P2 | F-0400 | unenforced-limit | `CampaignService.java` | maxCollaborators is persisted and echoed back to the client but read by no enforcement path, so the cap is decorative |
| P2 | F-0401 | missing-state-machine | `CampaignService.java` | applyPatch accepts whatever status string the client sends; there is no legal-transition check, so a campaign can jump to any status including backwards |
| P2 | F-0412 | dead-control | `CreatorCampaignService.java` | Creator-side campaign filters niche and platform are applied in memory after the page has already been fetched, so they prune only the current page rather … |
| P2 | F-0416 | unreachable-endpoint | `EscrowController.java` | POST /wallet/escrow/refund is real, authorised and returns funds to the brand wallet, but no frontend anywhere calls it, so no brand can ever trigger a ref… |
| P2 | F-0417 | unenforced-limit | `DeliverableService.java` | requestRevision increments revisionCount unconditionally and gates only on canReview, so a brand can demand revisions without limit and a creator has no ce… |
| P2 | F-0418 | unenforced-limit | `DeliverableService.java` | The deliverables deadline column is returned on the creator DTO but no scheduled job, auto-fail, penalty or status transition ever reads getDeadline to act… |
| P2 | F-0420 | hardcoded-neutral-value | `CreatorScoreMath.java` | Roughly 15 percent of the quality composite is a hardcoded neutral 50 rather than a measured signal, so scores differentiate less than the weighting implie… |
| P2 | F-0421 | unreachable-feature | `AnalyticsService.java` | ROI is computed in exactly one place, the Meera tool executor for campaign performance, and on no brand analytics endpoint, so ROI is reachable only by ask… |
| P2 | F-0422 | incomplete-revocation | `MetaConnectionService.java` | POST /meta/oauth/disconnect flips a local revoked flag but issues no Graph API DELETE, so the user's grant survives on Meta's side and Influora keeps appea… |
| P2 | F-0433 | advertised-feature-absent | `DealController.java` | Dispute evidence does not exist anywhere: OpenDisputeRequest is a single reason field, the service builds the dispute from that reason alone, and the entit… |
| P2 | F-0445 | unreachable-endpoint | `EscrowController.java` | POST /wallet/escrow/payout has no frontend caller, so the direct payout route is unreachable from the product and money can only move by the release path. |
| P2 | F-0446 | unreachable-endpoint | `CreatorController.java` | GET /creators/search, the endpoint that computes discovery facets, has no consumer anywhere in src while the discover page renders hardcoded city and langu… |
| P2 | F-0447 | unreachable-endpoint | `ContractController.java` | GET /contracts/unsigned has no frontend caller, and a stale comment in the client claims the endpoint does not exist, so a creator has no pending-signature… |
| P2 | F-0448 | unreachable-endpoint | `UserController.java` | GET and PATCH /users/me have no frontend caller; the client reads and writes profile through the separate /me/creator-profile routes, leaving the canonical… |
| P2 | F-0449 | unreachable-endpoint | `DeliverableMetricController.java` | PUT /deliverables/id/metrics has no frontend caller; the UI posts to the different creator route POST /creator/deliverables/id/metrics, so this one is orph… |
| P2 | F-0450 | unreachable-endpoint | `CreatorOnboardingController.java` | POST /onboarding/creator/payout has no frontend caller; the client comment records saveCreatorPayout as removed, so creator payout details cannot be set du… |

### Ananya — 25 findings

| Pri | ID | Class | Where | Symptom |
|---|---|---|---|---|
| P0 | F-0459 | storage-split-breaks-guard | `App.tsx` | Unchecking remember-me routes creator_token into sessionStorage, but CreatorProtectedRoute reads localStorage only, so a successful login redirects straigh… |
| P0 | F-0460 | incomplete-logout | `brand-layout.tsx` | Brand sidebar logout removes the token and navigates without calling the logout endpoint, so refresh tokens stay live in the database and the HttpOnly cook… |
| P0 | F-0461 | mock-in-production-path | `upload.ts` | uploadToR2 is a mock that sleeps and returns a fabricated r2.influora.com URL. Brand onboarding persists that string into workspaces.logo_url, so every bra… |
| P0 | F-0462 | full-replace-patch-clears-fields | `brand-settings.tsx` | The workspace PATCH is full-replace but the save handler sends only name, email, phone and websiteUrl, so every save silently wipes industry, companySize, … |
| P0 | F-0465 | token-in-url | `websocket.ts` | The admin websocket puts the bearer token in a query string, where it is exposed to proxy logs, server access logs and browser history. |
| P1 | F-0419 | mocked-surface | `brand-analytics.tsx` | Brand analytics is largely mocked unless VITE_API_MODE=live, and even in live mode there is no brand-wide aggregate endpoint, so the page picks one creator… |
| P1 | F-0437 | fabricated-data-in-ui | `deal-payments-tab.tsx` | When the server sends no milestones the payments tab replaces the empty list with a fabricated schedule derived from the deal value and maps over it uncond… |
| P1 | F-0438 | no-session-recovery | `api.ts` | On a mid-session 401 the client refreshes once, retries once, then clears the token and returns the original 401. Route guards read the token only during r… |
| P1 | F-0439 | no-request-timeout | `api.ts` | The main request path passes no signal and constructs no AbortController; the only AbortController in the module belongs to the deal SSE stream. A hung con… |
| P1 | F-0441 | mock-data-in-live-mode | `brand-creator-analytics.tsx` | The page imports demo creator fixtures and has no live-mode check anywhere in the file, while the app-wide demo banner correctly returns null in live mode,… |
| P1 | F-0463 | fabricated-data-in-ui | `brand-register.tsx` | Registration splits the email local part into firstName and lastName, so sales@acme.com becomes Sales User, is greeted that way on the welcome screen and d… |
| P1 | F-0466 | validation-errors-unmappable | `api.ts` | ApiError drops the server's field and fields members, so no server-side validation error can be mapped to the form field that caused it anywhere in the app… |
| P2 | F-0405 | dead-control | `creator-discovery.tsx` | Four of eight brand discovery filters (language, engagement rate, verified-only, sort order) never leave the browser; they re-filter only the 20 rows alrea… |
| P2 | F-0408 | contract-drift | `api.ts` | Server accepts sortBy with engagement/rate/price_low/price_high/rating/relevance but creatorSearchQuery never sends it; separately the server treats 'relev… |
| P2 | F-0409 | contract-drift | `creator-discovery.tsx` | Server rateOverlap deliberately keeps unpriced creators by ORing an isNull check, but the client price filter drops them, so the two layers disagree on the… |
| P2 | F-0410 | empty-state-misleads | `creator-discovery.tsx` | The 'Showing N creators' count is the length of the client-filtered current page, not the server total, so a brand reads a filtered 20-row page as the size… |
| P2 | F-0411 | dead-control | `creator-discovery.tsx` | Discovery facets (cities, languages) are module-level hardcoded arrays while the backend buildAvailableFacets endpoint GET /creators/search has no consumer… |
| P2 | F-0431 | contract-drift | `api.ts` | CreatorController.search reads sixteen params; creatorSearchQuery emits exactly ten. The six never sent are categories, languages, minEngagementRate, maxEn… |
| P2 | F-0432 | dropped-field-at-call-site | `brand-chat.tsx` | CounterRequest carries usageRights and DealService persists it when non-blank, but of the four screens that counter, only brand chat maps a real value; the… |
| P2 | F-0434 | dropped-field-at-call-site | `creator-portfolio-editor.tsx` | The portfolio PATCH DTO exposes twelve optional fields but the editor always sends the same hardcoded six regardless of what the user changed, so six field… |
| P2 | F-0435 | dto-drift | `api.ts` | Two discovery drifts: the portfolio item type declares a metrics object the server record does not have and the mapper hard-nulls description; and the mapp… |
| P2 | F-0436 | empty-state-misleads | `useNotifications.ts` | The server sends an authoritative unread total on the response envelope and the api wrapper returns it, but the hook destructures only items and recomputes… |
| P2 | F-0440 | optimistic-update-not-rolled-back | `deal-room` | The deal room accept handler dismisses the proposal dialog before issuing the request and never reopens it, so on failure the dialog is gone and the user h… |
| P2 | F-0442 | dead-protocol-support | `api.ts` | The deal message stream reconnects with backoff but carries no replay: the client never parses the SSE id field and never sends Last-Event-ID, so the serve… |
| P2 | F-0464 | dto-drift | `api.ts` | engagementRate is typed as a non-null number on the client but the column is nullable and is never initialised at profile creation, so a creator who has ne… |

### Dev — 6 findings

| Pri | ID | Class | Where | Symptom |
|---|---|---|---|---|
| P1 | F-0426 | stale-runtime-copy | `citations.py` | In-project citations gate is a superseded stub that never verifies a quotation and exits 0 on a document with zero citations, so agents running it get a me… |
| P1 | F-0428 | stale-runtime-copy | `.proof-os/gates/` | Every gate shared with the plugin is stale, not just citations.py: deck, frontmatter, graph_source, meta_length, registry_render, version_assert, e2e.sh, f… |
| P1 | F-0429 | silent-oracle | `.proof-os/gates/` | The in-project gates directory was missing _oracles.py, _rc.py and _rc.sh entirely. Canonical gates import _oracles and crash without it, and every gate wr… |
| P2 | F-0407 | false-red-tool-error | `endpoint_reachability.py` | norm() rewrites a leading ${API_BASE_URL} interpolation to '*', so every frontend call written as fetch(`${API_BASE_URL}/path`) normalises to '*/path' and … |
| P2 | F-0423 | false-red-tool-error | `endpoint_reachability.py` | Four further call shapes were invisible to the FE scanner, each reporting live endpoints as unreachable: requestOrNull absent from the helper list; a gener… |
| P2 | F-0430 | report-mislabels-finding | `endpoint_reachability.py` | norm rewrites the literal segment /me to the wildcard /*, so a principal-scoped route prints identically to a path-parameter route. GET and PATCH /users/me… |

### Arjun — 2 findings

| Pri | ID | Class | Where | Symptom |
|---|---|---|---|---|
| P2 | F-0427 | false-green-gate-blind-spot | `R2-A1-features.answers.md` | 226 of 595 quotation checks fail across 70 of 100 answers. Cause is format not fabrication: the gate QUOTE regex treats backticked spans as claimed verbati… |
| P2 | F-0467 | concurrent-write-collision | `R2-A2-user.answers.md` | A concurrent session edited BrandContextService.java between the read and the gate run, inserting a suspension check that pushed the cited line from 74 to … |

**Close rule:** a ledger row closes ONLY via `promote.py <F-id> <gate-path> --who <name>`. A fix with no gate leaves the row open, because the class will recur.

**Blocked classes** (`promote.py --recurrence`): unreachable-endpoint ×9, unenforced-limit ×4, missing-feature ×3, stale-runtime-copy ×3 — plus 3 more at ×2. `endpoint_reachability.py` already catches all 9 unreachable-endpoint rows and has never been promoted against the class.

**Coverage:** features and user bases only. Brand, creator and admin bases have not been asked a single question — this list is not the whole defect set.

---
