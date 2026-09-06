# Facts: credits, billing, subscriptions, wallet top-up, payments flags

Verified against HEAD `eac5e58` on branch `feat/meera-creator-phase-e`, 2026-09-05. Every line number was read in this session (explorer sweep + direct re-reads of each symbol the credits spec cites). Nothing below is inferred from names.

Purpose: ground `CREDITS-SPEC.md` and `finance/credits-gtm-plan.md` §8 in what the code actually has.

---

## 1. Brand AI credits (the only credit ledger today)

### `AICreditService` — `service/meera/AICreditService.java`
- Constants L74–88: `DEFAULT_MONTHLY_ALLOTMENT = 100`, `LOYALTY_MONTHLY_ALLOTMENT = 150`, `DAILY_ACTION_HARD_CAP = 500`, `CHARGE_SCOPE = "meera.turn_charged"`, `RELEASE_SCOPE = "meera.turn_released"`.
- Constructor L93: `(BrandAiCreditRepository, IdempotencyService)`. Test builds it the same way (`AICreditServiceTest` L57).
- `ensureInitialized(String workspaceId)` L99 — lazily creates the row (allotment 100, `cycleStart`/`lastReset` = today).
- `tryConsume(workspaceId, cost)` L121: reset daily counter if date changed → 429 `DAILY_ACTION_LIMIT_EXCEEDED` at 500 → bump counter → if `isUnlimited(now)` return → `tryDecrement` → 0 rows = 402 `CREDITS_EXHAUSTED`.
- `tryConsumeForTurn(workspaceId, cost, turnId)` L182 = `tryConsume` + `idempotencyService.executeOnce(turnId, workspaceId, CHARGE_SCOPE, () -> turnId)`.
- `wasCharged(workspaceId, turnId)` L194 = `idempotencyService.isCompleted(turnId, workspaceId, CHARGE_SCOPE)`.
- `release(workspaceId, cost, turnId)` L219–241: blank turnId → 400; `executeOnce(turnId, workspaceId, RELEASE_SCOPE, …)`; `AlreadyCompleted`/`AlreadyInProgress` caught and logged as no-op.
- `doRelease` L243–268, **order matters**: (1) `!isCompleted(turnId, workspaceId, CHARGE_SCOPE)` → WARN "was never charged", return, **before** any credit-row access; (2) `isCompleted(turnId, workspaceId, MeeraSessionService.PERSIST_WRITEBACK_SCOPE)` → WARN, return; (3) `ensureInitialized`, `refundCredits` unless unlimited, `refundDailyActions` if same UTC day.
  - Consequence: calling `release` with a creator user id as `workspaceId` is safe today — guard (1) returns before `ensureInitialized` could try to insert a `brand_ai_credits` row whose FK to `workspaces` would fail. It only writes an `idempotency_keys` row under `meera.turn_released` and logs a WARN.
- `applyEscrowFundedReset` L275 — no caller, no `@EventListener`. The "150 after first funded campaign" on the pricing page never fires.
- `applyPlanAllotment` L293 (does not reset remaining), `resetForNewCycle` L301, `newId()` L310 (unused).
- Never calls `AuditLogService`.

### `BrandAiCredit` — `domain/entity/BrandAiCredit.java`, table `brand_ai_credits` (V14 + V16)
PK `workspace_id VARCHAR(26)` with `FOREIGN KEY … REFERENCES workspaces(id)`; `credits_remaining`, `monthly_allotment`, `cycle_start DATE`, `unlimited_until`, `last_reset DATE`, `first_campaign_at`, `daily_actions_used`, `daily_actions_date`, timestamps. `isUnlimited(Instant)` L137. **A creator has no `workspaces` row, so this table cannot hold a creator balance.**

### `BrandAiCreditRepository` — `repository/BrandAiCreditRepository.java`
`findByWorkspaceId`; `@Modifying @Transactional` JPQL: `tryDecrement(workspaceId, cost)` guarded by `creditsRemaining >= :cost`; `refundCredits(workspaceId, amount)` clamped to `monthlyAllotment` with `CASE WHEN`; `refundDailyActions(workspaceId, amount, today)` floored at 0.

### `AICreditResetJob` — `job/AICreditResetJob.java`
`@Scheduled(cron = "0 0 2 1 * ?", zone = "UTC")` L53, `@SchedulerLock(name = "AICreditResetJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")` L54, `AtomicBoolean running` re-entrancy guard, `workspaceRepository.findIdsByType(WorkspaceType.BRAND)` (`WorkspaceRepository` L47), per-row try/catch, `applyProAllotmentIfActive` then `resetForNewCycle`. Imports: `net.javacrumbs.shedlock.spring.annotation.SchedulerLock` L11.

### Where a turn is charged — `service/meera/MeeraSessionService.java`
- L80 `TURN_CREDIT_COST = 1`; L81 `SEND_TURN_SCOPE = "meera.send_turn"`; L89 `static final String PERSIST_WRITEBACK_SCOPE = "meera.persist_writeback"` (package-visible).
- Constructor L102–111, **ten** args in this order: `AiConversationRepository, AiMessageRepository, WorkspaceRepository, BrandProfileRepository, AICreditService, BrandContextAssembler, StreamTokenService, OnBehalfTokenService, IdempotencyService, CreatorAgentConversationService`. Construction sites in the tree: `MeeraSessionServiceTest` L81 only (plus Spring).
- `startOrResumeForCreator(creatorUserId, userId, displayName, language)` L180.
- `sendTurn(workspaceId, userId, userType, conversationId, content, idempotencyKey)` L280 (public, wraps `doSendTurn` in idempotency).
- `doSendTurn` L308: `messageId = Ulids.newUlid()` L324; `isCreatorTurn = userType == UserType.CREATOR` L332; `if (!isCreatorTurn) creditService.tryConsumeForTurn(workspaceId, TURN_CREDIT_COST, messageId)` L342. **For a creator turn `workspaceId` is the creator's `users.id`** (comment L326–331). USER row saved with `creditsCharged(0)`. Creator branch: `sanitizedContext = Map.of()`.
- `doPersistAssistantWriteback(workspaceId, conversationId, content, metadata, turnId, userType)` L560: `if (userType == CREATOR) creditsCharged = 0;` L575 else `wasCharged ? 1 : 0`; assistant row `.creditsCharged(creditsCharged)`; creator branch then `creatorAgentConversationService.recordTurnForUser(...)`.
- `releaseTurnCredit(String workspaceId, String turnId)` L635 — public, no `@Transactional`, thin pass-through to `creditService.release(workspaceId, TURN_CREDIT_COST, turnId)`. **Does not receive a `UserType`.**

### Refund route — `web/MeeraInternalController.java`
`@PostMapping("/turns/release")` L354: `AiConversation conversation = sessionService.resolveConversation(body.conversationId()); onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, conversation.getWorkspaceId()); sessionService.releaseTurnCredit(conversation.getWorkspaceId(), body.turnId());`. The resolver's return value is **discarded** here, but the writeback route above it (L337) uses `ctx.userType()` from the same call. `OnBehalfAuthResolver.resolveForWorkspace(String, String)` (`security/OnBehalfAuthResolver.java` L86) returns `OnBehalfContext(String userId, String workspaceId, UserType userType, String conversationId)` (L78). `ReleaseTurnRequest(@NotBlank conversationId, @NotBlank turnId)` (`MeeraToolDtos` L166). `AiConversation` has no user-type column (`workspace_id`, `started_by`, timestamps).

### Creator controller — `web/CreatorMeeraController.java` (`/creator/meera`)
- Deps: `MeeraSessionService, CreatorContextService, MeeraStreamProperties, CreatorAgentPreferencesService, MeeraVoiceAiClient, MeeraCreatorFeatureProperties`. `requireFeatureEnabled()` and `requireConsent(creatorUserId)` are **private** (copy, do not call).
- `startSession` L164–172: `profile = creatorContext.requireCreatorProfile(principal)`; `creatorUserId = profile.getUserId()`; returns `new SessionStartResponse(conversation.getId(), status, null, (CreditsSummary) null)`.
- `sendTurn` L196–207: passes `creatorUserId` as the workspaceId arg and `UserType.CREATOR`; builds `new SendTurnResponse(userMessageId, assistantMessageId, streamToken, streamProperties.getPublicChatUrl(), 0, placeholderReply, creatorUserId, onBehalfToken)` — the `0` is the `int creditsRemaining` positional slot.
- `messages` L215; `speak` (`POST /voice/speak`) and `transcribe` (`POST /voice/transcribe`, multipart, `MAX_VOICE_CLIP_BYTES` 10 MB) both return `Map.of("fallback", true)` on any failure rather than an error status.
- `CreatorContextService` (`service/CreatorContextService.java`): `requireCreator(AuthPrincipal)` L34, `requireCreatorProfile(AuthPrincipal)` L47 → `CreatorProfile` (`id` = `creator_profiles.id`, `userId` = `users.id`, `@Column(name="user_id", unique=true, length=26)` L26).
- Security: `SecurityConfig` L227–228 `.requestMatchers("/creator/**").hasRole("CREATOR")` (after the admin matcher). `/internal/**` is mesh-authenticated (L233 comment).
- Feature flag: `MeeraCreatorFeatureProperties` (`config/`) reads `${influora.meera.creator-enabled:true}`; `application.yml` L199 `creator-enabled: ${MEERA_CREATOR_ENABLED:true}`.

### DTOs — `web/dto/meera/MeeraDtos.java`
`CreditsSummary(int remaining, boolean unlimited)` L23; `SessionStartResponse(conversationId, status, brandProfileStatus, CreditsSummary credits)` L26 (`NON_NULL`); `SendTurnRequest(@NotBlank @Size(max=8000) content)` L33; `SendTurnResponse(messageId, assistantMessageId, streamToken, streamUrl, int creditsRemaining, reply, workspaceId, onBehalfToken)` L51; `CreditStatusResponse(creditsRemaining, monthlyAllotment, unlimited, unlimitedUntil, cycleStart, state)` L74.

### Brand credit read route
`MeeraController` `GET /meera/credits` L181–197 → `brandContextService.requireBrandWorkspace(principal)`; state `UNLIMITED | FREE | EXHAUSTED`. **Brand-only; no `/creator/meera/credits` exists.**

---

## 2. Subscriptions, plans, invoices (all brand-only)

- `Subscription` (`subscriptions`): `workspace_id NOT NULL UNIQUE` (FK workspaces), `plan_id`, `status` `SubscriptionStatus {ACTIVE, PAST_DUE, HALTED, CANCELLED}` (no trial), `razorpay_subscription_id`, period start/end, `cancel_at_period_end`, `seats_purchased`, comp fields (V63), `last_webhook_event_at`, `@Version`.
- `Plan` (`plans`): `code` `PlanCode {FREE, PRO}` UNIQUE, `price_inr` (**paise**, 499900 = ₹4,999), `billing_cycle` `{MONTHLY}`, `razorpay_plan_id` (created lazily via `SubscriptionService#ensureRazorpayPlanId`), `fee_bps`, `ai_monthly_allotment` (100 / 400), `seat_limit`, tracked-creator and analytics limits, feature booleans, `active`. **Catalogue lives in the DB, seeded by `V55__seed_plans.sql`; no yml plan config, no plan-id keys in `RazorpayProperties`.**
- `PlanService` (`service/billing/PlanService.java`, read-only): `getByCode`, `getActivePlans`, `getFreePlan`, `getProPlan`.
- `SubscriptionService` (`service/billing/SubscriptionService.java`): `getActivePlanForWorkspace` L178 (non-ACTIVE → Free), `initiateCheckout(workspaceId, planCode)` L270 (`runExclusive`, scope `billing.checkout.pro`, key `PRO_CHECKOUT`; errors `FREE_PLAN_NO_CHECKOUT`, `RAZORPAY_MISCONFIGURED` 503, `PLAN_NOT_AVAILABLE`, `ALREADY_SUBSCRIBED`, `CHECKOUT_IN_PROGRESS`), `cancel` L343, `grantAdminPlan` L407, `applySubscriptionWebhookUpdate` L534, `reconcileAiCreditAllotment` L706, `applyRenewalSafetyNet` L747, `finalizeLapsedCancellation` L787.
- `Invoice` (`invoices`): `amount` int **paise**; GST breakup `base_amount/cgst/sgst/igst DECIMAL(14,2)` rupees; `invoice_number` series `INF/SUB/<FY>/<seq>`; `InvoiceService#generateInvoiceFromWebhook` L159 (only `subscription.charged` generates one).
- `BillingController` (`/billing`): `GET plan|invoices|usage|invoices/{id}/pdf`, `POST checkout|cancel` — every route `requireBrandWorkspace`. `GET /billing/usage` reads `brandAiCreditRepository.findByWorkspaceId` directly (L123–129).
- `AdminBillingController` (`/admin/billing`): `GET subscriptions|metrics`, `POST comp|override` → `grantAdminPlan`. Bare DTOs (admin convention).
- Jobs: `SubscriptionDunningJob` (`0 0 3 * * *` UTC, grace 7 d, PAST_DUE → HALTED, `recordMoneyEvent("SUBSCRIPTION_HALTED", …, "subscription-dunning-halt:" + id)`); `SubscriptionRenewalResetJob` (`0 30 3 * * *`, ACTIVE past period end → renew or finalize cancel; `recordMoneyEvent` with `"subscription-renewal-safetynet:" + id + ":" + epoch`). Both iterate `SubscriptionRepository.findByStatus` **unfiltered by owner type** and call brand-shaped credit resync.

---

## 3. Razorpay

- `RazorpayClient` (`integration/razorpay/RazorpayClient.java`, raw `java.net.http`, no SDK): `isConfigured()` L62, `isFullyConfigured()` L75, `createOrder(BigDecimal amountInRupees, String currency, String receiptId)` L98 → `OrderResult(orderId, status, rawResponse)` L266, `fetchOrder` L133, `createPlan(name, amountInPaise, period)` L160, `createSubscription(razorpayPlanId, totalCount, JSONObject notes)` L199 → `SubscriptionResult(subscriptionId, status, shortUrl)`, `cancelSubscription(id, cancelAtCycleEnd)` L242.
- `RazorpayProperties` (`influora.razorpay`): `keyId`, `keySecret`, `webhookSecret`, `payoutAccountNumber`, `apiBaseUrl`, `payoutApiBaseUrl`, `platformFeePercent` default `15.00` (not in yml). `application.yml` L366–372 binds the first six from `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAYX_ACCOUNT_NUMBER`, `RAZORPAY_API_BASE_URL`, `RAZORPAYX_API_BASE_URL`.
- `RazorpayWebhookController` (`/webhooks/razorpay`, `POST receive(@RequestHeader("X-Razorpay-Signature"), @RequestBody String)` L89): switch L98–154 — `order.paid` → `dispatchFundingEvent`; `payment.captured` → `dispatchFundingEventIfResolvable`; payout events; five `subscription.*` events → `handleSubscriptionEvent`. `WebhookEvent(eventType, entityId, paymentId, Long amountInPaise, currency)` record L438 (`entityId` = order **receipt**).
- `dispatchFundingEvent` L158–168, verbatim shape:
  ```java
  String receipt = event.entityId();
  if (receipt != null && receipt.startsWith(WalletTopUpService.RECEIPT_PREFIX)) {
      String topUpId = receipt.substring(WalletTopUpService.RECEIPT_PREFIX.length());
      walletTopUpService.confirmCredited(topUpId, event.paymentId(), event.amountInPaise(), event.currency());
      return;
  }
  escrowService.confirmFunded(event.entityId(), event.paymentId(), event.amountInPaise(), event.currency());
  ```
  Anything not prefixed `topup:` falls through to escrow. A new receipt prefix must be branched **before** the fallthrough.
- Tests: `test/.../integration/razorpay/RazorpayWebhookControllerTest.java`, `RazorpayClientTest.java`.

---

## 4. Wallet top-up (brand only)

- `WalletController` `POST /wallet/topup` L95–110: requires `Idempotency-Key` header, `@Valid WalletTopUpRequest(amount 1.00–1,000,000.00, pan?, gstin?)` (`MoneyDtos` L97), calls `walletTopUpService.initiateTopUp(principal, amount, pan, gstin, idempotencyKey)`. `getBalance`/`getSummary`/`transactions` branch on `UserType.CREATOR`; **`topUp` does not**.
- `WalletTopUpService` (`service/WalletTopUpService.java`): `RECEIPT_PREFIX = "topup:"` L62; deps `WalletTopUpRepository, WalletService, WalletLedgerService, PlatformWalletService, BrandContextService, WorkspaceRepository, RazorpayClient, WalletProperties`. `initiateTopUp` L98 starts with `brandContext.requireBrandWorkspace(principal)` + `requireMember` + `requireRole(OWNER, ADMIN)` → **a creator is rejected**. Then `INVALID_TOPUP_AMOUNT`, `TOPUP_LIMIT_EXCEEDED` (`walletProperties.getMaxTopupAmount()`, yml `WALLET_MAX_TOPUP_AMOUNT` 1,000,000), `IDEMPOTENCY_KEY_REQUIRED`, `topUpRepository.findByIdempotencyKey` replay / `IDEMPOTENCY_KEY_CONFLICT`, save row, `razorpayClient.createOrder(amount, "INR", RECEIPT_PREFIX + topUp.getId())`, persist `razorpayOrderId`.
- `confirmCredited(topUpId, gatewayRef, Long webhookAmountInPaise, String webhookCurrency)` L176: `TOPUP_NOT_FOUND` 404; already `CREDITED` → return (idempotent); `validateWebhookAmount` (missing amount/currency → log.error and reject); ledger post with key `RECEIPT_PREFIX + topUp.getId()` (**never** the client header); `topUp.markCredited(creditLegId)`.
- `WalletTopUp` (`wallet_topups`): `id`, `workspace_id`, `amount DECIMAL(14,2)`, `currency(3)`, `status` `WalletTopUpStatus {PENDING, CREDITED}`, `razorpay_order_id(64)`, `credit_txn_id(26)`, `idempotency_key(64) NOT NULL`, `credited_at`, timestamps; `markCredited(String)`, builder. `WalletTopUpRepository`: `findByIdempotencyKey`, `findByCreatedAtBetween`.
- FE: `api.ts` L3504 `topUp(body, idempotencyKey)`; `src/lib/razorpay.ts` exports `loadRazorpayScript`, `getRazorpayKeyId`, `openRazorpayCheckout(OpenCheckoutParams)` L152; used by `src/pages/brand-wallet.tsx` and `FundEscrowButton.tsx`.

---

## 5. Payments flags

Backend has **no** payments-in/out flag; the only server gate is `RazorpayClient.isConfigured()`/`isFullyConfigured()`. Frontend: `src/lib/api.ts` L69 `export type MoneyOperation = 'topup' | 'withdraw' | 'escrow-fund';` L76 `PAYMENTS_IN_ENABLED` from `VITE_PAYMENTS_IN_ENABLED`, L109 `PAYOUTS_ENABLED` from `VITE_PAYOUTS_ENABLED`; L119–122 `isMoneyActionBlocked(op)` → `op === 'withdraw' ? !PAYOUTS_ENABLED : !PAYMENTS_IN_ENABLED`; `PaymentsUnavailableError` L164; `requirePaymentsEnabled` L175. `.env.production`: payments-in `true`, payouts `false`. Any new operation name is gated by payments-in by construction.

---

## 6. Creator-side spend control today (USD cap, Python)

- `CreatorAgentPreferences.aiMonthlyCapUsd` (`DECIMAL(6,2)`, migration `V20260903150000`), admin-only write `PUT /admin/creator-agent/creators/{creatorId}/monthly-cap` (`AdminCreatorAgentController` L52, `creatorId` = **profile id**), service `adminSetMonthlyCapOverride` (`CreatorAgentPreferencesService` L265). Java never gates on it; it rides into the context payload as a string and Python enforces.
- Python: `spend_tracker.py` `CREATOR_MONTHLY_CAP_USD = Decimal("0.75")` L80, `CREATOR_CAP_CODE = "CREATOR_MONTHLY_CAP_REACHED"` L93, `check_creator_spend_gate(creator_id, audience, *, reserve_usd, reserve_ttl_seconds, cap_usd)` L535 (no-op unless `audience.upper() == "CREATOR"`; `cap <= 0` disables), `record_creator_spend` L476, `release()` L747. `config.py` L455 `ai_creator_monthly_cap_usd` (env `AI_CREATOR_MONTHLY_CAP_USD`). `chat.py`: gate call L528, `CREATOR_CAP_CODE` L253, `_creator_cap_response` returns **HTTP 429** (L263–274), `release_charge` L619, refund ladder L852–952 (disconnect keeps the charge; empty/provider failure refunds).
- Python → Spring: `persist_assistant_message` metadata = `{prompt_version, token_usage, request_id}` (`chat.py` L896–906). **No `cost_usd` crosses to Spring.** `release_turn_credit` posts `{conversationId, turnId}` to `/internal/meera/turns/release` with no idempotency header (`spring.py` L344–379).

---

## 7. Idempotency, audit, notifications, precedents

- `IdempotencyService` (`service/IdempotencyService.java`): `executeOnce(key, ownerId, scope, Supplier)` L116; overload with `Function<T,String> resultDigestFn` L131; `findCompletedResultDigest(key, ownerId, scope)` L175; `isCompleted` L191; `runExclusive` L266 (deletes on success, so `isCompleted` never resolves for it). Composite = `scope + ":" + ownerId + ":" + key`, max 128 chars. Writes go through `IdempotencyReservationOps` (`REQUIRES_NEW`). Storage `idempotency_keys` (V15).
- `AuditLogService.recordMoneyEvent(workspaceId, eventType, BigDecimal serverAmount, BigDecimal beforeBalance, BigDecimal afterBalance, String idempotencyKey, Map<String,Object> detail)` L110, `REQUIRES_NEW`. Existing eventTypes: `SUBSCRIPTION_HALTED`, `SUBSCRIPTION_RENEWAL_SAFETY_NET`, `SUBSCRIPTION_CANCELLATION_FINALIZED`, plus affiliate/conversion/redemption. **No credit event types exist.**
- `NotificationEvent` sealed permits list (`service/notification/event/NotificationEvent.java`) includes `CreditsExhaustedEvent(userId, workspaceId, entityId)` (`"ai.credits_exhausted"`) and `CreditsResetEvent(…, int newAllotment)`. `NotificationListener` L647/L660 handles both (deep link `/brand/meera/credits`, email key `brand.credits_exhausted`). **Nothing publishes either.** Last permits entry `CreatorNotConnectedEvent` has no trailing comma.
- Precedents: `AffiliateEarning` (`affiliate_earnings`, `UNIQUE(redemption_id)` + `UNIQUE(idempotency_key)`, `PENDING→SETTLED|FAILED`) for "earned" balances; `CouponCode` is a brand discount code, **not** a platform credit; no referral table; no `CreatorCoupon` entity.

---

## 8. Frontend surfaces

- `src/lib/meera-api.ts`: `MeeraRole` with `basePath(role)` L432 → `'/creator/meera' | '/meera'`; `startSession(role)` L519; `sendTurn(conversationId, text, role)` L536; `getCredits()` L572 hits `/meera/credits` with **no role arg**; `MeeraCreditStatus` L99–106; `speak`/`transcribe` take a role.
- `src/components/creator/MeeraCopilotChat.tsx`: `CAP_REACHED_ERROR_CODE = 'CREATOR_MONTHLY_CAP_REACHED'` L38; `sendTurn(…, 'creator')` L218; stream `onError` cap branch L266; non-stream `.catch` L301–313 renders `err.message` for the cap code, generic text otherwise. Messages are `{id, role, text}` strings. **No credit code anywhere on the creator side** (`creator-copilot.tsx` header `<h1>Co-pilot</h1>` L121, chat mounted L154).
- `src/hooks/useMeeraCredits.ts` (brand, `LOW_CREDITS_THRESHOLD = 10`, state machine), `CreditMeter.tsx` (unused orphan), `CreditPaywall.tsx` (wired in `MeeraChatPanel` on `CREDITS_EXHAUSTED`).
- `src/pages/pricing.tsx` L72–78 `CREATOR_INCLUDED` = five "free" bullets; matrix row "AI credits/month 100 → 150 / 400" is brand-only. File header records CTO copy rulings (no fee digits; only ₹4,999 shown).
- Creator routes in `src/App.tsx`: `/creator/dashboard` L535, `/creator/deals` L551, `/creator/copilot` L562, `/creator/wallet` L570 (protected block).
- Admin `src/admin/services/api-contracts.ts` `billingApi` L974–1028 (`/billing/subscriptions|metrics|comp|override`).

---

## 9. Migrations

Highest on disk: `V20260903170000__creator_agent_preferences_timezone_currency.sql`. Phase B reserved `V20260910100000`–`V20260910100700` (spec only, not on disk). `V20260912*` is free and is what `CREDITS-SPEC.md` uses. Re-check `ls db/migration | grep ^V2026 | sort | tail` on build day.

---

## 10. What a creator credit ledger can reuse (verified)

1. `IdempotencyService.executeOnce` + `isCompleted` + `findCompletedResultDigest` with **new scopes** — no schema change, no collision with brand scopes.
2. The charge-at-send / guarded-release shape and its two guards, verbatim from `AICreditService`.
3. The `tryDecrement … WHERE remaining >= :cost` and clamped-refund JPQL patterns.
4. The creator branch already exists at `doSendTurn` L332/L342 and `doPersistAssistantWriteback` L575; the server-minted `messageId` is already generated for creator turns.
5. Python's refund ladder is audience-agnostic and already posts to `/turns/release` for creator turns; today it lands as a WARN no-op.
6. `AICreditResetJob` as the cron/ShedLock template.
7. `RazorpayClient.createOrder` + receipt-prefix routing in `dispatchFundingEvent` + `confirmCredited`'s amount cross-check and server-derived ledger key.
8. `AuditLogService.recordMoneyEvent` (`REQUIRES_NEW`).
9. FE: `openRazorpayCheckout`, `isMoneyActionBlocked`, `useMeeraCredits` state machine, `CreditPaywall`, and the `ApiError.code` branch pattern in `MeeraCopilotChat`.

## 11. What must be built (verified gaps)

Creator balance table (no `workspaces` FK), a ledger with reasons, signup grant, pack catalogue + orders, cost-aware debit (turn 1 / brief 3 / voice 2), a Spring-side gate before the stream token, `GET /creator/meera/credits`, real values in `SessionStartResponse.credits` and `SendTurnResponse.creditsRemaining`, a user-type-aware release path, a creator reset job, a `credits:` receipt branch in the webhook, credit audit events, admin grant route, config properties bound in yml, and every creator-facing surface (badge, exhausted state, packs page, pricing copy). Creator **subscriptions** are out of scope for v1 (Tejas: packs, not subscriptions; `subscriptions.workspace_id` is a NOT NULL FK to workspaces anyway).
