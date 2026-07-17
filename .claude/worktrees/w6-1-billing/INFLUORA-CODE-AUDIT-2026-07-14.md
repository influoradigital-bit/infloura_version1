# Influora — Production-Readiness Code Audit

**Date:** 14 July 2026
**Scope:** Full codebase audit from source only (no `.md` / docs / comments trusted as truth).
**Method:** Every conclusion below was reached by reading the actual source and tracing execution flow across all three tiers. Key claims were independently re-verified by grep + file reads.

**System under audit (verified from code, not docs):**

- **Frontend** — React 19 + Vite SPA (`src/`), `react-router-dom` v7, `zustand`, `@tanstack/react-query`. Single API client `src/lib/api.ts` (2,690 lines). The root `next.config.mjs` / `app/` / `lib/` / `hooks/` are **vestigial** — the live app is Vite, alias `@` → `src`.
- **Backend** — Java Spring Boot (`influora-api`), Maven. 62 controllers, ~210 endpoints, 108 services, 75 repositories, 70 entities, 74 Flyway migrations. Context path `/api/v1`.
- **AI service** — Python FastAPI (`influora-ai`), "Meera" reasoner. 8 declared routes, real Anthropic/Gemini/Sarvam SDK calls.

> **One-line verdict:** This is a *real, substantially-built* platform — the hardest parts (transactional double-entry wallet ledger, JWT/refresh auth, internal service-mesh auth, SSRF guard) are production-grade. But it is **not production-ready**. The frontend renders fabricated `mock*` data on several flagship money surfaces instead of calling the (working) API; creator authentication is a hardcoded fake; the creator-onboarding and file-upload backends don't exist; and two of five AI features are dead code killed by a config regression. A live deploy today would leave the entire creator side non-functional.

---

## Severity legend & confidence

Each issue carries **Severity** (Critical / High / Medium / Low), **Module**, **Affected user**, evidence with `file:line`, root cause, impact, fix, and **Confidence**. "Confidence: High" means I read both sides of the flow and reproduced the defect statically.

---

# CRITICAL ISSUES

## C1 — Creator authentication is a hardcoded fake; no credentials are ever verified
**Severity:** Critical **Module:** Auth **Affected:** Creator
**Problem:** Creator login and register mint `mock_creator_token` locally and never call the (existing, live) `api.auth.creatorLogin` / `creatorRegister`.
**Evidence:** `src/pages/creator-login.tsx:36-39` (`assertMockAuthAllowed(); … localStorage.setItem('creator_token','mock_creator_token')`); `src/pages/creator-register.tsx:52,66`. The real client method exists at `src/lib/api.ts:380-386` but is never imported. Brand login, by contrast, correctly calls `api.auth.brandLogin` (`src/pages/brand-login.tsx:34`).
**Root cause:** v0-generated stub auth left in place; only the brand pages were wired.
**Impact:** In a production live build, `assertMockAuthAllowed()` does **not** throw (it only throws when `PROD && !live`), so *any* email/password "logs in" with a fake token. Every subsequent request sends `Bearer mock_creator_token`, which the backend rejects → the entire creator experience 401s. There is zero credential verification.
**Fix:** Call `api.auth.creatorLogin(...)`, persist the returned token via `http.setToken('creator', token)`, surface errors.
**Confidence:** High (verified in source).

## C2 — Creator onboarding backend does not exist; frontend POSTs to phantom routes
**Severity:** Critical **Module:** Onboarding / API contract **Affected:** Creator
**Problem:** The frontend fires five creator-onboarding calls, but `OnboardingController` is `@RequestMapping("/onboarding/brand")` only. No `/onboarding/creator/*` exists anywhere in the Java source.
**Evidence:** FE `src/lib/api.ts:469,489,498,506,520` (`/onboarding/creator/{socials,profile,complete,kyc,payout}`) vs `OnboardingController.java:20,29,36,42` (brand-only). `grep "onboarding/creator" influora-api/src/main/java` → **no matches** (re-verified).
**Root cause:** Creator onboarding backend never built; the client fabricated symmetrical paths mirroring the brand flow.
**Impact:** In live mode a creator cannot connect socials, save a profile, submit KYC, or add a payout method — every onboarding call 404s. Creators are literally non-onboardable.
**Fix:** Implement `/onboarding/creator/**`, or repoint the FE to the existing `MeCreatorProfileController` (`/me/creator-profile`).
**Confidence:** High.

## C3 — Brand campaigns list is 100% fabricated data and never calls the API
**Severity:** Critical **Module:** Campaigns / Brand dashboard **Affected:** Brand
**Problem:** `/brand/campaigns` renders `allCampaigns = [demoHypeCampaign, ...mockCampaigns]` and derives every stat, filter, and count from local fixtures. `api.campaigns.list` is never called and the file does not import `@/lib/api`.
**Evidence:** `src/components/brand/campaigns/campaigns-list.tsx:54,168` (re-verified: `mockCampaigns` array + `allCampaigns`); live method exists at `src/lib/api.ts:590-604`.
**Root cause:** Page never migrated off local fixtures although the API is fully wired.
**Impact:** Every brand — including in production — sees fake campaigns ("Summer Collection", etc.), fake budgets and counts, never their real data. No loading / empty / error states.
**Fix:** `useQuery(['campaigns'], () => api.campaigns.list(params))` with proper states.
**Confidence:** High.

## C4 — Creator deal room is entirely mock; every action is a `console.log` stub
**Severity:** Critical **Module:** Deal room / negotiations **Affected:** Creator
**Problem:** The creator negotiation surface (`/creator/chat`) renders `mockDealRooms` / `mockTimelineEvents`; accept, decline, counter, deliverable-submit and shipping handlers only `console.log('[v0]…')`. No `api.*`, no `useMutation`.
**Evidence:** `src/pages/creator-chat.tsx:171,245,431` and handlers at `:505,518,530,535,540,550,557,565,572`. `api.deals`, `api.messages`, `api.deliverables` are all live.
**Impact:** Creators can "accept a deal", "send a counter", "submit a deliverable", "confirm receipt" — and nothing is saved or transmitted. The core creator workflow is theater.
**Fix:** Wire handlers to `api.deals.accept/reject/counter`, `api.messages.send`, `api.creatorDeliverables`; load the real thread.
**Confidence:** High.

## C5 — Two of five AI features are dead code (routers unregistered + config regression → ImportError)
**Severity:** Critical **Module:** AI (brand-safety, trendspark-nudge) **Affected:** Brand, Creator, AI
**Problem:** `/internal/brand-safety` and `/internal/trendspark/nudge` are never served and cannot even import. `app/main.py:36-38` registers only `chat`, `analyze_site`, `voice`. Both dead routes import `from app.costs.pricing import estimate_cost_usd`, and `pricing.py:19` imports `TRENDSPARK_MODEL` from `app.config` — which no longer defines it.
**Evidence (re-verified):** `influora-ai/app/main.py:36-38` (only 3 `include_router`); `grep "TRENDSPARK_MODEL" app/config.py` → **no match**. Additionally `trendspark.py:262` calls `claude.complete_text(...)`, a method that does not exist on `ClaudeProvider`, and the `"trendspark"` scope is missing from `ENDPOINT_SCOPES` (`service_token.py:42-50`) so it would 403 even if reachable.
**Root cause:** `config.py` was regressed — constants/Settings fields the two routes depend on (`TRENDSPARK_MODEL`, `brand_safety_max_*`, `ai_spend_*`) were removed. A stale `config.cpython-310.pyc` still references them, proving they once existed.
**Impact:** GARM brand-safety scoring and the Trend-Spark nudge are 100% non-functional in production despite ~90% of their code (and tests) being written. Their test suites fail at import.
**Fix:** Restore the missing config constants/fields; register both routers; add `ClaudeProvider.complete_text`; add the `"trendspark"` scope; add a CI boot-smoke-import of every route module.
**Confidence:** High (reproduced the `ImportError` by execution).

## C6 — No AI spend gate / cost cap on any live route; the kill-switch is inert
**Severity:** Critical **Module:** AI cost control **Affected:** System
**Problem:** The only callers of `check_spend_gate()` are the two **dead** internal routes. The live cost drivers — `/chat` (the largest spender), `/analyze-site`, `/voice/*` — never import `costs.gate` / `costs.pricing`, never gate, never record spend.
**Evidence:** `grep check_spend_gate` → only `brand_safety.py:281`, `trendspark.py:231`; `chat.py` imports no `costs.*`. `gate.py:36,44` read `settings.ai_spend_kill_switch` / `ai_daily_spend_ceiling_usd`, which no longer exist in `config.py` (AttributeError at call time).
**Impact:** No runaway-cost protection on the primary AI surface; the daily ceiling and kill-switch cannot fire. Uncapped provider spend.
**Fix:** Call `check_spend_gate()` at the top of chat/analyze/voice; record spend after each provider call; restore the config fields.
**Confidence:** High.

---

# HIGH ISSUES

## H1 — Committed dev-default signing secrets, EC/AES key material, and `root:root` DB creds in `application.yml`
**Severity:** High **Module:** Config / Secrets **Affected:** System
**Problem:** Real, usable secret values ship as `${ENV:default}` fallbacks.
**Evidence (re-verified):** `application.yml:6-7` (`username:${…:root}`, `password:${…:root}`), `:21-22` (`dev-access-secret-change-in-production-min-32-chars`, refresh secret), `:29` (Meera stream secret), `:35-36` (internal service-token + HMAC secrets); committed AES-256 admin-MFA key + EC private-key PEM as validator defaults in `SecretsStartupValidator.java:78-88`.
**Root cause:** Convenience dev defaults inlined; DB password not covered by the validator at all.
**Impact:** If any secret env var is unset (and the guard in H2 is mis-set), the committed values are used → forgeable user/internal tokens, decryptable admin TOTP, `root:root` DB login. These committed keys must be treated as compromised.
**Fix:** Remove all secret defaults (fail-fast on missing env), rotate the committed EC/AES keys, add the DB password to the validator.
**Confidence:** High.

## H2 — Secrets validator is gated on `influora.env` (default `dev`), not the active Spring profile → prod can boot on dev secrets
**Severity:** High **Module:** Config / Secrets **Affected:** System
**Problem:** `SecretsStartupValidator` only fails closed when `influora.env != "dev"`, and that value defaults to `dev` and is independent of `SPRING_PROFILES_ACTIVE`. `application-prod.yml` never sets `influora.env`.
**Evidence:** `SecretsStartupValidator.java:105` (`@Value("${influora.env:dev}")`), `:150,:183-187`; `application-prod.yml` sets only `on-profile: prod`.
**Impact:** A deploy with `SPRING_PROFILES_ACTIVE=prod` but no `APP_ENV=prod` silently degrades every fail-closed check (dev-secret detection, <32-byte rejection, secure-cookie enforcement, webhook-secret presence) to a log line — combined with H1, prod signs JWT/HMAC with world-readable committed secrets, enabling forged ADMIN tokens.
**Fix:** Derive `isDev` from `Environment.getActiveProfiles()`; fail closed when `influora.env` is unset while a non-dev profile is active.
**Confidence:** High.

## H3 — All non-Razorpay webhooks + the JWKS endpoint sit behind `authenticated()` and will 401
**Severity:** High **Module:** Security / Integrations **Affected:** System, Creator
**Problem:** In `SecurityConfig`, only `POST /webhooks/razorpay` is `permitAll()`; everything else falls to `.anyRequest().authenticated()`. But these external endpoints carry no Bearer token, so the JWT filter never authenticates them and they 401 before reaching the controller.
**Evidence (re-verified):** `SecurityConfig.java:73-89` — permitAll covers `/health`, `/auth/**`, `workspaces/slug-check`, `/webhooks/razorpay` only, then `.anyRequest().authenticated()`. Unreachable as a result: `POST /webhooks/shopify`, `/webhooks/woocommerce`, `/webhooks/redemption`, `/webhooks/conversion`, `GET /track/click/{utmCampaignId}`, and `GET /.well-known/jwks.json` — each of which verifies its own trust boundary (HMAC / coupon signature) and needs no JWT.
**Impact:** Coupon-redemption and conversion attribution and affiliate click tracking are completely non-functional in production (creators never get affiliate credit); Shopify/WooCommerce order webhooks are dropped; and the JWKS endpoint the AI service needs to verify Spring tokens is unreachable.
**Fix:** `permitAll` the four additional webhook POSTs, the click-tracking GET, and `/.well-known/jwks.json`.
**Confidence:** High.

## H4 — Brand and Creator wallet pages render hardcoded balances/transactions; never call `api.wallet`
**Severity:** High **Module:** Wallet / Billing **Affected:** Brand, Creator
**Problem:** Both wallet screens display local `mockWalletData` / `mockEarningsData` / `mockTransactions` / `mockEscrowItems` / `mockPayouts` for all money figures. `api.wallet.get/transactions/withdraw` (`api.ts:1059-1148`) is unused.
**Evidence:** `src/pages/brand-wallet.tsx:108,135,211,269,514`; `src/pages/creator-wallet.tsx:59,67,110,118,140,186-195`.
**Impact:** Users see fabricated balances, runway, escrow holds, payouts and tax docs. "Recharge" / "Withdraw" are visually present but disconnected. Fabricated money figures on a payments platform are a trust/compliance risk.
**Fix:** Fetch via `api.wallet.*`; wire top-up (`useWalletTopUp` exists) and withdraw.
**Confidence:** High.

## H5 — Brand contracts & deliverables page is hardcoded `mockContracts`; no API
**Severity:** High **Module:** Contracts / Deliverables **Affected:** Brand
**Problem:** `/brand/contracts` selects and filters `mockContracts`; never calls `api.contracts.list` or `api.deliverables.list` (both live).
**Evidence:** `src/components/brand/contracts/contracts-and-deliverables.tsx:107,322,338`.
**Impact:** Sign / approve / revise operate on mock objects; `api.contracts.sign` and `api.deliverables.approve` are unused.
**Fix:** Load real contracts/deliverables and wire the mutations.
**Confidence:** High.

## H6 — Brand dashboard silently falls back to fabricated action items / pipeline on empty or error
**Severity:** High **Module:** Brand dashboard **Affected:** Brand
**Problem:** State seeds with `mockActionItems` / `mockPipeline`; live data overwrites only when the arrays are non-empty; on error the catch just `console.error`s and keeps the mock.
**Evidence:** `src/components/brand/dashboard/dashboard-page.tsx:139-141,154,166,169-171`.
**Impact:** A real brand with an empty dashboard (new account) or a backend error sees fake action cards ("Review deliverable from Priya Sharma") and fake pipeline counts. No empty/error UI.
**Fix:** Always assign the API result (even `[]`); add explicit loading/empty/error states; drop the mock seeds in live.
**Confidence:** High.

## H7 — Brand deal room list, timeline, and proposals are mock; only message fetch is live
**Severity:** High **Module:** Deal room **Affected:** Brand
**Problem:** `/brand/chat` sidebar and timeline come from `mockDealRooms` / `mockTimelineEvents`; proposal send and contract/deliverable status are local; only `GET /deals/:id/messages` is wired.
**Evidence:** `src/pages/brand-chat.tsx:107,179,352,403,439,638` (`// In real app: … call API`).
**Impact:** Brand sees fabricated deal rooms; "Proposal sent" never persists; the deal list never reflects `api.deals.list('brand')`.
**Fix:** Source the deal list from `api.deals.list`; timeline/proposals from live endpoints.
**Confidence:** High.

## H8 — No access-token refresh flow; sessions hard-break on token expiry
**Severity:** High **Module:** Auth **Affected:** Brand, Creator
**Problem:** The SPA stores only the short-lived access token; the HTTP client throws on any non-OK (including 401) with no refresh/retry, and nothing calls `/auth/refresh` for brand/creator (only the admin client defines one).
**Evidence:** `src/lib/api.ts:159-183` (no 401 branch); `src/lib/auth-session.ts` (no refresh); the HttpOnly refresh cookie is sent via `credentials:'include'` but never exchanged.
**Impact:** When the access token expires mid-session, every request 401s and the user is silently broken until manual re-login.
**Fix:** Add a 401 interceptor that POSTs `/auth/refresh` once (cookie-based) and retries; on failure clear the session and redirect to login.
**Confidence:** High.

## H9 — Admin console has no mock mode → non-functional in the default (mock) deployment
**Severity:** High **Module:** Admin **Affected:** Admin
**Problem:** `src/admin/services/api-contracts.ts` always does a real `fetch` to `/api/v1/admin/**` with no `isLive()` fallback (unlike the brand/creator client).
**Evidence:** `src/admin/services/api-contracts.ts:63-88`; every admin hook calls it.
**Impact:** The app ships defaulting to `VITE_API_MODE=mock`; in that mode the entire admin panel errors on every data call. Admin works only against a live backend.
**Fix:** Add a mock layer, or make admin explicitly require live mode and surface clear error states.
**Confidence:** High.

## H10 — Payout-method management endpoints (`/wallet/payout-methods`) do not exist on the backend
**Severity:** High **Module:** Wallet / Payouts **Affected:** Creator
**Problem:** The FE reads/writes payout instruments via `/wallet/payout-methods`; `WalletController` exposes only `/balance`, `GET /wallet`, `/topup`, `/withdraw`, `/transactions`.
**Evidence:** `src/lib/api.ts:1120,1134,1146` vs `WalletController.java:51,63,85,106,122`. The encrypted `CreatorBankAccount` entity/service exists but was never exposed via HTTP.
**Impact:** Creators cannot add/list/select a bank/UPI destination in live mode; the withdrawal flow has no registrable target.
**Fix:** Add a `CreatorBankAccountController` at `/wallet/payout-methods` backed by the existing encrypted service.
**Confidence:** High.

## H11 — Generic `POST /uploads` (logo, KYC docs, deliverable files) has no controller
**Severity:** High **Module:** File upload **Affected:** Brand, Creator
**Problem:** `uploads.upload` posts multipart to `/uploads`; no controller maps it (only scoped routes like `/creator/deliverables/{id}/upload` and `/me/portfolio/cover` exist).
**Evidence (re-verified):** `src/lib/api.ts:1649` (`http.upload('/uploads', …)`); `grep '"/uploads"' influora-api/src/main/java` → **no match**.
**Impact:** Company-logo upload, KYC-document upload, and any flow that first calls `uploads.upload` to obtain `{url,key}` all fail in live mode.
**Fix:** Implement `POST /uploads` (S3/R2 presign + store), or route each caller to a scoped endpoint.
**Confidence:** High.

## H12 — Escrow funding requires wallet balance *and* a second Razorpay charge, then debits the wallet (double-charge / contradictory flow)
**Severity:** High **Module:** Payments / Escrow **Affected:** Brand
**Problem:** `initiateFund` rejects unless the wallet balance ≥ amount (`EscrowService.java:146-150`), then creates a *new* Razorpay order for the same amount (`:165`); on the payment webhook `confirmFunded` **debits the brand wallet** and credits the clearing wallet (`:239-250`). The captured external payment is never credited anywhere — it is only used as a gate.
**Evidence:** `EscrowService.java:146-167`, `:218-253`. Separately, `WalletTopUpService` is the real "money-in" path.
**Impact:** A brand that topped up its wallet must pay a *second* time through the escrow order, and its wallet is still debited — an economic double-charge; or, if the Razorpay order is the intended funding, the wallet debit is spurious. Either way the escrow ledger diverges from the brand's real cash position.
**Fix:** Choose one model. Wallet-funded → drop the Razorpay order, debit synchronously in `initiateFund`. Externally-funded → `confirmFunded` should credit from the captured payment and remove the balance precondition.
**Confidence:** Medium.

## H13 — AI chat & site-analysis use the known-bypassable prompt-injection wrapper, not the hardened one
**Severity:** High **Module:** AI security **Affected:** AI, Brand
**Problem:** `app/prompt/untrusted.py` documents a hardened `neutralize_angle_brackets` wrap (added after a red-team finding that the old approach was bypassable via case-variation / split-rejoin). But `assembler.py` never adopted it — it still does a single case-sensitive `content.replace("</untrusted_{label}>", "")` and doesn't import the hardened helper. This `_wrap_untrusted` wraps every chat user turn and every scraped site body.
**Evidence:** `app/prompt/assembler.py:68` (imports only `persona`, `schemas`); contrast `app/prompt/untrusted.py:14-44`.
**Impact:** A user message or scraped page containing `</UNTRUSTED_USER_MESSAGE>` (case variant) or split-rejoin can escape the data envelope and inject instructions into Meera — exactly the bypass class the fix was written for.
**Fix:** Replace `assembler._wrap_untrusted` with `app.prompt.untrusted.wrap_untrusted`.
**Confidence:** High.

## H14 — Voice upload has no size or content-type limit (memory-exhaustion DoS)
**Severity:** High **Module:** AI / Voice **Affected:** Creator, System
**Problem:** `/voice/transcribe` does `audio_bytes = await audio_file.read()` with no cap and no MIME check; a multi-GB "audio" field is read whole into memory and forwarded to Sarvam.
**Evidence:** `app/routes/voice.py:122`; no size/type setting exists.
**Impact:** Any valid-token caller can exhaust worker memory and waste Sarvam spend.
**Fix:** Stream-read with a hard byte cap (e.g. 10 MB), reject on exceed, validate content-type against an allow-list.
**Confidence:** High.

## H15 — NotificationBell uses a divergent hook: wrong base URL, brand-only token, unimplemented backend
**Severity:** High **Module:** Notifications **Affected:** Brand, Creator
**Problem:** `useNotifications` bypasses `api.ts` and does raw `fetch('/api/v1/notifications')` with a hardcoded relative URL and `Bearer brand_token`, tagged TODO. The properly-wired `api.notifications.*` (`api.ts:1342-1381`) is unused.
**Evidence:** `src/hooks/useNotifications.ts:118-126`; consumer `src/components/feature/meera/NotificationBell.tsx:17,115`.
**Impact:** Ignores `VITE_API_BASE_URL` (breaks when the API host ≠ origin) and always sends `brand_token`, so a creator's bell is unauthenticated.
**Fix:** Delete the raw fetch; use `api.notifications.list/markRead(role)`.
**Confidence:** High.

---

# MEDIUM ISSUES

## M1 — `payout.processed` / `payout.reversed` webhooks are a no-op; reversals never reconciled
**Severity:** Medium **Module:** Payout **Affected:** Creator, System
**Problem:** `RazorpayWebhookController.java:61` routes both events to `payoutService.confirmExecuted(...)`, whose body is empty (`PayoutService.java:263-270`, comment: "no payouts table exists in this slice").
**Impact:** A RazorpayX payout that later **reverses** (failed bank/UPI transfer) is silently ignored — funds were already moved into the creator's Influora wallet at release time, so the creator keeps a balance the platform never delivered (unreconciled loss). Payout status also never advances past "queued".
**Fix:** Add a `payouts` table keyed by RazorpayX id; mark PROCESSED / reverse the ledger movement accordingly.
**Confidence:** High.

## M2 — Admin CEO dashboard returns hardcoded zero revenue and zero trend deltas
**Severity:** Medium **Module:** Admin **Affected:** Admin
**Problem:** `AdminDashboardStatsCache.java:82-87` builds `CeoPulseDataDto` with `revenue = ZERO`, `revenueChange/gmvChange/activeCampaignsChange = 0.0` hardcoded, backing the live CEO-pulse endpoint.
**Impact:** The admin dashboard always shows platform revenue = 0 and all period-over-period changes = 0 — actively misleading to operators.
**Fix:** Compute revenue from realized platform-fee ledger entries; add a KPI snapshot table for deltas.
**Confidence:** High.

## M3 — `@Scheduled` money jobs have no distributed lock (multi-instance double-run)
**Severity:** Medium **Module:** Background jobs **Affected:** System
**Problem:** 12 cron jobs run via `@EnableScheduling`; there is **no** ShedLock / `@SchedulerLock` anywhere. Guards like `AffiliateSettlementJob.running` are a per-JVM `AtomicBoolean` (`AffiliateSettlementJob.java:131`).
**Impact:** On any horizontally-scaled deployment (>1 instance) every node fires each job simultaneously. Idempotency keys protect settlement/reconciliation, but jobs without them (dunning emails, metric polling) duplicate side effects.
**Fix:** Add DB-backed ShedLock, or pin jobs to a single instance.
**Confidence:** Medium.

## M4 — Notification "mark-all-read" and email-preference endpoints don't exist (path/verb mismatch)
**Severity:** Medium **Module:** Notifications **Affected:** Brand, Creator
**Problem:** FE calls `POST /notifications/read-all`, `GET/POST /notifications/preferences`; backend exposes only `GET`, `POST /read`, `POST /unsubscribe`.
**Evidence:** `src/lib/api.ts:1357,1369,1376` vs `NotificationController.java:51,71,97`.
**Impact:** "Mark all as read" and the preferences screen 404 in live mode; the unsubscribe toggle is unreachable via the FE.
**Fix:** Add the missing endpoints, or repoint `setPreference` to `/notifications/unsubscribe`.
**Confidence:** High.

## M5 — Meera chat history load (`GET /meera/sessions/:id/messages`) has no mapping
**Severity:** Medium **Module:** Meera AI chat **Affected:** Brand
**Problem:** `meera-api.ts:376-379` loads history via GET; `MeeraController.java:81` maps only `POST /meera/sessions/{id}/messages`.
**Impact:** Reopening a Meera conversation cannot fetch prior turns in live mode (404) — the history panel breaks.
**Fix:** Add the GET-messages mapping.
**Confidence:** High.

## M6 — Five admin dashboard/finance/support endpoints are called but not implemented
**Severity:** Medium **Module:** Admin **Affected:** Admin
**Problem:** The admin client calls `/dashboard/marketing`, `/campaigns/at-risk`, `/campaigns/hype/ops`, `/finance/escrow`, `/support/stats` — none of which have a backend controller.
**Evidence:** `src/admin/services/api-contracts.ts:147,311,320,334,529` vs `AdminDashboardController.java:39,44` (pulse/operations only), `AdminSupportController.java:46` (tickets only), etc.
**Impact:** Those admin panels 404 in live mode.
**Fix:** Implement the five endpoints or hide the panels.
**Confidence:** High.

## M7 — `POST /wallet/recharge` has no backend (superseded by `/topup`)
**Severity:** Medium **Module:** Wallet **Affected:** Brand
**Problem:** FE `wallet.recharge` posts `/wallet/recharge`; `WalletController` has `/topup` (Razorpay) but no `/recharge`.
**Evidence:** `src/lib/api.ts:1087` vs `WalletController.java:85`.
**Fix:** Remove `wallet.recharge` or alias it to `topUp`.
**Confidence:** High.

## M8 — Per-IP auth/OTP throttle trusts unvalidated `X-Forwarded-For`
**Severity:** Medium **Module:** Rate limiting / Security **Affected:** All
**Problem:** `clientIp()` returns the first XFF token with no trusted-proxy allowlist.
**Evidence:** `AuthRateLimitFilter.java:402-409`.
**Impact:** An attacker rotates `X-Forwarded-For` per request for a fresh bucket, defeating login/OTP/reset brute-force defense (10/min sensitive, 5/min OTP).
**Fix:** Honor XFF only from configured trusted proxies (`forward-headers-strategy` / `RemoteIpValve`); otherwise use `getRemoteAddr()`.
**Confidence:** High (trust confirmed); exploitability network-dependent.

## M9 — Malware scanning is a no-op in every profile, including prod, on 500 MB–1 GB uploads
**Severity:** Medium **Module:** File upload / Security **Affected:** Brand, Creator
**Problem:** `NoOpMalwareScanService` is the only `MalwareScanService` and is an unguarded `@Service` (no `@Profile`), so it is the prod bean; `requireClean()` always passes.
**Evidence (re-verified):** `NoOpMalwareScanService.java:14-15` (no `@Profile`); wired at `CreatorDeliverableService.java:72`, `PortfolioService.java:87`; caps `max-file-size:500MB` / `max-request-size:1GB` (`application.yml:32-33`).
**Impact:** Hostile files up to 1 GB pass with only MIME/size checks and become brand-visible.
**Fix:** Ship a real scanner for non-dev, or gate the no-op with `@Profile("dev")` so prod refuses to start without a real bean.
**Confidence:** High.

## M10 — `payouts` table + `Payout` entity + `PayoutRepository` are fully orphaned (dead table)
**Severity:** Medium **Module:** Database **Affected:** System
**Problem:** `Payout` / `PayoutRepository` / the `payouts` table are defined and migrated but never read or written; payout state lives on `PaymentMilestone` / `EscrowHold` instead.
**Evidence:** `PayoutRepository.java:7` is the only reference (0 injections); `V48__payouts.sql` never populated.
**Impact:** No dedicated payout audit row; misleads reporting and future devs; `validate` ddl-auto maintains the empty table forever.
**Fix:** Either persist a payout row in `PayoutService.initiatePayout`, or drop the entity/repo/table.
**Confidence:** High.

## M11 — Sarvam TTS returns the raw HTTP body as audio, but the API replies JSON(base64) → voice output likely corrupt
**Severity:** Medium **Module:** AI / Voice **Affected:** Creator
**Problem:** `speak()` returns `response.content` as `audio/wav`, but Sarvam TTS returns `{"audios":["<base64 wav>"]}`.
**Evidence:** `app/providers/sarvam.py:119-135`.
**Impact:** `/voice/speak` never emits valid audio (a silent failure that isn't caught as a fallback).
**Fix:** `data = response.json(); audio_bytes = base64.b64decode(data["audios"][0])`.
**Confidence:** Medium (external API shape not in-repo; strongly indicated).

## M12 — SSRF guard enforces the response-size cap *after* buffering the entire body
**Severity:** Medium **Module:** AI / SSRF **Affected:** System
**Problem:** The guard reads `response.content` (full buffer) and only then checks `len(body) > max_bytes`.
**Evidence:** `app/security/ssrf_guard.py:159-184`.
**Impact:** A host that omits `Content-Length` and streams an unbounded body exhausts memory before the check runs, despite an otherwise strong SSRF guard.
**Fix:** Use `client.stream()` and abort once accumulated bytes exceed the cap.
**Confidence:** High.

## M13 — Blocking `guarded_fetch` called inside the async `/analyze-site` handler stalls the event loop
**Severity:** Medium **Module:** AI **Affected:** System
**Problem:** `analyze_site` is `async def` but calls the synchronous `guarded_fetch` (sync `httpx.Client`, up to 15 s per hop), blocking the single asyncio loop for the whole fetch.
**Evidence:** `app/routes/analyze_site.py:92`; `ssrf_guard.py:149`.
**Impact:** One slow site fetch freezes all concurrent `/chat` streams (head-of-line blocking).
**Fix:** Use `httpx.AsyncClient`, or offload via `anyio.to_thread.run_sync`.
**Confidence:** High.

## M14 — AI chat assistant turns are fire-and-forget; disconnect or Spring failure silently loses billed output
**Severity:** Medium **Module:** AI **Affected:** Brand
**Problem:** Persistence to Spring happens only after the stream completes and only if `not disconnected`; a mid-stream disconnect returns early and never persists the generated (provider-billed) text; a persistence throw is swallowed as a WARNING with no retry.
**Evidence:** `app/routes/chat.py:198-224`.
**Impact:** Lost conversation history (turns the user saw vanish), especially on flaky mobile; a Spring/DB blip → silent data loss.
**Fix:** Persist accumulated text on disconnect too; add a durable outbox/retry.
**Confidence:** High.

## M15 — AI misconfig boots "ready" then 500s every request; dev HS256 secret accepted for boot in any env
**Severity:** Medium **Module:** AI / Auth **Affected:** System
**Problem:** `require_boot_secrets` passes if `spring_jwks_url OR dev_shared_jwt_secret` is set (`config.py:189`); `/readyz` only checks that bool. In non-dev with no JWKS but a `DEV_SHARED_JWT_SECRET`, boot and health pass, but the first request constructs `StaticDevJwksSource`, whose `__init__` raises when env≠dev, and `chat.py:83` only catches `AuthError` → propagates as 500.
**Impact:** A silently-broken prod deploy that passes health checks.
**Fix:** Require `spring_jwks_url` when `env != "dev"` in `require_boot_secrets`.
**Confidence:** High.

## M16 — Subscription upgrade/cancel and admin comp are not implemented
**Severity:** Medium **Module:** Billing **Affected:** Brand, Admin
**Problem:** `billing.initiateCheckout` / `cancelSubscription` reject `NOT_YET_IMPLEMENTED` (backend stub also throws); admin "grant comp" is a no-op toast.
**Evidence:** `src/lib/api.ts:1503-1513`; `src/admin/components/billing/BillingConsole.tsx:217,255-266`.
**Impact:** Brands cannot upgrade to PRO or cancel; admins cannot grant credits — presented in the UI as available.
**Fix:** Gate/disable these CTAs until the Razorpay Subscriptions backend ships.
**Confidence:** High.

## M17 — Brand analytics roster is hardcoded demo creators; live metrics query fake IDs
**Severity:** Medium **Module:** Analytics **Affected:** Brand
**Problem:** `roster = demoCreators`; the selected demo `creatorId` is passed to the live `useCreatorMetrics` hook; demographics are permanently "Coming soon".
**Evidence:** `src/pages/brand-analytics.tsx:49-61`; `src/pages/brand-creator-analytics.tsx:54,168-172`.
**Impact:** In live mode analytics are fetched for fabricated creator IDs → empty/garbage.
**Fix:** Add/consume a real tracked-creator roster endpoint.
**Confidence:** High.

## M18 — Creator affiliate earnings and tax-identity: shells with no wiring / no backend
**Severity:** Medium **Module:** Creator earnings / Tax **Affected:** Creator
**Problem:** `/creator/affiliate` renders a "Coming soon" card although `api.affiliateEarnings.get` (`api.ts:2393-2402`) and `useAffiliateEarnings` both exist and are unused. Separately, `creatorTaxIdentity.submit` unconditionally rejects `NOT_IMPLEMENTED` (no endpoint, `api.ts:1631-1639`).
**Impact:** Creators can't see affiliate commission though the plumbing exists; and cannot submit GSTIN/PAN, blocking marketplace invoicing.
**Fix:** Wire `useAffiliateEarnings`; build the tax-identity backend endpoint.
**Confidence:** High.

## M19 — Content-moderation action from the unified approval queue throws 501
**Severity:** Medium **Module:** Admin / Moderation **Affected:** Admin
**Problem:** `ApprovalWorkflowService.act(...)` for `TYPE_CONTENT_MODERATION` throws `APPROVAL_ACTION_NOT_IMPLEMENTED`.
**Evidence:** `ApprovalWorkflowService.java:173-179`.
**Impact:** Admins cannot action content flags through the approval workflow — a moderation gap.
**Fix:** Implement content-flag actioning or hide the action.
**Confidence:** High.

## M20 — Fake-follower / authenticity detection is a deliberate placeholder feeding real scores
**Severity:** Medium **Module:** Scoring **Affected:** Creator, Brand
**Problem:** `FakeFollowerDetectionService` is coded as "deliberately not implemented" yet emits a computed-looking authenticity signal used in discovery/vetting.
**Evidence:** `FakeFollowerDetectionService.java:100-110`.
**Impact:** Brands may rely on a placeholder as if it were fraud detection.
**Fix:** Implement, or clearly mark scores "not yet assessed".
**Confidence:** Medium.

## M21 — Disputes derive from `/deals` only; live dispute detail is missing
**Severity:** Medium **Module:** Disputes **Affected:** Brand, Creator
**Problem:** `creatorDisputes.list` / `brandDisputes.list` filter `deals.list` for `DISPUTED` and leave `reason`, `openedAt`, `disputeStatus`, and resolution undefined in live mode (real `GET /brand|creator/disputes` endpoints exist but are unused by design).
**Evidence:** `src/lib/api.ts:2514-2579`.
**Impact:** Live dispute pages show only partial rows behind a perpetual "partial data" banner.
**Fix:** Consume the real dispute detail endpoints.
**Confidence:** High.

## M22 — Two complete backend features are unreachable from any frontend (orphan APIs)
**Severity:** Medium **Module:** Workspaces / Templates **Affected:** Brand
**Problem:** The entire `WorkspaceMemberController` (team seats: invite/accept/list/remove/switch) and `CampaignTemplateController` (list/get/create/delete) have zero FE callers, yet billing advertises `seatLimit`/`activeSeatsUsed` and `campaignTemplatesEnabled`.
**Evidence:** `WorkspaceMemberController.java:42-104`, `CampaignTemplateController.java:32-62`; no matching paths in any FE client.
**Impact:** Paid seat/agency and template functionality are unreachable — billed features with no UI path.
**Fix:** Build the FE clients or descope the plan flags.
**Confidence:** High.

---

# LOW ISSUES

- **L1 — Route guards are token-presence-only; `/brand/onboarding` is unguarded.** `ProtectedRoute` checks only `localStorage` token existence (no shape/expiry); onboarding has no wrapper. Server still enforces, so it's a UX/leak issue. `src/App.tsx:64-72,84-89,125`. Confidence High.
- **L2 — `POST /escrow/payout` requires only workspace membership, not OWNER/ADMIN.** Any member can trigger the bank payout (payee/amount are server-derived, so limited blast radius). `PayoutService.java:98-99` vs `EscrowService.java:279,357`. Confidence High.
- **L3 — 33 `console.*` incl. `[v0]` debug logs shipped.** Deal-room and form flows. Potential data leakage in console. Confidence High.
- **L4 — Public legal pages are placeholders.** `/terms`, `/privacy`, `/support` render "being finalized" stubs — a launch/compliance blocker for a payments platform. `src/App.tsx:477-503`. Confidence High.
- **L5 — Portfolio link-click metrics hardcoded to empty.** `PortfolioService.java:222` returns `List.of()`; creator portfolio analytics show zero clicks as if real. Confidence Medium.
- **L6 — Placeholder video URLs rendered as deliverables.** `fileUrl:'/placeholder-video.mp4'` in `creator-chat.tsx:364,388`. Confidence High.
- **L7 — Creator "Forgot password?" is a dead no-op** (`type="button"`, no handler; no creator reset route). `creator-login.tsx:114-119`. Confidence High.
- **L8 — `useAuth` maintains a parallel brand-session notion** separate from token storage; `user` can desync from the real token. `src/hooks/useAuth.ts`. Confidence Medium.
- **L9 — Dead directories & orphan pages.** Root `/app`, `/lib`, `/hooks` are legacy duplicates unused by `src`; orphan page files (`admin-dashboard.tsx` demo, `creator-dashboard.tsx`, redirect-only pages) risk editing the wrong file. Confidence High.
- **L10 — CORS `allowCredentials(true)` + wildcard `allowedHeaders("*")`, no boot-time origin validation.** Low as shipped (origin default is a localhost allowlist, not `*`); risk is silent operator misconfiguration. `CorsConfig.java:25-27`. Confidence High (behavior).
- **L11 — Hikari pool minimally tuned** (only `maximum-pool-size`); no `connection-timeout`/`max-lifetime`/`leak-detection-threshold`. `application.yml:8-9`. Hardening, not a bug. Confidence High.
- **L12 — `/readyz` claims "provider reachability" but only checks keys are non-empty.** False readiness signal. `app/main.py:62-85`. Confidence High.
- **L13 — AI circuit-breaker `half_open_max_calls` is unused** → no probe-concurrency limit on recovery (thundering herd). `claude.py:45-51`. Confidence Medium.
- **L14 — Many implemented backend endpoints have no FE caller** (`GET /wallet/balance`, `contracts/unsigned`, `contracts/:id/pdf-download-url`, `escrow/refund`, `creators/{featured,suggestions,similar,search}`, `campaigns/:id/analytics`, `campaigns/:id/export`, `auth/reset-password`, etc.). Dead/partially-shipped surface. Confidence High.

---

# Verified strengths (so the report is even-handed)

These were read and confirmed as genuinely well-built, not assumed:

- **Wallet money ledger** (`WalletLedgerService.post`) — true double-entry, `SELECT … FOR UPDATE` on both wallets in sorted order (deadlock-safe), balance + currency checks, DB-unique idempotency with insert-first-catch-and-refetch, replay cross-check. Production-grade.
- **Escrow/top-up webhooks** cross-check the webhook amount/currency against the persisted hold before flipping to FUNDED and fail closed on missing amounts.
- **Razorpay webhook** HMAC-SHA256 with constant-time compare, fail-closed on missing secret; Shopify webhook verifies HMAC before parsing.
- **Auth** — bcrypt(12), refresh-token rotation + single-use + revoke-all on logout/reset, HttpOnly `SameSite=Strict` path-scoped refresh cookie, generic `INVALID_CREDENTIALS` (no user enumeration).
- **Internal service mesh** — `/internal/**` requires a short-TTL service JWT **plus** an HMAC request signature **plus** a replay nonce; not bypassable via body-supplied tokens.
- **AI core** — real Anthropic/Gemini/Sarvam SDK calls (no fake completions), a terminating tool loop that never trusts AI-supplied amounts, real SSE streaming with JWKS RS256/ES256 auth, and a strong SSRF guard (DNS-pin, private/metadata blocklist, redirect re-validation).
- **Database** — `ddl-auto=validate` in every profile (no data-loss risk), 74 Flyway migrations, `open-in-view:false`, 69/70 entities repository-backed, FKs modeled as scalar id columns (so no `mappedBy`/cascade/N+1 relationship bugs).

---

# FINAL SUMMARY

### 1. Overall Project Health Score: **60 / 100**
A strong backend/auth/ledger core dragged down by disconnected frontends, missing creator backends, committed secrets, and dead AI features. High engineering quality in parts, low integration completeness overall.

### 2–8. Scorecard

| Dimension | Score | Basis |
|---|---|---|
| **Feature completion** | **62%** | Most features exist somewhere in code; many are not end-to-end wired. |
| **Frontend completion** | **UI built ~90% / functionally wired ~55%** | Client (`api.ts`) is ~90% live-capable; flagship pages render `mock*` instead of calling it. |
| **Backend completion** | **80%** | Auth ~90%, wallet/escrow ~80%, campaigns/deals ~80%, admin ~70%, integrations ~60% (webhook routes 401), jobs ~80% (no distributed lock). |
| **AI integration** | **55%** | chat/site connected (with cost + injection regressions); voice connected but TTS broken; brand-safety + trendspark 100% dead. |
| **Database health** | **90%** | Migration-managed, `validate`, all-but-one entity live; one orphan table + minimal pool tuning. |
| **Security** | **70%** | Hard parts strong; pulled down by committed secrets, bypassable env gate, XFF trust, no-op prod malware scan. |
| **Production readiness** | **42%** | Multiple launch blockers: fake creator auth, mock money screens, missing creator backends, dead AI, committed secrets. |

### 9–12. Issue counts
- **Critical: 6**
- **High: 15**
- **Medium: 22**
- **Low: 14**

### 13. Missing features (UI/expectation exists, implementation absent)
Creator onboarding backend (C2); `/uploads` file endpoint (H11); `/wallet/payout-methods` (H10); notification read-all + preferences (M4); Meera history GET (M5); 5 admin endpoints (M6); subscription checkout/cancel + admin comp (M16); creator tax-identity backend (M18); content-moderation actioning (M19); real fake-follower detection (M20); brand tracked-creator roster (M17).

### 14. Broken code-flow list (UI renders but nothing happens / wrong data)
Creator auth (C1); brand campaigns list (C3); creator deal room (C4); brand + creator wallets (H4); brand contracts/deliverables (H5); brand dashboard mock-fallback (H6); brand deal room (H7); NotificationBell (H15); no token refresh (H8); admin console has no mock mode (H9).

### 15. Broken / missing API list (frontend → no backend)
`/onboarding/creator/*`; `/wallet/payout-methods`; `/uploads`; `/notifications/read-all` + `/preferences`; `GET /meera/sessions/:id/messages`; admin `/dashboard/marketing`, `/campaigns/at-risk`, `/campaigns/hype/ops`, `/finance/escrow`, `/support/stats`; `/wallet/recharge`. AI: `/internal/brand-safety` + `/internal/trendspark/nudge` (unregistered).

### 16. Missing / broken database connections
Orphan `payouts` table + `Payout` entity + `PayoutRepository` (M10); payout reversal never reconciled to the ledger (M1); admin revenue not derived from the fee ledger (M2).

### 17. Unused code
Root `/app`, `/lib`, `/hooks` legacy dirs; orphan pages (`admin-dashboard.tsx`, `creator-dashboard.tsx`, redirect-only pages); orphan controllers (`WorkspaceMemberController`, `CampaignTemplateController`, most of `CreatorDeliverableController`, `DeliverableMetricController`); ~14 orphan endpoints (L14); AI `neutralize_angle_brackets_REMOVED_*` stub; unused `half_open_max_calls`.

### 18. Dead components / features
AI brand-safety and trendspark-nudge (C5); creator affiliate-earnings shell (M18); billing subscription CTAs (M16); creator forgot-password (L7); legal/support pages (L4).

### 19. Recommended development priority (highest → lowest)
1. **Security blockers first:** remove committed secrets + rotate keys (H1), fix the env-gate so prod fails closed (H2), permitAll the webhooks/JWKS (H3). *(config-only, high blast radius)*
2. **Make the creator side exist:** real creator auth (C1), creator onboarding backend (C2), `/uploads` (H11), `/wallet/payout-methods` (H10).
3. **Stop showing fake money/data:** wire campaigns list (C3), both wallets (H4), contracts/deliverables (H5), dashboard (H6), both deal rooms (C4/H7) to the live API; add token refresh (H8).
4. **Fix the AI regressions:** restore `config.py`, register + repair brand-safety/trendspark (C5), add spend gates to live routes (C6), adopt the hardened injection wrap (H13), cap voice uploads (H14).
5. **Reconcile payments correctness:** resolve the escrow double-charge (H12), reconcile payout reversals (M1), real admin revenue (M2).
6. **Ops hardening:** distributed job locks (M3), XFF trust (M8), real malware scanning (M9), AI readiness/boot checks (M12/M15), streaming SSRF cap (M12), async fetch (M13).
7. **Cleanup & polish:** remaining Medium/Low items, dead code removal, legal pages, console-log stripping.

---
*Prepared from source only. No `.md`, `.docx`, `.pdf`, README, or code-comment claim was treated as evidence of a working feature; every finding cites the file and line where the behavior actually lives.*
