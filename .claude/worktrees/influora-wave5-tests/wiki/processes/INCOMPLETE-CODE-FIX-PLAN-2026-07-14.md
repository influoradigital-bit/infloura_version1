# Incomplete-Code Fix Plan — 2026-07-14

> **Author:** Arjun (Engineering Lead), from Priya's audit
> **Trigger:** Swapnil / Priya — "plan this properly"
> **Scope:** Structured 3-wave plan to address the ~55 incomplete-code findings from `wiki/tech/INCOMPLETE-CODE-AUDIT-2026-07-14.md`

---

## Executive summary

**Don't fix all 55 at once.** This is a 3-wave plan, each wave gated:
- **Wave 1 (Trust & Money):** P0 + money-adjacent P1s — fix actively misleading money/identity UX + the 1-line unblocks. ~10 items, ~2 engineer-weeks.
- **Wave 2 (Core Brand Workspace):** Wire the 5 mock brand pages as one coordinated pass — campaigns, wallet, deals, contracts, creator-profile. Backend already exists for all 5; this is pure FE→BE wiring. ~1.5 engineer-weeks.
- **Wave 3 (Systemic Gaps):** Notification event-producer pass + admin phantom-endpoint fixes. ~3 engineer-weeks.

**Decision gates before each wave** (blockers flagged inline). **Process fixes** (extend the contract-test guardrail, add event-producer lint) happen in Wave 1 to prevent regression during Waves 2-3.

---

## Wave 1: Trust & Money (P0 + money-adjacent P1s + process fixes)

**Goal:** Stop actively misleading users about money/identity state. Fix the one-line unblocks. Patch the process gaps so Waves 2-3 don't regress.

**Duration estimate:** ~2 engineer-weeks (Vikram + Ananya in parallel on different items).

**Decision gate (BEFORE starting):**
- [ ] **Swapnil:** Real delete-account semantics — hard-delete user+data, or soft-delete/anonymize?
- [ ] **Vikram + Priya:** KYC PII storage design — new `CreatorKyc` entity, or extend `CreatorProfile`? Where does sensitive ID live (separate encrypted table, S3 with pre-signed URLs)?

### 1.1 — P0: Brand Wallet wiring (M)
**Owner:** Ananya (Frontend)
**Files:** `src/pages/brand-wallet.tsx:108-233`
**What:** Replace `mockWalletData`/`mockTransactions`/`mockEscrowItems` with real `api.wallet.get('brand')` (already exists, used in `dashboard-page.tsx:150`). Wire balance, transactions, escrow holdings, FY tax totals to the real backend.
**Backend:** `WalletController` already exists, zero backend work.
**Verify:** Ananya self-check via live dev server (real brand login, confirm ₹ balance changes when wallet is topped up).
**Blocks:** None.

### 1.2 — P1: Creator payout-method management (M)
**Owner:** Vikram (Backend) + Ananya (Frontend)
**Files:** BE `influora-api/.../web/WalletController.java` or new `PayoutMethodController.java`, FE `src/pages/creator-wallet.tsx:485-525`
**What:** 
- Backend: add `GET /wallet/payout-methods` (list creator's UPI/bank accounts) + `POST /wallet/payout-methods` (add) + `PUT /wallet/payout-methods/:id/primary` (set default). Schema: new `PayoutMethod` entity or extend `Wallet`.
- Frontend: replace hardcoded `priya@okaxis` / `HDFC ****4532` with fetched list; wire Set Primary / Add New Method buttons.
**Verify:** Kavya QA + Meera local (`npm run build`, `mvn -o compile`, curl the new endpoints).
**Blocks:** None (schema decision: keep it simple — UPI string + bank account fields in a single `PayoutMethod` table).

### 1.3 — P1: Delete Account honesty (M)
**Owner:** Vikram (Backend) + Ananya (Frontend)
**Files:** BE new endpoint, FE `src/pages/creator-settings.tsx:144-150`
**What:**
- Backend: `DELETE /me/account` endpoint implementing Swapnil's chosen semantics (hard-delete vs soft-delete). If hard-delete: transaction spans `User`, `CreatorProfile`, `Wallet`, deals, messages (cascading FK deletes or manual cleanup). If soft-delete: `User.deletedAt` timestamp + anonymize PII fields.
- Frontend: replace `setTimeout` fake with real `api.me.deleteAccount()` call.
**Decision dependency:** Swapnil must choose hard vs. soft BEFORE this starts.
**Verify:** Kavya QA (test the cascading-delete logic doesn't orphan FK rows) + Meera local.
**Blocks:** **🔴 DECISION GATE** — cannot start until Swapnil answers.

### 1.4 — P1: Creator KYC real persistence (M)
**Owner:** Vikram (Backend)
**Files:** `influora-api/.../service/OnboardingService.java:283-295`
**What:**
- Schema: new `CreatorKyc` entity (or extend `CreatorProfile` with `panNumber`, `aadhaarLast4`, `kycSelfieUrl`, `kycStatus ENUM`, `kycSubmittedAt`, `kycReviewedAt`) per Priya's design decision.
- `submitCreatorKyc()`: persist the validated PAN/Aadhaar/selfie, set `kycStatus=PENDING`, return the real row's status (not a fake).
- Withdrawal gate already exists (`WalletController.withdraw` checks KYC) — confirm it reads the real persisted status.
**Decision dependency:** Priya + Vikram must agree on schema (separate table vs. profile extension, encryption at-rest if separate table).
**Verify:** Kavya QA (submit KYC, reload page, confirm status survived; attempt withdrawal before/after KYC) + Meera local.
**Blocks:** **🔴 DECISION GATE** — Priya/Vikram schema design.

### 1.5 — P1: Brand-safety AI route mount (S, 1-line fix)
**Owner:** Vikram (Python AI service)
**Files:** `influora-ai/app/main.py:36-39`
**What:** Add `app.include_router(brand_safety.router)` alongside the other 4 routers.
**Verify:** `curl -X POST http://localhost:8000/internal/brand-safety -H "Authorization: Bearer ..." -d '{"url":"https://example.com","brand_id":"..."}' | jq` returns a score, not 404.
**Blocks:** None.

### 1.6 — P1: notifications.markRead path fix (S)
**Owner:** Ananya (Frontend) or Vikram (Backend, pick one)
**Files:** FE `src/lib/api.ts:1277` vs. BE `NotificationController.java:74`
**What:** **Option A (FE fix, recommended):** change `notifications.markRead` to `POST /notifications/read` with `{notificationId}` body instead of path param. **Option B (BE fix):** add `@PostMapping("/{id}/read")` to match FE's current call. Pick A — the body-based pattern is already live and tested.
**Verify:** Click a notification, confirm it marks read and the badge count decrements.
**Blocks:** None.

### 1.7 — Process fix: Extend api-contract guardrail to admin client (M)
**Owner:** Ananya (Frontend test)
**Files:** `src/lib/__tests__/api-contract.test.ts:79-104`, `src/admin/services/api-contracts.ts`
**What:** The current test only parses `src/lib/api.ts`. Extend the `extractApiPaths()` regex to also read `src/admin/services/api-contracts.ts` (it uses `apiRequest('/path')` calls, different from the main client's `http.request` pattern). Match admin paths against the `/admin/**` controller set. This will catch the ~20 phantom admin endpoints automatically going forward.
**Verify:** Run the test — it should NOW fail (listing the ~20 phantom admin paths Priya found). The test failure is the success signal; Waves 2-3 fix the actual endpoints.
**Blocks:** None.

### 1.8 — Quick wins: 3 trivial unblocks (S each, batch together)
**Owner:** Ananya (Frontend)
1. **Un-mount brand-safety router** (Python, already done in 1.5).
2. **Add route for creator-affiliate-earnings page:** `src/App.tsx` — import `creator-affiliate-earnings.tsx` and add `<Route path="/creator/affiliate-earnings" element={<CreatorAffiliateEarnings />} />`. The page and backend both exist; just never routed.
3. **Remove 2 stale `NOT_IMPLEMENTED` guards:**
   - `src/lib/api.ts:1900` (`brandReviews.listReceived`) — replace `throw ApiError(NOT_IMPLEMENTED)` with `http.request('GET', '/brand/reviews/received', {role:'brand'}) |> mapReviewFromApi`.
   - `src/lib/api.ts:1625,1673` (`analytics.getCreatorDemographics`, `contentPerformance.list`) — wire to the now-existing `GET /analytics/creators/{id}/demographics` and `/media`.
**Verify:** Each route/call loads without error.
**Blocks:** None.

**Wave 1 output:** P0 fixed, 4 money-adjacent P1s shipped, contract-test guardrail extended, 3 one-line unblocks done. ~10 items closed.

---

## Wave 2: Core Brand Workspace Wiring

**Goal:** Wire the 5 brand core-workspace mock pages to their already-existing backends as **one coordinated pass** (they share root cause and some share files — doing them together avoids merge conflicts and ensures the pattern is consistently fixed).

**Duration estimate:** ~1.5 engineer-weeks (Ananya focused, Kavya QA in parallel on each page as it lands).

**Decision gate:** None — all backends already exist, this is pure FE wiring.

### 2.1 — Brand Wallet (already done in Wave 1.1)
Moved to Wave 1 as the P0. Check here for completeness.

### 2.2 — Brand Campaigns List (M)
**Owner:** Ananya
**Files:** `src/components/brand/campaigns/campaigns-list.tsx:54`
**What:** Replace `mockCampaigns` with `api.campaigns.listAll('brand')` (the method exists, called by admin; confirm it works for brands or add a brand-scoped variant). Wire the list, search, filters.
**Backend:** `CampaignController` already exists.
**Verify:** Create a campaign via the `/brand/campaigns/new` flow, confirm it appears in the list.

### 2.3 — Brand Campaign Detail (M)
**Owner:** Ananya
**Files:** `src/pages/brand-campaign-detail.tsx:40`
**What:** Replace `mockActiveCampaign`/`mockBids`/`mockCollaborators` with real fetches keyed on the `:id` route param: `api.campaigns.getById(id, 'brand')`, `api.deals.listForCampaign(id)` (or similar), collaborators from deals.
**Backend:** `CampaignController` + `DealController`.
**Verify:** Open `/brand/campaigns/{real-id}`, confirm title/status/bid-list match the campaign you created.

### 2.4 — Brand Contracts & Deliverables (M)
**Owner:** Ananya
**Files:** `src/components/brand/contracts/contracts-and-deliverables.tsx:107`
**What:** Replace `mockContracts` with `api.contracts.list('brand')` (or `api.deals.listContracts`). Wire contract statuses, deliverables.
**Backend:** `ContractController`.
**Verify:** Sign a contract via the deal flow, confirm it appears in `/brand/contracts`.

### 2.5 — Brand Deal-Room Inbox List (M)
**Owner:** Ananya
**Files:** `src/pages/brand-chat.tsx:107`
**What:** Replace `mockDealRooms` with `api.deals.list('brand', ...)` (same pattern as `creator-deals.tsx:217` — already wired on creator side). The per-deal messages are ALREADY wired (line 507-600), so this is just the inbox-list fetch.
**Backend:** `DealController`.
**Verify:** Accept a creator's bid → deal created → appears in brand's `/brand/chat` inbox.

### 2.6 — Brand-side Creator Profile (M)
**Owner:** Ananya
**Files:** `src/pages/brand-creator-profile.tsx:55,200-211`
**What:** 
- Replace `mockCreator` with `api.creators.getById(id)` keyed on the `:id` route param (the method exists, used in `creator-discovery.tsx`).
- **The "Invite" dialog** (line 200-211): replace `setTimeout` fake with a real `api.deals.createProposal({creatorId: id, campaignId: selectedCampaign, ...})` POST. Also replace `mockCampaigns` dropdown with `api.campaigns.listAll('brand')`.
**Backend:** `CreatorController` + `DealController`.
**Verify:** Search for a creator in discovery, click into their profile, confirm their real stats load. Fill out the Invite dialog and send — confirm the deal appears in the creator's inbox.

**Wave 2 output:** All 5 brand core-workspace mock pages are now wired to real data. A brand can see their actual campaigns, deals, contracts, wallet, and creators.

---

## Wave 3: Systemic Gaps (Notifications + Admin Phantom Endpoints)

**Goal:** Fix the two large systemic issues — the 85%-dead notification event system and the ~20 phantom admin endpoints.

**Duration estimate:** ~3 engineer-weeks (Vikram backend-heavy, some admin endpoints may need product/ops decisions).

**Decision gates:**
- [ ] **Admin content-moderation actioning** (P1, line 60 of audit) — who owns building the resolution flow? Vikram or is this deferred?
- [ ] **Admin Finance/Escrow/Error/Email/Marketing dashboards** — do these ship, or are they backlog? Some (Finance, Escrow) are money-path; others (Error-monitoring, Email-ops) are ops tooling.

### 3.1 — Notification Event-Producer Pass (L, systemic)
**Owner:** Vikram (Backend)
**Files:** `influora-api/.../service/` (multiple service classes)
**What:** The 24 dead event types from Priya's audit, prioritized by user impact:

**Money-path (ship first):**
1. `PayoutReleasedEvent` — publish in `WalletService.releasePayout()` after escrow settles.
2. `WalletLowBalanceEvent` — publish when `Wallet.balance` drops below a threshold (e.g., ₹5000 for brands).
3. `EscrowFundedEvent` — publish in `EscrowService.fund()` after successful hold.

**Core deal-flow notifications:**
4. `BidAcceptedEvent` — publish in `DealService` when a brand accepts a creator's bid.
5. `ApplicationCreatedEvent` — publish in `DealService` when a creator applies to a campaign.
6. `ProposalSentEvent` — publish in `DealService.createProposal()`.
7. `ProposalAcceptedEvent` — publish when creator accepts brand's direct proposal.
8. `BidCounteredEvent` — publish when either side counters.
9. `ContractPendingSignatureEvent` — publish in `ContractService.create()` when a contract awaits the other party's signature.
10. `DeliverableSubmittedEvent` — publish in `CreatorDeliverableService.submit()`.

**KYC/onboarding:**
11. `KycApprovedEvent` / `KycRejectedEvent` — publish when admin/system reviews KYC (once KYC persistence from Wave 1.4 lands).

**Meera AI:**
12. `SiteAnalyzedEvent` — publish in the onboarding-job or site-analyzer when analysis completes.
13. `CampaignRecommendedEvent` — publish when Meera suggests a campaign to a brand.
14. `CreditsExhaustedEvent` / `CreditsResetEvent` — publish in `AICreditService` and `AICreditResetJob` (Priya noted these already).

**Lower priority (backlog after Wave 3):**
- `ShipmentCreatedEvent`, `ShipmentReceivedEvent`, `CreatorFirstMessageEvent`, `FirstMessageSentEvent`, `MonthlyStatementEvent`, `UserCreatedEvent` (onboarding welcome — low urgency).

**Method:** For each event, grep the codebase for the service method that semantically "owns" that event (e.g., `PayoutReleasedEvent` → `WalletService.releasePayout`), add `new XxxEvent(...)` construction with the relevant entity IDs, and `applicationEventPublisher.publishEvent(event)`. The listener side is already built — zero work there.

**Verify:** For each event:
1. Trigger the action in the UI (e.g., accept a bid, submit a deliverable, fund escrow).
2. Check the in-app notifications list — confirm the notification appears.
3. Check `email_outbox` table — confirm an email row was queued (for email-enabled event types).

**Process fix (do FIRST):** Add a test or lint rule that fails if a `NotificationEvent` subtype has zero `new XxxEvent(` call sites (grep-based check in CI). This prevents future dead events.

### 3.2 — Admin Phantom Endpoints — Finance namespace (L)
**Owner:** Vikram (Backend)
**Files:** `src/admin/services/api-contracts.ts:327-358`
**What:** Build `AdminFinanceController` backing:
- `GET /admin/finance/revenue` — aggregate platform revenue (requires Rohan's fee-formula decision from the audit).
- `GET /admin/finance/escrow` — escrow summary (total locked, per-deal breakdown).
- `GET /admin/finance/payouts` — payout queue.
- `POST /admin/finance/payouts/:id/retry` — retry a failed payout.
- `GET /admin/finance/reconciliation` — unreconciled transactions.
- `POST /admin/finance/reconciliation/:id/resolve`.
- `GET /admin/finance/tds/26q` — TDS 26Q report generation.
**Decision dependency:** Revenue endpoint needs Rohan's platform-fee formula (see P2 findings).
**Verify:** Meera curl each endpoint; Kavya QA the admin Finance screen.

### 3.3 — Admin Phantom Endpoints — Escrow namespace (L, money-path)
**Owner:** Vikram (Backend)
**Files:** `src/admin/services/api-contracts.ts:443-470`
**What:** Build `AdminEscrowController` (distinct from the brand-facing `EscrowController`) backing:
- `GET /admin/escrow/flagged` — list holds flagged for review (e.g., dispute-related, SLA-breach).
- `POST /admin/escrow/{id}/release` — admin-override release.
- `POST /admin/escrow/{id}/hold` — admin-override hold (freeze).
- `POST /admin/escrow/{id}/refund`.
**Verify:** Meera curl; Kavya QA the admin Escrow screen.

### 3.4 — Admin Phantom Endpoints — Error / Email / Marketing namespaces (M each, lower priority)
**Decision gate:** Do these ship in Wave 3, or backlog?
- **errorApi** (`GET /admin/errors/recent`, etc.) — ops tooling, not user-facing.
- **emailApi** (`GET /admin/emails/queue`, `POST /admin/emails/send-bulk`) — ops + comms tool.
- **marketingApi** (`GET /admin/marketing/acquisition`, etc.) — analytics.

If shipping: Vikram builds 3 controllers (`AdminErrorController`, `AdminEmailController`, `AdminMarketingController`) mirroring the `api-contracts.ts` spec. If backlog: remove the FE calls or stub them with honest "not built yet" banners.

### 3.5 — Admin Phantom Endpoints — Dashboard / Campaign / Support drill-downs (M)
**Owner:** Vikram (Backend)
**Files:** `src/admin/services/api-contracts.ts:131,146,307,310,313` + others
**What:** Add the missing GET mappings to existing controllers:
- `AdminDashboardController`: `@GetMapping("/financial")`, `@GetMapping("/marketing")`.
- `AdminCampaignController`: `@GetMapping("/{id}")`, `@GetMapping("/at-risk")`, `@GetMapping("/hype/ops")`.
- `AdminSupportController`: `@PostMapping("/{id}/escalate")`, separate `@GetMapping("/stats")`.
- `AdminModerationController`: `@GetMapping("/suspensions")`, `@PostMapping("/suspensions/{id}/appeal")`.
- `AdminBrandController` / `AdminCreatorController`: `@PutMapping("/{id}")`, `PUT /{id}/tier`, etc.
**Verify:** Meera curl; Kavya QA each admin screen.

**Wave 3 output:** Notification system alive (14+ priority events firing), admin Finance + Escrow + core drill-downs functional. Decision-gated admin namespaces (Error/Email/Marketing) either shipped or honestly deferred.

---

## Process improvements (run alongside Wave 1)

These prevent the same gaps from recurring:

1. **api-contract.test.ts extended** (already in Wave 1.7) — catches admin phantom endpoints.
2. **Event-producer lint/test** (Wave 3.1) — fails if a `NotificationEvent` subtype has zero `new` call sites.
3. **Mock-page guardrail** (Priya's recommendation #1): add a test that greps `src/pages/` and `src/components/` for `const mock[A-Z]` and fails if the same file never imports `@/lib/api`. Whitelist honest placeholders (e.g., demo pages). This catches UI-before-wiring debt automatically.

---

## Verification gates (each wave)

**Wave 1:** Kavya QA + Meera local verify each item. No Kabir red-team this wave (mostly wiring fixes, not new attack surface).

**Wave 2:** Kavya QA + Meera local verify each page. **After all 5 pages land:** Kabir red-team the brand workspace as one surface (XSS in campaign/contract rendering, authz on deal/wallet APIs, IDOR on creator-profile `:id` param).

**Wave 3:** Kavya QA + Meera local verify each notification/endpoint. **After notification event-producer pass:** Kabir red-team the notification system (email injection, notification-spam DoS, privilege escalation via crafted events).

---

## Decision summary (gate each wave on these)

**BEFORE Wave 1:**
- [ ] Swapnil: Delete-account semantics (hard vs. soft).
- [ ] Priya + Vikram: KYC PII storage schema design.

**BEFORE Wave 3:**
- [ ] Swapnil/Priya: Admin content-moderation actioning — build or defer?
- [ ] Swapnil/Priya: Admin Error/Email/Marketing namespaces — ship in Wave 3 or backlog?
- [ ] Rohan: Platform-fee revenue formula (needed for `GET /admin/finance/revenue`).

**P2/P3 Backlog decisions** (not blocking any wave, defer to normal prioritization):
- Affiliate commission rate (placeholder 10% → real per-campaign rate).
- Meera idempotency result-ref build.
- Real payment/social integration provisioning (Razorpay/Meta/Shopify/MSG91 app setup).
- Subscription-billing (V54) ship timeline.
- Brand-messages page retire-or-wire.
- Grievance Officer legal details.

---

## Timeline estimate

- **Wave 1:** 2 weeks (Vikram + Ananya in parallel).
- **Wave 2:** 1.5 weeks (Ananya focused, Kavya QA in parallel).
- **Wave 3:** 3 weeks (Vikram backend-heavy, some decision gates mid-wave).

**Total:** ~6.5 engineer-weeks, non-parallelizable across waves (Wave 2 depends on Wave 1 contract-test fix landing, Wave 3 notification pass depends on Wave 1 KYC persistence if wiring KYC events).

**Next:** Swapnil approves the plan + answers the 2 Wave-1-blocking decisions, then Arjun routes Wave 1 items to Vikram + Ananya and starts the pipeline.
