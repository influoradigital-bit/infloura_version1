# Influora — Production Readiness Code Audit

**Date:** 2026-07-14
**Method:** Source-code-only audit. Every conclusion below was traced through the actual code (controller → service → repository → entity → migration, and UI → API client → endpoint). No `.md`, README, or comment was trusted as evidence of behaviour. Eight independent specialist passes (auth/security, commerce, money, AI, integrations, frontend/API-contracts, admin, database/jobs) plus direct verification of every Critical claim.

**Codebase shape (verified):**
- **Backend** — Java Spring Boot (`influora-api`): 62 controllers, 109 services, 70 repositories, 129 entities, 74 Flyway migrations.
- **AI service** — Python FastAPI (`influora-ai`): providers for Claude/Gemini/Sarvam, agentic tool loop, SSRF guard, redaction, cost gate, service-token auth.
- **Frontend** — React/Vite (`src`): 74 pages, 193 components, plus a separate admin sub-app (`src/admin`).

---

## 1. Verdict

**Not production ready.** The system is *architecturally* mature and mostly written — the schema, the double-entry ledger, webhook signature verification, encryption, and MFA are genuinely well-built. But the product breaks at its **integration seams**: money can be charged twice, the entire AI layer is disconnected, the deliverable pipeline can never start, creator accounts cannot authenticate, notifications are never delivered, and admin "suspend/moderate" actions do nothing. These are not polish items — they are launch blockers on the core revenue and trust paths.

### Scorecard

| Metric | Score | Basis |
|---|---:|---|
| **Overall Project Health** | **54 / 100** | Strong foundations, multiple Critical launch-blockers on core flows |
| Feature Completion | 58% | Most features coded; key wirings between layers missing |
| Frontend Completion | 60% | Brand ~70%, Creator ~45% (auth mock, dashboard orphaned) |
| Backend Completion | 72% | Controllers/services/repos largely implemented; happy paths solid |
| AI Integration | 35% | Real provider code exists; **every** user-facing AI feature disconnected |
| Database Health | 85% | Clean entity/repo/migration reconciliation; minor orphans |
| Security | 68% | Excellent primitives; undermined by IDOR, cosmetic bans, secret fallbacks |
| Production Readiness | 38% | Money double-charge, AI down, notifications undelivered |
| **Critical issues** | **15** | |
| **High issues** | **27** | |
| **Medium issues** | **~30** | |
| **Low issues** | **~20** | |

---

## 2. Critical Issues (launch blockers)

Each item was verified directly in source.

### C1. Escrow funding double-charges the brand
- **Severity:** Critical · **Module:** Escrow/Payments · **Affected:** Brand
- **Problem:** To fund escrow, `initiateFund` requires the brand's wallet balance to already be ≥ amount **and** creates a fresh Razorpay order for the same amount. The frontend opens Razorpay Checkout so the brand pays that order; the resulting webhook (`confirmFunded`) then **debits the brand's internal wallet** (funded by a prior top-up) into the clearing wallet. The externally-paid Razorpay cash is never credited to any wallet. Net: the brand pays the amount twice, escrow holds it once.
- **Evidence:** `influora-api/.../service/EscrowService.java:149` (`INSUFFICIENT_FUNDS` balance check), `:165` (`razorpayClient.createOrder`), `:230-252` (`confirmFunded` posts DEBIT brand wallet → CREDIT clearing); `src/hooks/useEscrowFund.ts` (opens Razorpay Checkout, polls for FUNDED). Only `WalletTopUpService` ever credits a wallet from a Razorpay payment.
- **Root cause:** Two contradictory funding models ("fund from pre-funded wallet" and "pay a fresh external order") both execute.
- **Impact:** Systematic 2× overcharge on every escrow funding; the second payment is stranded platform cash with no ledger entry — direct money loss + reconciliation breakage.
- **Fix:** Pick one model. Wallet-funded: move funds wallet→clearing internally at fund time, drop the Razorpay order/webhook gate. Pay-per-escrow: remove the wallet-balance requirement and the wallet debit, credit clearing directly from the verified webhook amount.
- **Confidence:** High

### C2. Deliverable rows are never created — the entire upload→review→approve pipeline is unreachable
- **Severity:** Critical · **Module:** Deliverables · **Affected:** Creator, Brand, System
- **Problem:** No production code inserts a `Deliverable`. Every service method only *loads* and mutates an existing row. Deal acceptance creates no deliverable slots. With no rows, every deliverable endpoint 404s in a real workspace.
- **Evidence:** `Deliverable.builder(`/`new Deliverable(` appears only in `domain/entity/Deliverable.java:306` and test files — zero production callers. Table `V37__deliverables.sql`.
- **Root cause:** The slot-provisioning step (campaign deliverable specs → `deliverables` rows on deal/contract agreement) was never built.
- **Impact:** Deliverables feature is non-functional end-to-end; the marketplace's core "creator submits work" loop cannot begin.
- **Fix:** Provision `Deliverable` rows on deal acceptance/contract creation, linking `collaborationId`, `creatorProfileId`, `milestoneId`.
- **Confidence:** High

### C3. Brand approval sets a status with no downstream effect — no escrow release, no payment, no notification
- **Severity:** Critical · **Module:** Deliverables ↔ Escrow/Payments · **Affected:** Creator, Brand
- **Problem:** `BrandDeliverableService.approve()` calls `applyApprove()` (status → APPROVED), saves, and returns. It never invokes escrow release, milestone payment, or an event. Escrow release exists only as a separate manual endpoint with nothing linking approval to it. **Approve ≠ pay.**
- **Evidence:** `service/BrandDeliverableService.java:57-69` (constructor injects only context/repo/storage — no escrow/event deps); `Deliverable.applyApprove():242-248`; release isolated in `EscrowService.release()`.
- **Impact:** Approving a deliverable does not release funds or mark a milestone paid — the core marketplace guarantee is missing.
- **Fix:** In `approve()`, resolve the linked milestone and release escrow (respecting `release_condition`) in the same transaction, then publish an approval/notification event.
- **Confidence:** High

### C4. Frontend cannot generate or sign contracts — both payloads mismatch backend DTOs
- **Severity:** Critical · **Module:** Contracts · **Affected:** Brand, Creator
- **Problem:** Client `contracts.generate` posts `{dealId}` but backend requires `{collaborationId, milestones[]}` → 400. Client `contracts.sign` posts `{name, agreedAt}` but backend requires `{role}` → 400.
- **Evidence:** `src/lib/api.ts:906-919`; `web/dto/money/MoneyDtos.java:181-192`; `ContractService.generate:139-143`.
- **Impact:** The whole contract lifecycle (generate → sign → ACTIVE → escrow-ready) is unreachable from the UI, cascading into the escrow/milestone flow.
- **Fix:** Align shapes on either side.
- **Confidence:** High

### C5. Creator authentication is entirely mock — no creator can log in against the real backend
- **Severity:** Critical · **Module:** Auth · **Affected:** Creator
- **Problem:** `creator-login.tsx` / `creator-register.tsx` never call the backend; they set a hardcoded `localStorage 'creator_token' = 'mock_creator_token'`. In a production build the mock guard *throws*, so login errors out; in a live build it mints a fake token the Java JWT filter rejects (401). The real `/auth/creator/login|register` endpoints exist but have zero callers.
- **Evidence:** `src/pages/creator-login.tsx:8,38-39`; `src/pages/creator-register.tsx:42-66`; contrast `brand-login.tsx:34` (real `api.auth.brandLogin`).
- **Impact:** No creator can authenticate; every downstream creator API rides a token the backend rejects. Half the two-sided marketplace is inoperable.
- **Fix:** Wire creator login/register to the real endpoints and persist a real session (mirror the brand flow).
- **Confidence:** High

### C6. Creator onboarding posts to non-existent `/onboarding/creator/*` routes (404)
- **Severity:** Critical · **Module:** Onboarding · **Affected:** Creator
- **Problem:** Onboarding calls `POST /onboarding/creator/socials|profile|complete`, but `OnboardingController` is `@RequestMapping("/onboarding/brand")` only. Every step 404s in live mode; profile/social data is never persisted.
- **Evidence:** `src/pages/creator-onboarding.tsx:93,145,182`; `OnboardingController.java` (brand-only mappings).
- **Fix:** Implement the creator onboarding controller, or repoint the client at the real creator-profile/social endpoints.
- **Confidence:** High

### C7. Notifications are triple-broken in live mode (wrong base URL, wrong paths, wrong response shape)
- **Severity:** Critical · **Module:** Notifications · **Affected:** Brand, Creator, System
- **Problem:** The UI's `useNotifications` (a) fetches a **relative** `/api/v1/notifications`, ignoring `VITE_API_BASE_URL`; (b) reads `data.data` while the backend returns an un-enveloped `{notifications,...}` (no `data` key) → list always empty; (c) marks read via `POST /notifications/{id}/read` and `/read-all`, neither of which exists (backend is `POST /notifications/read` with `{notificationId}`). The alternate `api.notifications.*` client throws anyway because `NotificationController` returns a raw body without the `success` envelope the client requires.
- **Evidence:** `src/hooks/useNotifications.ts:118-176`; `NotificationController.java:52-98` (raw `NotificationListResponse`, no `ApiResponse` wrapper); `NotificationDtos.java` (`isRead`/`eventType` vs FE `read`/`type`).
- **Impact:** Notification list always empty; mark-read/read-all silently 404; unread badge never clears.
- **Fix:** Route through one client, wrap controller responses in `ApiResponse.ok(...)`, align field names + endpoints.
- **Confidence:** High

### C8. AI: TrendSpark and Brand-Safety FastAPI routers are never registered
- **Severity:** Critical · **Module:** AI service · **Affected:** System
- **Problem:** `main.py` includes only `chat`, `analyze_site`, `voice`. The `trendspark` and `brand_safety` routers are never imported/registered, so `POST /internal/trendspark/nudge` and `/internal/brand-safety` return 404 — exactly the paths the Java clients call.
- **Evidence:** `influora-ai/app/main.py:23,36-38` (verified: three routers only); callers `integration/ai/TrendSparkAiClient.java:119`, `BrandSafetyAiClient.java:98`.
- **Impact:** TrendSpark AI phrasing and brand-safety GARM scoring completely non-functional.
- **Fix:** `include_router` both routers in `main.py`.
- **Confidence:** High

### C9. AI: service references configuration symbols that do not exist → import/attribute crashes
- **Severity:** Critical · **Module:** AI service (config/costs) · **Affected:** System
- **Problem:** `costs/pricing.py` imports `TRENDSPARK_MODEL` from `app.config`, which never defines it → `ImportError` on load. `costs/gate.py` reads `settings.ai_spend_kill_switch` / `ai_daily_spend_ceiling_usd`, and the trendspark/brand_safety routes read `settings.trendspark_max_*` / `brand_safety_max_*` — none exist on `Settings` → `AttributeError` at runtime.
- **Evidence:** `costs/pricing.py:19`; `costs/gate.py:36,44`; `routes/trendspark.py:200-204`; `config.py:59-195` (fields absent — grep-verified).
- **Impact:** Even if routes were mounted, importing `pricing.py` crashes the app and the spend gate throws before any check runs.
- **Fix:** Add the missing config fields with defaults.
- **Confidence:** High

### C10. AI: Meera chat performs no LLM call on the backend — hardcoded placeholder reply
- **Severity:** Critical · **Module:** AI (MeeraSessionService) · **Affected:** Brand
- **Problem:** `sendTurn` persists and returns a hardcoded `"Meera (placeholder): ..."` string. No Claude/Gemini call happens in Spring; the real reasoning was meant to come from a browser→Python SSE stream that (C11/C12) cannot connect.
- **Evidence:** `service/meera/MeeraSessionService.java:186-205` (verified: `"Meera (placeholder): received your message..."`, class javadoc "No real LLM call happens").
- **Impact:** Brands get a canned placeholder for every message.
- **Fix:** Complete the browser↔Python SSE wiring and remove the placeholder echo.
- **Confidence:** High

### C11. AI: stream token missing `scope` and `iss` claims → chat auth always 401
- **Severity:** Critical · **Module:** Backend ↔ AI (auth) · **Affected:** Brand, System
- **Problem:** `StreamTokenService.mint` sets `sub/aud/workspaceId/conversationId/messageId` but **no** `scope` and **no** issuer. Python `verify_token` requires `iss` and a valid `scope` and rejects otherwise (401). The sibling brand-safety token service sets both correctly.
- **Evidence:** `service/meera/StreamTokenService.java:58-73`; `influora-ai/app/auth/service_token.py:206,235-245`.
- **Fix:** Add `.issuer(...)` and `.claim("scope","chat:stream")`.
- **Confidence:** High

### C12. AI: frontend opens the chat stream with EventSource (GET) but the Python route is POST
- **Severity:** Critical · **Module:** Frontend ↔ AI · **Affected:** Brand
- **Problem:** `useMeeraStream.open` uses `new EventSource(url + "?token=")` — GET only, can't set headers or a body. Python `/chat` is `POST`, reads `workspace_id`/conversation from the JSON body and the token from the `Authorization` header or body — never from `?token=`.
- **Evidence:** `src/hooks/useMeeraStream.ts:124-126`; `influora-ai/app/routes/chat.py:67-72,237-243`.
- **Impact:** Chat stream 405s / can't transmit workspace or token — streaming is fundamentally broken end-to-end.
- **Fix:** Use `fetch` POST + `ReadableStream`, send token via header/body and include the conversation identifiers.
- **Confidence:** High

*(AI Critical group also includes `routes/trendspark.py` calling a non-existent `ClaudeProvider.complete_text` — every TrendSpark nudge that passes the gate throws `AttributeError`. `routes/trendspark.py:262` vs `providers/claude.py`.)*

### C13. Admin "Suspend Brand" is a cosmetic flag — the brand keeps operating
- **Severity:** Critical · **Module:** Admin / Brand management · **Affected:** Brand, System
- **Problem:** Suspend persists `workspaces.suspended = true`, but that flag is read nowhere outside admin read paths. Brand login gates on `user.status`, which suspend never sets. A suspended brand's owner can still log in and run campaigns — while the UI promises they've lost access.
- **Evidence:** `service/admin/AdminBrandService.java:264-291` (saves only `Workspace`); `service/AuthService.java:178` (login checks `user.getStatus()` only); flag read nowhere else.
- **Impact:** Moderation against a fraudulent/abusive brand has no effect.
- **Fix:** Enforce `workspace.suspended` at login and campaign/escrow entry points (or flip owner `User.status`).
- **Confidence:** High

### C14. Admin "Suspend Creator" is cosmetic — no login block, still discoverable
- **Severity:** Critical · **Module:** Admin / Creator management · **Affected:** Creator, System
- **Problem:** Suspend persists `creator_profiles.suspended = true`, read only by the admin list/detail. Creator login gates on `user.status`; discovery/marketplace/matching never filter on `suspended`. A suspended (or rejected) creator stays logged-in, discoverable, and invitable.
- **Evidence:** `service/admin/AdminCreatorService.java:296-331`; `AuthService.creatorLogin:290`; discovery specs in `CreatorDiscoveryService` filter only `discoverable()`. (Corroborated by the commerce pass: `CreatorProfile.suspend()`/reject never touch `discoverable`.)
- **Impact:** Safety/abuse controls against creators are illusory.
- **Fix:** Filter `suspended`/`applicationStatus` in discovery + matching, and enforce at login.
- **Confidence:** High

### C15. AI layer, holistically, is disconnected end-to-end
- **Severity:** Critical · **Module:** AI (system-level) · **Affected:** Brand, System
- **Problem:** Consolidating C8–C12: **no** user-facing AI feature currently works. Meera chat returns a placeholder (and its stream can't auth or connect); TrendSpark always falls back to a template (route unmounted + missing config + missing provider method); site analysis has **no** Spring caller at all; brand safety is unmounted + crashes on missing config; voice bypasses the AI service entirely (frontend uses browser `webkitSpeechRecognition`, Sarvam routes have no caller). The real provider/security code exists but is unwired at every seam.
- **Evidence:** aggregated from `influora-ai/app/*`, `integration/ai/*`, `service/meera/*`, `src/hooks/useVoiceInput.ts:49`, `src/hooks/useMeeraStream.ts`.
- **Fix:** Treat AI as an integration project: mount routes, add config, wire stream transport/auth, add the analyze-site client, and route voice through Sarvam.
- **Confidence:** High

---

## 3. High-Severity Issues

Presented in the standard format, grouped by area. (All verified in source; file refs are exact.)

### Auth & Security
**H1. Admin login is unreachable as configured.** `/admin/auth/**` is not in the security `permitAll` list (only `/auth/**` is), so `POST /admin/auth/login` falls through to `.anyRequest().authenticated()` — but login is how an admin gets a token. Chicken-and-egg lockout. *Evidence:* `config/SecurityConfig.java:74-89` (verified: no `/admin/auth` matcher); `web/AdminAuthController.java` (`@RequestMapping("/admin/auth")`). *Fix:* add explicit `permitAll()` for `POST /admin/auth/login` and `/refresh`. *Confidence:* High.

**H2. Email verification is broken/bypassed under the shipped default config.** With `require-email-verification=true` and `require-email-otp-before-register=false`, registration issues a full session without verification, yet the OTP `verify-email` path never sets `user.emailVerified`, so any later login is permanently rejected `EMAIL_NOT_VERIFIED`. *Evidence:* `service/AuthService.java:104-156,182-187`; `service/BrandEmailOtpService.java:122-151`; `domain/entity/User.java:244-248`. *Fix:* propagate OTP verification to `User.emailVerified`. *Confidence:* High.

**H3. IDOR: escrow milestone funding trusts `milestoneId` with no ownership check.** The milestone branch of `deriveFundAmount` uses bare `milestoneRepository.findById(...)` (vs the workspace-scoped campaign branch), letting a brand in workspace A read/corrupt workspace B's milestone on a real-money path. *Evidence:* `service/EscrowService.java:178-186` vs `188-194`; `confirmFunded:255-263`. *Fix:* resolve milestone→campaign→workspace and require `findByIdAndWorkspaceId`. *Confidence:* High.

**H4. No framework-level authorization backstop.** No `@EnableMethodSecurity`/`@PreAuthorize` anywhere; the only global rule is `.anyRequest().authenticated()`, so any valid brand/creator JWT passes the filter. All admin/tier enforcement is manual service-layer guards — one missed guard on a future endpoint = brand→admin bypass. *Evidence:* `config/SecurityConfig.java:70-90`; grep: zero `@PreAuthorize`. *Fix:* add `requestMatchers("/admin/**").hasRole("ADMIN")` as defense-in-depth. *Confidence:* High.

### Commerce
**H5. Milestone `release_condition` gate (V52) is dead — escrow release is ungated.** The `ON_APPROVAL/ON_POSTED/ON_VERIFIED_METRICS` column is never read (not even mapped on the entity); `EscrowService.release()` checks only role + no-active-dispute. Funds can be released with zero deliverable verification. *Evidence:* `V52__payment_milestone_release_condition.sql`; grep `release_condition` → only the migration; `EscrowService.release()`. *Confidence:* High.

**H6. No terminal rejection path for deliverables.** Only `/approve` and `/revise` exist; `applyReject()` doesn't exist and `REJECTED` is only ever read by a cleanup job. Brands can't terminally reject — only unbounded revision requests. *Evidence:* `web/BrandDeliverableController.java:40-55`; `DeliverableStatus.java:11`. *Confidence:* High.

**H7. RazorpayX payout persistence & reconciliation stubbed; `payouts` table orphaned.** `PayoutService.confirmExecuted()` is a no-op; `queuePayout` never persists a `Payout` (repo never injected). A reversed/failed bank payout is silently lost. *Evidence:* `service/PayoutService.java:98-138,263-270`; `V48__payouts.sql`; `repository/PayoutRepository.java` (no consumer). *Confidence:* High.

**H8. Lifecycle events never published (25 of 26 notification listeners are dead).** `DeliverableSubmittedEvent`, `BidAcceptedEvent`, `EscrowFundedEvent`, `PayoutReleasedEvent`, `KycApproved/RejectedEvent`, etc. have full listeners but **no publisher**. Only `ContractSignedEvent` is wired end-to-end. *Evidence:* `service/notification/NotificationListener.java:56-428`; publishers only in `ContractService`/`SubscriptionDunningJob`. *Confidence:* High.

**H9. Brand campaign list & detail pages render mock data.** `campaigns-list.tsx` builds from an in-file `mockCampaigns`; `brand-campaign-detail.tsx` renders `MOCK_CAMPAIGNS`/`mockBids`. Real creator applications never appear on the campaign surface, though working endpoints exist. *Evidence:* `src/components/brand/campaigns/campaigns-list.tsx:54,199-238`; `src/pages/brand-campaign-detail.tsx:38-143`. *Confidence:* High.

### Money
**H10. Statutory-invoice failure inside the release transaction rolls back a completed payout.** `CampaignServiceInvoiceService.createAtRelease` runs in the same `@Transactional` as the release ledger posting; a missing HSN/SAC row or missing creator profile throws uncaught and reverses the entire release — blocking *all* creator payouts. *Evidence:* `service/EscrowService.java:348`; `CampaignServiceInvoiceService.java:88-165`; `HsnSacCodeService.java:26-36`. *Fix:* `REQUIRES_NEW` / best-effort invoice creation. *Confidence:* High.

**H11. Invoice-number uniqueness not enforced for platform-wide series (NULL creator code).** The uniqueness/retry design relies on a composite unique index including `creator_invoice_code`, but for SUBSCRIPTION/COMMISSION series that column is NULL and MySQL treats NULLs as distinct — so duplicate/gapped statutory numbers are possible under FY-rollover concurrency (illegal under GST). *Evidence:* `V20260715150000__invoice_number_sequences.sql:15-19`; `InvoiceNumberService.java:104-141`. *Fix:* store a non-null sentinel or use `SELECT … FOR UPDATE` on a pre-seeded row. *Confidence:* High.

### AI
**H12. Cost gate/spend recording not applied to the main chat route.** `chat.py` (the highest-cost path) never calls `check_spend_gate()` or `record_spend()`; only the two broken/unmounted routes reference the gate. The daily ceiling/kill-switch can never trip from chat usage. *Evidence:* `routes/chat.py:34-40`. *Confidence:* High.

**H13. Spend tracker is per-process in-memory only.** Module-level dict; resets on restart, not shared across workers → effective ceiling = ceiling × workers, and zero after any deploy. *Evidence:* `costs/spend_tracker.py:32-40`. *Confidence:* High.

**H14. Prompt-injection hardening exists but isn't wired into assembly.** `assembler.py` uses the bypassable single-`replace` wrapper and interpolates brand fields (derived from untrusted scraped site text) straight into the system prompt; the hardened `neutralize_angle_brackets` sits unused. *Evidence:* `prompt/assembler.py:63-69,87-118`; `prompt/untrusted.py:14-58`. *Confidence:* High.

**H15. Site analysis has no backend caller.** The Python `/analyze-site` route is complete but no Java code calls it; `BrandProfile.analysisStatus` is read by the UI but nothing drives it via real AI. *Evidence:* `integration/ai/` (no analyze client); `service/meera/BrandContextAssembler.java:41,54`. *Confidence:* High.

### Frontend
**H16. No 401 handling or token refresh anywhere.** `HttpClient` attaches the bearer + sends the refresh cookie, but there is no 401→`/auth/refresh`→retry interceptor. On access-token expiry, every request throws and users are silently logged out mid-use. *Evidence:* `src/lib/api.ts:137-184`; `/auth/refresh` only referenced in comments. *Confidence:* High.

**H17. `creator-dashboard.tsx` is orphaned — creators have no dashboard route.** The page exists but is never imported/routed in `App.tsx`; login lands on `/creator/inbox`. *Evidence:* `App.tsx` (no import); `grep creator-dashboard` → 0 non-test refs. *Confidence:* High.

**H18. Meera SSE-failure fallback hits a non-existent GET route (405).** On stream failure the panel calls `GET /meera/sessions/{id}/messages`, but the controller maps only POST there → the recovery path itself fails and the reply is lost. *Evidence:* `src/lib/meera-api.ts:362-381`; `MeeraController.java:81`. *Confidence:* High.

**H19. Deliverable submit drops media.** `deliverables.submit` sends `{fileUrls, notes}` but the backend `SubmitRequest(finalCaption, hashtags, notes)` has no `fileUrls` (files must be attached via a separate multipart upload the client never calls) → submissions may carry no media. *Evidence:* `src/lib/api.ts:987-994`; `CreatorDeliverableDtos.java:50`. *Confidence:* Medium.

### Admin
**H20. Content moderation "REMOVE" doesn't remove content.** `actionFlag("REMOVE")` only flips the flag to `ACTIONED`; the underlying video/post/profile is never hidden or deleted. *Evidence:* `service/admin/AdminModerationService.java:118-129`. *Confidence:* High.

**H21. Support "Escalate" calls a non-existent endpoint (404).** `TicketList` posts `/admin/support/tickets/{id}/escalate`, which the controller never implements. *Evidence:* `src/admin/components/support/TicketList.tsx:265`; `web/AdminSupportController.java`. *Confidence:* High.

**H22. Admin Billing Console runs entirely on mock data.** The backend (comp/override/subscriptions/metrics, SUPER_ADMIN+MFA gated) is real and wired to `SubscriptionService`, but `BillingConsole` renders `MOCK_SUBSCRIPTIONS`/`DEMO_METRICS` and its comp/override handlers do "No network call." *Evidence:* `src/admin/components/billing/BillingConsole.tsx:116,260,388,494`. *Confidence:* High.

### Database / Jobs / Infra
**H23. No distributed locking (ShedLock) — every `@Scheduled` job double-executes at scale.** 12 jobs + the email worker use plain `@Scheduled` guarded only by an in-JVM `AtomicBoolean`. With ≥2 replicas, monthly credit reset, affiliate settlement, dunning/renewal, payouts, and the email outbox all fire on every node → duplicate money movement and duplicate emails. *Evidence:* grep `shedlock/LockProvider` → none; `job/AICreditResetJob.java:40`; `job/AffiliateSettlementJob.java:115`. *Confidence:* High.

**H24. Email outbox can double-send across instances.** `findPendingForSend` is a plain SELECT with no `FOR UPDATE SKIP LOCKED`; two replicas read and send the same PENDING rows before either marks SENT. *Evidence:* `repository/EmailOutboxRepository.java:19-26`; `EmailWorker.java:39-55`. *Confidence:* High.

**H25. Email delivery may be silently mocked — MSG91 auth-key bound to the wrong property prefix.** `Msg91EmailClient` reads `${msg91.auth-key:}` while the yml defines `influora.msg91.*` (the same class correctly reads `${influora.msg91.email.template-id}`). When empty, `isConfigured()` is false and every send hits the `[MOCK] … return true` branch while the outbox row is marked SENT. *Evidence:* `integration/msg91/Msg91EmailClient.java:39-41`; `application.yml:92-98`; `EmailWorker.java:57-70`. *Confidence:* Medium (env-var relaxed binding may mask it in some deploys).

**H26. `@Async` on notification listeners is a silent no-op.** Handlers are `@Async` but there is no `@EnableAsync`, so they run synchronously inside the publisher's transaction — a notification failure can roll back the originating business operation. *Evidence:* grep `EnableAsync` → none; `NotificationListener.java:56-57`. *Confidence:* High.

**H27. Placeholder signing/payment secrets ship as baked-in defaults.** `application.yml` provides working `dev-*-change-in-production` defaults for JWT/stream/internal-service/HMAC secrets; `application-prod.yml` overrides only Flyway baselining, so a missing env var in prod boots with publicly-known secrets (JWT forgery / service impersonation). *Evidence:* `application.yml:75-91,128-135`; `application-prod.yml`. *Fix:* fail-fast in prod on `dev-*` literals. *Confidence:* High.

---

## 4. Medium & Low Issues (condensed)

**Medium:**
- Discovery UI filters (language, engagement, verified, sort) never sent to backend; applied client-side over one page → matches beyond page 1 dropped (`src/lib/api.ts:661-674`).
- Creator campaign browse applies niche/platform filters in-memory after pagination → `hasMore=false`, later matches unreachable (`CreatorCampaignService.java:99-124`).
- Campaign metrics hardcoded to zero (`CampaignService.list/get` pass `CampaignMetrics.empty()`).
- Approval workflow: `CONTENT_MODERATION` actioning is a 501 stub; 4 of 6 types non-actionable (`ApprovalWorkflowService.java:105-119`).
- Portfolio analytics fabricated (`profileClicks = followers/100`); `POST /me/portfolio/sync` is a no-op returning a false "synced" timestamp (`PortfolioService.java:179-226`).
- Campaign Templates: full backend + 4 seeded presets, **zero** frontend callers.
- `payment.captured` / payout webhooks can't route (routing key read only from the order entity → null receipt) (`RazorpayWebhookController.java:59-93`).
- Creator withdrawal debits the wallet but initiates no bank payout (`WalletService.java:142-198`).
- No wallet-fund reservation between PENDING hold and funding → overcommitted holds.
- Comp Pro grants never auto-expire (V63 `comp_expires_at` unenforced).
- Campaign service invoice records no CGST/SGST/IGST split for GST-registered creators.
- Two divergent deliverable-metrics write paths onto the same milestone-keyed row.
- Admin real-time WebSocket (`/admin/ws`) has **no** backend — client reconnect-loops forever.
- Admin dashboard revenue and all WoW deltas hardcoded to 0; financial/marketing summaries have no route.
- Admin campaign "management" is read-only (list only; no pause/cancel/budget action).
- Shopify OAuth callback does not verify Shopify's request HMAC (`ShopifyConnectController.java`).
- Malware scanning is a no-op in every environment (`NoOpMalwareScanService` is the only impl).
- Meta long-lived token stored already-expired when `expires_in` absent (`MetaOAuthController.java:91-93`).
- MetricsPollingJob's media-metrics fetch is a TODO → `media_metrics` never written.
- EmailWorker holds a DB transaction open across external HTTP sends.
- Mixed `V<n>` / `V<timestamp>` migrations rely on `out-of-order:true` (fragile ordering).
- Prod datasource defaults to `root/root` + `useSSL=false` if env vars are unset.
- Two published events (`ContractReadyForEscrowEvent`, `SubscriptionHaltedEvent`) have no listener.

**Low:** OTP/reset tokens hashed with unsalted SHA-256; JWT filter's narrow catch → 500 on malformed-but-signed token; access token in `localStorage` (XSS amplification); Meta secrets/tokens passed as URL query params + full error-body logging; conversion webhook parses body before signature verify; integration encryption/webhook secrets excluded from startup validation; unused `R2StorageService.presignPut` lacks content-type/key validation; portfolio avatar/cover URLs stored without scheme validation; in-memory OAuth state stores break under horizontal scaling; SUPPORT-tier admins get write actions without MFA; orphaned `file_uploads` table (V1); dead contract terminal states (COMPLETED/CANCELLED never set); milestone FROZEN never set on dispute freeze; admin session can't refresh from the SPA (`refreshToken` is `@JsonIgnore`); no MFA-enrollment UI (operational lockout risk); no cross-provider AI fallback; Sarvam TTS response treated as raw bytes instead of base64 JSON; per-turn double ASSISTANT-row persistence in Meera.

---

## 5. Cross-Cutting Summaries

### Missing / incomplete features
Creator auth & onboarding (mock/404); deliverable creation & rejection; deliverable-approval → payment link; contract generate/sign from UI; AI (all of Meera chat, TrendSpark, site analysis, brand safety, voice); notification delivery (in-app + email); admin content takedown; admin billing/campaign actions; admin real-time; creator bank payout execution; payout reconciliation; campaign templates UI; media-metrics ingestion.

### Broken code flows
Brand pay-for-escrow (double charge) · Deliverable submit→review→approve→pay (never starts, no downstream) · Contract lifecycle (UI DTO mismatch) · Creator login→dashboard (mock token, orphaned route) · Meera chat (placeholder + broken stream auth/transport) · Notifications (never published, never delivered, UI can't read) · Admin suspend/moderate (cosmetic).

### Broken / mismatched APIs
Dead frontend calls (→404/405): `/onboarding/creator/*`, `/wallet/payout-methods*`, `/wallet/recharge`, `/notifications/{id}/read`, `/notifications/read-all`, `/notifications/preferences`, `GET /meera/sessions/{id}/messages`, `/admin/support/tickets/{id}/escalate`, most of admin `escrowApi/emailApi/errorApi/marketingApi/financeApi`. Shape mismatches (→400/data loss): contracts generate/sign, deliverable submit `fileUrls`, notification envelope/fields. Unused backends: creator auth endpoints, `/auth/refresh`, `/auth/reset-password`, `/creators/search|featured|similar|suggestions`, `/campaign-templates/*`, `/workspace/members/*`, admin billing endpoints, dispute detail endpoints.

### Missing DB / model connections
Orphaned `file_uploads` table; write-orphaned `media_metrics`; `payouts` table/entity/repo unused by `PayoutService`; `release_condition` column unmapped/unused; `comp_expires_at` unenforced.

### Unused / dead code
25 of 26 notification listeners; `Deliverable.Builder` (prod); `DeliverableStatus.REJECTED`; contract COMPLETED/CANCELLED transitions; `api.notifications.*` + wallet payout-method client methods; `R2StorageService.presignPut`; large sections of `src/admin/services/api-contracts.ts`; `creator-dashboard.tsx`.

### What actually works (verified — do not "fix")
Double-entry wallet ledger with pessimistic locking + insert-first idempotency + `BigDecimal` (no double-spend/negative/float bugs); Razorpay/Shopify/WooCommerce/conversion webhook HMAC verification over raw body with constant-time compare, fail-closed; AES-256-GCM encryption of bank PII, MFA secrets, integration tokens; BCrypt-12 + common-password denylist + complexity; real RFC-6238 TOTP MFA with lockout; SSRF guard (DNS-pin, metadata-range block, per-hop redirect re-validation) in the AI service; parameterized JPQL/Criteria throughout (no SQLi found); tight CSP + explicit CORS allow-list; deal lifecycle, creator apply, reviews, admin KYC/application review, admin dispute-resolve-with-escrow, audit logging — all real and persisted; clean entity/repo/migration reconciliation (110 FKs, no enum drift).

---

## 6. Recommended Development Priority (highest → lowest)

1. **Stop money loss** — fix escrow double-charge (C1); make `payment.captured`/payout webhooks route; add fund reservation. *(Nothing else ships until this is correct.)*
2. **Restore the creator side** — real creator auth (C5) + onboarding (C6) + dashboard route (H17).
3. **Make the core marketplace loop exist** — create deliverable rows (C2), wire approval→escrow release respecting `release_condition` (C3, H5), add reject path (H6), fix contract generate/sign DTOs (C4).
4. **Connect AI** — mount routers + add config (C8, C9), fix stream token + transport (C11, C12), replace Meera placeholder (C10), add analyze-site client (H15), enforce cost gate on chat with a shared spend store (H12, H13), wire prompt-injection hardening (H14).
5. **Deliver notifications** — publish the 25 missing events (H8), enable `@EnableAsync`/`AFTER_COMMIT` (H26), fix MSG91 property binding (H25), fix the frontend notification client (C7).
6. **Make admin actions real** — enforce brand/creator suspension (C13, C14), implement content takedown (H20), wire the billing console + escalate (H21, H22), fix admin-login permitAll (H1).
7. **Harden for scale/prod** — ShedLock on all jobs + outbox `SKIP LOCKED` (H23, H24); fail-fast on placeholder secrets + prod datasource hardening (H27); invoice-number NULL-uniqueness + move invoice creation to `REQUIRES_NEW` (H10, H11); close the escrow-milestone IDOR (H3) and add `/admin/**` role matcher (H4).
8. **Finish the long tail** — real payout execution/reconciliation (H7), portfolio analytics/sync, campaign metrics, discovery/browse server-side filtering, Shopify callback HMAC, malware scanning, and the Medium/Low list.

---

*Every finding above is grounded in the source at the cited path. Percentages are engineering estimates of "implemented and correctly wired," not test coverage.*
