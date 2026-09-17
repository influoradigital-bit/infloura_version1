# Subscription answers, Part 3 (technical testing), 2026-09-17

**From:** Priya (CTO). **Answers:** `subscription-questions-0917.md` T-1 to T-12, plus the Part 4 summary.
**Code checked:** COMMITTED code only (`git show HEAD:`). HEAD is **e2dba1c**, not 1921786. The two commits after 1921786 (e4a357b docs, e2dba1c the deploy compose) change no Java or TS under test.
**Test runs:** in `git archive HEAD influora-api` extracted to a scratch folder outside the repo, `mvn -o test -Dtest=...`. Baseline: 170 tests, 0 failures, 0 errors, **Skipped 0**, BUILD SUCCESS.
**Mutations:** each one changes a scratch copy, runs the named tests, then restores the file (restore checked with diff). "RED" means the tests caught the change. "GREEN" means **nothing caught it**.
**Not run:** Testcontainers and `@SpringBootTest` (no Docker on this machine). No subscription or credit test in this module uses a real database at all.

**Scope:** creator credits, credit packs, `CREATOR_CREDITS_ENABLED`, `CreatorCreditResetJob` and the `V20260912100*` migrations are **not in HEAD**. `git grep -iE "CreatorCredit|CREATOR_CREDITS|credit_pack|V20260912100"` finds only unrelated names (`festival-editions.ts` `FestivalCreatorCredit`, escrow test method names). Every "pack" and "creator top-up" part below is therefore **NOT BUILT**.

### Mutation results

| # | Mutation (scratch copy) | Tests run | Result |
|---|---|---|---|
| M1 | Webhook signature check turned off (`RazorpayWebhookController.java:92`) | RazorpayWebhookControllerTest | RED (`receive_invalidSignature_rejectsBeforeAnyDispatch`) |
| M2 | `WebhookSignatureVerifier.verify` returns true for any input | RazorpayWebhookControllerTest, SecretsStartupValidatorTest | **GREEN, 74/74** |
| M3 | Stale-webhook skip removed (`SubscriptionService.java:630`) | SubscriptionServiceTest | RED (`testStaleWebhookSkipDoesNotReconcile`) |
| M4b | Stale path writes `setStatus(target)` to the loaded row, then returns without saving | SubscriptionServiceTest | **GREEN, 19/19** |
| M5 | Turn debit call deleted (`MeeraSessionService.java:388`) | EntitlementConformanceTest, MeeraSessionServiceTest | RED (conformance + 2 session tests) |
| M6 | `AND c.creditsRemaining >= :cost` dropped from `tryDecrement` | AICreditServiceTest, BrandAiCreditRepositoryQueryTest, MeeraSessionServiceTest | **GREEN, 49/49** |
| M7 | Refund idempotency wrapper bypassed | AICreditServiceTest | RED (`testDoubleReleaseIsNoOp`, `testRacingReleaseIsNoOp`) |
| M8 | Refund never credits | AICreditServiceTest | RED |
| M9 | Pro fee condition inverted (`BrandCampaignFeeService.java:119`) | BrandCampaignFeeServiceTest | RED |
| M10 | UI publish fee call deleted (`CampaignService.java:376`) | BrandFeePublishPathConformanceTest | RED |
| M11 | Seat `requireCapacity` disabled (`WorkspaceMemberService.java:430`) | EntitlementConformanceTest | RED |
| M12 | Webhook never calls `applySubscriptionWebhookUpdate` | RazorpayWebhookControllerTest | RED (7 tests) |
| M13 | Webhook idempotency key made unique per delivery (`+ System.nanoTime()`) | RazorpayWebhookControllerTest | **GREEN, 13/13** |
| M14 | ALREADY_SUBSCRIBED guard removed | SubscriptionServiceTest | RED |
| M15 | Renewal job ignores cancel-at-period-end | SubscriptionRenewalResetJobTest | RED |
| M16 | Checkout sends note key `workspace_id` instead of `workspaceId` | SubscriptionServiceTest, RazorpayClientTest, RazorpayWebhookControllerTest | **GREEN, 42/42** |
| M17 | Razorpay plan created at priceInr/100 (₹49.99) | SubscriptionServiceTest, RazorpayClientTest | **GREEN, 29/29** |
| M18 | Renewal job renews every ACTIVE row, not only lapsed ones | SubscriptionRenewalResetJobTest | RED |

---

## 3A. Test coverage that would catch a break

### T-1 — **PARTLY**
Several paths have a test that goes red; the paths most likely to break in production do not.

| Path | Test that fails if the path is deleted or inverted | Mocks the layer the bug would live in? |
|---|---|---|
| Upgrade checkout | `SubscriptionServiceTest` `testAlreadySubscribedGuardStillFiresAheadOfLock` (:455, M14 RED), `testConcurrentCheckoutCallsCreateExactlyOneRazorpaySubscription` (:269, by reading) | **Yes.** `RazorpayClient` is `@Mock` (:66), and `createSubscription(...)` is matched with `any()` notes (:417, :449). `RazorpayClientTest` only covers the not-configured stub/throw branches (:45–:112). No test checks the real request body (`plan_id`, `total_count`, `notes.workspaceId`) or the plan amount. M16 and M17 both stay GREEN. |
| Subscription webhook to Pro | `RazorpayWebhookControllerTest` activated/charged/cancelled tests (:220–:383, M12 RED); `SubscriptionServiceTest` :471 (by reading) | **Yes.** `SubscriptionService`, `IdempotencyService` and `WebhookSignatureVerifier` are all `@Mock` (:62–:71). Real HMAC (M2), real dedupe (M13) and the JPA write (M4b) are all GREEN. |
| Credit debit | `MeeraSessionServiceTest` charge-at-send tests and `EntitlementConformanceTest` AI_CREDITS branch (M5 RED; this is the first falsification of that branch, which its javadoc at :103–:109 says was never done) | **Yes, for the guard.** The conditional decrement is SQL (`BrandAiCreditRepository.java:24–28`), and `AICreditServiceTest` mocks the repository (`@Mock` :50, `tryDecrement` stubbed to return 1 or 0). Dropping the `>=` guard is GREEN (M6). |
| Credit refund | `AICreditServiceTest` `testReleaseRefundsChargeAndDailyCounterWhenEligible` (:207, M8 RED), `testDoubleReleaseIsNoOp` (:275, M7 RED) | **Partly.** The refund cap is SQL. `BrandAiCreditRepositoryQueryTest:29` only checks the `@Query` string by reflection (it says there is "no H2/testcontainers-backed @DataJpaTest harness", :17). The double-release guard is tested only through a mocked `IdempotencyService`. |
| Pack purchase | **NOT BUILT** | — |
| Platform fee (brand, publish) | `BrandCampaignFeeServiceTest` :77/:93 (M9 RED); `BrandFeePublishPathConformanceTest` :164/:184 names both publish paths (M10 RED) | The plan lookup is mocked, which is fine for an if/else. Deal-funding and direct-hire fees are not in this table; I did not mutate `PlatformFeeService` or `EscrowService`. |
| Plan-limit gate | SEATS: `EntitlementConformanceTest` (M11 RED). EXPORT: `PlanGateInterceptorTest:110` `testRealExportEndpointRejectsFreePlan` (by reading). | Checks by bytecode and reflection; no HTTP-level test. `trackedCreatorLimit` has no gate and no test (see T-6). |

Paths with **no test at all**: `AICreditResetJob` (no test class exists in HEAD) and the HMAC arithmetic in `WebhookSignatureVerifier` (no `WebhookSignatureVerifierTest`).

### T-2 — **PARTLY**
- **Verified in code: YES, for every Razorpay event.** `RazorpayWebhookController.java:92–95` returns 400 `INVALID_WEBHOOK_SIGNATURE` before parsing or dispatch, for all `subscription.*`, `order.paid` and `payment.captured` events. `WebhookSignatureVerifier.java:26–37` computes HMAC-SHA256 over the raw body, compares in constant time, and rejects when the secret is blank (:31–33).
- **Bad-signature test:** `RazorpayWebhookControllerTest.java:387` `receive_invalidSignature_rejectsBeforeAnyDispatch` does exist (M1 RED). **But it mocks the verifier** (`@Mock` :62, stubbed `false` at :388, stubbed `true` for all other tests at :89). Nothing tests the HMAC itself: a verifier that accepts any signature passes all 74 tests (M2 GREEN).
- **Pack events:** NOT BUILT.

### T-3 — **PARTLY**
- **Code, by reading: yes, idempotent.**
  - Each delivery reserves an idempotency row keyed on `eventType:subscriptionId:created_at` (`RazorpayWebhookController.java:326–344`, key built at :458–462). Duplicates are caught and acknowledged with 200.
  - One subscription row: `subscriptions.workspace_id UNIQUE` and `razorpay_subscription_id UNIQUE` (`V54__subscription_billing.sql:36,39`). The service updates the existing row it finds (`SubscriptionService.java:598–602`).
  - One invoice: dedupe on payment id (`InvoiceService.java:167–170`), backed by `razorpay_invoice_id UNIQUE` (`V54:58`).
  - `subscription.charged` generates no credit grant; it only reconciles the allotment (`SubscriptionService.java:745–757`). There is no credit ledger. The wallet top-up path (`payment.captured`/`order.paid`) short-circuits when the top-up is already CREDITED (`WalletTopUpService.java:193`); no test covers a duplicate top-up delivery.
- **Test:** `RazorpayWebhookControllerTest.java:415` `receive_subscriptionChargedReplay_isDedupedAndNeverDoubleApplied`. **It mocks the dedupe layer:** the second call is scripted to throw `AlreadyCompletedException` (:418–420), so it never checks that the key is stable. M13 (a different key on every delivery) stays GREEN. No database test checks the unique constraints.
- **Pack `payment.captured` credit grant:** NOT BUILT.

### T-4 — **PARTLY**
- **Final state, by reading: CANCELLED.**
  - `cancelled` arrives first: the row is set to CANCELLED and `lastWebhookEventAt` is recorded (`SubscriptionService.java:652, 705, 717`).
  - The later `activated` has an older `created_at`, so it is skipped (`:630–640`).
  - The active-plan lookup then resolves to Free (`:216–222`).
  - Caveat: the check is `isBefore`, so two events with the **same** `created_at` second are applied in arrival order.
- **Tested:** `SubscriptionServiceTest.java:599` `testStaleWebhookSkipDoesNotReconcile`. It uses PAST_DUE rather than cancelled/activated; M3 RED.
- **Weakness:** the test only checks that `saveAndFlush` is never called and never checks the row's status. A regression that writes status on the loaded row before returning stays GREEN (M4b). With a real database, JPA's automatic flush would commit that write anyway. There is no controller-level or database-level out-of-order test.

### T-5 — **NO**
- **Can both succeed? Yes, by reading.**
- **What was meant to stop it:** `tryDecrement` is a conditional UPDATE (`BrandAiCreditRepository.java:24–28`, `WHERE creditsRemaining >= :cost`). That UPDATE alone would be race-safe.
- **Why it fails:**
  1. Before the UPDATE, `tryConsume` changes the loaded row (`dailyActionsUsed`) and saves it (`AICreditService.java:141–142`).
  2. `BrandAiCredit` has no `@Version` and no `@DynamicUpdate` (`BrandAiCredit.java:10–12`), so Hibernate's automatic flush writes **every column**, including the `credits_remaining` value it read earlier.
  3. Turn B's flush waits on A's row lock, then overwrites A's decrement with the old 1. B's conditional decrement then sees 1 and succeeds.
  4. Result: two turns, one credit charged. The same lost update can undo a refund or a monthly reset, and it also loses daily-counter increments. The 500/day cap is a plain read-then-write (:128–142).
- **Tested:** only against a mock. The `AICreditServiceTest` "racing" test (:288) covers release only, through a mocked `IdempotencyService`. There is no concurrency test for debit against a real database.

### T-6 — **NO**
What `EntitlementConformanceTest` really enforces (ASM scan of compiled classes, :150–237; assertions :244–310): it checks **registry → enforcement only**. Every `Entitlement` constant must have an enforcing call site:
- CAPACITY → `requireCapacity`
- METERED → `consume`, or `AICreditService.tryConsume*` for AI_CREDITS
- FLAG → `@RequiresPlan`
- RATE → `resolveBrandFeeBps`/`chargeOnPublish`

It also resolves distinct plan columns (:329) and checks that declared endpoints exist (:381).

- **Unregistered plan-limited feature: not detected.** Real case in HEAD: `Plan.trackedCreatorLimit` (`Plan.java:62`, seeded to 5 for Free at `V55`) is shown to brands (`BillingController.java:146, 228`), but there is no `Entitlement` constant for it (`Entitlement.java:40–56`) and no enforcement. The test is green. The gate is `.proof-os/gates/F-0490-dead-metric.sh`, and its own baseline (`_dead_metric_baseline.json`) lists this metric as a known exception.
- **`requireCapacity` without `consume`: not detected.** Nothing pairs the two. CAPACITY entitlements only need `requireCapacity`, and a METERED constant passed to `requireCapacity` would throw only at runtime.
- **Other gaps:**
  - A constant passed through a local variable counts toward nothing, so the gate fails closed on it (javadoc :80–89).
  - The RATE check only needs one caller; `BrandFeePublishPathConformanceTest` covers the second (M10).
  - The billing gates under `.proof-os/gates/` (F-0158 ×2, F-0159, F-0802) run mutations or these same unit tests. None of them uses a real database.

## 3B. Environment and live

### T-7 — **PARTLY** (unit tests only; nothing was proven on production)

| Flow | Unit tests (mocked) | Testcontainers | Production |
|---|---|---|---|
| Brand upgrade (checkout) | SubscriptionServiceTest | none | **Never.** Subscriptions never ran live; 0 subscription rows as of 2026-09-15. |
| Webhook → Pro | RazorpayWebhookControllerTest, SubscriptionServiceTest | none | **Never** |
| Credit reset job (`AICreditResetJob`) | **none** (no test class in HEAD) | none | **Unknown.** Nothing in code records a run except a log line (`AICreditResetJob.java:106`). |
| Renewal reset job | SubscriptionRenewalResetJobTest (mocked repository and service) | none | **Never** as a paid renewal (no paid rows exist) |
| Creator pack purchase | **NOT BUILT** | — | — |

The only Razorpay flow proven live is one-time orders (wallet top-up, 2026-09-13). Those use `order.paid`/`payment.captured`, not `subscription.*`.

### T-8 — **YES** (for the placeholders that ship in the repo)
- **Fails at boot.** `SecretsStartupValidator.validate()` is `@PostConstruct` (:283). It calls `validateRazorpayWebhookSecret` (:337, body :479–506), which flags a missing or placeholder `webhook-secret`, `key-id` and `key-secret`. It **throws `IllegalStateException` unless the environment is dev** (:350–354).
- **Dev mode needs both:** `influora.env=dev` **and** the `dev` Spring profile (:309–311). A dev env value with a non-dev profile is itself reported as a problem (:371–380).
- **Placeholders checked:** the ones in `application.yml:401–403`.
- **Tests:** `SecretsStartupValidatorTest` :451, :462, :813, :824, :835 (by reading).
- **Second guard:** `SubscriptionService.java:328–334` refuses checkout with 503 when keys are set but the webhook secret is not.
- **Limit:** it rejects only the exact placeholder strings. A real `rzp_test_*` key on production boots without complaint.

### T-9 — **PARTLY**
- **One instance: YES.** All three jobs carry `@SchedulerLock` (`AICreditResetJob.java:54`, `SubscriptionRenewalResetJob.java:100`, `SubscriptionDunningJob.java:108`), backed by the JDBC ShedLock provider (`SchedulerLockConfig.java:33–38`, table `V68__shedlock.sql`), plus an in-process `AtomicBoolean`. Scheduling can be turned off with `influora.scheduling.enabled=false` (`TaskSchedulerConfig.java:72–75`; defaults to on).
- **Run twice in a day:**
  - `AICreditResetJob`: **not idempotent.** `resetForNewCycle` unconditionally sets remaining to the allotment (`AICreditService.java:302–307`) and does not check `lastReset`. A second run hands back credits spent in between. The lock only lasts 1–30 minutes.
  - `SubscriptionRenewalResetJob`: idempotent for a row that is one cycle behind, because the renewal moves the period end past now (`:118–121`). A row several cycles behind moves forward one cycle per day and gets a credit reset each day (`SubscriptionService.java:786–795`).
  - **Every month, by reading:** Free rows are ACTIVE with period end on the 1st (`SubscriptionService.java:235–258`, backfill `V20260912120000`). So the 03:30 renewal job treats every Free brand as a "missed webhook". It resets credits a second time 90 minutes after the 02:00 job and writes a `SUBSCRIPTION_RENEWAL_SAFETY_NET` audit event for each one. It renews by the old cycle's length (`SubscriptionRenewalResetJob.java:175–181`), so Free periods drift away from the 1st (28-day February).
- **Server down at 02:00 on the 1st:** there is no catch-up for `AICreditResetJob` (Spring `@Scheduled` cron has no missed-run replay). Free brands get reset anyway by the renewal job described above, by accident. **Pro brands are not reset until the next 1st**, because the webhook keeps their period current and a `subscription.charged` renewal does not reset credits (`SubscriptionService.java:745–757` only sets the allotment).
- **Bigger risk:** the safety net renews **any** ACTIVE Pro row whose period lapsed, without checking with Razorpay (`SubscriptionRenewalResetJob.java:125, 190`). If a failed renewal's `subscription.pending`/`halted` webhook is lost, Pro keeps renewing unpaid indefinitely.
- **Tests:** none for AICreditResetJob; renewal and dunning job tests are mocked.

### T-10 — **YES**

| Flag | Where | Production value | What it turns off |
|---|---|---|---|
| `CREATOR_CREDITS_ENABLED` | **not in HEAD** | — | Nothing to turn off: creator credits are NOT BUILT |
| `MEERA_CREATOR_ENABLED` / `influora.meera.creator-enabled` | `application.yml:215`, `MeeraCreatorFeatureProperties.java:31` | defaults to `true`; the live Utho box's env is not knowable from repo compose | When false, every creator-audience Meera route returns 404 |
| `VITE_PAYMENTS_IN_ENABLED` | `src/lib/api.ts:76, 121`; baked in at build (`publish-images.yml:252`, defaults to `'true'`) | whatever the image was built with; defaults to true | Blocks wallet top-up and escrow funding in the UI and client. **It does not gate `billing.initiateCheckout`** (`api.ts:4375–4382`). |
| `VITE_API_MODE` | `api.ts:61` | must be `live` | When it is not `live`, billing reads return mock data and checkout rejects (`api.ts:4346–4382`). The mock allotment (150) differs from the Free seed (100). |
| `influora.scheduling.enabled` | `TaskSchedulerConfig.java:72–75` | defaults to on | Every reset, renewal and dunning job |
| Razorpay keys unset in dev | `RazorpayClient.java:160–163, 199–206` | n/a on production (boot throws, T-8) | Dev returns `plan_stub_*`/`sub_stub_*`; unit tests exercise only these branches |

Also, **no suite test needs Docker for billing**, so "Skipped 0" says nothing about database behaviour (T-3/T-4/T-5).

### T-11 — **YES** (wrong numbers found, by reading; not rendered in a browser)
1. **Pro price shown 100× too high.** `plan.priceInr` is in paise (`V54:16` comment, `V55` seeds `499900`). `brand-billing-settings.tsx:377` passes it to `formatCurrency` (:47–53, no /100), which renders **₹4,99,900 per month**. The same page's CTA uses rupees: `formatCurrency(4999)` at :554.
2. **Invoice amount 100× too high.** `InvoiceResponse.amount` is int paise (`Invoice.java:39`); `brand-billing-settings.tsx:683` renders it as rupees.
3. **Pro "Up to undefined" / "undefined view/month".** `PlanDto` is `@JsonInclude(NON_NULL)` (`BillingDtos.java:17`), so the Pro plan's null `trackedCreatorLimit`/`creatorAnalyticsMonthlyLimit` are **left out** of the JSON. The TS type says `number | null` (`api.ts:4263–4264`), and the page tests `=== null` (`brand-billing-settings.tsx:428, 441`), so Pro renders the string `undefined`.
4. **Free `feeBps`.** Also left out (`V57` sets it to NULL, plus NON_NULL). The TS type says `feeBps: number` (`api.ts:4260`). The page guards it (`brand-billing-settings.tsx:58–60`), so the UI shows "—".
5. **`meera-api.ts`.** `MeeraCreditStatus.unlimitedUntil: string | null` (:103) arrives as undefined (NON_NULL, `MeeraDtos.java:73–80`), which does no harm. Field names match `CreditStatusResponse`. `state` is `"FREE"` for any workspace with credits left, including Pro (`MeeraController.java:195`).
6. **`UsageSummaryResponse`** (no NON_NULL) matches `BillingUsageSummary` (`api.ts:4292–4302`) field for field.

### T-12 — **PARTLY** (the brand test is possible; there is no creator top-up to test)
**Brand Pro subscription** (₹4,999; refund it from the Razorpay dashboard afterwards, since there is no refund code path):
0. **Before starting:**
   - Fix T-11 #1 so the page shows the right price.
   - Confirm the Razorpay dashboard webhook `https://<api host>/api/v1/webhooks/razorpay` (`application.yml:119` context path) has `subscription.activated/charged/pending/halted/cancelled` enabled.
   - Confirm live keys are set.
   - DB: `SELECT razorpay_plan_id, price_inr FROM plans WHERE code='PRO'` should show NULL, 499900.
1. **Start checkout.** An OWNER/ADMIN of a test brand clicks Sidebar Billing → Upgrade to Pro (`POST /billing/checkout`).
   - Check `plans.razorpay_plan_id` is now `plan_*`.
   - Check the Razorpay dashboard plan amount is **₹4,999.00**; this is untested (M17).
   - Check an `idempotency_keys` row with scope `billing.checkout.pro` exists.
   - `subscriptions` should be **unchanged** (still the Free row).
2. **Pay** ₹4,999 on the `short_url` (real card or UPI mandate).
3. **After the webhooks:**
   - `subscriptions`: `status='ACTIVE'`, `plan_id`=PRO id, `razorpay_subscription_id='sub_…'`, `last_webhook_event_at` set, period matches the Razorpay dashboard.
   - `idempotency_keys` rows with scope `razorpay.subscription.webhook` (one per event).
   - `invoices`: one row, `amount=499900`, `razorpay_invoice_id='pay_…'`.
   - `brand_ai_credits.monthly_allotment=400` (`credits_remaining` does **not** change until the 1st).
   - An email outbox row for InvoiceReady.
   - **If the subscription row stays Free for more than 5 minutes, stop.** No reconciliation exists; see blocker B1.
4. **Pro limits apply:**
   - Invite a 2nd member: succeeds (seat limit 5).
   - `GET /campaigns/{id}/export`: 200.
   - Publish a campaign: `wallet_transactions` fee = budget × 0.07.
5. **Cancel** (`POST /billing/cancel`): `cancel_at_period_end=1`; Razorpay shows cancel at cycle end. Optionally cancel immediately in the dashboard. After `subscription.cancelled`: `status='CANCELLED'`, `GET /billing/plan` returns FREE, `monthly_allotment=100`.
6. **Replay one delivered webhook** from the Razorpay dashboard: still one `invoices` row and no status change. This is the only real test of T-3.

**Creator top-up:** NOT BUILT. No route, pack catalogue, order or webhook exists in HEAD, so there is nothing to click.

---

## Part 4 — Summary

### 1. Verdicts
| Id | Verdict |
|---|---|
| T-1 | PARTLY |
| T-2 | PARTLY |
| T-3 | PARTLY |
| T-4 | PARTLY |
| T-5 | NO |
| T-6 | NO |
| T-7 | PARTLY |
| T-8 | YES |
| T-9 | PARTLY |
| T-10 | YES |
| T-11 | YES |
| T-12 | PARTLY |

Count: YES 3 · NO 2 · PARTLY 7 · NOT BUILT 0 · UNKNOWN 0. The pack and creator top-up parts inside T-1/2/3/7/12 are NOT BUILT.

### 2. Blockers to charging money
- **B1 — Payment taken, Pro not delivered, and no recovery.**
  - Checkout writes no local row (`SubscriptionService.java:350–363`).
  - Pro is only created by the webhook, and there is no pull-reconciliation against Razorpay.
  - Any webhook that cannot be matched to a workspace is acknowledged with 200 and dropped (`RazorpayWebhookController.java:314–324`), so Razorpay will not retry it.
  - The note-key contract between checkout and webhook is untested (M16 GREEN).
- **B2 — Pro delivered without payment.** The renewal safety net extends any lapsed ACTIVE Pro row indefinitely if `pending`/`halted` is lost (T-9).
- **B3 — Wrong price on screen.** The billing page shows ₹4,99,900/month and invoices 100× too high (T-11 #1–2). A brand would see this before and after paying.
- **B4 — Signature verification is untested.** A broken HMAC ships green (M2), and every Razorpay money event trusts it.
- **B5 — Credits can be over-spent.** The lost-update race lets turns run without being charged (T-5). The risk is to credit accounting, not rupees.
- **B6 — Possible double subscription.** A PAST_DUE/HALTED brand can start a second checkout: the guard only blocks ACTIVE Pro (`SubscriptionService.java:343–348`). That can leave two live Razorpay subscriptions.
- **B7 — Webhooks from the other product.** An unknown `plan_id` on a subscription webhook defaults to Pro (`SubscriptionService.java:837–847`). The Razorpay account is shared with Snapsby, so a foreign subscription event that happens to carry `notes.workspaceId` would grant Pro.

### 3. New work found (suggested owners)
| # | Gap | Owner |
|---|---|---|
| N1 | Checkout→webhook reconciliation. Store a PENDING row with `razorpay_subscription_id` at checkout, and poll `subscriptions.fetch` for rows with no webhook. | Vikram |
| N2 | Renewal safety net must check the subscription's status with Razorpay before extending Pro, and must skip Free rows (stop the double reset, period drift and audit noise). | Vikram |
| N3 | Make `AICreditResetJob` idempotent (guard on `lastReset` month) and add catch-up on startup. Reset Pro credits on `subscription.charged`. Add a test for the job. | Vikram |
| N4 | Fix the credit race: `@DynamicUpdate` or `@Version` on `BrandAiCredit`, or move the daily counter into the conditional UPDATE. Add a Testcontainers test for concurrent debit at 1 credit. | Vikram (code), Meera (run the Docker suite) |
| N5 | Add `WebhookSignatureVerifierTest` with a known Razorpay HMAC vector, good and bad. Add a controller test with a real verifier and real bad signature. | Kavya |
| N6 | Add a wire-format test for `RazorpayClient.createPlan`/`createSubscription` (amount in paise, `notes.workspaceId`), using a real client with a mock server, not a mocked SDK. | Kavya |
| N7 | Fix the billing page: priceInr and invoice amount /100; handle `undefined` for NON_NULL-omitted limits (or drop NON_NULL on `PlanDto`). Add a vitest with a real Pro-shaped payload. | Ananya |
| N8 | Checkout guard for PAST_DUE/HALTED rows (block, or cancel the old Razorpay subscription first). | Vikram |
| N9 | Unknown `plan_id` webhook: refuse or log-and-ack instead of defaulting to Pro. | Vikram |
| N10 | Reverse-direction entitlement gate: fail when a `Plan` limit column is displayed without an `Entitlement` constant. Then decide what to do with `trackedCreatorLimit`: enforce or remove. | Priya (gate spec), Kavya (implement) |
| N11 | Database-backed tests for webhook replay and out-of-order status (unique constraints and automatic flush), runnable in CI with Docker. | Meera |
| N12 | Run the T-12 live ₹4,999 test once N1, N2 and N7 land; record the DB rows in the ledger. | Swapnil (payer) + Meera (DB checks) |
| N13 | Creator credits and packs: not in HEAD. Confirm whether the work lives on another branch before anyone answers CR-7/CR-8 as built. | Arjun |
