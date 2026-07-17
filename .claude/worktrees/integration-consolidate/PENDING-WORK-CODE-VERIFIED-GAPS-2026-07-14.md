# Influora — Pending Work: CODE-VERIFIED GAPS **Not in Existing Trackers**

**Date:** 2026-07-14 · **Method:** Source-only audit (8 parallel agents; every Critical re-verified by direct `grep`/file read). Cross-referenced against your 10 tracker docs — items already listed there are **excluded** (see §0). Everything below is code-proven and **absent** from `PENDING-WORK*.md`, `PRIYA-CTO-*.md`, `API-CONNECTION-*.md`, `ADMIN_PENDING_WORK_LOOP.md`, `SHARED_CONTEXT.md`.
**Pipeline (unchanged):** Vikram/Ananya/Ash → Kavya QA → Kabir (money/KYC/auth) → Meera build → Priya sign-off.
**Gate rule:** nothing marked `[x]` without file-anchored evidence.

> **Why this doc exists:** your trackers say the remaining work is "verification + deploy" (~85%) and that the compile break is a vague "Maven-gated / ~40 errors from lost edits." That is optimistic. The build fails on **6 specific missing symbols** (§A), and there are **~45 code-level defects** — mostly in money, the post-agreement pipeline, notifications, security config, and the AI service — that no tracker records. These are new features/fixes, not env flips.

---

## §0. Already tracked in your docs — NOT repeated below

For transparency, these are real but **already** in your ledgers, so they're out of scope here: mock→live swaps for brand/creator wallet, contracts, campaign list/detail, messages, profile (`API-CONNECTION-PENDING.md`, `PENDING-WORK-CREATOR-BRAND-*.md`); missing `DealController`/`DashboardController`/`PortfolioController`/`DealMessage` + creator auth/onboarding routes (`PRIYA-CTO-FEATURE-HEALTH-*`); admin panel unmounted + dead admin endpoints + hardcoded-0 admin metrics + `AdminBillingController` (`PRIYA-CTO-ADMIN-PENDING-WORK`, `ADMIN_PENDING_WORK_LOOP`); `.env` provider keys + `register-test-brand.sh` password (cross-cutting notes); `VITE_API_BASE_URL=localhost`, no-CI, 117 `tsc` errors on `feature/analytics-platform`, Meera SSE "not wired" decision, `MetricsPollingJob` recent-media, notifications `read-all` vs `read` suffix.

---

## §A. The build break is 6 named missing symbols — not "~40 lost-edit errors"
Your docs call this "compile blocked (pre-existing)" and "restore stub'd files from stash" without identifying what. Here is the exact list; each is a hard `javac` failure, confirmed by grep (symbol exists nowhere in `src/main`). Unit tests pass only because they **mock** these. *(Owner: Vikram · Kabir gate on b,f · Meera compile-verify)*

- [ ] **a. `Campaign.campaignType`** — `CampaignService.java:110,127` builds `.campaignType(...)`; field/builder absent in `Campaign.java`. Map `campaign_type` (V30) `@Enumerated(STRING)`; persist in create/duplicate; expose in `CampaignResponse`.
- [ ] **b. `Campaign.getCommissionRate()`** — `AffiliateEarningsService.java:351` maps it; absent. Map `commission_rate` (V50) `BigDecimal(5,4)`; add validated DTO field; without it every affiliate payout silently uses the flat 10%.
- [ ] **c. `User.softDelete()` / `getDeletedAt()`** — `AccountController.java:74`, `CreatorContextService.java:40`; absent in `User.java`. Add `deleted_at` (V61) field + `softDelete()` (anonymize email/phone/hash/name) + getter. Whole "Delete Account" + stale-token fix is nonfunctional without it.
- [ ] **d. `CreatorProfile.username` / `applicationStatus` / `newForUser` / `portfolioSettingsJson`** — `CreatorProfileRepository.java:18,25` declares derived queries on them + services call them; absent in entity. **Also fails Spring Data context startup.** Rebuild the entity to map every column through V38.
- [ ] **e. `CreatorDeliverableService.markPosted()`** — `CreatorDeliverableController.java:105-110` calls it; absent. Restore (HTTPS + instagram.com/youtube.com host allow-list per the existing test, APPROVED-state check, `applyMarkPosted`). Without it `DeliverableVerificationJob` (`...PostUrlIsNotNull`) never finds a candidate.
- [ ] **f. `EscrowService.adminReleaseForDispute / adminRefundForDispute / adminSplitForDispute`** — `DisputeService.java:241,245,250`; absent (only `freezeUnreleasedForDispute`/`hasFundedUnreleasedEscrow` exist). Implement on top of `release`/`refund` with hold locking; `POST /admin/disputes/{id}/resolve` cannot work and disputed escrow stays frozen forever.
- [ ] **g. `MeeraSessionService.sendTurn` arity** — `MeeraController.java:91` calls it with **5 args** (adds `idempotencyKey`); definition at `:95` takes **4**. Add the param + implement turn-dedupe by key.

> **P0 action beyond fixing these:** add a `mvn -q compile` CI gate (§I). It would have caught all six before delivery. Replace the Mockito stubs of these collaborators with ≥1 real-wiring integration test so a mock can never again hide a missing method.

---

## §B. Money correctness — no money flow works end-to-end (none of this is tracked)
Your trackers treat wallet/escrow as "FE-wired, backend route exists" swaps. The backend logic itself is broken. *(Owner: Vikram · Kabir mandatory on all)*

- [ ] **B-1. Wallet ledger has no external-money entry point → every top-up fails.** `WalletTopUpService.confirmCredited` debits the clearing wallet; `WalletLedgerService.post:120-125` rejects debit > balance; clearing wallet is created at 0 with no seed/mint path. Razorpay captures the card, webhook throws 400, retries forever, wallet never credits. Model the clearing/settlement account as external (exempt from the balance check) or credit against a `GATEWAY` leg on capture.
- [ ] **B-2. Escrow funding double-charges the brand.** `EscrowService.initiateFund:143-147` requires wallet balance ≥ amount **and** `:162` creates a Razorpay order for the same amount that must be paid; `confirmFunded:235` also debits the wallet. Brand pays ~2×. Pick one model (wallet-only under lock, or gateway-only with wallet credit).
- [ ] **B-3. Milestone payout pays gross + double-pays; withdrawal never disburses.** `EscrowService.release` credits the creator's wallet net-of-fee; `PayoutService.doQueuePayout` then RazorpayX-pays `milestone.getAmount()` (gross, no ledger debit) using `creatorId` as `fund_account_id` (placeholder). `WalletService.requestCreatorWithdrawal` moves creator→clearing and returns a fabricated `payoutId` with no RazorpayX call/`payouts` row/job. Debit net on payout; resolve a real fund account; add the missing `/wallet/payout-methods` controller.
- [ ] **B-4. Payout persistence/reconciliation is a no-op.** `payouts` (V48)/`Payout`/`PayoutRepository` are dead — no row ever inserted; `PayoutService.confirmExecuted` is empty ("no payouts table exists" — false). `payout.processed/reversed` parse `payload.order.entity.receipt` (payout webhooks carry `payload.payout.entity`) → id always null; `reversed` treated as `processed` with no re-credit. `RazorpayXClient:84-91` ignores HTTP status, records failures as `"queued"`. Persist at queue; parse the payout entity; re-credit on reversal; reject non-2xx.
- [ ] **B-5. Razorpay subscription webhooks unhandled → paid Pro never activates.** `RazorpayWebhookController` handles only order/payment/payout; `applySubscriptionWebhookUpdate`, `InvoiceService.generateInvoiceFromWebhook`, `InvoiceReadyEvent`, `SubscriptionPaymentFailedEvent` have **zero callers**. Brand pays on hosted checkout, local plan stays Free forever, no invoices, dunning never fires. Add `subscription.*` dispatch (idempotent, honor V56 ordering).
- [ ] **B-6. Fee `effective_at` never read; brand vs creator fee from divergent sources.** `PlatformFeeService:38` reads current bps unconditionally; `effectiveAt` is write-only (hardcoded `now()`). Brand fee defaults to hardcoded 15% (`RazorpayProperties:24`) while creator fee is DB-backed → can silently diverge. Honor `effectiveAt` (or snapshot bps onto the hold at fund time); single fee source.
- [ ] **B-7. Affiliate settlement never disburses.** `AffiliateSettlementJob.doSettleCreator` only flips rows to SETTLED — no wallet credit, no RazorpayX; sweep not period-bounded (month-boundary earnings mis-batched). Credit the internal wallet at settlement; bound the sweep by `createdAt < periodEnd`.
- [ ] **B-8. Milestone amounts unvalidated.** `MilestoneWriteRequest` (`MoneyDtos:178`) has no constraints; only the SUM is checked positive → null NPEs (500), negatives persist ([+5000, −4999]). Add `@NotNull @DecimalMin("0.01")` per row + `@Valid` on the list.
- [ ] **B-9. `wallets.escrow_balance` never written** → always ₹0 in responses. Maintain it in hold/release/refund, or drop the field.
- [ ] **B-10. Creator withdrawal idempotency key is raw/optional** (`WalletService:177`) — collision-griefable, and absent → double-debit on retry. Require + scope: `"creator-withdraw:"+userId+":"+clientKey`.

---

## §C. Post-agreement pipeline does not exist (not tracked)
Your docs mark deliverables/reviews as "% working." In code the write side is absent. *(Owner: Vikram)*

- [ ] **C-1. No `Deliverable` row is ever created.** grep `Deliverable.builder()|new Deliverable` in services → **0**. Deal acceptance/contract signing never materialize slots. Every deliverable endpoint returns empty/404 forever; verification/cleanup jobs sweep an empty table. Materialize `Deliverable` rows on deal acceptance/contract activation, in the milestone transaction.
- [ ] **C-2. Collaboration lifecycle dead-ends at TERMS_AGREED.** Only 4 `transitionTo(` sites exist; CONTRACT_PENDING/CONTRACTED/IN_PROGRESS/REVIEW_PENDING/REVISION_REQUESTED/COMPLETED never written. `ReviewService` requires COMPLETED → **no review can ever be submitted**; deals never show as in-progress/completed. Wire execution-phase transitions.
- [ ] **C-3. `payment_milestones.release_condition` (V52) is dead schema.** No Java reads it; `BrandDeliverableService.approve` only flips deliverable status; `EscrowService.release` never checks deliverable state. The core promise (approval gates payment) is unenforced both directions. Map the column; trigger release from the matching deliverable transition; block manual release until met.
- [ ] **C-4. Contract terms never stored.** `contracts.terms` gets only `{"tamperHashSha256": sha256(req.toString())}` — never verified, not canonical; usage rights (V64) + exclusivity dropped (`Collaboration.getUsageRights` has 0 callers). Persist canonical terms JSON, hash that, verify before signing, surface in PDF.
- [ ] **C-5. Brand can record the creator's signature.** `POST /contracts/{id}/sign {"role":"CREATOR"}` lets a brand drive a contract to ACTIVE without the creator (`ContractService:255`). Remove/flag-off the brand-relay CREATOR path.
- [ ] **C-6. Contract generation ignores deal state; unlimited duplicate v1 contracts.** `generate` never checks collaboration status or existing contracts; version always 1 → "latest by version" arbitrary; COMPLETED/CANCELLED unreachable. Gate on status; supersede prior unsigned; wire terminal transitions.
- [ ] **C-7. Deal cancellation allowed on funded/agreed deals with no escrow handling.** `canReject()` blocks only COMPLETED/CANCELLED/DISPUTED (`Collaboration:196`) — cancel a signed+funded deal with no freeze/refund. Block once contract signed/escrow funded.
- [ ] **C-8. Instagram verification calls Graph API with the internal ULID as the IG account id** (`DeliverableVerificationService:154`, `MetricsPollingJob`). OAuth callback never resolves the real numeric IG business account id. Every Graph call 400s → verification always FALLBACK. Metric list also deprecated on the pinned v25. Resolve+persist the IG id at OAuth; update metric names.
- [ ] **C-9. Two metrics endpoints upsert the same row with different auth gates** — legacy `PUT /deliverables/{id}/metrics` bypasses the APPROVED gate; zeros stored as null; only first proof kept. Retire the legacy path.
- [ ] **C-10. Discovery ranking substrate has no writer.** `creator_profiles.total_followers`/`engagement_rate` and `platform_stats` are never written (no setter/`save` sites); `MetricsPollingJob` writes only `creator_metrics`. Every real creator has 0 followers → fails `minFollowers`/ranking → discovery serves only V7's fake creators. Add an aggregation upsert step.

---

## §D. Notifications & email deliver essentially nothing (not tracked)
*(Owner: Vikram)*

- [ ] **D-1. 24 of 26 event handlers never fire.** `NotificationListener` has 26 `@EventListener`; only **4 `publishEvent` sites** exist total. Publish events at each state transition.
- [ ] **D-2. Every event email is addressed to `null`** ("would come from user lookup in real impl") → `NotificationService` skips — even `ContractSignedEvent` which carries the recipient + PDF link. Resolve recipient email from userId.
- [ ] **D-3. `@Async` listeners run synchronously** — `@EnableAsync` is never declared, so a listener exception can roll back the business transaction. Add `@EnableAsync` + `@TransactionalEventListener(AFTER_COMMIT)`.
- [ ] **D-4. Password-reset email never sent in prod.** `AuthService.createPasswordResetToken` only logs the token in dev; never publishes/queues. API still says "reset link sent" → permanent lockout. (Also: no reset page consumes `POST /auth/reset-password`.)
- [ ] **D-5. MSG91 config prefix mismatch → no email leaves the system.** `Msg91EmailClient` reads `${msg91.auth-key:}` but yml defines `influora.msg91.*`; unconfigured it logs "[MOCK]", returns success, and the outbox marks rows **SENT**. Align prefixes; in non-dev, unconfigured must mark FAILED not SENT. *(Note: this directly contradicts `PRIYA-CTO-CODEBASE-STATUS` §5's "secrets correct / no action.")*
- [ ] **D-6. `EmailWorker.processOutbox` wraps a 50-item batch + HTTP in one `@Transactional`** → duplicate sends on rollback, pool starvation. Per-row claim→send→mark.
- [ ] **D-7. `SubscriptionHaltedEvent` and `ContractReadyForEscrowEvent` have no listeners** — published into the void. Add handlers.

---

## §E. Security config & hardening (contradicts "security solid" in your docs)
Your `CODEBASE-STATUS`/`PENDING-WORK` mark security 🟢 90%. Code says otherwise. *(Owner: Vikram + Kabir mandatory)*

- [ ] **E-1. SecurityConfig 401-blocks every non-Razorpay webhook, the public portfolio, JWKS, click-tracking, and the Shopify OAuth callback.** `permitAll` = only `/health`, `POST /auth/**`, `verify-email`, `slug-check`, `POST /webhooks/razorpay`. So `POST /webhooks/{shopify,woocommerce,conversion,redemption}`, `GET /track/click/{id}`, `GET /portfolio/{u}` + contact, `GET /.well-known/jwks.json` (the AI service needs this), `GET /shopify/oauth/callback` all 401 in prod — HMAC verification never even runs. Add permitAll matchers (signatures remain the trust boundary).
- [ ] **E-2. Fail-open secret validation.** `SecretsStartupValidator` throws only in non-`dev`, but `@Value("${influora.env:dev}")` **defaults to dev** — a prod deploy that forgets `APP_ENV` boots on committed dev-default JWT/stream/internal/HMAC secrets + `secure=false` cookies. Fail closed (abort if unset). Same `APP_ENV`-defaults-dev on the Python side. *(Contradicts the "fails fast / no action" claim.)*
- [ ] **E-3. Placeholder Razorpay/R2/MSG91 creds pass `isConfigured()`** (non-blank) and the clients return `[MOCK]` stub IDs → a misconfigured prod "processes" payments with fake order IDs, no alert. Fail non-dev boot on placeholders; mock mode requires dev.
- [ ] **E-4. Shopify/Woo/conversion webhook secrets have no yml block at all** → default `""` → verifiers fail closed (all rejected even after E-1); conversion AES key empty. Add env-backed blocks + boot validation.
- [ ] **E-5. Admin/workspace suspension is never enforced.** `is_suspended`/`CreatorProfile.suspend` are written but no request-path reads them — `BrandContextService.requireBrandWorkspace` (gate for escrow etc.) never checks. A fraud-suspended brand keeps moving money; suspended creators stay discoverable. Enforce in the context services (403) + exclude from discovery.
- [ ] **E-6. `V7__seed_discoverable_creators.sql` inserts 5 public ACTIVE creators sharing `bcrypt(Password@123)` (password in the migration) into every env incl. prod.** Move to a dev-profile seeder; add a prod cleanup migration.
- [ ] **E-7. No per-account login lockout for brand/creator; rate limiter trusts spoofable `X-Forwarded-For`.** `AuthRateLimitFilter:157` keys buckets on client XFF with no trusted-proxy allow-list → unique XFF per request = unthrottled credential stuffing. Per-account lockout + trust XFF only from ingress.
- [ ] **E-8. Soft-delete enforced only on the creator gate** — `BrandContextService.requireBrand` never re-checks `deletedAt`; delete never flips `is_discoverable`. Mirror the check; flip discoverability.
- [ ] **E-9. Brand KYC docs are unvalidated URL strings; malware scan is a no-op; `file_uploads` (V1) orphaned.** `POST /onboarding/brand/kyc` accepts free-form `gstinDocUrl/panDocUrl` (admins click attacker URLs); `MalwareScanService` is `NoOpMalwareScanService`. Presigned uploads against `file_uploads`+R2; wire a real scanner before brand visibility.
- [ ] **E-10. Publish-fee bypass.** Fee charged only on PATCH→ACTIVE; `CampaignService.create` honors `status=ACTIVE` with no fee and `ConfirmLaunchExecutor` (AI path) flips ACTIVE with neither fee nor verification gate. Force DRAFT or charge in create(); gate the AI executor.
- [ ] **E-11. `@Valid` missing on 30/59 controllers** (deliverable revise/metrics/mark-posted, campaign PATCH, tracking-link/coupon, billing checkout, support assign). Constraints inert → 500s/bad data. Add `@Valid` + missing constraints; ArchUnit test.
- [ ] **E-12. Access token in `localStorage` (all roles); admin token passed as WebSocket `?token=` query param** (logged by proxies/history). In-memory access token; single-use socket ticket.
- [ ] **E-13. Open redirect on public `GET /track/click/{id}`** (no destination allow-list). Validate against campaign domains.

---

## §F. AI service internal breaks (contradicts "AI is the healthiest component")
`CODEBASE-STATUS` calls the AI service 🟢 solid / one intentional `NotImplementedError`. In code, two of five routes crash on import and no E2E path closes. *(Owner: Ash / AI)*

- [ ] **F-1. TrendSpark & brand-safety routers are never registered AND crash on import.** `main.py` mounts only chat/analyze_site/voice. `config.py` defines **none** of `TRENDSPARK_MODEL`, `trendspark_max_*`, `brand_safety_max_*`, `ai_spend_kill_switch`, `ai_daily_spend_ceiling_usd` → importing `trendspark.py`/`brand_safety.py`/`pricing.py` raises `ImportError`. Add the settings, then `include_router` both.
- [ ] **F-2. `trendspark.py` calls `ClaudeProvider.complete_text()` — doesn't exist** (only `stream_turn`/`complete_with_forced_tool`); tests mock it, hiding the gap. Implement `complete_text(system,user,model,max_tokens)` with never-raise semantics.
- [ ] **F-3. Endpoint scope `"trendspark"` absent from `ENDPOINT_SCOPES`** → valid tokens still 403. Add `"trendspark": (SCOPE_SERVICE,)`.
- [ ] **F-4. Spring `BrandSafetyScoreService` has zero callers** (`ScoreCalculationJob` javadoc: "NOT wired in") → `creator_scores.brand_safety_score` never populated. Wire it into the scoring job.
- [ ] **F-5. Meera chat has no working reply path.** (a) FE `EventSource(?token=)` is a GET; Python `/chat` is POST reading body+header → 405. (b) fallback `GET /meera/sessions/{id}/messages` is unmapped → 405. (c) `StreamTokenService.mint()` omits `iss`+`scope` claims Python's verifier requires → 401. Plus: FastAPI has no CORS; FE never sends the required `Idempotency-Key`; Spring persists a hardcoded "placeholder" reply as a real ASSISTANT row. Fix all five.
- [ ] **F-6. `/chat` has no spend gate and records no spend** (gate.py's own docstring claims chat is a call site; it imports nothing from `app.costs`). The only routes that call the gate are the dead ones. Spend tracker is in-memory (resets on restart, ×N workers). Gate + `record_spend` in `/chat`; persist spend.
- [ ] **F-7. SSRF-guarded fetch is synchronous inside the async route** (`analyze_site.py:92`, blocking `httpx.Client`/`getaddrinfo`) → one slow site head-of-line-blocks all chat/voice on the single worker. `run_in_executor` or async client.
- [ ] **F-8. `/analyze-site` is orphaned both directions** — no Spring caller, and `AnalyzeSiteCallback` is referenced nowhere → `BrandProfile.analysisStatus` never leaves PENDING; Meera answers with no brand context. Build the Spring client + async job + callback consumer.
- [ ] **F-9. Sarvam voice is orphaned** — routes accept only `scope=service` tokens (browsers can't hold), no Spring proxy; shipped UX is browser Web Speech + `clean-transcript.ts` mock + no-op `voice-usage.ts`. Either proxy it or descope Sarvam (stop requiring the key at boot).
- [ ] **F-10. No video/AI content-analysis pipeline exists** anywhere (no multimodal call). If it's on the roadmap it's unbuilt; if not, remove the `durationSeconds`/analysis dead fields.
- [ ] **F-11.** Tool loop never forwards `conversation_id` → `meera_tool_calls.conversation_id` always null (broken AI-action audit chain). Stream token "single-use" asserted but never enforced (60s replay window). Dockerfile installs Playwright (~1GB) nothing uses.

---

## §G. Database integrity (not tracked)
*(Owner: Meera + Vikram)*

- [ ] **G-1. Mixed Flyway versioning silently skips migrations.** Numeric `V41–V64` sort **below** timestamp `V20260709155921…`; `out-of-order` is unset (false). On any env migrated since 2026-07-09, V50–V64 (incl. soft-delete, dispute/subscription `@Version`, bank-account) are pending-below-current and **never apply** → schema drift vs a fresh DB, and `ddl-auto: validate` would fail on the mapped `@Version` columns. Pick one scheme; renumber stragglers above current max or set `out-of-order: true`; add a CI guard.
- [ ] **G-2. `baseline-on-migrate: true` unconditionally** — masks a wrong-DB misconfig by baselining at v1. Set false for prod.
- [ ] **G-3. `wallets` finder ignores `owner_type`** (`findByOwnerId` vs `UNIQUE(owner_id,owner_type)`) — latent NonUniqueResult. `PayoutRepository` + `file_uploads` (V1) are dead. Version gaps V40/V60 (unmerged branches) will be silently skipped if backfilled.
- [ ] **G-4. No optimistic locking on Collaboration/Contract/PaymentMilestone/Deliverable** (`@Version` only on 3 entities) + no transition guards → racing state transitions produce inconsistent records. Add `@Version` + allowed-transition maps.
- [ ] **G-5. No distributed locking on jobs/EmailWorker** — 12 jobs + the 30s worker guarded only by per-JVM `AtomicBoolean` (EmailWorker's select is unlocked). On ≥2 instances: duplicate emails, duplicate metrics/scores, double Meta spend. Add ShedLock (JDBC) + atomic outbox claim.
- [ ] **G-6. `IdempotencyService` FAILED keys are terminal and status updates are lost** (mutates a detached entity without `save()`; `@Transactional` self-invoked → no tx) → a failed payout/settlement/redemption wedges forever at `AlreadyInProgressException`. Separate transactional bean + explicit save + FAILED→IN_PROGRESS reclaim.
- [ ] **G-7. Admin `findAll()` unbounded on campaigns; 2N+1 on the creator list.** Paginate; batch-load.

---

## §H. Frontend correctness beyond the tracked mock→live swaps (not tracked)
*(Owner: Ananya)*

- [ ] **H-1. `Multipart` upload cap is Spring's 1 MB default** — no `spring.servlet.multipart.*` config exists, so the 500 MB video / 1 GB batch limits are dead; every real creator upload fails with an opaque 500 (no handler). *(Backend, but it kills the FE upload UX.)* Set the limits + add the exception handler. *(Vikram)*
- [ ] **H-2. Zustand auth store persists nothing (`partialize:()=>({})`) while guards read raw localStorage** → after refresh `user` is null (guards still pass), brands never populate `user`, and `creator-layout` shows hardcoded `@priya_sharma`. Hydrate the store from `/auth/me` on mount; guards read the store.
- [ ] **H-3. Widespread `try/finally` without `catch` on real mutations** (hype publish, deal accept optimistic-nav, onboarding save/complete, portfolio load, discover search) → silent blank/spinner on live failure. Add `catch` → error state on every mutation.
- [ ] **H-4. Footer/legal/feature links 404 or hit the `/:handle` catch-all** — `/features/*`, `/blog`, `/refund-policy`, `/grievance`, `/disclosure`, `/kyc`, `/tds` render a stranger's "not found"; built `blog/`/`features/`/`legal/` pages are unrouted. India-required compliance links break; `company.ts` grievance-officer/address are empty TODOs. Register routes before the catch-all; fill compliance data.
- [ ] **H-5. `console.log` leaks PII/negotiation terms** — 18 `[v0]` logs; worst log shipping addresses, counter amounts, deliverable payloads (`creator-chat.tsx`, `deliverable-submission.tsx`). Delete; add `no-console` lint.
- [ ] **H-6. Forms bypass the installed zod/react-hook-form** on ~all pages (raw useState) → invisible submit errors; `campaign-form` edit-mode loads a hardcoded campaign for one id then Save PUTs blank → data loss. Adopt zod on money-touching forms; fetch before edit.
- [ ] **H-7. Notification bell always empty + mark-read 404s** — hook reads `data.data` (controller returns raw `{notifications,…}`), posts `/{id}/read` (real route is `/notifications/read` with a body), `read-all` unmapped, fields `eventType/isRead` vs FE `type/read`, hardcodes `brand_token` for creators. Align to the real contract.
- [ ] **H-8. FE never calls `POST /auth/refresh`** despite full backend support → sessions hard-expire mid-use with no 401-retry. Add a refresh interceptor.

---

## §I. Ops / observability / tests (partially tracked — these specifics are not)
*(Owner: Meera)*

- [ ] **I-1. No actuator; `/health` always returns "ok"** (no DB check) and leaks R2 config unauthenticated; no structured logging / correlation id (Java). `GlobalExceptionHandler.handleGeneric` has **no logger** and no handlers for upload-size/data-integrity/unreadable-JSON/optimistic-lock → prod 500s leave no trace. Add actuator + JSON logging + log/4xx handlers.
- [ ] **I-2. `docker-compose` is MySQL-only** — no api/ai/frontend services, healthchecks never exercised → the stack can't be booted/e2e-tested. Complete it.
- [ ] **I-3. Test pyramid inverted** — ~1017 real Java unit tests but **no MockMvc** (routing/filter/auth/serialization untested), the sole `@SpringBootTest` is Testcontainers-gated and per its own javadoc never ran, e2e is 4 mock-mode smoke tests with auth bypassed. Nothing exercises the wiring where §A/§E/§F live. Add `@WebMvcTest` slices for auth/webhook/payout + one live money-path Playwright flow.
- [ ] **I-4. Add the `mvn compile` + full-suite CI gate** (§A). Highest-leverage single fix.
- [ ] **I-5. `DeliverableCleanupJob` permanently dry-run** (`influora.cleanup.dry-run` defaults true, set nowhere); revision sweep a documented no-op; thumbnails uploaded before validation (orphans). Flip after a key-history store exists.

---

## Count of NEW pending items (not in your trackers): **~70**
6 compile-break symbols (§A) · 10 money (§B) · 10 pipeline (§C) · 7 notifications (§D) · 13 security (§E) · 11 AI (§F) · 7 DB (§G) · 8 frontend (§H) · 5 ops/test (§I).

**Reconciliation with your ledgers:** your "~85% done, remaining is verification + deploy" reflects UI/route coverage, not runtime correctness. Against working code paths, nothing money-, deliverable-, notification-, or AI-chat-related functions end to end, and the backend does not compile. Realistic remaining engineering to a shippable state is substantial, not a deploy step.

*Companion to `INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md` (full findings with severity/impact). Source-only; no product code modified by this doc.*
